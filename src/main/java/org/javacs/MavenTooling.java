package org.javacs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * All Maven knowledge: module graph resolution, dependency resolution, compiler args inference,
 * caching, and mvn subprocess management.
 */
public final class MavenTooling {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static final Path NOT_FOUND = Paths.get("");

    private static final Pattern DEPENDENCY =
            Pattern.compile("^\\[INFO\\]\\s+(.*:.*:.*:.*:.*):(/.*?)( -- module .*)?$");

    private static final String DEPENDENCY_LIST = "dependency:list";
    private static final String DEPENDENCY_SOURCES = "dependency:sources";

    private MavenTooling() {}

    // =========================================================================
    // Records
    // =========================================================================

    static final class CompilerArgs {
        final List<String> args;
        final String source;
        final boolean mixedModules;

        CompilerArgs(List<String> args, String source, boolean mixedModules) {
            this.args = List.copyOf(args);
            this.source = source;
            this.mixedModules = mixedModules;
        }

        static CompilerArgs none() { return new CompilerArgs(List.of(), "none", false); }
        List<String> args() { return args; }
        String source() { return source; }
        boolean mixedModules() { return mixedModules; }
    }

    static final record FileFingerprint(String path, long size, long contentHash) {
        FileFingerprint() { this(null, 0, 0); }
    }

    static final record MavenInferenceCacheEntry(List<FileFingerprint> pomInputs, FileFingerprint settings, List<String> dependencies) {}
    static final record MavenInferenceCacheFile(Map<String, MavenInferenceCacheEntry> entries, MavenCompilerLevelCacheEntry compilerLevel) {}
    static final record MavenCompilerLevelCacheEntry(List<FileFingerprint> pomInputs, FileFingerprint settings, List<String> args, String source, boolean mixedModules) {}
    private record MavenCacheInputs(List<FileFingerprint> pomInputs, FileFingerprint settings) {}
    static final record MavenDependencies(Set<Path> classpath, Set<Path> sources) {}

    // =========================================================================
    // Module Graph Resolution
    // =========================================================================

    /**
     * Build a module graph from all pom.xml files under {@code workspaceRoot}.
     * Returns {@link ModuleGraph#EMPTY} if not a multi-module Maven project.
     */
    public static ModuleGraph resolveModuleGraph(Path workspaceRoot) {
        var rootPom = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(rootPom)) return ModuleGraph.EMPTY;

        List<Path> allPoms;
        try (var stream = Files.walk(workspaceRoot, 8)) {
            allPoms = stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> {
                        for (var part : workspaceRoot.relativize(p)) {
                            var s = part.toString();
                            if (s.equals("target") || s.equals("build")) return false;
                        }
                        return true;
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return ModuleGraph.EMPTY;
        }

        if (allPoms.size() <= 1) return ModuleGraph.EMPTY;

        var coordToDir = new LinkedHashMap<String, Path>();
        var pomData = new LinkedHashMap<Path, PomData>();

        for (var pom : allPoms) {
            var data = parsePomForGraph(pom);
            if (data == null) continue;
            pomData.put(pom, data);
            if (data.groupId != null && data.artifactId != null) {
                coordToDir.put(data.groupId + ":" + data.artifactId, pom.getParent());
            }
        }

        var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var entry : pomData.entrySet()) {
            var pom = entry.getKey();
            var data = entry.getValue();
            var moduleDir = pom.getParent().toAbsolutePath().normalize();
            var projectPath = ":" + workspaceRoot.relativize(moduleDir).toString().replace("/", ":");
            if (moduleDir.equals(workspaceRoot.toAbsolutePath().normalize())) projectPath = ":";

            var sourceDirs = new ArrayList<Path>();
            for (var rel : List.of("src/main/java", "src/test/java")) {
                var dir = moduleDir.resolve(rel);
                if (Files.exists(dir)) sourceDirs.add(dir);
            }

            var moduleDeps = new ArrayList<String>();
            for (var depCoord : data.dependencyCoords) {
                var depDir = coordToDir.get(depCoord);
                if (depDir != null) {
                    var depModuleDir = depDir.toAbsolutePath().normalize();
                    var depPath = ":" + workspaceRoot.relativize(depModuleDir).toString().replace("/", ":");
                    if (depModuleDir.equals(workspaceRoot.toAbsolutePath().normalize())) depPath = ":";
                    moduleDeps.add(depPath);
                }
            }

            modules.put(projectPath, new ModuleGraph.ModuleInfo(
                    projectPath, moduleDir, List.copyOf(sourceDirs),
                    List.of(), List.copyOf(moduleDeps), data.sourceCompatibility));
        }

        return new ModuleGraph(Collections.unmodifiableMap(modules));
    }

    private record PomData(String groupId, String artifactId,
                           List<String> dependencyCoords, String sourceCompatibility) {}

    private static PomData parsePomForGraph(Path pom) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var doc = factory.newDocumentBuilder().parse(pom.toFile());
            var root = doc.getDocumentElement();

            var groupId = textOf(root, "groupId");
            if (groupId == null) {
                var parent = firstChild(root, "parent");
                if (parent != null) groupId = textOf(parent, "groupId");
            }
            var artifactId = textOf(root, "artifactId");

            var deps = new ArrayList<String>();
            var depsEl = firstChild(root, "dependencies");
            if (depsEl != null) {
                for (var dep : elements(depsEl.getChildNodes())) {
                    if (!"dependency".equals(dep.getLocalName())) continue;
                    var dg = textOf(dep, "groupId");
                    var da = textOf(dep, "artifactId");
                    var scope = textOf(dep, "scope");
                    if (dg != null && da != null && !"runtime".equals(scope)) deps.add(dg + ":" + da);
                }
            }

            String sourceCompatibility = null;
            var props = firstChild(root, "properties");
            if (props != null) {
                var mv = textOf(props, "maven.compiler.release");
                if (mv == null) mv = textOf(props, "maven.compiler.source");
                sourceCompatibility = mv;
            }

            return new PomData(groupId, artifactId, List.copyOf(deps), sourceCompatibility);
        } catch (Exception e) {
            LOG.fine("[maven-module-graph] failed to parse " + pom + ": " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Dependency Resolution
    // =========================================================================

    static MavenDependencies resolveDependencies(Path pomXml, Path mavenHome, Map<String, String> envVars, String modulePath) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");
        Objects.requireNonNull(mavenHome, "mavenHome is null");
        var started = Instant.now();
        var cacheHome = cacheHome(envVars);

        var cachedClasspath = loadCachedMavenDependencies(pomXml, DEPENDENCY_LIST, mavenHome, cacheHome, modulePath);
        var cachedSources = loadCachedMavenDependencies(pomXml, DEPENDENCY_SOURCES, mavenHome, cacheHome, modulePath);
        if (!cachedClasspath.isEmpty() && !cachedSources.isEmpty()) {
            CacheAudit.hit("infer_config.maven_dependencies");
            CacheAudit.load("infer_config.maven_dependencies");
            LOG.info(String.format(
                    "[perf] infer_config_maven goal=combined source=cache_disk classpath=%d sources=%d took=%dms",
                    cachedClasspath.size(), cachedSources.size(),
                    Duration.between(started, Instant.now()).toMillis()));
            return new MavenDependencies(cachedClasspath, cachedSources);
        }
        CacheAudit.miss("infer_config.maven_dependencies");

        try {
            var cmd = new ArrayList<>(List.of(
                findMvnCommand(normalizePath(pomXml).getParent(), envVars),
                "--batch-mode", "-U",
                DEPENDENCY_LIST, DEPENDENCY_SOURCES,
                "-DincludeScope=test", "-DoutputAbsoluteArtifactFilename=true"
            ));
            if (modulePath != null) {
                cmd.add("-pl"); cmd.add(modulePath); cmd.add("-am");
            }
            var output = Files.createTempFile("jls-maven-output", ".txt");
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            LOG.info("[maven-exec] command=" + String.join(" ", cmd) + " reason=dependency_resolution workingDir=" + workingDirectory);
            var processStarted = Instant.now();
            var process = new ProcessBuilder()
                    .command(cmd).directory(workingDirectory)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(output.toFile()).start();

            var result = process.waitFor();
            LOG.info("[maven-exec] exit=" + result + " took=" + Duration.between(processStarted, Instant.now()).toMillis() + "ms");
            if (result != 0) {
                return new MavenDependencies(Set.of(), Set.of());
            }

            var classpath = new HashSet<Path>();
            var sources = new HashSet<Path>();
            for (var line : Files.readAllLines(output)) {
                var jar = readDependency(line);
                if (jar == NOT_FOUND) continue;
                if (jar.getFileName().toString().contains("-sources")) sources.add(jar);
                else classpath.add(jar);
            }
            LOG.info(String.format(
                    "[perf] infer_config_maven goal=combined source=fresh classpath=%d sources=%d process=%dms total=%dms",
                    classpath.size(), sources.size(),
                    Duration.between(processStarted, Instant.now()).toMillis(),
                    Duration.between(started, Instant.now()).toMillis()));
            var immutableClasspath = Set.copyOf(classpath);
            var immutableSources = Set.copyOf(sources);
            storeCachedMavenDependencies(pomXml, DEPENDENCY_LIST, mavenHome, cacheHome, immutableClasspath, modulePath);
            storeCachedMavenDependencies(pomXml, DEPENDENCY_SOURCES, mavenHome, cacheHome, immutableSources, modulePath);
            CacheAudit.store("infer_config.maven_dependencies");
            return new MavenDependencies(immutableClasspath, immutableSources);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
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
            var cached = loadCachedMavenDependencies(pomXml, goal, mavenHome, cacheHome, modulePath);
            if (!cached.isEmpty()) {
                CacheAudit.hit("infer_config.maven_dependencies");
                CacheAudit.load("infer_config.maven_dependencies");
                LOG.info(String.format("[perf] infer_config_maven goal=%s source=cache_disk dependencies=%d took=%dms",
                        goal, cached.size(), Duration.between(started, Instant.now()).toMillis()));
                return cached;
            }
            CacheAudit.miss("infer_config.maven_dependencies");

            var cmd = new ArrayList<>(List.of(
                findMvnCommand(normalizePath(pomXml).getParent(), envVars),
                "--batch-mode", "-U", goal,
                "-DincludeScope=test", "-DoutputAbsoluteArtifactFilename=true"
            ));
            if (modulePath != null) {
                cmd.add("-pl"); cmd.add(modulePath); cmd.add("-am");
            }
            var output = Files.createTempFile("jls-maven-output", ".txt");
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            LOG.info("[maven-exec] command=" + String.join(" ", cmd) + " reason=dependency_resolution workingDir=" + workingDirectory);
            var processStarted = Instant.now();
            var process = new ProcessBuilder()
                    .command(cmd).directory(workingDirectory)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(output.toFile()).start();

            var result = process.waitFor();
            LOG.info("[maven-exec] exit=" + result + " took=" + Duration.between(processStarted, Instant.now()).toMillis() + "ms");
            if (result != 0) return Set.of();

            var dependencies = new HashSet<Path>();
            for (var line : Files.readAllLines(output)) {
                var jar = readDependency(line);
                if (jar != NOT_FOUND) dependencies.add(jar);
            }
            LOG.info(String.format("[perf] infer_config_maven goal=%s source=fresh dependencies=%d process=%dms total=%dms",
                    goal, dependencies.size(),
                    Duration.between(processStarted, Instant.now()).toMillis(),
                    Duration.between(started, Instant.now()).toMillis()));
            var immutable = Set.copyOf(dependencies);
            storeCachedMavenDependencies(pomXml, goal, mavenHome, cacheHome, immutable, modulePath);
            CacheAudit.store("infer_config.maven_dependencies");
            return immutable;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // Caching
    // =========================================================================

    static Set<Path> loadCachedMavenDependencies(Path pomXml, String goal, Path mavenHome, Path cacheHome, String modulePath) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome, modulePath);
        var cache = readCacheFile(cacheFile);
        if (cache == null || cache.entries() == null) {
            LOG.info("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=no_cache_file");
            return Set.of();
        }
        var entry = cache.entries().get(goal);
        if (entry == null) {
            LOG.info("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=no_entry");
            return Set.of();
        }
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(entry.pomInputs(), inputs.pomInputs())
                || !Objects.equals(entry.settings(), inputs.settings())) {
            LOG.info("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=fingerprint_mismatch");
            return Set.of();
        }
        LOG.info("[maven-cache] hit goal=" + goal + " module=" + modulePath + " deps=" + entry.dependencies().size());
        var result = new LinkedHashSet<Path>();
        for (var dependency : entry.dependencies()) result.add(Paths.get(dependency));
        return Set.copyOf(result);
    }

    static CompilerArgs loadCachedCompilerArgs(Path pomXml, Path mavenHome, Path cacheHome, String modulePath) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cache = readCacheFile(workspaceCacheFile(workspaceRoot, cacheHome, modulePath));
        if (cache == null || cache.compilerLevel() == null) return null;
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(cache.compilerLevel().pomInputs(), inputs.pomInputs())
                || !Objects.equals(cache.compilerLevel().settings(), inputs.settings())) return null;
        return new CompilerArgs(
                cache.compilerLevel().args() == null ? List.of() : cache.compilerLevel().args(),
                cache.compilerLevel().source() == null ? "none" : cache.compilerLevel().source(),
                cache.compilerLevel().mixedModules());
    }

    static void storeCachedMavenDependencies(
            Path pomXml, String goal, Path mavenHome, Path cacheHome, Set<Path> dependencies, String modulePath) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome, modulePath);
        var cache = readCacheFile(cacheFile);
        var entries = new LinkedHashMap<String, MavenInferenceCacheEntry>();
        if (cache != null && cache.entries() != null) entries.putAll(cache.entries());
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        var dependencyStrings = dependencies.stream()
                .map(path -> path.toAbsolutePath().normalize().toString()).sorted().toList();
        entries.put(goal, new MavenInferenceCacheEntry(inputs.pomInputs(), inputs.settings(), dependencyStrings));
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(new MavenInferenceCacheFile(entries, cache == null ? null : cache.compilerLevel()), writer);
            }
        } catch (IOException e) {
            LOG.fine(String.format("Failed to write Maven cache %s: %s", cacheFile, e.getMessage()));
        }
    }

    static void storeCachedCompilerArgs(
            Path pomXml, Path mavenHome, Path cacheHome, CompilerArgs compilerArgs, String modulePath) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome, modulePath);
        var cache = readCacheFile(cacheFile);
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(new MavenInferenceCacheFile(
                        cache == null || cache.entries() == null ? Map.of() : cache.entries(),
                        new MavenCompilerLevelCacheEntry(inputs.pomInputs(), inputs.settings(),
                                compilerArgs.args(), compilerArgs.source(), compilerArgs.mixedModules())), writer);
            }
        } catch (IOException e) {
            LOG.fine(String.format("Failed to write Maven compiler cache %s: %s", cacheFile, e.getMessage()));
        }
    }

    private static MavenInferenceCacheFile readCacheFile(Path cacheFile) {
        if (!Files.exists(cacheFile)) return null;
        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            return GSON.fromJson(reader, MavenInferenceCacheFile.class);
        } catch (IOException | JsonParseException e) {
            LOG.fine(String.format("Failed to read Maven cache file %s: %s", cacheFile, e.getMessage()));
            return null;
        }
    }

    static Path workspaceCacheFile(Path workspaceRoot, Path cacheHome) {
        return workspaceCacheFile(workspaceRoot, cacheHome, null);
    }

    static Path workspaceCacheFile(Path workspaceRoot, Path cacheHome, String modulePath) {
        var normalizedRoot = normalizePath(workspaceRoot);
        var dir = workspaceCacheDirectory(normalizedRoot, cacheHome);
        var moduleDir = (modulePath == null || modulePath.isBlank()) ? "root" : modulePath.replace(",", "_").replace("/", "_");
        return dir.resolve(moduleDir).resolve("maven-inference.json");
    }

    private static Path workspaceCacheDirectory(Path workspaceRoot, Path cacheHome) {
        var name = workspaceRoot.getFileName() == null ? "workspace" : workspaceRoot.getFileName().toString();
        return cacheHome.resolve("jls").resolve(name + "-" + shortHash(workspaceRoot.toString()));
    }

    private static MavenCacheInputs cacheInputs(Path workspaceRoot, Path mavenHome) {
        return new MavenCacheInputs(workspacePomFingerprints(workspaceRoot), fingerprintIfExists(mavenHome.resolve("settings.xml")));
    }

    private static List<FileFingerprint> workspacePomFingerprints(Path workspaceRoot) {
        // only fingerprint the root pom + immediate child module poms.
        var poms = new ArrayList<Path>();
        var rootPom = workspaceRoot.resolve("pom.xml");
        if (Files.exists(rootPom)) poms.add(rootPom);
        try (var dirs = Files.list(workspaceRoot)) {
            dirs.filter(Files::isDirectory)
                .map(d -> d.resolve("pom.xml"))
                .filter(Files::exists)
                .filter(p -> !isGeneratedPom(p))
                .forEach(poms::add);
        } catch (IOException ignored) {}
        return poms.stream()
                .map(MavenTooling::fingerprintExistingFile)
                .sorted(Comparator.comparing(FileFingerprint::path))
                .toList();
    }

    private static boolean isGeneratedPom(Path pom) {
        var name = pom.getFileName().toString();
        return name.equals("flattened-pom.xml") || name.equals(".flattened-pom.xml");
    }

    private static FileFingerprint fingerprintExistingFile(Path path) {
        try {
            var normalized = normalizePath(path);
            var bytes = Files.readAllBytes(normalized);
            var crc = new java.util.zip.CRC32();
            crc.update(bytes);
            return new FileFingerprint(normalized.toString(), bytes.length, crc.getValue());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static FileFingerprint fingerprintIfExists(Path path) {
        return Files.exists(path) ? fingerprintExistingFile(path) : null;
    }

    // =========================================================================
    // Compiler Args Inference
    // =========================================================================

    static CompilerArgs inferCompilerArgs(Path pomXml, Path mavenHome, Map<String, String> envVars) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");
        Objects.requireNonNull(mavenHome, "mavenHome is null");
        var started = Instant.now();
        var cacheHome = cacheHome(envVars);
        var cached = loadCachedCompilerArgs(pomXml, mavenHome, cacheHome, null);
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
            storeCachedCompilerArgs(pomXml, mavenHome, cacheHome, mixed, null);
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
        storeCachedCompilerArgs(pomXml, mavenHome, cacheHome, inferred, null);
        CacheAudit.store("infer_config.maven_compiler_args");
        logCompilerArgsInference("fresh", inferred, started);
        return inferred;
    }

    private static void logCompilerArgsInference(String source, CompilerArgs inferred, Instant started) {
        LOG.info(String.format("[perf] infer_config_maven_compiler source=%s selected=%s args=%d took=%dms",
                source, inferred.source(), inferred.args().size(),
                Duration.between(started, Instant.now()).toMillis()));
    }

    private static Map<String, CompilerArgs> rawWorkspaceCompilerLevels(Path workspaceRoot) {
        var levels = new LinkedHashMap<String, CompilerArgs>();
        for (var pom : workspacePomFiles(workspaceRoot)) {
            var document = parseXml(pom);
            if (document == null) continue;
            var level = parseCompilerArgs(document, true);
            if (!level.args().isEmpty()) levels.putIfAbsent(String.join(" ", level.args()), level);
        }
        return levels;
    }

    private static CompilerArgs parseEffectivePomCompilerArgs(Path effectivePom) {
        var document = parseXml(effectivePom);
        return document == null ? CompilerArgs.none() : parseCompilerArgs(document, false);
    }

    private static CompilerArgs parseCompilerArgs(Document document, boolean includeJavaVersionProperty) {
        var release = property(document, "maven.compiler.release");
        if (isConcreteJavaLevel(release)) return new CompilerArgs(List.of("--release", release), "maven_release", false);
        var pluginRelease = compilerPluginRelease(document);
        if (isConcreteJavaLevel(pluginRelease)) return new CompilerArgs(List.of("--release", pluginRelease), "maven_release", false);
        if (includeJavaVersionProperty) {
            var javaVersion = property(document, "java.version");
            if (isConcreteJavaLevel(javaVersion)) return new CompilerArgs(List.of("--release", javaVersion), "maven_release", false);
        }
        var source = property(document, "maven.compiler.source");
        var target = property(document, "maven.compiler.target");
        if (isConcreteJavaLevel(source) && isConcreteJavaLevel(target)) return sourceTargetArgs(source, target);
        return compilerPluginSourceTarget(document);
    }

    private static String compilerPluginRelease(Document document) {
        var plugin = compilerPlugin(document);
        return plugin == null ? null : nestedText(plugin, "configuration", "release");
    }

    private static CompilerArgs compilerPluginSourceTarget(Document document) {
        var plugin = compilerPlugin(document);
        if (plugin == null) return CompilerArgs.none();
        var source = nestedText(plugin, "configuration", "source");
        var target = nestedText(plugin, "configuration", "target");
        return !isConcreteJavaLevel(source) || !isConcreteJavaLevel(target)
                ? CompilerArgs.none() : sourceTargetArgs(source, target);
    }

    private static Element compilerPlugin(Document document) {
        var plugins = document.getElementsByTagNameNS("*", "plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            if (!(plugins.item(i) instanceof Element element)) continue;
            if ("maven-compiler-plugin".equals(textOf(element, "artifactId"))) return element;
        }
        return null;
    }

    private static CompilerArgs sourceTargetArgs(String source, String target) {
        return new CompilerArgs(List.of("-source", source, "-target", target), "maven_source_target", false);
    }

    private static Path mvnEffectivePom(Path pomXml, Map<String, String> envVars) {
        try {
            var output = Files.createTempFile("jls-effective-pom", ".xml");
            String[] command = {
                findMvnCommand(normalizePath(pomXml).getParent(), envVars),
                "--batch-mode", "help:effective-pom",
                "-Doutput=" + output.toAbsolutePath(),
            };
            var workingDir = normalizePath(pomXml).getParent().toFile();
            LOG.info("[maven-exec] command=" + String.join(" ", command) + " reason=effective_pom workingDir=" + workingDir);
            var processStarted = Instant.now();
            var process = new ProcessBuilder()
                    .command(command).directory(workingDir)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            var result = process.waitFor();
            LOG.info("[maven-exec] exit=" + result + " took=" + Duration.between(processStarted, Instant.now()).toMillis() + "ms");
            if (result != 0 || !Files.exists(output)) {
                LOG.warning(String.format("[perf] infer_config_maven_effective_pom source=fresh exit=%d", result));
                return NOT_FOUND;
            }
            return output;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // Maven Command Helpers
    // =========================================================================

    static String findMvnCommand(Path projectDir, Map<String, String> envVars) {
        var wrapperName = File.separatorChar == '\\' ? "mvnw.cmd" : "mvnw";
        for (var dir = projectDir; dir != null; dir = dir.getParent()) {
            var candidate = dir.resolve(wrapperName);
            if (Files.isRegularFile(candidate)) {
                LOG.info("[maven] using wrapper: " + candidate);
                return candidate.toString();
            }
        }
        LOG.info("[maven] using system mvn (no wrapper found)");
        return getMvnCommand(envVars);
    }

    static String getMvnCommand(Map<String, String> envVars) {
        var mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd", envVars);
            if (mvnCommand == null) mvnCommand = findExecutableOnPath("mvn.bat", envVars);
        }
        return mvnCommand == null ? "mvn" : mvnCommand;
    }

    // parse <build><outputDirectory> from pom.xml, fallback target/classes
    static Path outputDirectory(Path moduleDir) {
        var pom = moduleDir.resolve("pom.xml");
        if (!Files.exists(pom)) return moduleDir.resolve("target").resolve("classes");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var doc = factory.newDocumentBuilder().parse(pom.toFile());
            var build = firstChild(doc.getDocumentElement(), "build");
            if (build != null) {
                var out = textOf(build, "outputDirectory");
                if (out != null) return moduleDir.resolve(out).normalize();
            }
        } catch (Exception e) {
            LOG.fine("Failed to parse outputDirectory from " + pom + ": " + e.getMessage());
        }
        return moduleDir.resolve("target").resolve("classes");
    }

    static Path readDependency(String line) {
        var match = DEPENDENCY.matcher(line);
        if (!match.matches()) return NOT_FOUND;
        var path = match.group(2);
        return Paths.get(path);
    }

    // =========================================================================
    // XML / Path Helpers
    // =========================================================================

    private static Path cacheHome(Map<String, String> envVars) {
        var xdg = envVars.get("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) return Paths.get(xdg);
        return Paths.get(System.getProperty("user.home")).resolve(".cache");
    }

    private static Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String shortHash(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 8);
    }

    private static Document parseXml(Path xmlFile) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(xmlFile.toFile());
        } catch (Exception e) {
            LOG.fine(String.format("Failed to parse Maven XML %s: %s", xmlFile, e.getMessage()));
            return null;
        }
    }

    private static List<Path> workspacePomFiles(Path workspaceRoot) {
        try (Stream<Path> files = Files.walk(workspaceRoot)) {
            return files.filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .map(MavenTooling::normalizePath).sorted().toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String property(Document document, String name) {
        return nestedText(document.getDocumentElement(), "properties", name);
    }

    private static boolean isConcreteJavaLevel(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
    }

    private static void deleteIfExists(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }

    private static String findExecutableOnPath(String name, Map<String, String> envVars) {
        String pathEnv = envVars.get("PATH");
        if (pathEnv == null) return null;
        for (var dirname : pathEnv.split(File.pathSeparator)) {
            var file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) return file.getAbsolutePath();
        }
        return null;
    }

    // Shared XML helpers (used by both module graph and compiler args)
    private static String textOf(Element parent, String localName) {
        var child = firstChild(parent, localName);
        if (child == null) return null;
        var text = child.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Element firstChild(Element parent, String localName) {
        if (parent == null) return null;
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && localName.equals(el.getLocalName())) return el;
        }
        return null;
    }

    private static List<Element> elements(NodeList nodes) {
        var result = new ArrayList<Element>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el) result.add(el);
        }
        return result;
    }

    private static String nestedText(Element parent, String childName, String grandchildName) {
        return textOf(firstChild(parent, childName), grandchildName);
    }
}
