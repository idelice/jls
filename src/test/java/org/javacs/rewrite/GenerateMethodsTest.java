package org.javacs.rewrite;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import org.javacs.CompilerProvider;
import org.javacs.LanguageServerFixture;
import org.junit.Test;

public class GenerateMethodsTest {
    static final CompilerProvider compiler = LanguageServerFixture.getCompilerProvider();

    private Path file(String name) {
        return LanguageServerFixture.DEFAULT_WORKSPACE_ROOT
                .resolve("src/org/javacs/rewrite")
                .resolve(name)
                .toAbsolutePath();
    }

    @Test
    public void generateConstructor() {
        var edits =
                new GenerateMethods("org.javacs.rewrite.TestGenerateMethods", "constructor", 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestGenerateMethods.java")));
    }

    @Test
    public void generateGetters() {
        var edits =
                new GenerateMethods("org.javacs.rewrite.TestGenerateMethods", "getters", 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestGenerateMethods.java")));
    }

    @Test
    public void generateSetters() {
        var edits =
                new GenerateMethods("org.javacs.rewrite.TestGenerateMethods", "setters", 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestGenerateMethods.java")));
    }

    @Test
    public void generateEquals() {
        var edits =
                new GenerateMethods("org.javacs.rewrite.TestGenerateMethods", "equals", 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestGenerateMethods.java")));
    }

    @Test
    public void generateHashCode() {
        var edits =
                new GenerateMethods("org.javacs.rewrite.TestGenerateMethods", "hashCode", 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestGenerateMethods.java")));
    }

    @Test
    public void generateToString() {
        var edits =
                new GenerateMethods("org.javacs.rewrite.TestGenerateMethods", "toString", 1)
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestGenerateMethods.java")));
    }

    @Test
    public void cancelOnInvalidMethodKind() {
        var edits =
                new GenerateMethods("org.javacs.rewrite.TestGenerateMethods", "invalidKind", 1)
                        .rewrite(compiler);
        assertThat(edits, is(equalTo(Rewrite.CANCELLED)));
    }
}
