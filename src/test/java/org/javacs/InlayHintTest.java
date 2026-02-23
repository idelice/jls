package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.InlayHintParams;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextDocumentContentChangeEvent;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentItem;
import org.javacs.lsp.VersionedTextDocumentIdentifier;
import org.junit.Test;

public class InlayHintTest {
    private final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void varTypeHintsAreReturned() {
        var uri = FindResource.uri("/org/javacs/example/InlayHintsExample.java");
        var hints = server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), fullRange()));
        var labels = hints.stream().map(h -> h.label).collect(Collectors.toList());

        assertThat("labels=" + labels, labels.contains(": String"), equalTo(true));
        assertThat("labels=" + labels, labels.contains(": int"), equalTo(true));
    }

    @Test
    public void parameterNameHintsAreReturned() {
        var uri = FindResource.uri("/org/javacs/example/InlayHintsExample.java");
        var hints = server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), fullRange()));
        var labels = hints.stream().map(h -> h.label).collect(Collectors.toList());

        assertThat("labels=" + labels, labels.contains("label:"), equalTo(true));
        assertThat("labels=" + labels, labels.contains("count:"), equalTo(true));
    }

    @Test
    public void parameterNameHintsCoverConstructorsMethodsAndNullWithoutNullType() {
        var uri = FindResource.uri("/org/javacs/example/ParameterNameHintsExample.java");
        var hints = server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), fullRange()));
        var labels = hints.stream().map(h -> h.label).collect(Collectors.toList());

        assertThat("labels=" + labels, labels.contains("capacity:"), equalTo(true));
        assertThat("labels=" + labels, labels.contains("bound:"), equalTo(true));
        assertThat("labels=" + labels, labels.contains("text:"), equalTo(true));
        assertThat("labels=" + labels, labels.contains("value:"), equalTo(true));
        assertThat("labels=" + labels, labels.contains("maybe:"), equalTo(true));
        assertThat("labels=" + labels, labels.contains("<nulltype>:"), equalTo(false));
    }

    @Test
    public void varTypeHintsUseSimpleTypeNames() {
        var uri = FindResource.uri("/org/javacs/example/VarTypeReferences.java");
        var hints = server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), fullRange()));
        var labels = hints.stream().map(h -> h.label).collect(Collectors.toList());

        assertThat("labels=" + labels, labels.contains(": Foo"), equalTo(false));
    }

    @Test
    public void varTypeHintsSkipRedundantExplicitTypeInitializers() {
        var uri = FindResource.uri("/org/javacs/example/VarRedundantHints.java");
        var hints = server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), fullRange()));
        var labels = hints.stream().map(h -> h.label).collect(Collectors.toList());

        assertThat("labels=" + labels, labels.contains(": Foo"), equalTo(false));
        assertThat("labels=" + labels, labels.contains(": String[]"), equalTo(false));
        assertThat("labels=" + labels, labels.contains(": Object"), equalTo(false));
        assertThat("labels=" + labels, labels.contains(": Class<String>"), equalTo(false));
    }

    @Test
    public void repeatedRequestWithSameVersionReturnsEquivalentHints() {
        var uri = FindResource.uri("/org/javacs/example/InlayHintsExample.java");
        var first = server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), fullRange()));
        var second = server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), fullRange()));

        assertThat(labels(second), equalTo(labels(first)));
    }

    @Test
    public void cacheInvalidatesAfterEdit() throws Exception {
        var file = FindResource.path("/org/javacs/example/InlayHintsExample.java");
        var original = Files.readString(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument = new TextDocumentItem();
        open.textDocument.uri = file.toUri();
        open.textDocument.languageId = "java";
        open.textDocument.version = 1;
        open.textDocument.text = original;
        server.didOpenTextDocument(open);

        var params = new InlayHintParams(new TextDocumentIdentifier(file.toUri()), fullRange());
        var before = server.inlayHint(params);
        var beforeLabels = labels(before);
        assertThat("labels=" + beforeLabels, beforeLabels.contains(": int"), equalTo(true));

        var changed = original.replace("var size = 3;", "var size = \"three\";");
        var update = new DidChangeTextDocumentParams();
        update.textDocument = new VersionedTextDocumentIdentifier();
        update.textDocument.uri = file.toUri();
        update.textDocument.version = 2;
        var change = new TextDocumentContentChangeEvent();
        change.text = changed;
        update.contentChanges = List.of(change);
        server.didChangeTextDocument(update);

        var after = server.inlayHint(params);
        var afterLabels = labels(after);
        assertThat("labels=" + afterLabels, afterLabels.contains(": String"), equalTo(true));
        assertThat(afterLabels, not(equalTo(beforeLabels)));
    }

    private static Range fullRange() {
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }

    private static List<String> labels(List<org.javacs.lsp.InlayHint> hints) {
        return hints.stream().map(h -> h.label).collect(Collectors.toList());
    }
}
