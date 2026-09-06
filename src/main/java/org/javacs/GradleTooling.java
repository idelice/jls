package org.javacs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Two-phase lazy Gradle resolution with per-module caching.
 *
 * <p>Phase 1 ({@link #resolveModuleGraph}) resolves project structure only (module paths,
 * source dirs, inter-module deps). Lightweight, typically &lt;5s.
 *
 * <p>Phase 2 ({@link #resolveClasspath}) resolves external classpath for specific modules
 * on demand. Cached per-module so only stale modules are re-resolved.
 *
 * <p>Cache location: {@code ~/.cache/jls/gradle/<project-fingerprint>/}
 */
public final class GradleTooling {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setLenient().create();
    private static final String GRAPH_MARKER_START = "___JLS_MODULE_GRAPH_START___";
    private static final String GRAPH_MARKER_END = "___JLS_MODULE_GRAPH_END___";
    private static final String CP_MARKER_START = "___JLS_CLASSPATH_START___";
    private static final String CP_MARKER_END = "___JLS_CLASSPATH_END___";
    private static final long TIMEOUT_SECONDS = 90;

    private GradleTooling() {}

    // =========================================================================
    // Public API — Phase 2 result types
    // =========================================================================

    public record ModuleClasspath(Map<String, ResolvedModule> modules) {
        public static final ModuleClasspath EMPTY = new ModuleClasspath(Map.of());
    }

    public record ResolvedModule(List<Path> externalClasspath, List<Path> testClasspath) {}

    // =========================================================================
    // Phase 1 — Module Graph (structure only, no classpath resolution)
    // =========================================================================

    /** Resolve module graph with default cache home (~/.cache). Backwards compat for single-arg callers. */
    public static ModuleGraph resolveModuleGraph(Path workspaceRoot) {
        return resolveModuleGraph(workspaceRoot, defaultCacheHome());
    }

    /** Resolve module graph with explicit cache home. Used by InferConfig. */
    public static ModuleGraph resolveModuleGraph(Path workspaceRoot, Path cacheHome) {
        if (findSettingsGradle(workspaceRoot) == null) return ModuleGraph.EMPTY;

        var fingerprint = computeProjectFingerprint(workspaceRoot);
        if (fingerprint == null) {
            LOG.warning("[gradle] cannot compute project fingerprint");
            return ModuleGraph.EMPTY;
        }

        var cacheDir = cacheDir(cacheHome, workspaceRoot, fingerprint);
        var graphFile = cacheDir.resolve("graph.json");

        // Check cache
        if (Files.exists(graphFile)) {
            var cached = loadGraphCache(graphFile);
            if (cached != null) {
                LOG.info("[gradle] phase1 cache hit: " + cached.modules().size() + " modules");
                return cached;
            }
        }

        LOG.info("[gradle] phase1 cache miss — running gradlew");
        var started = Instant.now();
        try {
            var graph = runPhase1(workspaceRoot);
            if (graph == ModuleGraph.EMPTY) return graph;
            storeGraphCache(cacheDir, graphFile, fingerprint, graph);
            LOG.info("[gradle] phase1 resolved " + graph.modules().size() + " modules in "
                    + Duration.between(started, Instant.now()).toMillis() + "ms");
            return graph;
        } catch (Exception e) {
            LOG.warning("[gradle] phase1 failed: " + e.getMessage());
            return ModuleGraph.EMPTY;
        }
    }

    // =========================================================================
    // Phase 2 — Per-module classpath resolution
    // =========================================================================

    /** Resolve classpath for specific modules with default cache home. */
    public static ModuleClasspath resolveClasspath(Path workspaceRoot, List<String> moduleProjectPaths) {
        return resolveClasspath(workspaceRoot, moduleProjectPaths, defaultCacheHome());
    }

    /** Resolve classpath for specific modules with explicit cache home. */
    public static ModuleClasspath resolveClasspath(Path workspaceRoot, List<String> moduleProjectPaths, Path cacheHome) {
        if (moduleProjectPaths == null || moduleProjectPaths.isEmpty()) return ModuleClasspath.EMPTY;
        if (findSettingsGradle(workspaceRoot) == null) return ModuleClasspath.EMPTY;

        var fingerprint = computeProjectFingerprint(workspaceRoot);
        if (fingerprint == null) return ModuleClasspath.EMPTY;

        var cacheDir = cacheDir(cacheHome, workspaceRoot, fingerprint);
        var modulesDir = cacheDir.resolve("modules");

        var result = new LinkedHashMap<String, ResolvedModule>();
        var uncached = new ArrayList<String>();

        // Check per-module cache
        for (var modulePath : moduleProjectPaths) {
            var moduleFile = modulesDir.resolve(modulePathHash(modulePath) + ".json");
            if (Files.exists(moduleFile)) {
                var cached = loadModuleCache(moduleFile, workspaceRoot, modulePath);
                if (cached != null) {
                    LOG.info("[gradle] phase2 cache hit: " + modulePath);
                    result.put(modulePath, cached);
                    continue;
                }
            }
            LOG.info("[gradle] phase2 cache miss: " + modulePath);
            uncached.add(modulePath);
        }

        if (uncached.isEmpty()) return new ModuleClasspath(Collections.unmodifiableMap(result));

        // Resolve uncached modules
        LOG.info("[gradle] phase2 resolving " + uncached.size() + " modules");
        var started = Instant.now();
        try {
            var resolved = runPhase2(workspaceRoot, uncached);
            // Save each resolved module to cache
            for (var entry : resolved.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
                storeModuleCache(modulesDir, workspaceRoot, entry.getKey(), entry.getValue());
            }
            LOG.info("[gradle] phase2 resolved " + resolved.size() + " modules in "
                    + Duration.between(started, Instant.now()).toMillis() + "ms");
        } catch (Exception e) {
            LOG.warning("[gradle] phase2 failed: " + e.getMessage());
        }

        return new ModuleClasspath(Collections.unmodifiableMap(result));
    }

    // =========================================================================
    // Phase 1 — Gradle execution
    // =========================================================================

    private static ModuleGraph runPhase1(Path workspaceRoot) throws IOException, InterruptedException {
        var gradle = findGradleExecutable(workspaceRoot);
        if (gradle == null) {
            LOG.warning("[gradle] no gradle executable found");
            return ModuleGraph.EMPTY;
        }
        var initScript = extractResource("/jls-gradle-graph.gradle");
        if (initScript == null) throw new IOException("Missing Gradle graph resource");
        var outputDir = Files.createTempDirectory("jls-gradle-graph");
        try {
            var cmd = List.of(gradle.toString(), "--no-daemon",
                    "--init-script", initScript.toString(), "-q",
                    "-Pjls.outputDir=" + outputDir, "jlsModuleGraph");
            LOG.info("[gradle] phase1 exec: " + String.join(" ", cmd));
            executeGradle(cmd, workspaceRoot, "phase1");
            var modules = new ArrayList<GraphModule>();
            for (var json : readResults(outputDir, "phase1")) {
                var module = GSON.fromJson(json, GraphModule.class);
                if (module != null && module.projectPath != null) modules.add(module);
            }
            return toModuleGraph(modules);
        } catch (JsonParseException e) {
            LOG.warning("[gradle] phase1 parse failed: " + e.getMessage());
            return ModuleGraph.EMPTY;
        } finally {
            Files.deleteIfExists(initScript);
            deleteRecursively(outputDir);
        }
    }

    /** Each project writes one JSON file: parallel builds interleave stdout. */
    private static List<String> readResults(Path outputDir, String phase) throws IOException {
        if (!Files.isDirectory(outputDir)) return List.of();
        var results = new ArrayList<String>();
        try (var files = Files.list(outputDir)) {
            for (var file : files.sorted().toList()) {
                results.add(Files.readString(file));
            }
        }
        LOG.info("[gradle] " + phase + " results=" + results.size());
        return results;
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.fine("[gradle] could not remove " + dir + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // Phase 2 — Gradle execution
    // =========================================================================

    private static Map<String, ResolvedModule> runPhase2(Path workspaceRoot, List<String> modules)
            throws IOException, InterruptedException {
        var gradle = findGradleExecutable(workspaceRoot);
        if (gradle == null) {
            LOG.warning("[gradle] no gradle executable found");
            return Map.of();
        }
        var initScript = extractResource("/jls-gradle-classpath.gradle");
        if (initScript == null) throw new IOException("Missing Gradle classpath resource");
        var outputDir = Files.createTempDirectory("jls-gradle-classpath");
        try {
            // Qualified task paths: only the requested projects resolve, and each holds its own lock.
            var cmd = new ArrayList<>(List.of(gradle.toString(), "--no-daemon",
                    "--init-script", initScript.toString(), "-q", "-Pjls.outputDir=" + outputDir));
            for (var module : modules) {
                cmd.add((module.equals(":") ? "" : module) + ":jlsResolveClasspath");
            }
            LOG.info("[gradle] phase2 exec: " + String.join(" ", cmd));
            executeGradle(cmd, workspaceRoot, "phase2");
            return parseClasspathResults(readResults(outputDir, "phase2"));
        } finally {
            Files.deleteIfExists(initScript);
            deleteRecursively(outputDir);
        }
    }

    private static Map<String, ResolvedModule> parseClasspathResults(List<String> results) {
        var result = new LinkedHashMap<String, ResolvedModule>();
        for (var json : results) {
            try {
                var module = GSON.fromJson(json, ClasspathModule.class);
                if (module == null || module.projectPath == null) continue;
                var cp = module.externalClasspath == null ? List.<Path>of()
                        : module.externalClasspath.stream().map(Path::of).toList();
                var testCp = module.testClasspath == null ? List.<Path>of()
                        : module.testClasspath.stream().map(Path::of).toList();
                result.put(module.projectPath, new ResolvedModule(cp, testCp));
            } catch (JsonParseException e) {
                LOG.warning("[gradle] phase2 parse failed: " + e.getMessage());
            }
        }
        return result;
    }

    // =========================================================================
    // Gradle execution helper
    // =========================================================================

    /**
     * Run Gradle and report failures. Results are read from files, so a non-zero exit is only a
     * signal: a build that fails late (for example on deprecation warnings) can still have written
     * usable per-project results.
     */
    private static void executeGradle(List<String> cmd, Path workingDir, String phase)
            throws IOException, InterruptedException {
        var process = new ProcessBuilder(cmd)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            LOG.warning("[gradle] " + phase + " timeout " + TIMEOUT_SECONDS + "s");
            return;
        }
        if (process.exitValue() != 0) {
            LOG.warning("[gradle] " + phase + " exit=" + process.exitValue() + " " + failureCause(output));
        }
    }

    /** Gradle prints the cause after "What went wrong"; the banner above it is noise. */
    private static String failureCause(String output) {
        var start = output.indexOf("* What went wrong:");
        if (start < 0) return truncate(output, 600);
        var end = output.indexOf("* Try:", start);
        var cause = end < 0 ? output.substring(start) : output.substring(start, end);
        return cause.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    // =========================================================================
    // Graph → ModuleGraph conversion
    // =========================================================================

    private static ModuleGraph toModuleGraph(List<GraphModule> parsed) {
        if (parsed == null || parsed.isEmpty()) return ModuleGraph.EMPTY;
        var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var m : parsed) {
            modules.put(m.projectPath, new ModuleGraph.ModuleInfo(
                    m.projectPath,
                    Path.of(m.projectDir),
                    m.sourceDirs == null ? List.of() : m.sourceDirs.stream().map(Path::of).toList(),
                    m.testSourceDirs == null ? List.of() : m.testSourceDirs.stream().map(Path::of).toList(),
                    m.classOutputDir == null ? null : Path.of(m.classOutputDir),
                    m.testOutputDir == null ? null : Path.of(m.testOutputDir),
                    // Phase 1 does NOT resolve classpath — leave empty
                    m.externalClasspath == null ? List.of() : m.externalClasspath.stream().map(Path::of).toList(),
                    m.moduleDeps == null ? List.of() : List.copyOf(m.moduleDeps),
                    m.testModuleDeps == null ? List.of() : List.copyOf(m.testModuleDeps),
                    m.sourceCompatibility,
                    List.of(),
                    null,
                    null));
        }
        return new ModuleGraph(Collections.unmodifiableMap(modules));
    }

    // =========================================================================
    // Cache — Phase 1 (graph)
    // =========================================================================

    private static ModuleGraph loadGraphCache(Path graphFile) {
        try (Reader reader = Files.newBufferedReader(graphFile)) {
            var parsed = GSON.fromJson(reader, GraphOutput.class);
            var graph = toModuleGraph(parsed == null ? null : parsed.modules);
            return graph == ModuleGraph.EMPTY ? null : graph;
        } catch (IOException | JsonParseException e) {
            LOG.warning("[gradle] graph cache read failed: " + e.getMessage());
            return null;
        }
    }

    private static void storeGraphCache(Path cacheDir, Path graphFile, String fingerprint, ModuleGraph graph) {
        var output = new GraphOutput();
        output.modules = new ArrayList<>();
        for (var info : graph.modules().values()) {
            var m = new GraphModule();
            m.projectPath = info.projectPath();
            m.projectDir = info.projectDir().toString();
            m.sourceDirs = info.sourceDirs().stream().map(Path::toString).toList();
            m.testSourceDirs = info.testSourceDirs().stream().map(Path::toString).toList();
            m.testOutputDir = info.testOutputDir() == null ? null : info.testOutputDir().toString();
            m.testModuleDeps = List.copyOf(info.testModuleDeps());
            m.classOutputDir = info.mainOutputDir() == null ? null : info.mainOutputDir().toString();
            m.externalClasspath = info.externalClasspath().stream().map(Path::toString).toList();
            m.moduleDeps = List.copyOf(info.moduleDeps());
            m.sourceCompatibility = info.sourceCompatibility();
            output.modules.add(m);
        }
        try {
            Files.createDirectories(cacheDir);
            try (Writer writer = Files.newBufferedWriter(graphFile)) {
                GSON.toJson(output, writer);
            }
            Files.writeString(cacheDir.resolve("fingerprint.txt"), fingerprint, StandardCharsets.UTF_8);
            LOG.info("[gradle] phase1 cache written: " + graphFile);
        } catch (IOException e) {
            LOG.warning("[gradle] phase1 cache write failed: " + e.getMessage());
        }
    }

    /** Try to load any stale graph cache for this workspace (different fingerprint). */
    // =========================================================================
    // Cache — Phase 2 (per-module)
    // =========================================================================

    private static ResolvedModule loadModuleCache(Path moduleFile, Path workspaceRoot, String modulePath) {
        try (Reader reader = Files.newBufferedReader(moduleFile)) {
            var cached = GSON.fromJson(reader, CachedModule.class);
            if (cached == null) return null;

            // Verify per-module fingerprint
            var currentFingerprint = computeModuleFingerprint(workspaceRoot, modulePath);
            if (currentFingerprint != null && !currentFingerprint.equals(cached.moduleFingerprint)) {
                LOG.info("[gradle] phase2 module stale (fingerprint changed): " + modulePath);
                return null;
            }

            var cp = cached.externalClasspath == null ? List.<Path>of()
                    : cached.externalClasspath.stream().map(Path::of).toList();
            var testCp = cached.testClasspath == null ? List.<Path>of()
                    : cached.testClasspath.stream().map(Path::of).toList();
            return new ResolvedModule(cp, testCp);
        } catch (IOException | JsonParseException e) {
            LOG.warning("[gradle] module cache read failed: " + e.getMessage());
            return null;
        }
    }

    private static void storeModuleCache(Path modulesDir, Path workspaceRoot, String modulePath, ResolvedModule resolved) {
        var cached = new CachedModule();
        cached.projectPath = modulePath;
        cached.moduleFingerprint = computeModuleFingerprint(workspaceRoot, modulePath);
        cached.externalClasspath = resolved.externalClasspath().stream().map(Path::toString).toList();
        cached.testClasspath = resolved.testClasspath().stream().map(Path::toString).toList();
        try {
            Files.createDirectories(modulesDir);
            var moduleFile = modulesDir.resolve(modulePathHash(modulePath) + ".json");
            try (Writer writer = Files.newBufferedWriter(moduleFile)) {
                GSON.toJson(cached, writer);
            }
        } catch (IOException e) {
            LOG.warning("[gradle] module cache write failed for " + modulePath + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // Fingerprinting
    // =========================================================================

    /** SHA-256 of concatenated build files: settings.gradle(.kts), all build.gradle(.kts), gradle.properties, libs.versions.toml. */
    private static String computeProjectFingerprint(Path workspaceRoot) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("per-project-tasks-v3".getBytes(StandardCharsets.UTF_8));

            // settings.gradle(.kts) — walk up from workspaceRoot to find the Gradle project root
            var settings = findSettingsGradle(workspaceRoot);
            if (settings == null) return null;
            var gradleRoot = settings.getParent();
            digest.update(readFileBytes(settings));

            // All build.gradle(.kts) files recursively from Gradle root
            var buildFiles = findBuildGradleFiles(gradleRoot);
            // Sort for deterministic order
            buildFiles.sort(Comparator.comparing(Path::toString));
            for (var bf : buildFiles) {
                digest.update(readFileBytes(bf));
            }

            // gradle.properties at Gradle root
            var props = gradleRoot.resolve("gradle.properties");
            if (Files.exists(props)) digest.update(readFileBytes(props));

            // libs.versions.toml (version catalog)
            var toml = gradleRoot.resolve("gradle").resolve("libs.versions.toml");
            if (Files.exists(toml)) digest.update(readFileBytes(toml));

            return hexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            LOG.warning("[gradle] SHA-256 not available");
            return null;
        }
    }

    /** SHA-256 of a specific module's build.gradle(.kts). */
    private static String computeModuleFingerprint(Path workspaceRoot, String modulePath) {
        // Convert Gradle project path to filesystem path
        // e.g. ":app" -> "app", ":lib:core" -> "lib/core"
        var relativePath = modulePath.startsWith(":") ? modulePath.substring(1) : modulePath;
        relativePath = relativePath.replace(':', '/');

        Path moduleDir;
        if (relativePath.isEmpty()) {
            moduleDir = workspaceRoot;
        } else {
            moduleDir = workspaceRoot.resolve(relativePath);
        }

        // Find build.gradle or build.gradle.kts
        var buildFile = moduleDir.resolve("build.gradle");
        if (!Files.exists(buildFile)) {
            buildFile = moduleDir.resolve("build.gradle.kts");
        }
        if (!Files.exists(buildFile)) return null;

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(readFileBytes(buildFile));
            return hexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Path defaultCacheHome() {
        return Path.of(System.getProperty("user.home"), ".cache");
    }

    private static Path cacheDir(Path cacheHome, Path workspaceRoot, String fingerprint) {
        // Match Maven's naming: <project-dir-name>-<8-char-hash-of-path>/<fingerprint>/
        var settings = findSettingsGradle(workspaceRoot);
        var gradleRoot = settings != null ? settings.getParent() : workspaceRoot;
        var projectName = gradleRoot.getFileName() == null ? "gradle" : gradleRoot.getFileName().toString();
        var pathHash = shortHash(gradleRoot.toAbsolutePath().toString());
        return cacheHome.resolve("jls").resolve(projectName + "-" + pathHash).resolve(fingerprint);
    }

    private static String shortHash(String value) {
        return java.util.UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 8);
    }

    private static String modulePathHash(String modulePath) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(modulePath.getBytes(StandardCharsets.UTF_8));
            return hexString(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // Fallback: sanitize the path
            return modulePath.replace(':', '_').replace('/', '_');
        }
    }

    private static Path extractResource(String resourceName) {
        try {
            var tmp = Files.createTempFile("jls-gradle", ".gradle");
            try (var is = GradleTooling.class.getResourceAsStream(resourceName)) {
                if (is == null) return null;
                Files.write(tmp, is.readAllBytes());
            }
            return tmp;
        } catch (IOException e) {
            LOG.warning("[gradle] failed to extract resource " + resourceName + ": " + e.getMessage());
            return null;
        }
    }

    private static Path findSettingsGradle(Path workspaceRoot) {
        for (var dir = workspaceRoot; dir != null; dir = dir.getParent()) {
            for (var name : List.of("settings.gradle", "settings.gradle.kts")) {
                var f = dir.resolve(name);
                if (Files.exists(f)) return f;
            }
        }
        for (var name : List.of("build.gradle", "build.gradle.kts")) {
            var file = workspaceRoot.resolve(name);
            if (Files.exists(file)) return file;
        }
        return null;
    }

    /** Find gradlew in workspace root or parents. Falls back to system gradle on PATH. */
    static Path findGradleExecutable(Path workspaceRoot) {
        var isWindows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        var wrapperName = isWindows ? "gradlew.bat" : "gradlew";

        // Walk up to find gradlew
        for (var dir = workspaceRoot; dir != null; dir = dir.getParent()) {
            var wrapper = dir.resolve(wrapperName);
            if (Files.isExecutable(wrapper)) return wrapper;
        }

        // Fallback: system gradle on PATH
        var systemGradle = isWindows ? "gradle.bat" : "gradle";
        var pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (var segment : pathEnv.split(isWindows ? ";" : ":")) {
                var candidate = Path.of(segment).resolve(systemGradle);
                if (Files.isExecutable(candidate)) {
                    LOG.info("[gradle] using system gradle: " + candidate);
                    return candidate;
                }
            }
        }

        LOG.warning("[gradle] no gradlew or system gradle found");
        return null;
    }

    private static List<Path> findBuildGradleFiles(Path workspaceRoot) {
        var result = new ArrayList<Path>();
        try {
            Files.walkFileTree(workspaceRoot, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 20, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    var name = file.getFileName().toString();
                    if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    var name = dir.getFileName();
                    if (name != null) {
                        var n = name.toString();
                        // Skip build output, caches, VCS dirs
                        if (n.equals("build") || n.equals(".gradle") || n.equals(".git")
                                || n.equals("node_modules") || n.equals("out")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warning("[gradle] error scanning for build files: " + e.getMessage());
        }
        return result;
    }

    private static byte[] readFileBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String hexString(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // =========================================================================
    // JSON DTOs for cache serialization
    // =========================================================================

    /** Phase 1 output from Gradle init script (and disk cache format). */
    private static final class GraphOutput {
        List<GraphModule> modules;
    }

    private static final class GraphModule {
        String projectPath, projectDir, classOutputDir, testOutputDir, sourceCompatibility;
        List<String> sourceDirs, testSourceDirs, externalClasspath, moduleDeps, testModuleDeps;
    }

    /** Phase 2 output from Gradle classpath init script. */
    private static final class ClasspathModule {
        String projectPath;
        List<String> externalClasspath, testClasspath;
    }

    /** Per-module disk cache format for Phase 2. */
    private static final class CachedModule {
        String projectPath;
        String moduleFingerprint;
        List<String> externalClasspath;
        List<String> testClasspath;
    }
}
