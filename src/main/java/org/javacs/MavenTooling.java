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
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
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
    private static final int CACHE_VERSION = 7;

    static final Path NOT_FOUND = Paths.get("");

    private static final String EFFECTIVE_POM =
            "org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom";

    private MavenTooling() {}

    static final record FileFingerprint(String path, long size, long contentHash) {
        FileFingerprint() { this(null, 0, 0); }
    }

    static final record MavenInferenceCacheFile(
            int version,
            FileFingerprint settings,
            List<FileFingerprint> pomInputs,
            List<CachedMavenModule> modules,
            Map<String, List<String>> dependencyGoals) {}
    static final record CachedMavenModule(
            String projectPath,
            String projectDir,
            List<String> sourceDirs,
            List<String> testSourceDirs,
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
    static final record MavenWorkspace(Path buildRoot, ModuleGraph graph) {}

    // =========================================================================
    // Module Graph Resolution
    // =========================================================================

    static ModuleGraph resolveModuleGraph(
            Path workspaceRoot, Path mavenHome, Map<String, String> envVars) {
        var pom = normalizePath(workspaceRoot).resolve("pom.xml");
        if (!Files.exists(pom)) return ModuleGraph.EMPTY;
        refreshCacheInputsSnapshot(workspaceRoot, mavenHome);
        return resolveMavenModel(pom, mavenHome, envVars);
    }

    static MavenWorkspace resolveWorkspace(
            Path workspaceRoot, Path candidateBuildRoot, Path mavenHome, Map<String, String> envVars) {
        var workspace = normalizePath(workspaceRoot);
        var buildRoot = normalizePath(candidateBuildRoot);
        var graph = resolveModuleGraph(buildRoot, mavenHome, envVars);
        if (ownsWorkspace(graph, workspace)) {
            return new MavenWorkspace(buildRoot, graph);
        }

        var projectRoot = findProjectRoot(workspace);
        if (projectRoot == null || projectRoot.equals(buildRoot)) {
            return new MavenWorkspace(buildRoot, graph);
        }
        var result = new MavenWorkspace(projectRoot, resolveModuleGraph(projectRoot, mavenHome, envVars));
        return result;
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

    static ModuleGraph parseEffectivePomModuleGraph(Path workspaceRoot, Document document) {
        var projectElements = effectiveProjects(document);
        if (projectElements.isEmpty()) {
            return ModuleGraph.EMPTY;
        }

        var projects = projectElements.stream().map(EffectiveProject::new).toList();
        EffectiveProject rootProject;
        if (projects.size() == 1) {
            rootProject = projects.getFirst();
        } else {
            rootProject = findEffectiveProject(workspaceRoot.resolve("pom.xml"), projects, Set.of());
            if (rootProject == null) {
                return ModuleGraph.EMPTY;
            }
        }

        var modules = new LinkedHashMap<String, ResolvedModule>();
        if (!collectEffectiveModules(
                workspaceRoot.toAbsolutePath().normalize(),
                workspaceRoot.toAbsolutePath().normalize(),
                rootProject,
                projects,
                modules,
                new HashSet<>())) {
            return ModuleGraph.EMPTY;
        }

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
            var sourceCompatibility = compilerArgs.size() < 2 ? null : compilerArgs.get(1);
            result.put(entry.getKey(), new ModuleGraph.ModuleInfo(
                    entry.getKey(),
                    module.directory,
                    sourceDirs,
                    project.testSourceDir == null ? List.of() : List.of(project.testSourceDir),
                    project.mainOutputDir,
                    project.testOutputDir,
                    List.of(),
                    dependencies,
                    testDependencies,
                    sourceCompatibility,
                    compilerArgs,
                    project.artifactId,
                    project.coordinates()));
        }
        return new ModuleGraph(Collections.unmodifiableMap(result));
    }

    private static ModuleGraph resolveMavenModel(
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
        if (effectivePom == NOT_FOUND) {
            return ModuleGraph.EMPTY;
        }
        try {
            var document = parseXml(effectivePom);
            if (document == null) {
                return ModuleGraph.EMPTY;
            }
            var graph = parseEffectivePomModuleGraph(normalizePath(pomXml).getParent(), document);
            storeCachedMavenModel(pomXml, mavenHome, cacheHome, graph);
            CacheAudit.store("infer_config.maven_model");
            return graph;
        } finally {
            deleteIfExists(effectivePom);
        }
    }

    private record ResolvedModule(Path directory, EffectiveProject project) {}

    private static final class EffectiveProject {
        final Element element;
        final String groupId;
        final String artifactId;
        final String declaredArtifactId;
        final PomIdentity parent;
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
            this.parent = pomIdentity(firstChild(element, "parent"));
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
        var document = parseXml(pom);
        if (document == null) return null;
        var identity = pomIdentity(document.getDocumentElement());
        if (identity == null || identity.artifactId == null || identity.artifactId.isBlank()) return null;
        var matches = projects.stream()
                .filter(project -> !used.contains(project))
                .filter(project -> identity.matches(project))
                .toList();
        if (matches.size() > 1) {
            matches = matches.stream().filter(identity::matchesParent).toList();
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private record PomIdentity(String groupId, String artifactId, String version, PomIdentity parent) {
        boolean matches(EffectiveProject project) {
            if (!artifactId.equals(project.artifactId) && !artifactId.equals(project.declaredArtifactId)) return false;
            if (isConcrete(groupId) && !groupId.equals(project.groupId)) return false;
            return !isConcrete(version) || version.equals(project.version);
        }

        boolean matchesParent(EffectiveProject project) {
            if (parent != null && project.parent != null) {
                if (isConcrete(parent.groupId) && !parent.groupId.equals(project.parent.groupId)) return false;
                if (isConcrete(parent.artifactId) && !parent.artifactId.equals(project.parent.artifactId)) return false;
                if (isConcrete(parent.version) && !parent.version.equals(project.parent.version)) return false;
            }
            return true;
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

    private static PomIdentity pomIdentity(Element project) {
        if (project == null) return null;
        var parent = firstChild(project, "parent");
        var groupId = textOf(project, "groupId");
        if (groupId == null) groupId = textOf(parent, "groupId");
        var version = textOf(project, "version");
        if (version == null) version = textOf(parent, "version");
        return new PomIdentity(groupId, textOf(project, "artifactId"), version, pomIdentity(parent));
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

    static MavenDependencies resolveModuleDependencies(
            Path pomXml, Path mavenHome, Map<String, String> envVars, ModuleGraph graph,
            ModuleGraph.ModuleInfo module, boolean testSources) {
        var buildRoot = normalizePath(pomXml).getParent();
        var selector = moduleSelector(buildRoot, module);
        var cacheHome = cacheHome(envVars);
        var scope = testSources ? "test" : "main";
        var classpath = loadCachedMavenDependencies(pomXml, "source-classpath-" + scope, mavenHome, cacheHome, selector);
        var sourceRoots = loadCachedMavenDependencies(pomXml, "source-roots-" + scope, mavenHome, cacheHome, selector);
        var needsResolution = classpath == null || sourceRoots == null || classpath.stream().anyMatch(path -> !Files.exists(path));
        if (needsResolution) {
            var resolved = resolveSourceDependencies(buildRoot, envVars, module, selector);
            var cache = new LinkedHashMap<String, Set<Path>>();
            for (var selectedScope : List.of("main", "test")) {
                cache.put("source-classpath-" + selectedScope, propertyPaths(resolved, selectedScope + ".path."));
                var roots = new LinkedHashSet<>(graph.transitiveSourceDirs(module.projectPath(), selectedScope.equals("test")));
                roots.addAll(propertyPaths(resolved, selectedScope + ".source."));
                cache.put("source-roots-" + selectedScope, Set.copyOf(roots));
            }
            // An incomplete result is useful now, but must not become a permanent disk cache.
            if ("true".equals(resolved.getProperty("complete"))) {
                storeCachedMavenDependencies(pomXml, mavenHome, cacheHome, cache, selector);
            }
            classpath = cache.get("source-classpath-" + scope);
            sourceRoots = cache.get("source-roots-" + scope);
        }
        var external = graph.externalClasspath(classpath);
        var sources = new LinkedHashSet<Path>();
        for (var dependency : external) {
            var name = dependency.getFileName().toString();
            if (!name.endsWith(".jar")) continue;
            var source = dependency.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
            if (Files.isRegularFile(source)) sources.add(source);
        }
        return new MavenDependencies(external, Set.copyOf(sources), sourceRoots);
    }

    private static Properties resolveSourceDependencies(
            Path buildRoot, Map<String, String> envVars, ModuleGraph.ModuleInfo module, String selector) {
        var result = new Properties();
        Path output = null;
        try {
            output = Files.createTempFile("jls-maven-dependencies", ".properties");
            var command = List.of(findMvnCommand(buildRoot, envVars), "--batch-mode",
                    "-Dmaven.ext.class.path=" + dependencyExtension(),
                    "-Djls.dependencies.output=" + output,
                    "-Djls.dependencies.project=" + module.coordinates(),
                    "-pl", selector, "-am", EFFECTIVE_POM);
            var started = System.nanoTime();
            var process = trackProcess(new ProcessBuilder(command).directory(buildRoot.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
            int exit;
            try { exit = process.waitFor(); } finally { untrackProcess(process); }
            try (var input = Files.newInputStream(output)) { result.load(input); }
            LOG.info("[maven] source_dependencies module=" + module.projectPath() + " exit=" + exit
                    + " complete=" + result.getProperty("complete", "false")
                    + " ms=" + (System.nanoTime() - started) / 1_000_000);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            LOG.warning("[maven] partial_dependencies module=" + module.projectPath() + " cause=" + failure.getMessage());
        } finally {
            if (output != null) deleteIfExists(output);
        }
        return result;
    }

    private static Set<Path> propertyPaths(Properties properties, String prefix) {
        var paths = new LinkedHashSet<Path>();
        properties.stringPropertyNames().stream().filter(key -> key.startsWith(prefix)).sorted()
                .forEach(key -> paths.add(Path.of(properties.getProperty(key))));
        return Set.copyOf(paths);
    }

    private static Path dependencyExtension;

    private static synchronized Path dependencyExtension() throws IOException {
        if (dependencyExtension != null) {
            return dependencyExtension;
        }
        try (var resource = MavenTooling.class.getResourceAsStream("/org/javacs/maven/jls-maven-extension.jar")) {
            if (resource == null) throw new IOException("Missing Maven dependency extension");
            var jar = Files.createTempFile("jls-maven-extension", ".jar");
            Files.copy(resource, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            jar.toFile().deleteOnExit();
            dependencyExtension = jar;
            return jar;
        }
    }

    private static String moduleSelector(Path buildRoot, ModuleGraph.ModuleInfo module) {
        var selector = buildRoot.relativize(module.projectDir()).toString();
        return selector.isBlank() ? "." : selector.replace(File.separatorChar, '/');
    }

    // =========================================================================
    // Caching
    // =========================================================================

    static Set<Path> loadCachedMavenDependencies(Path pomXml, String goal, Path mavenHome, Path cacheHome, String modulePath) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome, modulePath);
        var cache = readCacheFile(cacheFile);
        if (cache == null || cache.version() != CACHE_VERSION || cache.dependencyGoals() == null) {
            LOG.fine("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=no_cache_file");
            return null;
        }
        var dependencies = cache.dependencyGoals().get(goal);
        if (dependencies == null) {
            LOG.fine("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=no_entry");
            return null;
        }
        var inputs = getCacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(cache.pomInputs(), inputs.pomInputs())
                || !Objects.equals(cache.settings(), inputs.settings())) {
            LOG.fine("[maven-cache] miss goal=" + goal + " module=" + modulePath + " reason=fingerprint_mismatch");
            return null;
        }
        LOG.fine("[maven-cache] hit goal=" + goal + " module=" + modulePath + " deps=" + dependencies.size());
        var result = new LinkedHashSet<Path>();
        for (var dependency : dependencies) result.add(Paths.get(dependency));
        return Set.copyOf(result);
    }

    private static ModuleGraph loadCachedMavenModel(
            Path pomXml, Path mavenHome, Path cacheHome) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cache = readCacheFile(workspaceCacheFile(workspaceRoot, cacheHome, null));
        if (cache == null || cache.version() != CACHE_VERSION || cache.modules() == null) {
            return null;
        }
        var inputs = getCacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(cache.pomInputs(), inputs.pomInputs())
                || !Objects.equals(cache.settings(), inputs.settings())) {
            return null;
        }

        var modules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var cached : cache.modules()) {
            var sourceDirs = cached.sourceDirs() == null
                    ? List.<Path>of()
                    : cached.sourceDirs().stream().map(Paths::get).toList();
            modules.put(cached.projectPath(), new ModuleGraph.ModuleInfo(
                    cached.projectPath(),
                    Paths.get(cached.projectDir()),
                    sourceDirs,
                    cached.testSourceDirs() == null ? List.of() : cached.testSourceDirs().stream().map(Paths::get).toList(),
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
        return new ModuleGraph(Collections.unmodifiableMap(modules));
    }

    private static void storeCachedMavenModel(
            Path pomXml, Path mavenHome, Path cacheHome, ModuleGraph graph) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome, null);
        var cache = readCacheFile(cacheFile);
        var inputs = getCacheInputs(workspaceRoot, mavenHome);
        var modules = graph.modules().values().stream()
                .map(module -> new CachedMavenModule(
                        module.projectPath(),
                        module.projectDir().toString(),
                        module.sourceDirs().stream().map(Path::toString).toList(),
                        module.testSourceDirs().stream().map(Path::toString).toList(),
                        module.mainOutputDir() == null ? null : module.mainOutputDir().toString(),
                        module.testOutputDir() == null ? null : module.testOutputDir().toString(),
                        module.moduleDeps(),
                        module.testModuleDeps(),
                        module.sourceCompatibility(),
                        module.compilerArgs(),
                        module.artifactId(),
                        module.coordinates()))
                .toList();
        // Preserve any dependency goals already written for this (root) module, but only if
        // their fingerprint still matches the current generation; otherwise drop them.
        var goals = cache != null && cache.dependencyGoals() != null
                && Objects.equals(cache.pomInputs(), inputs.pomInputs())
                && Objects.equals(cache.settings(), inputs.settings())
                ? cache.dependencyGoals() : Map.<String, List<String>>of();
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(new MavenInferenceCacheFile(
                        CACHE_VERSION,
                        inputs.settings(),
                        inputs.pomInputs(),
                        modules,
                        goals), writer);
            }
        } catch (IOException e) {
            LOG.fine(String.format("Failed to write Maven model cache %s: %s", cacheFile, e.getMessage()));
        }
    }

    static void storeCachedMavenDependencies(
            Path pomXml,
            Path mavenHome,
            Path cacheHome,
            Map<String, Set<Path>> dependencies,
            String modulePath) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome, modulePath);
        var cache = readCacheFile(cacheFile);
        var inputs = getCacheInputs(workspaceRoot, mavenHome);
        // Keep existing modules/goals only if their fingerprint still matches this generation.
        var sameGeneration = cache != null
                && Objects.equals(cache.pomInputs(), inputs.pomInputs())
                && Objects.equals(cache.settings(), inputs.settings());
        var goals = new LinkedHashMap<String, List<String>>();
        if (sameGeneration && cache.dependencyGoals() != null) goals.putAll(cache.dependencyGoals());
        var modules = sameGeneration && cache.modules() != null ? cache.modules() : List.<CachedMavenModule>of();
        for (var dependencySet : dependencies.entrySet()) {
            var dependencyStrings = dependencySet.getValue().stream()
                    .map(path -> path.toAbsolutePath().normalize().toString()).sorted().toList();
            goals.put(dependencySet.getKey(), dependencyStrings);
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(new MavenInferenceCacheFile(
                        CACHE_VERSION,
                        inputs.settings(),
                        inputs.pomInputs(),
                        modules,
                        goals), writer);
            }
        } catch (IOException e) {
            LOG.fine(String.format("Failed to write Maven cache %s: %s", cacheFile, e.getMessage()));
        }
    }

    private static MavenInferenceCacheFile readCacheFile(Path cacheFile) {
        if (!Files.exists(cacheFile)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            var cache = GSON.fromJson(reader, MavenInferenceCacheFile.class);
            return cache;
        } catch (IOException | JsonParseException e) {
            LOG.fine(String.format("Failed to read Maven cache file %s: %s", cacheFile, e.getMessage()));
            return null;
        }
    }

    static Path workspaceCacheFile(Path workspaceRoot, Path cacheHome, String modulePath) {
        var normalizedRoot = normalizePath(workspaceRoot);
        var dir = workspaceCacheDirectory(normalizedRoot, cacheHome);
        var moduleDir = (modulePath == null || modulePath.isBlank()) ? "root" : modulePath.replace(",", "_").replace("/", "_");
        var result = dir.resolve(moduleDir).resolve("maven-inference.json");
        return result;
    }

    private static Path workspaceCacheDirectory(Path workspaceRoot, Path cacheHome) {
        var name = workspaceRoot.getFileName() == null ? "workspace" : workspaceRoot.getFileName().toString();
        return cacheHome.resolve("jls").resolve(name + "-" + shortHash(workspaceRoot.toString()));
    }

    private static MavenCacheInputs cacheInputs(Path workspaceRoot, Path mavenHome) {
        return new MavenCacheInputs(workspacePomFingerprints(workspaceRoot), fingerprintIfExists(mavenHome.resolve("settings.xml")));
    }

    private static List<FileFingerprint> workspacePomFingerprints(Path workspaceRoot) {
        var poms = workspacePomFiles(workspaceRoot);
        var fingerprints = poms.stream().map(MavenTooling::fingerprintExistingFile)
                .sorted(Comparator.comparing(FileFingerprint::path)).toList();
        return fingerprints;
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

    private static List<String> parseCompilerArgs(Element project) {
        var release = nestedText(project, "properties", "maven.compiler.release");
        if (isConcrete(release)) return List.of("--release", release);
        var pluginRelease = compilerPluginRelease(project);
        if (isConcrete(pluginRelease)) return List.of("--release", pluginRelease);
        var source = nestedText(project, "properties", "maven.compiler.source");
        var target = nestedText(project, "properties", "maven.compiler.target");
        if (isConcrete(source) && isConcrete(target)) return sourceTargetArgs(source, target);
        return compilerPluginSourceTarget(project);
    }

    private static String compilerPluginRelease(Element project) {
        var plugin = compilerPlugin(project);
        return plugin == null ? null : nestedText(plugin, "configuration", "release");
    }

    private static List<String> compilerPluginSourceTarget(Element project) {
        var plugin = compilerPlugin(project);
        if (plugin == null) return List.of();
        var source = nestedText(plugin, "configuration", "source");
        var target = nestedText(plugin, "configuration", "target");
        return !isConcrete(source) || !isConcrete(target)
                ? List.of() : sourceTargetArgs(source, target);
    }

    private static Element compilerPlugin(Element project) {
        var plugins = project.getElementsByTagNameNS("*", "plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            if (!(plugins.item(i) instanceof Element element)) continue;
            if ("maven-compiler-plugin".equals(textOf(element, "artifactId"))) return element;
        }
        return null;
    }

    private static List<String> sourceTargetArgs(String source, String target) {
        return List.of("-source", source, "-target", target);
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

    // Track active Maven subprocesses for cleanup on shutdown
    private static final List<Process> activeProcesses = Collections.synchronizedList(new ArrayList<>());

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
                        perms.add(PosixFilePermission.OWNER_EXECUTE);
                        Files.setPosixFilePermissions(candidate, perms);
                        LOG.info("[maven] set executable bit on wrapper: " + candidate);
                    } catch (IOException | UnsupportedOperationException e) {
                        LOG.warning("[maven] wrapper not executable and chmod failed: " + candidate + " — " + e.getMessage());
                        continue;
                    }
                }
                if (wrapperJarMismatch(dir)) {
                    LOG.warning("[maven] wrapper '" + candidate + "' has a SHA-256 mismatch on maven-wrapper.jar."
                            + " Falling back to system mvn; run 'mvn -N wrapper:wrapper' to repair it.");
                    break;
                }
                LOG.fine("[maven] using wrapper: " + candidate);
                return candidate.toString();
            }
        }

        // 3. System mvn
        LOG.fine("[maven] using system mvn");
        var command = getMvnCommand(envVars);
        return command;
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

    // mvnw self-validates maven-wrapper.jar against wrapperSha256Sum and exits 1 on mismatch (no fallback).
    // Detect that on disk without spawning a process, so a compromised/stale wrapper falls back to system mvn.
    private static boolean wrapperJarMismatch(Path dir) {
        var props = dir.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.properties");
        if (!Files.isRegularFile(props)) return false;
        try {
            String declared = null;
            for (var line : Files.readAllLines(props)) {
                var trimmed = line.strip();
                if (trimmed.startsWith("wrapperSha256Sum=")) {
                    declared = trimmed.substring("wrapperSha256Sum=".length()).strip();
                    break;
                }
            }
            if (declared == null || declared.isEmpty()) return false; // nothing to validate → wrapper usable
            var jar = dir.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.jar");
            if (!Files.isRegularFile(jar)) return true; // sum declared but jar missing → mvnw will fail
            var sha = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
            var actual = java.util.HexFormat.of().formatHex(sha);
            return !actual.equalsIgnoreCase(declared);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return false; // on error, let mvnw try rather than suppress it
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
            var result = factory.newDocumentBuilder().parse(xmlFile.toFile());
            return result;
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

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            result.sort(Comparator.naturalOrder());
            return List.copyOf(result);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
