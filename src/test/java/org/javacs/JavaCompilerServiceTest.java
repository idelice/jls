package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

public class JavaCompilerServiceTest {
    static {
        Main.setRootFormat();
    }

    private JavaCompilerService compiler =
            new JavaCompilerService(
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptySet());

    static Path simpleProjectSrc() {
        return Paths.get("src/test/examples/simple-project").normalize();
    }

    @Before
    public void setWorkspaceRoot() {
        FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
    }

    @Test
    public void fallsBackToNonApCompileWhenProcessorCrashes() throws IOException {
        var root = Files.createTempDirectory("ap-fallback");
        var file = root.resolve("ApFallback.java");
        Files.writeString(file, "class ApFallback { int value() { return 1; } }\n");
        FileStore.setWorkspaceRoots(Set.of(root));

        var classPath = testRuntimeClassPath();
        var extraArgs = Set.of("-proc:full");
        var compilerWithFailingProcessor =
                new JavaCompilerService(classPath, Collections.emptySet(), Collections.emptySet(), extraArgs);

        try (var task = compilerWithFailingProcessor.compile(file)) {
            var hasError = task.diagnostics.stream().anyMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR);
            assertThat("fallback compile should succeed without fatal errors", hasError, is(false));
            assertThat(task.roots, not(empty()));
        }
    }

    private static Set<Path> testRuntimeClassPath() {
        var result = new HashSet<Path>();
        var entries = System.getProperty("java.class.path", "").split(java.io.File.pathSeparator);
        for (var entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            result.add(Paths.get(entry));
        }
        return result;
    }
}
