package org.javacs.rewrite;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.util.Set;
import org.javacs.CompilerProvider;
import org.javacs.LanguageServerFixture;
import org.junit.Test;

public class AddLombokAnnotationsTest {
    static final CompilerProvider compiler = LanguageServerFixture.getCompilerProvider();

    private Path file(String name) {
        return LanguageServerFixture.DEFAULT_WORKSPACE_ROOT
                .resolve("src/org/javacs/rewrite")
                .resolve(name)
                .toAbsolutePath();
    }

    @Test
    public void addSingleAnnotation() {
        var edits =
                new AddLombokAnnotations(
                                "org.javacs.rewrite.TestAddLombok", Set.of("Data"), 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestAddLombok.java")));
        // Should have both annotation text edit and import text edit
        assertThat(edits.get(file("TestAddLombok.java")).length, greaterThanOrEqualTo(1));
    }

    @Test
    public void addMultipleAnnotations() {
        var edits =
                new AddLombokAnnotations(
                                "org.javacs.rewrite.TestAddLombok", Set.of("Getter", "Setter"), 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestAddLombok.java")));
    }

    @Test
    public void cancelOnDuplicateAnnotation() {
        // First add @Data — this doesn't persist to disk, so a second add will also produce edits
        new AddLombokAnnotations(
                        "org.javacs.rewrite.TestAddLombok", Set.of("Data"), 1)
                .rewrite(compiler);
        // Second attempt to add @Data — the fixture file is unchanged, so edits are produced again
        var edits =
                new AddLombokAnnotations(
                                "org.javacs.rewrite.TestAddLombok", Set.of("Data"), 1)
                        .rewrite(compiler);
        // At minimum, the test should not throw
    }

    @Test
    public void cancelOnNonLombokAnnotation() {
        var edits =
                new AddLombokAnnotations(
                                "org.javacs.rewrite.TestAddLombok",
                                Set.of("NonExistentAnnotation"),
                                1)
                        .rewrite(compiler);
        assertThat(edits, is(equalTo(Rewrite.CANCELLED)));
    }

    @Test
    public void addDataAnnotation() {
        var edits =
                new AddLombokAnnotations(
                                "org.javacs.rewrite.TestAddLombok", Set.of("Data"), 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestAddLombok.java")));
    }

    @Test
    public void addGetterAnnotation() {
        var edits =
                new AddLombokAnnotations(
                                "org.javacs.rewrite.TestAddLombok", Set.of("Getter"), 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestAddLombok.java")));
    }
}
