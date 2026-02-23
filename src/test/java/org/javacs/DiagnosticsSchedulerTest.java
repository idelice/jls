package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

import com.google.gson.JsonElement;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.javacs.lsp.*;
import org.junit.Before;
import org.junit.Test;

public class DiagnosticsSchedulerTest {
    private final AtomicInteger publishedForFile = new AtomicInteger();
    private final AtomicInteger publishedForSecondFile = new AtomicInteger();
    private final AtomicInteger totalPublished = new AtomicInteger();
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
        server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT,
                        new LanguageClient() {
                            @Override
                            public void publishDiagnostics(PublishDiagnosticsParams params) {
                                totalPublished.incrementAndGet();
                                if (params.uri.equals(file.toUri())) {
                                    publishedForFile.incrementAndGet();
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
        assertThat(publishedForFile.get(), greaterThanOrEqualTo(2));
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
}
