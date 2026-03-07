package org.javacs;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.DidChangeConfigurationParams;
import org.javacs.lsp.DidSaveTextDocumentParams;
import org.javacs.lsp.Position;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentPositionParams;
import org.javacs.lsp.TextDocumentItem;
import org.junit.Assert;
import org.junit.Test;

public class JavaLanguageServerTest {

    @Test
    public void LintShouldNotCrashOnCodeWithMissingTypeIdentifier() {
        String filePath = "src/test/examples/missing-type-identifier/Sample.java";
        TextDocumentItem textDocument = new TextDocumentItem();
        textDocument.uri = URI.create("file:///" + filePath);
        try {
            textDocument.text = Files.readString(Path.of(filePath));
        } catch (IOException e) {
            System.out.println(e.toString());
        }
        textDocument.version = 1;
        textDocument.languageId = "java";
        JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();
        server.didOpenTextDocument(new DidOpenTextDocumentParams(textDocument));

        // Should not fail
        server.lint(Collections.singleton(Paths.get(textDocument.uri)));
    }

    @Test
    public void lintDoesNotRefreshCompletionIndex() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var hello = FindResource.path("org/javacs/example/HelloWorld.java");
        var before = completionIndexVersion(server);
        server.lint(List.of(hello));
        var after = completionIndexVersion(server);
        Assert.assertEquals("diagnostics lint should not rebuild completion index", before, after);
    }

    @Test
    public void didSaveRefreshesCompletionIndexWithoutDiagnosticsCoupling() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var initial = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = initial;
        server.didOpenTextDocument(open);

        var before = completionIndexVersion(server);
        var save = new DidSaveTextDocumentParams();
        save.textDocument = new TextDocumentIdentifier(file.toUri());
        server.didSaveTextDocument(save);
        var updated = awaitCompletionIndexAdvance(server, before, 10, TimeUnit.SECONDS);
        Assert.assertTrue("didSave should trigger completion index refresh", updated);
    }

    @Test
    public void completionUsesCurrentIndexEvenAfterUnsavedVersionChange() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var original = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = original;
        server.didOpenTextDocument(open);

        var broken = original.replace("this.", "this.\n");
        var change = new org.javacs.lsp.DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = 2;
        var delta = new org.javacs.lsp.TextDocumentContentChangeEvent();
        delta.text = broken;
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);

        var completion =
                server.completion(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(4, 13)));
        Assert.assertTrue(completion.isPresent());
        Assert.assertTrue(
                "member completion should keep using index after unsaved change",
                completion.get().items.stream().anyMatch(i -> "testFields".equals(i.label)));
    }

    @Test
    public void concurrentCompilerCallsAfterSettingsChangeRecreateCompilerOnce() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var before = completionIndexVersion(server);

        var settings = new JsonObject();
        var java = new JsonObject();
        var inlayHints = new JsonObject();
        inlayHints.addProperty("enabled", true);
        java.add("inlayHints", inlayHints);
        java.addProperty("codeLens", true);
        settings.add("java", java);
        var change = new DidChangeConfigurationParams();
        change.settings = settings;
        server.didChangeConfiguration(change);

        var workers = 6;
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(workers);
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < workers; i++) {
            var t =
                    new Thread(
                            () -> {
                                try {
                                    ready.countDown();
                                    start.await(5, TimeUnit.SECONDS);
                                    server.compiler();
                                } catch (Throwable e) {
                                    failures.add(e);
                                } finally {
                                    done.countDown();
                                }
                            },
                            "compiler-race-" + i);
            t.start();
        }

        Assert.assertTrue("workers were not ready in time", ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        Assert.assertTrue("workers did not finish in time", done.await(60, TimeUnit.SECONDS));
        Assert.assertTrue("compiler workers failed: " + failures, failures.isEmpty());

        var after = completionIndexVersion(server);
        Assert.assertEquals(
                "settings change should recreate compiler exactly once under concurrency",
                before + 1,
                after);
    }

    private long completionIndexVersion(JavaLanguageServer server) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("completionIndexVersion");
        field.setAccessible(true);
        return ((AtomicLong) field.get(server)).get();
    }

    private boolean awaitCompletionIndexAdvance(
            JavaLanguageServer server, long before, long timeout, TimeUnit unit) throws Exception {
        var deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (completionIndexVersion(server) > before) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
