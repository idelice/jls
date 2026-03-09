package org.javacs;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.DidChangeConfigurationParams;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidChangeWatchedFilesParams;
import org.javacs.lsp.DidSaveTextDocumentParams;
import org.javacs.lsp.Diagnostic;
import org.javacs.lsp.DiagnosticSeverity;
import org.javacs.lsp.FileChangeType;
import org.javacs.lsp.FileEvent;
import org.javacs.lsp.InitializeParams;
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.Position;
import org.javacs.lsp.PublishDiagnosticsParams;
import org.javacs.lsp.ShowMessageParams;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentContentChangeEvent;
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
    public void didChangeRefreshesCompletionIndexIncrementallyForLombokModel() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var model = FindResource.path("org/javacs/example/LombokCrossTypeModel.java");
        var original = FileStore.contents(model);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = model.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = original;
        server.didOpenTextDocument(open);
        Assert.assertTrue(
                "expected completion index to initialize before didChange check",
                awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

        var before = completionIndexVersion(server);
        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = model.toUri();
        change.textDocument.version = 2;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = original.replace("private String name;", "private String title;");
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);

        Assert.assertTrue(
                "didChange should refresh completion index for Lombok model updates",
                awaitCompletionIndexAdvance(server, before, 10, TimeUnit.SECONDS));
    }

    @Test
    public void completionBootstrapsInitialCompletionIndexWhenStillEmpty() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/LombokCrossTypeCompletion.java");

        Assert.assertEquals("expected empty completion index before bootstrap", 0L, completionIndexVersion(server));

        var position =
                new TextDocumentPositionParams(
                        new TextDocumentIdentifier(file.toUri()), new Position(5, 14));
        Optional<CompletionList> completion = server.completion(position);

        Assert.assertTrue("expected completion result after bootstrap", completion.isPresent());
        var labels =
                completion.get().items.stream().map(item -> item.label).collect(java.util.stream.Collectors.toSet());
        Assert.assertTrue("expected getter completion after bootstrap", labels.contains("getName"));
        Assert.assertTrue("expected setter completion after bootstrap", labels.contains("setName"));
        Assert.assertTrue(
                "completion should initialize the completion index",
                completionIndexVersion(server) > 0);
    }

    @Test
    public void didOpenBuildsInitialCompletionIndexFromSharedDiagnosticsPass() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer();
            var file = FindResource.path("org/javacs/example/HelloWorld.java");
            var text = FileStore.contents(file);

            Assert.assertEquals("expected empty completion index before didOpen", 0L, completionIndexVersion(server));

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = text;
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "didOpen should initialize the completion index from the shared diagnostics pass",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var debounce = capture.lastLineContaining("[perf] diagnostics_debounce trigger=didOpen");
            Assert.assertNotNull("expected didOpen diagnostics debounce log", debounce);
            Assert.assertTrue(
                    "initial didOpen should reuse the diagnostics compile to build the index, line=" + debounce,
                    debounce.contains("shared_index=true"));
            Assert.assertTrue(
                    "initial shared diagnostics pass should install a full bootstrap index",
                    capture.countContaining("completion_index_refresh_shared trigger=async:didOpen files=1 version=") > 0);
            var shared = capture.lastLineContaining("completion_index_refresh_shared trigger=async:didOpen");
            Assert.assertTrue("expected full bootstrap mode, line=" + shared, shared.contains("mode=full_bootstrap"));
            Assert.assertEquals(
                    "startup should not schedule a separate compilerRecreated completion refresh when no active docs existed",
                    0,
                    capture.countContaining("completion_index_debounce trigger=compilerRecreated"));
            Assert.assertEquals(
                    "full bootstrap should not immediately schedule a redundant activeDeclarations refresh",
                    0,
                    capture.countContaining("completion_index_debounce trigger=index:async:didOpen:activeDeclarations"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void didOpenBootstrapIndexIncludesReferencedTypesForMemberCompletion() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var fieldFile = FindResource.path("org/javacs/example/CompletionBootstrapFieldMembers.java");
        var fieldText = FileStore.contents(fieldFile);

        var openField = new DidOpenTextDocumentParams();
        openField.textDocument.uri = fieldFile.toUri();
        openField.textDocument.version = 1;
        openField.textDocument.languageId = "java";
        openField.textDocument.text = fieldText;
        server.didOpenTextDocument(openField);

        Assert.assertTrue(
                "didOpen should initialize the completion index",
                awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

        var fieldCompletion =
                server.completion(
                                new TextDocumentPositionParams(
                                        new TextDocumentIdentifier(fieldFile.toUri()),
                                        new Position(6, 19)))
                        .orElseThrow();
        var fieldLabels =
                fieldCompletion.items.stream()
                        .map(item -> item.label)
                        .collect(java.util.stream.Collectors.toSet());
        Assert.assertTrue("field receiver should resolve referenced type members", fieldLabels.contains("value"));

        FileStore.reset();
        server = LanguageServerFixture.getJavaLanguageServer();
        var localFile = FindResource.path("org/javacs/example/CompletionBootstrapLocalMembers.java");
        var localText = FileStore.contents(localFile);

        var openLocal = new DidOpenTextDocumentParams();
        openLocal.textDocument.uri = localFile.toUri();
        openLocal.textDocument.version = 1;
        openLocal.textDocument.languageId = "java";
        openLocal.textDocument.text = localText;
        server.didOpenTextDocument(openLocal);

        Assert.assertTrue(
                "didOpen should initialize the completion index for local receiver test",
                awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

        var localCompletion =
                server.completion(
                                new TextDocumentPositionParams(
                                        new TextDocumentIdentifier(localFile.toUri()),
                                        new Position(5, 10)))
                        .orElseThrow();
        var localLabels =
                localCompletion.items.stream()
                        .map(item -> item.label)
                        .collect(java.util.stream.Collectors.toSet());
        Assert.assertTrue("local receiver should resolve referenced type members", localLabels.contains("value"));
    }

    @Test
    public void didChangeClearsPublishedDiagnosticsBeforeDebouncedRefresh() throws Exception {
        var client = new RecordingDiagnosticsClient();
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);
        var file = FindResource.path("org/javacs/example/Goto.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        Assert.assertTrue(
                "expected initial diagnostics on open",
                client.awaitErrorMatching(file.toUri(), __ -> true, 10, TimeUnit.SECONDS));

        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = 2;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = text + "\n// clear-stale";
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);

        Assert.assertTrue(
                "didChange should clear stale diagnostics immediately",
                client.awaitDiagnosticsCount(file.toUri(), 0, 2, TimeUnit.SECONDS));
    }

    @Test
    public void didSaveCompilesOnceAndRefreshesIndexFromSharedResult() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        Thread.sleep(900);

        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var before = completionIndexVersion(server);
            CompileBatch.resetPerfCounters();

            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = file.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = text + "\n// force-save-compile";
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            Thread.sleep(80);
            var save = new DidSaveTextDocumentParams();
            save.textDocument = new TextDocumentIdentifier(file.toUri());
            server.didSaveTextDocument(save);
            var updated = awaitCompletionIndexAdvance(server, before, 10, TimeUnit.SECONDS);
            Assert.assertTrue("didSave should refresh completion index", updated);

            Thread.sleep(300);
            Assert.assertEquals(
                    "didSave should compile diagnostics exactly once",
                    1,
                    capture.countContaining("[perf] diagnostics_compile trigger=didSave"));
            Assert.assertEquals(
                    "didSave should rebuild completion index from the same compile",
                    1,
                    capture.countContaining("[perf] completion_index_refresh_shared trigger=didSave"));
            Assert.assertEquals(
                    "didSave should run one semantic compile",
                    1L,
                    CompileBatch.perfCounters().analyzeInvocations);
            Assert.assertEquals(
                    "pending didChange diagnostics should be canceled by save",
                    0,
                    capture.countContaining("[perf] diagnostics_compile trigger=async:didChange"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void lombokAnnotationTypeDetectionHandlesQualifiedAndSimpleNames() {
        Assert.assertTrue(LombokAnnotations.isLombokAnnotationType("lombok.Data"));
        Assert.assertTrue(LombokAnnotations.isLombokAnnotationType("lombok.experimental.SuperBuilder"));
        Assert.assertTrue(LombokAnnotations.isLombokAnnotationType("Data"));
        Assert.assertTrue(LombokAnnotations.isLombokAnnotationType("Getter"));

        Assert.assertFalse(LombokAnnotations.isLombokAnnotationType(null));
        Assert.assertFalse(LombokAnnotations.isLombokAnnotationType(""));
        Assert.assertFalse(LombokAnnotations.isLombokAnnotationType("org.example.Custom"));
    }

    @Test
    public void diagnosticsStalenessTracksContentRevisionOnly() {
        Assert.assertFalse(JavaLanguageServer.isStaleDiagnosticsContent(-1, 42));
        Assert.assertFalse(JavaLanguageServer.isStaleDiagnosticsContent(7, 7));
        Assert.assertTrue(JavaLanguageServer.isStaleDiagnosticsContent(7, 8));
    }

    @Test
    public void lombokStructuralAnnotationDetectionExcludesSlf4j() {
        Assert.assertTrue(LombokAnnotations.isStructuralLombokAnnotationType("lombok.Data"));
        Assert.assertTrue(LombokAnnotations.isStructuralLombokAnnotationType("Getter"));
        Assert.assertFalse(LombokAnnotations.isStructuralLombokAnnotationType("lombok.extern.slf4j.Slf4j"));
        Assert.assertFalse(LombokAnnotations.isStructuralLombokAnnotationType("Slf4j"));
    }

    @Test
    public void slf4jDidChangeDoesNotFanoutDiagnosticsAcrossActiveDocuments() throws Exception {
        var workspace = Files.createTempDirectory("jls-slf4j-no-fanout");
        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);
            var serviceFile = pkg.resolve("ServiceTwo.java");
            var otherFile = pkg.resolve("Other.java");
            Files.writeString(
                    serviceFile,
                    "package p;\n"
                            + "import lombok.extern.slf4j.Slf4j;\n"
                            + "@Slf4j\n"
                            + "class ServiceTwo {\n"
                            + "  void test() {\n"
                            + "    log.info(\"x\");\n"
                            + "  }\n"
                            + "}\n");
            Files.writeString(
                    otherFile,
                    "package p;\n"
                            + "class Other {\n"
                            + "  void test(ServiceTwo service) {\n"
                            + "    service.toString();\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            var otherOpen = new DidOpenTextDocumentParams();
            otherOpen.textDocument.uri = otherFile.toUri();
            otherOpen.textDocument.version = 1;
            otherOpen.textDocument.languageId = "java";
            otherOpen.textDocument.text = Files.readString(otherFile);
            server.didOpenTextDocument(otherOpen);

            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = serviceFile.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = serviceOpen.textDocument.text + "\n// logger-change";
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String didChangeDebounce = null;
            while (System.nanoTime() < deadline) {
                didChangeDebounce = capture.lastLineContaining("[perf] diagnostics_debounce trigger=didChange");
                if (didChangeDebounce != null) {
                    break;
                }
                Thread.sleep(20);
            }

            Assert.assertNotNull("expected didChange diagnostics debounce log", didChangeDebounce);
            Assert.assertTrue(
                    "logging-only Lombok should stay on single-file diagnostics path",
                    didChangeDebounce.contains("files=1"));
            Assert.assertTrue(
                    "logging-only Lombok should not reuse shared diagnostics compile for index refresh",
                    didChangeDebounce.contains("shared_index=false"));
            Assert.assertEquals(
                    "logging-only Lombok should not fan diagnostics out across active docs",
                    0,
                    capture.countContaining(
                            "[perf] diagnostics_active_fanout trigger=didChange file=ServiceTwo.java"));
            Assert.assertEquals(
                    "logging-only Lombok should not use shared completion-index refresh on didChange",
                    0,
                    capture.countContaining("[perf] completion_index_refresh_shared trigger=async:didChange"));
        } finally {
            logger.removeHandler(capture);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void lombokConsumerDiagnosticsRemainResolvedBeforeAndAfterModelSave() throws Exception {
        var client = new RecordingDiagnosticsClient();
        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);

        var serviceFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        var modelFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/model/ReproTxn.java");

        var serviceOpen = new DidOpenTextDocumentParams();
        serviceOpen.textDocument.uri = serviceFile.toUri();
        serviceOpen.textDocument.version = 1;
        serviceOpen.textDocument.languageId = "java";
        serviceOpen.textDocument.text = Files.readString(serviceFile);
        try {
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "expected Lombok member diagnostics to resolve from referenced Lombok source expansion",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "expected consumer compile set to include referenced Lombok model",
                    capture.countContaining("[perf] lombok_ap_sources requested=1 expanded=2") > 0);

            var modelOpen = new DidOpenTextDocumentParams();
            modelOpen.textDocument.uri = modelFile.toUri();
            modelOpen.textDocument.version = 1;
            modelOpen.textDocument.languageId = "java";
            modelOpen.textDocument.text = Files.readString(modelFile);
            server.didOpenTextDocument(modelOpen);

            var modelSave = new DidSaveTextDocumentParams();
            modelSave.textDocument = new TextDocumentIdentifier(modelFile.toUri());
            server.didSaveTextDocument(modelSave);

            var changed = new FileEvent();
            changed.uri = modelFile.toUri();
            changed.type = FileChangeType.Changed;
            var watched = new DidChangeWatchedFilesParams();
            watched.changes = List.of(changed);
            server.didChangeWatchedFiles(watched);

            Assert.assertTrue(
                    "expected Lombok member diagnostics to stay resolved after model save",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void didChangeLombokModelRefreshesDiagnosticsForActiveConsumersWithoutSave() throws Exception {
        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        var client = new RecordingDiagnosticsClient();
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);

        var serviceFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        var modelFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/model/ReproTxn.java");
        var originalModel = Files.readString(modelFile);

        try {
            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            var modelOpen = new DidOpenTextDocumentParams();
            modelOpen.textDocument.uri = modelFile.toUri();
            modelOpen.textDocument.version = 1;
            modelOpen.textDocument.languageId = "java";
            modelOpen.textDocument.text = originalModel;
            server.didOpenTextDocument(modelOpen);

            Assert.assertTrue(
                    "expected baseline Lombok consumer diagnostics to be clean",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));

            var brokenModel = originalModel.replace("msref", "title");
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = modelFile.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = brokenModel;
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            Assert.assertTrue(
                    "changing a Lombok model should refresh active consumer diagnostics without save",
                    client.awaitErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "lombok didChange should reuse the diagnostics compile for the completion index",
                    capture.countContaining("[perf] completion_index_refresh_shared trigger=async:didChange")
                            > 0);

            var revert = new DidChangeTextDocumentParams();
            revert.textDocument.uri = modelFile.toUri();
            revert.textDocument.version = 3;
            var revertDelta = new TextDocumentContentChangeEvent();
            revertDelta.text = originalModel;
            revert.contentChanges.add(revertDelta);
            server.didChangeTextDocument(revert);

            Assert.assertTrue(
                    "restoring the Lombok model should clear active consumer diagnostics without save",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));
        } finally {
            logger.removeHandler(capture);
        }
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
        Assert.assertTrue(
                "expected completion index to initialize before unsaved-change check",
                awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

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
    public void completionRequestDoesNotInvokeCompileBatch() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");

        Thread.sleep(1200);
        CompileBatch.resetPerfCounters();

        var completion =
                server.completion(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(4, 13)));
        Assert.assertTrue(completion.isPresent());
        Assert.assertEquals(
                "completion should not invoke semantic compile",
                0L,
                CompileBatch.perfCounters().analyzeInvocations);
    }

    @Test
    public void compileAndPublishSerializesConcurrentDiagnosticsCompiles() throws Exception {
        var workspace = Files.createTempDirectory("jls-compile-lock");
        var a = workspace.resolve("A.java");
        var b = workspace.resolve("B.java");
        Files.writeString(a, "class A {}\n");
        Files.writeString(b, "class B {}\n");

        var server = new JavaLanguageServer(new RecordingDiagnosticsClient());
        setLombokVerifyBypass(server);
        var compiler = new ConcurrencyTrackingCompiler();

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();

        var t1 =
                new Thread(
                        () -> {
                            try {
                                start.await(2, TimeUnit.SECONDS);
                                invokeCompileAndPublish(server, List.of(a), compiler, "foreground");
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                            } finally {
                                done.countDown();
                            }
                        },
                        "compile-test-1");

        var t2 =
                new Thread(
                        () -> {
                            try {
                                start.await(2, TimeUnit.SECONDS);
                                invokeCompileAndPublish(server, List.of(b), compiler, "foreground");
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                            } finally {
                                done.countDown();
                            }
                        },
                        "compile-test-2");

        t1.start();
        t2.start();
        start.countDown();

        Assert.assertTrue("both compile tasks should complete", done.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError("concurrent compile call failed", failure.get());
        }
        Assert.assertEquals(
                "compileAndPublish should not overlap diagnostics compiler usage",
                1,
                compiler.maxConcurrentCompiles());

        deleteRecursively(workspace);
    }

    @Test
    public void parseLifecycleParsesOnOpenAndChangeThenReusesForRequestsAndSave() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        var compiler = server.compiler();
        var opened = compiler.parsedUnits.get(file);
        Assert.assertNotNull("didOpen should parse and store AST", opened);
        var openedRoot = opened.task.root;

        var firstCompletion =
                server.completion(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(4, 13)));
        Assert.assertTrue(firstCompletion.isPresent());
        server.hover(new TextDocumentPositionParams(new TextDocumentIdentifier(file.toUri()), new Position(2, 13)));

        var afterOpenRequests = compiler.parsedUnits.get(file);
        Assert.assertNotNull(afterOpenRequests);
        Assert.assertSame(
                "completion/hover should reuse didOpen parse result when text is unchanged",
                openedRoot,
                afterOpenRequests.task.root);

        var save = new DidSaveTextDocumentParams();
        save.textDocument = new TextDocumentIdentifier(file.toUri());
        server.didSaveTextDocument(save);

        var afterSave = compiler.parsedUnits.get(file);
        Assert.assertNotNull(afterSave);
        Assert.assertSame(
                "didSave should reuse existing parse result when there is no text change",
                openedRoot,
                afterSave.task.root);

        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = 2;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = text + "\n// parse-lifecycle-change";
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);

        var changed = compiler.parsedUnits.get(file);
        Assert.assertNotNull("didChange should refresh parsed unit", changed);
        Assert.assertNotSame(
                "didChange should reparse when text changes", openedRoot, changed.task.root);
        var changedRoot = changed.task.root;

        var secondCompletion =
                server.completion(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(4, 13)));
        Assert.assertTrue(secondCompletion.isPresent());
        server.hover(new TextDocumentPositionParams(new TextDocumentIdentifier(file.toUri()), new Position(2, 13)));

        var afterChangeRequests = compiler.parsedUnits.get(file);
        Assert.assertNotNull(afterChangeRequests);
        Assert.assertSame(
                "completion/hover should reuse didChange parse result until next edit",
                changedRoot,
                afterChangeRequests.task.root);
    }

    @Test
    public void concurrentCompilerCallsAfterSettingsChangeRecreateCompilerOnce() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {

            var settings = new JsonObject();
            var java = new JsonObject();
            var extraCompilerArgs = new com.google.gson.JsonArray();
            extraCompilerArgs.add("-Xlint:deprecation");
            java.add("extraCompilerArgs", extraCompilerArgs);
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

            Assert.assertEquals(
                    "settings change should recreate compiler exactly once under concurrency",
                    1,
                    capture.countContaining("[perf] lombok_setting enabled="));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void nonCompilerSettingsDoNotRecreateCompiler() throws Exception {
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

        server.compiler();
        var after = completionIndexVersion(server);
        Assert.assertEquals(
                "non-compiler Java settings should not recreate compiler",
                before,
                after);
    }

    @Test
    public void compilerRecreatedRefreshUsesSharedDiagnosticsPassForActiveFiles() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var settings = new JsonObject();
            var java = new JsonObject();
            var extraCompilerArgs = new com.google.gson.JsonArray();
            extraCompilerArgs.add("-Xlint:deprecation");
            java.add("extraCompilerArgs", extraCompilerArgs);
            settings.add("java", java);
            var change = new DidChangeConfigurationParams();
            change.settings = settings;
            server.didChangeConfiguration(change);

            server.compiler();
            var line = capture.lastLineContaining("[perf] diagnostics_debounce trigger=compilerRecreated");
            Assert.assertTrue("expected compilerRecreated diagnostics debounce log", line != null);
            Assert.assertTrue(
                    "compilerRecreated shared diagnostics pass should use active files only, line=" + line,
                    line.matches(".*files=1\\b.*"));
            Assert.assertTrue(
                    "compilerRecreated active-file startup pass should refresh the index from shared compile, line="
                            + line,
                    line.contains("shared_index=true"));
            Assert.assertEquals(
                    "compilerRecreated should not schedule a separate sync completion refresh when active files exist",
                    0,
                    capture.countContaining("completion_index_refresh_sync trigger=compilerRecreated"));
            Assert.assertEquals(
                    "compilerRecreated should not schedule a separate deferred completion refresh when active files exist",
                    0,
                    capture.countContaining("completion_index_debounce trigger=compilerRecreated"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void startupCompilerRecreatedRefreshIsLazyWhenNoActiveDocs() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server =
                    new JavaLanguageServer(
                            new org.javacs.lsp.LanguageClient() {
                                @Override
                                public void publishDiagnostics(org.javacs.lsp.PublishDiagnosticsParams params) {}

                                @Override
                                public void showMessage(org.javacs.lsp.ShowMessageParams params) {}

                                @Override
                                public void registerCapability(
                                        String method, com.google.gson.JsonElement options) {}

                                @Override
                                public void refreshInlayHints() {}

                                @Override
                                public void customNotification(
                                        String method, com.google.gson.JsonElement params) {}
                            });
            var init = new org.javacs.lsp.InitializeParams();
            init.rootUri = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.toUri();
            server.initialize(init);
            server.initialized();

            Assert.assertEquals(
                    "startup should not do synchronous full refresh when no active docs",
                    0,
                    capture.countContaining("completion_index_refresh_sync trigger=compilerRecreated"));
            Assert.assertEquals(
                    "startup should not schedule a deferred compilerRecreated refresh without active docs",
                    0,
                    capture.countContaining("completion_index_debounce trigger=compilerRecreated"));
            Assert.assertTrue(
                    "startup should log that compilerRecreated refresh is deferred until real demand",
                    capture.countContaining("completion_index_refresh_deferred trigger=compilerRecreated reason=no_active_docs")
                            > 0);

            server.shutdown();
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void didOpenDoesNotOverlapWithCompilerRecreatedProjectIndexCompile() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer();
            var file = FindResource.path("org/javacs/example/HelloWorld.java");
            var text = FileStore.contents(file);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = text;
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "didOpen should still bootstrap the completion index",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            Assert.assertEquals(
                    "startup should not skip a compilerRecreated refresh after already doing the expensive work",
                    0,
                    capture.countContaining("completion_index_refresh_skip trigger=compilerRecreated"));
            Assert.assertEquals(
                    "didOpen bootstrap should not schedule a second declaration refresh compile",
                    0,
                    capture.countContaining("completion_index_debounce trigger=index:async:didOpen:activeDeclarations"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void didOpenDoesNotEagerlyRefreshInlayHintsBeforeIndexInstall() throws Exception {
        FileStore.reset();
        var client = new RecordingInlayHintClient();
        var server = new JavaLanguageServer(client);
        var init = new InitializeParams();
        init.rootUri = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.toUri();
        var capabilities = new JsonObject();
        var workspace = new JsonObject();
        var inlayHint = new JsonObject();
        inlayHint.addProperty("refreshSupport", true);
        workspace.add("inlayHint", inlayHint);
        capabilities.add("workspace", workspace);
        init.capabilities = capabilities;
        server.initialize(init);
        server.initialized();

        try {
            var file = FindResource.path("org/javacs/example/HelloWorld.java");
            var text = FileStore.contents(file);
            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = text;
            server.didOpenTextDocument(open);

            Assert.assertEquals("didOpen should not request immediate inlay hint refresh", 0, client.refreshCount.get());
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void didOpenRefreshesInlayHintsAfterInitialIndexInstall() throws Exception {
        FileStore.reset();
        var client = new RecordingInlayHintClient();
        var server = new JavaLanguageServer(client);
        var init = new InitializeParams();
        init.rootUri = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.toUri();
        var capabilities = new JsonObject();
        var workspace = new JsonObject();
        var inlayHint = new JsonObject();
        inlayHint.addProperty("refreshSupport", true);
        workspace.add("inlayHint", inlayHint);
        capabilities.add("workspace", workspace);
        init.capabilities = capabilities;
        server.initialize(init);
        server.initialized();

        try {
            var before = completionIndexVersion(server);
            var file = FindResource.path("org/javacs/example/HelloWorld.java");
            var text = FileStore.contents(file);
            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = text;
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected completion index to advance after open",
                    awaitCompletionIndexAdvance(server, before, 10, TimeUnit.SECONDS));
            Assert.assertTrue(
                    "expected one inlay hint refresh after index install",
                    client.awaitRefreshCountAtLeast(1, 10, TimeUnit.SECONDS));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void didOpenDoesNotRefreshInlayHintsWithoutClientSupport() throws Exception {
        FileStore.reset();
        var client = new RecordingInlayHintClient();
        var server = new JavaLanguageServer(client);
        var init = new InitializeParams();
        init.rootUri = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.toUri();
        server.initialize(init);
        server.initialized();

        try {
            var file = FindResource.path("org/javacs/example/HelloWorld.java");
            var text = FileStore.contents(file);
            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = text;
            server.didOpenTextDocument(open);

            Assert.assertEquals("didOpen should not request inlay hint refresh", 0, client.refreshCount.get());
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void didChangeDiagnosticsAreCoalescedWithLongerDebounce() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        Thread.sleep(900);

        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            for (int i = 0; i < 5; i++) {
                var change = new DidChangeTextDocumentParams();
                change.textDocument.uri = file.toUri();
                change.textDocument.version = 2 + i;
                var delta = new TextDocumentContentChangeEvent();
                delta.text = text + "\n// debounce-change-" + i;
                change.contentChanges.add(delta);
                server.didChangeTextDocument(change);
                Thread.sleep(35);
            }

            Thread.sleep(1200);
            Assert.assertEquals(
                    "rapid didChange burst should compile diagnostics exactly once after debounce",
                    1,
                    capture.countContaining("diagnostics_compile trigger=async:didChange"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void didSaveCancelsPendingDidChangeDiagnostics() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        Thread.sleep(900);

        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = file.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = text + "\n// pending-diagnostics-change";
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            Thread.sleep(80);
            var save = new DidSaveTextDocumentParams();
            save.textDocument = new TextDocumentIdentifier(file.toUri());
            server.didSaveTextDocument(save);

            Thread.sleep(900);
            Assert.assertEquals(
                    "save should cancel pending async didChange diagnostics compile",
                    0,
                    capture.countContaining("[perf] diagnostics_compile trigger=async:didChange"));
            Assert.assertEquals(
                    "save should compile diagnostics immediately once",
                    1,
                    capture.countContaining("[perf] diagnostics_compile trigger=didSave"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void watchedCreatedForActiveJavaFileSkipsDuplicateWork() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        Thread.sleep(700);

        var logger = Logger.getLogger("main");
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var created = new FileEvent();
            created.uri = file.toUri();
            created.type = FileChangeType.Created;
            var watched = new DidChangeWatchedFilesParams();
            watched.changes = List.of(created);
            server.didChangeWatchedFiles(watched);

            Thread.sleep(250);
            Assert.assertEquals(
                    "active-doc watched create should not trigger full-project index refresh",
                    0,
                    capture.countContaining(
                            "[perf] completion_index_debounce trigger=didChangeWatchedFiles:javaCreated "));
            Assert.assertEquals(
                    "active-doc watched create should not trigger diagnostics debounce",
                    0,
                    capture.countContaining(
                            "[perf] diagnostics_debounce trigger=didChangeWatchedFiles"));
            Assert.assertTrue(
                    "expected skip log for active-doc watched create",
                    capture.lastLineContaining(
                                    "[perf] watched_java_change_skip reason=active_document event=created")
                            != null);
        } finally {
            logger.removeHandler(capture);
        }
    }

    private long completionIndexVersion(JavaLanguageServer server) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("completionIndexVersion");
        field.setAccessible(true);
        return ((AtomicLong) field.get(server)).get();
    }

    private void invokeCompileAndPublish(
            JavaLanguageServer server, List<Path> files, JavaCompilerService compiler, String trigger)
            throws Exception {
        var compileAndPublish =
                JavaLanguageServer.class.getDeclaredMethod(
                        "compileAndPublish",
                        java.util.Collection.class,
                        JavaCompilerService.class,
                        String.class,
                        long.class,
                        boolean.class);
        compileAndPublish.setAccessible(true);
        compileAndPublish.invoke(server, files, compiler, trigger, -1L, false);
    }

    private void setLombokVerifyBypass(JavaLanguageServer server) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("lombokVerifiedForCurrentCompiler");
        field.setAccessible(true);
        field.setBoolean(server, true);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
        }
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

    private static boolean containsAny(String text, String... needles) {
        if (text == null) {
            return false;
        }
        for (var needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static class RecordingDiagnosticsClient implements LanguageClient {
        private final Map<java.net.URI, List<Diagnostic>> diagnosticsByUri = new ConcurrentHashMap<>();
        private final AtomicInteger diagnosticsPublishCount = new AtomicInteger();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnosticsByUri.put(params.uri, List.copyOf(params.diagnostics));
            diagnosticsPublishCount.incrementAndGet();
        }

        @Override
        public void showMessage(ShowMessageParams params) {}

        @Override
        public void registerCapability(String method, com.google.gson.JsonElement options) {}

        @Override
        public void refreshInlayHints() {}

        @Override
        public void customNotification(String method, com.google.gson.JsonElement params) {}

        boolean awaitErrorMatching(
                java.net.URI uri,
                Predicate<Diagnostic> predicate,
                long timeout,
                TimeUnit unit)
                throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (hasErrorMatching(uri, predicate)) {
                    return true;
                }
                Thread.sleep(25);
            }
            return false;
        }

        boolean awaitNoErrorMatching(
                java.net.URI uri,
                Predicate<Diagnostic> predicate,
                long timeout,
                TimeUnit unit)
                throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (diagnosticsPublishCount.get() > 0 && !hasErrorMatching(uri, predicate)) {
                    return true;
                }
                Thread.sleep(25);
            }
            return false;
        }

        private boolean hasErrorMatching(java.net.URI uri, Predicate<Diagnostic> predicate) {
            var diagnostics = diagnosticsByUri.get(uri);
            if (diagnostics == null) {
                return false;
            }
            return diagnostics.stream()
                    .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                    .anyMatch(predicate);
        }

        boolean awaitDiagnosticsCount(java.net.URI uri, int count, long timeout, TimeUnit unit)
                throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                var diagnostics = diagnosticsByUri.get(uri);
                if (diagnostics != null && diagnostics.size() == count) {
                    return true;
                }
                Thread.sleep(25);
            }
            return false;
        }
    }

    private static class RecordingInlayHintClient implements LanguageClient {
        private final CountDownLatch refreshRequested = new CountDownLatch(1);
        private final AtomicInteger refreshCount = new AtomicInteger();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {}

        @Override
        public void showMessage(ShowMessageParams params) {}

        @Override
        public void registerCapability(String method, com.google.gson.JsonElement options) {}

        @Override
        public void refreshInlayHints() {
            refreshCount.incrementAndGet();
            refreshRequested.countDown();
        }

        @Override
        public void customNotification(String method, com.google.gson.JsonElement params) {}

        private boolean awaitRefresh(long timeout, TimeUnit unit) throws InterruptedException {
            return refreshRequested.await(timeout, unit) && refreshCount.get() > 0;
        }

        private boolean awaitRefreshCountAtLeast(int target, long timeout, TimeUnit unit) throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (refreshCount.get() >= target) {
                    return true;
                }
                Thread.sleep(25);
            }
            return refreshCount.get() >= target;
        }
    }

    private static class TestLogCapture extends Handler {
        private final java.util.concurrent.CopyOnWriteArrayList<String> lines =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getLevel().intValue() < Level.INFO.intValue()) {
                return;
            }
            lines.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String lastLineContaining(String needle) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                var line = lines.get(i);
                if (line != null && line.contains(needle)) {
                    return line;
                }
            }
            return null;
        }

        int countContaining(String needle) {
            var count = 0;
            for (var line : lines) {
                if (line != null && line.contains(needle)) {
                    count++;
                }
            }
            return count;
        }

        int countMatching(String pattern) {
            var count = 0;
            for (var line : lines) {
                if (line != null && line.matches(pattern)) {
                    count++;
                }
            }
            return count;
        }
    }

    private static class ConcurrencyTrackingCompiler extends JavaCompilerService {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();

        ConcurrencyTrackingCompiler() {
            super(Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), true);
        }

        @Override
        public CompileTask compile(Path... files) {
            var active = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(120);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new CompileTask(null, List.of(), List.of(), Map.of(), () -> {});
        }

        int maxConcurrentCompiles() {
            return maxInFlight.get();
        }
    }
}
