package org.javacs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class MavenSupport {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static final Path NOT_FOUND = Paths.get("");

    private static final Pattern DEPENDENCY =
            Pattern.compile("^\\[INFO\\]\\s+(.*:.*:.*:.*:.*):(/.*?)( -- module .*)?$");

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

    static final record FileFingerprint(String path, long lastModifiedMillis, long size) {
        FileFingerprint() {
            this(null, 0, 0);
        }
    }

    static final record MavenInferenceCacheEntry(List<FileFingerprint> pomInputs, FileFingerprint settings, List<String> dependencies) {}

    static final record MavenInferenceCacheFile(Map<String, MavenInferenceCacheEntry> entries, MavenCompilerLevelCacheEntry compilerLevel) {}

    static final record MavenCompilerLevelCacheEntry(List<FileFingerprint> pomInputs, FileFingerprint settings, List<String> args, String source, boolean mixedModules) {}

    private record MavenCacheInputs(List<FileFingerprint> pomInputs, FileFingerprint settings) {
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
        if (cache == null || cache.compilerLevel() == null) {
            return null;
        }
        var inputs = cacheInputs(workspaceRoot, mavenHome);
        if (!Objects.equals(cache.compilerLevel().pomInputs(), inputs.pomInputs())
                || !Objects.equals(cache.compilerLevel().settings(), inputs.settings())) {
            return null;
        }
        return new CompilerArgs(
                cache.compilerLevel().args() == null ? List.of() : cache.compilerLevel().args(),
                cache.compilerLevel().source() == null ? "none" : cache.compilerLevel().source(),
                cache.compilerLevel().mixedModules());
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
                                cache == null ? null : cache.compilerLevel()),
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
                    .map(MavenSupport::fingerprintExistingFile)
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
                    .map(MavenSupport::normalizePath)
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
}
