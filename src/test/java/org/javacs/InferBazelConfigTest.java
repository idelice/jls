package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Ignore;
import org.junit.Test;

@Ignore // TODO Bazel updates have broken this, will revive it...
public class InferBazelConfigTest {
    @Test
    public void bazelClassPath() {
        var bazel = new InferConfig(Paths.get("src/test/examples/bazel-project"), Collections.emptySet());
        var classPath = bazel.classPath();
        assertThat(classPath, contains(hasToString(endsWith("guava-18.0.jar"))));
    }

    @Test
    public void bazelClassPathInSubdir() {
        var bazel = new InferConfig(Paths.get("src/test/examples/bazel-project/hello"), Collections.emptySet());
        var classPath = bazel.classPath();
        assertThat(classPath, contains(hasToString(endsWith("guava-18.0.jar"))));
    }

    @Test
    public void bazelClassPathWithProtos() {
        var bazel = new InferConfig(Paths.get("src/test/examples/bazel-protos-project"), Collections.emptySet());
        var classPath = bazel.classPath();
        assertThat(classPath, hasItem(hasToString(endsWith("libperson_proto-speed.jar"))));
    }

    @Test
    public void bazelDocPath() {
        var bazel = new InferConfig(Paths.get("src/test/examples/bazel-project"), Collections.emptySet());
        var docPath = bazel.buildDocPath();
        assertThat(docPath, contains(hasToString(endsWith("guava-18.0-sources.jar"))));
    }

    @Test
    public void bazelDocPathInSubdir() {
        var bazel = new InferConfig(Paths.get("src/test/examples/bazel-project/hello"), Collections.emptySet());
        assertThat(bazel.buildDocPath(), contains(hasToString(endsWith("guava-18.0-sources.jar"))));
    }

    @Test
    public void bazelDocPathWithProtos() {
        var bazel = new InferConfig(Paths.get("src/test/examples/bazel-protos-project"), Collections.emptySet());
        assertThat(bazel.buildDocPath(), hasItem(hasToString(endsWith("person_proto-speed-src.jar"))));
    }
}
