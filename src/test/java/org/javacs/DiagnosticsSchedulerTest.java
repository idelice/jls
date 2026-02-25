package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

import com.google.gson.JsonElement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.javacs.lsp.*;
import org.junit.Before;
import org.junit.Test;

public class DiagnosticsSchedulerTest {
    private final AtomicInteger publishedForFile = new AtomicInteger();
    private final AtomicInteger publishedForSecondFile = new AtomicInteger();
    private final AtomicInteger totalPublished = new AtomicInteger();
    private final List<String> publishedCodesForFile = new ArrayList<>();
    private final List<String> publishedCodesWithLineForFile = new ArrayList<>();
    private final List<List<String>> publishEventsForFile = new ArrayList<>();
    private JavaLanguageServer server;
    private Path file;
    private Path secondFile;

    @Before
    public void setup() {
        file = FindResource.path("org/javacs/warn/Unused.java");
        secondFile = FindResource.path("org/javacs/warn/VarUsed.java");
        publishedForFile.set(0);
        publishedForSecondFile.set(0);
        totalPublished.set(0);
        publishedCodesForFile.clear();
        publishedCodesWithLineForFile.clear();
        publishEventsForFile.clear();
        server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT,
                        new LanguageClient() {
                            @Override
                            public void publishDiagnostics(PublishDiagnosticsParams params) {
                                totalPublished.incrementAndGet();
                                if (params.uri.equals(file.toUri())) {
                                    publishedForFile.incrementAndGet();
                                    var eventCodes = new ArrayList<String>();
                                    for (var diagnostic : params.diagnostics) {
                                        if (diagnostic.code != null) {
                                            publishedCodesForFile.add(diagnostic.code);
                                            eventCodes.add(diagnostic.code);
                                            publishedCodesWithLineForFile.add(
                                                    String.format(
                                                            "%s(%d)",
                                                            diagnostic.code,
                                                            diagnostic.range.start.line + 1));
                                        }
                                    }
                                    publishEventsForFile.add(eventCodes);
                                }
                                if (params.uri.equals(secondFile.toUri())) {
                                    publishedForSecondFile.incrementAndGet();
                                }
                            }

                            @Override
                            public void showMessage(ShowMessageParams params) {}

                            @Override
                            public void registerCapability(String method, JsonElement options) {}

                            @Override
                            public void customNotification(String method, JsonElement params) {}
                        });
    }

    @Test
    public void doesNotLintBeforeDebounceDeadline() {
        open(file);
        server.doAsyncWork();
        assertThat(publishedForFile.get(), is(0));
    }

    @Test
    public void debouncedLintPublishesFastOnlyByDefault() throws Exception {
        open(file);
        Thread.sleep(260);
        server.doAsyncWork();
        // Default mode is warnings-on-save-only, so typing path publishes only fast diagnostics.
        assertThat(publishedForFile.get(), is(1));
    }

    @Test
    public void savePublishesFullDiagnostics() throws Exception {
        open(file);
        Thread.sleep(260);
        server.doAsyncWork();
        assertThat(publishedForFile.get(), is(1));

        var save = new DidSaveTextDocumentParams();
        save.textDocument = new TextDocumentIdentifier(file.toUri());
        server.didSaveTextDocument(save);
        Thread.sleep(220);
        server.doAsyncWork();
        assertThat(publishedForFile.get(), greaterThanOrEqualTo(2));
    }

    @Test
    public void savePublishesErrorsFirstThenDeferredWarnings() throws Exception {
        file = FindResource.path("org/javacs/warn/UnusedAfterTyping.java");
        open(file);

        publishEventsForFile.clear();
        publishedCodesWithLineForFile.clear();

        var save = new DidSaveTextDocumentParams();
        save.textDocument = new TextDocumentIdentifier(file.toUri());
        server.didSaveTextDocument(save);

        assertThat(publishEventsForFile.size(), is(1));
        var firstHasUnusedWarning =
                publishEventsForFile.get(0).stream().anyMatch(code -> code.startsWith("unused_local"));
        assertThat(firstHasUnusedWarning, is(false));

        Thread.sleep(220);
        server.doAsyncWork();
        var hasUnusedAfterDeferredPhase =
                publishedCodesWithLineForFile.stream().anyMatch(code -> code.startsWith("unused_local("));
        assertThat(hasUnusedAfterDeferredPhase, is(true));
    }

    @Test
    public void cachedJarSourcesAreNotScheduledForLint() throws Exception {
        var before = totalPublished.get();
        var uri =
                java.net.URI.create(
                        "file:///tmp/jls-jar-sources/test123/com/example/External.java");
        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = uri;
        open.textDocument.text = "package com.example; class External {}";
        open.textDocument.version = editVersion++;
        open.textDocument.languageId = "java";
        server.didOpenTextDocument(open);

        Thread.sleep(260);
        server.doAsyncWork();
        assertThat(totalPublished.get(), is(before));
    }

    @Test
    public void queuedLintRunsForOtherActiveFilesAfterLastEditedCloses() throws Exception {
        open(file);
        open(secondFile);
        close(secondFile);

        Thread.sleep(260);
        server.doAsyncWork();

        assertThat(publishedForFile.get(), is(1));
        assertThat(totalPublished.get(), greaterThanOrEqualTo(2));
    }

    @Test
    public void fastLintStillPublishesWrongArityCompilerError() throws Exception {
        file = FindResource.path("org/javacs/example/LombokWrongArityHover.java");
        open(file);

        Thread.sleep(260);
        server.doAsyncWork();

        var hasWrongArityError =
                publishedCodesForFile.stream()
                        .anyMatch(
                                code ->
                                        code.equals("compiler.err.cant.apply.symbol")
                                                || code.startsWith("compiler.err.cant.resolve.location"));
        assertThat(hasWrongArityError, is(true));
    }

    @Test
    public void fastLintDoesNotReportMissingLombokGetterInsideSpringStringUtilsCall() throws Exception {
        file = FindResource.path("com/example/demo/models/ThisPojIfUsage.java");
        open(file);

        Thread.sleep(260);
        server.doAsyncWork();

        var hasMissingGetter =
                publishedCodesWithLineForFile.stream()
                        .anyMatch(code -> code.startsWith("compiler.err.cant.resolve.location.args(7)"));
        assertThat(hasMissingGetter, is(false));
    }

    @Test
    public void fastLintDoesNotReportMissingLombokGetterInsideSpringStringUtilsServiceCondition() throws Exception {
        file = FindResource.path("com/example/demo/service/ServiceTwo.java");
        open(file);

        Thread.sleep(260);
        server.doAsyncWork();

        var hasMissingGetter =
                publishedCodesWithLineForFile.stream()
                        .anyMatch(code -> code.startsWith("compiler.err.cant.resolve.location.args(13)"));
        assertThat(hasMissingGetter, is(false));
    }

    @Test
    public void fastLintMalformedStringUtilsConditionDoesNotReportMissingLombokGetter() throws Exception {
        file = FindResource.path("com/example/demo/service/ServiceTwo.java");
        open(file);

        var broken =
                FileStore.contents(file)
                        .replace(
                                "if (StringUtils.isEmpty(ksk.getFoo())) {",
                                "if (StringUtils.isEmpty(ksk.getFoo()) {");
        edit(file, broken);

        Thread.sleep(260);
        server.doAsyncWork();

        var hasExpectedSyntaxError =
                publishedCodesWithLineForFile.stream().anyMatch(code -> code.startsWith("compiler.err.expected(14)"));
        assertThat(hasExpectedSyntaxError, is(true));

        var hasMissingGetter =
                publishedCodesWithLineForFile.stream()
                        .anyMatch(code -> code.startsWith("compiler.err.cant.resolve.location.args(14)"));
        assertThat(hasMissingGetter, is(false));
    }

    @Test
    public void unusedLocalWarningReturnsOnlyOnSaveAfterTyping() throws Exception {
        file = FindResource.path("org/javacs/warn/UnusedAfterTyping.java");
        open(file);

        var save = new DidSaveTextDocumentParams();
        save.textDocument = new TextDocumentIdentifier(file.toUri());
        server.didSaveTextDocument(save);
        Thread.sleep(220);
        server.doAsyncWork();

        var hasUnusedAfterSave =
                publishedCodesWithLineForFile.stream().anyMatch(code -> code.startsWith("unused_local("));
        assertThat(hasUnusedAfterSave, is(true));

        publishedCodesWithLineForFile.clear();
        var edited = FileStore.contents(file).replace("var asdw1 = spl[0];", "var asdw1 = spl[0]; ");
        edit(file, edited);
        Thread.sleep(260);
        server.doAsyncWork();

        var hasUnusedDuringTyping =
                publishedCodesWithLineForFile.stream().anyMatch(code -> code.startsWith("unused_local("));
        assertThat(hasUnusedDuringTyping, is(false));

        publishedCodesWithLineForFile.clear();
        server.didSaveTextDocument(save);
        Thread.sleep(220);
        server.doAsyncWork();

        var hasUnusedAfterSecondSave =
                publishedCodesWithLineForFile.stream().anyMatch(code -> code.startsWith("unused_local("));
        assertThat(hasUnusedAfterSecondSave, is(true));
    }

    @Test
    public void stressTypingBurstPublishesTwoPhaseDiagnosticsForManyWarnings() throws Exception {
        file = FindResource.path("org/javacs/warn/DiagnosticsStress.java");
        open(file);

        publishEventsForFile.clear();
        publishedCodesWithLineForFile.clear();
        var base = FileStore.contents(file);

        // Simulate rapid typing bursts (multiple didChange events before debounce fires).
        for (int i = 0; i < 40; i++) {
            var edited = (i % 2 == 0) ? base + "\n" : base;
            edit(file, edited);
        }

        Thread.sleep(260);
        server.doAsyncWork();
        assertThat("fast phase should publish once", publishEventsForFile.size(), is(1));
        var firstHasWarnings =
                publishEventsForFile.get(0).stream().anyMatch(code -> code.startsWith("unused_local"));
        assertThat("fast phase should not include warnings", firstHasWarnings, is(false));

        var beforeSaveEvents = publishEventsForFile.size();
        var save = new DidSaveTextDocumentParams();
        save.textDocument = new TextDocumentIdentifier(file.toUri());
        server.didSaveTextDocument(save);

        Thread.sleep(220);
        server.doAsyncWork();
        var eventsAfterSave = publishEventsForFile.size() - beforeSaveEvents;
        assertThat("save path should produce bounded diagnostics publishes", eventsAfterSave >= 1 && eventsAfterSave <= 2, is(true));

        var hasWarningsAfterSave =
                publishedCodesWithLineForFile.stream().anyMatch(code -> code.startsWith("unused_local("));
        assertThat("save/deferred path should contain warning diagnostics", hasWarningsAfterSave, is(true));
    }

    private static int editVersion = 1;

    private void open(Path javaFile) {
        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = javaFile.toUri();
        open.textDocument.text = FileStore.contents(javaFile);
        open.textDocument.version = editVersion++;
        open.textDocument.languageId = "java";
        server.didOpenTextDocument(open);
    }

    private void close(Path javaFile) {
        var close = new DidCloseTextDocumentParams();
        close.textDocument.uri = javaFile.toUri();
        server.didCloseTextDocument(close);
    }

    private void edit(Path javaFile, String contents) {
        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = javaFile.toUri();
        change.textDocument.version = editVersion++;
        var evt = new TextDocumentContentChangeEvent();
        evt.text = contents;
        change.contentChanges.add(evt);
        server.didChangeTextDocument(change);
    }
}
