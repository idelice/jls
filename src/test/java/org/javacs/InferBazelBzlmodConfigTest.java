package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Paths;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.BeforeClass;
import java.io.IOException;

public class InferBazelBzlmodConfigTest {
    private static final String EXAMPLE_PATH = "src/test/examples/bazel-bzlmod-project";

    @BeforeClass
    public static void setup() throws IOException {
        var path = Paths.get(EXAMPLE_PATH);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
        var moduleFile = path.resolve("MODULE.bazel");
        if (!Files.exists(moduleFile)) {
            Files.createFile(moduleFile);
        }
        var subDir = path.resolve("subdir");
        if (!Files.exists(subDir)) {
            Files.createDirectories(subDir);
        }
    }

    @Test
    public void detectBzlmodRoot() {
        var config = new InferConfig(Paths.get(EXAMPLE_PATH));
        assertThat(config.isBazelProject(Paths.get(EXAMPLE_PATH)), is(true));
        assertThat(config.bazelWorkspaceRoot(), equalTo(Paths.get(EXAMPLE_PATH)));
    }

    @Test
    public void detectBzlmodRootFromSubdir() {
        var config = new InferConfig(Paths.get(EXAMPLE_PATH).resolve("subdir"));
        assertThat(config.bazelWorkspaceRoot(), equalTo(Paths.get(EXAMPLE_PATH)));
    }
}
