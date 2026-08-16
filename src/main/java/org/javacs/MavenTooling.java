package org.javacs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * All Maven knowledge: module graph resolution, dependency resolution, compiler args inference,
 * caching, and mvn subprocess management.
 */
public final class MavenTooling {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int CACHE_VERSION = 4;

    static final Path NOT_FOUND = Paths.get("");

    private static final Pattern DEPENDENCY = Pattern.compile(
            "^(?:\\[INFO\\]\\s+)?\\s*(.*:.*:.*:.*:.*):((?:/|[A-Za-z]:\\\\).*?)( \\(optional\\))?( -- module .*)?$");

    private static final String DEPENDENCY_LIST = "dependency:list";
    private static final String DEPENDENCY_SOURCES = "dependency:sources";
    private static final String EFFECTIVE_POM =
            "org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom";

    private MavenTooling() {}

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
    static final record MavenInferenceCacheFile(
            int version,
            Map<String, MavenInferenceCacheEntry> entries,
            MavenCompilerLevelCacheEntry compilerLevel,
            MavenModuleGraphCacheEntry moduleGraph) {}
    static final record MavenCompilerLevelCacheEntry(List<FileFingerprint> pomInputs, FileFingerprint settings, List<String> args, String source, boolean mixedModules) {}
    static final record MavenModuleGraphCacheEntry(
            List<FileFingerprint> pomInputs,
            FileFingerprint settings,
            List<CachedMavenModule> modules) {}
    static final record CachedMavenModule(
            String projectPath,
            String projectDir,
            List<String> sourceDirs,
            String testSourceDir,
            String mainOutputDir,
            String testOutputDir,
            List<String> moduleDeps,
            List<String> testModuleDeps,
            String sourceCompatibility,
            List<String> compilerArgs,
            String artifactId,
            String coordinates) {}
    private record MavenCacheInputs(List<FileFingerprint> pomInputs, FileFingerprint settings) {}

    // fingerprint-once-per-generation. Computed at compiler creation, reused
    // for all module cache lookups in that generation. Avoids re-scanning all pom files
    // on every cache check
    private static volatile MavenCacheInputs cachedInputsSnapshot;
    private static volatile Path cachedInputsWorkspaceRoot;

    /**
     * Compute and cache the reactor fingerprint for this compiler generation.
     * Call this once at compiler creation time. All subsequent cache lookups
     * reuse this snapshot until the next generation.
     */
    static void refreshCacheInputsSnapshot(Path workspaceRoot, Path mavenHome) {
        var started = Instant.now();
        cachedInputsWorkspaceRoot = normalizePath(workspaceRoot);
        cachedInputsSnapshot = cacheInputs(cachedInputsWorkspaceRoot, mavenHome);
        LOG.info("[maven-cache] fingerprint snapshot refreshed took="
                + Duration.between(started, Instant.now()).toMillis() + "ms"
                + " poms=" + cachedInputsSnapshot.pomInputs().size());
    }

    /** Invalidate the snapshot (forces recomputation on next access). */
    static void invalidateCacheInputsSnapshot() {
        cachedInputsSnapshot = null;
        cachedInputsWorkspaceRoot = null;
    }

    private static MavenCacheInputs getCacheInputs(Path workspaceRoot, Path mavenHome) {
        var snapshot = cachedInputsSnapshot;
        var snapshotRoot = cachedInputsWorkspaceRoot;
        if (snapshot != null && snapshotRoot != null && snapshotRoot.equals(normalizePath(workspaceRoot))) {
            return snapshot;
        }
        // Fallback: compute on the fly (shouldn't happen if refreshCacheInputsSnapshot was called)
        return cacheInputs(workspaceRoot, mavenHome);
    }
    static final record MavenDependencies(
            Set<Path> classpath, Set<Path> sources, Set<Path> sourceRoots) {
        MavenDependencies(Set<Path> classpath, Set<Path> sources) {
            this(classpath, sources, Set.of());
        }
    }
    private record ResolvedDependency(
            String coordinates, String type, String classifier, String scope, Path path) {
        boolean onMainClasspath() {
            return "compile".equals(scope) || "provided".equals(scope) || "system".equals(scope);
        }

        boolean isTestArtifact() {
            return "test-jar".equals(type) || "tests".equals(classifier);
        }
    }
    static final record MavenWorkspace(Path buildRoot, ModuleGraph graph) {}
    private record ResolvedMavenModel(ModuleGraph graph, CompilerArgs compilerArgs) {}

    // =========================================================================
    // Module Graph Resolution
    // =========================================================================

    /** Build the exact single- or multi-module graph reported by Maven. */
    public static ModuleGraph resolveModuleGraph(Path workspaceRoot) {
        return resolveModuleGraph(
                workspaceRoot,
                Paths.get(System.getProperty("user.home")).resolve(".m2"),
                System.getenv());
    }

    static ModuleGraph resolveModuleGraph(
            Path workspaceRoot, Path mavenHome, Map<String, String> envVars) {
        var pom = normalizePath(workspaceRoot).resolve("pom.xml");
        if (!Files.exists(pom)) return ModuleGraph.EMPTY;
        return resolveMavenModel(pom, mavenHome, envVars).graph();
    }

    static MavenWorkspace resolveWorkspace(
            Path workspaceRoot, Path candidateBuildRoot, Path mavenHome, Map<String, String> envVars) {
        var workspace = normalizePath(workspaceRoot);
        var buildRoot = normalizePath(candidateBuildRoot);
        var graph = resolveModuleGraph(buildRoot, mavenHome, envVars);
        if (ownsWorkspace(graph, workspace)) return new MavenWorkspace(buildRoot, graph);

        var projectRoot = findProjectRoot(workspace);
        if (projectRoot == null || projectRoot.equals(buildRoot)) return new MavenWorkspace(buildRoot, graph);
        return new MavenWorkspace(projectRoot, resolveModuleGraph(projectRoot, mavenHome, envVars));
    }

    private static boolean ownsWorkspace(ModuleGraph graph, Path workspace) {
        for (var module : graph.modules().values()) {
            if (workspace.equals(module.projectDir())) return true;
            for (var sourceDir : module.sourceDirs()) {
                if (workspace.startsWith(sourceDir) || sourceDir.startsWith(workspace)) return true;
            }
        }
        return false;
    }

    static ModuleGraph parseEffectivePomModuleGraph(Path workspaceRoot, Path effectivePom) {
        var document = parseXml(effectivePom);
        if (document == null) return ModuleGraph.EMPTY;
        return parseEffectivePomModuleGraph(workspaceRoot, document);
    }

    private static ModuleGraph parseEffectivePomModuleGraph(Path workspaceRoot, Document document) {
        var projectElements = effectiveProjects(document);
        if (projectElements.isEmpty()) return ModuleGraph.EMPTY;

        var projects = projectElements.stream().map(EffectiveProject::new).toList();
        EffectiveProject rootProject;
        if (projects.size() == 1) {
            rootProject = projects.getFirst();
        } else {
            rootProject = findEffectiveProject(workspaceRoot.resolve("pom.xml"), projects, Set.of());
            if (rootProject == null) return ModuleGraph.EMPTY;
        }

        var modules = new LinkedHashMap<String, ResolvedModule>();
        if (!collectEffectiveModules(
                workspaceRoot.toAbsolutePath().normalize(),
                workspaceRoot.toAbsolutePath().normalize(),
                rootProject,
                projects,
                modules,
                new HashSet<>())) return ModuleGraph.EMPTY;

        var coordinates = new HashMap<String, String>();
        for (var entry : modules.entrySet()) coordinates.put(entry.getValue().project.coordinates(), entry.getKey());

        var result = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var entry : modules.entrySet()) {
            var module = entry.getValue();
            var project = module.project;
            var dependencies = project.dependencies.stream()
                    .filter(MavenDependency::onMainClasspath)
                    .map(dependency -> coordinates.get(dependency.coordinates()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            var testDependencies = project.dependencies.stream()
                    .filter(MavenDependency::onTestClasspath)
                    .map(dependency -> coordinates.get(dependency.coordinates()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            var sourceDirs = Stream.of(project.mainSourceDir, project.testSourceDir)
                    .filter(Objects::nonNull)
                    .toList();
            var compilerArgs = parseCompilerArgs(project.element);
            var sourceCompatibility = compilerArgs.args().size() < 2 ? null : compilerArgs.args().get(1);
            result.put(entry.getKey(), new ModuleGraph.ModuleInfo(
                    entry.getKey(),
                    module.directory,
                    sourceDirs,
                    project.testSourceDir,
                    project.mainOutputDir,
                    project.testOutputDir,
                    List.of(),
                    dependencies,
                    testDependencies,
                    sourceCompatibility,
                    compilerArgs.args(),
                    project.artifactId,
                    project.coordinates()));
        }
        return new ModuleGraph(Collections.unmodifiableMap(result));
    }

    private static ResolvedMavenModel resolveMavenModel(
            Path pomXml, Path mavenHome, Map<String, String> envVars) {
        var cacheHome = cacheHome(envVars);
        var cached = loadCachedMavenModel(pomXml, mavenHome, cacheHome);
        if (cached != null) {
            CacheAudit.hit("infer_config.maven_model");
            CacheAudit.load("infer_config.maven_model");
            return cached;
        }
        CacheAudit.miss("infer_config.maven_model");

        var effectivePom = mvnEffectivePom(pomXml, envVars);
        if (effectivePom == NOT_FOUND) return new ResolvedMavenModel(ModuleGraph.EMPTY, CompilerArgs.none());
        try {
            var document = parseXml(effectivePom);
            if (document == null) return new ResolvedMavenModel(ModuleGraph.EMPTY, CompilerArgs.none());
            var model = new ResolvedMavenModel(
                    parseEffectivePomModuleGraph(normalizePath(pomXml).getParent(), document),
                    compilerArgs(effectiveProjects(document)));
            storeCachedMavenModel(pomXml, mavenHome, cacheHome, model);
            CacheAudit.store("infer_config.maven_model");
            return model;
        } finally {
            deleteIfExists(effectivePom);
        }
    }

    private static CompilerArgs compilerArgs(List<Element> projects) {
        var levels = new LinkedHashMap<List<String>, CompilerArgs>();
        for (var project : projects) {
            if ("pom".equals(textOf(project, "packaging"))) continue;
            var args = parseCompilerArgs(project);
            if (!args.args().isEmpty()) levels.putIfAbsent(args.args(), args);
        }
        if (levels.size() > 1) return new CompilerArgs(List.of(), "fallback_mixed_modules", true);
        return levels.isEmpty() ? CompilerArgs.none() : levels.values().iterator().next();
    }

    private record ResolvedModule(Path directory, EffectiveProject project) {}

    private static final class EffectiveProject {
        final Element element;
        final String groupId;
        final String artifactId;
        final String declaredArtifactId;
        final String version;
        final List<String> modules;
        final List<MavenDependency> dependencies;
        final Path mainSourceDir;
        final Path testSourceDir;
        final Path mainOutputDir;
        final Path testOutputDir;

        EffectiveProject(Element element) {
            this.element = element;
            this.groupId = textOf(element, "groupId");
            this.artifactId = textOf(element, "artifactId");
            this.declaredArtifactId = declaredArtifactId(element);
            this.version = textOf(element, "version");
            this.modules = childTexts(firstChild(element, "modules"), "module");
            var dependenciesElement = firstChild(element, "dependencies");
            var dependencies = new ArrayList<MavenDependency>();
            if (dependenciesElement != null) {
                for (var dependency : elements(dependenciesElement.getChildNodes())) {
                    if (!"dependency".equals(elementName(dependency))) continue;
                    var scope = textOf(dependency, "scope");
                    var groupId = textOf(dependency, "groupId");
                    var artifactId = textOf(dependency, "artifactId");
                    var version = textOf(dependency, "version");
                    if (isConcrete(groupId) && isConcrete(artifactId) && isConcrete(version)) {
                        dependencies.add(new MavenDependency(
                                groupId + ":" + artifactId + ":" + version,
                                scope));
                    }
                }
            }
            this.dependencies = List.copyOf(dependencies);
            var build = firstChild(element, "build");
            this.mainSourceDir = exactPath(textOf(build, "sourceDirectory"));
            this.testSourceDir = exactPath(textOf(build, "testSourceDirectory"));
            this.mainOutputDir = exactPath(textOf(build, "outputDirectory"));
            this.testOutputDir = exactPath(textOf(build, "testOutputDirectory"));
        }

        String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    private record MavenDependency(String coordinates, String scope) {
        boolean onMainClasspath() {
            return scope == null
                    || scope.isBlank()
                    || "compile".equals(scope)
                    || "provided".equals(scope)
                    || "system".equals(scope);
        }

        boolean onTestClasspath() {
            return !"import".equals(scope);
        }
    }

    private static List<Element> effectiveProjects(Document document) {
        var root = document.getDocumentElement();
        if ("project".equals(elementName(root))) return List.of(root);
        return elements(root.getChildNodes()).stream()
                .filter(element -> "project".equals(elementName(element)))
                .toList();
    }

    private static boolean collectEffectiveModules(
            Path workspaceRoot,
            Path moduleDir,
            EffectiveProject project,
            List<EffectiveProject> allProjects,
            Map<String, ResolvedModule> modules,
            Set<EffectiveProject> used) {
        if (!used.add(project)) return false;
        var projectPath = projectPath(workspaceRoot, moduleDir);
        modules.put(projectPath, new ResolvedModule(moduleDir, project));
        for (var moduleName : project.modules) {
            var childDir = moduleDir.resolve(moduleName).toAbsolutePath().normalize();
            var childPom = childDir.resolve("pom.xml");
            var childProject = findEffectiveProject(childPom, allProjects, used);
            if (childProject == null
                    || !collectEffectiveModules(workspaceRoot, childDir, childProject, allProjects, modules, used)) {
                return false;
            }
        }
        return true;
    }

    private static EffectiveProject findEffectiveProject(
            Path pom, List<EffectiveProject> projects, Set<EffectiveProject> used) {
        var identity = pomIdentity(pom);
        if (identity == null || identity.artifactId == null || identity.artifactId.isBlank()) return null;
        var matches = projects.stream()
                .filter(project -> !used.contains(project))
                .filter(project -> identity.matches(project))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private record PomIdentity(String groupId, String artifactId, String version) {
        boolean matches(EffectiveProject project) {
            if (!artifactId.equals(project.artifactId) && !artifactId.equals(project.declaredArtifactId)) return false;
            if (isConcrete(groupId) && !groupId.equals(project.groupId)) return false;
            return !isConcrete(version) || version.equals(project.version);
        }
    }

    private static String declaredArtifactId(Element project) {
        var artifactId = firstChild(project, "artifactId");
        if (artifactId == null) return null;
        for (Node node = artifactId.getNextSibling(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.TEXT_NODE && node.getTextContent().isBlank()) continue;
            if (node.getNodeType() != Node.COMMENT_NODE) break;
            var origin = node.getTextContent().trim();
            var line = origin.lastIndexOf(", line ");
            if (line != -1) origin = origin.substring(0, line);
            var firstColon = origin.indexOf(':');
            var lastColon = origin.lastIndexOf(':');
            if (firstColon != -1 && lastColon > firstColon) {
                return origin.substring(firstColon + 1, lastColon);
            }
            break;
        }
        return artifactId.getTextContent().trim();
    }

    private static PomIdentity pomIdentity(Path pom) {
        var document = parseXml(pom);
        if (document == null) return null;
        var project = document.getDocumentElement();
        var parent = firstChild(project, "parent");
        var groupId = textOf(project, "groupId");
        if (groupId == null) groupId = textOf(parent, "groupId");
        var version = textOf(project, "version");
        if (version == null) version = textOf(parent, "version");
        return new PomIdentity(groupId, textOf(project, "artifactId"), version);
    }

    private static String projectPath(Path workspaceRoot, Path moduleDir) {
        if (workspaceRoot.equals(moduleDir)) return ":";
        var relative = workspaceRoot.relativize(moduleDir);
        var parts = new ArrayList<String>();
        for (var part : relative) parts.add(part.toString());
        return ":" + String.join(":", parts);
    }

    private static List<String> childTexts(Element parent, String name) {
        if (parent == null) return List.of();
        var values = new ArrayList<String>();
        for (var child : elements(parent.getChildNodes())) {
            if (!name.equals(elementName(child))) continue;
            var value = child.getTextContent();
            if (value != null && !value.isBlank()) values.add(value.trim());
        }
        return List.copyOf(values);
    }

    private static Path exactPath(String value) {
        if (!isConcrete(value)) return null;
        try {
            var path = Paths.get(value);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isConcrete(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
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
        if (cachedClasspath != null && cachedSources != null) {
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
            LOG.fine("[maven-exec] command=" + String.join(" ", cmd) + " reason=dependency_resolution workingDir=" + workingDirectory);
            var processStarted = Instant.now();
            var process = trackProcess(new ProcessBuilder()
                    .command(cmd).directory(workingDirectory)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(output.toFile()).start());

            int result;
            try { result = process.waitFor(); } finally { untrackProcess(process); }
            LOG.info("[maven-exec] reason=dependency_resolution exit=" + result + " took="
                    + Duration.between(processStarted, Instant.now()).toMillis() + "ms");
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

    static MavenDependencies resolveModuleDependencies(
            Path pomXml,
            Path mavenHome,
            Map<String, String> envVars,
            ModuleGraph graph,
            ModuleGraph.ModuleInfo module,
            boolean testSources) {
        var buildRoot = normalizePath(pomXml).getParent();
        var selector = moduleSelector(buildRoot, module);
        var mvn = findMvnCommand(buildRoot, envVars);
        var cacheGoal = "module-classpath-" + (testSources ? "test" : "main");
        var cacheHome = cacheHome(envVars);
        var cached = loadCachedMavenDependencies(pomXml, cacheGoal, mavenHome, cacheHome, selector);
        var reactorOutputs = new HashSet<Path>();
        for (var info : graph.modules().values()) {
            if (info.mainOutputDir() != null) reactorOutputs.add(info.mainOutputDir());
            if (info.testOutputDir() != null) reactorOutputs.add(info.testOutputDir());
        }
        if (cached != null && cached.stream()
                .anyMatch(path -> !Files.exists(path) && !reactorOutputs.contains(path))) cached = null;
        try {
            if (cached == null) {
                var output = Files.createTempFile("jls-maven-module", ".txt");
                var mainClasspath = new LinkedHashSet<Path>();
                var testClasspath = new LinkedHashSet<Path>();
                try {
                    var resolved = false;
                    var phases = testSources ? List.of("test-compile") : List.of("compile", "test-compile");
                    for (var phase : phases) {
                        deleteIfExists(output);
                        var command = new ArrayList<String>();
                        command.add(mvn);
                        command.add("--batch-mode");
                        command.add("-DskipTests");
                        command.add("-Dmaven.compiler.failOnError=false");
                        command.add("-Dmaven.compiler.proc=none");
                        command.add(phase);
                        command.add(DEPENDENCY_LIST);
                        command.add("-pl");
                        command.add(selector);
                        command.add("-am");
                        command.add("-DincludeScope=test");
                        command.add("-DoutputAbsoluteArtifactFilename=true");
                        command.add("-DoutputFile=" + output);
                        LOG.fine("[maven-exec] command=" + String.join(" ", command)
                                + " reason=module_dependencies module=" + module.projectPath());
                        var started = Instant.now();
                        var process = trackProcess(new ProcessBuilder(command)
                                .directory(buildRoot.toFile())
                                .redirectError(ProcessBuilder.Redirect.INHERIT)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start());
                        int exit;
                        try { exit = process.waitFor(); } finally { untrackProcess(process); }
                        LOG.info("[maven-exec] reason=module_dependencies module=" + module.projectPath()
                                + " scope=" + (testSources ? "test" : "main")
                                + " phase=" + phase
                                + " exit=" + exit
                                + " took=" + Duration.between(started, Instant.now()).toMillis() + "ms");
                        if (exit == 0 && Files.exists(output)) {
                            resolved = true;
                            break;
                        }
                    }
                    if (!resolved) {
                        throw new RuntimeException(
                                "Maven dependency resolution failed for " + module.projectPath());
                    }
                    var reactorModules = new HashMap<String, ModuleGraph.ModuleInfo>();
                    for (var info : graph.modules().values()) {
                        reactorModules.put(info.coordinates(), info);
                    }
                    for (var line : Files.readAllLines(output)) {
                        var dependency = readResolvedDependency(line);
                        if (dependency == null) continue;
                        var path = dependency.path();
                        var reactorModule = reactorModules.get(dependency.coordinates());
                        var reactorOutput = false;
                        if (reactorModule != null) {
                            if (dependency.isTestArtifact()) {
                                path = reactorModule.testOutputDir();
                                reactorOutput = true;
                            } else if (dependency.classifier().isBlank()) {
                                path = reactorModule.mainOutputDir();
                                reactorOutput = true;
                            }
                        }
                        if (path == null || !reactorOutput && !Files.exists(path)) continue;
                        testClasspath.add(path);
                        if (dependency.onMainClasspath()) mainClasspath.add(path);
                    }
                } finally {
                    deleteIfExists(output);
                }
                if (module.mainOutputDir() != null) {
                    mainClasspath.add(module.mainOutputDir());
                    testClasspath.add(module.mainOutputDir());
                }
                if (module.testOutputDir() != null) testClasspath.add(module.testOutputDir());
                var main = Set.copyOf(mainClasspath);
                var test = Set.copyOf(testClasspath);
                storeCachedMavenDependencies(
                        pomXml,
                        mavenHome,
                        cacheHome,
                        Map.of("module-classpath-main", main, "module-classpath-test", test),
                        selector);
                cached = testSources ? test : main;
            }
            refreshMissingModuleOutputs(buildRoot, mvn, graph, module, cached, testSources);
            return moduleDependencies(graph, module, cached, testSources);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void refreshMissingModuleOutputs(
            Path buildRoot,
            String mvn,
            ModuleGraph graph,
            ModuleGraph.ModuleInfo module,
            Set<Path> classpath,
            boolean testSources) throws IOException, InterruptedException {
        var requiredOutputs = new LinkedHashSet<Path>();
        for (var candidate : graph.modules().values()) {
            if (candidate.projectPath().equals(module.projectPath())) continue;
            if (classpath.contains(candidate.mainOutputDir())) requiredOutputs.add(candidate.mainOutputDir());
            if (classpath.contains(candidate.testOutputDir())) requiredOutputs.add(candidate.testOutputDir());
        }
        if (requiredOutputs.stream().allMatch(Files::isDirectory)) return;
        var selector = moduleSelector(buildRoot, module);
        var command = List.of(
                mvn,
                "--batch-mode",
                "-DskipTests",
                "-Dmaven.compiler.failOnError=false",
                "-Dmaven.test.failure.ignore=true",
                testSources ? "test-compile" : "compile",
                "-pl", selector,
                "-am");
        LOG.fine("[maven-exec] command=" + String.join(" ", command)
                + " reason=missing_module_outputs module=" + module.projectPath());
        var started = Instant.now();
        var process = trackProcess(new ProcessBuilder(command)
                .directory(buildRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start());
        int exit;
        try { exit = process.waitFor(); } finally { untrackProcess(process); }
        LOG.info("[maven-exec] reason=missing_module_outputs module="
                + module.projectPath() + " exit=" + exit + " took="
                + Duration.between(started, Instant.now()).toMillis() + "ms");
        var missingOutputs = requiredOutputs.stream()
                .filter(path -> !Files.isDirectory(path))
                .toList();
        if (exit != 0 || !missingOutputs.isEmpty()) {
            throw new RuntimeException(
                    "Maven could not build required module outputs for " + module.projectPath()
                            + ": " + missingOutputs);
        }
    }

    private static String moduleSelector(Path buildRoot, ModuleGraph.ModuleInfo module) {
        var selector = buildRoot.relativize(module.projectDir()).toString();
        return selector.isBlank() ? "." : selector.replace(File.separatorChar, '/');
    }

    private static MavenDependencies moduleDependencies(
            ModuleGraph graph,
            ModuleGraph.ModuleInfo module,
            Set<Path> externalClasspath,
            boolean testSources) {
        var classpath = new LinkedHashSet<>(externalClasspath);
        var sources = new LinkedHashSet<Path>();
        for (var dependency : externalClasspath) {
            var name = dependency.getFileName().toString();
            if (!name.endsWith(".jar")) continue;
            var source = dependency.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
            if (Files.exists(source)) sources.add(source);
        }
        var sourceRoots = new LinkedHashSet<Path>();
        for (var source : module.sourceDirs()) {
            if (testSources || !source.equals(module.testSourceDir())) sourceRoots.add(source);
        }
        for (var dependency : graph.modules().values()) {
            if (dependency.projectPath().equals(module.projectPath())) continue;
            if (dependency.mainOutputDir() != null && classpath.contains(dependency.mainOutputDir())) {
                for (var source : dependency.sourceDirs()) {
                    if (!source.equals(dependency.testSourceDir())) sourceRoots.add(source);
                }
            }
            if (dependency.testOutputDir() != null
                    && classpath.contains(dependency.testOutputDir())
                    && dependency.testSourceDir() != null) {
                sourceRoots.add(dependency.testSourceDir());
            }
        }
        return new MavenDependencies(
                Set.copyOf(classpath), Set.copyOf(sources), Set.copyOf(sourceRoots));
    }

    // =========================================================================
    // Caching
    // =========================================================================

    static Set<Path> loadCachedMavenDependencies(Path pomXml, String goal, Path mavenHome, Path cacheHome, String modulePath) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome, modulePath);
        var cache = readCacheFile(cacheFile);
        if (cache == null || cache.version() != CACHE_VERSION || cache.entries() == null) {
            LOG.fine("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=no_cache_file");
            return null;
        }
        var entry = cache.entries().get(goal);
        if (entry == null) {
            LOG.fine("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=no_entry");
            return null;
        }
        var inputs = getCacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(entry.pomInputs(), inputs.pomInputs())
                || !Objects.equals(entry.settings(), inputs.settings())) {
            LOG.fine("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=fingerprint_mismatch");
            return null;
        }
        LOG.fine("[maven-cache] hit goal=" + goal + " module=" + modulePath + " deps=" + entry.dependencies().size());
        var result = new LinkedHashSet<Path>();
        for (var dependency : entry.dependencies()) result.add(Paths.get(dependency));
        return Set.copyOf(result);
    }

    private static ResolvedMavenModel loadCachedMavenModel(
            Path pomXml, Path mavenHome, Path cacheHome) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cache = readCacheFile(workspaceCacheFile(workspaceRoot, cacheHome));
        if (cache == null || cache.version() != CACHE_VERSION || cache.compilerLevel() == null || cache.moduleGraph() == null) return null;
        var inputs = getCacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(cache.moduleGraph().pomInputs(), inputs.pomInputs())
                || !Objects.equals(cache.moduleGraph().settings(), inputs.settings())
                || !Objects.equals(cache.compilerLevel().pomInputs(), inputs.pomInputs())
                || !Objects.equals(cache.compilerLevel().settings(), inputs.settings())) return null;

        var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var cached : cache.moduleGraph().modules()) {
            var sourceDirs = cached.sourceDirs() == null
                    ? List.<Path>of()
                    : cached.sourceDirs().stream().map(Paths::get).toList();
            modules.put(cached.projectPath(), new ModuleGraph.ModuleInfo(
                    cached.projectPath(),
                    Paths.get(cached.projectDir()),
                    sourceDirs,
                    cached.testSourceDir() == null ? null : Paths.get(cached.testSourceDir()),
                    cached.mainOutputDir() == null ? null : Paths.get(cached.mainOutputDir()),
                    cached.testOutputDir() == null ? null : Paths.get(cached.testOutputDir()),
                    List.of(),
                    cached.moduleDeps() == null ? List.of() : cached.moduleDeps(),
                    cached.testModuleDeps() == null ? List.of() : cached.testModuleDeps(),
                    cached.sourceCompatibility(),
                    cached.compilerArgs() == null ? List.of() : cached.compilerArgs(),
                    cached.artifactId(),
                    cached.coordinates()));
        }
        var compiler = cache.compilerLevel();
        return new ResolvedMavenModel(
                new ModuleGraph(Collections.unmodifiableMap(modules)),
                new CompilerArgs(
                        compiler.args() == null ? List.of() : compiler.args(),
                        compiler.source() == null ? "none" : compiler.source(),
                        compiler.mixedModules()));
    }

    private static void storeCachedMavenModel(
            Path pomXml, Path mavenHome, Path cacheHome, ResolvedMavenModel model) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome);
        var cache = readCacheFile(cacheFile);
        var inputs = getCacheInputs(workspaceRoot, mavenHome);
        var modules = model.graph().modules().values().stream()
                .map(module -> new CachedMavenModule(
                        module.projectPath(),
                        module.projectDir().toString(),
                        module.sourceDirs().stream().map(Path::toString).toList(),
                        module.testSourceDir() == null ? null : module.testSourceDir().toString(),
                        module.mainOutputDir() == null ? null : module.mainOutputDir().toString(),
                        module.testOutputDir() == null ? null : module.testOutputDir().toString(),
                        module.moduleDeps(),
                        module.testModuleDeps(),
                        module.sourceCompatibility(),
                        module.compilerArgs(),
                        module.artifactId(),
                        module.coordinates()))
                .toList();
        var compilerArgs = model.compilerArgs();
        var compiler = new MavenCompilerLevelCacheEntry(
                inputs.pomInputs(), inputs.settings(),
                compilerArgs.args(), compilerArgs.source(), compilerArgs.mixedModules());
        var graph = new MavenModuleGraphCacheEntry(inputs.pomInputs(), inputs.settings(), modules);
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(new MavenInferenceCacheFile(
                        CACHE_VERSION,
                        cache == null || cache.entries() == null ? Map.of() : cache.entries(),
                        compiler,
                        graph), writer);
            }
        } catch (IOException e) {
            LOG.fine(String.format("Failed to write Maven model cache %s: %s", cacheFile, e.getMessage()));
        }
    }

    static void storeCachedMavenDependencies(
            Path pomXml, String goal, Path mavenHome, Path cacheHome, Set<Path> dependencies, String modulePath) {
        storeCachedMavenDependencies(
                pomXml, mavenHome, cacheHome, Map.of(goal, dependencies), modulePath);
    }

    private static void storeCachedMavenDependencies(
            Path pomXml,
            Path mavenHome,
            Path cacheHome,
            Map<String, Set<Path>> dependencies,
            String modulePath) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome, modulePath);
        var cache = readCacheFile(cacheFile);
        var entries = new LinkedHashMap<String, MavenInferenceCacheEntry>();
        if (cache != null && cache.entries() != null) entries.putAll(cache.entries());
        var inputs = getCacheInputs(workspaceRoot, mavenHome);
        for (var dependencySet : dependencies.entrySet()) {
            var dependencyStrings = dependencySet.getValue().stream()
                    .map(path -> path.toAbsolutePath().normalize().toString()).sorted().toList();
            entries.put(
                    dependencySet.getKey(),
                    new MavenInferenceCacheEntry(inputs.pomInputs(), inputs.settings(), dependencyStrings));
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(new MavenInferenceCacheFile(
                        CACHE_VERSION,
                        entries,
                        cache == null ? null : cache.compilerLevel(),
                        cache == null ? null : cache.moduleGraph()), writer);
            }
        } catch (IOException e) {
            LOG.fine(String.format("Failed to write Maven cache %s: %s", cacheFile, e.getMessage()));
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
        return workspacePomFiles(workspaceRoot).stream()
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
        var inferred = resolveMavenModel(pomXml, mavenHome, envVars).compilerArgs();
        logCompilerArgsInference("maven_model", inferred, started);
        return inferred;
    }

    private static void logCompilerArgsInference(String source, CompilerArgs inferred, Instant started) {
        LOG.info(String.format("[perf] infer_config_maven_compiler source=%s selected=%s args=%d took=%dms",
                source, inferred.source(), inferred.args().size(),
                Duration.between(started, Instant.now()).toMillis()));
    }

    private static CompilerArgs parseCompilerArgs(Element project) {
        var release = property(project, "maven.compiler.release");
        if (isConcreteJavaLevel(release)) return new CompilerArgs(List.of("--release", release), "maven_release", false);
        var pluginRelease = compilerPluginRelease(project);
        if (isConcreteJavaLevel(pluginRelease)) return new CompilerArgs(List.of("--release", pluginRelease), "maven_release", false);
        var source = property(project, "maven.compiler.source");
        var target = property(project, "maven.compiler.target");
        if (isConcreteJavaLevel(source) && isConcreteJavaLevel(target)) return sourceTargetArgs(source, target);
        return compilerPluginSourceTarget(project);
    }

    private static String compilerPluginRelease(Element project) {
        var plugin = compilerPlugin(project);
        return plugin == null ? null : nestedText(plugin, "configuration", "release");
    }

    private static CompilerArgs compilerPluginSourceTarget(Element project) {
        var plugin = compilerPlugin(project);
        if (plugin == null) return CompilerArgs.none();
        var source = nestedText(plugin, "configuration", "source");
        var target = nestedText(plugin, "configuration", "target");
        return !isConcreteJavaLevel(source) || !isConcreteJavaLevel(target)
                ? CompilerArgs.none() : sourceTargetArgs(source, target);
    }

    private static Element compilerPlugin(Element project) {
        var plugins = project.getElementsByTagNameNS("*", "plugin");
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
            var command = new ArrayList<>(List.of(
                findMvnCommand(normalizePath(pomXml).getParent(), envVars),
                "--batch-mode", EFFECTIVE_POM,
                "-Dverbose",
                "-Doutput=" + output.toAbsolutePath()));
            var workingDir = normalizePath(pomXml).getParent().toFile();
            LOG.fine("[maven-exec] command=" + String.join(" ", command) + " reason=effective_pom workingDir=" + workingDir);
            var processStarted = Instant.now();
            var process = trackProcess(new ProcessBuilder()
                    .command(command).directory(workingDir)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
            int result;
            try { result = process.waitFor(); } finally { untrackProcess(process); }
            LOG.info("[maven-exec] reason=effective_pom exit=" + result + " took="
                    + Duration.between(processStarted, Instant.now()).toMillis() + "ms");
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

    static Path findBuildRoot(Path workspaceRoot) {
        var workspace = normalizePath(workspaceRoot);
        var buildRoot = workspace;
        for (var directory = workspace.getParent(); directory != null; directory = directory.getParent()) {
            var pom = directory.resolve("pom.xml");
            if (Files.exists(pom) && declaresModuleContaining(pom, directory, workspace)) buildRoot = directory;
        }
        return buildRoot;
    }

    private static Path findProjectRoot(Path workspace) {
        for (var directory = workspace; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve("pom.xml"))) return directory;
        }
        return null;
    }

    static String modulePath(ModuleGraph graph, Path buildRoot, Path workspaceRoot) {
        var module = graph.moduleForFile(normalizePath(workspaceRoot));
        if (module.isEmpty() || module.get().projectDir().equals(normalizePath(buildRoot))) return null;
        return normalizePath(buildRoot).relativize(module.get().projectDir()).toString();
    }

    private static boolean declaresModuleContaining(Path pom, Path projectDir, Path workspace) {
        var document = parseXml(pom);
        if (document == null) return false;
        var moduleNames = new ArrayList<String>();
        var directModules = firstChild(document.getDocumentElement(), "modules");
        moduleNames.addAll(childTexts(directModules, "module"));
        var profiles = firstChild(document.getDocumentElement(), "profiles");
        if (profiles != null) {
            for (var profile : elements(profiles.getChildNodes())) {
                moduleNames.addAll(childTexts(firstChild(profile, "modules"), "module"));
            }
        }
        for (var moduleName : moduleNames) {
            if (!isConcrete(moduleName)) continue;
            var module = projectDir.resolve(moduleName).toAbsolutePath().normalize();
            if (module.getFileName() != null && module.getFileName().toString().equals("pom.xml")) {
                module = module.getParent();
            }
            if (workspace.startsWith(module)) return true;
        }
        return false;
    }

    // warnings queued during Maven resolution for the client to display.
    // Flushed by JavaLanguageServer after createCompilers().
    private static final List<String> pendingWarnings = Collections.synchronizedList(new ArrayList<>());

    // Track active Maven subprocesses for cleanup on shutdown
    private static final List<Process> activeProcesses = Collections.synchronizedList(new ArrayList<>());

    static List<String> flushWarnings() {
        var copy = new ArrayList<>(pendingWarnings);
        pendingWarnings.clear();
        return copy;
    }

    /** Kill all tracked Maven subprocesses. Call on server shutdown. */
    static void destroyAllProcesses() {
        var snapshot = new ArrayList<>(activeProcesses);
        activeProcesses.clear();
        for (var process : snapshot) {
            if (process.isAlive()) {
                process.destroyForcibly();
                LOG.info("[maven] killed orphaned subprocess pid=" + process.pid());
            }
        }
    }

    static Process trackProcess(Process process) {
        activeProcesses.add(process);
        return process;
    }

    static void untrackProcess(Process process) {
        activeProcesses.remove(process);
    }

    static String findMvnCommand(Path projectDir, Map<String, String> envVars) {
        // 1. Prefer mvnd (Maven Daemon) — dramatically faster for large reactors
        var mvnd = findMvnd(envVars);
        if (mvnd != null) {
            LOG.info("[maven] using mvnd: " + mvnd);
            return mvnd;
        }

        // 2. Try project wrapper (mvnw)
        var wrapperName = File.separatorChar == '\\' ? "mvnw.cmd" : "mvnw";
        for (var dir = projectDir; dir != null; dir = dir.getParent()) {
            var candidate = dir.resolve(wrapperName);
            if (Files.isRegularFile(candidate)) {
                if (!Files.isExecutable(candidate)) {
                    try {
                        var perms = Files.getPosixFilePermissions(candidate);
                        perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                        Files.setPosixFilePermissions(candidate, perms);
                        LOG.info("[maven] set executable bit on wrapper: " + candidate);
                    } catch (IOException | UnsupportedOperationException e) {
                        LOG.warning("[maven] wrapper not executable and chmod failed: " + candidate + " — " + e.getMessage());
                        continue;
                    }
                }
                if (!validateWrapper(candidate)) {
                    var msg = "Maven wrapper '" + candidate + "' is broken (missing .mvn/wrapper/ files?). Falling back to system mvn. Fix: run 'mvn -N wrapper:wrapper' in your project.";
                    LOG.warning("[maven] " + msg);
                    pendingWarnings.add(msg);
                    break;
                }
                LOG.fine("[maven] using wrapper: " + candidate);
                return candidate.toString();
            }
        }

        // 3. System mvn
        LOG.fine("[maven] using system mvn");
        return getMvnCommand(envVars);
    }

    private static String findMvnd(Map<String, String> envVars) {
        var name = File.separatorChar == '\\' ? "mvnd.cmd" : "mvnd";
        // 1. MVND_HOME env var (canonical, set by sdkman and manual installs)
        var mvndHome = envVars.getOrDefault("MVND_HOME", System.getenv("MVND_HOME"));
        if (mvndHome != null && !mvndHome.isBlank()) {
            var candidate = Path.of(mvndHome, "bin", name);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        // 2. PATH (covers brew, macports, manual, sdkman, any install that modifies PATH)
        return findExecutableOnPath(name, envVars);
    }

    private static boolean validateWrapper(Path wrapper) {
        try {
            var process = new ProcessBuilder()
                    .command(wrapper.toString(), "--version")
                    .directory(wrapper.getParent().toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            var exit = process.waitFor();
            return exit == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    static String getMvnCommand(Map<String, String> envVars) {
        var mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd", envVars);
            if (mvnCommand == null) mvnCommand = findExecutableOnPath("mvn.bat", envVars);
        }
        return mvnCommand == null ? "mvn" : mvnCommand;
    }

    static Path readDependency(String line) {
        var dependency = readResolvedDependency(line);
        return dependency == null ? NOT_FOUND : dependency.path();
    }

    private static ResolvedDependency readResolvedDependency(String line) {
        var match = DEPENDENCY.matcher(line);
        if (!match.matches()) return null;
        var parts = match.group(1).split(":");
        if (parts.length < 5) return null;
        var coordinates = parts[0] + ":" + parts[1] + ":" + parts[parts.length - 2];
        var classifier = parts.length > 5 ? parts[3] : "";
        return new ResolvedDependency(
                coordinates,
                parts[2],
                classifier,
                parts[parts.length - 1],
                Paths.get(match.group(2)));
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
        var result = new ArrayList<Path>();
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    var name = dir.getFileName();
                    if (name != null && !dir.equals(workspaceRoot)
                            && (name.toString().equals("target") || name.toString().equals("build"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals("pom.xml") && !isGeneratedPom(file)) {
                        result.add(normalizePath(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            result.sort(Comparator.naturalOrder());
            return List.copyOf(result);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String property(Element project, String name) {
        return nestedText(project, "properties", name);
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
            if (children.item(i) instanceof Element el && localName.equals(elementName(el))) return el;
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

    private static String elementName(Element element) {
        return element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
    }

    private static String nestedText(Element parent, String childName, String grandchildName) {
        return textOf(firstChild(parent, childName), grandchildName);
    }
}
