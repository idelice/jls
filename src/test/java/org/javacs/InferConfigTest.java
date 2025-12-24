package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public class InferConfigTest {
    private Path workspaceRoot = Paths.get("src/test/examples/maven-project");
    private Path mavenHome = Paths.get("src/test/examples/home-dir/.m2");
    private Path gradleHome = Paths.get("src/test/examples/home-dir/.gradle");
    private Set<String> externalDependencies = Set.of("com.external:external-library:1.2");
    private InferConfig both = new InferConfig(workspaceRoot, externalDependencies, mavenHome, gradleHome);
    private InferConfig gradle = new InferConfig(workspaceRoot, externalDependencies, Paths.get("nowhere"), gradleHome);
    private InferConfig thisProject = new InferConfig(Paths.get("."), Set.of());

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
                InferConfig.mvnDependencies(Paths.get("pom.xml"), "dependency:list", Optional.empty()),
                not(empty()));
    }

    @Test
    public void thisProjectClassPath() {
        assertThat(
                thisProject.classPath(),
                hasItem(hasToString(endsWith(".m2/repository/junit/junit/4.13.2/junit-4.13.2.jar"))));
    }

    @Test
    public void thisProjectDocPath() {
        assertThat(
                thisProject.buildDocPath(),
                hasItem(hasToString(endsWith(".m2/repository/junit/junit/4.13.2/junit-4.13.2-sources.jar"))));
    }
}
