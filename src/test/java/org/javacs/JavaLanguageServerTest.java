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
import java.util.Set;
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
import org.javacs.completion.CompositeTypeIndex;
import org.javacs.completion.ExternalBinaryTypeIndex;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.DidChangeConfigurationParams;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidChangeWatchedFilesParams;
import org.javacs.lsp.DidCloseTextDocumentParams;
import org.javacs.lsp.DidSaveTextDocumentParams;
import org.javacs.lsp.Diagnostic;
import org.javacs.lsp.DiagnosticSeverity;
import org.javacs.lsp.FileChangeType;
import org.javacs.lsp.FileEvent;
import org.javacs.lsp.InitializeParams;
import org.javacs.lsp.InlayHintParams;
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.Position;
import org.javacs.lsp.PublishDiagnosticsParams;
import org.javacs.lsp.ReferenceParams;
import org.javacs.lsp.Range;
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
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer();
            var file = FindResource.path("org/javacs/example/LombokCrossTypeCompletion.java");

            Assert.assertEquals("expected empty completion index before bootstrap", 0L, completionIndexVersion(server));

            var position =
                    new TextDocumentPositionParams(
                            new TextDocumentIdentifier(file.toUri()), new Position(5, 14));
            Optional<CompletionList> initialCompletion = server.completion(position);

            Assert.assertTrue("expected completion result while bootstrap is pending", initialCompletion.isPresent());
            Assert.assertTrue(
                    "completion should schedule async workspace bootstrap when the index is empty",
                    capture.countContaining("completion_index_debounce trigger=completionBootstrap") > 0);
            Assert.assertEquals(
                    "completion should not do synchronous index rebuilds on cold start",
                    0,
                    capture.countContaining("completion_index_refresh_sync trigger=completionBootstrap"));
            Assert.assertEquals(
                    "completion should not perform a full workspace compile directly",
                    0,
                    capture.countContaining("compile request=completion mode=full"));

            Assert.assertTrue(
                    "completion bootstrap should initialize the completion index asynchronously",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            Assert.assertTrue(
                    "completion bootstrap should log workspace bootstrap start",
                    capture.countContaining("workspace bootstrap started trigger=completionBootstrap") > 0);
            Assert.assertTrue(
                    "completion bootstrap should log workspace index install",
                    capture.countContaining("workspace index installed trigger=completionBootstrap") > 0);

            Optional<CompletionList> completion = server.completion(position);
            Assert.assertTrue("expected completion result after bootstrap", completion.isPresent());
            var labels =
                    completion.get().items.stream()
                            .map(item -> item.label)
                            .collect(java.util.stream.Collectors.toSet());
            Assert.assertTrue("expected getter completion after bootstrap", labels.contains("getName"));
            Assert.assertTrue("expected setter completion after bootstrap", labels.contains("setName"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void definitionBootstrapsInitialCompletionIndexWhenStillEmpty() throws Exception {
        FileStore.reset();
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/LombokFieldReferences.java");

        Assert.assertEquals("expected empty completion index before definition bootstrap", 0L, completionIndexVersion(server));

        var result =
                server.gotoDefinition(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()),
                                new Position(9, 12)));

        Assert.assertTrue("expected definition result after bootstrap", result.isPresent());
        Assert.assertTrue("expected definition bootstrap to initialize completion index", completionIndexVersion(server) > 0);
    }

    @Test
    public void referencesBootstrapInitialCompletionIndexWhenStillEmpty() throws Exception {
        FileStore.reset();
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/GotoImplementation.java");

        Assert.assertEquals("expected empty completion index before references bootstrap", 0L, completionIndexVersion(server));

        var params = new ReferenceParams();
        params.textDocument = new TextDocumentIdentifier(file.toUri());
        params.position = new Position(8, 20);
        var result = server.findReferences(params);

        Assert.assertTrue("expected references result after bootstrap", result.isPresent());
        Assert.assertTrue("expected references bootstrap to initialize completion index", completionIndexVersion(server) > 0);
    }

    @Test
    public void didOpenSchedulesWorkspaceCompletionBootstrapInsteadOfSharedDiagnosticsIndex() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
                    "didOpen should initialize the completion index from workspace bootstrap",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            Assert.assertTrue(
                    "didOpen should log workspace bootstrap start",
                    capture.countContaining("workspace bootstrap started trigger=didOpenActiveBootstrap") > 0);
            Assert.assertTrue(
                    "didOpen should log workspace index install",
                    capture.countContaining("workspace index installed trigger=didOpenActiveBootstrap") > 0);

            Assert.assertTrue(
                    "didOpen should schedule a dedicated workspace completion bootstrap",
                    capture.countContaining("completion_index_debounce trigger=didOpenActiveBootstrap") > 0);
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
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void lintSchedulesWorkspaceBootstrapInsteadOfInstallingInitialIndex() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
            FileStore.open(open);

            cancelPendingCompletionIndex(server, "test");
            setCompletionIndexVersion(server, 0);

            server.lint(List.of(file));

            Assert.assertTrue(
                    "lint should schedule a dedicated workspace bootstrap when the initial index is empty",
                    capture.countContaining("completion_index_debounce trigger=lintBootstrap") > 0);
            Assert.assertEquals(
                    "lint should not synchronously install the initial completion index",
                    0,
                    capture.countContaining("completion_index_refresh_sync trigger=lintBootstrap"));
            Assert.assertTrue(
                    "lint bootstrap should initialize the completion index asynchronously",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            Assert.assertTrue(
                    "lint bootstrap should log workspace index install",
                    capture.countContaining("workspace index installed trigger=lintBootstrap") > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
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
    public void didOpenWorkspaceBootstrapIncludesSplitInheritedDotCompletion() throws Exception {
        FileStore.reset();
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/SplitInheritedFooDot.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        Assert.assertTrue(
                "didOpen should initialize the completion index",
                awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

        CompileBatch.resetPerfCounters();
        var completion =
                server.completion(
                                new TextDocumentPositionParams(
                                        new TextDocumentIdentifier(file.toUri()),
                                        new Position(4, 25)))
                        .orElseThrow();
        var labels =
                completion.items.stream()
                        .map(item -> item.label)
                        .collect(java.util.stream.Collectors.toSet());
        Assert.assertTrue(
                "split-file inherited dot completion should work without opening the superclass first",
                labels.contains("perform"));
        Assert.assertEquals(
                "completion should not use full compile after workspace bootstrap",
                0L,
                CompileBatch.perfCounters().fullBatches);
        Assert.assertEquals(
                "completion should not analyze after workspace bootstrap",
                0L,
                CompileBatch.perfCounters().analyzeInvocations);
        Assert.assertEquals(
                "completion should not run annotation processing after workspace bootstrap",
                0L,
                CompileBatch.perfCounters().apEnabledBatches);
    }

    @Test
    public void didChangeKeepsPublishedDiagnosticsUntilDebouncedRefresh() throws Exception {
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
        var publishCountBeforeChange = client.diagnosticsPublishCount();

        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = 2;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = text + "\n// clear-stale";
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);

        Thread.sleep(200);
        Assert.assertEquals(
                "didChange should not clear diagnostics before the debounced refresh runs",
                publishCountBeforeChange,
                client.diagnosticsPublishCount());
        Assert.assertTrue(
                "previous diagnostics should remain visible during the debounce window",
                client.hasErrorMatching(file.toUri(), __ -> true));
    }

    @Test
    public void didSaveCompilesDiagnosticsOnceAndRefreshesIndexSeparately() throws Exception {
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
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
            Assert.assertTrue(
                    "didSave should schedule a dedicated completion-index refresh",
                    capture.countContaining("[perf] completion_index_debounce trigger=didSave") > 0);
            Assert.assertEquals(
                    "pending didChange diagnostics should be canceled by save",
                    0,
                    capture.countContaining("[perf] diagnostics_compile trigger=async:didChange"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
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
    public void didChangeSchedulesDirtyOpenFilesInDiagnosticsBatch() throws Exception {
        var workspace = Files.createTempDirectory("jls-diagnostics-open-batch");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
                    "didChange should include other dirty open files in the diagnostics batch",
                    didChangeDebounce.contains("files=2"));
            Assert.assertEquals(
                    "expected one dirty-open file to be included alongside the requested file",
                    1,
                    capture.countContaining(
                            "[perf] diagnostics_batch trigger=didChange requested=1 dirty_open=1 files=2"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void didOpenConsumerIncludesUnsavedActiveEnumDependencyInDiagnosticsCompile() throws Exception {
        var workspace = Files.createTempDirectory("jls-enum-related-open");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var models = workspace.resolve("src/com/example/demo/models");
            var service = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(models);
            Files.createDirectories(service);

            var enumFile = models.resolve("MyEnum.java");
            var serviceFile = service.resolve("ServiceTwo.java");
            Files.writeString(
                    enumFile,
                    "package com.example.demo.models;\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST;\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.models.MyEnum;\n"
                            + "class ServiceTwo {\n"
                            + "  String test() {\n"
                            + "    return MyEnum.FIRST.getType();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var enumOpen = new DidOpenTextDocumentParams();
            enumOpen.textDocument.uri = enumFile.toUri();
            enumOpen.textDocument.version = 1;
            enumOpen.textDocument.languageId = "java";
            enumOpen.textDocument.text =
                    "package com.example.demo.models;\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST;\n"
                            + "  public String getType() {\n"
                            + "    return name();\n"
                            + "  }\n"
                            + "}\n";
            server.didOpenTextDocument(enumOpen);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "consumer diagnostics should include the active unsaved enum dependency on open",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getType()"),
                            10,
                            TimeUnit.SECONDS));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void didOpenPublishesDiagnosticsOnlyForRequestedFileUri() throws Exception {
        var workspace = Files.createTempDirectory("jls-diagnostics-requested-uri");
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);

            var fooFile = pkg.resolve("Foo.java");
            var serviceFile = pkg.resolve("Service.java");
            Files.writeString(
                    fooFile,
                    "package p;\n"
                            + "class Foo {\n"
                            + "  private String unused = \"x\";\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package p;\n"
                            + "class Service {\n"
                            + "  Foo test() {\n"
                            + "    return new Foo();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = serviceFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected diagnostics publication for the requested service file",
                    client.awaitDiagnosticsCount(serviceFile.toUri(), 0, 10, TimeUnit.SECONDS));
            Thread.sleep(300);
            Assert.assertEquals(
                    "didOpen should only publish diagnostics for the requested file URI",
                    Set.of(serviceFile.toUri()),
                    client.publishedUris());
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void didOpenConsumerIncludesReferencedLombokEnumDependencyWithoutOpeningEnum() throws Exception {
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        var client = new RecordingDiagnosticsClient();
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);
        var serviceFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproEnumService.java");
        try {
            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "consumer diagnostics should include the referenced Lombok enum dependency on first open",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getType()"),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "didOpen should expand the consumer compile with the referenced Lombok enum",
                    capture.countContaining("[perf] lombok_ap_sources requested=1 expanded=2") > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void openingDependencyAfterConsumerDoesNotChangeConsumerDiagnosticsOutcome() throws Exception {
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

        var serviceOpen = new DidOpenTextDocumentParams();
        serviceOpen.textDocument.uri = serviceFile.toUri();
        serviceOpen.textDocument.version = 1;
        serviceOpen.textDocument.languageId = "java";
        serviceOpen.textDocument.text = Files.readString(serviceFile);
        server.didOpenTextDocument(serviceOpen);

        Assert.assertTrue(
                "expected clean consumer diagnostics before dependency is opened",
                client.awaitNoErrorMatching(
                        serviceFile.toUri(),
                        d -> containsAny(d.message, "getMsref()", "setMsref("),
                        10,
                        TimeUnit.SECONDS));

        var modelOpen = new DidOpenTextDocumentParams();
        modelOpen.textDocument.uri = modelFile.toUri();
        modelOpen.textDocument.version = 1;
        modelOpen.textDocument.languageId = "java";
        modelOpen.textDocument.text = Files.readString(modelFile);
        server.didOpenTextDocument(modelOpen);

        Assert.assertTrue(
                "opening the dependency should not change the consumer diagnostics outcome",
                client.awaitNoErrorMatching(
                        serviceFile.toUri(),
                        d -> containsAny(d.message, "getMsref()", "setMsref("),
                        10,
                        TimeUnit.SECONDS));
    }

    @Test
    public void completionOnDependencyDoesNotWarmDiagnosticsParseCache() throws Exception {
        var client = new RecordingDiagnosticsClient();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);

        var consumerFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        var dependencyFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/model/ReproTxn.java");
        try {
            server.completion(
                    new TextDocumentPositionParams(
                            new TextDocumentIdentifier(dependencyFile.toUri()), new Position(4, 12)));

            var consumerOpen = new DidOpenTextDocumentParams();
            consumerOpen.textDocument.uri = consumerFile.toUri();
            consumerOpen.textDocument.version = 1;
            consumerOpen.textDocument.languageId = "java";
            consumerOpen.textDocument.text = Files.readString(consumerFile);
            server.didOpenTextDocument(consumerOpen);

            Assert.assertTrue(
                    "expected clean consumer diagnostics after dependency completion warmup",
                    client.awaitNoErrorMatching(
                            consumerFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "expected interactive completion request to parse the dependency first",
                    capture.countContaining("[perf] parse_cache_store file=ReproTxn.java") > 0);
            Assert.assertTrue(
                    "diagnostics compiler should parse its own dependency state instead of reusing interactive parse warmup",
                    capture.countContaining(
                                    "[perf] diagnostics_cache_store compiler=diagnostics file=ReproTxn.java")
                            > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void didOpenConsumerLogsLombokDiagnosticsExpansionDetails() throws Exception {
        var client = new RecordingDiagnosticsClient();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);

        var serviceFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        try {
            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "expected clean consumer diagnostics for the Lombok repro service",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));

            var sourceFilesLog = capture.lastLineContaining("[perf] lombok_ap_source_files compiler=diagnostics");
            Assert.assertNotNull("expected a compact Lombok source expansion log", sourceFilesLog);
            Assert.assertTrue(
                    "expected the diagnostics expansion log to include the requested consumer file",
                    sourceFilesLog.contains("ReproService.java"));
            Assert.assertTrue(
                    "expected the diagnostics expansion log to include the referenced Lombok model",
                    sourceFilesLog.contains("ReproTxn.java"));

            var verifyMembersLog =
                    capture.lastLineContaining(
                            "[perf] lombok_verify_members phase=diagnostics class=org.javacs.repro.model.ReproTxn");
            Assert.assertNotNull("expected Lombok generated member visibility details", verifyMembersLog);
            Assert.assertTrue(
                    "expected generated getter names to be listed for investigation",
                    verifyMembersLog.contains("getMsref"));
            Assert.assertTrue(
                    "expected generated setter names to be listed for investigation",
                    verifyMembersLog.contains("setMsref"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void didOpenConsumerIncludesActiveUnsavedTransitiveLombokDependency() throws Exception {
        var workspace = Files.createTempDirectory("jls-lombok-transitive-active");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var model = workspace.resolve("src/p/model");
            var service = workspace.resolve("src/p/service");
            Files.createDirectories(model);
            Files.createDirectories(service);

            var fooFile = model.resolve("Foo.java");
            var barFile = model.resolve("Bar.java");
            var serviceFile = service.resolve("Service.java");
            Files.writeString(
                    fooFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Foo {\n"
                            + "  private Bar bar;\n"
                            + "}\n");
            Files.writeString(
                    barFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Bar {\n"
                            + "  private String biz;\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package p.service;\n"
                            + "import p.model.Foo;\n"
                            + "public class Service {\n"
                            + "  void run(Foo foo) {\n"
                            + "    var bar = foo.getBar();\n"
                            + "    var biz = bar.getBiz();\n"
                            + "    biz.length();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var settings = new JsonObject();
            var java = new JsonObject();
            var classPath = new com.google.gson.JsonArray();
            classPath.add(Paths.get("lib/lombok-1.18.30.jar").toAbsolutePath().toString());
            java.add("classPath", classPath);
            settings.add("java", java);
            var change = new DidChangeConfigurationParams();
            change.settings = settings;
            server.didChangeConfiguration(change);

            var fooOpen = new DidOpenTextDocumentParams();
            fooOpen.textDocument.uri = fooFile.toUri();
            fooOpen.textDocument.version = 1;
            fooOpen.textDocument.languageId = "java";
            fooOpen.textDocument.text =
                    Files.readString(fooFile)
                            + "\n// keep Foo active and unsaved so diagnostics must use in-memory dependency content\n";
            server.didOpenTextDocument(fooOpen);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "consumer diagnostics should include active unsaved Lombok dependencies transitively",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getBar()", "getBiz()"),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "didOpen should expand diagnostics compile with the transitive Lombok dependency chain",
                    capture.countContaining("[perf] lombok_ap_sources requested=1 expanded=3") > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void repeatedDidOpenAcrossDifferentLombokConsumersKeepsDiagnosticsResolved() throws Exception {
        var client = new RecordingDiagnosticsClient();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);

        var firstFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        var secondFile = FindResource.path("org/javacs/example/LombokNestedDiagnostics.java");
        try {
            var firstOpen = new DidOpenTextDocumentParams();
            firstOpen.textDocument.uri = firstFile.toUri();
            firstOpen.textDocument.version = 1;
            firstOpen.textDocument.languageId = "java";
            firstOpen.textDocument.text = Files.readString(firstFile);
            server.didOpenTextDocument(firstOpen);

            Assert.assertTrue(
                    "first Lombok consumer diagnostics should stay resolved",
                    client.awaitNoErrorMatching(
                            firstFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));

            var secondOpen = new DidOpenTextDocumentParams();
            secondOpen.textDocument.uri = secondFile.toUri();
            secondOpen.textDocument.version = 1;
            secondOpen.textDocument.languageId = "java";
            secondOpen.textDocument.text = Files.readString(secondFile);
            server.didOpenTextDocument(secondOpen);

            Assert.assertTrue(
                    "second Lombok consumer diagnostics should stay resolved after reusing diagnostics compiler",
                    client.awaitNoErrorMatching(
                            secondFile.toUri(),
                            d -> containsAny(d.message, "getBar()", "getName()"),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertEquals(
                    "repeated Lombok diagnostics should not disable annotation processing",
                    0,
                    capture.countContaining("[perf] lombok_ap disabled=true"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void enterpriseStyleLombokDiagnosticsResolveGeneratedGetterChain() throws Exception {
        var workspace = Files.createTempDirectory("jls-enterprise-lombok-getter-chain");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var model = workspace.resolve("src/main/java/p/model");
            var service = workspace.resolve("src/main/java/p/service");
            Files.createDirectories(model);
            Files.createDirectories(service);

            var fooFile = model.resolve("Foo.java");
            var barFile = model.resolve("Bar.java");
            var bizFile = model.resolve("Biz.java");
            var consumerFile = service.resolve("FooService.java");
            Files.writeString(
                    fooFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Foo {\n"
                            + "  private Bar bar;\n"
                            + "}\n");
            Files.writeString(
                    barFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Bar {\n"
                            + "  private Biz biz = new Biz();\n"
                            + "}\n");
            Files.writeString(
                    bizFile,
                    "package p.model;\n"
                            + "public class Biz {\n"
                            + "  public String value() {\n"
                            + "    return \"ok\";\n"
                            + "  }\n"
                            + "}\n");
            Files.writeString(
                    consumerFile,
                    "package p.service;\n"
                            + "import p.model.Foo;\n"
                            + "public class FooService {\n"
                            + "  String use(Foo foo) {\n"
                            + "    var bar = foo.getBar();\n"
                            + "    var biz = bar.getBiz();\n"
                            + "    return biz.value();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);
            configureLombokClasspath(server);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = consumerFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(consumerFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "generated getter chain should not produce diagnostics in consumer",
                    client.awaitNoErrorMatching(
                            consumerFile.toUri(),
                            d -> containsAny(d.message, "getBar()", "getBiz()", "value()"),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "expected an async diagnostics compile for the consumer",
                    capture.countContaining("[perf] diagnostics_compile trigger=async:") > 0);
            Assert.assertTrue(
                    "expected lombok source expansion for consumer diagnostics",
                    capture.countContaining("[perf] lombok_ap_sources requested=1 expanded=") > 0);
            Assert.assertTrue(
                    "Lombok diagnostics should use a fresh compile path for the consumer batch",
                    capture.countContaining("[perf] diagnostics_lombok_mode fresh=true requested=1") > 0);
            var verifyBar =
                    capture.lastLineContaining(
                            "[perf] lombok_verify_members phase=diagnostics class=p.model.Bar");
            Assert.assertNotNull("expected Lombok verification for Bar during diagnostics", verifyBar);
            Assert.assertTrue(
                    "Bar diagnostics verification should report visible generated members",
                    !verifyBar.contains("visible_generated=-"));
            Assert.assertEquals(
                    "diagnostics repro should keep annotation processing enabled",
                    0,
                    capture.countContaining("[perf] lombok_ap disabled=true"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void enterpriseStyleLombokDiagnosticsResolveFieldSetterVariant() throws Exception {
        var workspace = Files.createTempDirectory("jls-enterprise-lombok-setter-variant");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var model = workspace.resolve("src/main/java/p/model");
            var service = workspace.resolve("src/main/java/p/service");
            Files.createDirectories(model);
            Files.createDirectories(service);

            var fooFile = model.resolve("Foo.java");
            var barFile = model.resolve("Bar.java");
            var bizFile = model.resolve("Biz.java");
            var consumerFile = service.resolve("FooService.java");
            Files.writeString(
                    fooFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Foo {\n"
                            + "  @lombok.Setter private Bar bar;\n"
                            + "}\n");
            Files.writeString(
                    barFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Bar {\n"
                            + "  private Biz biz = new Biz();\n"
                            + "}\n");
            Files.writeString(
                    bizFile,
                    "package p.model;\n"
                            + "public class Biz {\n"
                            + "  public String value() {\n"
                            + "    return \"ok\";\n"
                            + "  }\n"
                            + "}\n");
            Files.writeString(
                    consumerFile,
                    "package p.service;\n"
                            + "import p.model.Foo;\n"
                            + "public class FooService {\n"
                            + "  String use(Foo foo) {\n"
                            + "    var bar = foo.getBar();\n"
                            + "    var biz = bar.getBiz();\n"
                            + "    return biz.value();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);
            configureLombokClasspath(server);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = consumerFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(consumerFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "field-level setter variant should still resolve generated getter chain",
                    client.awaitNoErrorMatching(
                            consumerFile.toUri(),
                            d -> containsAny(d.message, "getBar()", "getBiz()", "value()"),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "expected an async diagnostics compile for the setter variant",
                    capture.countContaining("[perf] diagnostics_compile trigger=async:") > 0);
            Assert.assertTrue(
                    "expected lombok source expansion for setter variant",
                    capture.countContaining("[perf] lombok_ap_sources requested=1 expanded=") > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void enterpriseStyleLombokDiagnosticsResolveSplitPackageGetterChain() throws Exception {
        var workspace = Files.createTempDirectory("jls-enterprise-lombok-split-package");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var model = workspace.resolve("src/main/java/p/model");
            var nested = workspace.resolve("src/main/java/p/nested");
            var service = workspace.resolve("src/main/java/p/service");
            Files.createDirectories(model);
            Files.createDirectories(nested);
            Files.createDirectories(service);

            var fooFile = model.resolve("Foo.java");
            var barFile = nested.resolve("Bar.java");
            var bizFile = nested.resolve("Biz.java");
            var consumerFile = service.resolve("FooService.java");
            Files.writeString(
                    fooFile,
                    "package p.model;\n"
                            + "import p.nested.Bar;\n"
                            + "@lombok.Data\n"
                            + "public class Foo {\n"
                            + "  private Bar bar;\n"
                            + "}\n");
            Files.writeString(
                    barFile,
                    "package p.nested;\n"
                            + "@lombok.Data\n"
                            + "public class Bar {\n"
                            + "  private Biz biz = new Biz();\n"
                            + "}\n");
            Files.writeString(
                    bizFile,
                    "package p.nested;\n"
                            + "public class Biz {\n"
                            + "  public String value() {\n"
                            + "    return \"ok\";\n"
                            + "  }\n"
                            + "}\n");
            Files.writeString(
                    consumerFile,
                    "package p.service;\n"
                            + "import p.model.Foo;\n"
                            + "public class FooService {\n"
                            + "  String use(Foo foo) {\n"
                            + "    var bar = foo.getBar();\n"
                            + "    var biz = bar.getBiz();\n"
                            + "    return biz.value();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);
            configureLombokClasspath(server);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = consumerFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(consumerFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "split-package getter chain should not produce diagnostics in consumer",
                    client.awaitNoErrorMatching(
                            consumerFile.toUri(),
                            d -> containsAny(d.message, "getBar()", "getBiz()", "value()"),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "expected lombok source expansion for split-package consumer diagnostics",
                    capture.countContaining("[perf] lombok_ap_sources requested=1 expanded=") > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionInTestSourceResolvesMainSourceMemberChain() throws Exception {
        var workspace = Files.createTempDirectory("jls-test-source-main-member-chain");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var model = workspace.resolve("src/main/java/p/model");
            var tests = workspace.resolve("src/test/java/p/test");
            Files.createDirectories(model);
            Files.createDirectories(tests);

            Files.writeString(
                    model.resolve("Foo.java"),
                    "package p.model;\n"
                            + "public class Foo {\n"
                            + "  public Bar getBar() {\n"
                            + "    return new Bar();\n"
                            + "  }\n"
                            + "}\n");
            Files.writeString(
                    model.resolve("Bar.java"),
                    "package p.model;\n"
                            + "public class Bar {\n"
                            + "  public String getHello() {\n"
                            + "    return \"hello\";\n"
                            + "  }\n"
                            + "}\n");
            var testFile = tests.resolve("FooIntegrationTest.java");
            Files.writeString(
                    testFile,
                    "package p.test;\n"
                            + "import p.model.Foo;\n"
                            + "class FooIntegrationTest {\n"
                            + "  void test(Foo foo) {\n"
                            + "    foo.getBar().\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = testFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(testFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected completion index bootstrap before test-file completion",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var completion =
                    server.completion(
                                    new TextDocumentPositionParams(
                                            new TextDocumentIdentifier(testFile.toUri()),
                                            new Position(4, 17)))
                            .orElseThrow();
            var labels =
                    completion.items.stream()
                            .map(item -> item.label)
                            .collect(java.util.stream.Collectors.toSet());
            Assert.assertTrue(
                    "test-source completion should resolve workspace member chain from main sources",
                    labels.contains("getHello"));
            Assert.assertEquals(
                    "workspace source types in test completion should not fall through to external binary lookup",
                    0,
                    capture.countContaining("[external-binary] miss type=p.model.Bar"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionInTestSourceResolvesMainSourceLocalVarChain() throws Exception {
        var workspace = Files.createTempDirectory("jls-test-source-local-var-chain");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var model = workspace.resolve("src/main/java/p/model");
            var tests = workspace.resolve("src/test/java/p/test");
            Files.createDirectories(model);
            Files.createDirectories(tests);

            Files.writeString(
                    model.resolve("Foo.java"),
                    "package p.model;\n"
                            + "public class Foo {\n"
                            + "  public Bar getBar() {\n"
                            + "    return new Bar();\n"
                            + "  }\n"
                            + "}\n");
            Files.writeString(
                    model.resolve("Bar.java"),
                    "package p.model;\n"
                            + "public class Bar {\n"
                            + "  public String getHello() {\n"
                            + "    return \"hello\";\n"
                            + "  }\n"
                            + "}\n");
            var testFile = tests.resolve("FooIntegrationTest.java");
            Files.writeString(
                    testFile,
                    "package p.test;\n"
                            + "import p.model.Foo;\n"
                            + "class FooIntegrationTest {\n"
                            + "  void test(Foo foo) {\n"
                            + "    var bar = foo.getBar();\n"
                            + "    bar.\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = testFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(testFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected completion index bootstrap before local-var completion",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var completion =
                    server.completion(
                                    new TextDocumentPositionParams(
                                            new TextDocumentIdentifier(testFile.toUri()),
                                            new Position(5, 8)))
                            .orElseThrow();
            var labels =
                    completion.items.stream()
                            .map(item -> item.label)
                            .collect(java.util.stream.Collectors.toSet());
            Assert.assertTrue(
                    "test-source completion should resolve local var receiver type from main sources",
                    labels.contains("getHello"));
            Assert.assertEquals(
                    "local var receiver in test completion should stay on workspace resolution path",
                    0,
                    capture.countContaining("[external-binary] miss type=p.model.Bar"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionInTestSourceResolvesLombokNestedMemberChain() throws Exception {
        var workspace = Files.createTempDirectory("jls-test-source-lombok-member-chain");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var model = workspace.resolve("src/main/java/p/model");
            var tests = workspace.resolve("src/test/java/p/test");
            Files.createDirectories(model);
            Files.createDirectories(tests);

            Files.writeString(
                    model.resolve("Foo.java"),
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Foo {\n"
                            + "  private Bar bar = new Bar();\n"
                            + "}\n");
            Files.writeString(
                    model.resolve("Bar.java"),
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Bar {\n"
                            + "  private String hello = \"hello\";\n"
                            + "}\n");
            var testFile = tests.resolve("FooIntegrationTest.java");
            Files.writeString(
                    testFile,
                    "package p.test;\n"
                            + "import p.model.Foo;\n"
                            + "class FooIntegrationTest {\n"
                            + "  void test(Foo foo) {\n"
                            + "    foo.getBar().\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            configureLombokClasspath(server);
            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = testFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(testFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected completion index bootstrap before Lombok test-file completion",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var completion =
                    server.completion(
                                    new TextDocumentPositionParams(
                                            new TextDocumentIdentifier(testFile.toUri()),
                                            new Position(4, 17)))
                            .orElseThrow();
            var labels =
                    completion.items.stream()
                            .map(item -> item.label)
                            .collect(java.util.stream.Collectors.toSet());
            Assert.assertTrue(
                    "test-source completion should resolve Lombok-backed workspace member chain",
                    labels.contains("getHello"));
            Assert.assertEquals(
                    "Lombok workspace source types in test completion should not fall through to external binary lookup",
                    0,
                    capture.countContaining("[external-binary] miss type=p.model.Bar"));
            Assert.assertEquals(
                    "Lombok workspace source types in test completion should not be mis-resolved to the test package",
                    0,
                    capture.countContaining("[external-binary] miss type=p.test.Bar"));
            Assert.assertEquals(
                    "Lombok workspace source types in test completion should not fall back to java.lang",
                    0,
                    capture.countContaining("[external-binary] miss type=java.lang.Bar"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void didChangeEnumRefreshesActiveConsumerDiagnostics() throws Exception {
        var workspace = Files.createTempDirectory("jls-enum-related-change");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var models = workspace.resolve("src/com/example/demo/models");
            var service = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(models);
            Files.createDirectories(service);

            var enumFile = models.resolve("MyEnum.java");
            var serviceFile = service.resolve("ServiceTwo.java");
            Files.writeString(
                    enumFile,
                    "package com.example.demo.models;\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST;\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.models.MyEnum;\n"
                            + "class ServiceTwo {\n"
                            + "  String test() {\n"
                            + "    return MyEnum.FIRST.getType();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var enumOpen = new DidOpenTextDocumentParams();
            enumOpen.textDocument.uri = enumFile.toUri();
            enumOpen.textDocument.version = 1;
            enumOpen.textDocument.languageId = "java";
            enumOpen.textDocument.text = Files.readString(enumFile);
            server.didOpenTextDocument(enumOpen);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "baseline should show the missing enum member error before the unsaved enum change",
                    client.awaitErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getType()"),
                            10,
                            TimeUnit.SECONDS));

            var enumChange = new DidChangeTextDocumentParams();
            enumChange.textDocument.uri = enumFile.toUri();
            enumChange.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text =
                    "package com.example.demo.models;\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST;\n"
                            + "  public String getType() {\n"
                            + "    return name();\n"
                            + "  }\n"
                            + "}\n";
            enumChange.contentChanges.add(delta);
            server.didChangeTextDocument(enumChange);

            Assert.assertTrue(
                    "changing the enum should refresh the open consumer diagnostics without a reopen",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getType()"),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "enum change should include the dirty open consumer in the next diagnostics batch",
                    capture.countContaining(
                                    "[perf] diagnostics_batch trigger=didChange requested=1 dirty_open=1 files=2")
                            > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void didChangeEnumKeepsClosedConsumerDirtyUntilReopen() throws Exception {
        var workspace = Files.createTempDirectory("jls-enum-related-reopen");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var models = workspace.resolve("src/com/example/demo/models");
            var service = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(models);
            Files.createDirectories(service);

            var enumFile = models.resolve("MyEnum.java");
            var serviceFile = service.resolve("ServiceTwo.java");
            Files.writeString(
                    enumFile,
                    "package com.example.demo.models;\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST;\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.models.MyEnum;\n"
                            + "class ServiceTwo {\n"
                            + "  String test() {\n"
                            + "    return MyEnum.FIRST.getType();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var enumOpen = new DidOpenTextDocumentParams();
            enumOpen.textDocument.uri = enumFile.toUri();
            enumOpen.textDocument.version = 1;
            enumOpen.textDocument.languageId = "java";
            enumOpen.textDocument.text = Files.readString(enumFile);
            server.didOpenTextDocument(enumOpen);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "baseline should show the missing enum member error before the unsaved enum change",
                    client.awaitErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getType()"),
                            10,
                            TimeUnit.SECONDS));

            var serviceClose = new DidCloseTextDocumentParams();
            serviceClose.textDocument.uri = serviceFile.toUri();
            server.didCloseTextDocument(serviceClose);

            var enumChange = new DidChangeTextDocumentParams();
            enumChange.textDocument.uri = enumFile.toUri();
            enumChange.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text =
                    "package com.example.demo.models;\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST;\n"
                            + "  public String getType() {\n"
                            + "    return name();\n"
                            + "  }\n"
                            + "}\n";
            enumChange.contentChanges.add(delta);
            server.didChangeTextDocument(enumChange);

            Assert.assertTrue(
                    "changing the enum with the consumer closed should keep the diagnostics batch on the requested file only",
                    capture.countContaining(
                                    "[perf] diagnostics_batch trigger=didChange requested=1 dirty_open=0 files=1")
                            > 0);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "reopening the dirty consumer should refresh its diagnostics after the dependency changed",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getType()"),
                            10,
                            TimeUnit.SECONDS));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void lombokConsumerDiagnosticsRemainResolvedBeforeAndAfterModelSave() throws Exception {
        var client = new RecordingDiagnosticsClient();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
            Assert.assertTrue(
                    "Lombok diagnostics should use a fresh compile path before dependency open",
                    capture.countContaining("[perf] diagnostics_lombok_mode fresh=true requested=1 expanded=2") > 0);

            var baselineDiagnostics = diagnosticFingerprints(client.diagnostics(serviceFile.toUri()));

            var modelOpen = new DidOpenTextDocumentParams();
            modelOpen.textDocument.uri = modelFile.toUri();
            modelOpen.textDocument.version = 1;
            modelOpen.textDocument.languageId = "java";
            modelOpen.textDocument.text = Files.readString(modelFile);
            server.didOpenTextDocument(modelOpen);

            Thread.sleep(300);
            Assert.assertEquals(
                    "opening an unchanged Lombok dependency should not change consumer diagnostics",
                    baselineDiagnostics,
                    diagnosticFingerprints(client.diagnostics(serviceFile.toUri())));

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
            Assert.assertEquals(
                    "saving an unchanged Lombok dependency should not change consumer diagnostics",
                    baselineDiagnostics,
                    diagnosticFingerprints(client.diagnostics(serviceFile.toUri())));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void didChangeLombokModelRefreshesOpenConsumerDiagnostics() throws Exception {
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
                    "changing a Lombok model should refresh consumer diagnostics without a reopen",
                    client.awaitErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "lombok model change should include the dirty open consumer in the next diagnostics batch",
                    capture.countContaining(
                                    "[perf] diagnostics_batch trigger=didChange requested=1 dirty_open=1 files=2")
                            > 0);

            var revert = new DidChangeTextDocumentParams();
            revert.textDocument.uri = modelFile.toUri();
            revert.textDocument.version = 3;
            var revertDelta = new TextDocumentContentChangeEvent();
            revertDelta.text = originalModel;
            revert.contentChanges.add(revertDelta);
            server.didChangeTextDocument(revert);

            Assert.assertTrue(
                    "restoring the Lombok model should clear the open consumer diagnostics without a reopen",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> containsAny(d.message, "getMsref()", "setMsref("),
                            10,
                            TimeUnit.SECONDS));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void watchedLombokDependencyChangeRefreshesOpenConsumerDiagnosticsWithoutOpeningDependency()
            throws Exception {
        var workspace = Files.createTempDirectory("jls-watched-lombok-dependency-refresh");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var model = workspace.resolve("src/main/java/p/model");
            var service = workspace.resolve("src/main/java/p/service");
            Files.createDirectories(model);
            Files.createDirectories(service);

            var fooFile = model.resolve("Foo.java");
            var barFile = model.resolve("Bar.java");
            var bizFile = model.resolve("Biz.java");
            var serviceFile = service.resolve("Service.java");
            Files.writeString(
                    fooFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Foo {\n"
                            + "  private Bar bar = new Bar();\n"
                            + "}\n");
            Files.writeString(
                    barFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Bar {\n"
                            + "  private Biz biz = new Biz();\n"
                            + "}\n");
            Files.writeString(
                    bizFile,
                    "package p.model;\n"
                            + "public class Biz {\n"
                            + "  public String value() {\n"
                            + "    return \"ok\";\n"
                            + "  }\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package p.service;\n"
                            + "import p.model.Foo;\n"
                            + "class Service {\n"
                            + "  String run(Foo foo) {\n"
                            + "    return foo.getBar().getBiz().value();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);
            configureLombokClasspath(server);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "consumer diagnostics should resolve against an unopened Lombok dependency on disk",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> true,
                            10,
                            TimeUnit.SECONDS));

            Files.writeString(
                    fooFile,
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Foo {\n"
                            + "  private Bar bar = new Bar();\n"
                            + "  // watched dependency change\n"
                            + "}\n");
            var changed = new FileEvent();
            changed.uri = fooFile.toUri();
            changed.type = FileChangeType.Changed;
            var watched = new DidChangeWatchedFilesParams();
            watched.changes = List.of(changed);
            server.didChangeWatchedFiles(watched);

            Assert.assertTrue(
                    "watched Lombok dependency changes should re-run diagnostics without opening the dependency",
                    client.awaitNoErrorMatching(
                            serviceFile.toUri(),
                            d -> true,
                            10,
                            TimeUnit.SECONDS));
            Assert.assertTrue(
                    "watched Lombok dependency refresh should keep diagnostics on the consumer batch",
                    capture.countContaining(
                                    "[perf] diagnostics_batch trigger=didChangeWatchedFiles requested=1 dirty_open=0 files=1")
                            > 0);
            Assert.assertTrue(
                    "watched Lombok dependency refresh should stay on the fresh diagnostics compile path",
                    capture.countContaining("[perf] diagnostics_lombok_mode fresh=true requested=1") > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void incompleteStatementPublishesOnlyPrimarySyntaxDiagnostic() throws Exception {
        var workspace = Files.createTempDirectory("jls-diagnostics-incomplete-statement");
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);
            var serviceFile = pkg.resolve("Service.java");
            Files.writeString(
                    serviceFile,
                    "package p;\n"
                            + "class Foo {\n"
                            + "  String getBar() {\n"
                            + "    return \"bar\";\n"
                            + "  }\n"
                            + "}\n"
                            + "class Service {\n"
                            + "  void test() {\n"
                            + "    var foo = new Foo();\n"
                            + "    foo\n"
                            + "    foo.getBar();\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = serviceFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected a single primary syntax diagnostic for the incomplete statement",
                    client.awaitDiagnosticsMatching(
                            serviceFile.toUri(),
                            diagnostics ->
                                    diagnostics.size() == 1
                                            && isSyntaxBlockingDiagnostic(diagnostics.get(0))
                                            && !containsAny(
                                                    diagnostics.get(0).code,
                                                    "compiler.err.cant.resolve.location",
                                                    "compiler.err.already.defined"),
                            10,
                            TimeUnit.SECONDS));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void missingSemicolonSuppressesRecoveryDiagnosticsUntilFixed() throws Exception {
        var workspace = Files.createTempDirectory("jls-diagnostics-missing-semicolon");
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);
            var serviceFile = pkg.resolve("Service.java");
            var brokenText =
                    "package p;\n"
                            + "class Foo {\n"
                            + "  String getBar() {\n"
                            + "    return \"bar\";\n"
                            + "  }\n"
                            + "}\n"
                            + "class Service {\n"
                            + "  void test() {\n"
                            + "    var foo = new Foo();\n"
                            + "    foo.getAsd()\n"
                            + "    foo.getBar();\n"
                            + "  }\n"
                            + "}\n";
            Files.writeString(serviceFile, brokenText);

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = serviceFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = brokenText;
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected the missing semicolon to suppress downstream recovery diagnostics",
                    client.awaitDiagnosticsMatching(
                            serviceFile.toUri(),
                            diagnostics -> diagnostics.size() == 1 && isSyntaxBlockingDiagnostic(diagnostics.get(0)),
                            10,
                            TimeUnit.SECONDS));

            var fixedText = brokenText.replace("foo.getAsd()\n", "foo.getAsd();\n");
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = serviceFile.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = fixedText;
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            Assert.assertTrue(
                    "after fixing syntax, semantic diagnostics should return without downstream recovery fallout",
                    client.awaitDiagnosticsMatching(
                            serviceFile.toUri(),
                            diagnostics ->
                                    diagnostics.size() == 1
                                            && diagnostics.get(0).code != null
                                            && diagnostics.get(0).code.equals("compiler.err.cant.resolve.location.args")
                                            && diagnostics.get(0).message.contains("getAsd")
                                            && diagnostics.stream()
                                                    .noneMatch(d -> d.message != null && d.message.contains("getBar")),
                            10,
                            TimeUnit.SECONDS));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void missingDelimiterSuppressesSyntaxCascadeBeyondPrimaryLine() throws Exception {
        var workspace = Files.createTempDirectory("jls-diagnostics-missing-delimiter");
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);
            var serviceFile = pkg.resolve("Service.java");
            Files.writeString(
                    serviceFile,
                    "package p;\n"
                            + "class Service {\n"
                            + "  void test(boolean ok) {\n"
                            + "    if (ok {\n"
                            + "      var value = 1;\n"
                            + "      value++;\n"
                            + "    }\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = serviceFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected missing delimiter diagnostics to stay near the primary broken line",
                    client.awaitDiagnosticsMatching(
                            serviceFile.toUri(),
                            diagnostics ->
                                    diagnostics.size() == 1
                                            && diagnostics.stream().allMatch(JavaLanguageServerTest::isSyntaxBlockingDiagnostic)
                                            && diagnostics.stream()
                                                    .mapToInt(d -> d.range.start.line)
                                                    .max()
                                                    .orElse(-1)
                                                    <= 3,
                            10,
                            TimeUnit.SECONDS));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void missingClosingParenthesisPublishesOnlyPrimarySyntaxDiagnostic() throws Exception {
        var workspace = Files.createTempDirectory("jls-diagnostics-missing-closing-paren");
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);
            var serviceFile = pkg.resolve("Service.java");
            Files.writeString(
                    serviceFile,
                    "package p;\n"
                            + "class Service {\n"
                            + "  boolean ok() {\n"
                            + "    return true;\n"
                            + "  }\n"
                            + "  void test() {\n"
                            + "    if (ok( {\n"
                            + "      var value = 1;\n"
                            + "      value++;\n"
                            + "    }\n"
                            + "  }\n"
                            + "}\n");

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = serviceFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected missing closing parenthesis diagnostics to collapse to a single primary syntax error",
                    client.awaitDiagnosticsMatching(
                            serviceFile.toUri(),
                            diagnostics ->
                                    diagnostics.size() == 1
                                            && isSyntaxBlockingDiagnostic(diagnostics.get(0))
                                            && diagnostics.get(0).range.start.line == 6,
                            10,
                            TimeUnit.SECONDS));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void syntaxInvalidFileSuppressesUnrelatedSemanticErrorsUntilFixed() throws Exception {
        var workspace = Files.createTempDirectory("jls-diagnostics-syntax-only");
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);
            var serviceFile = pkg.resolve("Service.java");
            var brokenText =
                    "package p;\n"
                            + "class Foo {\n"
                            + "  String getBar() {\n"
                            + "    return \"bar\";\n"
                            + "  }\n"
                            + "}\n"
                            + "class Service {\n"
                            + "  void test(boolean ok, Foo foo) {\n"
                            + "    foo.getMissing();\n"
                            + "    if (ok {\n"
                            + "      foo.getBar();\n"
                            + "    }\n"
                            + "  }\n"
                            + "}\n";
            Files.writeString(serviceFile, brokenText);

            var client = new RecordingDiagnosticsClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = serviceFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = brokenText;
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "while syntax is invalid, publish only primary syntax diagnostics",
                    client.awaitDiagnosticsMatching(
                            serviceFile.toUri(),
                            diagnostics ->
                                    !diagnostics.isEmpty()
                                            && diagnostics.stream().allMatch(JavaLanguageServerTest::isSyntaxBlockingDiagnostic)
                                            && diagnostics.stream()
                                                    .mapToInt(d -> d.range.start.line)
                                                    .distinct()
                                                    .count()
                                                    == 1,
                            10,
                            TimeUnit.SECONDS));

            var fixedText = brokenText.replace("if (ok {\n", "if (ok) {\n");
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = serviceFile.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = fixedText;
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            Assert.assertTrue(
                    "after fixing syntax, the underlying semantic error should return",
                    client.awaitDiagnosticsMatching(
                            serviceFile.toUri(),
                            diagnostics ->
                                    diagnostics.size() == 1
                                            && diagnostics.get(0).code != null
                                            && diagnostics.get(0).code.equals("compiler.err.cant.resolve.location.args")
                                            && diagnostics.get(0).message.contains("getMissing"),
                            10,
                            TimeUnit.SECONDS));
        } finally {
            deleteRecursively(workspace);
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
    public void completionUsesPublishedSnapshotWhileIncrementalRefreshBuildsNextVersion() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
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
                    "expected completion index bootstrap before snapshot publication check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var before = completionIndexVersion(server);
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = file.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text =
                    original.replace(
                            "    public String testFields;\n",
                            "    public String testFields;\n    public String refreshedField;\n");
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            var completion =
                    server.completion(
                            new TextDocumentPositionParams(
                                    new TextDocumentIdentifier(file.toUri()), new Position(4, 13)));
            Assert.assertTrue("expected completion result during incremental refresh", completion.isPresent());
            Assert.assertTrue(
                    "completion should keep serving published members while refresh builds the next snapshot",
                    completion.get().items.stream().anyMatch(i -> "testFields".equals(i.label)));
            Assert.assertEquals(
                    "completion should return before the refreshed snapshot is published",
                    before,
                    completionIndexVersion(server));

            var completionFlow = capture.lastLineContaining("[perf] completion_flow file=AutocompleteMember.java");
            Assert.assertNotNull("expected completion flow log for snapshot publication check", completionFlow);
            Assert.assertTrue(
                    "completion should log the currently published snapshot version",
                    completionFlow.contains("index_version=" + before));
            Assert.assertTrue(
                    "completion flow metrics should stay isolated from compiler phases",
                    completionFlow.contains("enter=0 analyze=0 ap=0"));

            Assert.assertTrue(
                    "expected incremental refresh to publish a newer snapshot after completion returns",
                    awaitCompletionIndexAdvance(server, before, 10, TimeUnit.SECONDS));
            var mergeLog = capture.lastLineContaining("[perf] completion_type_index_merge trigger=index:didChange");
            Assert.assertNotNull("expected incremental index merge log", mergeLog);
            Assert.assertTrue(
                    "incremental refresh should log the published base snapshot version",
                    mergeLog.contains("base_version=" + before));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void completionFlowMetricsStayZeroWhileDiagnosticsCompileRuns() throws Exception {
        FileStore.reset();
        var server = LanguageServerFixture.getJavaLanguageServer();
        var completionFile = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var diagnosticsFile = FindResource.path("org/javacs/example/LargeFile.java");

        var openCompletion = new DidOpenTextDocumentParams();
        openCompletion.textDocument.uri = completionFile.toUri();
        openCompletion.textDocument.version = 1;
        openCompletion.textDocument.languageId = "java";
        openCompletion.textDocument.text = FileStore.contents(completionFile);
        server.didOpenTextDocument(openCompletion);

        var diagnosticsOriginal = FileStore.contents(diagnosticsFile);
        var openDiagnostics = new DidOpenTextDocumentParams();
        openDiagnostics.textDocument.uri = diagnosticsFile.toUri();
        openDiagnostics.textDocument.version = 1;
        openDiagnostics.textDocument.languageId = "java";
        openDiagnostics.textDocument.text = diagnosticsOriginal;
        server.didOpenTextDocument(openDiagnostics);

        Assert.assertTrue(
                "expected initial completion index bootstrap before diagnostics isolation check",
                awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = diagnosticsFile.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text =
                    diagnosticsOriginal.replace(
                            "        return version(\"release\");  // mm.nn.oo[-milestone]\n",
                            "        return version(\"release\");  // mm.nn.oo[-milestone] diagnostics-only-change\n");
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            Thread.sleep(300);
            for (int i = 0; i < 3; i++) {
                var completion =
                        server.completion(
                                new TextDocumentPositionParams(
                                        new TextDocumentIdentifier(completionFile.toUri()),
                                        new Position(4, 13)));
                Assert.assertTrue("expected completion result while diagnostics compile runs", completion.isPresent());
            }

            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (capture.countContaining("[perf] diagnostics_compile trigger=async:didChange") == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }

            Assert.assertTrue(
                    "expected async didChange diagnostics compile during completion isolation check",
                    capture.countContaining("[perf] diagnostics_compile trigger=async:didChange") > 0);
            Assert.assertTrue(
                    "expected completion flow logs during diagnostics isolation check",
                    capture.countContaining("[perf] completion_flow file=AutocompleteMember.java") > 0);
            Assert.assertEquals(
                    "completion should never report non-zero enter counters",
                    0,
                    capture.countMatching(".*\\[perf\\] completion_flow .*enter=[1-9][0-9]*.*"));
            Assert.assertEquals(
                    "completion should never report non-zero analyze counters",
                    0,
                    capture.countMatching(".*\\[perf\\] completion_flow .*analyze=[1-9][0-9]*.*"));
            Assert.assertEquals(
                    "completion should never report non-zero annotation-processing counters",
                    0,
                    capture.countMatching(".*\\[perf\\] completion_flow .*ap=[1-9][0-9]*.*"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void gotoDefinitionPrefersWorkspaceTypeBeforeExternalBinaryLookup() throws Exception {
        var workspace = Files.createTempDirectory("jls-workspace-first-definition");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var models = workspace.resolve("src/com/example/demo/models");
            var service = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(models);
            Files.createDirectories(service);

            var enumFile = models.resolve("MyEnum.java");
            var serviceFile = service.resolve("ServiceTwo.java");
            Files.writeString(
                    enumFile,
                    "package com.example.demo.models;\n"
                            + "import lombok.AllArgsConstructor;\n"
                            + "import lombok.Getter;\n"
                            + "@Getter\n"
                            + "@AllArgsConstructor\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST(\"asd\");\n"
                            + "  private final String type;\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.models.MyEnum;\n"
                            + "class ServiceTwo {\n"
                            + "  String test() {\n"
                            + "    return MyEnum.FIRST.getType();\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            configureLombokClasspath(server);

            var enumOpen = new DidOpenTextDocumentParams();
            enumOpen.textDocument.uri = enumFile.toUri();
            enumOpen.textDocument.version = 1;
            enumOpen.textDocument.languageId = "java";
            enumOpen.textDocument.text = Files.readString(enumFile);
            server.didOpenTextDocument(enumOpen);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "expected completion index bootstrap before workspace-first definition check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            capture.clear();

            var found =
                    server.gotoDefinition(
                            new TextDocumentPositionParams(
                                    new TextDocumentIdentifier(serviceFile.toUri()),
                                    new Position(4, 26)));

            Assert.assertTrue("expected Lombok-backed definition result", found.isPresent());
            Assert.assertFalse("expected at least one definition location", found.get().isEmpty());
            Assert.assertEquals(
                    "definition should resolve to workspace source instead of an external binary candidate",
                    enumFile.toUri(),
                    found.get().get(0).uri);
            Assert.assertEquals(
                    "definition on the Lombok accessor should resolve to the backing field line",
                    7,
                    found.get().get(0).range.start.line);
            Assert.assertTrue(
                    "expected explicit Lombok field-link log",
                    capture.countContaining(
                                    "[perf] definition_lombok_field_link owner=com.example.demo.models.MyEnum accessor=getType field=type")
                            > 0);
            Assert.assertEquals(
                    "workspace definition should not speculate through the enclosing service type in the external-binary index",
                    0,
                    capture.countMatching(
                            ".*\\[external-binary\\].*type=com\\.example\\.demo\\.service\\.ServiceTwo\\.MyEnum.*"));
            Assert.assertEquals(
                    "workspace definition should not hit external-binary for the workspace enum type",
                    0,
                    capture.countMatching(
                            ".*\\[external-binary\\].*type=com\\.example\\.demo\\.models\\.MyEnum.*"));
            Assert.assertEquals(
                    "workspace definition should not leak workspace candidates into external lookup",
                    0,
                    capture.countContaining("[workspace-boundary] external_leak"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void referencesOnWorkspaceEnumDoNotProbeExternalBinary() throws Exception {
        var workspace = Files.createTempDirectory("jls-workspace-first-references");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var models = workspace.resolve("src/com/example/demo/models");
            var service = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(models);
            Files.createDirectories(service);

            var enumFile = models.resolve("MyEnum.java");
            var serviceFile = service.resolve("ServiceTwo.java");
            Files.writeString(
                    enumFile,
                    "package com.example.demo.models;\n"
                            + "import lombok.AllArgsConstructor;\n"
                            + "import lombok.Getter;\n"
                            + "@Getter\n"
                            + "@AllArgsConstructor\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST(\"asd\");\n"
                            + "  private final String type;\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.models.MyEnum;\n"
                            + "class ServiceTwo {\n"
                            + "  String test() {\n"
                            + "    return MyEnum.FIRST.getType();\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            configureLombokClasspath(server);

            var enumOpen = new DidOpenTextDocumentParams();
            enumOpen.textDocument.uri = enumFile.toUri();
            enumOpen.textDocument.version = 1;
            enumOpen.textDocument.languageId = "java";
            enumOpen.textDocument.text = Files.readString(enumFile);
            server.didOpenTextDocument(enumOpen);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "expected completion index bootstrap before workspace references check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            capture.clear();

            var params = new ReferenceParams();
            params.textDocument = new TextDocumentIdentifier(serviceFile.toUri());
            params.position = new Position(4, 11);
            var found = server.findReferences(params);

            Assert.assertTrue("expected references result", found.isPresent());
            Assert.assertFalse("expected at least one reference location", found.get().isEmpty());
            Assert.assertEquals(
                    "workspace references should not probe the enclosing service type in the external-binary index",
                    0,
                    capture.countMatching(
                            ".*\\[external-binary\\].*type=com\\.example\\.demo\\.service\\.ServiceTwo\\.MyEnum.*"));
            Assert.assertEquals(
                    "workspace references should not hit external-binary for the workspace enum type: "
                            + capture.linesMatching(".*\\[external-binary\\].*type=com\\.example\\.demo\\.models\\.MyEnum.*"),
                    0,
                    capture.countMatching(
                            ".*\\[external-binary\\].*type=com\\.example\\.demo\\.models\\.MyEnum.*"));
            Assert.assertEquals(
                    "workspace references should not leak workspace candidates into external lookup: "
                            + capture.linesMatching(".*\\[workspace-boundary\\] external_leak.*"),
                    0,
                    capture.countContaining("[workspace-boundary] external_leak"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void referencesOnWorkspaceEnumDeclarationDoNotLeakToExternalBinary() throws Exception {
        var workspace = Files.createTempDirectory("jls-workspace-enum-declaration-references");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var models = workspace.resolve("src/com/example/demo/models");
            var service = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(models);
            Files.createDirectories(service);

            var enumFile = models.resolve("MyEnum.java");
            var serviceFile = service.resolve("ServiceTwo.java");
            Files.writeString(
                    enumFile,
                    "package com.example.demo.models;\n"
                            + "import lombok.AllArgsConstructor;\n"
                            + "import lombok.Getter;\n"
                            + "@Getter\n"
                            + "@AllArgsConstructor\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST(\"asd\");\n"
                            + "  private final String type;\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.models.MyEnum;\n"
                            + "class ServiceTwo {\n"
                            + "  String test() {\n"
                            + "    return MyEnum.FIRST.getType();\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            configureLombokClasspath(server);

            var enumOpen = new DidOpenTextDocumentParams();
            enumOpen.textDocument.uri = enumFile.toUri();
            enumOpen.textDocument.version = 1;
            enumOpen.textDocument.languageId = "java";
            enumOpen.textDocument.text = Files.readString(enumFile);
            server.didOpenTextDocument(enumOpen);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "expected completion index bootstrap before enum declaration references check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            capture.clear();

            var params = new ReferenceParams();
            params.textDocument = new TextDocumentIdentifier(enumFile.toUri());
            params.position = new Position(6, 2);
            params.context = new org.javacs.lsp.ReferenceContext();
            params.context.includeDeclaration = true;
            var found = server.findReferences(params);

            Assert.assertTrue("expected references result on enum declaration", found.isPresent());
            Assert.assertFalse("expected at least one enum declaration reference", found.get().isEmpty());
            Assert.assertEquals(
                    "enum declaration references should not hit external-binary for the workspace enum type: "
                            + capture.linesMatching(".*com\\.example\\.demo\\.models\\.MyEnum.*"),
                    0,
                    capture.countMatching(
                            ".*\\[external-binary\\].*type=com\\.example\\.demo\\.models\\.MyEnum.*"));
            Assert.assertEquals(
                    "enum declaration references should not leak workspace enum ownership into external lookup: "
                            + capture.linesMatching(".*\\[workspace-boundary\\] external_leak.*"),
                    0,
                    capture.countMatching(
                            ".*\\[workspace-boundary\\] external_leak.*candidate=com\\.example\\.demo\\.models\\.MyEnum.*"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionOnWorkspaceOwnedReceiverDoesNotLeakToExternalBinary() throws Exception {
        var workspace = Files.createTempDirectory("jls-workspace-owned-receiver-completion");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var models = workspace.resolve("src/com/example/demo/models");
            var service = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(models);
            Files.createDirectories(service);

            var enumFile = models.resolve("MyEnum.java");
            var serviceFile = service.resolve("ServiceTwo.java");
            Files.writeString(
                    enumFile,
                    "package com.example.demo.models;\n"
                            + "import lombok.AllArgsConstructor;\n"
                            + "import lombok.Getter;\n"
                            + "@Getter\n"
                            + "@AllArgsConstructor\n"
                            + "public enum MyEnum {\n"
                            + "  FIRST(\"asd\");\n"
                            + "  private final String type;\n"
                            + "}\n");
            Files.writeString(
                    serviceFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.models.MyEnum;\n"
                            + "class ServiceTwo {\n"
                            + "  String test() {\n"
                            + "    return MyEnum.FIRST.\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            configureLombokClasspath(server);

            var enumOpen = new DidOpenTextDocumentParams();
            enumOpen.textDocument.uri = enumFile.toUri();
            enumOpen.textDocument.version = 1;
            enumOpen.textDocument.languageId = "java";
            enumOpen.textDocument.text = Files.readString(enumFile);
            server.didOpenTextDocument(enumOpen);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertTrue(
                    "expected completion index bootstrap before workspace-owned receiver completion check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            capture.clear();

            var completion =
                    server.completion(
                                    new TextDocumentPositionParams(
                                            new TextDocumentIdentifier(serviceFile.toUri()),
                                            new Position(4, 24)))
                            .orElseThrow();

            var labels =
                    completion.items.stream()
                            .map(item -> item.label)
                            .collect(java.util.stream.Collectors.toSet());
            Assert.assertTrue(
                    "workspace-owned receiver completion should expose Lombok accessor members",
                    labels.contains("getType"));
            Assert.assertEquals(
                    "workspace-owned receiver completion should not hit external-binary for the enclosing workspace owner: "
                            + capture.linesMatching(".*ServiceTwo.*"),
                    0,
                    capture.countMatching(
                            ".*\\[external-binary\\].*type=com\\.example\\.demo\\.service\\.ServiceTwo.*"));
            Assert.assertEquals(
                    "workspace-owned receiver completion should not leak workspace owner lookup into external lookup: "
                            + capture.linesMatching(".*\\[workspace-boundary\\] external_leak.*"),
                    0,
                    capture.countMatching(
                            ".*\\[workspace-boundary\\] external_leak.*candidate=com\\.example\\.demo\\.service\\.ServiceTwo.*"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void typeLookupBoundaryBlocksWorkspaceLeakAndStillResolvesExternalDependency() throws Exception {
        var workspace = Files.createTempDirectory("jls-type-lookup-boundary");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);
            var workspaceType = pkg.resolve("A.java");
            var useFile = pkg.resolve("Use.java");
            Files.writeString(workspaceType, "package p;\nclass A {}\n");
            Files.writeString(
                    useFile,
                    "package p;\n"
                            + "import java.util.ArrayList;\n"
                            + "class Use {\n"
                            + "  A local;\n"
                            + "  ArrayList<String> values;\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, diagnostic -> {});
            var boundary =
                    new TypeLookupBoundary(
                            server.compiler(),
                            new CompositeTypeIndex(
                                    org.javacs.completion.WorkspaceTypeIndex.EMPTY,
                                    new ExternalBinaryTypeIndex(server.compiler())));
            var parse = server.compiler().parse(useFile);

            Assert.assertEquals(
                    Optional.of("p.A"),
                    boundary.resolveWorkspaceType("A", parse.root));
            Assert.assertTrue(
                    "workspace-owned candidates must not reach external lookup",
                    boundary.findExternalSource("p.A", "unitTest").isEmpty());
            Assert.assertTrue(
                    "expected explicit workspace-boundary bug log for blocked external workspace lookup",
                    capture.countContaining("[workspace-boundary] external_leak candidate=p.A reason=unitTest") > 0);
            Assert.assertEquals(
                    Optional.of("java.util.ArrayList"),
                    boundary.resolveTypeName("ArrayList", parse.root));
            Assert.assertEquals(
                    "dependency/JDK lookup should not be reported as workspace leakage",
                    0,
                    capture.countContaining("[workspace-boundary] external_leak candidate=java.util.ArrayList"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
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
    public void compilerRecreatedSchedulesWorkspaceBootstrapForActiveFiles() throws Exception {
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
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
                    "compilerRecreated diagnostics pass should use active files only, line=" + line,
                    line.matches(".*files=1\\b.*"));
            Assert.assertEquals(
                    "compilerRecreated should not schedule a separate sync completion refresh when active files exist",
                    0,
                    capture.countContaining("completion_index_refresh_sync trigger=compilerRecreated"));
            Assert.assertTrue(
                    "compilerRecreated should schedule a dedicated workspace completion bootstrap when active files exist",
                    capture.countContaining("completion_index_debounce trigger=compilerRecreated") > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void startupCompilerRecreatedRefreshIsLazyWhenNoActiveDocs() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
            logger.setLevel(previousLevel);
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
    public void didOpenDoesNotRefreshInlayHintsAfterInitialIndexInstall() throws Exception {
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
            Assert.assertEquals(
                    "didOpen should not request inlay hint refresh after index install",
                    0,
                    client.refreshCount.get());
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
    public void didChangeDoesNotRefreshInlayHintsAfterIncrementalIndexUpdate() throws Exception {
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
            var file = FindResource.path("org/javacs/example/LombokCrossTypeModel.java");
            var original = FileStore.contents(file);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = original;
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected completion index to initialize before didChange check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            Assert.assertEquals("didOpen should not request inlay hint refresh", 0, client.refreshCount.get());

            var before = completionIndexVersion(server);
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = file.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = original.replace("private String name;", "private String title;");
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            Assert.assertTrue(
                    "didChange should still refresh completion index",
                    awaitCompletionIndexAdvance(server, before, 10, TimeUnit.SECONDS));
            Assert.assertEquals(
                    "didChange should not request inlay hint refresh after incremental index update",
                    0,
                    client.refreshCount.get());
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void didSaveDoesNotRefreshInlayHintsAfterSharedIndexUpdate() throws Exception {
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
            var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
            var text = FileStore.contents(file);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = text;
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "expected completion index to initialize before didSave check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            Assert.assertEquals("didOpen should not request inlay hint refresh", 0, client.refreshCount.get());

            var before = completionIndexVersion(server);
            var save = new DidSaveTextDocumentParams();
            save.textDocument = new TextDocumentIdentifier(file.toUri());
            server.didSaveTextDocument(save);

            Assert.assertTrue(
                    "didSave should still refresh completion index",
                    awaitCompletionIndexAdvance(server, before, 10, TimeUnit.SECONDS));
            Assert.assertEquals(
                    "didSave should not request inlay hint refresh after shared index update",
                    0,
                    client.refreshCount.get());
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void externalJavaSourceSkipsWorkspaceDiagnosticsAndInlayHints() throws Exception {
        FileStore.reset();
        var workspace = Files.createTempDirectory("jls-workspace-only");
        var externalRoot = Files.createTempDirectory("jls-external-java");
        var client = new RecordingDiagnosticsClient();
        try {
            var workspaceFile = workspace.resolve("src/app/Main.java");
            Files.createDirectories(workspaceFile.getParent());
            Files.writeString(workspaceFile, "package app;\nclass Main {}\n");

            var externalFile = externalRoot.resolve("cached/ext/ExternalPojo.java");
            Files.createDirectories(externalFile.getParent());
            Files.writeString(
                    externalFile,
                    "package ext;\n"
                            + "class ExternalPojo {\n"
                            + "  void test() {\n"
                            + "    var value = unknown();\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);
            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = externalFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(externalFile);
            server.didOpenTextDocument(open);

            Thread.sleep(1100);
            Assert.assertFalse(
                    "external cached-source files should not become active workspace documents",
                    FileStore.activeDocuments().contains(externalFile));
            Assert.assertEquals(
                    "external cached-source files should not publish diagnostics",
                    0,
                    client.diagnosticsPublishCount.get());

            var hints =
                    server.inlayHint(
                            new InlayHintParams(
                                    new TextDocumentIdentifier(externalFile.toUri()),
                                    new Range(new Position(0, 0), new Position(10, 0))));
            Assert.assertTrue(
                    "external cached-source files should not return inlay hints",
                    hints.isEmpty());
        } finally {
            deleteRecursively(externalRoot);
            deleteRecursively(workspace);
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
        } finally {
            logger.removeHandler(capture);
        }
    }

    private long completionIndexVersion(JavaLanguageServer server) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("completionIndexVersion");
        field.setAccessible(true);
        return ((AtomicLong) field.get(server)).get();
    }

    private void setCompletionIndexVersion(JavaLanguageServer server, long value) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("completionIndexVersion");
        field.setAccessible(true);
        ((AtomicLong) field.get(server)).set(value);
    }

    private void cancelPendingCompletionIndex(JavaLanguageServer server, String reason) throws Exception {
        var method = JavaLanguageServer.class.getDeclaredMethod("cancelPendingCompletionIndex", String.class);
        method.setAccessible(true);
        method.invoke(server, reason);
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
                        long.class);
        compileAndPublish.setAccessible(true);
        compileAndPublish.invoke(server, files, compiler, trigger, -1L);
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

    private static List<String> diagnosticFingerprints(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(
                        d ->
                                String.format(
                                        "%s|%s|%s|%d:%d",
                                        d.severity,
                                        d.code,
                                        d.message,
                                        d.range.start.line,
                                        d.range.start.character))
                .toList();
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

    private static boolean isSyntaxBlockingDiagnostic(Diagnostic diagnostic) {
        if (diagnostic == null || diagnostic.code == null) {
            return false;
        }
        return switch (diagnostic.code) {
            case "compiler.err.expected",
                    "compiler.err.expected2",
                    "compiler.err.not.stmt",
                    "compiler.err.illegal.start.of.expr",
                    "compiler.err.illegal.start.of.stmt" -> true;
            default -> false;
        };
    }

    private static void configureLombokClasspath(JavaLanguageServer server) {
        var settings = new JsonObject();
        var java = new JsonObject();
        var classPath = new com.google.gson.JsonArray();
        classPath.add(Paths.get("lib/lombok-1.18.30.jar").toAbsolutePath().toString());
        java.add("classPath", classPath);
        settings.add("java", java);
        var change = new DidChangeConfigurationParams();
        change.settings = settings;
        server.didChangeConfiguration(change);
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

        boolean hasErrorMatching(java.net.URI uri, Predicate<Diagnostic> predicate) {
            var diagnostics = diagnosticsByUri.get(uri);
            if (diagnostics == null) {
                return false;
            }
            return diagnostics.stream()
                    .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                    .anyMatch(predicate);
        }

        int diagnosticsPublishCount() {
            return diagnosticsPublishCount.get();
        }

        Set<java.net.URI> publishedUris() {
            return Set.copyOf(diagnosticsByUri.keySet());
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

        boolean awaitDiagnosticsMatching(
                java.net.URI uri, Predicate<List<Diagnostic>> predicate, long timeout, TimeUnit unit)
                throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                var diagnostics = diagnosticsByUri.get(uri);
                if (diagnostics != null && predicate.test(diagnostics)) {
                    return true;
                }
                Thread.sleep(25);
            }
            return false;
        }

        List<Diagnostic> diagnostics(java.net.URI uri) {
            return diagnosticsByUri.getOrDefault(uri, List.of());
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
            if (record == null || record.getLevel().intValue() < Level.FINE.intValue()) {
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

        List<String> linesMatching(String pattern) {
            var result = new java.util.ArrayList<String>();
            for (var line : lines) {
                if (line != null && line.matches(pattern)) {
                    result.add(line);
                }
            }
            return result;
        }

        void clear() {
            lines.clear();
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
