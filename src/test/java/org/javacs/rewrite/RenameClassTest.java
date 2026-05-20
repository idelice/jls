package org.javacs.rewrite;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.LanguageServerFixture;
import org.junit.Test;

public class RenameClassTest {
    static final CompilerProvider compiler = LanguageServerFixture.getCompilerProvider();
    private static final Logger LOG = Logger.getLogger(RenameClassTest.class.getName());

    private Path file(String name) {
        return LanguageServerFixture.DEFAULT_WORKSPACE_ROOT
                .resolve("src/org/javacs/rewrite")
                .resolve(name)
                .toAbsolutePath();
    }

    @Test
    public void renameClassDeclaration() {
        var edits =
                new RenameClass("org.javacs.rewrite.TestRenameClassOldName", "TestRenameClassNewName")
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestRenameClassOldName.java")));
    }

    @Test
    public void renameUpdatesReferences() {
        var edits =
                new RenameClass("org.javacs.rewrite.TestRenameClassOldName", "TestRenameClassNewName")
                        .rewrite(compiler);
        assertThat(edits, hasKey(file("TestRenameClassRef.java")));
    }

    @Test
    public void renameConstructorInSource() {
        var edits =
                new RenameClass("org.javacs.rewrite.TestRenameClassOldName", "TestRenameClassNewName")
                        .rewrite(compiler);
        var sourceEdits = edits.get(file("TestRenameClassOldName.java"));
        assertThat(sourceEdits.length, greaterThan(0));
    }

    @Test
    public void cancelOnSameName() {
        var edits =
                new RenameClass("org.javacs.rewrite.TestRenameClassOldName", "TestRenameClassOldName")
                        .rewrite(compiler);
        assertThat(edits, is(equalTo(Rewrite.CANCELLED)));
    }

    @Test
    public void renameUpdatesConstructorCalls() {
        var edits =
                new RenameClass("org.javacs.rewrite.TestRenameClassOldName", "TestRenameClassNewName")
                        .rewrite(compiler);
        var refEdits = edits.get(file("TestRenameClassRef.java"));
        assertThat(refEdits, notNullValue());
    }

    @Test
    public void renameUpdatesImportStatements() {
        var edits =
                new RenameClass("org.javacs.rewrite.TestRenameClassOldName", "TestRenameClassNewName")
                        .rewrite(compiler);
        assertThat(edits.size(), greaterThanOrEqualTo(2));
    }

    @Test
    public void renameDoesNotRenameVariableNamesInLombokAnnotatedFile() throws IOException {
        var edits =
                new RenameClass("org.javacs.rewrite.TestLombokBean", "TestLombokBeanNew")
                        .rewrite(compiler);
        var holderFile = file("TestLombokBeanHolder.java");
        assertThat(edits, hasKey(holderFile));
        var holderEdits = edits.get(holderFile);
        assertThat(holderEdits, notNullValue());
        assertThat(holderEdits.length, greaterThan(0));

        var holderLines = Files.readAllLines(holderFile);
        for (var edit : holderEdits) {
            var line = edit.range.start.line;
            var colStart = edit.range.start.character;
            var colEnd = edit.range.end.character;
            LOG.info(String.format(
                    "[lombokRename] edit line=%d col=%d..%d newText=%s",
                    line, colStart, colEnd, edit.newText));
            var actualText = holderLines.get(line).substring(colStart, colEnd);
            assertThat(
                    "Edit must replace 'TestLombokBean' but found '"
                            + actualText
                            + "' at line=" + line + " col=" + colStart + ".." + colEnd
                            + " — a variable/field name is being renamed instead",
                    actualText,
                    is("TestLombokBean"));
        }
    }
}
