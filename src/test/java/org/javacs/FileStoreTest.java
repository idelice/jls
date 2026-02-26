package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.util.Set;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextDocumentContentChangeEvent;
import org.junit.Before;
import org.junit.Test;

public class FileStoreTest {

    @Before
    public void setWorkspaceRoot() {
        FileStore.setWorkspaceRoots(Set.of(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT));
    }

    @Test
    public void packageName() {
        var file = FindResource.path("/org/javacs/example/Goto.java");
        assertThat(FileStore.suggestedPackageName(file), equalTo("org.javacs.example"));
    }

    @Test
    public void missingFile() {
        var file = FindResource.path("/org/javacs/example/NoSuchFile.java");
        assertThat(FileStore.packageName(file), nullValue());
        assertThat(FileStore.modified(file), nullValue());
    }

    @Test
    public void incrementalChangesKeepInMemoryBufferConsistent() {
        var file = FindResource.path("/org/javacs/example/HelloWorld.java");
        var text =
                "class T {\n"
                        + "  void m() {\n"
                        + "    txn.getMsref();\n"
                        + "  }\n"
                        + "}\n";
        open(file, text, 1);

        change(file, 2, range(2, 19, 2, 19), "\n    ");
        change(file, 3, range(3, 4, 3, 4), "t");
        change(file, 4, range(3, 5, 3, 5), "xn");
        change(file, 5, range(3, 7, 3, 7), ".");

        var updated = FileStore.contents(file);
        assertThat(updated, containsString("txn.getMsref();\n    txn.\n"));
        assertThat(updated, not(containsString("txn txn")));
    }

    private void open(Path file, String text, int version) {
        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = version;
        open.textDocument.text = text;
        FileStore.open(open);
    }

    private void change(Path file, int version, Range range, String text) {
        var params = new DidChangeTextDocumentParams();
        params.textDocument.uri = file.toUri();
        params.textDocument.version = version;
        var event = new TextDocumentContentChangeEvent();
        event.range = range;
        event.text = text;
        params.contentChanges.add(event);
        FileStore.change(params);
    }

    private Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter));
    }
}
