package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.Instant;
import java.util.Set;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.TextDocumentContentChangeEvent;
import org.junit.Test;

public class SourceFileObjectTest {

    @Test
    public void capturesOpenDocumentSnapshotAtConstructionTime() {
        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT));
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var initial = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.text = initial;
        FileStore.open(open);

        var snapshot = new SourceFileObject(file);
        var snapshotModified = snapshot.getLastModified();

        var changed = new DidChangeTextDocumentParams();
        changed.textDocument.uri = file.toUri();
        changed.textDocument.version = 2;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = initial + "\n// changed";
        changed.contentChanges.add(delta);
        FileStore.change(changed);

        assertThat(snapshot.contentVersion(), is(1));
        assertThat(snapshot.getLastModified(), is(snapshotModified));
        assertThat(snapshot.getCharContent(true).toString(), is(initial));
        assertThat(FileStore.version(file), is(2));
    }

    @Test
    public void parserCacheUsesVersionWhenModifiedMillisIsUnchanged() {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var initial = FileStore.contents(file);
        var updated = initial.replace("Hello world!", "Hello world 2!");
        var fixedTime = Instant.EPOCH;

        var first = Parser.parseJavaFileObject(new SourceFileObject(file, initial, fixedTime, 1));
        var second = Parser.parseJavaFileObject(new SourceFileObject(file, updated, fixedTime, 2));

        assertThat(first.contents, is(initial));
        assertThat(second.contents, is(updated));
    }

    @Test
    public void parserDoesNotReuseSharedParseInstanceAcrossRequests() {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var contents = FileStore.contents(file);
        var fixedTime = Instant.EPOCH;

        var first = Parser.parseJavaFileObject(new SourceFileObject(file, contents, fixedTime, 7));
        var second = Parser.parseJavaFileObject(new SourceFileObject(file, contents, fixedTime, 7));

        assertThat(first == second, is(false));
    }
}
