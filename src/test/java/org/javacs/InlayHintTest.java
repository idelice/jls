package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import com.google.gson.JsonObject;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.InlayHintParams;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentItem;
import org.javacs.lsp.TextDocumentContentChangeEvent;
import org.javacs.lsp.VersionedTextDocumentIdentifier;
import org.junit.Test;

public class InlayHintTest {
    private final JavaLanguageServer server =
            LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.SIMPLE_WORKSPACE_ROOT, diagnostic -> {});

    @Test
    public void parameterNameHints() {
        configureHints(true);

        var path = Paths.get("src/test/examples/simple-project/InlayHintsSimple.java").toAbsolutePath().normalize();
        var uri = path.toUri();
        var range = new Range(new Position(0, 0), new Position(200, 0));
        var params = new InlayHintParams(new TextDocumentIdentifier(uri), range);
        List<InlayHint> hintsResult = server.inlayHint(params);

        var labels = hintsResult.stream().map(h -> h.label).collect(Collectors.toList());
        assertThat(labels, hasItems("label: ", "count: "));
        boolean hasStringType = labels.stream().anyMatch(l -> l.contains("String"));
        org.junit.Assert.assertTrue("expected var type hint to contain String", hasStringType);
    }

    @Test
    public void transientErrorDoesNotDropHintsBelowEdit() throws Exception {
        configureHints(true);
        var path = Paths.get("src/test/examples/simple-project/InlayHintsTransientEdit.java").toAbsolutePath().normalize();
        var uri = path.toUri();
        var initialText = java.nio.file.Files.readString(path);

        var open = new DidOpenTextDocumentParams();
        open.textDocument = new TextDocumentItem();
        open.textDocument.uri = uri;
        open.textDocument.languageId = "java";
        open.textDocument.version = 1;
        open.textDocument.text = initialText;
        server.didOpenTextDocument(open);

        var range = new Range(new Position(0, 0), new Position(200, 0));
        var params = new InlayHintParams(new TextDocumentIdentifier(uri), range);
        var baseline = server.inlayHint(params).size();

        var changed = initialText.replace("        \n", "        w\n");
        var change = new DidChangeTextDocumentParams();
        change.textDocument = new VersionedTextDocumentIdentifier();
        change.textDocument.uri = uri;
        change.textDocument.version = 2;
        var fullTextChange = new TextDocumentContentChangeEvent();
        fullTextChange.text = changed;
        change.contentChanges = List.of(fullTextChange);
        server.didChangeTextDocument(change);

        var after = server.inlayHint(params).size();
        assertThat(after, greaterThanOrEqualTo(baseline));
    }

    @Test
    public void varTypeHintsRefreshAfterDebounceWindow() throws Exception {
        configureHints(false, 250, 120000, 256);
        var path = Paths.get("src/test/examples/simple-project/InlayHintsVarTypeChange.java")
                .toAbsolutePath()
                .normalize();
        var uri = path.toUri();
        var initialText = java.nio.file.Files.readString(path);

        var open = new DidOpenTextDocumentParams();
        open.textDocument = new TextDocumentItem();
        open.textDocument.uri = uri;
        open.textDocument.languageId = "java";
        open.textDocument.version = 1;
        open.textDocument.text = initialText;
        server.didOpenTextDocument(open);

        var range = new Range(new Position(0, 0), new Position(200, 0));
        var params = new InlayHintParams(new TextDocumentIdentifier(uri), range);
        var baselineLabels =
                server.inlayHint(params).stream().map(h -> h.label).collect(Collectors.toList());
        assertThat(baselineLabels, hasItem(": String"));

        var changed = initialText.replace("\"abc\"", "1");
        var change = new DidChangeTextDocumentParams();
        change.textDocument = new VersionedTextDocumentIdentifier();
        change.textDocument.uri = uri;
        change.textDocument.version = 2;
        var fullTextChange = new TextDocumentContentChangeEvent();
        fullTextChange.text = changed;
        change.contentChanges = List.of(fullTextChange);
        server.didChangeTextDocument(change);

        var immediateLabels =
                server.inlayHint(params).stream().map(h -> h.label).collect(Collectors.toList());
        assertThat(immediateLabels, hasItem(": String"));

        Thread.sleep(350);

        var refreshedLabels =
                server.inlayHint(params).stream().map(h -> h.label).collect(Collectors.toList());
        assertThat(refreshedLabels, hasItem(": int"));
        assertThat(refreshedLabels, not(hasItem(": String")));
    }

    @Test
    public void debounceDisabledRefreshesVarTypeHintsImmediately() throws Exception {
        configureHints(false, 0, 120000, 256);
        var path = Paths.get("src/test/examples/simple-project/InlayHintsVarTypeChange.java")
                .toAbsolutePath()
                .normalize();
        var uri = path.toUri();
        var initialText = java.nio.file.Files.readString(path);

        var open = new DidOpenTextDocumentParams();
        open.textDocument = new TextDocumentItem();
        open.textDocument.uri = uri;
        open.textDocument.languageId = "java";
        open.textDocument.version = 1;
        open.textDocument.text = initialText;
        server.didOpenTextDocument(open);

        var range = new Range(new Position(0, 0), new Position(200, 0));
        var params = new InlayHintParams(new TextDocumentIdentifier(uri), range);
        var baselineLabels =
                server.inlayHint(params).stream().map(h -> h.label).collect(Collectors.toList());
        assertThat(baselineLabels, hasItem(": String"));

        var changed = initialText.replace("\"abc\"", "1");
        var change = new DidChangeTextDocumentParams();
        change.textDocument = new VersionedTextDocumentIdentifier();
        change.textDocument.uri = uri;
        change.textDocument.version = 2;
        var fullTextChange = new TextDocumentContentChangeEvent();
        fullTextChange.text = changed;
        change.contentChanges = List.of(fullTextChange);
        server.didChangeTextDocument(change);

        var immediateLabels =
                server.inlayHint(params).stream().map(h -> h.label).collect(Collectors.toList());
        assertThat(immediateLabels, hasItem(": int"));
        assertThat(immediateLabels, not(hasItem(": String")));
    }

    private void configureHints(boolean parameterNames) {
        configureHints(parameterNames, 250, 120000, 256);
    }

    private void configureHints(boolean parameterNames, int debounceMs, int cacheIdleMs, int cacheMaxEntries) {
        var settings = new JsonObject();
        var java = new JsonObject();
        var hints = new JsonObject();
        hints.addProperty("enabled", true);
        hints.addProperty("parameterNames", parameterNames);
        hints.addProperty("debounceMs", debounceMs);
        hints.addProperty("cacheIdleMs", cacheIdleMs);
        hints.addProperty("cacheMaxEntries", cacheMaxEntries);
        java.add("inlayHints", hints);
        settings.add("java", java);
        var config = new org.javacs.lsp.DidChangeConfigurationParams();
        config.settings = settings;
        server.didChangeConfiguration(config);
    }
}
