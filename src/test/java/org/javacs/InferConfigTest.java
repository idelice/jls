package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
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

    // TODO: update to use MavenTooling.resolveDependencies() after mvnDependencies was deleted
    // @Test
    // public void dependencyList() {
    //     assertThat(
    //             MavenTooling.mvnDependencies(Paths.get("pom.xml"), "dependency:list", Paths.get(System.getProperty("user.home"), ".m2"), System.getenv()),
    //             not(empty()));
    // }

    @Test
    public void inferCompilerArgsUsesMavenCompilerRelease() throws Exception {
        var workspace = temp.newFolder("release-workspace").toPath();
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>release-workspace</artifactId>
                  <version>1</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """);
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(workspace.resolve("pom.xml").toFile());
        var graph = MavenTooling.parseEffectivePomModuleGraph(workspace, document);
        var module = graph.modules().get(":");
        assertThat(module, notNullValue());
        assertThat(module.compilerArgs(), contains("--release", "21"));
    }

    @Test
    public void inferCompilerArgsUsesSourceAndTargetWhenReleaseMissing() throws Exception {
        var workspace = temp.newFolder("source-target-workspace").toPath();
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
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
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(workspace.resolve("pom.xml").toFile());
        var graph = MavenTooling.parseEffectivePomModuleGraph(workspace, document);
        var module = graph.modules().get(":");
        assertThat(module, notNullValue());
        assertThat(module.compilerArgs(), contains("-source", "17", "-target", "17"));
    }

    @Test
    public void compilerArgsFailsClosedWhenPropertyIsRecursive() throws Exception {
        var workspace = temp.newFolder("recursive-property-workspace").toPath();
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>recursive-property-workspace</artifactId>
                  <version>1</version>
                  <properties>
                    <maven.compiler.release>${maven.compiler.release}</maven.compiler.release>
                  </properties>
                </project>
                """);
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(workspace.resolve("pom.xml").toFile());
        var graph = MavenTooling.parseEffectivePomModuleGraph(workspace, document);
        var module = graph.modules().get(":");
        assertThat(module, notNullValue());
        assertThat(module.compilerArgs(), empty());
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
    public void mavenCacheUsesWorkspaceSpecificFile() throws Exception {
        var cacheHome = temp.newFolder("cache-home").toPath();
        var workspace = temp.newFolder("demo").toPath();
        var cacheFile = MavenTooling.workspaceCacheFile(workspace, cacheHome, null);

        assertThat(cacheFile.toString(), containsString("cache-home"));
        assertThat(cacheFile.toString(), containsString("jls"));
        assertThat(cacheFile.getFileName().toString(), equalTo("maven-inference.json"));
        assertThat(cacheFile.getParent().getFileName().toString(), equalTo("root"));
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
                workspace.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:list", Set.of(workspace.resolve("lib/example.jar"))), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                hasItem(workspace.resolve("lib/example.jar").toAbsolutePath().normalize()));

        Thread.sleep(5);
        Files.writeString(module.resolve("pom.xml"), "<project><version>2</version></project>");
        MavenTooling.invalidateCacheInputsSnapshot();

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                is(nullValue()));
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
                workspace.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:sources", Set.of(workspace.resolve("lib/example-sources.jar"))), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, null),
                hasItem(workspace.resolve("lib/example-sources.jar").toAbsolutePath().normalize()));

        Thread.sleep(5);
        Files.writeString(settings, "<settings><proxies/></settings>");
        MavenTooling.invalidateCacheInputsSnapshot();

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, null),
                is(nullValue()));
    }

    @Test
    public void mavenCacheReturnsEmptyWhenCacheFileMissing() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                is(nullValue()));
    }

    @Test
    public void mavenCacheHandlesMalformedCacheFile() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();
        var cacheFile = MavenTooling.workspaceCacheFile(workspace, cacheHome, null);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{not-json");

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                is(nullValue()));
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
                workspace.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:list", Set.of(listJar)), null);
        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:sources", Set.of(sourcesJar)), null);

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
                workspace.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:list", Set.of(jar)), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                hasItem(jar.toAbsolutePath().normalize()));

        Thread.sleep(5);
        Files.writeString(m2.resolve("settings.xml"), "<settings/>");
        MavenTooling.invalidateCacheInputsSnapshot();

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                is(nullValue()));
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
                workspaceOne.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:list", Set.of(jar)), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspaceTwo.resolve("pom.xml"), "dependency:list", m2, cacheHome, null),
                is(nullValue()));
    }

    @Test
    public void mavenCacheReturnsEmptyWhenCacheEntriesAreMissing() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var cacheHome = temp.newFolder("cache-home").toPath();
        var cacheFile = MavenTooling.workspaceCacheFile(workspace, cacheHome, null);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "{\"workspaceRoot\":\"" + workspace.toAbsolutePath().normalize() + "\"}");

        assertThat(
                MavenTooling.loadCachedMavenDependencies(
                        workspace.resolve("pom.xml"), "dependency:list", temp.newFolder("m2").toPath(), cacheHome, null),
                is(nullValue()));
    }

    @Test
    public void mavenCacheReturnsEmptyWhenGoalEntryIsMissing() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:list", Set.of(workspace.resolve("lib/example.jar"))), null);

        assertThat(
                MavenTooling.loadCachedMavenDependencies(workspace.resolve("pom.xml"), "dependency:sources", m2, cacheHome, null),
                is(nullValue()));
    }

    @Test
    public void mavenCacheStoreMergesExistingEntries() throws Exception {
        var workspace = temp.newFolder("workspace").toPath();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var m2 = temp.newFolder("m2").toPath();
        var cacheHome = temp.newFolder("cache-home").toPath();

        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:list", Set.of(workspace.resolve("lib/list.jar"))), null);
        MavenTooling.storeCachedMavenDependencies(
                workspace.resolve("pom.xml"), m2, cacheHome,
                Map.of("dependency:sources", Set.of(workspace.resolve("lib/src.jar"))), null);

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
                workspace.resolve("pom.xml"), m2, blockingFile,
                Map.of("dependency:list", Set.of(workspace.resolve("lib/list.jar"))), null);

        var cacheFile = MavenTooling.workspaceCacheFile(workspace, blockingFile, null);
        assertThat(Files.exists(cacheFile), equalTo(false));
    }

    @Test
    public void workspaceCacheFileUsesWorkspaceFallbackNameForRootPath() {
        var cacheFile = MavenTooling.workspaceCacheFile(Paths.get("/"), Paths.get("/tmp/cache-home"), null);
        assertThat(cacheFile.getParent().getParent().getFileName().toString(), startsWith("workspace-"));
    }

    @Test
    public void fingerprintExistingFileThrowsForMissingPath() throws Exception {
        var method = MavenTooling.class.getDeclaredMethod("stampExistingFile", Path.class);
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

}
