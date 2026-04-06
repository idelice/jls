package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InferConfigTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path workspaceRoot = Paths.get("src/test/examples/maven-project");
    private Path mavenHome = Paths.get("src/test/examples/home-dir/.m2");
    private Path gradleHome = Paths.get("src/test/examples/home-dir/.gradle");
    private Set<String> externalDependencies = Set.of("com.external:external-library:1.2");
    private InferConfig both = new InferConfig(workspaceRoot, externalDependencies, mavenHome, gradleHome);
    private InferConfig gradle = new InferConfig(workspaceRoot, externalDependencies, Paths.get("nowhere"), gradleHome);
    private InferConfig thisProject = new InferConfig(Paths.get("."), (Map<String, String>) null);

    @Test
    public void mavenClassPath() {
        assertThat(
                both.classPath(),
                contains(mavenHome.resolve("repository/com/external/external-library/1.2/external-library-1.2.jar")));
        // v1.1 should be ignored
    }

    @Test
    public void gradleClasspath() {
        assertThat(
                gradle.classPath(),
                contains(
                        gradleHome.resolve(
                                "caches/modules-2/files-2.1/com.external/external-library/1.2/xxx/external-library-1.2.jar")));
        // v1.1 should be ignored
    }

    @Test
    public void mavenDocPath() {
        assertThat(
                both.buildDocPath(),
                contains(
                        mavenHome.resolve(
                                "repository/com/external/external-library/1.2/external-library-1.2-sources.jar")));
        // v1.1 should be ignored
    }

    @Test
    public void gradleDocPath() {
        assertThat(
                gradle.buildDocPath(),
                contains(
                        gradleHome.resolve(
                                "caches/modules-2/files-2.1/com.external/external-library/1.2/yyy/external-library-1.2-sources.jar")));
        // v1.1 should be ignored
    }

    @Test
    public void dependencyList() {
        assertThat(
                InferConfig.mvnDependencies(Paths.get("pom.xml"), "dependency:list", Paths.get(System.getProperty("user.home"), ".m2"), System.getenv()),
                not(empty()));
    }

    @Test
    public void classpathFromEnvironmentVariable() {
        String dummyPath1 = "target/dummy1.jar";
        String dummyPath2 = "target/dummy2.jar";
        String dummyClassPath = dummyPath1 + File.pathSeparator + dummyPath2;
        Map<String, String> envVars = new HashMap<>();
        envVars.put("CLASSPATH", dummyClassPath);
        envVars.put("PATH", System.getenv("PATH"));

        InferConfig config = new InferConfig(Paths.get("."), envVars);
        Set<Path> classPath = config.classPath();
        assertThat(classPath, containsInAnyOrder(Paths.get(dummyPath1), Paths.get(dummyPath2)));
    }

    @Test
    public void thisProjectClassPath() {
        InferConfig currentTestProject = new InferConfig(Paths.get("."), (Map<String, String>) null);
        assertThat(
                currentTestProject.classPath(),
                hasItem(hasToString(endsWith(".m2/repository/junit/junit/4.13.1/junit-4.13.1.jar"))));
    }

    @Test
    public void thisProjectDocPath() {
        InferConfig currentTestProject = new InferConfig(Paths.get("."), (Map<String, String>) null);
        assertThat(
                currentTestProject.buildDocPath(),
                hasItem(hasToString(endsWith(".m2/repository/junit/junit/4.13.1/junit-4.13.1-sources.jar"))));
    }

    @Test
    public void parseDependencyLine() {
        String[][] testCases = {
            {
                "[INFO]    org.openjdk.jmh:jmh-generator-annprocess:jar:1.21:provided:/Users/georgefraser/.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.21/jmh-generator-annprocess-1.21.jar",
                "/Users/georgefraser/.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.21/jmh-generator-annprocess-1.21.jar",
            },
            {
                "[INFO]    org.openjdk.jmh:jmh-generator-annprocess:jar:1.21:provided:/Users/georgefraser/.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.21/jmh-generator-annprocess-1.21.jar -- module jmh.generator.annprocess (auto)",
                "/Users/georgefraser/.m2/repository/org/openjdk/jmh/jmh-generator-annprocess/1.21/jmh-generator-annprocess-1.21.jar",
            },
        };
        for (var pair : testCases) {
            assert pair.length == 2;
            var line = pair[0];
            var expect = pair[1];
            var path = InferConfig.readDependency(line);
            assertThat(path, equalTo(Paths.get(expect)));
        }
    }

    @Test
    public void mavenCacheUsesWorkspaceSpecificFile() throws Exception {
        var cacheHome = temp.newFolder("cache-home").toPath();
        var workspace = temp.newFolder("demo").toPath();
        var cacheFile = InferConfig.workspaceCacheFile(workspace, cacheHome);

        assertThat(cacheFile.toString(), containsString("cache-home"));
        assertThat(cacheFile.toString(), containsString("jls"));
        assertThat(cacheFile.getFileName().toString(), equalTo("maven-inference.json"));
        assertThat(cacheFile.getParent().getFileName().toString(), startsWith("demo-"));
    }

    @Test
    public void mavenCacheInvalidatesWhenWorkspacePomChanges() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        var module = Files.createDirectories(workspace.resolve("module"));
        Files.writeString(workspace.resolve("pom.xml"), "<project><version>1</version></project>");
        Files.writeString(module.resolve("pom.xml"), "<project><version>1</version></project>");
        var m2 = temp.newFolder("m2").toPath();
        Files.writeString(m2.resolve("settings.xml"), "<settings/>");
        var cacheHome = temp.newFolder("cache-home").toPath();

        InferConfig.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"),
                "dependency:list",
                m2,
                cacheHome,
                Set.of(workspace.resolve("lib/example.jar")));

        assertThat(
                InferConfig.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome),
                hasItem(workspace.resolve("lib/example.jar").toAbsolutePath().normalize()));

        Thread.sleep(5);
        Files.writeString(module.resolve("pom.xml"), "<project><version>2</version></project>");

        assertThat(
                InferConfig.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome),
                empty());
    }

    @Test
    public void mavenCacheInvalidatesWhenSettingsXmlChanges() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project><version>1</version></project>");
        var m2 = temp.newFolder("m2").toPath();
        var settings = m2.resolve("settings.xml");
        Files.writeString(settings, "<settings><mirrors/></settings>");
        var cacheHome = temp.newFolder("cache-home").toPath();

        InferConfig.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"),
                "dependency:sources",
                m2,
                cacheHome,
                Set.of(workspace.resolve("lib/example-sources.jar")));

        assertThat(
                InferConfig.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome),
                hasItem(workspace.resolve("lib/example-sources.jar").toAbsolutePath().normalize()));

        Thread.sleep(5);
        Files.writeString(settings, "<settings><proxies/></settings>");

        assertThat(
                InferConfig.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome),
                empty());
    }
}
