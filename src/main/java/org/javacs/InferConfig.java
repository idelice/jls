package org.javacs;

import com.google.devtools.build.lib.analysis.AnalysisProtos;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors; // Added for Collectors.toSet()
import java.util.stream.Stream;

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
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            return mvnDependencies(pomXml, "dependency:list", mavenHome, this.envVars);
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
            return result;
        }

        // Maven
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            return mvnDependencies(pomXml, "dependency:sources", mavenHome, this.envVars);
        }

        // Bazel
        var bazelWorkspaceRoot = bazelWorkspaceRoot();
        if (Files.exists(bazelWorkspaceRoot.resolve("WORKSPACE"))) {
            return bazelSourcepath(bazelWorkspaceRoot);
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

    static Set<Path> mvnDependencies(Path pomXml, String goal, Path mavenHome, Map<String, String> envVars) {
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

            // TODO consider using mvn valide dependency:copy-dependencies -DoutputDirectory=??? instead
            // Run maven as a subprocess
            String[] command = {
                getMvnCommand(envVars),
                "--batch-mode", // Turns off ANSI control sequences
                "validate",
                goal,
                "-DincludeScope=test",
                "-DoutputAbsoluteArtifactFilename=true",
            };
            var output = Files.createTempFile("jls-maven-output", ".txt");
            LOG.info("Running " + String.join(" ", command) + " ...");
            var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            var processStarted = Instant.now();
            var process =
                    new ProcessBuilder()
                            .command(command)
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
        String goal;
        String workspaceRoot;
        List<FileFingerprint> pomInputs;
        FileFingerprint settings;
        List<String> dependencies;

        MavenInferenceCacheEntry() {}

        MavenInferenceCacheEntry(
                String goal,
                String workspaceRoot,
                List<FileFingerprint> pomInputs,
                FileFingerprint settings,
                List<String> dependencies) {
            this.goal = goal;
            this.workspaceRoot = workspaceRoot;
            this.pomInputs = pomInputs;
            this.settings = settings;
            this.dependencies = dependencies;
        }

        String workspaceRoot() {
            return workspaceRoot;
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
        String workspaceRoot;
        Map<String, MavenInferenceCacheEntry> entries;

        MavenInferenceCacheFile() {}

        MavenInferenceCacheFile(String workspaceRoot, Map<String, MavenInferenceCacheEntry> entries) {
            this.workspaceRoot = workspaceRoot;
            this.entries = entries;
        }

        Map<String, MavenInferenceCacheEntry> entries() {
            return entries;
        }
    }

    static Set<Path> loadCachedMavenDependencies(Path pomXml, String goal, Path mavenHome, Path cacheHome) {
        var workspaceRoot = normalizePath(pomXml).getParent();
        var cacheFile = workspaceCacheFile(workspaceRoot, cacheHome);
        if (!Files.exists(cacheFile)) {
            return Set.of();
        }
        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            var cache = GSON.fromJson(reader, MavenInferenceCacheFile.class);
            if (cache == null || cache.entries() == null) {
                return Set.of();
            }
            var entry = cache.entries().get(goal);
            if (entry == null) {
                return Set.of();
            }
            if (!Objects.equals(entry.workspaceRoot(), workspaceRoot.toString())) {
                return Set.of();
            }
            if (!Objects.equals(entry.pomInputs(), workspacePomFingerprints(workspaceRoot))) {
                return Set.of();
            }
            if (!Objects.equals(entry.settings(), fingerprintIfExists(mavenHome.resolve("settings.xml")))) {
                return Set.of();
            }
            var result = new LinkedHashSet<Path>();
            for (var dependency : entry.dependencies()) {
                result.add(Paths.get(dependency));
            }
            return Set.copyOf(result);
        } catch (IOException e) {
            LOG.fine(String.format("Failed to read Maven cache %s: %s", cacheFile, e.getMessage()));
            return Set.of();
        }
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
        var dependencyStrings =
                dependencies.stream()
                        .map(path -> path.toAbsolutePath().normalize().toString())
                        .sorted()
                        .toList();
        entries.put(
                goal,
                new MavenInferenceCacheEntry(
                        goal,
                        workspaceRoot.toString(),
                        workspacePomFingerprints(workspaceRoot),
                        fingerprintIfExists(mavenHome.resolve("settings.xml")),
                        dependencyStrings));
        try {
            Files.createDirectories(cacheFile.getParent());
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(new MavenInferenceCacheFile(workspaceRoot.toString(), entries), writer);
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
            return GSON.fromJson(reader, MavenInferenceCacheFile.class);
        } catch (IOException e) {
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
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
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
