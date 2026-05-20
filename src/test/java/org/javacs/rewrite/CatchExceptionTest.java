package org.javacs.rewrite;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import org.javacs.CompilerProvider;
import org.javacs.LanguageServerFixture;
import org.junit.Test;

public class CatchExceptionTest {
    static final CompilerProvider compiler = LanguageServerFixture.getCompilerProvider();

    private Path file(String name) {
        return LanguageServerFixture.DEFAULT_WORKSPACE_ROOT
                .resolve("src/org/javacs/rewrite")
                .resolve(name)
                .toAbsolutePath();
    }

    @Test
    public void wrapCheckedExceptionStatement() {
        var file = file("TestCatchException.java");
        var edits = new CatchException("org.javacs.rewrite.TestCatchException", "java.io.IOException", 192, 193)
                .rewrite(compiler);
        assertThat(edits.keySet(), hasSize(1));
        assertThat(edits, hasKey(file));
        var textEdits = edits.get(file);
        assertThat(textEdits.length, greaterThan(0));
        assertThat(textEdits[0].newText, containsString("try {"));
        assertThat(textEdits[0].newText, containsString("catch (IOException e)"));
    }

    @Test
    public void wrapSimpleStatement() {
        var edits = new CatchException("org.javacs.rewrite.TestCatchException", "Exception", 310, 311)
                .rewrite(compiler);
        assertThat(edits.keySet(), hasSize(1));
        assertThat(edits, hasKey(file("TestCatchException.java")));
        var textEdits = edits.get(file("TestCatchException.java"));
        assertThat(textEdits[0].newText, containsString("try {"));
        assertThat(textEdits[0].newText, containsString("catch (Exception e)"));
    }

    @Test
    public void cancelWhenAlreadyInTryCatch() {
        var edits = new CatchException("org.javacs.rewrite.TestCatchException", "Exception", 418, 419)
                .rewrite(compiler);
        assertThat(edits, is(equalTo(Rewrite.CANCELLED)));
    }

    @Test
    public void cancelWhenNotInMethod() {
        var edits = new CatchException("org.javacs.rewrite.TestCatchException", "Exception", 0, 1)
                .rewrite(compiler);
        assertThat(edits, is(equalTo(Rewrite.CANCELLED)));
    }

    @Test
    public void useCustomExceptionType() {
        var edits = new CatchException("org.javacs.rewrite.TestCatchException", "java.sql.SQLException", 192, 193)
                .rewrite(compiler);
        assertThat(edits, hasKey(file("TestCatchException.java")));
        var textEdits = edits.get(file("TestCatchException.java"));
        assertThat(textEdits[0].newText, containsString("catch (SQLException e)"));
    }

    @Test
    public void defaultExceptionType() {
        var edits = new CatchException("org.javacs.rewrite.TestCatchException", "", 192, 193)
                .rewrite(compiler);
        assertThat(edits, hasKey(file("TestCatchException.java")));
        var textEdits = edits.get(file("TestCatchException.java"));
        assertThat(textEdits[0].newText, containsString("catch (Exception e)"));
    }
}
