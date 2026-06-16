package org.javacs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Resolves classpath and module structure for Gradle projects by invoking
 * {@code ./gradlew --no-daemon} with a custom init script.
 *
 * <p>No persistent Gradle daemon is left running — a single short-lived subprocess
 * extracts the full multi-project graph and exits. Results are cached to disk keyed on
 * build file fingerprints, so subsequent startups are instant.
 */
public final class GradleTooling {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setLenient().create();
    private static final String MARKER_START = "___JLS_MODULE_GRAPH_START___";
    private static final String MARKER_END = "___JLS_MODULE_GRAPH_END___";

    private GradleTooling() {}

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

        var started = Instant.now();
        LOG.info("[gradle] Resolving module graph via gradlew for " + workspaceRoot);
        try {
            var graph = fetchViaGradlew(workspaceRoot);
            if (graph == ModuleGraph.EMPTY) {
                return graph;
            }
            storeCache(cacheFile, fingerprints, graph);
            LOG.info(String.format(
                    "[gradle] Module graph resolved: %d modules in %dms",
                    graph.modules().size(),
                    Duration.between(started, Instant.now()).toMillis()));
            return graph;
        } catch (Exception e) {
            LOG.warning("[gradle] Resolution failed: " + e.getMessage());
            return ModuleGraph.EMPTY;
        }
    }

    /**
     * Compile the transitive inter-module dependencies of {@code projectPath} so that their
     * compiled classes are available on the classpath.
     */
    public static void compileDependencies(Path workspaceRoot, ModuleGraph graph, String projectPath) {
        var deps = new LinkedHashSet<String>();
        collectModuleDeps(graph, projectPath, deps, new HashSet<>());
        if (deps.isEmpty()) {
            return;
        }
        var tasks = deps.stream()
                .map(dep -> dep.equals(":") ? ":compileJava" : dep + ":compileJava")
                .toList();

        LOG.info(String.format("[gradle] Compiling %d dependency modules for %s", tasks.size(), projectPath));
        var started = java.time.Instant.now();
        try {
            var gradlew = findGradlew(workspaceRoot);
            if (gradlew == null) {
                LOG.warning("[gradle] gradlew not found, cannot compile dependencies");
                return;
            }
            var cmd = new ArrayList<String>();
            cmd.add(gradlew.toString());
            cmd.add("--no-daemon");
            cmd.add("-q");
            cmd.addAll(tasks);

            var process = new ProcessBuilder(cmd)
                    .directory(gradlew.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
            // Drain output to prevent blocking
            process.getInputStream().transferTo(OutputStream.nullOutputStream());
            var exited = process.waitFor(300, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                LOG.warning("[gradle] Dependency compilation timed out");
                return;
            }
            if (process.exitValue() == 0) {
                LOG.info(String.format("[gradle] Dependency compilation complete: %d modules in %dms",
                        tasks.size(),
                        java.time.Duration.between(started, java.time.Instant.now()).toMillis()));
            } else {
                LOG.warning("[gradle] Dependency compilation failed with exit code " + process.exitValue());
            }
        } catch (Exception e) {
            LOG.warning("[gradle] Dependency compilation failed: " + e.getMessage());
        }
    }

    /** No-op — no persistent daemon to shut down with the subprocess approach. */
    public static synchronized void shutdown(Path workspaceRoot) {
        // ponytail: nothing to do, --no-daemon means no persistent process
    }

    // -------------------------------------------------------------------------
    // Subprocess invocation
    // -------------------------------------------------------------------------

    private static ModuleGraph fetchViaGradlew(Path workspaceRoot) throws IOException, InterruptedException {
        var gradlew = findGradlew(workspaceRoot);
        if (gradlew == null) {
            LOG.warning("[gradle] gradlew not found in " + workspaceRoot);
            return ModuleGraph.EMPTY;
        }

        // Write init script to a temp file
        var initScript = extractInitScript();
        try {
            var cmd = List.of(
                    gradlew.toString(),
                    "--no-daemon",
                    "--init-script", initScript.toString(),
                    "-q",
                    "jlsProjectInfo");

            var process = new ProcessBuilder(cmd)
                    .directory(gradlew.getParent().toFile())
                    .redirectErrorStream(false)
                    .start();

            // Read stdout for JSON, let stderr drain
            var stdoutFuture = readStream(process.getInputStream());
            var stderrDrain = Thread.startVirtualThread(() -> {
                try { process.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
                catch (IOException ignored) {}
            });

            var exited = process.waitFor(120, TimeUnit.SECONDS);
            stderrDrain.join(1000);
            if (!exited) {
                process.destroyForcibly();
                LOG.warning("[gradle] gradlew timed out after 120s");
                return ModuleGraph.EMPTY;
            }

            var stdout = stdoutFuture;
            if (process.exitValue() != 0) {
                LOG.warning("[gradle] gradlew exited with code " + process.exitValue());
                return ModuleGraph.EMPTY;
            }

            return parseModuleGraphOutput(stdout);
        } finally {
            Files.deleteIfExists(initScript);
        }
    }

    private static String readStream(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static ModuleGraph parseModuleGraphOutput(String output) {
        var startIdx = output.indexOf(MARKER_START);
        var endIdx = output.indexOf(MARKER_END);
        if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx) {
            LOG.warning("[gradle] Could not find module graph markers in gradlew output");
            return ModuleGraph.EMPTY;
        }
        var json = output.substring(startIdx + MARKER_START.length(), endIdx).trim();
        try {
            var parsed = GSON.fromJson(json, GradlewOutput.class);
            if (parsed == null || parsed.modules == null) {
                return ModuleGraph.EMPTY;
            }
            var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
            for (var m : parsed.modules) {
                var info = new ModuleGraph.ModuleInfo(
                        m.projectPath,
                        Paths.get(m.projectDir),
                        m.sourceDirs == null ? List.of() : m.sourceDirs.stream().map(Paths::get).toList(),
                        m.externalClasspath == null ? List.of() : m.externalClasspath.stream().map(Paths::get).toList(),
                        m.moduleDeps == null ? List.of() : List.copyOf(m.moduleDeps),
                        m.sourceCompatibility);
                modules.put(m.projectPath, info);
            }
            return new ModuleGraph(Collections.unmodifiableMap(modules));
        } catch (JsonParseException e) {
            LOG.warning("[gradle] Failed to parse module graph JSON: " + e.getMessage());
            return ModuleGraph.EMPTY;
        }
    }

    /** JSON structure emitted by the init script */
    private static final class GradlewOutput {
        List<GradlewModule> modules;
    }

    private static final class GradlewModule {
        String projectPath;
        String projectDir;
        List<String> sourceDirs;
        List<String> externalClasspath;
        List<String> moduleDeps;
        String sourceCompatibility;
    }

    private static Path extractInitScript() throws IOException {
        var tmp = Files.createTempFile("jls-gradle-init", ".gradle");
        try (var is = GradleTooling.class.getResourceAsStream("/jls-gradle-init.gradle")) {
            if (is == null) {
                throw new IOException("jls-gradle-init.gradle not found in classpath");
            }
            Files.write(tmp, is.readAllBytes());
        }
        return tmp;
    }

    // -------------------------------------------------------------------------
    // Module dependency traversal
    // -------------------------------------------------------------------------

    private static void collectModuleDeps(ModuleGraph graph, String projectPath,
            Set<String> result, Set<String> visited) {
        if (!visited.add(projectPath)) return;
        var info = graph.modules().get(projectPath);
        if (info == null) return;
        for (var dep : info.moduleDeps()) {
            if (graph.modules().containsKey(dep)) {
                result.add(dep);
                collectModuleDeps(graph, dep, result, visited);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Disk cache
    // -------------------------------------------------------------------------

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

    private static Path findGradlew(Path workspaceRoot) {
        var isWindows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        var name = isWindows ? "gradlew.bat" : "gradlew";
        // Check workspace root first, then walk up
        for (var dir = workspaceRoot; dir != null; dir = dir.getParent()) {
            var candidate = dir.resolve(name);
            if (Files.isExecutable(candidate)) return candidate;
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
}
