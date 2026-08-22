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
            if (graph == ModuleGraph.EMPTY) {
                // Try stale cache as fallback
                var stale = loadStaleGraphCache(cacheHome, workspaceRoot);
                if (stale != null) {
                    LOG.info("[gradle] using stale graph cache (" + stale.modules().size() + " modules)");
                    return stale;
                }
                return graph;
            }
            storeGraphCache(cacheDir, graphFile, fingerprint, graph);
            LOG.info("[gradle] phase1 resolved " + graph.modules().size() + " modules in "
                    + Duration.between(started, Instant.now()).toMillis() + "ms");
            return graph;
        } catch (Exception e) {
            LOG.warning("[gradle] phase1 failed: " + e.getMessage());
            // Fallback to stale cache
            var stale = loadStaleGraphCache(cacheHome, workspaceRoot);
            if (stale != null) {
                LOG.info("[gradle] using stale graph cache after failure (" + stale.modules().size() + " modules)");
                return stale;
            }
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
            // Try stale cache for remaining modules
            for (var modulePath : uncached) {
                var stale = loadStaleModuleCache(cacheHome, workspaceRoot, modulePath);
                if (stale != null) {
                    LOG.info("[gradle] using stale module cache: " + modulePath);
                    result.put(modulePath, stale);
                }
            }
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
        if (initScript == null) {
            // Fallback: try old init script name
            initScript = extractResource("/jls-gradle-init.gradle");
            if (initScript == null) {
                LOG.severe("[gradle] init script not found on classpath");
                return ModuleGraph.EMPTY;
            }
        }
        try {
            var cmd = List.of(gradle.toString(), "--no-daemon",
                    "--init-script", initScript.toString(), "-q", "jlsModuleGraph");
            LOG.info("[gradle] phase1 exec: " + String.join(" ", cmd));

            var output = executeGradle(cmd, gradle.getParent());
            if (output == null) return ModuleGraph.EMPTY;
            return parseGraphOutput(output);
        } finally {
            Files.deleteIfExists(initScript);
        }
    }

    private static ModuleGraph parseGraphOutput(String output) {
        // Try new markers first, fall back to old markers
        var startMarker = GRAPH_MARKER_START;
        var endMarker = GRAPH_MARKER_END;
        var startIdx = output.indexOf(startMarker);
        var endIdx = output.indexOf(endMarker);

        // Fallback to old init script markers
        if (startIdx < 0 || endIdx < 0) {
            startMarker = "___JLS_MODULE_GRAPH_START___";
            endMarker = "___JLS_MODULE_GRAPH_END___";
            startIdx = output.indexOf(startMarker);
            endIdx = output.indexOf(endMarker);
        }

        if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx) {
            LOG.warning("[gradle] phase1 markers not found (len=" + output.length() + ")");
            return ModuleGraph.EMPTY;
        }
        var json = output.substring(startIdx + startMarker.length(), endIdx).trim();
        try {
            var parsed = GSON.fromJson(json, GraphOutput.class);
            return toModuleGraph(parsed);
        } catch (JsonParseException e) {
            LOG.warning("[gradle] phase1 parse failed: " + e.getMessage());
            return ModuleGraph.EMPTY;
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
        if (initScript == null) {
            // Fallback: resolve using old init script (which bundles classpath in graph)
            LOG.warning("[gradle] classpath init script not found, falling back to combined resolution");
            return resolveClasspathViaOldScript(workspaceRoot, modules);
        }
        try {
            var targetModules = String.join(",", modules);
            var cmd = List.of(gradle.toString(), "--no-daemon",
                    "--init-script", initScript.toString(), "-q",
                    "jlsResolveClasspath", "-Pjls.targetModules=" + targetModules);
            LOG.info("[gradle] phase2 exec: " + String.join(" ", cmd));

            var output = executeGradle(cmd, gradle.getParent());
            if (output == null) return Map.of();
            return parseClasspathOutput(output);
        } finally {
            Files.deleteIfExists(initScript);
        }
    }

    /** Fallback: use old init script which resolves everything together. */
    private static Map<String, ResolvedModule> resolveClasspathViaOldScript(Path workspaceRoot, List<String> modules)
            throws IOException, InterruptedException {
        var gradle = findGradleExecutable(workspaceRoot);
        if (gradle == null) return Map.of();
        var initScript = extractResource("/jls-gradle-init.gradle");
        if (initScript == null) return Map.of();
        try {
            var cmd = List.of(gradle.toString(), "--no-daemon",
                    "--init-script", initScript.toString(), "-q", "jlsProjectInfo");
            var output = executeGradle(cmd, gradle.getParent());
            if (output == null) return Map.of();

            var startIdx = output.indexOf("___JLS_MODULE_GRAPH_START___");
            var endIdx = output.indexOf("___JLS_MODULE_GRAPH_END___");
            if (startIdx < 0 || endIdx < 0) return Map.of();

            var json = output.substring(startIdx + "___JLS_MODULE_GRAPH_START___".length(), endIdx).trim();
            var parsed = GSON.fromJson(json, GraphOutput.class);
            if (parsed == null || parsed.modules == null) return Map.of();

            var requested = new HashSet<>(modules);
            var result = new LinkedHashMap<String, ResolvedModule>();
            for (var m : parsed.modules) {
                if (requested.contains(m.projectPath)) {
                    var cp = m.externalClasspath == null ? List.<Path>of()
                            : m.externalClasspath.stream().map(Path::of).toList();
                    result.put(m.projectPath, new ResolvedModule(cp, List.of()));
                }
            }
            return result;
        } finally {
            Files.deleteIfExists(initScript);
        }
    }

    private static Map<String, ResolvedModule> parseClasspathOutput(String output) {
        var startIdx = output.indexOf(CP_MARKER_START);
        var endIdx = output.indexOf(CP_MARKER_END);
        if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx) {
            LOG.warning("[gradle] phase2 markers not found (len=" + output.length() + ")");
            return Map.of();
        }
        var json = output.substring(startIdx + CP_MARKER_START.length(), endIdx).trim();
        try {
            var type = new TypeToken<ClasspathOutput>() {}.getType();
            ClasspathOutput parsed = GSON.fromJson(json, type);
            if (parsed == null || parsed.modules == null) return Map.of();

            var result = new LinkedHashMap<String, ResolvedModule>();
            for (var m : parsed.modules) {
                var cp = m.externalClasspath == null ? List.<Path>of()
                        : m.externalClasspath.stream().map(Path::of).toList();
                var testCp = m.testClasspath == null ? List.<Path>of()
                        : m.testClasspath.stream().map(Path::of).toList();
                result.put(m.projectPath, new ResolvedModule(cp, testCp));
            }
            return result;
        } catch (JsonParseException e) {
            LOG.warning("[gradle] phase2 parse failed: " + e.getMessage());
            return Map.of();
        }
    }

    // =========================================================================
    // Gradle execution helper
    // =========================================================================

    private static String executeGradle(List<String> cmd, Path workingDir) throws IOException, InterruptedException {
        var process = new ProcessBuilder(cmd)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();

        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            LOG.warning("[gradle] timeout " + TIMEOUT_SECONDS + "s");
            return null;
        }
        if (process.exitValue() != 0) {
            // Gradle may exit non-zero due to deprecation warnings or other non-fatal issues.
            // If our markers are present in the output, the task actually succeeded.
            if (output.contains("___JLS_") && output.contains("_START___")) {
                LOG.info("[gradle] exit=" + process.exitValue() + " but output contains markers — using it");
                return output;
            }
            LOG.warning("[gradle] exit=" + process.exitValue() + " output=" + truncate(output, 500));
            return null;
        }
        return output;
    }

    // =========================================================================
    // Graph → ModuleGraph conversion
    // =========================================================================

    private static ModuleGraph toModuleGraph(GraphOutput parsed) {
        if (parsed == null || parsed.modules == null) return ModuleGraph.EMPTY;
        var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var m : parsed.modules) {
            modules.put(m.projectPath, new ModuleGraph.ModuleInfo(
                    m.projectPath,
                    Path.of(m.projectDir),
                    m.sourceDirs == null ? List.of() : m.sourceDirs.stream().map(Path::of).toList(),
                    null,
                    m.classOutputDir == null ? null : Path.of(m.classOutputDir),
                    null,
                    // Phase 1 does NOT resolve classpath — leave empty
                    m.externalClasspath == null ? List.of() : m.externalClasspath.stream().map(Path::of).toList(),
                    m.moduleDeps == null ? List.of() : List.copyOf(m.moduleDeps),
                    m.moduleDeps == null ? List.of() : List.copyOf(m.moduleDeps),
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
            var graph = toModuleGraph(parsed);
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
    private static ModuleGraph loadStaleGraphCache(Path cacheHome, Path workspaceRoot) {
        LOG.warning("[gradle] stale graph cache not used — data from different build config is unreliable");
        return null;
    }

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

    /** Try to load a stale module cache across all fingerprint dirs. */
    private static ResolvedModule loadStaleModuleCache(Path cacheHome, Path workspaceRoot, String modulePath) {
        LOG.warning("[gradle] stale module cache not used for " + modulePath + " — data from different build config is unreliable");
        return null;
    }

    // =========================================================================
    // Fingerprinting
    // =========================================================================

    /** SHA-256 of concatenated build files: settings.gradle(.kts), all build.gradle(.kts), gradle.properties, libs.versions.toml. */
    private static String computeProjectFingerprint(Path workspaceRoot) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");

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

    // kept for backward compat with any test that calls findGradlew directly
    static Path findGradlew(Path workspaceRoot) {
        return findGradleExecutable(workspaceRoot);
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
        String projectPath, projectDir, classOutputDir, sourceCompatibility;
        List<String> sourceDirs, externalClasspath, moduleDeps;
    }

    /** Phase 2 output from Gradle classpath init script. */
    private static final class ClasspathOutput {
        List<ClasspathModule> modules;
    }

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
