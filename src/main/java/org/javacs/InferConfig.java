package org.javacs;

import com.google.devtools.build.lib.analysis.AnalysisProtos;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class InferConfig {
    private static final Logger LOG = Logger.getLogger("main");

    enum BuildSystem { MAVEN, GRADLE, BAZEL, UNKNOWN }

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
    /** Cached build root (lazily initialized) */
    private Path buildRoot;
    /** Cached build system detection (lazily initialized) */
    private BuildSystem buildSystem;
    /** Cached module graph (lazily initialized) */
    private ModuleGraph cachedModuleGraph;
    /** Cached maven deps (lazily initialized) */
    private MavenTooling.MavenDependencies cachedMavenDeps;

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

    /**
     * @param workspaceRoot project root directory
     * @param externalDependencies additional classpath entries
     *     Uses default Maven (~/.m2) and Gradle paths.
     */
    InferConfig(Path workspaceRoot, Collection<String> externalDependencies) {
        this(workspaceRoot, externalDependencies, defaultMavenHome(), defaultGradleHome(), null);
    }

    private static Path defaultMavenHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".m2");
    }

    private static Path defaultGradleHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".gradle");
    }

    Path buildRoot() {
        if (buildRoot == null) buildRoot = findBuildRoot();
        return buildRoot;
    }

    BuildSystem buildSystem() {
        if (buildSystem == null) buildSystem = detectBuildSystem();
        return buildSystem;
    }

    private BuildSystem detectBuildSystem() {
        var root = buildRoot();
        if (Files.exists(root.resolve("settings.gradle")) || Files.exists(root.resolve("settings.gradle.kts")))
            return BuildSystem.GRADLE;
        if (Files.exists(root.resolve("pom.xml")))
            return BuildSystem.MAVEN;
        if (Files.exists(root.resolve("WORKSPACE")))
            return BuildSystem.BAZEL;
        return BuildSystem.UNKNOWN;
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
            return resolveExternalDependencies(false);
        }

        return switch (buildSystem()) {
            case MAVEN -> mavenClasspath();
            case GRADLE -> gradleClasspath();
            case BAZEL -> bazelClasspath(bazelWorkspaceRoot());
            case UNKNOWN -> Collections.emptySet();
        };
    }

    private Set<Path> mavenClasspath() {
        var pomXml = buildRoot().resolve("pom.xml");
        var modulePath = mavenModulePath(buildRoot(), workspaceRoot);
        cachedMavenDeps = MavenTooling.resolveDependencies(pomXml, mavenHome, this.envVars, modulePath);
        var classPath = new HashSet<>(cachedMavenDeps.classpath());
        var moduleOut = MavenTooling.outputDirectory(workspaceRoot);
        if (Files.isDirectory(moduleOut)) {
            LOG.info("[classpath] Adding module build output: " + moduleOut);
            classPath.add(moduleOut);
        }
        var graph = MavenTooling.resolveModuleGraph(buildRoot());
        if (graph != ModuleGraph.EMPTY) {
            for (var info : graph.modules().values()) {
                var siblingOut = MavenTooling.outputDirectory(info.projectDir());
                if (Files.isDirectory(siblingOut) && !siblingOut.equals(moduleOut)) {
                    LOG.info("[classpath] Adding sibling build output: " + siblingOut);
                    classPath.add(siblingOut);
                }
            }
        }
        return classPath;
    }

    private Set<Path> gradleClasspath() {
        LOG.info("[gradle] Resolving classpath via Gradle Tooling API — this may take a minute for large projects.");
        var graph = moduleGraph();
        if (graph != ModuleGraph.EMPTY) {
            var activeModule = graph.moduleForFile(workspaceRoot);
            if (activeModule.isPresent()) {
                var classPath = new HashSet<>(activeModule.get().externalClasspath());
                for (var dir : graph.transitiveClassOutputDirs(activeModule.get().projectPath())) {
                    if (Files.isDirectory(dir)) classPath.add(dir);
                }
                return classPath;
            }
            var rootModule = graph.modules().get(":");
            if (rootModule != null) {
                var classPath = new HashSet<>(rootModule.externalClasspath());
                for (var dir : graph.transitiveClassOutputDirs(rootModule.projectPath())) {
                    if (Files.isDirectory(dir)) classPath.add(dir);
                }
                return classPath;
            }
        }
        return Collections.emptySet();
    }

    private Set<Path> resolveExternalDependencies(boolean source) {
        var result = new HashSet<Path>();
        for (var id : externalDependencies) {
            var a = Artifact.parse(id);
            var found = findAnyJar(a, source);
            if (found == NOT_FOUND) {
                LOG.warning(String.format("Couldn't find %sjar for %s in %s or %s",
                        source ? "doc " : "", a, mavenHome, gradleHome));
                continue;
            }
            result.add(found);
        }
        return result;
    }

    /** Resolve the module graph for this workspace (cached). Returns EMPTY for single-module projects. */
    /** Resolve the module graph for this workspace (cached). Returns EMPTY for single-module projects. */
    ModuleGraph moduleGraph() {
        if (cachedModuleGraph != null) return cachedModuleGraph;
        cachedModuleGraph = switch (buildSystem()) {
            case GRADLE -> GradleTooling.resolveModuleGraph(buildRoot(), cacheHome(this.envVars));
            case MAVEN -> MavenTooling.resolveModuleGraph(buildRoot());
            default -> ModuleGraph.EMPTY;
        };
        return cachedModuleGraph;
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

    String mavenModulePath(Path buildRoot, Path workspaceRoot) {
        var normalizedBuild = buildRoot.toAbsolutePath().normalize();
        var normalizedWork = workspaceRoot.toAbsolutePath().normalize();
        if (!normalizedWork.equals(normalizedBuild)) {
            return normalizedBuild.relativize(normalizedWork).toString();
        }
        // workspaceRoot IS the build root — resolve everything at root (no -pl needed)
        return null;
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
        if (!externalDependencies.isEmpty()) {
            return resolveExternalDependencies(true);
        }
        return switch (buildSystem()) {
            case MAVEN -> {
                // Reuse cached result from classPath() call — no second mvn invocation
                if (cachedMavenDeps != null) {
                    yield cachedMavenDeps.sources();
                }
                var pomXml = buildRoot().resolve("pom.xml");
                var modulePath = mavenModulePath(buildRoot(), workspaceRoot);
                yield MavenTooling.resolveDependencies(pomXml, mavenHome, this.envVars, modulePath).sources();
            }
            case BAZEL -> bazelSourcepath(bazelWorkspaceRoot());
            default -> Collections.emptySet();
        };
    }

    CompilerArgs compilerArgs() {
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(pomXml)) {
            return CompilerArgs.none();
        }
        var result = MavenTooling.inferCompilerArgs(pomXml, mavenHome, envVars);
        return new CompilerArgs(result.args(), result.source(), result.mixedModules());
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

    private static Path cacheHome(Map<String, String> envVars) {
        var xdg = envVars.get("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg);
        }
        return Paths.get(System.getProperty("user.home")).resolve(".cache");
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
            if (outputIds.contains(artifact.getId())) {
                // artifact is the output of another java action
                continue;
            }
            LOG.info("...found bazel dependency " + artifact.getExecPath());
            artifactPaths.add(artifact.getExecPath());
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
