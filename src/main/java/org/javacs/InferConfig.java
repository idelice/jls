package org.javacs;

import com.google.devtools.build.lib.analysis.AnalysisProtos;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors; // Added for Collectors.toSet()
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class InferConfig {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Root of the workspace that is currently open in VSCode */
    private final Path workspaceRoot;
    /** External dependencies specified manually by the user */
    private final Collection<String> externalDependencies;
    /** Location of the maven repository, usually ~/.m2 */
    private final Path mavenHome;
    /** Location of the gradle cache, usually ~/.gradle */
    private final Path gradleHome;
    /** Environment variables, primarily for testing */
    private final Map<String, String> envVars;

    InferConfig(
            Path workspaceRoot,
            Collection<String> externalDependencies,
            Path mavenHome,
            Path gradleHome,
            Map<String, String> envVars) {
        this.workspaceRoot = workspaceRoot;
        this.externalDependencies = externalDependencies;
        this.mavenHome = mavenHome;
        this.gradleHome = gradleHome;
        this.envVars = Objects.requireNonNullElseGet(envVars, System::getenv);
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies, Path mavenHome, Path gradleHome) {
        this(workspaceRoot, externalDependencies, mavenHome, gradleHome, null); // Null envVars defaults to System.getenv()
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies) {
        this(workspaceRoot, externalDependencies, defaultMavenHome(), defaultGradleHome(), null);
    }

    InferConfig(Path workspaceRoot) {
        this(workspaceRoot, Collections.emptySet(), defaultMavenHome(), defaultGradleHome(), null);
    }

    // Constructor for testing, allowing envVars injection.
    InferConfig(Path workspaceRoot, Map<String, String> envVars) {
        this(workspaceRoot, Collections.emptySet(), defaultMavenHome(), defaultGradleHome(), envVars);
    }

    private static Path defaultMavenHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".m2");
    }

    private static Path defaultGradleHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".gradle");
    }

    /** Find .jar files for external dependencies, for examples maven dependencies in ~/.m2 or jars in bazel-genfiles */
    Set<Path> classPath() {
        // Check for CLASSPATH environment variable first
        String classPathEnv = this.envVars.get("CLASSPATH");
        if (classPathEnv != null && !classPathEnv.isEmpty()) {
            LOG.info("Using CLASSPATH environment variable: " + classPathEnv);
            return Arrays.stream(classPathEnv.split(Pattern.quote(File.pathSeparator)))
                    .map(Paths::get)
                    .collect(Collectors.toSet());
        }
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            var result = new HashSet<Path>();
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, false);
                if (found == NOT_FOUND) {
                    LOG.warning(String.format("Couldn't find jar for %s in %s or %s", a, mavenHome, gradleHome));
                    continue;
                }
                result.add(found);
            }
            return result;
        }

        // Maven
        var buildRoot = findBuildRoot();
        var pomXml = buildRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            var modulePath = mavenModulePath(buildRoot, workspaceRoot);
            return mvnDependencies(pomXml, "dependency:list", mavenHome, this.envVars, modulePath);
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            return bazelClasspath(bazelWorkspaceRoot);
        }

        // Gradle
        LOG.info("[gradle] Resolving classpath via Gradle Tooling API — this may take a minute for large projects...");
        var graph = GradleTooling.resolveModuleGraph(buildRoot, cacheHome(this.envVars));
        if (graph != ModuleGraph.EMPTY) {
            // Scope to the active module's own external classpath only.
            // Inter-module deps are resolved via compiled .class files (added by compileDependencies).
            var activeModule = graph.moduleForFile(workspaceRoot);
            if (activeModule.isPresent()) {
                return new HashSet<>(activeModule.get().externalClasspath());
            }
            // Fallback: root module
            var rootModule = graph.modules().get(":");
            if (rootModule != null) {
                return new HashSet<>(rootModule.externalClasspath());
            }
            return Collections.emptySet();
        }

        return Collections.emptySet();
    }

    /** Resolve the module graph for this workspace (cached). Returns EMPTY for single-module projects. */
    ModuleGraph moduleGraph() {
        var buildRoot = findBuildRoot();
        // Determine build system at the root — don't cross-contaminate
        if (Files.exists(buildRoot.resolve("settings.gradle"))
                || Files.exists(buildRoot.resolve("settings.gradle.kts"))) {
            return GradleTooling.resolveModuleGraph(buildRoot, cacheHome(this.envVars));
        }
        if (Files.exists(buildRoot.resolve("pom.xml"))) {
            return MavenTooling.resolveModuleGraph(buildRoot);
        }
        return ModuleGraph.EMPTY;
    }

    /**
     * Walk up from workspaceRoot to find the actual build root containing settings.gradle or
     * root pom.xml with modules. Handles the common case where the user opens a submodule
     * directory in their editor rather than the repository root.
     */
    Path findBuildRoot() {
        for (var dir = workspaceRoot; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("settings.gradle"))
                    || Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            // A pom.xml with <modules> means this is a multi-module root
            var pom = dir.resolve("pom.xml");
            if (Files.exists(pom) && hasModules(pom)) {
                return dir;
            }
        }
        // Fall back to workspaceRoot if nothing found above
        return workspaceRoot;
    }

    private static boolean hasModules(Path pomXml) {
        try {
            var content = Files.readString(pomXml);
            return content.contains("<modules>");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns {@code -pl} argument for multi-module Maven projects, or null for single-module.
     */
    static String mavenModulePath(Path buildRoot, Path workspaceRoot) {
        var normalizedBuild = buildRoot.toAbsolutePath().normalize();
        var normalizedWork = workspaceRoot.toAbsolutePath().normalize();
        if (!hasModules(normalizedBuild.resolve("pom.xml"))) {
            return null;
        }
        if (!normalizedWork.equals(normalizedBuild)) {
            return normalizedBuild.relativize(normalizedWork).toString();
        }
        // workspaceRoot IS the build root — extract module names from pom
        return parseDeclaredModules(normalizedBuild.resolve("pom.xml"));
    }

    private static String parseDeclaredModules(Path pomXml) {
        try {
            var content = Files.readString(pomXml);
            var matcher = Pattern.compile("<module>([^<]+)</module>").matcher(content);
            var modules = new ArrayList<String>();
            while (matcher.find()) modules.add(matcher.group(1).trim());
            return modules.isEmpty() ? null : String.join(",", modules);
        } catch (IOException e) {
            return null;
        }
    }

    private Path bazelWorkspaceRoot() {
        for (var current = workspaceRoot; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve("WORKSPACE"))) {
                return current;
            }
        }
        return workspaceRoot;
    }

    /** Find source .jar files in local maven repository. */
    Set<Path> buildDocPath() {
        // externalDependencies
        if (!externalDependencies.isEmpty()) {
            var result = new HashSet<Path>();
            for (var id : externalDependencies) {
                var a = Artifact.parse(id);
                var found = findAnyJar(a, true);
                if (found == NOT_FOUND) {
                    LOG.warning(String.format("Couldn't find doc jar for %s in %s or %s", a, mavenHome, gradleHome));
                    continue;
                }
                result.add(found);
            }
            return result;
        }

        // Maven
        var buildRoot = findBuildRoot();
        var pomXml = buildRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            var modulePath = mavenModulePath(buildRoot, workspaceRoot);
            return mvnDependencies(pomXml, "dependency:sources", mavenHome, this.envVars, modulePath);
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            return bazelSourcepath(bazelWorkspaceRoot);
        }

        return Collections.emptySet();
    }

    CompilerArgs compilerArgs() {
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(pomXml)) {
            return CompilerArgs.none();
        }
        return inferCompilerArgs(pomXml, mavenHome, envVars);
    }

    private Path findAnyJar(Artifact artifact, boolean source) {
        Path maven = findMavenJar(artifact, source);

        if (maven != NOT_FOUND) {
            return maven;
        } else return findGradleJar(artifact, source);
    }

    Path findMavenJar(Artifact artifact, boolean source) {
        var jar =
                mavenHome
                        .resolve("repository")
                        .resolve(artifact.groupId.replace('.', File.separatorChar))
                        .resolve(artifact.artifactId)
                        .resolve(artifact.version)
                        .resolve(fileName(artifact, source));
        if (!Files.exists(jar)) {
            LOG.warning(jar + " does not exist");
            return NOT_FOUND;
        }
        return jar;
    }

    private Path findGradleJar(Artifact artifact, boolean source) {
        // Search for caches/modules-*/files-*/groupId/artifactId/version/*/artifactId-version[-sources].jar
        var base = gradleHome.resolve("caches");
        var pattern =
                "glob:"
                        + String.join(
                                File.separator,
                                base.toString(),
                                "modules-*",
                                "files-*",
                                artifact.groupId,
                                artifact.artifactId,
                                artifact.version,
                                "*",
                                fileName(artifact, source));
        var match = FileSystems.getDefault().getPathMatcher(pattern);

        try {
            return Files.walk(base, 7).filter(match::matches).findFirst().orElse(NOT_FOUND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String fileName(Artifact artifact, boolean source) {
        return artifact.artifactId + '-' + artifact.version + (source ? "-sources" : "") + ".jar";
    }

    static Set<Path> mvnDependencies(Path pomXml, String goal, Path mavenHome, Map<String, String> envVars) {
        return mvnDependencies(pomXml, goal, mavenHome, envVars, null);
    }

    static Set<Path> mvnDependencies(Path pomXml, String goal, Path mavenHome, Map<String, String> envVars, String modulePath) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");
        Objects.requireNonNull(mavenHome, "mavenHome is null");
        var started = Instant.now();
        try {
            var cacheHome = cacheHome(envVars);
            var cached = loadCachedMavenDependencies(pomXml, goal, mavenHome, cacheHome);
            if (!cached.isEmpty()) {
                CacheAudit.hit("infer_config.maven_dependencies");
                CacheAudit.load("infer_config.maven_dependencies");
                LOG.info(String.format(
                        "[perf] infer_config_maven goal=%s source=cache_disk dependencies=%d took=%dms",
                        goal, cached.size(), Duration.between(started, Instant.now()).toMillis()));
                return cached;
            }
            CacheAudit.miss("infer_config.maven_dependencies");

            // Run maven as a subprocess
            var cmd = new ArrayList<>(List.of(
                getMvnCommand(envVars),
                "--batch-mode",
                modulePath != null ? "package" : "validate",
                goal,
                "-DincludeScope=test",
                "-DoutputAbsoluteArtifactFilename=true"
            ));
            if (modulePath != null) {
                cmd.add("-pl");
                cmd.add(modulePath);
                cmd.add("-am");
                cmd.add("-DskipTests");
            }
            var output = Files.createTempFile("jls-maven-output", ".txt");
            LOG.info("Running " + String.join(" ", cmd) + " ...");
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            var processStarted = Instant.now();
            var process =
                    new ProcessBuilder()
                            .command(cmd)
                            .directory(workingDirectory)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(output.toFile())
                            .start();

            var result = process.waitFor();
            if (result != 0) {
                LOG.warning(
                        String.format(
                                "[perf] infer_config_maven goal=%s source=fresh exit=%d took=%dms",
                                goal, result, Duration.between(started, Instant.now()).toMillis()));
                return Set.of();
            }
            // Read output
            var dependencies = new HashSet<Path>();
            for (var line : Files.readAllLines(output)) {
                var jar = readDependency(line);
                if (jar != NOT_FOUND) {
                    dependencies.add(jar);
                }
            }
            LOG.info(
                    String.format(
                            "[perf] infer_config_maven goal=%s source=fresh dependencies=%d process=%dms total=%dms",
                            goal,
                            dependencies.size(),
                            Duration.between(processStarted, Instant.now()).toMillis(),
                            Duration.between(started, Instant.now()).toMillis()));
            var immutable = Set.copyOf(dependencies);
            storeCachedMavenDependencies(pomXml, goal, mavenHome, cacheHome, immutable);
            CacheAudit.store("infer_config.maven_dependencies");
            return immutable;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    static final class CompilerArgs {
        final List<String> args;
        final String source;
        final boolean mixedModules;

        CompilerArgs(List<String> args, String source, boolean mixedModules) {
            this.args = List.copyOf(args);
            this.source = source;
            this.mixedModules = mixedModules;
        }

        static CompilerArgs none() {
            return new CompilerArgs(List.of(), "none", false);
        }

        List<String> args() {
            return args;
        }

        String source() {
            return source;
        }

        boolean mixedModules() {
            return mixedModules;
        }
    }

    static final class FileFingerprint {
        String path;
        long lastModifiedMillis;
        long size;

        FileFingerprint() {}

        FileFingerprint(String path, long lastModifiedMillis, long size) {
            this.path = path;
            this.lastModifiedMillis = lastModifiedMillis;
            this.size = size;
        }

        String path() {
            return path;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FileFingerprint that)) {
                return false;
            }
            return lastModifiedMillis == that.lastModifiedMillis
                    && size == that.size
                    && Objects.equals(path, that.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, lastModifiedMillis, size);
        }
    }

    static final class MavenInferenceCacheEntry {
        List<FileFingerprint> pomInputs;
        FileFingerprint settings;
        List<String> dependencies;

        MavenInferenceCacheEntry(
                List<FileFingerprint> pomInputs,
                FileFingerprint settings,
                List<String> dependencies) {
            this.pomInputs = pomInputs;
            this.settings = settings;
            this.dependencies = dependencies;
        }

        List<FileFingerprint> pomInputs() {
            return pomInputs;
        }

        FileFingerprint settings() {
            return settings;
        }

        List<String> dependencies() {
            return dependencies;
        }
    }

    static final class MavenInferenceCacheFile {
        Map<String, MavenInferenceCacheEntry> entries;
        MavenCompilerLevelCacheEntry compilerLevel;

        MavenInferenceCacheFile(Map<String, MavenInferenceCacheEntry> entries, MavenCompilerLevelCacheEntry compilerLevel) {
            this.entries = entries;
            this.compilerLevel = compilerLevel;
        }

        Map<String, MavenInferenceCacheEntry> entries() {
            return entries;
        }
    }

    static final class MavenCompilerLevelCacheEntry {
        List<FileFingerprint> pomInputs;
        FileFingerprint settings;
        List<String> args;
        String source;
        boolean mixedModules;

        MavenCompilerLevelCacheEntry(
                List<FileFingerprint> pomInputs,
                FileFingerprint settings,
                List<String> args,
                String source,
                boolean mixedModules) {
            this.pomInputs = pomInputs;
            this.settings = settings;
            this.args = args;
            this.source = source;
            this.mixedModules = mixedModules;
        }
    }

    private record MavenCacheInputs(List<FileFingerprint> pomInputs, FileFingerprint settings) {
    }

    static Set<Path> loadCachedMavenDependencies(Path pomXml, String goal, Path mavenHome, Path cacheHome) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome);
        var cache = readCacheFile(cacheFile);
        if (cache == null || cache.entries() == null) {
            return Set.of();
        }
        var entry = cache.entries().get(goal);
        if (entry == null) {
            return Set.of();
        }
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(entry.pomInputs(), inputs.pomInputs())
                || !Objects.equals(entry.settings(), inputs.settings())) {
            return Set.of();
        }
        var result = new LinkedHashSet<Path>();
        for (var dependency : entry.dependencies()) {
            result.add(Paths.get(dependency));
        }
        return Set.copyOf(result);
    }

    static CompilerArgs loadCachedCompilerArgs(Path pomXml, Path mavenHome, Path cacheHome) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cache = readCacheFile(workspaceCacheFile(workspaceRoot, cacheHome));
        if (cache == null || cache.compilerLevel == null) {
            return null;
        }
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(cache.compilerLevel.pomInputs, inputs.pomInputs())
                || !Objects.equals(cache.compilerLevel.settings, inputs.settings())) {
            return null;
        }
        return new CompilerArgs(
                cache.compilerLevel.args == null ? List.of() : cache.compilerLevel.args,
                cache.compilerLevel.source == null ? "none" : cache.compilerLevel.source,
                cache.compilerLevel.mixedModules);
    }

    static void storeCachedMavenDependencies(
            Path pomXml, String goal, Path mavenHome, Path cacheHome, Set<Path> dependencies) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome);
        var cache = readCacheFile(cacheFile);
        var entries = new LinkedHashMap<String, MavenInferenceCacheEntry>();
        if (cache != null && cache.entries() != null) {
            entries.putAll(cache.entries());
        }
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        var dependencyStrings =
                dependencies.stream()
                        .map(path -> path.toAbsolutePath().normalize().toString())
                        .sorted()
                        .toList();
        entries.put(
                goal,
                new MavenInferenceCacheEntry(
                        inputs.pomInputs(),
                        inputs.settings(),
                        dependencyStrings));
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(
                        new MavenInferenceCacheFile(
                                entries,
                                cache == null ? null : cache.compilerLevel),
                        writer);
            }
        } catch (IOException e) {
            LOG.fine(String.format("Failed to write Maven cache %s: %s", cacheFile, e.getMessage()));
        }
    }

    static void storeCachedCompilerArgs(
            Path pomXml, Path mavenHome, Path cacheHome, CompilerArgs compilerArgs) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome);
        var cache = readCacheFile(cacheFile);
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(
                        new MavenInferenceCacheFile(
                                cache == null || cache.entries() == null ? Map.of() : cache.entries(),
                                new MavenCompilerLevelCacheEntry(
                                        inputs.pomInputs(),
                                        inputs.settings(),
                                        compilerArgs.args(),
                                        compilerArgs.source(),
                                        compilerArgs.mixedModules())),
                        writer);
            }
        } catch (IOException e) {
            LOG.fine(String.format("Failed to write Maven compiler cache %s: %s", cacheFile, e.getMessage()));
        }
    }

    private static MavenInferenceCacheFile readCacheFile(Path cacheFile) {
        if (!Files.exists(cacheFile)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            return GSON.fromJson(reader, MavenInferenceCacheFile.class);
        } catch (IOException | JsonParseException e) {
            LOG.fine(String.format("Failed to read Maven cache file %s: %s", cacheFile, e.getMessage()));
            return null;
        }
    }

    static Path workspaceCacheFile(Path workspaceRoot, Path cacheHome) {
        var normalizedRoot = normalizePath(workspaceRoot);
        return workspaceCacheDirectory(normalizedRoot, cacheHome).resolve("maven-inference.json");
    }

    private static Path workspaceCacheDirectory(Path workspaceRoot, Path cacheHome) {
        var name = workspaceRoot.getFileName() == null ? "workspace" : workspaceRoot.getFileName().toString();
        return cacheHome.resolve("jls").resolve(name + "-" + shortHash(workspaceRoot.toString()));
    }

    private static Path cacheHome(Map<String, String> envVars) {
        var xdg = envVars.get("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg);
        }
        return Paths.get(System.getProperty("user.home")).resolve(".cache");
    }

    private static Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static MavenCacheInputs cacheInputs(Path workspaceRoot, Path mavenHome) {
        return new MavenCacheInputs(
                workspacePomFingerprints(workspaceRoot),
                fingerprintIfExists(mavenHome.resolve("settings.xml")));
    }

    private static List<FileFingerprint> workspacePomFingerprints(Path workspaceRoot) {
        try (Stream<Path> files = Files.walk(workspaceRoot)) {
            return files.filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .map(InferConfig::fingerprintExistingFile)
                    .sorted(Comparator.comparing(FileFingerprint::path))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static FileFingerprint fingerprintExistingFile(Path path) {
        try {
            var normalized = normalizePath(path);
            return new FileFingerprint(
                    normalized.toString(),
                    Files.getLastModifiedTime(normalized).toMillis(),
                    Files.size(normalized));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static FileFingerprint fingerprintIfExists(Path path) {
        return Files.exists(path) ? fingerprintExistingFile(path) : null;
    }

    private static String shortHash(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 8);
    }

    static CompilerArgs inferCompilerArgs(Path pomXml, Path mavenHome, Map<String, String> envVars) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");
        Objects.requireNonNull(mavenHome, "mavenHome is null");
        var started = Instant.now();
        var cacheHome = cacheHome(envVars);
        var cached = loadCachedCompilerArgs(pomXml, mavenHome, cacheHome);
        if (cached != null) {
            CacheAudit.hit("infer_config.maven_compiler_args");
            CacheAudit.load("infer_config.maven_compiler_args");
            logCompilerArgsInference("cache_disk", cached, started);
            return cached;
        }
        CacheAudit.miss("infer_config.maven_compiler_args");

        var workspaceRoot = normalizePath(pomXml).getParent();
        var rawLevels = rawWorkspaceCompilerLevels(workspaceRoot);
        if (rawLevels.size() > 1) {
            var mixed = new CompilerArgs(List.of(), "fallback_mixed_modules", true);
            storeCachedCompilerArgs(pomXml, mavenHome, cacheHome, mixed);
            CacheAudit.store("infer_config.maven_compiler_args");
            logCompilerArgsInference("fresh", mixed, started);
            return mixed;
        }

        var effectivePom = mvnEffectivePom(pomXml, envVars);
        if (effectivePom == NOT_FOUND) {
            return rawLevels.isEmpty() ? CompilerArgs.none() : rawLevels.values().iterator().next();
        }
        CompilerArgs inferred;
        try {
            inferred = parseEffectivePomCompilerArgs(effectivePom);
        } finally {
            deleteIfExists(effectivePom);
        }
        if (inferred.args().isEmpty() && rawLevels.size() == 1) {
            inferred = rawLevels.values().iterator().next();
        }
        storeCachedCompilerArgs(pomXml, mavenHome, cacheHome, inferred);
        CacheAudit.store("infer_config.maven_compiler_args");
        logCompilerArgsInference("fresh", inferred, started);
        return inferred;
    }

    private static void logCompilerArgsInference(String source, CompilerArgs inferred, Instant started) {
        LOG.info(
                String.format(
                        "[perf] infer_config_maven_compiler source=%s selected=%s args=%d took=%dms",
                        source,
                        inferred.source(),
                        inferred.args().size(),
                        Duration.between(started, Instant.now()).toMillis()));
    }

    private static Map<String, CompilerArgs> rawWorkspaceCompilerLevels(Path workspaceRoot) {
        var levels = new LinkedHashMap<String, CompilerArgs>();
        for (var pom : workspacePomFiles(workspaceRoot)) {
            var document = parseXml(pom);
            if (document == null) {
                continue;
            }
            var level = parseCompilerArgs(document, true);
            if (!level.args().isEmpty()) {
                levels.putIfAbsent(String.join(" ", level.args()), level);
            }
        }
        return levels;
    }

    private static CompilerArgs parseEffectivePomCompilerArgs(Path effectivePom) {
        var document = parseXml(effectivePom);
        return document == null ? CompilerArgs.none() : parseCompilerArgs(document, false);
    }

    private static CompilerArgs parseCompilerArgs(Document document, boolean includeJavaVersionProperty) {
        var release = property(document, "maven.compiler.release");
        if (isConcreteJavaLevel(release)) {
            return new CompilerArgs(List.of("--release", release), "maven_release", false);
        }
        var pluginRelease = compilerPluginRelease(document);
        if (isConcreteJavaLevel(pluginRelease)) {
            return new CompilerArgs(List.of("--release", pluginRelease), "maven_release", false);
        }
        if (includeJavaVersionProperty) {
            var javaVersion = property(document, "java.version");
            if (isConcreteJavaLevel(javaVersion)) {
                return new CompilerArgs(List.of("--release", javaVersion), "maven_release", false);
            }
        }
        var source = property(document, "maven.compiler.source");
        var target = property(document, "maven.compiler.target");
        if (isConcreteJavaLevel(source) && isConcreteJavaLevel(target)) {
            return sourceTargetArgs(source, target);
        }
        return compilerPluginSourceTarget(document);
    }

    private static String compilerPluginRelease(Document document) {
        var plugin = compilerPlugin(document);
        if (plugin == null) {
            return null;
        }
        return nestedText(plugin, "configuration", "release");
    }

    private static CompilerArgs compilerPluginSourceTarget(Document document) {
        var plugin = compilerPlugin(document);
        if (plugin == null) {
            return CompilerArgs.none();
        }
        var source = nestedText(plugin, "configuration", "source");
        var target = nestedText(plugin, "configuration", "target");
        return !isConcreteJavaLevel(source) || !isConcreteJavaLevel(target)
                ? CompilerArgs.none()
                : sourceTargetArgs(source, target);
    }

    private static Element compilerPlugin(Document document) {
        var plugins = document.getElementsByTagNameNS("*", "plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            var plugin = plugins.item(i);
            if (!(plugin instanceof Element element)) {
                continue;
            }
            var artifactId = directChildText(element, "artifactId");
            if (!"maven-compiler-plugin".equals(artifactId)) {
                continue;
            }
            return element;
        }
        return null;
    }

    private static CompilerArgs sourceTargetArgs(String source, String target) {
        return new CompilerArgs(List.of("-source", source, "-target", target), "maven_source_target", false);
    }

    private static String property(Document document, String name) {
        return nestedText(document.getDocumentElement(), "properties", name);
    }

    private static Path mvnEffectivePom(Path pomXml, Map<String, String> envVars) {
        try {
            var output = Files.createTempFile("jls-effective-pom", ".xml");
            String[] command = {
                getMvnCommand(envVars),
                "--batch-mode",
                "help:effective-pom",
                "-Doutput=" + output.toAbsolutePath(),
            };
            LOG.info("Running " + String.join(" ", command) + " ...");
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(normalizePath(pomXml).getParent().toFile())
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            var result = process.waitFor();
            if (result != 0 || !Files.exists(output)) {
                LOG.warning(String.format("[perf] infer_config_maven_effective_pom source=fresh exit=%d", result));
                return NOT_FOUND;
            }
            return output;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Document parseXml(Path xmlFile) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var builder = factory.newDocumentBuilder();
            return builder.parse(xmlFile.toFile());
        } catch (Exception e) {
            LOG.fine(String.format("Failed to parse Maven XML %s: %s", xmlFile, e.getMessage()));
            return null;
        }
    }

    private static List<Path> workspacePomFiles(Path workspaceRoot) {
        try (Stream<Path> files = Files.walk(workspaceRoot)) {
            return files.filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .map(InferConfig::normalizePath)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Element directChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var child = children.item(i);
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String nestedText(Element parent, String childName, String grandchildName) {
        return directChildText(directChild(parent, childName), grandchildName);
    }

    private static String directChildText(Element parent, String localName) {
        var child = directChild(parent, localName);
        if (child == null) {
            return null;
        }
        var text = child.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static boolean isConcreteJavaLevel(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.fine(String.format("Failed to delete temporary file %s: %s", path, e.getMessage()));
        }
    }

    private static final Pattern DEPENDENCY =
            Pattern.compile("^\\[INFO\\]\\s+(.*:.*:.*:.*:.*):(/.*?)( -- module .*)?$");

    static Path readDependency(String line) {
        var match = DEPENDENCY.matcher(line);
        if (!match.matches()) {
            return NOT_FOUND;
        }
        var artifact = match.group(1);
        var path = match.group(2);
        LOG.fine(String.format("...%s => %s", artifact, path));
        return Paths.get(path);
    }

    static String getMvnCommand(Map<String, String> envVars) {
        var mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd", envVars);
            if (mvnCommand == null) {
                mvnCommand = findExecutableOnPath("mvn.bat", envVars);
            }
        }
        // If findExecutableOnPath returns null (e.g. PATH is not set), we should still return "mvn"
        // and let the execution fail later if it's not on the (empty) path.
        return mvnCommand == null ? "mvn" : mvnCommand;
    }

    private static String findExecutableOnPath(String name, Map<String, String> envVars) {
        String pathEnv = envVars.get("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (var dirname : pathEnv.split(File.pathSeparator)) {
            var file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private boolean buildProtos(Path bazelWorkspaceRoot) {
        var targets = bazelQuery(bazelWorkspaceRoot, "java_proto_library");
        if (targets.size() == 0) {
            return false;
        }
        bazelDryRunBuild(bazelWorkspaceRoot, targets);
        return true;
    }

    private Set<Path> bazelClasspath(Path bazelWorkspaceRoot) {
        var absolute = new HashSet<Path>();

        // Add protos
        if (buildProtos(bazelWorkspaceRoot)) {
            for (var relative : bazelAQuery(bazelWorkspaceRoot, "Javac", "--output", "proto_library")) {
                absolute.add(bazelWorkspaceRoot.resolve(relative));
            }
        }

        // Add rest of classpath
        for (var relative :
                bazelAQuery(bazelWorkspaceRoot, "Javac", "--classpath", "java_library", "java_test", "java_binary")) {
            absolute.add(bazelWorkspaceRoot.resolve(relative));
        }
        return absolute;
    }

    private Set<Path> bazelSourcepath(Path bazelWorkspaceRoot) {
        var absolute = new HashSet<Path>();
        var outputBase = bazelOutputBase(bazelWorkspaceRoot);
        for (var relative :
                bazelAQuery(
                        bazelWorkspaceRoot, "JavaSourceJar", "--sources", "java_library", "java_test", "java_binary")) {
            absolute.add(outputBase.resolve(relative));
        }

        // Add proto source files
        if (buildProtos(bazelWorkspaceRoot)) {
            for (var relative : bazelAQuery(bazelWorkspaceRoot, "Javac", "--source_jars", "proto_library")) {
                absolute.add(bazelWorkspaceRoot.resolve(relative));
            }
        }

        return absolute;
    }

    private Path bazelOutputBase(Path bazelWorkspaceRoot) {
        // Run bazel as a subprocess
        String[] command = {
            "bazel", "info", "output_base",
        };
        var output = fork(bazelWorkspaceRoot, command);
        if (output == NOT_FOUND) {
            return NOT_FOUND;
        }
        // Read output
        try {
            var out = Files.readString(output).trim();
            return Paths.get(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void bazelDryRunBuild(Path bazelWorkspaceRoot, Set<String> targets) {
        var command = new ArrayList<String>();
        command.add("bazel");
        command.add("build");
        command.add("--nobuild");
        command.addAll(targets);
        String[] c = new String[command.size()];
        c = command.toArray(c);
        var output = fork(bazelWorkspaceRoot, c);
        if (output == NOT_FOUND) {
            return;
        }
        return;
    }

    private Set<String> bazelQuery(Path bazelWorkspaceRoot, String filterKind) {
        String[] command = {"bazel", "query", "kind(" + filterKind + ",//...)"};
        var output = fork(bazelWorkspaceRoot, command);
        if (output == NOT_FOUND) {
            return Set.of();
        }
        return readQueryResult(output);
    }

    private Set<String> readQueryResult(Path output) {
        try {
            Stream<String> stream = Files.lines(output);
            var targets = new HashSet<String>();
            var i = stream.iterator();
            while (i.hasNext()) {
                var t = i.next();
                targets.add(t);
            }
            return targets;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<String> bazelAQuery(
            Path bazelWorkspaceRoot, String filterMnemonic, String filterArgument, String... kinds) {
        String kindUnion = "";
        for (var kind : kinds) {
            if (kindUnion.length() > 0) {
                kindUnion += " union ";
            }
            kindUnion += "kind(" + kind + ", ...)";
        }
        String[] command = {
            "bazel",
            "aquery",
            "--output=proto",
            "--include_aspects", // required for java_proto_library, see
            // https://stackoverflow.com/questions/63430530/bazel-aquery-returns-no-action-information-for-java-proto-library
            "--allow_analysis_failures",
            "mnemonic(" + filterMnemonic + ", " + kindUnion + ")"
        };
        var output = fork(bazelWorkspaceRoot, command);
        if (output == NOT_FOUND) {
            return Set.of();
        }
        return readActionGraph(output, filterArgument);
    }

    private Set<String> readActionGraph(Path output, String filterArgument) {
        try {
            var containerV2 = AnalysisProtosV2.ActionGraphContainer.parseFrom(Files.newInputStream(output));
            if (containerV2.getArtifactsCount() != 0 && containerV2.getArtifactsList().get(0).getId() != 0) {
                return readActionGraphFromV2(containerV2, filterArgument);
            }
            var containerV1 = AnalysisProtos.ActionGraphContainer.parseFrom(Files.newInputStream(output));
            return readActionGraphFromV1(containerV1, filterArgument);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Set<String> readActionGraphFromV1(AnalysisProtos.ActionGraphContainer container, String filterArgument) {
        var argumentPaths = new HashSet<String>();
        var outputIds = new HashSet<String>();
        for (var action : container.getActionsList()) {
            var isFilterArgument = false;
            for (var argument : action.getArgumentsList()) {
                if (isFilterArgument && argument.startsWith("-")) {
                    isFilterArgument = false;
                    continue;
                }
                if (!isFilterArgument) {
                    isFilterArgument = argument.equals(filterArgument);
                    continue;
                }
                argumentPaths.add(argument);
            }
            outputIds.addAll(action.getOutputIdsList());
        }
        var artifactPaths = new HashSet<String>();
        for (var artifact : container.getArtifactsList()) {
            if (!argumentPaths.contains(artifact.getExecPath())) {
                // artifact was not specified by --filterArgument
                continue;
            }
            if (outputIds.contains(artifact.getId()) && !filterArgument.equals("--output")) {
                // artifact is the output of another java action
                continue;
            }
            var relative = artifact.getExecPath();
            LOG.info("...found bazel dependency " + relative);
            artifactPaths.add(relative);
        }
        return artifactPaths;
    }

    private Set<String> readActionGraphFromV2(AnalysisProtosV2.ActionGraphContainer container, String filterArgument) {
        var argumentPaths = new HashSet<String>();
        var outputIds = new HashSet<Integer>();
        for (var action : container.getActionsList()) {
            var isFilterArgument = false;
            for (var argument : action.getArgumentsList()) {
                if (isFilterArgument && argument.startsWith("-")) {
                    isFilterArgument = false;
                    continue;
                }
                if (!isFilterArgument) {
                    isFilterArgument = argument.equals(filterArgument);
                    continue;
                }
                argumentPaths.add(argument);
            }
            outputIds.addAll(action.getOutputIdsList());
        }
        var artifactPaths = new HashSet<String>();
        for (var artifact : container.getArtifactsList()) {
            if (outputIds.contains(artifact.getId()) && !filterArgument.equals("--output")) {
                // artifact is the output of another java action
                continue;
            }
            var relative = buildPath(container.getPathFragmentsList(), artifact.getPathFragmentId());
            if (!argumentPaths.contains(relative)) {
                // artifact was not specified by --filterArgument
                continue;
            }
            LOG.info("...found bazel dependency " + relative);
            artifactPaths.add(relative);
        }
        return artifactPaths;
    }

    private static String buildPath(List<PathFragment> fragments, int id) {
        for (PathFragment fragment : fragments) {
            if (fragment.getId() == id) {
                if (fragment.getParentId() != 0) {
                    return buildPath(fragments, fragment.getParentId()) + "/" + fragment.getLabel();
                }
                return fragment.getLabel();
            }
        }
        throw new RuntimeException();
    }

    private static Path fork(Path workspaceRoot, String[] command) {
        try {
            LOG.info("Running " + String.join(" ", command) + " ...");
            var output = Files.createTempFile("jls-bazel-output", ".proto");
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workspaceRoot.toFile())
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(output.toFile())
                            .start();
            // Wait for process to exit
            var result = process.waitFor();
            if (result != 0) {
                LOG.severe("`" + String.join(" ", command) + "` returned " + result);
                return NOT_FOUND;
            }
            return output;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Path NOT_FOUND = Paths.get("");
}
