package org.javacs;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.devtools.build.lib.analysis.AnalysisProtos;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class InferConfig {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Path CACHE_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "jls-cache");
    private static final Gson GSON = new Gson();

    static {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Root of the workspace that is currently open in VSCode */
    private final Path workspaceRoot;
    /** External dependencies specified manually by the user */
    private final Collection<String> externalDependencies;
    /** Location of the maven repository, usually ~/.m2 */
    private final Path mavenHome;
    /** Location of the gradle cache, usually ~/.gradle */
    private final Path gradleHome;
    /** Optional Maven settings.xml to use when running Maven */
    private final Optional<Path> mavenSettings;

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies, Path mavenHome, Path gradleHome) {
        this(workspaceRoot, externalDependencies, mavenHome, gradleHome, null);
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies, Path mavenHome, Path gradleHome, Path mavenSettings) {
        this.workspaceRoot = workspaceRoot;
        this.externalDependencies = externalDependencies;
        this.mavenHome = mavenHome;
        this.gradleHome = gradleHome;
        this.mavenSettings = Optional.ofNullable(mavenSettings);
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies) {
        this(workspaceRoot, externalDependencies, defaultMavenHome(), defaultGradleHome());
    }

    InferConfig(Path workspaceRoot) {
        this(workspaceRoot, Collections.emptySet(), defaultMavenHome(), defaultGradleHome());
    }

    InferConfig(Path workspaceRoot, Collection<String> externalDependencies, Path mavenSettings) {
        this(workspaceRoot, externalDependencies, defaultMavenHome(), defaultGradleHome(), mavenSettings);
    }

    private static Path defaultMavenHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".m2");
    }

    private static Path defaultGradleHome() {
        return Paths.get(System.getProperty("user.home")).resolve(".gradle");
    }

    /** Find .jar files for external dependencies, for examples maven dependencies in ~/.m2 or jars in bazel-genfiles */
    Set<Path> classPath() {
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
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            return mvnDependencies(pomXml, "dependency:list", mavenSettings);
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            return bazelClasspath(bazelWorkspaceRoot);
        }

        return Collections.emptySet();
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
            LOG.fine("Doc path (external deps) size=" + result.size());
            return result;
        }

        // Maven
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            var deps = mvnDependencies(pomXml, "dependency:resolve", mavenSettings, "-Dclassifier=sources");
            LOG.fine("Doc path (maven sources) size=" + deps.size());
            return deps;
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            var deps = bazelSourcepath(bazelWorkspaceRoot);
            LOG.fine("Doc path (bazel) size=" + deps.size());
            return deps;
        }

        return Collections.emptySet();
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

    static Set<Path> mvnDependencies(Path pomXml, String goal, Optional<Path> mavenSettings, String... extraArgs) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");
        
        // Check cache
        String hash = "";
        try {
            var content = Files.readString(pomXml);
            var sb = new StringBuilder(content);
            sb.append(goal);
            mavenSettings.ifPresent(p -> sb.append(p.toString()));
            for (var arg : extraArgs) sb.append(arg);
            hash = md5(sb.toString());
            var cacheFile = CACHE_DIR.resolve("mvn-" + hash + ".json");
            
            if (Files.exists(cacheFile)) {
                try (var reader = new InputStreamReader(Files.newInputStream(cacheFile), StandardCharsets.UTF_8)) {
                    Set<String> paths = GSON.fromJson(reader, new TypeToken<Set<String>>() {}.getType());
                    var cached = paths.stream().map(Paths::get).collect(Collectors.toSet());
                    var missing = cached.stream().filter(p -> !Files.exists(p)).collect(Collectors.toSet());
                    boolean wantsSources = Arrays.asList(extraArgs).contains("-Dclassifier=sources");
                    if (!missing.isEmpty()) {
                        LOG.warning("Cached Maven paths missing on disk, re-running Maven: " + missing.size());
                    } else if (cached.isEmpty() && wantsSources) {
                        LOG.warning("Cached Maven sources are empty, re-running Maven to pick up newly downloaded sources");
                    } else {
                        LOG.fine(
                                "Using cached Maven dependencies: size="
                                        + cached.size()
                                        + " goal="
                                        + goal
                                        + (wantsSources ? " classifier=sources" : ""));
                        return cached;
                    }
                } catch (IOException e) {
                    LOG.warning("Failed to read Maven cache: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to calculate hash for pom.xml: " + e.getMessage());
        }

        try {
            // TODO consider using mvn valide dependency:copy-dependencies -DoutputDirectory=??? instead
            // Run maven as a subprocess
            var commandList = new ArrayList<String>();
            commandList.add(getMvnCommand());
            if (mavenSettings.isPresent()) {
                commandList.add("-s");
                commandList.add(mavenSettings.get().toAbsolutePath().toString());
            }
            commandList.add("--batch-mode"); // Turns off ANSI control sequences
            commandList.add("validate");
            commandList.add(goal);
            commandList.add("-DincludeScope=test");
            commandList.add("-DincludeOptional=true");
            commandList.add("-DoutputAbsoluteArtifactFilename=true");
            Collections.addAll(commandList, extraArgs);

            String[] command = commandList.toArray(String[]::new);
            var output = Files.createTempFile("jls-maven-output", ".txt");
            LOG.info("Running " + String.join(" ", command) + " ...");
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            var process =
                    new ProcessBuilder()
                            .command(command)
                            .directory(workingDirectory)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .redirectOutput(output.toFile())
                            .start();
            // Wait for process to exit
            var result = process.waitFor();
            if (result != 0) {
                LOG.severe("`" + String.join(" ", command) + "` returned " + result);
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
            boolean wantsSources = Arrays.asList(extraArgs).contains("-Dclassifier=sources");
            LOG.fine(
                    "Maven dependencies resolved: size="
                            + dependencies.size()
                            + " goal="
                            + goal
                            + (wantsSources ? " classifier=sources" : ""));
            
            // Save to cache
            if (!hash.isEmpty()) {
                var cacheFile = CACHE_DIR.resolve("mvn-" + hash + ".json");
                try (var writer = new OutputStreamWriter(Files.newOutputStream(cacheFile), StandardCharsets.UTF_8)) {
                    var paths = dependencies.stream().map(Path::toString).collect(Collectors.toSet());
                    GSON.toJson(paths, writer);
                } catch (IOException e) {
                    LOG.warning("Failed to write Maven cache: " + e.getMessage());
                }
            }
            
            return dependencies;
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    private static String md5(String input) {
        try {
            var md = MessageDigest.getInstance("MD5");
            var bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (var b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
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
        // LOG.info(String.format("...%s => %s", artifact, path));
        return Paths.get(path);
    }

    static String getMvnCommand() {
        var mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd");
            if (mvnCommand == null) {
                mvnCommand = findExecutableOnPath("mvn.bat");
            }
        }
        return mvnCommand;
    }

    private static String findExecutableOnPath(String name) {
        for (var dirname : System.getenv("PATH").split(File.pathSeparator)) {
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
