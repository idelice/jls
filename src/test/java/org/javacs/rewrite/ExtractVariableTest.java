package org.javacs.rewrite;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import org.javacs.CompilerProvider;
import org.javacs.LanguageServerFixture;
import org.junit.Test;

public class ExtractVariableTest {
    static final CompilerProvider compiler = LanguageServerFixture.getCompilerProvider();

    private Path file(String name) {
        return LanguageServerFixture.DEFAULT_WORKSPACE_ROOT
                .resolve("src/org/javacs/rewrite")
                .resolve(name)
                .toAbsolutePath();
    }

    @Test
    public void extractMethodCallToVariable() {
        var type = new JavaType("long", new JavaType[0]);
        var edits = new ExtractVariable("org.javacs.rewrite.TestExtractVariable", type, 111, 144)
                .rewrite(compiler);
        assertThat(edits, hasKey(file("TestExtractVariable.java")));
        assertThat(edits.get(file("TestExtractVariable.java")).length, is(2));
    }

    @Test
    public void extractToStringCall() {
        var type = new JavaType("String", new JavaType[0]);
        var edits = new ExtractVariable("org.javacs.rewrite.TestExtractVariable", type, 210, 237)
                .rewrite(compiler);
        assertThat(edits, hasKey(file("TestExtractVariable.java")));
        assertThat(edits.get(file("TestExtractVariable.java")).length, is(2));
    }

    @Test
    public void cancelWhenSimpleVariable() {
        var type = new JavaType("int", new JavaType[0]);
        var edits = new ExtractVariable("org.javacs.rewrite.TestExtractVariable", type, 315, 316)
                .rewrite(compiler);
        assertThat(edits, is(equalTo(Rewrite.CANCELLED)));
    }

    @Test
    public void extractExpression() {
        var type = new JavaType("int", new JavaType[0]);
        var edits = new ExtractVariable("org.javacs.rewrite.TestExtractVariable", type, 414, 419)
                .rewrite(compiler);
        assertThat(edits, hasKey(file("TestExtractVariable.java")));
        assertThat(edits.get(file("TestExtractVariable.java")).length, is(2));
    }

    @Test
    public void generatedVariableNameIsMethodName() {
        var type = new JavaType("long", new JavaType[0]);
        var edits = new ExtractVariable("org.javacs.rewrite.TestExtractVariable", type, 111, 144)
                .rewrite(compiler);
        assertThat(edits, hasKey(file("TestExtractVariable.java")));
        assertThat(edits.get(file("TestExtractVariable.java"))[1].newText, is("size"));
    }

    @Test
    public void cancelOnCrossStatementSelection() {
        var type = new JavaType("int", new JavaType[0]);
        var edits = new ExtractVariable("org.javacs.rewrite.TestExtractVariable", type, 367, 397)
                .rewrite(compiler);
        assertThat(edits, is(equalTo(Rewrite.CANCELLED)));
    }
}
