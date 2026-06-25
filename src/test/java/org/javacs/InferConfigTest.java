package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.javacs.MavenTooling;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InferConfigTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path workspaceRoot = Paths.get("src/test/examples/maven-project");
    private Path mavenHome = Paths.get("src/test/examples/home-dir/.m2");
    private Path gradleHome = Paths.get("src/test/examples/home-dir/.gradle");
    private Set<String> externalDependencies = Set.of("com.external:external-library:1.2");
    private InferConfig both = new InferConfig(workspaceRoot, externalDependencies, mavenHome, gradleHome, null);
    private InferConfig gradle = new InferConfig(workspaceRoot, externalDependencies, Paths.get("nowhere"), gradleHome, null);
    private InferConfig thisProject = new InferConfig(Paths.get("."), Collections.emptySet());

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
                MavenTooling.mvnDependencies(Paths.get("pom.xml"), "dependency:list", Paths.get(System.getProperty("user.home"), ".m2"), System.getenv()),
                not(empty()));
    }

    @Test
    public void inferCompilerArgsUsesMavenCompilerRelease() throws Exception {
        var workspace = temp.newFolder("release-workspace").toPath();
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>release-workspace</artifactId>
                  <version>1</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """);
        var cacheHome = temp.newFolder("cache-home").toPath();
        var m2 = temp.newFolder("m2").toPath();

        var inferred =
                MavenTooling.inferCompilerArgs(
                        workspace.resolve("pom.xml"), m2, envWithCacheHome(cacheHome));

        assertThat(inferred.source(), equalTo("maven_release"));
        assertThat(inferred.args(), contains("--release", "21"));
        assertThat(inferred.mixedModules(), equalTo(false));
    }

    @Test
    public void inferCompilerArgsUsesSpringBootJavaVersionViaEffectivePom() throws Exception {
        var workspace = temp.newFolder("spring-boot-workspace").toPath();
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>4.0.1</version>
                    <relativePath/>
                  </parent>
                  <groupId>example</groupId>
                  <artifactId>spring-boot-workspace</artifactId>
                  <version>1</version>
                  <properties>
                    <java.version>21</java.version>
                  </properties>
                </project>
                """);
        var cacheHome = temp.newFolder("cache-home").toPath();
        var m2 = temp.newFolder("m2").toPath();

        var inferred =
                MavenTooling.inferCompilerArgs(
                        workspace.resolve("pom.xml"), m2, envWithCacheHome(cacheHome));

        assertThat(inferred.source(), equalTo("maven_release"));
        assertThat(inferred.args(), contains("--release", "21"));
    }

    @Test
    public void inferCompilerArgsUsesSourceAndTargetWhenReleaseMissing() throws Exception {
        var workspace = temp.newFolder("source-target-workspace").toPath();
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>source-target-workspace</artifactId>
                  <version>1</version>
                  <properties>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                  </properties>
                </project>
                """);
        var cacheHome = temp.newFolder("cache-home").toPath();
        var m2 = temp.newFolder("m2").toPath();

        var inferred =
                MavenTooling.inferCompilerArgs(
                        workspace.resolve("pom.xml"), m2, envWithCacheHome(cacheHome));

        assertThat(inferred.source(), equalTo("maven_source_target"));
        assertThat(inferred.args(), contains("-source", "17", "-target", "17"));
    }

    @Test
    public void inferCompilerArgsFallsBackForMixedModuleLevels() throws Exception {
        var workspace = temp.newFolder("mixed-workspace").toPath();
        Files.createDirectories(workspace.resolve("mod17"));
        Files.createDirectories(workspace.resolve("mod21"));
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>mixed-workspace</artifactId>
                  <version>1</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>mod17</module>
                    <module>mod21</module>
                  </modules>
                </project>
                """);
        Files.writeString(
                workspace.resolve("mod17/pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId>
                    <artifactId>mixed-workspace</artifactId>
                    <version>1</version>
                  </parent>
                  <artifactId>mod17</artifactId>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                </project>
                """);
        Files.writeString(
                workspace.resolve("mod21/pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId>
                    <artifactId>mixed-workspace</artifactId>
                    <version>1</version>
                  </parent>
                  <artifactId>mod21</artifactId>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """);
        var cacheHome = temp.newFolder("cache-home").toPath();
        var m2 = temp.newFolder("m2").toPath();

        var inferred =
                MavenTooling.inferCompilerArgs(
                        workspace.resolve("pom.xml"), m2, envWithCacheHome(cacheHome));

        assertThat(inferred.source(), equalTo("fallback_mixed_modules"));
        assertThat(inferred.args(), empty());
        assertThat(inferred.mixedModules(), equalTo(true));
    }

    @Test
    public void inferCompilerArgsIgnoresPlaceholderChildModuleLevelsWhenParentIsUniform() throws Exception {
        var workspace = temp.newFolder("placeholder-modules-workspace").toPath();
        Files.createDirectories(workspace.resolve("modA"));
        Files.createDirectories(workspace.resolve("modB"));
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>placeholder-modules-workspace</artifactId>
                  <version>1</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                  <modules>
                    <module>modA</module>
                    <module>modB</module>
                  </modules>
                </project>
                """);
        Files.writeString(
                workspace.resolve("modA/pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId>
                    <artifactId>placeholder-modules-workspace</artifactId>
                    <version>1</version>
                  </parent>
                  <artifactId>modA</artifactId>
                  <properties>
                    <maven.compiler.release>${maven.compiler.release}</maven.compiler.release>
                  </properties>
                </project>
                """);
        Files.writeString(
                workspace.resolve("modB/pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId>
                    <artifactId>placeholder-modules-workspace</artifactId>
                    <version>1</version>
                  </parent>
                  <artifactId>modB</artifactId>
                  <properties>
                    <maven.compiler.release>${maven.compiler.release}</maven.compiler.release>
                  </properties>
                </project>
                """);
        var cacheHome = temp.newFolder("cache-home").toPath();
        var m2 = temp.newFolder("m2").toPath();

        var inferred =
                MavenTooling.inferCompilerArgs(
                        workspace.resolve("pom.xml"), m2, envWithCacheHome(cacheHome));

        assertThat(inferred.source(), equalTo("maven_release"));
        assertThat(inferred.args(), contains("--release", "21"));
        assertThat(inferred.mixedModules(), equalTo(false));
    }

    @Test
    public void inferCompilerArgsFailsClosedOnMalformedPom() throws Exception {
        var workspace = temp.newFolder("malformed-pom-workspace").toPath();
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>broken
                """);
        var cacheHome = temp.newFolder("cache-home").toPath();
        var m2 = temp.newFolder("m2").toPath();

        var inferred =
                MavenTooling.inferCompilerArgs(
                        workspace.resolve("pom.xml"), m2, envWithCacheHome(cacheHome));

        assertThat(inferred.source(), equalTo("none"));
        assertThat(inferred.args(), empty());
        assertThat(inferred.mixedModules(), equalTo(false));
    }

    @Test
    public void classpathFromEnvironmentVariable() {
        String dummyPath1 = "target/dummy1.jar";
        String dummyPath2 = "target/dummy2.jar";
        String dummyClassPath = dummyPath1 + File.pathSeparator + dummyPath2;
        Map<String, String> envVars = new HashMap<>();
        envVars.put("CLASSPATH", dummyClassPath);
        envVars.put("PATH", System.getenv("PATH"));

        InferConfig config = new InferConfig(Paths.get("."), Collections.emptySet(), null, null, envVars);
        Set<Path> classPath = config.classPath();
        assertThat(classPath, containsInAnyOrder(Paths.get(dummyPath1), Paths.get(dummyPath2)));
    }

    @Test
    public void thisProjectClassPath() {
        InferConfig currentTestProject = new InferConfig(Paths.get("."), Collections.emptySet());
        assertThat(
                currentTestProject.classPath(),
                hasItem(hasToString(endsWith(".m2/repository/junit/junit/4.13.1/junit-4.13.1.jar"))));
    }

    @Test
    public void thisProjectDocPath() {
        InferConfig currentTestProject = new InferConfig(Paths.get("."), Collections.emptySet());
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
            var path = MavenTooling.readDependency(line);
            assertThat(path, equalTo(Paths.get(expect)));
        }
    }

    @Test
    public void mavenCacheUsesWorkspaceSpecificFile() throws Exception {
        var cacheHome = temp.newFolder("cache-home").toPath();
        var workspace = temp.newFolder("demo").toPath();
        var cacheFile = MavenTooling.workspaceCacheFile(workspace, cacheHome);

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

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"),
                "dependency:list",
                m2,
                cacheHome,
                Set.of(workspace.resolve("lib/example.jar")), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                hasItem(workspace.resolve("lib/example.jar").toAbsolutePath().normalize()));

        Thread.sleep(5);
        Files.writeString(module.resolve("pom.xml"), "<project><version>2</version></project>");

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
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

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"),
                "dependency:sources",
                m2,
                cacheHome,
                Set.of(workspace.resolve("lib/example-sources.jar")), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, null),
                hasItem(workspace.resolve("lib/example-sources.jar").toAbsolutePath().normalize()));

        Thread.sleep(5);
        Files.writeString(settings, "<settings><proxies/></settings>");

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, null),
                empty());
    }

    @Test
    public void mavenCacheReturnsEmptyWhenCacheFileMissing() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                empty());
    }

    @Test
    public void mavenCacheHandlesMalformedCacheFile() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();
        var cacheFile = MavenTooling.workspaceCacheFile(workspace, cacheHome);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{not-json");

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                empty());
    }

    @Test
    public void mavenCacheKeepsSeparateEntriesPerGoal() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();
        var listJar = workspace.resolve("lib/list.jar");
        var sourcesJar = workspace.resolve("lib/list-sources.jar");

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, Set.of(listJar), null);
        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, Set.of(sourcesJar), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                hasItem(listJar.toAbsolutePath().normalize()));
        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, null),
                hasItem(sourcesJar.toAbsolutePath().normalize()));
    }

    @Test
    public void mavenCacheInvalidatesWhenSettingsFileAppears() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();
        var jar = workspace.resolve("lib/example.jar");

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, Set.of(jar), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                hasItem(jar.toAbsolutePath().normalize()));

        Thread.sleep(5);
        Files.writeString(m2.resolve("settings.xml"), "<settings/>");

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                empty());
    }

    @Test
    public void mavenCacheIgnoresDifferentWorkspaceWithSameGoal() throws Exception {
        var cacheHome = temp.newFolder("cache-home").toPath();
        var m2 = temp.newFolder("m2").toPath();
        var workspaceOne = temp.newFolder("workspace-one").toPath();
        var workspaceTwo = temp.newFolder("workspace-two").toPath();
        Files.writeString(workspaceOne.resolve("pom.xml"), "<project><name>one</name></project>");
        Files.writeString(workspaceTwo.resolve("pom.xml"), "<project><name>two</name></project>");
        var jar = workspaceOne.resolve("lib/example.jar");

        MavenTooling.storeCachedMavenDependencies(
                workspaceOne.resolve("pom.xml"), "dependency:list", m2, cacheHome, Set.of(jar), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspaceTwo.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                empty());
    }

    @Test
    public void mavenCacheReturnsEmptyWhenCacheEntriesAreMissing() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var cacheHome = temp.newFolder("cache-home").toPath();
        var cacheFile = MavenTooling.workspaceCacheFile(workspace, cacheHome);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{\"workspaceRoot\":\"" + workspace.toAbsolutePath().normalize() + "\"}");

        assertThat(
                MavenTooling.loadCachedMavenDependencies(
                        workspace.resolve("pom.xml"), "dependency:list", temp.newFolder("m2").toPath(), cacheHome, null),
                empty());
    }

    @Test
    public void mavenCacheReturnsEmptyWhenGoalEntryIsMissing() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, Set.of(workspace.resolve("lib/example.jar")), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, null),
                empty());
    }

    @Test
    public void mavenCacheStoreMergesExistingEntries() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, Set.of(workspace.resolve("lib/list.jar")), null);
        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, Set.of(workspace.resolve("lib/src.jar")), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                hasItem(workspace.resolve("lib/list.jar").toAbsolutePath().normalize()));
        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, null),
                hasItem(workspace.resolve("lib/src.jar").toAbsolutePath().normalize()));
    }

    @Test
    public void mavenCacheStoreIgnoresUnwritableCacheLocation() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var blockingFile = temp.newFile("cache-root-file").toPath();

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), "dependency:list", m2, blockingFile, Set.of(workspace.resolve("lib/list.jar")), null);

        var cacheFile = MavenTooling.workspaceCacheFile(workspace, blockingFile);
        assertThat(Files.exists(cacheFile), equalTo(false));
    }

    @Test
    public void workspaceCacheFileUsesWorkspaceFallbackNameForRootPath() {
        var cacheFile = MavenTooling.workspaceCacheFile(Paths.get("/"), Paths.get("/tmp/cache-home"));
        assertThat(cacheFile.getParent().getParent().getFileName().toString(), startsWith("workspace-"));
    }

    @Test
    public void invalidDependencyLineReturnsNotFound() {
        assertThat(MavenTooling.readDependency("not a dependency line"), equalTo(Paths.get("")));
    }

    @Test
    public void fingerprintExistingFileThrowsForMissingPath() throws Exception {
        var method = MavenTooling.class.getDeclaredMethod("fingerprintExistingFile", Path.class);
        method.setAccessible(true);

        try {
            method.invoke(null, temp.getRoot().toPath().resolve("missing-file"));
        } catch (InvocationTargetException e) {
            assertThat(e.getCause(), instanceOf(RuntimeException.class));
            return;
        }
        throw new AssertionError("Expected RuntimeException for missing file fingerprint");
    }

    @Test
    public void cacheHomeUsesXdgCacheHomeWhenProvided() throws Exception {
        var method = InferConfig.class.getDeclaredMethod("cacheHome", Map.class);
        method.setAccessible(true);
        Map<String, String> env = new HashMap<>();
        env.put("XDG_CACHE_HOME", "/tmp/jls-cache");

        var result = (Path) method.invoke(null, env);

        assertThat(result, equalTo(Paths.get("/tmp/jls-cache")));
    }

    private static Map<String, String> envWithCacheHome(Path cacheHome) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PATH", System.getenv("PATH"));
        env.put("XDG_CACHE_HOME", cacheHome.toString());
        return env;
    }
}
