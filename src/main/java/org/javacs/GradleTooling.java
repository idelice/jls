package org.javacs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.eclipse.EclipseProject;

/**
 * Resolves classpath and module structure for Gradle projects using the Gradle Tooling API.
 *
 * <p>A single {@code EclipseProject} model fetch from the root project returns the full
 * multi-project tree in one Gradle daemon call. Results are cached to disk keyed on
 * {@code settings.gradle} + all {@code build.gradle} mtimes, and invalidated automatically
 * when any build file changes.
 */
public final class GradleTooling {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // Singleton connection — one per server lifetime. Avoids spawning multiple Gradle daemons.
    private static org.gradle.tooling.ProjectConnection sharedConnection;
    private static Path sharedConnectionRoot;

    private GradleTooling() {}

    /** Get or create the shared Gradle Tooling API connection for the given project root. */
    private static synchronized ProjectConnection getConnection(Path buildRoot) {
        if (sharedConnection != null && buildRoot.equals(sharedConnectionRoot)) {
            return sharedConnection;
        }
        if (sharedConnection != null) {
            try { sharedConnection.close(); } catch (Exception ignored) {}
        }
        sharedConnection = GradleConnector.newConnector()
                .forProjectDirectory(buildRoot.toFile())
                .useBuildDistribution()
                .connect();
        sharedConnectionRoot = buildRoot;
        return sharedConnection;
    }

    /** Close the shared connection and stop the Gradle daemon. Called on server shutdown. */
    public static synchronized void shutdown(Path workspaceRoot) {
        if (sharedConnection != null) {
            try { sharedConnection.close(); } catch (Exception ignored) {}
            sharedConnection = null;
            sharedConnectionRoot = null;
        }
        stopDaemon(workspaceRoot);
    }

    /**
     * Resolve the module graph for a Gradle workspace, using a disk cache.
     *
     * <p>Returns {@link ModuleGraph#EMPTY} if the workspace is not a Gradle project or if
     * resolution fails.
     */
    public static ModuleGraph resolveModuleGraph(Path workspaceRoot, Path cacheHome) {
        var settingsGradle = findSettingsGradle(workspaceRoot);
        if (settingsGradle == null) {
            return ModuleGraph.EMPTY;
        }

        var cacheFile = cacheFile(workspaceRoot, cacheHome);
        var fingerprints = buildFileFingerprints(workspaceRoot);

        // Try cache first
        var cached = loadCache(cacheFile, fingerprints);
        if (cached != null) {
            CacheAudit.hit("gradle_tooling.module_graph");
            return cached;
        }
        CacheAudit.miss("gradle_tooling.module_graph");

        var started = java.time.Instant.now();
        LOG.info("[gradle] Resolving module graph via Tooling API for " + workspaceRoot);
        try {
            var graph = fetchFromGradle(workspaceRoot);
            storeCache(cacheFile, fingerprints, graph);
            LOG.info(String.format(
                    "[gradle] Module graph resolved: %d modules in %dms",
                    graph.modules().size(),
                    java.time.Duration.between(started, java.time.Instant.now()).toMillis()));
            return graph;
        } catch (Exception e) {
            LOG.warning("[gradle] Tooling API resolution failed: " + e.getMessage());
            return ModuleGraph.EMPTY;
        }
    }

    private static ModuleGraph fetchFromGradle(Path workspaceRoot) {
        var connection = getConnection(workspaceRoot);
        var root = connection.getModel(EclipseProject.class);
        var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        collectModules(root, modules);
        return new ModuleGraph(Collections.unmodifiableMap(modules));
    }

    /**
     * Compile the transitive inter-module dependencies of {@code projectPath} so that their
     * compiled classes are available on the classpath. Without this, javac cannot resolve types
     * from other modules in an un-built project.
     *
     * <p>This is analogous to how Maven's {@code mvn compile} is required before type resolution
     * works across modules.
     */
    public static void compileDependencies(Path workspaceRoot, ModuleGraph graph, String projectPath) {
        var deps = new LinkedHashSet<String>();
        collectModuleDeps(graph, projectPath, deps, new HashSet<>());
        if (deps.isEmpty()) {
            return;
        }
        // Build task list: :module:compileJava for each transitive dep
        var tasks = deps.stream()
                .map(dep -> dep.equals(":") ? ":compileJava" : dep + ":compileJava")
                .toArray(String[]::new);

        LOG.info(String.format("[gradle] Compiling %d dependency modules for %s", tasks.length, projectPath));
        var started = java.time.Instant.now();
        try {
            var connection = getConnection(workspaceRoot);
            var errorCapture = new java.io.ByteArrayOutputStream();
            connection.newBuild()
                    .forTasks(tasks)
                    .setStandardOutput(java.io.OutputStream.nullOutputStream())
                    .setStandardError(errorCapture)
                    .run();
            LOG.info(String.format("[gradle] Dependency compilation complete: %d modules in %dms",
                    tasks.length,
                    java.time.Duration.between(started, java.time.Instant.now()).toMillis()));
        } catch (Exception e) {
            LOG.warning("[gradle] Dependency compilation failed: " + e.getMessage());
            var cause = e.getCause();
            while (cause != null) {
                LOG.warning("[gradle]   caused by: " + cause.getMessage());
                cause = cause.getCause();
            }
        }
    }

    private static void collectModuleDeps(ModuleGraph graph, String projectPath,
            Set<String> result, Set<String> visited) {
        if (!visited.add(projectPath)) return;
        var info = graph.modules().get(projectPath);
        if (info == null) return;
        for (var dep : info.moduleDeps()) {
            // Only include deps that exist in our module graph — composite/included builds
            // have project deps that aren't addressable via the root build's task paths.
            if (graph.modules().containsKey(dep)) {
                result.add(dep);
                collectModuleDeps(graph, dep, result, visited);
            }
        }
    }

    private static void collectModules(EclipseProject project, Map<String, ModuleGraph.ModuleInfo> modules) {
        var gradleProject = project.getGradleProject();
        var projectPath = gradleProject.getPath();
        var projectDir = project.getProjectDirectory().toPath().toAbsolutePath().normalize();

        // Source directories
        var sourceDirs = new ArrayList<Path>();
        for (var srcDir : project.getSourceDirectories()) {
            var dir = srcDir.getDirectory().toPath().toAbsolutePath().normalize();
            if (Files.exists(dir)) {
                sourceDirs.add(dir);
            }
        }

        // External JAR dependencies
        var externalClasspath = new ArrayList<Path>();
        for (var dep : project.getClasspath()) {
            var file = dep.getFile().toPath().toAbsolutePath().normalize();
            if (Files.exists(file)) {
                externalClasspath.add(file);
            }
        }

        // Inter-module dependencies
        var moduleDeps = new ArrayList<String>();
        for (var dep : project.getProjectDependencies()) {
            moduleDeps.add(dep.getPath());
        }

        // Source compatibility level
        String sourceCompatibility = null;
        try {
            var javaSettings = project.getJavaSourceSettings();
            if (javaSettings != null && javaSettings.getSourceLanguageLevel() != null) {
                sourceCompatibility = javaSettings.getSourceLanguageLevel().getMajorVersion();
            }
        } catch (Exception ignored) {
            // Older Gradle versions may not support getJavaSourceSettings
        }

        modules.put(projectPath, new ModuleGraph.ModuleInfo(
                projectPath, projectDir,
                List.copyOf(sourceDirs),
                List.copyOf(externalClasspath),
                List.copyOf(moduleDeps),
                sourceCompatibility));

        for (var child : project.getChildren()) {
            collectModules(child, modules);
        }
    }

    // -------------------------------------------------------------------------
    // Disk cache
    // -------------------------------------------------------------------------

    /** Serializable form of ModuleGraph for Gson */
    private static final class CacheFile {
        List<String> fingerprints;
        List<CachedModule> modules;
    }

    private static final class CachedModule {
        String projectPath;
        String projectDir;
        List<String> sourceDirs;
        List<String> externalClasspath;
        List<String> moduleDeps;
        String sourceCompatibility;
    }

    private static ModuleGraph loadCache(Path cacheFile, List<String> fingerprints) {
        if (!Files.exists(cacheFile)) return null;
        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            var cached = GSON.fromJson(reader, CacheFile.class);
            if (cached == null || !Objects.equals(cached.fingerprints, fingerprints)) return null;
            var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
            for (var m : cached.modules) {
                var info = new ModuleGraph.ModuleInfo(
                        m.projectPath,
                        Paths.get(m.projectDir),
                        m.sourceDirs.stream().map(Paths::get).toList(),
                        m.externalClasspath.stream().map(Paths::get).toList(),
                        List.copyOf(m.moduleDeps),
                        m.sourceCompatibility);
                modules.put(m.projectPath, info);
            }
            return new ModuleGraph(Collections.unmodifiableMap(modules));
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    private static void storeCache(Path cacheFile, List<String> fingerprints, ModuleGraph graph) {
        var file = new CacheFile();
        file.fingerprints = fingerprints;
        file.modules = new ArrayList<>();
        for (var info : graph.modules().values()) {
            var m = new CachedModule();
            m.projectPath = info.projectPath();
            m.projectDir = info.projectDir().toString();
            m.sourceDirs = info.sourceDirs().stream().map(Path::toString).toList();
            m.externalClasspath = info.externalClasspath().stream().map(Path::toString).toList();
            m.moduleDeps = List.copyOf(info.moduleDeps());
            m.sourceCompatibility = info.sourceCompatibility();
            file.modules.add(m);
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(file, writer);
            }
        } catch (IOException e) {
            LOG.fine("[gradle] Failed to write module graph cache: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path findSettingsGradle(Path workspaceRoot) {
        for (var name : List.of("settings.gradle", "settings.gradle.kts")) {
            var candidate = workspaceRoot.resolve(name);
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Fingerprints of all build.gradle / settings.gradle files in the workspace,
     * sorted for stable cache key comparison.
     */
    static List<String> buildFileFingerprints(Path workspaceRoot) {
        var buildFileNames = Set.of(
                "build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts");
        try (Stream<Path> files = Files.walk(workspaceRoot, 10)) {
            return files
                    .filter(p -> buildFileNames.contains(p.getFileName().toString()))
                    .filter(p -> {
                        // Skip build output directories
                        for (var part : p) {
                            var s = part.toString();
                            if (s.equals("build") || s.equals(".gradle")) return false;
                        }
                        return true;
                    })
                    .sorted()
                    .map(p -> {
                        try {
                            return p.toAbsolutePath().normalize() + ":"
                                    + Files.getLastModifiedTime(p).toMillis() + ":"
                                    + Files.size(p);
                        } catch (IOException e) {
                            return p.toString();
                        }
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path cacheFile(Path workspaceRoot, Path cacheHome) {
        var name = workspaceRoot.getFileName() == null ? "workspace" : workspaceRoot.getFileName().toString();
        var hash = shortHash(workspaceRoot.toAbsolutePath().normalize().toString());
        return cacheHome.resolve("jls").resolve(name + "-" + hash).resolve("gradle-module-graph.json");
    }

    private static String shortHash(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 8);
    }

    /** Stop the Gradle daemon that was started by the Tooling API. */
    public static void stopDaemon(Path workspaceRoot) {
        try {
            var gradlew = workspaceRoot.resolve("gradlew");
            if (!Files.exists(gradlew)) {
                // Walk up to find it
                for (var dir = workspaceRoot; dir != null; dir = dir.getParent()) {
                    if (Files.exists(dir.resolve("gradlew"))) {
                        gradlew = dir.resolve("gradlew");
                        break;
                    }
                }
            }
            if (Files.exists(gradlew)) {
                new ProcessBuilder(gradlew.toString(), "--stop")
                        .directory(gradlew.getParent().toFile())
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(5, TimeUnit.SECONDS);
                LOG.info("[gradle] Daemon stopped");
            }
        } catch (Exception e) {
            LOG.fine("[gradle] Failed to stop daemon: " + e.getMessage());
        }
    }
}
