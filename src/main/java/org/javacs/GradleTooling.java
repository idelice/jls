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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Resolves module structure for Gradle projects.
 * Cache hit = instant. Cache miss = runs gradlew synchronously (~20s).
 * Uses redirectErrorStream(true) to prevent pipe deadlocks.
 */
public final class GradleTooling {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setLenient().create();
    private static final String MARKER_START = "___JLS_MODULE_GRAPH_START___";
    private static final String MARKER_END = "___JLS_MODULE_GRAPH_END___";

    private GradleTooling() {}

    public static ModuleGraph resolveModuleGraph(Path workspaceRoot, Path cacheHome) {
        if (findSettingsGradle(workspaceRoot) == null) return ModuleGraph.EMPTY;
        var cacheFile = cacheFile(workspaceRoot, cacheHome);
        if (Files.exists(cacheFile)) {
            var cached = loadCache(cacheFile);
            if (cached != null) {
                LOG.info("[gradle] cache hit: " + cached.modules().size() + " modules");
                return cached;
            }
        }
        LOG.info("[gradle] cache miss — running gradlew");
        var started = Instant.now();
        try {
            var graph = runGradlew(workspaceRoot);
            if (graph == ModuleGraph.EMPTY) return graph;
            storeCache(cacheFile, graph);
            LOG.info("[gradle] resolved " + graph.modules().size() + " modules in "
                    + Duration.between(started, Instant.now()).toMillis() + "ms");
            return graph;
        } catch (Exception e) {
            LOG.warning("[gradle] resolution failed: " + e.getMessage());
            return ModuleGraph.EMPTY;
        }
    }

    private static ModuleGraph runGradlew(Path workspaceRoot) throws IOException, InterruptedException {
        var gradlew = findGradlew(workspaceRoot);
        if (gradlew == null) {
            LOG.warning("[gradle] gradlew not found");
            return ModuleGraph.EMPTY;
        }
        var initScript = extractInitScript();
        try {
            var cmd = List.of(gradlew.toString(), "--no-daemon",
                    "--init-script", initScript.toString(), "-q", "jlsProjectInfo");
            LOG.info("[gradle] exec: " + String.join(" ", cmd));

            // redirectErrorStream(true) merges stderr into stdout.
            // Prevents pipe buffer deadlock that caused "never ending" hangs.
            var process = new ProcessBuilder(cmd)
                    .directory(gradlew.getParent().toFile())
                    .redirectErrorStream(true)
                    .start();

            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var exited = process.waitFor(90, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                LOG.warning("[gradle] timeout 90s");
                return ModuleGraph.EMPTY;
            }
            if (process.exitValue() != 0) {
                LOG.warning("[gradle] exit=" + process.exitValue());
                return ModuleGraph.EMPTY;
            }
            LOG.info("[gradle] output=" + output.length() + " bytes");
            return parseOutput(output);
        } finally {
            Files.deleteIfExists(initScript);
        }
    }

    private static ModuleGraph parseOutput(String output) {
        var startIdx = output.indexOf(MARKER_START);
        var endIdx = output.indexOf(MARKER_END);
        if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx) {
            LOG.warning("[gradle] markers not found (len=" + output.length() + ")");
            return ModuleGraph.EMPTY;
        }
        var json = output.substring(startIdx + MARKER_START.length(), endIdx).trim();
        try {
            var parsed = GSON.fromJson(json, GradlewOutput.class);
            var graph = toModuleGraph(parsed);
            if (graph != null) {
                LOG.info("[gradle] parsed " + graph.modules().size() + " modules");
                return graph;
            }
            return ModuleGraph.EMPTY;
        } catch (JsonParseException e) {
            LOG.warning("[gradle] parse failed: " + e.getMessage());
            return ModuleGraph.EMPTY;
        }
    }

    private static ModuleGraph toModuleGraph(GradlewOutput parsed) {
        if (parsed == null || parsed.modules == null) return null;
        var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var m : parsed.modules) {
            modules.put(m.projectPath, new ModuleGraph.ModuleInfo(
                    m.projectPath, Paths.get(m.projectDir),
                    m.sourceDirs == null ? List.of() : m.sourceDirs.stream().map(Paths::get).toList(),
                    m.externalClasspath == null ? List.of() : m.externalClasspath.stream().map(Paths::get).toList(),
                    m.moduleDeps == null ? List.of() : List.copyOf(m.moduleDeps),
                    m.sourceCompatibility));
        }
        return new ModuleGraph(Collections.unmodifiableMap(modules));
    }

    // =========================================================================
    // Cache
    // =========================================================================

    private static final class GradlewOutput { List<GradlewModule> modules; }
    private static final class GradlewModule {
        String projectPath, projectDir, sourceCompatibility;
        List<String> sourceDirs, externalClasspath, moduleDeps;
    }

    private static ModuleGraph loadCache(Path cacheFile) {
        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            var parsed = GSON.fromJson(reader, GradlewOutput.class);
            return toModuleGraph(parsed);
        } catch (IOException | JsonParseException e) {
            LOG.warning("[gradle] cache read failed: " + e.getMessage());
            return null;
        }
    }

    private static void storeCache(Path cacheFile, ModuleGraph graph) {
        var file = new GradlewOutput();
        file.modules = new ArrayList<>();
        for (var info : graph.modules().values()) {
            var m = new GradlewModule();
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
            try (Writer writer = Files.newBufferedWriter(cacheFile)) { GSON.toJson(file, writer); }
            LOG.info("[gradle] cache written: " + cacheFile);
        } catch (IOException e) {
            LOG.warning("[gradle] cache write failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Path extractInitScript() throws IOException {
        var tmp = Files.createTempFile("jls-gradle-init", ".gradle");
        try (var is = GradleTooling.class.getResourceAsStream("/jls-gradle-init.gradle")) {
            if (is == null) throw new IOException("jls-gradle-init.gradle not found in classpath");
            Files.write(tmp, is.readAllBytes());
        }
        return tmp;
    }

    private static Path findSettingsGradle(Path workspaceRoot) {
        for (var name : List.of("settings.gradle", "settings.gradle.kts")) {
            if (Files.exists(workspaceRoot.resolve(name))) return workspaceRoot.resolve(name);
        }
        return null;
    }

    static Path findGradlew(Path workspaceRoot) {
        var name = System.getProperty("os.name", "").toLowerCase().startsWith("windows") ? "gradlew.bat" : "gradlew";
        for (var dir = workspaceRoot; dir != null; dir = dir.getParent()) {
            if (Files.isExecutable(dir.resolve(name))) return dir.resolve(name);
        }
        return null;
    }

    private static Path cacheFile(Path workspaceRoot, Path cacheHome) {
        var name = workspaceRoot.getFileName() == null ? "workspace" : workspaceRoot.getFileName().toString();
        var hash = UUID.nameUUIDFromBytes(workspaceRoot.toAbsolutePath().normalize().toString()
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 8);
        return cacheHome.resolve("jls").resolve(name + "-" + hash).resolve("gradle-module-graph.json");
    }
}
