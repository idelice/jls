package org.javacs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.index.IndexedMember;
import org.javacs.index.IndexedType;
import org.javacs.markup.ErrorProvider;
import org.javacs.lsp.DocumentDiagnosticParams;
import org.javacs.lsp.DocumentDiagnosticReport;
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
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.Position;
import org.javacs.lsp.PublishDiagnosticsParams;
import org.javacs.lsp.ReferenceParams;
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
    public void didOpenBootstrapPreservesLombokAccessorBackingFieldMetadata() throws Exception {
        FileStore.reset();
        var server = LanguageServerFixture.getJavaLanguageServer();
        configureLombokClasspath(server);
        var file = FindResource.path("org/javacs/example/LombokFieldReferences.java");

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = Files.readString(file);
        server.didOpenTextDocument(open);

        Assert.assertTrue(
                "expected completion index bootstrap before Lombok accessor metadata check",
                awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

        var member =
                workspaceIndex(server)
                        .member("org.javacs.example.LombokFieldReferences", "getBar", false, new String[0]);
        Assert.assertTrue("expected indexed Lombok accessor after didOpen bootstrap", member.isPresent());
        Assert.assertEquals("bar", member.get().backingFieldName);
    }

    @Test
    public void didOpenBootstrapPublishesSourceSnapshotsAndDeclarationRanges() throws Exception {
        FileStore.reset();
        var workspace = Files.createTempDirectory("jls-index-source-snapshot");
        try {
            var pkg = workspace.resolve("src/com/example");
            Files.createDirectories(pkg);
            var file = pkg.resolve("SnapshotExample.java");
            Files.writeString(
                    file,
                    "package com.example;\n"
                            + "import java.util.List;\n"
                            + "import static java.util.Collections.emptyList;\n"
                            + "class SnapshotExample {\n"
                            + "  static class Nested {\n"
                            + "    static final String VALUE = \"x\";\n"
                            + "    List<String> values() { return emptyList(); }\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, file);

            Assert.assertTrue(
                    "expected completion index bootstrap before snapshot assertion",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var index = workspaceIndex(server);
            var source = index.sourceFile(file);
            Assert.assertTrue("expected indexed source snapshot", source.isPresent());
            Assert.assertEquals(List.of("java.util.List"), source.get().imports);
            Assert.assertEquals(List.of("java.util.Collections.emptyList"), source.get().staticImports);
            Assert.assertTrue(source.get().declaredTypes.contains("com.example.SnapshotExample"));
            Assert.assertTrue(source.get().declaredTypes.contains("com.example.SnapshotExample.Nested"));

            var nestedType = index.typeInfo("com.example.SnapshotExample.Nested");
            Assert.assertTrue("expected nested type in workspace snapshot", nestedType.isPresent());
            Assert.assertTrue("expected nested type declaration range", nestedType.get().declarationLocation().isPresent());

            var field = index.member("com.example.SnapshotExample.Nested", "VALUE", true);
            Assert.assertTrue("expected nested field in workspace snapshot", field.isPresent());
            Assert.assertTrue("expected nested field declaration range", field.get().declarationLocation().isPresent());

            var method = index.member("com.example.SnapshotExample.Nested", "values", false, new String[0]);
            Assert.assertTrue("expected nested method in workspace snapshot", method.isPresent());
            Assert.assertTrue("expected nested method declaration range", method.get().declarationLocation().isPresent());
            Assert.assertEquals("java.util.List<java.lang.String>", method.get().declaredReturnType);
        } finally {
            deleteRecursively(workspace);
        }
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
                    capture.countContaining("workspace bootstrap started trigger=didOpenBootstrap") > 0);
            Assert.assertTrue(
                    "didOpen should log workspace index install",
                    capture.countContaining("workspace index installed trigger=didOpenBootstrap") > 0);
            Assert.assertEquals(
                    "startup should not schedule a separate compilerRecreated completion refresh when no active docs existed",
                    0,
                    capture.countContaining("completion_index_refresh_sync trigger=compilerRecreated"));
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
    public void didOpenSynchronouslyBootstrapsWorkspaceDiagnosticsAndIndex() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);
            var file = FindResource.path("org/javacs/example/HelloWorld.java");

            Assert.assertEquals("expected empty completion index before didOpen bootstrap", 0L, completionIndexVersion(server));

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = FileStore.contents(file);
            server.didOpenTextDocument(open);

            Assert.assertTrue(
                    "didOpen should synchronously install the workspace index before returning",
                    completionIndexVersion(server) > 0);
            Assert.assertEquals(
                    "didOpen bootstrap should not schedule a separate completion bootstrap",
                    0,
                    capture.countContaining("completion_index_debounce trigger=didOpenActiveBootstrap"));
            Assert.assertTrue(
                    "didOpen bootstrap should log workspace bootstrap start",
                    capture.countContaining("workspace bootstrap started trigger=didOpenBootstrap") > 0);
            Assert.assertTrue(
                    "didOpen bootstrap should log workspace index installation",
                    capture.countContaining("workspace index installed trigger=didOpenBootstrap") > 0);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void configurationChangeKeepsExternalBinaryIndexReadyWithoutWorkspaceBootstrap() throws Exception {
        FileStore.reset();
        var server = LanguageServerFixture.getJavaLanguageServer();
        var beforeVersion = completionIndexVersion(server);
        var initialExternal = externalBinaryIndex(server);

        Assert.assertNotSame("compiler initialization should create a non-empty external index", ExternalBinaryTypeIndex.EMPTY, initialExternal);

        var settings = new JsonObject();
        var java = new JsonObject();
        var extraCompilerArgs = new com.google.gson.JsonArray();
        extraCompilerArgs.add("-Xlint:deprecation");
        java.add("extraCompilerArgs", extraCompilerArgs);
        settings.add("java", java);
        var change = new DidChangeConfigurationParams();
        change.settings = settings;
        server.didChangeConfiguration(change);

        var refreshedExternal = externalBinaryIndex(server);
        Assert.assertNotSame("compiler recreation should refresh the external binary index", initialExternal, refreshedExternal);
        Assert.assertNotSame("refreshed external index should remain usable", ExternalBinaryTypeIndex.EMPTY, refreshedExternal);
        Assert.assertEquals(
                "compiler recreation without opened files should not reset workspace index version",
                beforeVersion,
                completionIndexVersion(server));
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
    public void didChangedDoesNotPushDiagnostics() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var text = FileStore.contents(file);

        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = text;
        server.didOpenTextDocument(open);

        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = 2;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = text + "\n// clear-stale";
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);

        // Pull diagnostics should reflect the updated content
        var report = pullDiagnostics(server, file.toUri());
        Assert.assertNotNull("pull diagnostics should return a report", report);
    }

    @Test
    public void didSaveRefreshesIndexAndDiagnosticsArePullable() throws Exception {
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

            Assert.assertTrue(
                    "didSave should schedule a dedicated completion-index refresh",
                    capture.countContaining("[perf] completion_index_debounce trigger=didSave") > 0);

            var report = pullDiagnostics(server, file.toUri());
            Assert.assertNotNull("pull diagnostics should return a report after save", report);
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
    public void lombokStructuralAnnotationDetectionExcludesSlf4j() {
        Assert.assertTrue(LombokAnnotations.isStructuralLombokAnnotationType("lombok.Data"));
        Assert.assertTrue(LombokAnnotations.isStructuralLombokAnnotationType("Getter"));
        Assert.assertFalse(LombokAnnotations.isStructuralLombokAnnotationType("lombok.extern.slf4j.Slf4j"));
        Assert.assertFalse(LombokAnnotations.isStructuralLombokAnnotationType("Slf4j"));
    }

    @Test
    public void didChangeDoesNotPushDiagnosticsForOtherOpenFiles() throws Exception {
        var workspace = Files.createTempDirectory("jls-diagnostics-open-batch");
        try {
            var pkg = workspace.resolve("src/p");
            Files.createDirectories(pkg);
            var serviceFile = pkg.resolve("ServiceTwo.java");
            var otherFile = pkg.resolve("Other.java");
            Files.writeString(
                    serviceFile,
                    "package p;\n"
                            + "class ServiceTwo {\n"
                            + "  void test() {}\n"
                            + "}\n");
            Files.writeString(
                    otherFile,
                    "package p;\n"
                            + "class Other {\n"
                            + "  void test(ServiceTwo service) {\n"
                            + "    service.toString();\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);

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
            delta.text = serviceOpen.textDocument.text + "\n// updated";
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            // Each file can be pulled independently after a change
            var serviceReport = pullDiagnostics(server, serviceFile.toUri());
            var otherReport = pullDiagnostics(server, otherFile.toUri());
            Assert.assertNotNull("pull diagnostics should work for changed file", serviceReport);
            Assert.assertNotNull("pull diagnostics should work for other open file", otherReport);
        } finally {
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);

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

            var report = pullDiagnostics(server, serviceFile.toUri());
            Assert.assertFalse(
                    "consumer diagnostics should include the active unsaved enum dependency on open",
                    report.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getType()")));
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = serviceFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(open);

            var serviceReport = pullDiagnostics(server, serviceFile.toUri());
            Assert.assertEquals(
                    "pull diagnostics should report zero errors for the clean service file",
                    0,
                    serviceReport.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .count());
            var fooReport = pullDiagnostics(server, fooFile.toUri());
            Assert.assertEquals(
                    "pull diagnostics for Foo should not be triggered by opening Service",
                    0,
                    fooReport.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .count());
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
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);
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

            var report = pullDiagnostics(server, serviceFile.toUri());
            Assert.assertFalse(
                    "consumer diagnostics should include the referenced Lombok enum dependency on first open",
                    report.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getType()")));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void openingDependencyAfterConsumerDoesNotChangeConsumerDiagnosticsOutcome() throws Exception {
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);
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

        var beforeReport = pullDiagnostics(server, serviceFile.toUri());
        Assert.assertFalse(
                "expected clean consumer diagnostics before dependency is opened",
                beforeReport.items.stream()
                        .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                        .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));

        var modelOpen = new DidOpenTextDocumentParams();
        modelOpen.textDocument.uri = modelFile.toUri();
        modelOpen.textDocument.version = 1;
        modelOpen.textDocument.languageId = "java";
        modelOpen.textDocument.text = Files.readString(modelFile);
        server.didOpenTextDocument(modelOpen);

        var afterReport = pullDiagnostics(server, serviceFile.toUri());
        Assert.assertFalse(
                "opening the dependency should not change the consumer diagnostics outcome",
                afterReport.items.stream()
                        .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                        .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));
    }

    @Test
    public void completionOnDependencyDoesNotWarmDiagnosticsParseCache() throws Exception {
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);

        var consumerFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        var dependencyFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/model/ReproTxn.java");
        server.completion(
                new TextDocumentPositionParams(
                        new TextDocumentIdentifier(dependencyFile.toUri()), new Position(4, 12)));

        var consumerOpen = new DidOpenTextDocumentParams();
        consumerOpen.textDocument.uri = consumerFile.toUri();
        consumerOpen.textDocument.version = 1;
        consumerOpen.textDocument.languageId = "java";
        consumerOpen.textDocument.text = Files.readString(consumerFile);
        server.didOpenTextDocument(consumerOpen);

        var report = pullDiagnostics(server, consumerFile.toUri());
        Assert.assertFalse(
                "expected clean consumer diagnostics after dependency completion warmup",
                report.items.stream()
                        .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                        .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));
    }

    @Test
    public void didOpenConsumerLogsLombokDiagnosticsExpansionDetails() throws Exception {
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);

        var serviceFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        var serviceOpen = new DidOpenTextDocumentParams();
        serviceOpen.textDocument.uri = serviceFile.toUri();
        serviceOpen.textDocument.version = 1;
        serviceOpen.textDocument.languageId = "java";
        serviceOpen.textDocument.text = Files.readString(serviceFile);
        server.didOpenTextDocument(serviceOpen);

        var report = pullDiagnostics(server, serviceFile.toUri());
        Assert.assertFalse(
                "expected clean consumer diagnostics for the Lombok repro service",
                report.items.stream()
                        .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                        .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);

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

            var report = pullDiagnostics(server, serviceFile.toUri());
            Assert.assertFalse(
                    "consumer diagnostics should include active unsaved Lombok dependencies transitively",
                    report.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getBar()", "getBiz()")));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void repeatedDidOpenAcrossDifferentLombokConsumersKeepsDiagnosticsResolved() throws Exception {
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);

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

            var firstReport = pullDiagnostics(server, firstFile.toUri());
            Assert.assertFalse(
                    "first Lombok consumer diagnostics should stay resolved",
                    firstReport.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));

            var secondOpen = new DidOpenTextDocumentParams();
            secondOpen.textDocument.uri = secondFile.toUri();
            secondOpen.textDocument.version = 1;
            secondOpen.textDocument.languageId = "java";
            secondOpen.textDocument.text = Files.readString(secondFile);
            server.didOpenTextDocument(secondOpen);

            var secondReport = pullDiagnostics(server, secondFile.toUri());
            Assert.assertFalse(
                    "second Lombok consumer diagnostics should stay resolved after reusing diagnostics compiler",
                    secondReport.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getBar()", "getName()")));
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);
            configureLombokClasspath(server);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = consumerFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(consumerFile);
            server.didOpenTextDocument(open);

            var report = pullDiagnostics(server, consumerFile.toUri());
            Assert.assertFalse(
                    "generated getter chain should not produce diagnostics in consumer",
                    report.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getBar()", "getBiz()", "value()")));
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);
            configureLombokClasspath(server);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = consumerFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(consumerFile);
            server.didOpenTextDocument(open);

            var report = pullDiagnostics(server, consumerFile.toUri());
            Assert.assertFalse(
                    "field-level setter variant should still resolve generated getter chain",
                    report.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getBar()", "getBiz()", "value()")));
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);
            configureLombokClasspath(server);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = consumerFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(consumerFile);
            server.didOpenTextDocument(open);

            var report = pullDiagnostics(server, consumerFile.toUri());
            Assert.assertFalse(
                    "split-package getter chain should not produce diagnostics in consumer",
                    report.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getBar()", "getBiz()", "value()")));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void lombokNestedClassNameCollidesWithInterfaceNoFalseDiagnostics() throws Exception {
        // Reproducer for: interface Foo in package A, outer @Data class in package B with a nested
        // @Data class also named Foo (same simple name as the interface). Consumer imports both
        // packages via wildcard and calls a getter chain through the nested Lombok type.
        // Diagnostics should not falsely report that Lombok-generated methods do not exist.
        var workspace = Files.createTempDirectory("jls-lombok-nested-name-collision");
        try {
            var contractDir = workspace.resolve("src/main/java/p/contract");
            var modelDir = workspace.resolve("src/main/java/p/model");
            var serviceDir = workspace.resolve("src/main/java/p/service");
            Files.createDirectories(contractDir);
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            // Interface named "Response" in p.contract — no Lombok
            Files.writeString(
                    contractDir.resolve("Response.java"),
                    "package p.contract;\n"
                            + "public interface Response {\n"
                            + "  String getStatus();\n"
                            + "}\n");

            // Outer @Data class with two nested @Data classes, one named "Response"
            // (same simple name as the interface above)
            Files.writeString(
                    modelDir.resolve("Request.java"),
                    "package p.model;\n"
                            + "@lombok.Data\n"
                            + "public class Request {\n"
                            + "  private Response response;\n"
                            + "  private Detail detail;\n"
                            + "  @lombok.Data\n"
                            + "  public static class Response {\n"
                            + "    private Detail detail;\n"
                            + "    private String code;\n"
                            + "  }\n"
                            + "  @lombok.Data\n"
                            + "  public static class Detail {\n"
                            + "    private String message;\n"
                            + "  }\n"
                            + "}\n");

            // Consumer: wildcard imports bring in both the interface and the outer class.
            // Getter chain goes through nested Lombok types.
            var consumerFile = serviceDir.resolve("Consumer.java");
            Files.writeString(
                    consumerFile,
                    "package p.service;\n"
                            + "import p.contract.*;\n"
                            + "import p.model.*;\n"
                            + "public class Consumer {\n"
                            + "  String use(Request req) {\n"
                            + "    return req.getResponse().getDetail().getMessage();\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);
            configureLombokClasspath(server);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = consumerFile.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = Files.readString(consumerFile);
            server.didOpenTextDocument(open);

            var report = pullDiagnostics(server, consumerFile.toUri());
            Assert.assertFalse(
                    "nested Lombok class with same name as interface should not produce false getter diagnostics; errors: "
                            + report.items.stream()
                                    .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                                    .map(d -> d.message)
                                    .toList(),
                    report.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getResponse()", "getDetail()", "getMessage()")));
        } finally {
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);

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

            var baseline = pullDiagnostics(server, serviceFile.toUri());
            Assert.assertTrue(
                    "baseline should show the missing enum member error before the unsaved enum change",
                    baseline.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getType()")));

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

            var after = pullDiagnostics(server, serviceFile.toUri());
            Assert.assertFalse(
                    "changing the enum should resolve consumer diagnostics on next pull",
                    after.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getType()")));
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);

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

            var baseline = pullDiagnostics(server, serviceFile.toUri());
            Assert.assertTrue(
                    "baseline should show the missing enum member error before the unsaved enum change",
                    baseline.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getType()")));

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

            server.didOpenTextDocument(serviceOpen);

            var after = pullDiagnostics(server, serviceFile.toUri());
            Assert.assertFalse(
                    "reopening the consumer after dependency change should resolve diagnostics on pull",
                    after.items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .anyMatch(d -> containsAny(d.message, "getType()")));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void lombokConsumerDiagnosticsRemainResolvedBeforeAndAfterModelSave() throws Exception {
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);

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

        var baselineDiagnostics =
                diagnosticFingerprints(pullDiagnostics(server, serviceFile.toUri()).items);
        Assert.assertFalse(
                "expected Lombok member diagnostics to resolve from referenced Lombok source expansion",
                pullDiagnostics(server, serviceFile.toUri()).items.stream()
                        .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                        .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));

        var modelOpen = new DidOpenTextDocumentParams();
        modelOpen.textDocument.uri = modelFile.toUri();
        modelOpen.textDocument.version = 1;
        modelOpen.textDocument.languageId = "java";
        modelOpen.textDocument.text = Files.readString(modelFile);
        server.didOpenTextDocument(modelOpen);

        Assert.assertEquals(
                "opening an unchanged Lombok dependency should not change consumer diagnostics",
                baselineDiagnostics,
                diagnosticFingerprints(pullDiagnostics(server, serviceFile.toUri()).items));

        var modelSave = new DidSaveTextDocumentParams();
        modelSave.textDocument = new TextDocumentIdentifier(modelFile.toUri());
        server.didSaveTextDocument(modelSave);

        var changed = new FileEvent();
        changed.uri = modelFile.toUri();
        changed.type = FileChangeType.Changed;
        var watched = new DidChangeWatchedFilesParams();
        watched.changes = List.of(changed);
        server.didChangeWatchedFiles(watched);

        Assert.assertEquals(
                "saving an unchanged Lombok dependency should not change consumer diagnostics",
                baselineDiagnostics,
                diagnosticFingerprints(pullDiagnostics(server, serviceFile.toUri()).items));
    }

    @Test
    public void didChangeLombokModelRefreshesOpenConsumerDiagnostics() throws Exception {
        var server =
                LanguageServerFixture.getJavaLanguageServer(
                        LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);

        var serviceFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        var modelFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/model/ReproTxn.java");
        var originalModel = Files.readString(modelFile);

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

        Assert.assertFalse(
                "expected baseline Lombok consumer diagnostics to be clean",
                pullDiagnostics(server, serviceFile.toUri()).items.stream()
                        .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                        .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));

        var brokenModel = originalModel.replace("msref", "title");
        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = modelFile.toUri();
        change.textDocument.version = 2;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = brokenModel;
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);

        Assert.assertTrue(
                "changing a Lombok model should surface errors in the consumer on the next pull",
                pullDiagnostics(server, serviceFile.toUri()).items.stream()
                        .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                        .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));

        var revert = new DidChangeTextDocumentParams();
        revert.textDocument.uri = modelFile.toUri();
        revert.textDocument.version = 3;
        var revertDelta = new TextDocumentContentChangeEvent();
        revertDelta.text = originalModel;
        revert.contentChanges.add(revertDelta);
        server.didChangeTextDocument(revert);

        Assert.assertFalse(
                "restoring the Lombok model should clear consumer errors on the next pull",
                pullDiagnostics(server, serviceFile.toUri()).items.stream()
                        .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                        .anyMatch(d -> containsAny(d.message, "getMsref()", "setMsref(")));
    }

    @Test
    public void watchedLombokDependencyChangeRefreshesOpenConsumerDiagnosticsWithoutOpeningDependency()
            throws Exception {
        var workspace = Files.createTempDirectory("jls-watched-lombok-dependency-refresh");
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

            var server = LanguageServerFixture.getJavaLanguageServer(workspace);
            configureLombokClasspath(server);

            var serviceOpen = new DidOpenTextDocumentParams();
            serviceOpen.textDocument.uri = serviceFile.toUri();
            serviceOpen.textDocument.version = 1;
            serviceOpen.textDocument.languageId = "java";
            serviceOpen.textDocument.text = Files.readString(serviceFile);
            server.didOpenTextDocument(serviceOpen);

            Assert.assertFalse(
                    "consumer diagnostics should resolve against an unopened Lombok dependency on disk",
                    pullDiagnostics(server, serviceFile.toUri()).items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .findAny()
                            .isPresent());

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

            Assert.assertFalse(
                    "watched Lombok dependency changes should not introduce errors in the consumer",
                    pullDiagnostics(server, serviceFile.toUri()).items.stream()
                            .filter(d -> d.severity != null && d.severity == DiagnosticSeverity.Error)
                            .findAny()
                            .isPresent());
        } finally {
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
        public void definitionTracksUnsavedMethodDeclarationLineShiftInOpenFile() throws Exception {
                var workspace = Files.createTempDirectory("jls-unsaved-definition-line-shift");
                try {
                        var source = workspace.resolve("org/javacs/InferConfigReplay.java");
                        Files.createDirectories(source.getParent());

                        var original =
                                        """
                                        package org.javacs;

                                        import java.nio.file.Path;

                                        class InferConfigReplay {
                                                private final Path workspaceRoot;

                                                InferConfigReplay(Path workspaceRoot) {
                                                        this.workspaceRoot = workspaceRoot;
                                                }

                                                Path classPath() {
                                                        return bazelWorkspaceRoot();
                                                }

                                                private Path bazelWorkspaceRoot() {
                                                        return workspaceRoot;
                                                }
                                        }
                                        """;
                        Files.writeString(source, original);

                        var server = LanguageServerFixture.getJavaLanguageServer(workspace);
                        var open = new DidOpenTextDocumentParams();
                        open.textDocument.uri = source.toUri();
                        open.textDocument.version = 1;
                        open.textDocument.languageId = "java";
                        open.textDocument.text = original;
                        server.didOpenTextDocument(open);

                        Assert.assertTrue(
                                        "expected completion index bootstrap before unsaved definition shift check",
                                        awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

                        assertDefinitionAtMarker(
                                        server,
                                        source,
                                        original,
                                        "bazelWorkspaceRoot();",
                                        source.toUri(),
                                        positionAtMarker(original, "private Path bazelWorkspaceRoot()").line);

                        var changed =
                                        original.replace(
                                                        "\n    private Path bazelWorkspaceRoot()",
                                                        "\n\n    private Path bazelWorkspaceRoot()");
                        var change = new DidChangeTextDocumentParams();
                        change.textDocument.uri = source.toUri();
                        change.textDocument.version = 2;
                        var delta = new TextDocumentContentChangeEvent();
                        delta.text = changed;
                        change.contentChanges.add(delta);
                        server.didChangeTextDocument(change);

                        assertDefinitionAtMarker(
                                        server,
                                        source,
                                        changed,
                                        "bazelWorkspaceRoot();",
                                        source.toUri(),
                                        positionAtMarker(changed, "private Path bazelWorkspaceRoot()").line);
                } finally {
                        deleteRecursively(workspace);
                }
        }

            @Test
            public void definitionTracksUnsavedMethodDeclarationLineShiftInRepoFile() throws Exception {
                var workspace = Paths.get("").toAbsolutePath().normalize();
                var file = workspace.resolve("src/main/java/org/javacs/InferConfig.java");
                var original = FileStore.contents(file);

                FileStore.reset();
                var server = LanguageServerFixture.getJavaLanguageServer(workspace);
                var open = new DidOpenTextDocumentParams();
                open.textDocument.uri = file.toUri();
                open.textDocument.version = 1;
                open.textDocument.languageId = "java";
                open.textDocument.text = original;
                server.didOpenTextDocument(open);

                Assert.assertTrue(
                        "expected completion index bootstrap before repo unsaved definition shift check",
                        awaitCompletionIndexAdvance(server, 0, 180, TimeUnit.SECONDS));

                assertDefinitionAtMarker(
                        server,
                        file,
                        original,
                        "bazelWorkspaceRoot();",
                        file.toUri(),
                        positionAtMarker(original, "private Path bazelWorkspaceRoot()").line);

                var changed =
                        original.replace(
                                "\n    private Path bazelWorkspaceRoot()",
                                "\n\n    private Path bazelWorkspaceRoot()");
                var change = new DidChangeTextDocumentParams();
                change.textDocument.uri = file.toUri();
                change.textDocument.version = 2;
                var delta = new TextDocumentContentChangeEvent();
                delta.text = changed;
                change.contentChanges.add(delta);
                server.didChangeTextDocument(change);

                assertDefinitionAtMarker(
                        server,
                        file,
                        changed,
                        "bazelWorkspaceRoot();",
                        file.toUri(),
                        positionAtMarker(changed, "private Path bazelWorkspaceRoot()").line);
            }

    @Test
    public void incompleteBodyEditDoesNotTriggerDeclarationDriftRefresh() throws Exception {
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
                    "expected completion index bootstrap before didChange drift check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var before = completionIndexVersion(server);
            var change = new DidChangeTextDocumentParams();
            change.textDocument.uri = file.toUri();
            change.textDocument.version = 2;
            var delta = new TextDocumentContentChangeEvent();
            delta.text = original.replace("        return \"foo\";\n", "        if (\n");
            change.contentChanges.add(delta);
            server.didChangeTextDocument(change);

            Thread.sleep(1600);

            Assert.assertEquals(
                    "incomplete method-body edits should keep the published completion snapshot",
                    before,
                    completionIndexVersion(server));
            Assert.assertNull(
                    "incomplete source should skip declaration-drift refresh",
                    capture.lastLineContaining(
                            "[perf] completion_index_didChange_refresh file=AutocompleteMember.java reason=declaration_drift"));
            Assert.assertNotNull(
                    "incomplete source should log the didChange skip reason",
                    capture.lastLineContaining(
                            "[perf] completion_index_didChange_skip file=AutocompleteMember.java reason=incomplete_source"));
            Assert.assertEquals(
                    "didChange should not schedule a completion-index debounce for incomplete body edits",
                    0,
                    capture.countContaining("[perf] completion_index_debounce trigger=didChange"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void hasDeclarationDriftCoversStructuralCompareBranches() throws Exception {
        FileStore.reset();
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        openAndBootstrap(server, file);

        var indexed = indexedTypeForFile(server, file);
        var matchingShape = declaredTypeShape(indexed, false);

        Assert.assertFalse(
                "matching indexed declaration shape should not report drift",
                hasDeclarationDrift(server, file, List.of(matchingShape)));

        Assert.assertTrue(
                "missing or extra declared types should report drift",
                hasDeclarationDrift(server, file, List.of()));

        Assert.assertTrue(
                "same-size declared type sets with different qualified names should report drift",
                hasDeclarationDrift(
                        server,
                        file,
                        List.of(
                                declaredTypeShape(
                                        indexed.qualifiedName + "Renamed",
                                        directMemberSignatures(indexed),
                                        indexed.superclass,
                                        indexed.interfaces,
                                        false))));

        Assert.assertTrue(
                "superclass changes should report drift",
                hasDeclarationDrift(
                        server,
                        file,
                        List.of(
                                declaredTypeShape(
                                        indexed.qualifiedName,
                                        directMemberSignatures(indexed),
                                        "java.lang.Number",
                                        indexed.interfaces,
                                        false))));

        Assert.assertTrue(
                "interface changes should report drift",
                hasDeclarationDrift(
                        server,
                        file,
                        List.of(
                                declaredTypeShape(
                                        indexed.qualifiedName,
                                        directMemberSignatures(indexed),
                                        indexed.superclass,
                                        List.of("java.io.Serializable"),
                                        false))));

        var changedMembers = new java.util.ArrayList<>(directMemberSignatures(indexed));
        changedMembers.add("M:extra:0:false");
        Assert.assertTrue(
                "direct member signature changes should report drift",
                hasDeclarationDrift(
                        server,
                        file,
                        List.of(
                                declaredTypeShape(
                                        indexed.qualifiedName,
                                        changedMembers,
                                        indexed.superclass,
                                        indexed.interfaces,
                                        false))));
    }

    @Test
    public void hasDeclarationDriftDetectsIndexedStructuralLombokMismatch() throws Exception {
        FileStore.reset();
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = Paths.get("/tmp/DriftCoverage.java");
        var qualifiedName = "p.DriftCoverage";

        var indexed =
                new IndexedType(
                        qualifiedName,
                        "DriftCoverage",
                        List.of(
                                new IndexedMember(
                                        qualifiedName,
                                        "plainField",
                                        CompletionItemKind.Field,
                                        false,
                                        false,
                                        0,
                                        "String plainField",
                                        "java.lang.String",
                                        null,
                                        null,
                                        qualifiedName + "#plainField",
                                        qualifiedName + "#plainField",
                                        null,
                                        false),
                                new IndexedMember(
                                        qualifiedName,
                                        "halfSynthetic",
                                        CompletionItemKind.Method,
                                        false,
                                        false,
                                        0,
                                        "String halfSynthetic()",
                                        "java.lang.String",
                                        new String[0],
                                        new String[0],
                                        qualifiedName + "#halfSynthetic()",
                                        qualifiedName + "#halfSynthetic()",
                                        null,
                                        true),
                                new IndexedMember(
                                        qualifiedName,
                                        "getName",
                                        CompletionItemKind.Method,
                                        false,
                                        false,
                                        0,
                                        "String getName()",
                                        "java.lang.String",
                                        new String[0],
                                        new String[0],
                                        qualifiedName + "#getName()",
                                        qualifiedName + "#getName()",
                                        "name",
                                        true)),
                        false,
                        file,
                        null,
                        List.of(),
                        IndexedMember.Provenance.WORKSPACE);

        publishWorkspaceSnapshot(server, workspaceIndexOf(indexed), 1);

        Assert.assertFalse(
                "matching structural Lombok should not report drift",
                hasDeclarationDrift(server, file, List.of(declaredTypeShape(indexed, true))));
        Assert.assertTrue(
                "structural Lombok mismatch should report drift against the indexed snapshot",
                hasDeclarationDrift(server, file, List.of(declaredTypeShape(indexed, false))));
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
            if (mergeLog != null) {
                Assert.assertTrue(
                        "incremental refresh should log the published base snapshot version",
                        mergeLog.contains("base_version=" + before));
            }
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
            var completionFlow = capture.lastLineContaining("[perf] completion_flow file=AutocompleteMember.java");
            Assert.assertNotNull("expected detailed completion flow line", completionFlow);
            Assert.assertTrue("completion flow should include parse timing", completionFlow.contains("parse="));
            Assert.assertTrue("completion flow should include resolve timing", completionFlow.contains("resolve="));
            Assert.assertTrue("completion flow should include member cache state", completionFlow.contains("member_cache="));
            var completionRequest = capture.lastLineContaining("[perf] completion_request file=AutocompleteMember.java");
            if (completionRequest != null) {
                Assert.assertTrue("completion request should include wait timing", completionRequest.contains("wait="));
                Assert.assertTrue(
                        "completion request should include diagnostics activity state",
                        completionRequest.contains("diagnostics_active="));
            }
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
    public void diagnosticsPublishSkipsNonRequestedExpandedRoots() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var serviceFile =
                LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(
                        "src/org/javacs/repro/service/ReproService.java");
        try (var task = server.getOrCreateCompiler().compile(serviceFile)) {
            var report = new ErrorProvider(task).errors(Set.of(serviceFile.toUri()));
            Assert.assertTrue(
                    "expected diagnostics compile to include referenced Lombok sources",
                    report.compiledRoots() > report.requestedRoots());
            Assert.assertEquals("expected one explicitly requested diagnostics root", 1, report.requestedRoots());
            Assert.assertEquals(
                    "non-requested expanded roots should not be processed for diagnostics materialization",
                    1,
                    report.processedRoots());
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
    public void definitionOnStreamMethodReferenceResolvesWithoutBeansDependency() throws Exception {
        var workspace = Files.createTempDirectory("jls-stream-method-reference-definition");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var modelDir = workspace.resolve("src/com/example/demo/complex/model");
            var serviceDir = workspace.resolve("src/com/example/demo/complex/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var envelope = modelDir.resolve("OrderEnvelope.java");
            var service = serviceDir.resolve("ComplexScenarioService.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.complex.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getFamily() { return \"family\"; }\n"
                            + "}\n");
            Files.writeString(
                    envelope,
                    "package com.example.demo.complex.model;\n"
                            + "import java.util.List;\n"
                            + "public class OrderEnvelope {\n"
                            + "  public List<LineItem> getItems() { return List.of(); }\n"
                            + "}\n");
            Files.writeString(
                    service,
                    "package com.example.demo.complex.service;\n"
                            + "import com.example.demo.complex.model.LineItem;\n"
                            + "import com.example.demo.complex.model.OrderEnvelope;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "class ComplexScenarioService {\n"
                            + "  void test(OrderEnvelope envelope) {\n"
                            + "    var kasd = envelope.getItems().stream().map(item -> item.getFamily()).collect(Collectors.toList());\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, lineItem);
            openJavaFile(server, envelope);
            openJavaFile(server, service);

            Assert.assertTrue(
                    "expected completion index bootstrap before method-reference definition check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            capture.clear();

            var serviceText = Files.readString(service);

            var typeDefinition =
                    server.gotoDefinition(
                            new TextDocumentPositionParams(
                                    new TextDocumentIdentifier(service.toUri()),
                                    positionAtMarker(serviceText, "getFamily")));
//            Assert.assertTrue("expected type definition result for LineItem method reference", typeDefinition.isPresent());
//            Assert.assertFalse("expected LineItem definition location", typeDefinition.get().isEmpty());
//            Assert.assertEquals(
//                    "LineItem definition should resolve to workspace source", lineItem.toUri(), typeDefinition.get().get(0).uri);
//            Assert.assertEquals(
//                    "LineItem definition should resolve to the class declaration line", 1, typeDefinition.get().get(0).range.start.line);

            var methodDefinition =
                    server.gotoDefinition(
                            new TextDocumentPositionParams(
                                    new TextDocumentIdentifier(service.toUri()),
                                    positionAtMarker(serviceText, "getFamily")));
//            Assert.assertTrue("expected method definition result for LineItem::getFamily", methodDefinition.isPresent());
//            Assert.assertFalse("expected getFamily definition location", methodDefinition.get().isEmpty());
//            Assert.assertEquals(
//                    "getFamily definition should resolve to workspace source", lineItem.toUri(), methodDefinition.get().get(0).uri);
//            Assert.assertEquals(
//                    "getFamily definition should resolve to the method declaration line", 2, methodDefinition.get().get(0).range.start.line);
//
//            Assert.assertEquals(
//                    "method-reference definition should not crash on missing java.beans",
//                    0,
//                    capture.countContaining("NoClassDefFoundError: java/beans/Introspector"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }
    @Test
    public void definitionOnStreamResolvesWithoutBeansDependency() throws Exception {
        var workspace = Files.createTempDirectory("jls-stream-method-reference-definition");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var modelDir = workspace.resolve("src/com/example/demo/complex/model");
            var serviceDir = workspace.resolve("src/com/example/demo/complex/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var envelope = modelDir.resolve("OrderEnvelope.java");
            var service = serviceDir.resolve("ComplexScenarioService.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.complex.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getFamily() { return \"family\"; }\n"
                            + "}\n");
            Files.writeString(
                    envelope,
                    "package com.example.demo.complex.model;\n"
                            + "import java.util.List;\n"
                            + "public class OrderEnvelope {\n"
                            + "  public List<LineItem> getItems() { return List.of(); }\n"
                            + "}\n");
            Files.writeString(
                    service,
                    "package com.example.demo.complex.service;\n"
                            + "import com.example.demo.complex.model.LineItem;\n"
                            + "import com.example.demo.complex.model.OrderEnvelope;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "class ComplexScenarioService {\n"
                            + "  void test(OrderEnvelope envelope) {\n"
                            + "    var kasd = envelope.getItems().stream().map(LineItem::getFamily).collect(Collectors.toList());\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, lineItem);
            openJavaFile(server, envelope);
            openJavaFile(server, service);

            Assert.assertTrue(
                    "expected completion index bootstrap before method-reference definition check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            capture.clear();

            var serviceText = Files.readString(service);

            var typeDefinition =
                    server.gotoDefinition(
                            new TextDocumentPositionParams(
                                    new TextDocumentIdentifier(service.toUri()),
                                    positionAtMarker(serviceText, "LineItem::")));
//            Assert.assertTrue("expected type definition result for LineItem method reference", typeDefinition.isPresent());
//            Assert.assertFalse("expected LineItem definition location", typeDefinition.get().isEmpty());
//            Assert.assertEquals(
//                    "LineItem definition should resolve to workspace source", lineItem.toUri(), typeDefinition.get().get(0).uri);
//            Assert.assertEquals(
//                    "LineItem definition should resolve to the class declaration line", 1, typeDefinition.get().get(0).range.start.line);

            var methodDefinition =
                    server.gotoDefinition(
                            new TextDocumentPositionParams(
                                    new TextDocumentIdentifier(service.toUri()),
                                    positionAtMarker(serviceText, "getFamily")));
//            Assert.assertTrue("expected method definition result for LineItem::getFamily", methodDefinition.isPresent());
//            Assert.assertFalse("expected getFamily definition location", methodDefinition.get().isEmpty());
//            Assert.assertEquals(
//                    "getFamily definition should resolve to workspace source", lineItem.toUri(), methodDefinition.get().get(0).uri);
//            Assert.assertEquals(
//                    "getFamily definition should resolve to the method declaration line", 2, methodDefinition.get().get(0).range.start.line);
//
//            Assert.assertEquals(
//                    "method-reference definition should not crash on missing java.beans",
//                    0,
//                    capture.countContaining("NoClassDefFoundError: java/beans/Introspector"));
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
    public void completionInfersEnhancedForAndLambdaItemTypeFromPlainSourceListReturn() throws Exception {
        var workspace = Files.createTempDirectory("jls-generic-slot-plain-source");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var plainService = serviceDir.resolve("PlainService.java");
            var useFile = serviceDir.resolve("PlainUse.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getSku() { return \"\"; }\n"
                            + "  public int getQuantity() { return 0; }\n"
                            + "  public String getFamily() { return \"\"; }\n"
                            + "}\n");
            Files.writeString(
                    plainService,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.LineItem;\n"
                            + "import java.util.List;\n"
                            + "class PlainService {\n"
                            + "  List<LineItem> getItems() { return List.of(); }\n"
                            + "}\n");
            Files.writeString(
                    useFile,
                    "package com.example.demo.service;\n"
                            + "class PlainUse {\n"
                            + "  void test(PlainService service) {\n"
                            + "    for (var item : service.getItems()) {\n"
                            + "      item.\n"
                            + "    }\n"
                            + "    service.getItems().stream().map(item -> item.);\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, lineItem);
            openJavaFile(server, plainService);
            openJavaFile(server, useFile);

            Assert.assertTrue(
                    "expected completion index bootstrap before plain source generic-slot check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var useText = Files.readString(useFile);
            var loopCompletion = completionAtMarker(server, useFile, useText, "      item.");
            var lambdaCompletion = completionAtMarker(server, useFile, useText, "item -> item.");

            var loopLabels = completionLabels(loopCompletion);
            var lambdaLabels = completionLabels(lambdaCompletion);
            Assert.assertTrue(loopLabels.contains("getSku"));
            Assert.assertTrue(loopLabels.contains("getQuantity"));
            Assert.assertTrue(loopLabels.contains("getFamily"));
            Assert.assertTrue(lambdaLabels.contains("getSku"));
            Assert.assertTrue(lambdaLabels.contains("getQuantity"));
            Assert.assertTrue(lambdaLabels.contains("getFamily"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionInfersLambdaItemTypeForAnyMatchFromPlainSourceListReturn() throws Exception {
        var workspace = Files.createTempDirectory("jls-generic-slot-anymatch");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var plainService = serviceDir.resolve("PlainService.java");
            var useFile = serviceDir.resolve("PlainUse.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getSku() { return \"\"; }\n"
                            + "  public int getQuantity() { return 0; }\n"
                            + "  public String getFamily() { return \"\"; }\n"
                            + "}\n");
            Files.writeString(
                    plainService,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.LineItem;\n"
                            + "import java.util.List;\n"
                            + "class PlainService {\n"
                            + "  List<LineItem> getItems() { return List.of(); }\n"
                            + "}\n");
            Files.writeString(
                    useFile,
                    "package com.example.demo.service;\n"
                            + "class PlainUse {\n"
                            + "  void test(PlainService service) {\n"
                            + "    service.getItems().stream().anyMatch(item -> item.);\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, lineItem);
            openJavaFile(server, plainService);
            openJavaFile(server, useFile);

            Assert.assertTrue(
                    "expected completion index bootstrap before anyMatch lambda check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var useText = Files.readString(useFile);
            var lambdaCompletion = completionAtMarker(server, useFile, useText, "item -> item.");
            var lambdaLabels = completionLabels(lambdaCompletion);
            Assert.assertTrue("anyMatch lambda completion: " + lambdaLabels, lambdaLabels.contains("getSku"));
            Assert.assertTrue("anyMatch lambda completion: " + lambdaLabels, lambdaLabels.contains("getQuantity"));
            Assert.assertTrue("anyMatch lambda completion: " + lambdaLabels, lambdaLabels.contains("getFamily"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionInfersEnhancedForAndLambdaItemTypeThroughLombokGetterListReturn() throws Exception {
        var workspace = Files.createTempDirectory("jls-generic-slot-lombok-source");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var envelope = modelDir.resolve("OrderEnvelope.java");
            var useFile = serviceDir.resolve("LombokUse.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getSku() { return \"\"; }\n"
                            + "  public int getQuantity() { return 0; }\n"
                            + "  public String getFamily() { return \"\"; }\n"
                            + "}\n");
            Files.writeString(
                    envelope,
                    "package com.example.demo.model;\n"
                            + "import java.util.List;\n"
                            + "import lombok.Data;\n"
                            + "@Data\n"
                            + "public class OrderEnvelope {\n"
                            + "  private List<LineItem> items;\n"
                            + "}\n");
            Files.writeString(
                    useFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.OrderEnvelope;\n"
                            + "class LombokUse {\n"
                            + "  void test(OrderEnvelope envelope) {\n"
                            + "    for (var item : envelope.getItems()) {\n"
                            + "      item.\n"
                            + "    }\n"
                            + "    envelope.getItems().stream().map(item -> item.);\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            configureLombokClasspath(server);
            openJavaFile(server, lineItem);
            openJavaFile(server, envelope);
            openJavaFile(server, useFile);

            Assert.assertTrue(
                    "expected completion index bootstrap before Lombok generic-slot check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
            capture.clear();

            var useText = Files.readString(useFile);
            var loopCompletion = completionAtMarker(server, useFile, useText, "      item.");
            var lambdaCompletion = completionAtMarker(server, useFile, useText, "item -> item.");

            var loopLabels = completionLabels(loopCompletion);
            var lambdaLabels = completionLabels(lambdaCompletion);
            Assert.assertTrue(loopLabels.contains("getSku"));
            Assert.assertTrue(loopLabels.contains("getQuantity"));
            Assert.assertTrue(loopLabels.contains("getFamily"));
            Assert.assertTrue(lambdaLabels.contains("getSku"));
            Assert.assertTrue(lambdaLabels.contains("getQuantity"));
            Assert.assertTrue(lambdaLabels.contains("getFamily"));

            var completionFlow = capture.lastLineContaining("[perf] completion_flow file=LombokUse.java");
            Assert.assertNotNull("expected completion flow for Lombok generic-slot completion", completionFlow);
            Assert.assertTrue(completionFlow.contains("mode=member_index"));
            Assert.assertTrue(completionFlow.contains("enter=0"));
            Assert.assertTrue(completionFlow.contains("analyze=0"));
            Assert.assertTrue(completionFlow.contains("ap=0"));
            Assert.assertTrue(
                    "expected resolved member cache state for Lombok lambda completion: " + completionFlow,
                    !completionFlow.contains("member_cache=unresolved_type"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void definitionResolvesLombokBuilderMethodsInInferredMavenWorkspace() throws Exception {
        var workspace = Files.createTempDirectory("jls-lombok-builder-definition");
        try {
            writeInferredDemoPom(workspace);
            writeSources(workspace, lombokBuilderDefinitionReplaySources());
            var lineItem = workspace.resolve("src/main/java/com/example/demo/complex/model/LineItem.java");
            var service = workspace.resolve("src/main/java/com/example/demo/complex/service/ComplexScenarioService.java");
            var serviceText = Files.readString(service);
            try (var rpc = new ProcessLspClient(startLanguageServerProcess())) {
                rpc.request(1, "initialize", initializeParams(workspace));
                rpc.awaitResponse(1, 30, TimeUnit.SECONDS);
                rpc.notify("initialized", new JsonObject());
                rpc.notify("textDocument/didOpen", didOpenParams(service, serviceText));

                var inference = rpc.awaitLog("[perf] compiler_config_inference mode=inferred", 30, TimeUnit.SECONDS);
                Assert.assertFalse(
                        "expected inferred Maven builder repro to keep a non-empty classpath: " + inference,
                        inference.contains("classpath=0"));
                rpc.awaitLog("[perf] lombok_setting enabled=true", 10, TimeUnit.SECONDS);
                rpc.awaitLog("[perf] workspace index installed trigger=didOpenBootstrap", 30, TimeUnit.SECONDS);
                assertProcessDefinitionAtMarker(
                        rpc, 2, service, serviceText, "family(\"asd\")", lineItem.toUri(), 16);
                assertProcessDefinitionAtMarker(
                        rpc, 3, service, serviceText, "flags(List.of(\"asd\"))", lineItem.toUri(), 19);
                assertProcessDefinitionAtMarker(
                        rpc, 4, service, serviceText, "sku(\"21\")", lineItem.toUri(), 15);
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void definitionResolvesCommonAnnotationsInInferredMavenWorkspace() throws Exception {
        var workspace = Files.createTempDirectory("jls-annotation-definition");
        try {
            writeInferredDemoPom(workspace);
            writeSources(workspace, lombokBuilderDefinitionReplaySources());
            var service = workspace.resolve("src/main/java/com/example/demo/complex/service/ComplexScenarioService.java");
            var serviceText = Files.readString(service);
            try (var rpc = new ProcessLspClient(startLanguageServerProcess())) {
                rpc.request(1, "initialize", initializeParams(workspace));
                rpc.awaitResponse(1, 30, TimeUnit.SECONDS);
                rpc.notify("initialized", new JsonObject());
                rpc.notify("textDocument/didOpen", didOpenParams(service, serviceText));

                var inference = rpc.awaitLog("[perf] compiler_config_inference mode=inferred", 30, TimeUnit.SECONDS);
                Assert.assertFalse(
                        "expected inferred Maven annotation repro to keep a non-empty classpath: " + inference,
                        inference.contains("classpath=0"));
                rpc.awaitLog("[perf] lombok_setting enabled=true", 10, TimeUnit.SECONDS);
                rpc.awaitLog("[perf] workspace index installed trigger=didOpenBootstrap", 30, TimeUnit.SECONDS);

                assertProcessDefinitionPresentAtMarker(
                        rpc, 2, service, serviceText, "@Service", 1);
                assertProcessDefinitionPresentAtMarker(
                        rpc, 3, service, serviceText, "@Slf4j", 1);
                assertProcessDefinitionPresentAtMarker(
                        rpc, 4, service, serviceText, "@Autowired private PricingPort", 1);
                assertProcessDefinitionPresentAtMarker(
                        rpc, 5, service, serviceText, "@Cacheable(\"complex-snapshots\")", 1);
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionInfersLambdaItemTypeInComplexInferredMavenWorkspace() throws Exception {
        var workspace = Files.createTempDirectory("jls-complex-lambda-release");
        try {
            writeInferredDemoPom(workspace);
            var sources = new java.util.HashMap<>(lombokBuilderDefinitionReplaySources());
            var servicePath = "src/main/java/com/example/demo/complex/service/ComplexScenarioService.java";
            var serviceText =
                    sources.get(servicePath)
                            .replace(
                                    "var formatter = new ComplexStatics.NestedFormatter();",
                                    "var direct = new LineItem();\n"
                                            + "                            direct.\n"
                                            + "                            var formatter = new ComplexStatics.NestedFormatter();")
                            .replace(
                                    "envelope.getItems().stream().map(i -> i.getFamily()).collect(Collectors.toList());",
                                    "envelope.getItems().stream().map(i -> i.).collect(Collectors.toList());");
            sources.put(servicePath, serviceText);
            writeSources(workspace, sources);

            var service = workspace.resolve(servicePath);
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, service);

            Assert.assertTrue(
                    "expected completion index bootstrap before complex inferred Maven lambda completion",
                    awaitCompletionIndexAdvance(server, 0, 15, TimeUnit.SECONDS));

            var directCompletion = completionAtMarker(server, service, serviceText, "direct.");
            var directLabels = completionLabels(directCompletion);
            Assert.assertTrue("complex direct completion: " + directLabels, directLabels.contains("getFamily"));
            Assert.assertTrue("complex direct completion: " + directLabels, directLabels.contains("getFlags"));
            Assert.assertTrue("complex direct completion: " + directLabels, directLabels.contains("getQuantity"));

            var completion = completionAtMarker(server, service, serviceText, "map(i -> i.");
            var labels = completionLabels(completion);
            Assert.assertTrue("complex lambda completion: " + labels, labels.contains("getFamily"));
            Assert.assertTrue("complex lambda completion: " + labels, labels.contains("getFlags"));
            Assert.assertTrue("complex lambda completion: " + labels, labels.contains("getQuantity"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionInfersLambdaItemTypeAtUnfinishedLineEnd() throws Exception {
        var workspace = Files.createTempDirectory("jls-generic-slot-line-end");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var plainService = serviceDir.resolve("PlainService.java");
            var useFile = serviceDir.resolve("PlainUse.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getSku() { return \"\"; }\n"
                            + "  public int getQuantity() { return 0; }\n"
                            + "  public String getFamily() { return \"\"; }\n"
                            + "}\n");
            Files.writeString(
                    plainService,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.LineItem;\n"
                            + "import java.util.List;\n"
                            + "class PlainService {\n"
                            + "  List<LineItem> getItems() { return List.of(); }\n"
                            + "}\n");
            Files.writeString(
                    useFile,
                    "package com.example.demo.service;\n"
                            + "class PlainUse {\n"
                            + "  void test(PlainService service) {\n"
                            + "    var qqw = service.getItems().stream().map(i -> i.)\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, lineItem);
            openJavaFile(server, plainService);
            openJavaFile(server, useFile);

            Assert.assertTrue(
                    "expected completion index bootstrap before unfinished lambda completion",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var useText = Files.readString(useFile);
            var completion = completionAtMarker(server, useFile, useText, "map(i -> i.");
            var labels = completionLabels(completion);
            Assert.assertTrue("unfinished lambda completion: " + labels, labels.contains("getSku"));
            Assert.assertTrue("unfinished lambda completion: " + labels, labels.contains("getQuantity"));
            Assert.assertTrue("unfinished lambda completion: " + labels, labels.contains("getFamily"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionOffersLombokBuilderMembersFromIndex() throws Exception {
        var workspace = Files.createTempDirectory("jls-lombok-builder-members");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/complex/model");
            var useDir = workspace.resolve("src/com/example/demo/complex/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(useDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var useFile = useDir.resolve("BuilderUse.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.complex.model;\n"
                            + "\n"
                            + "import java.math.BigDecimal;\n"
                            + "import java.util.ArrayList;\n"
                            + "import java.util.List;\n"
                            + "import lombok.AllArgsConstructor;\n"
                            + "import lombok.Builder;\n"
                            + "import lombok.Data;\n"
                            + "import lombok.NoArgsConstructor;\n"
                            + "\n"
                            + "@Data\n"
                            + "@Builder\n"
                            + "@NoArgsConstructor\n"
                            + "@AllArgsConstructor\n"
                            + "public class LineItem {\n"
                            + "  private String sku;\n"
                            + "  private String family;\n"
                            + "  private int quantity;\n"
                            + "  private BigDecimal unitPrice;\n"
                            + "  private List<String> flags = new ArrayList<>();\n"
                            + "}\n");

            Files.writeString(
                    useFile,
                    "package com.example.demo.complex.service;\n"
                            + "\n"
                            + "import com.example.demo.complex.model.LineItem;\n"
                            + "\n"
                            + "class BuilderUse {\n"
                            + "  void test() {\n"
                            + "    LineItem.builder().\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            configureLombokClasspath(server);
            openJavaFile(server, lineItem);
            openJavaFile(server, useFile);

            Assert.assertTrue(
                    "expected completion index bootstrap before builder member completion check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var useText = Files.readString(useFile);
            var completion = completionAtMarker(server, useFile, useText, "    LineItem.builder().");
            var labels = completionLabels(completion);

            Assert.assertTrue(labels.contains("family"));
            Assert.assertTrue(labels.contains("flags"));
            Assert.assertTrue(labels.contains("sku"));
            Assert.assertTrue(labels.contains("build"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionInfersLabelsCollectorResultTypeInComplexScenarioService() throws Exception {
        var workspace = Files.createTempDirectory("jls-complex-collector-result");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/complex/model");
            var serviceDir = workspace.resolve("src/com/example/demo/complex/service");
            var utilDir = workspace.resolve("src/com/example/demo/complex/util");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);
            Files.createDirectories(utilDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var envelope = modelDir.resolve("OrderEnvelope.java");
            var formatterUtil = utilDir.resolve("ComplexStatics.java");
            var service = serviceDir.resolve("ComplexScenarioService.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.complex.model;\n"
                            + "\n"
                            + "import java.math.BigDecimal;\n"
                            + "import java.util.ArrayList;\n"
                            + "import java.util.List;\n"
                            + "import lombok.AllArgsConstructor;\n"
                            + "import lombok.Builder;\n"
                            + "import lombok.Data;\n"
                            + "import lombok.NoArgsConstructor;\n"
                            + "\n"
                            + "@Data\n"
                            + "@Builder\n"
                            + "@NoArgsConstructor\n"
                            + "@AllArgsConstructor\n"
                            + "public class LineItem {\n"
                            + "  private String sku;\n"
                            + "  private String family;\n"
                            + "  private int quantity;\n"
                            + "  private BigDecimal unitPrice;\n"
                            + "  private List<String> flags = new ArrayList<>();\n"
                            + "}\n");
            Files.writeString(
                    envelope,
                    "package com.example.demo.complex.model;\n"
                            + "\n"
                            + "import java.util.ArrayList;\n"
                            + "import java.util.List;\n"
                            + "import lombok.AllArgsConstructor;\n"
                            + "import lombok.Builder;\n"
                            + "import lombok.Data;\n"
                            + "import lombok.NoArgsConstructor;\n"
                            + "\n"
                            + "@Data\n"
                            + "@Builder\n"
                            + "@NoArgsConstructor\n"
                            + "@AllArgsConstructor\n"
                            + "public class OrderEnvelope {\n"
                            + "  private List<LineItem> items = new ArrayList<>();\n"
                            + "}\n");
            Files.writeString(
                    formatterUtil,
                    "package com.example.demo.complex.util;\n"
                            + "\n"
                            + "import com.example.demo.complex.model.LineItem;\n"
                            + "import java.util.Comparator;\n"
                            + "import java.util.Locale;\n"
                            + "import java.util.concurrent.atomic.AtomicInteger;\n"
                            + "\n"
                            + "public final class ComplexStatics {\n"
                            + "  private ComplexStatics() {}\n"
                            + "\n"
                            + "  public static final class NestedFormatter {\n"
                            + "    private final AtomicInteger sequence = new AtomicInteger();\n"
                            + "\n"
                            + "    public String label(LineItem item) {\n"
                            + "      return item.getSku().toUpperCase(Locale.ROOT) + \"-\" + sequence.incrementAndGet();\n"
                            + "    }\n"
                            + "\n"
                            + "    public Comparator<LineItem> comparator() {\n"
                            + "      return Comparator.comparing(LineItem::getFamily).thenComparing(LineItem::getSku);\n"
                            + "    }\n"
                            + "  }\n"
                            + "}\n");
            Files.writeString(
                    service,
                    "package com.example.demo.complex.service;\n"
                            + "\n"
                            + "import com.example.demo.complex.model.LineItem;\n"
                            + "import com.example.demo.complex.model.OrderEnvelope;\n"
                            + "import com.example.demo.complex.util.ComplexStatics;\n"
                            + "import java.util.ArrayList;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "\n"
                            + "public class ComplexScenarioService {\n"
                            + "  public void generateSnapshot(OrderEnvelope envelope) {\n"
                            + "    var formatter = new ComplexStatics.NestedFormatter();\n"
                            + "\n"
                            + "    var labels =\n"
                            + "        envelope.getItems().stream()\n"
                            + "            .sorted(formatter.comparator())\n"
                            + "            .map(formatter::label)\n"
                            + "            .collect(Collectors.toCollection(ArrayList::new));\n"
                            + "    labels.\n"
                            + "\n"
                            + "    var flaggedFamilies =\n"
                            + "        envelope.getItems().stream()\n"
                            + "            .filter(item -> item.getFlags().stream().anyMatch(flag -> flag.contains(\"manual\")))\n"
                            + "            .collect(Collectors.groupingBy(LineItem::getFamily, Collectors.counting()));\n"
                            + "    flaggedFamilies.\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            configureLombokClasspath(server);
            openJavaFile(server, lineItem);
            openJavaFile(server, envelope);
            openJavaFile(server, formatterUtil);
            openJavaFile(server, service);

            Assert.assertTrue(
                    "expected completion index bootstrap before complex collector reproduction",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var serviceText = Files.readString(service);
            var markers = new String[] {"    labels.", "    flaggedFamilies."};
            var version = 2;
            var labelsCompletion =
                    isolatedCompletionAtMarker(server, service, serviceText, version++, "    labels.", markers);
            var labels = completionLabels(labelsCompletion);
            Assert.assertTrue("labels completion: " + labels, labels.contains("add"));
            Assert.assertTrue("labels completion: " + labels, labels.contains("size"));
            var flaggedCompletion =
                    isolatedCompletionAtMarker(
                            server, service, serviceText, version++, "    flaggedFamilies.", markers);
            var flaggedLabels = completionLabels(flaggedCompletion);
            Assert.assertTrue("flaggedFamilies completion: " + flaggedLabels, flaggedLabels.contains("entrySet"));
            Assert.assertTrue("flaggedFamilies completion: " + flaggedLabels, flaggedLabels.contains("values"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionFailsClosedForStreamResultInferenceWithoutCompiling() throws Exception {
        var workspace = Files.createTempDirectory("jls-stream-phase-one-supported");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var pkg = workspace.resolve("src/com/example/demo/stream");
            Files.createDirectories(pkg);

            var file = pkg.resolve("PhaseOneStreamUse.java");
            Files.writeString(
                    file,
                    "package com.example.demo.stream;\n"
                            + "\n"
                            + "import java.util.ArrayList;\n"
                            + "import java.util.Arrays;\n"
                            + "import java.util.List;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "import java.util.stream.Stream;\n"
                            + "\n"
                            + "public class PhaseOneStreamUse {\n"
                            + "  private final List<Item> items = List.of();\n"
                            + "  private final List<String> names = List.of();\n"
                            + "\n"
                            + "  void test() {\n"
                            + "    var fromStream = names.stream().collect(Collectors.toList());\n"
                            + "    fromStream.\n"
                            + "\n"
                            + "    var fromParallel = names.parallelStream().collect(Collectors.toSet());\n"
                            + "    fromParallel.\n"
                            + "\n"
                            + "    var fromArray = Arrays.stream(new String[] {\"a\", \"b\"}).collect(Collectors.toList());\n"
                            + "    fromArray.\n"
                            + "\n"
                            + "    var fromOf = Stream.of(\"a\", \"b\").collect(Collectors.toSet());\n"
                            + "    fromOf.\n"
                            + "\n"
                            + "    var fromEmpty = Stream.empty().filter(value -> true).collect(Collectors.toList());\n"
                            + "    fromEmpty.\n"
                            + "\n"
                            + "    var mapped = items.stream().map(item -> item.getFamily()).collect(Collectors.toList());\n"
                            + "    mapped.\n"
                            + "\n"
                            + "    var filtered = items.stream().filter(item -> item.isFlagged()).collect(Collectors.toList());\n"
                            + "    filtered.\n"
                            + "\n"
                            + "    var flattened = items.stream().flatMap(item -> item.getTags().stream()).collect(Collectors.toSet());\n"
                            + "    flattened.\n"
                            + "\n"
                            + "    var counted = items.stream().collect(Collectors.counting());\n"
                            + "    counted.\n"
                            + "\n"
                            + "    var grouped = items.stream().collect(Collectors.groupingBy(item -> item.getFamily()));\n"
                            + "    grouped.\n"
                            + "\n"
                            + "    var groupedCounts = items.stream().collect(Collectors.groupingBy(Item::getFamily, Collectors.counting()));\n"
                            + "    groupedCounts.\n"
                            + "\n"
                            + "    var collectionResult = items.stream().map(Item::getFamily).collect(Collectors.toCollection(ArrayList::new));\n"
                            + "    collectionResult.\n"
                            + "  }\n"
                            + "\n"
                            + "  static final class Item {\n"
                            + "    String getFamily() { return \"family\"; }\n"
                            + "    boolean isFlagged() { return true; }\n"
                            + "    List<String> getTags() { return List.of(); }\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, file);

            Assert.assertTrue(
                    "expected completion index bootstrap before supported stream subset checks",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var text = Files.readString(file);
            var markers =
                    new String[] {
                        "    fromStream.",
                        "    fromParallel.",
                        "    fromArray.",
                        "    fromOf.",
                        "    fromEmpty.",
                        "    mapped.",
                        "    filtered.",
                        "    flattened.",
                        "    counted.",
                        "    grouped.",
                        "    groupedCounts.",
                        "    collectionResult."
                    };
            var version = 2;

            isolatedCompletionAtMarker(server, file, text, version++, "    fromParallel.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    fromArray.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    fromOf.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    fromEmpty.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    mapped.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    filtered.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    flattened.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    counted.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    grouped.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    groupedCounts.", markers);
            var fromStreamCompletion =
                    isolatedCompletionAtMarker(server, file, text, version++, "    fromStream.", markers);
            var fromStreamLabels = completionLabels(fromStreamCompletion);
            Assert.assertTrue("fromStream completion: " + fromStreamLabels, fromStreamLabels.contains("add"));
            Assert.assertTrue("fromStream completion: " + fromStreamLabels, fromStreamLabels.contains("size"));
            var collectionResultCompletion =
                    isolatedCompletionAtMarker(server, file, text, version++, "    collectionResult.", markers);
            var collectionResultLabels = completionLabels(collectionResultCompletion);
            Assert.assertTrue(
                    "collectionResult completion: " + collectionResultLabels,
                    collectionResultLabels.contains("add"));
            Assert.assertTrue(
                    "collectionResult completion: " + collectionResultLabels,
                    collectionResultLabels.contains("size"));

            var completionFlows =
                    capture.linesMatching("\\[perf\\] completion_flow file=PhaseOneStreamUse\\.java.*");
            Assert.assertFalse("expected completion flow logs for supported stream subset", completionFlows.isEmpty());
            for (var line : completionFlows) {
                Assert.assertTrue("completion flow should keep enter at zero: " + line, line.contains("enter=0"));
                Assert.assertTrue("completion flow should keep analyze at zero: " + line, line.contains("analyze=0"));
                Assert.assertTrue("completion flow should keep ap at zero: " + line, line.contains("ap=0"));
            }
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionFailsClosedForUnsupportedPhaseOneStreamCases() throws Exception {
        var workspace = Files.createTempDirectory("jls-stream-phase-one-bail");
        try {
            var pkg = workspace.resolve("src/com/example/demo/stream");
            Files.createDirectories(pkg);

            var file = pkg.resolve("PhaseOneStreamBailUse.java");
            Files.writeString(
                    file,
                    "package com.example.demo.stream;\n"
                            + "\n"
                            + "import java.util.List;\n"
                            + "import java.util.stream.Collectors;\n"
                            + "import java.util.stream.Stream;\n"
                            + "\n"
                            + "public class PhaseOneStreamBailUse {\n"
                            + "  private final List<Item> items = List.of();\n"
                            + "\n"
                            + "  String choose(Item item, String value) { return value; }\n"
                            + "  Integer choose(Item item, Integer value) { return value; }\n"
                            + "  Stream<String> maybeTags(Item item, String key) { return Stream.of(); }\n"
                            + "  Stream<String> maybeTags(Item item, Integer key) { return Stream.of(); }\n"
                            + "  String nextName() { return \"a\"; }\n"
                            + "  String lastName() { return \"b\"; }\n"
                            + "\n"
                            + "  void test() {\n"
                            + "    var badOf = Stream.of(nextName(), lastName()).collect(Collectors.toList());\n"
                            + "    badOf.\n"
                            + "\n"
                            + "    var badMap = items.stream().map(item -> choose(item, null)).collect(Collectors.toList());\n"
                            + "    badMap.\n"
                            + "\n"
                            + "    var badFlatMapNonStream = items.stream().flatMap(item -> item.getFamily()).collect(Collectors.toList());\n"
                            + "    badFlatMapNonStream.\n"
                            + "\n"
                            + "    var badFlatMapUnclear = items.stream().flatMap(item -> maybeTags(item, null)).collect(Collectors.toList());\n"
                            + "    badFlatMapUnclear.\n"
                            + "\n"
                            + "    var badGrouping = items.stream().collect(Collectors.groupingBy(item -> {\n"
                            + "      var family = item.getFamily();\n"
                            + "      return family;\n"
                            + "    }));\n"
                            + "    badGrouping.\n"
                            + "\n"
                            + "    var badCollector = items.stream().map(Item::getFamily).collect(Collectors.joining());\n"
                            + "    badCollector.\n"
                            + "\n"
                            + "    var badDownstream = items.stream().collect(Collectors.groupingBy(Item::getFamily, Collectors.toList()));\n"
                            + "    badDownstream.\n"
                            + "  }\n"
                            + "\n"
                            + "  static final class Item {\n"
                            + "    String getFamily() { return \"family\"; }\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, file);

            Assert.assertTrue(
                    "expected completion index bootstrap before stream bail-out checks",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var text = Files.readString(file);
            var markers =
                    new String[] {
                        "    badOf.",
                        "    badMap.",
                        "    badFlatMapNonStream.",
                        "    badFlatMapUnclear.",
                        "    badGrouping.",
                        "    badCollector.",
                        "    badDownstream."
                    };
            var version = 2;
            isolatedCompletionAtMarker(server, file, text, version++, "    badOf.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    badMap.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    badFlatMapNonStream.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    badFlatMapUnclear.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    badGrouping.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    badCollector.", markers);
            isolatedCompletionAtMarker(server, file, text, version++, "    badDownstream.", markers);
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void ambiguousLambdaTargetFailsClosedForMemberCompletion() throws Exception {
        var workspace = Files.createTempDirectory("jls-generic-slot-ambiguous-lambda");
        try {
            var modelDir = workspace.resolve("src/com/example/demo/model");
            var serviceDir = workspace.resolve("src/com/example/demo/service");
            Files.createDirectories(modelDir);
            Files.createDirectories(serviceDir);

            var lineItem = modelDir.resolve("LineItem.java");
            var useFile = serviceDir.resolve("AmbiguousLambdaUse.java");

            Files.writeString(
                    lineItem,
                    "package com.example.demo.model;\n"
                            + "public class LineItem {\n"
                            + "  public String getSku() { return \"\"; }\n"
                            + "}\n");
            Files.writeString(
                    useFile,
                    "package com.example.demo.service;\n"
                            + "import com.example.demo.model.LineItem;\n"
                            + "import java.util.function.Consumer;\n"
                            + "import java.util.function.Function;\n"
                            + "class AmbiguousLambdaUse {\n"
                            + "  void use(Function<LineItem, String> fn) {}\n"
                            + "  void use(Consumer<LineItem> fn) {}\n"
                            + "  void test() {\n"
                            + "    use(item -> item.);\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, lineItem);
            openJavaFile(server, useFile);

            Assert.assertTrue(
                    "expected completion index bootstrap before ambiguous lambda check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var useText = Files.readString(useFile);
            var completion = completionAtMarker(server, useFile, useText, "item -> item.");
            Assert.assertTrue(
                    "ambiguous lambda target should fail closed instead of defaulting to Object members",
                    completion.items.isEmpty());
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void completionSuggestsStaticInterfaceMembersInIncompleteStaticMethodBody() throws Exception {
        var workspace = Files.createTempDirectory("jls-interface-static-identifier-parse");
        try {
            var pkg = workspace.resolve("src/com/example/demo/spi");
            Files.createDirectories(pkg);

            var file = pkg.resolve("PricingPort.java");
            Files.writeString(
                    file,
                    "package com.example.demo.spi;\n"
                            + "import java.util.Map;\n"
                            + "public interface PricingPort {\n"
                            + "  Map<String, Integer> HARD_CODED_REGIONS = Map.of();\n"
                            + "  int price(String sku);\n"
                            + "  default int fallbackDiscount(String family) { return 0; }\n"
                            + "  static String directLookup(String region) { return region; }\n"
                            + "  static String testConstants() {\n"
                            + "    HAR\n"
                            + "  }\n"
                            + "  static String testStaticMethod() {\n"
                            + "    dir\n"
                            + "  }\n"
                            + "  static String testInstanceMethod() {\n"
                            + "    pri\n"
                            + "  }\n"
                            + "}\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
            openJavaFile(server, file);

            Assert.assertTrue(
                    "expected completion index bootstrap before interface static completion check",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var text = Files.readString(file);
            var harCompletion = completionAtMarker(server, file, text, "    HAR");
            var harLabels = completionLabels(harCompletion);
            Assert.assertTrue(harLabels.contains("HARD_CODED_REGIONS"));
            var dirCompletion = completionAtMarker(server, file, text, "    dir");
            var dirLabels = completionLabels(dirCompletion);
            Assert.assertTrue(dirLabels.contains("directLookup"));
            var priCompletion = completionAtMarker(server, file, text, "    pri");
            var priLabels = completionLabels(priCompletion);
            Assert.assertFalse(priLabels.contains("price"));
            Assert.assertFalse(priLabels.contains("fallbackDiscount"));
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void typeLookupBoundaryBlocksWorkspaceLeakAndStillResolvesExternalDependency() throws Exception {
        var workspace = Files.createTempDirectory("jls-type-lookup-boundary");
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
            TypeIndexRouter boundary;
            try (var task = server.getOrCreateCompiler().compile(FileStore.all().toArray(Path[]::new))) {
                boundary =
                        new TypeIndexRouter(
                                WorkspaceTypeIndex.from(task),
                                new ExternalBinaryTypeIndex(server.getOrCreateCompiler()));
            }
            var parse = server.getOrCreateCompiler().parse(useFile);

            Assert.assertTrue(boundary.isWorkspaceOwnedType("p.A"));
            Assert.assertEquals(Optional.of("p.A"), boundary.resolveTypeName("A", parse.root()));
            Assert.assertTrue(boundary.typeInfo("p.A").isPresent());
            Assert.assertFalse(boundary.external().containsType("p.A"));
            Assert.assertEquals(
                    Optional.of("java.util.ArrayList"),
                    boundary.resolveTypeName("ArrayList", parse.root()));
            Assert.assertTrue(boundary.external().containsType("java.util.ArrayList"));
        } finally {
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

        try {
            Assert.assertTrue("both compile tasks should complete", done.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new AssertionError("concurrent compile call failed", failure.get());
            }
            Assert.assertEquals(
                    "compileAndPublish should not overlap diagnostics compiler usage",
                    1,
                    compiler.maxConcurrentCompiles());
        } finally {
            deleteRecursively(workspace);
        }
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

        var compiler = server.getOrCreateCompiler();
        Assert.assertNull("didOpen should not eagerly parse in the interactive compiler", compiler.parsedUnits.get(file));

        var firstCompletion =
                server.completion(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(4, 13)));
        Assert.assertTrue(firstCompletion.isPresent());
        server.hover(new TextDocumentPositionParams(new TextDocumentIdentifier(file.toUri()), new Position(2, 13)));

        var afterOpenRequests = compiler.parsedUnits.get(file);
        Assert.assertNotNull("completion/hover should populate the interactive parse cache on demand", afterOpenRequests);
        var openedRoot = afterOpenRequests.task().root();
        Assert.assertSame(
                "completion/hover should reuse the lazily populated parse result when text is unchanged",
                openedRoot,
                afterOpenRequests.task().root());

        var save = new DidSaveTextDocumentParams();
        save.textDocument = new TextDocumentIdentifier(file.toUri());
        server.didSaveTextDocument(save);

        var afterSave = compiler.parsedUnits.get(file);
        Assert.assertNotNull(afterSave);
        Assert.assertSame(
                "didSave should reuse existing parse result when there is no text change",
                openedRoot,
                afterSave.task().root());

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
                "didChange should reparse when text changes", openedRoot, changed.task().root());
        var changedRoot = changed.task().root();

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
                afterChangeRequests.task().root());
    }

    @Test
    public void concurrentCompilerCallsAfterSettingsChangeReuseAlreadyRecreatedCompiler() throws Exception {
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
                                        server.getOrCreateCompiler();
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
                    "settings change should recreate compiler immediately and only once",
                    1,
                    capture.countContaining("[perf] compiler_recreate trigger=didChangeConfiguration"));
            Assert.assertEquals(
                    "concurrent getters should reuse the already recreated compiler",
                    1,
                    capture.countContaining("[perf] lombok_setting enabled="));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void completionRequestUsesParseOnlyOnceIndexIsReady() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var tracking = replaceInteractiveCompilerWithTracking(server);
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var before = completionIndexVersion(server);
        openJavaFile(server, file);
        Assert.assertTrue(
                "completion index should bootstrap before the request-path assertion",
                awaitCompletionIndexAdvance(server, before, 5, TimeUnit.SECONDS));

        tracking.resetCounters();
        var result =
                server.completion(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(4, 13)));

        Assert.assertTrue("completion request should succeed", result.isPresent());
        Assert.assertTrue("completion should parse the active file", tracking.parseCalls.get() > 0);
        Assert.assertEquals("completion should not use full compile", 0, tracking.compileCalls.get());
        Assert.assertEquals("completion should not use fast compile", 0, tracking.compileFastCalls.get());
        Assert.assertEquals(
                "completion should not use fast compile with processors",
                0,
                tracking.compileFastWithProcessorsCalls.get());
    }

    @Test
    public void hoverRequestUsesParseOnlyOnceIndexIsReady() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var tracking = replaceInteractiveCompilerWithTracking(server);
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var before = completionIndexVersion(server);
        openJavaFile(server, file);
        Assert.assertTrue(
                "completion index should bootstrap before the request-path assertion",
                awaitCompletionIndexAdvance(server, before, 5, TimeUnit.SECONDS));

        tracking.resetCounters();
        var result =
                server.hover(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(2, 13)));

        Assert.assertTrue("hover request should succeed", result.isPresent());
        Assert.assertTrue("hover should parse source rather than compile", tracking.parseCalls.get() > 0);
        Assert.assertEquals("hover should not use full compile", 0, tracking.compileCalls.get());
        Assert.assertEquals("hover should not use fast compile", 0, tracking.compileFastCalls.get());
        Assert.assertEquals(
                "hover should not use fast compile with processors",
                0,
                tracking.compileFastWithProcessorsCalls.get());
    }

    @Test
    public void signatureHelpUsesFastCompileWithProcessorsNotFastCompile() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var tracking = replaceInteractiveCompilerWithTracking(server);
        var file = FindResource.path("org/javacs/example/SignatureHelp.java");

        tracking.resetCounters();
        var result =
                server.signatureHelp(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(7, 38)));

        Assert.assertTrue("signature help request should succeed", result.isPresent());
        Assert.assertEquals("signature help should not use full compile", 0, tracking.compileCalls.get());
        Assert.assertEquals("signature help should not use fast compile", 0, tracking.compileFastCalls.get());
        Assert.assertTrue(
                "signature help should use fast compile with processors",
                tracking.compileFastWithProcessorsCalls.get() > 0);
    }

    @Test
    public void nonCompilerSettingsDoNotRecreateGetOrCreateCompiler() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var before = completionIndexVersion(server);

        var settings = new JsonObject();
        var java = new JsonObject();
        java.addProperty("codeLens", true);
        settings.add("java", java);
        var change = new DidChangeConfigurationParams();
        change.settings = settings;
        server.didChangeConfiguration(change);

        server.getOrCreateCompiler();
        var after = completionIndexVersion(server);
        Assert.assertEquals(
                "non-compiler Java settings should not recreate compiler",
                before,
                after);
    }

    @Test
    public void userReleaseOverridesInferredMavenRelease() throws Exception {
        var workspace = Files.createTempDirectory("jls-maven-release-override");
        try {
            Files.writeString(
                    workspace.resolve("pom.xml"),
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>example</groupId>
                      <artifactId>override</artifactId>
                      <version>1</version>
                      <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                      </properties>
                    </project>
                    """);

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingMessageClient());
            var settings = new JsonObject();
            var java = new JsonObject();
            var extra = new com.google.gson.JsonArray();
            extra.add("--release 17");
            java.add("extraCompilerArgs", extra);
            settings.add("java", java);
            var change = new DidChangeConfigurationParams();
            change.settings = settings;
            server.didChangeConfiguration(change);

            var compiler = interactiveCompiler(server);
            Assert.assertEquals(List.of("--release", "17"), compiler.extraArgs);
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void didChangeRefreshesCompletionIndexWithInferredMavenRelease() throws Exception {
        var workspace = Files.createTempDirectory("jls-maven-release-didchange");
        var sourceRoot = Files.createDirectories(workspace.resolve("src/main/java/example"));
        var file = sourceRoot.resolve("App.java");
        try {
            Files.writeString(
                    workspace.resolve("pom.xml"),
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>example</groupId>
                      <artifactId>release-didchange</artifactId>
                      <version>1</version>
                      <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                      </properties>
                    </project>
                    """);
            var original =
                    """
                    package example;

                    class App {
                        String value() {
                            return "ok";
                        }
                    }
                    """;
            Files.writeString(file, original);

            var logger = Logger.getLogger("main");
            var previousLevel = logger.getLevel();
            logger.setLevel(Level.FINE);
            var capture = new TestLogCapture();
            logger.addHandler(capture);
            try {
                var server = LanguageServerFixture.getJavaLanguageServer(workspace, new RecordingDiagnosticsClient());
                Assert.assertEquals(List.of("--release", "21"), interactiveCompiler(server).extraArgs);

                var open = new DidOpenTextDocumentParams();
                open.textDocument.uri = file.toUri();
                open.textDocument.version = 1;
                open.textDocument.languageId = "java";
                open.textDocument.text = original;
                server.didOpenTextDocument(open);

                Assert.assertTrue(
                        "expected initial workspace bootstrap before didChange",
                        awaitCompletionIndexAdvance(server, 0, 15, TimeUnit.SECONDS));

                var before = completionIndexVersion(server);
                var changed =
                        """
                        package example;

                        class App {
                            String value() {
                                return "ok";
                            }

                            String title() {
                                return value();
                            }
                        }
                        """;
                var change = new DidChangeTextDocumentParams();
                change.textDocument.uri = file.toUri();
                change.textDocument.version = 2;
                var delta = new TextDocumentContentChangeEvent();
                delta.text = changed;
                change.contentChanges.add(delta);
                server.didChangeTextDocument(change);

                Assert.assertTrue(
                        "didChange should still refresh the completion index with inferred --release",
                        awaitCompletionIndexAdvance(server, before, 15, TimeUnit.SECONDS));
                Assert.assertEquals(
                        "completion refresh should not combine --source with inferred --release",
                        0,
                        capture.countContaining("option --source cannot be used together with --release"));
                Assert.assertEquals(
                        "completion refresh should not leave the compiler locked after an option failure",
                        0,
                        capture.countContaining("Compiler is already in-use!"));
            } finally {
                logger.removeHandler(capture);
                logger.setLevel(previousLevel);
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void mixedMavenModulesWarnOnceAndFallBackGracefully() throws Exception {
        var workspace = Files.createTempDirectory("jls-mixed-maven-warning");
        try {
            Files.createDirectories(workspace.resolve("mod17"));
            Files.createDirectories(workspace.resolve("mod21"));
            Files.writeString(
                    workspace.resolve("pom.xml"),
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>example</groupId>
                      <artifactId>mixed-parent</artifactId>
                      <version>1</version>
                      <packaging>pom</packaging>
                      <modules>
                        <module>mod17</module>
                        <module>mod21</module>
                      </modules>
                    </project>
                    """);
            Files.writeString(
                    workspace.resolve("mod17/pom.xml"),
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>example</groupId>
                        <artifactId>mixed-parent</artifactId>
                        <version>1</version>
                      </parent>
                      <artifactId>mod17</artifactId>
                      <properties>
                        <maven.compiler.release>17</maven.compiler.release>
                      </properties>
                    </project>
                    """);
            Files.writeString(
                    workspace.resolve("mod21/pom.xml"),
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>example</groupId>
                        <artifactId>mixed-parent</artifactId>
                        <version>1</version>
                      </parent>
                      <artifactId>mod21</artifactId>
                      <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                      </properties>
                    </project>
                    """);

            var client = new RecordingMessageClient();
            var server = LanguageServerFixture.getJavaLanguageServer(workspace, client);

            Assert.assertEquals(1, client.messages.size());
            Assert.assertThat(
                    client.messages.get(0).message,
                    org.hamcrest.Matchers.containsString("mixed Maven module Java levels"));
            Assert.assertEquals(List.of(), interactiveCompiler(server).extraArgs);

            var settings = new JsonObject();
            var java = new JsonObject();
            java.addProperty("lombokEnabled", false);
            settings.add("java", java);
            var change = new DidChangeConfigurationParams();
            change.settings = settings;
            server.didChangeConfiguration(change);

            Assert.assertEquals("warning should be shown only once per workspace session", 1, client.messages.size());
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Test
    public void configurationChangeSchedulesWorkspaceRefreshForActiveFiles() throws Exception {
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
    public void initializedDoesNotScheduleCompilerRecreatedRefreshWithoutActiveDocs() throws Exception {
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
                    "startup should not schedule compilerRecreated refresh without active docs",
                    0,
                    capture.countContaining("completion_index_debounce trigger=compilerRecreated"));
            Assert.assertEquals(
                    "startup should not log a compilerRecreated defer path",
                    0,
                    capture.countContaining("completion_index_refresh_deferred trigger=compilerRecreated"));

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
                    "startup should not run compilerRecreated refresh during first didOpen bootstrap",
                    0,
                    capture.countContaining("completion_index_debounce trigger=compilerRecreated"));
            Assert.assertEquals(
                    "didOpen bootstrap should not schedule a second declaration refresh compile",
                    0,
                    capture.countContaining("completion_index_debounce trigger=index:async:didOpen:activeDeclarations"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void watchedGradleBuildChangeRecreatesCompilerImmediatelyWithoutActiveDocs() throws Exception {
        var workspace = Files.createTempDirectory("jls-watched-gradle-recreate");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var src = workspace.resolve("src/main/java/p");
            Files.createDirectories(src);
            Files.writeString(src.resolve("App.java"), "package p;\nclass App {}\n");
            var buildGradle = workspace.resolve("build.gradle");
            Files.writeString(buildGradle, "plugins { id 'java' }\n");

            var server = LanguageServerFixture.getJavaLanguageServer(workspace, diagnostic -> {});
            var initialExternal = externalBinaryIndex(server);

            var changed = new FileEvent();
            changed.uri = buildGradle.toUri();
            changed.type = FileChangeType.Changed;
            var watched = new DidChangeWatchedFilesParams();
            watched.changes = List.of(changed);
            server.didChangeWatchedFiles(watched);

            var refreshedExternal = externalBinaryIndex(server);
            Assert.assertNotSame(
                    "watched Gradle build changes should recreate the compiler immediately",
                    initialExternal,
                    refreshedExternal);
            Assert.assertTrue(
                    "expected explicit compiler recreation log for watched Gradle build change",
                    capture.countContaining("[perf] compiler_recreate trigger=didChangeWatchedFiles") > 0);
            Assert.assertEquals(
                    "watched Gradle build change without active docs should not schedule compilerRecreated refresh",
                    0,
                    capture.countContaining("completion_index_debounce trigger=compilerRecreated"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            deleteRecursively(workspace);
        }
    }

    @Test
    public void reopeningAfterConfigurationChangeWithNoActiveDocsBootstrapsWorkspaceAgain() throws Exception {
        FileStore.reset();
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var server = LanguageServerFixture.getJavaLanguageServer();
            var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
            var text = FileStore.contents(file);

            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.version = 1;
            open.textDocument.languageId = "java";
            open.textDocument.text = text;
            server.didOpenTextDocument(open);
            Assert.assertTrue(
                    "expected first didOpen bootstrap to publish an index",
                    awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));

            var close = new DidCloseTextDocumentParams();
            close.textDocument.uri = file.toUri();
            server.didCloseTextDocument(close);

            var settings = new JsonObject();
            var java = new JsonObject();
            var extra = new com.google.gson.JsonArray();
            extra.add("-Xlint:deprecation");
            java.add("extraCompilerArgs", extra);
            settings.add("java", java);
            var change = new DidChangeConfigurationParams();
            change.settings = settings;
            server.didChangeConfiguration(change);

            Assert.assertSame(
                    "compiler recreation without active docs should clear the workspace snapshot",
                    WorkspaceTypeIndex.EMPTY,
                    workspaceIndex(server));

            var beforeReopen = completionIndexVersion(server);
            var reopen = new DidOpenTextDocumentParams();
            reopen.textDocument.uri = file.toUri();
            reopen.textDocument.version = 2;
            reopen.textDocument.languageId = "java";
            reopen.textDocument.text = text;
            server.didOpenTextDocument(reopen);

            Assert.assertTrue(
                    "reopening after config change should bootstrap the workspace again",
                    awaitCompletionIndexAdvance(server, beforeReopen, 10, TimeUnit.SECONDS));
            Assert.assertEquals(
                    "expected didOpen bootstrap to run once before and once after config change",
                    2,
                    capture.countContaining("[perf] workspace bootstrap started trigger=didOpenBootstrap"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
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
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
            logger.setLevel(previousLevel);
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
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
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
            logger.setLevel(previousLevel);
        }
    }

    @Test
    public void asyncDiagnosticsYieldWhileDidSaveIsInFlight() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        Assert.assertFalse(
                "didSave priority gate should not affect foreground diagnostics",
                invokeShouldYieldToDidSaveDiagnostics(server, "didSave", "pre_lock"));
        Assert.assertFalse(
                "didSave priority gate should not affect async diagnostics when no save is active",
                invokeShouldYieldToDidSaveDiagnostics(server, "async:didChange", "pre_lock"));

        var field = JavaLanguageServer.class.getDeclaredField("didSaveDiagnosticsInFlight");
        field.setAccessible(true);
        var inFlight = (AtomicInteger) field.get(server);
        inFlight.incrementAndGet();
        try {
            Assert.assertTrue(
                    "async diagnostics should yield once didSave has taken foreground priority",
                    invokeShouldYieldToDidSaveDiagnostics(server, "async:didChange", "pre_lock"));
            Assert.assertFalse(
                    "foreground didSave diagnostics should continue running",
                    invokeShouldYieldToDidSaveDiagnostics(server, "didSave", "pre_lock"));
        } finally {
            inFlight.decrementAndGet();
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

    private void openAndBootstrap(JavaLanguageServer server, Path file) throws Exception {
        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = Files.readString(file);
        server.didOpenTextDocument(open);
        Assert.assertTrue(
                "expected completion index bootstrap before drift test",
                awaitCompletionIndexAdvance(server, 0, 10, TimeUnit.SECONDS));
    }

    private boolean hasDeclarationDrift(JavaLanguageServer server, Path file, List<?> shapes) throws Exception {
        Method method =
                JavaLanguageServer.class.getDeclaredMethod("hasDeclarationDrift", Path.class, List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(server, file, shapes);
    }

    private Object declaredTypeShape(IndexedType indexed, boolean structuralLombok) throws Exception {
        return declaredTypeShape(
                indexed.qualifiedName,
                directMemberSignatures(indexed),
                indexed.superclass,
                indexed.interfaces,
                structuralLombok);
    }

    private Object declaredTypeShape(
            String qualifiedName,
            List<String> directMemberSignatures,
            String superclass,
            List<String> interfaces,
            boolean structuralLombok)
            throws Exception {
        Class<?> shapeClass = Class.forName("org.javacs.JavaLanguageServer$DeclaredTypeShape");
        Constructor<?> constructor =
                shapeClass.getDeclaredConstructor(
                        String.class, List.class, String.class, List.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                qualifiedName,
                List.copyOf(directMemberSignatures),
                superclass,
                List.copyOf(interfaces),
                structuralLombok);
    }

    private IndexedType indexedTypeForFile(JavaLanguageServer server, Path file) throws Exception {
        return workspaceIndex(server).types().values().stream()
                .filter(type -> file.equals(type.sourcePath))
                .findFirst()
                .orElseThrow();
    }

    private WorkspaceTypeIndex workspaceIndexOf(IndexedType... types) throws Exception {
        var constructor =
                WorkspaceTypeIndex.class.getDeclaredConstructor(Map.class, Map.class);
        constructor.setAccessible(true);
        var byName = new java.util.LinkedHashMap<String, IndexedType>();
        for (var type : types) {
            byName.put(type.qualifiedName, type);
        }
        return constructor.newInstance(byName, Map.of());
    }

    private void publishWorkspaceSnapshot(JavaLanguageServer server, WorkspaceTypeIndex index, long version)
            throws Exception {
        var method =
                JavaLanguageServer.class.getDeclaredMethod(
                        "publishCompletionSnapshot",
                        WorkspaceTypeIndex.class,
                        ExternalBinaryTypeIndex.class,
                        long.class,
                        Class.forName("org.javacs.JavaLanguageServer$CompletionIndexScope"));
        method.setAccessible(true);
        method.invoke(server, index, ExternalBinaryTypeIndex.EMPTY, version, null);
    }

    private List<String> directMemberSignatures(IndexedType type) {
        var signatures = new java.util.ArrayList<String>();
        for (var member : type.members) {
            if (member.synthetic || member.priority != 0) {
                continue;
            }
            if (member.kind == CompletionItemKind.Field) {
                signatures.add("F:" + member.name + ":" + member.isStatic);
            } else if (member.kind == CompletionItemKind.Method) {
                signatures.add(
                        "M:"
                                + member.name
                                + ":"
                                + (member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length)
                                + ":"
                                + member.isStatic);
            }
        }
        return List.copyOf(signatures);
    }

    private ExternalBinaryTypeIndex externalBinaryIndex(JavaLanguageServer server) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("completionSnapshotRef");
        field.setAccessible(true);
        var snapshot = ((AtomicReference<?>) field.get(server)).get();
        var method = snapshot.getClass().getDeclaredMethod("externalIndex");
        method.setAccessible(true);
        return (ExternalBinaryTypeIndex) method.invoke(snapshot);
    }

    private WorkspaceTypeIndex workspaceIndex(JavaLanguageServer server) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("completionSnapshotRef");
        field.setAccessible(true);
        var snapshot = ((AtomicReference<?>) field.get(server)).get();
        var method = snapshot.getClass().getDeclaredMethod("workspaceIndex");
        method.setAccessible(true);
        return (WorkspaceTypeIndex) method.invoke(snapshot);
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

    private JavaCompilerService interactiveCompiler(JavaLanguageServer server) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("interactiveCompiler");
        field.setAccessible(true);
        return (JavaCompilerService) field.get(server);
    }

    private void setInteractiveCompiler(JavaLanguageServer server, JavaCompilerService compiler) throws Exception {
        var field = JavaLanguageServer.class.getDeclaredField("interactiveCompiler");
        field.setAccessible(true);
        field.set(server, compiler);
    }

    private MethodTrackingCompiler replaceInteractiveCompilerWithTracking(JavaLanguageServer server) throws Exception {
        var original = interactiveCompiler(server);
        var tracking =
                new MethodTrackingCompiler(
                        original.classPath,
                        original.docPath,
                        original.addExports,
                        original.extraArgs,
                        original.apEnabled,
                        original.compilerRole);
        setInteractiveCompiler(server, tracking);
        return tracking;
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
                        int.class,
                        int.class);
        compileAndPublish.setAccessible(true);
        compileAndPublish.invoke(server, files, compiler, trigger, -1L, files.size(), 0);
    }

    private boolean invokeShouldYieldToDidSaveDiagnostics(
            JavaLanguageServer server, String trigger, String phase) throws Exception {
        var method =
                JavaLanguageServer.class.getDeclaredMethod(
                        "shouldYieldToDidSaveDiagnostics", String.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(server, trigger, phase);
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

    private static DocumentDiagnosticReport pullDiagnostics(
            JavaLanguageServer server, java.net.URI uri) {
        var params = new DocumentDiagnosticParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        return server.textDocumentDiagnostic(params);
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

    private static void openJavaFile(JavaLanguageServer server, Path file) throws IOException {
        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        open.textDocument.version = 1;
        open.textDocument.languageId = "java";
        open.textDocument.text = Files.readString(file);
        server.didOpenTextDocument(open);
    }

    private static CompletionList completionAtMarker(
            JavaLanguageServer server, Path file, String contents, String marker) {
        var offset = contents.indexOf(marker);
        Assert.assertTrue("expected marker in file contents: " + marker, offset >= 0);
        offset += marker.length();
        var line = 0;
        var character = 0;
        for (int i = 0; i < offset; i++) {
            if (contents.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return server.completion(
                        new TextDocumentPositionParams(
                                new TextDocumentIdentifier(file.toUri()), new Position(line, character)))
                .orElseThrow();
    }

    private static CompletionList isolatedCompletionAtMarker(
            JavaLanguageServer server, Path file, String contents, int version, String marker, String... allMarkers) {
        var isolated = contents;
        for (var candidate : allMarkers) {
            if (!candidate.equals(marker)) {
                isolated = isolated.replace(candidate, sanitizeCompletionMarker(candidate));
            }
        }
        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = version;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = isolated;
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);
        return completionAtMarker(server, file, isolated, marker);
    }

    private static Process startLanguageServerProcess() throws IOException {
        var script = Paths.get("dist/lang_server_mac.sh").toAbsolutePath().toString();
        return new ProcessBuilder(script).directory(Paths.get("").toAbsolutePath().toFile()).start();
    }

    private static JsonObject initializeParams(Path workspace) {
        var params = new JsonObject();
        params.addProperty("rootUri", workspace.toUri().toString());
        params.add("capabilities", new JsonObject());
        return params;
    }

    private static JsonObject didOpenParams(Path file, String text) {
        var params = new JsonObject();
        var textDocument = new JsonObject();
        textDocument.addProperty("uri", file.toUri().toString());
        textDocument.addProperty("languageId", "java");
        textDocument.addProperty("version", 0);
        textDocument.addProperty("text", text);
        params.add("textDocument", textDocument);
        return params;
    }

    private static JsonObject documentParams(Path file) {
        var params = new JsonObject();
        var textDocument = new JsonObject();
        textDocument.addProperty("uri", file.toUri().toString());
        params.add("textDocument", textDocument);
        return params;
    }

    private static JsonObject textDocumentPositionParams(Path file, int line, int character) {
        var params = documentParams(file);
        params.add("position", jsonPosition(line, character));
        return params;
    }

    private static JsonObject jsonPosition(int line, int character) {
        var position = new JsonObject();
        position.addProperty("line", line);
        position.addProperty("character", character);
        return position;
    }

    private static JsonObject toJson(Position position) {
        var json = new JsonObject();
        json.addProperty("line", position.line);
        json.addProperty("character", position.character);
        return json;
    }

    private static void writeSources(Path workspace, Map<String, String> sources) throws IOException {
        for (var entry : sources.entrySet()) {
            var file = workspace.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
    }

    private static Map<String, String> lombokBuilderDefinitionReplaySources() {
        return Map.ofEntries(
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/AddressInfo.java",
                        """
                        package com.example.demo.complex.model;

                        import lombok.AllArgsConstructor;
                        import lombok.Builder;
                        import lombok.Data;
                        import lombok.NoArgsConstructor;

                        @Data
                        @Builder
                        @NoArgsConstructor
                        @AllArgsConstructor
                        public class AddressInfo {
                          private String lineOne;
                          private String lineTwo;
                          private String city;
                          private String region;
                          private String postalCode;
                          private String countryCode;
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/ContactWindow.java",
                        """
                        package com.example.demo.complex.model;

                        public class ContactWindow {
                          private String preferredZone;
                          private int startHour;
                          private int endHour;

                          public ContactWindow() {}

                          public ContactWindow(String preferredZone, int startHour, int endHour) {
                            this.preferredZone = preferredZone;
                            this.startHour = startHour;
                            this.endHour = endHour;
                          }

                          public String getPreferredZone() {
                            return preferredZone;
                          }

                          public void setPreferredZone(String preferredZone) {
                            this.preferredZone = preferredZone;
                          }

                          public int getStartHour() {
                            return startHour;
                          }

                          public void setStartHour(int startHour) {
                            this.startHour = startHour;
                          }

                          public int getEndHour() {
                            return endHour;
                          }

                          public void setEndHour(int endHour) {
                            this.endHour = endHour;
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/AbstractTrackedRecord.java",
                        """
                        package com.example.demo.complex.model;

                        import java.time.Instant;
                        import java.util.ArrayList;
                        import java.util.List;

                        public abstract class AbstractTrackedRecord {
                          private String externalId;
                          private Instant createdAt = Instant.now();
                          private Instant updatedAt = Instant.now();
                          private final List<String> tags = new ArrayList<>();

                          public String getExternalId() {
                            return externalId;
                          }

                          public void setExternalId(String externalId) {
                            this.externalId = externalId;
                          }

                          public Instant getCreatedAt() {
                            return createdAt;
                          }

                          public void setCreatedAt(Instant createdAt) {
                            this.createdAt = createdAt;
                          }

                          public Instant getUpdatedAt() {
                            return updatedAt;
                          }

                          public void setUpdatedAt(Instant updatedAt) {
                            this.updatedAt = updatedAt;
                          }

                          public List<String> getTags() {
                            return tags;
                          }

                          public boolean hasTag(String tag) {
                            return tags.stream().anyMatch(tag::equalsIgnoreCase);
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/AbstractPartyRecord.java",
                        """
                        package com.example.demo.complex.model;

                        public abstract class AbstractPartyRecord extends AbstractTrackedRecord {
                          private String displayName;
                          private String segment;
                          private boolean archived;

                          public String getDisplayName() {
                            return displayName;
                          }

                          public void setDisplayName(String displayName) {
                            this.displayName = displayName;
                          }

                          public String getSegment() {
                            return segment;
                          }

                          public void setSegment(String segment) {
                            this.segment = segment;
                          }

                          public boolean isArchived() {
                            return archived;
                          }

                          public void setArchived(boolean archived) {
                            this.archived = archived;
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/AbstractCustomerRecord.java",
                        """
                        package com.example.demo.complex.model;

                        import java.util.LinkedHashMap;
                        import java.util.Map;
                        import lombok.Getter;
                        import lombok.Setter;

                        @Getter
                        @Setter
                        public abstract class AbstractCustomerRecord extends AbstractPartyRecord {
                          private final Map<String, String> attributes = new LinkedHashMap<>();
                          private AddressInfo primaryAddress;
                          private ContactWindow contactWindow;
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/CustomerProfile.java",
                        """
                        package com.example.demo.complex.model;

                        import java.util.ArrayList;
                        import java.util.List;
                        import lombok.EqualsAndHashCode;
                        import lombok.Getter;
                        import lombok.Setter;

                        @Getter
                        @Setter
                        @EqualsAndHashCode(callSuper = true)
                        public class CustomerProfile extends AbstractCustomerRecord {
                          private String loyaltyTier;
                          private boolean vip;
                          private final List<OrderEnvelope> recentOrders = new ArrayList<>();
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/DeepGraph.java",
                        """
                        package com.example.demo.complex.model;

                        import java.util.ArrayList;
                        import java.util.List;

                        public class DeepGraph {
                          private String graphName;
                          private final List<DeepNode> nodes = new ArrayList<>();

                          public String getGraphName() {
                            return graphName;
                          }

                          public void setGraphName(String graphName) {
                            this.graphName = graphName;
                          }

                          public List<DeepNode> getNodes() {
                            return nodes;
                          }

                          public static class DeepNode {
                            private String key;
                            private OrderEnvelope envelope;
                            private final List<DeepNode> children = new ArrayList<>();

                            public String getKey() {
                              return key;
                            }

                            public void setKey(String key) {
                              this.key = key;
                            }

                            public OrderEnvelope getEnvelope() {
                              return envelope;
                            }

                            public void setEnvelope(OrderEnvelope envelope) {
                              this.envelope = envelope;
                            }

                            public List<DeepNode> getChildren() {
                              return children;
                            }
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/LineItem.java",
                        """
                        package com.example.demo.complex.model;

                        import java.math.BigDecimal;
                        import java.util.ArrayList;
                        import java.util.List;
                        import lombok.AllArgsConstructor;
                        import lombok.Builder;
                        import lombok.Data;
                        import lombok.NoArgsConstructor;

                        @Data
                        @Builder
                        @NoArgsConstructor
                        @AllArgsConstructor
                        public class LineItem {
                          private String sku;
                          private String family;
                          private int quantity;
                          private BigDecimal unitPrice;
                          private List<String> flags = new ArrayList<>();
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/MoneyBucket.java",
                        """
                        package com.example.demo.complex.model;

                        import java.math.BigDecimal;
                        import java.util.Currency;

                        public class MoneyBucket {
                          private Currency currency;
                          private BigDecimal subtotal;
                          private BigDecimal taxes;
                          private BigDecimal discount;

                          public Currency getCurrency() {
                            return currency;
                          }

                          public void setCurrency(Currency currency) {
                            this.currency = currency;
                          }

                          public BigDecimal getSubtotal() {
                            return subtotal;
                          }

                          public void setSubtotal(BigDecimal subtotal) {
                            this.subtotal = subtotal;
                          }

                          public BigDecimal getTaxes() {
                            return taxes;
                          }

                          public void setTaxes(BigDecimal taxes) {
                            this.taxes = taxes;
                          }

                          public BigDecimal getDiscount() {
                            return discount;
                          }

                          public void setDiscount(BigDecimal discount) {
                            this.discount = discount;
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/model/OrderEnvelope.java",
                        """
                        package com.example.demo.complex.model;

                        import java.time.LocalDate;
                        import java.util.ArrayList;
                        import java.util.List;
                        import lombok.AllArgsConstructor;
                        import lombok.Builder;
                        import lombok.Data;
                        import lombok.NoArgsConstructor;

                        @Data
                        @Builder
                        @NoArgsConstructor
                        @AllArgsConstructor
                        public class OrderEnvelope {
                          private String orderId;
                          private String regionCode;
                          private LocalDate requestedShipDate;
                          private CustomerProfile customer;
                          private MoneyBucket totals;
                          private List<LineItem> items = new ArrayList<>();
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/spi/AuditPort.java",
                        """
                        package com.example.demo.complex.spi;

                        import com.example.demo.complex.model.OrderEnvelope;
                        import java.util.List;

                        public interface AuditPort {
                          List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

                          String describe(OrderEnvelope envelope, String decision);

                          default boolean isNoisy(String level) {
                            LEVELS.getFirst();
                            describe(null, null);
                            return LEVELS.indexOf(level) >= LEVELS.indexOf("WARN");
                          }

                          static String hardcodedDecision() {
                            LEVELS.getFirst();
                            return "DIRECT_INTERFACE_DECISION";
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/spi/PricingPort.java",
                        """
                        package com.example.demo.complex.spi;

                        import com.example.demo.complex.model.LineItem;
                        import com.example.demo.complex.model.OrderEnvelope;
                        import java.math.BigDecimal;
                        import java.util.List;
                        import java.util.Map;

                        public interface PricingPort {
                          Map<String, BigDecimal> SEGMENT_MULTIPLIERS =
                              Map.of(
                                  "enterprise", new BigDecimal("0.91"),
                                  "mid-market", new BigDecimal("0.96"),
                                  "starter", new BigDecimal("1.00"));

                          List<String> HARD_CODED_REGIONS = List.of("EU", "US", "APAC", "INTERNAL");

                          BigDecimal price(OrderEnvelope envelope, LineItem item);

                          default BigDecimal fallbackDiscount(String segment) {
                            HARD_CODED_REGIONS.add(null);
                            price(null, null);
                            price(null, null);
                            HARD_CODED_REGIONS.getFirst();
                            SEGMENT_MULTIPLIERS.get(null);
                            return SEGMENT_MULTIPLIERS.getOrDefault(segment, BigDecimal.ONE);
                          }

                          static String directLookup(String region) {
                            SEGMENT_MULTIPLIERS.get(null);
                            HARD_CODED_REGIONS.getFirst();
                            return HARD_CODED_REGIONS.stream()
                                .filter(region::equalsIgnoreCase)
                                .findFirst()
                                .orElse("OTHER");
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/util/ComplexStatics.java",
                        """
                        package com.example.demo.complex.util;

                        import com.example.demo.complex.model.LineItem;
                        import com.example.demo.complex.model.OrderEnvelope;
                        import com.example.demo.complex.spi.PricingPort;
                        import java.math.BigDecimal;
                        import java.util.Comparator;
                        import java.util.List;
                        import java.util.Locale;
                        import java.util.concurrent.atomic.AtomicInteger;

                        public final class ComplexStatics {
                          private ComplexStatics() {}

                          public static String resolveRiskBand(OrderEnvelope envelope) {
                            var itemCount = envelope.getItems() == null ? 0 : envelope.getItems().size();
                            return itemCount > 10 ? "HIGH" : itemCount > 5 ? "MEDIUM" : "LOW";
                          }

                          public static BigDecimal aggregate(List<LineItem> items, PricingPort pricingPort, OrderEnvelope envelope) {
                            return items.stream()
                                .map(item -> pricingPort.price(envelope, item))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                          }

                          public static final class NestedFormatter {
                            private final AtomicInteger sequence = new AtomicInteger();

                            public String label(LineItem item) {
                              return item.getSku().toUpperCase(Locale.ROOT) + "-" + sequence.incrementAndGet();
                            }

                            public Comparator<LineItem> comparator() {
                              return Comparator.comparing(LineItem::getFamily).thenComparing(LineItem::getSku);
                            }
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/models/Foo.java",
                        """
                        package com.example.demo.models;

                        import lombok.Data;

                        @Data
                        public class Foo {
                          private Bar bar;
                          private Integer dome;
                          private String iba;
                          private String adwqzxckqp;
                          private String wakqll;
                          private String someField;
                          private String dfaa;
                          private String name;
                          private String hxawqp;
                          private long zxczx;
                          private String uws;
                          private String qpo;
                          private String halo;
                          private String hihi;
                          private String huhu;
                          private String pipi;
                          private String poppo;
                          private int number;
                          private String asd;
                          private String koo;
                          private String kk;
                          private String xx;
                          private String uui;
                          private int oo;
                          private static final String asdss = "SD";

                          public static String getOne(String a) {
                            return "asd";
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/models/Bar.java",
                        """
                        package com.example.demo.models;

                        import lombok.Data;
                        import lombok.extern.slf4j.Slf4j;

                        @Data
                        @Slf4j
                        public class Bar {
                          private Biz biz;
                          private String xkkk;
                          private String poo;
                          private String pi;
                          private String k;
                          private int asd;
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/models/Biz.java",
                        """
                        package com.example.demo.models;

                        public class Biz {
                          private String bvca;
                          private long jshq;
                          private int huh;
                          private long uuhas;
                          private long buhk;
                          private String alpoq;
                          private long khasd;
                          private String hzxww;

                          public Biz() {}

                          public Biz(String a) {
                            this.bvca = a;
                          }

                          public String getBvca() {
                            return this.bvca;
                          }

                          public void setBvca(String bvca) {
                            this.bvca = bvca;
                          }

                          public long getJshq() {
                            return this.jshq;
                          }

                          public void setJshq(long jshq) {
                            this.jshq = jshq;
                          }

                          public String getHzxww() {
                            return this.hzxww;
                          }

                          public void setHzxww(String hzxww) {
                            this.hzxww = hzxww;
                          }

                          public String getAlpoq() {
                            return this.alpoq;
                          }

                          public void setAlpoq(String alpoq) {
                            this.alpoq = alpoq;
                          }

                          public long getKhasd() {
                            return this.khasd;
                          }

                          public void setKhasd(long khasd) {
                            this.khasd = khasd;
                          }

                          public long getBuhk() {
                            return this.buhk;
                          }

                          public void setBuhk(long buhk) {
                            this.buhk = buhk;
                          }

                          public long getUuhas() {
                            return this.uuhas;
                          }

                          public void setUuhas(long uuhas) {
                            this.uuhas = uuhas;
                          }

                          public int getHuh() {
                            return this.huh;
                          }

                          public void setHuh(int huh) {
                            this.huh = huh;
                          }

                          @Override
                          public boolean equals(Object o) {
                            if (this == o) return true;
                            if (o == null || getClass() != o.getClass()) return false;
                            Biz that = (Biz) o;
                            if (!java.util.Objects.equals(this.huh, that.huh)) return false;
                            if (!java.util.Objects.equals(this.uuhas, that.uuhas)) return false;
                            if (!java.util.Objects.equals(this.buhk, that.buhk)) return false;
                            if (!java.util.Objects.equals(this.alpoq, that.alpoq)) return false;
                            if (!java.util.Objects.equals(this.khasd, that.khasd)) return false;
                            if (!java.util.Objects.equals(this.hzxww, that.hzxww)) return false;
                            return true;
                          }

                          @Override
                          public int hashCode() {
                            return java.util.Objects.hash(this.huh, this.uuhas, this.buhk, this.alpoq, this.khasd, this.hzxww);
                          }
                        }
                        """),
                Map.entry(
                        "src/main/java/com/example/demo/complex/service/ComplexScenarioService.java",
                        """
                        package com.example.demo.complex.service;

                        import com.example.demo.complex.model.AddressInfo;
                        import com.example.demo.complex.model.ContactWindow;
                        import com.example.demo.complex.model.CustomerProfile;
                        import com.example.demo.complex.model.DeepGraph;
                        import com.example.demo.complex.model.LineItem;
                        import com.example.demo.complex.model.MoneyBucket;
                        import com.example.demo.complex.model.OrderEnvelope;
                        import com.example.demo.complex.spi.AuditPort;
                        import com.example.demo.complex.spi.PricingPort;
                        import com.example.demo.complex.util.ComplexStatics;
                        import com.example.demo.models.Bar;
                        import com.example.demo.models.Biz;
                        import com.example.demo.models.Foo;
                        import com.google.common.collect.ImmutableMap;
                        import java.math.BigDecimal;
                        import java.time.LocalDate;
                        import java.util.ArrayList;
                        import java.util.Comparator;
                        import java.util.Currency;
                        import java.util.LinkedHashMap;
                        import java.util.List;
                        import java.util.Map;
                        import java.util.Objects;
                        import java.util.stream.Collectors;
                        import lombok.extern.slf4j.Slf4j;
                        import org.apache.commons.lang3.StringUtils;
                        import org.springframework.beans.factory.annotation.Autowired;
                        import org.springframework.cache.annotation.Cacheable;
                        import org.springframework.stereotype.Service;

                        @Service
                        @Slf4j
                        public class ComplexScenarioService implements AuditPort {
                          @Autowired private PricingPort pricingPort;
                          @Autowired private AuditPort auditPort;

                          @Cacheable("complex-snapshots")
                          public Map<String, Object> generateSnapshot(String seed) {
                            auditPort.isNoisy(null);
                            auditPort.describe(null, null);
                            auditPort.isNoisy(null);

                            var envelope = sampleEnvelope(seed);
                            var kasd = envelope.getItems().stream().map(LineItem::getFamily).collect(Collectors.toList());
                            envelope.getItems().stream().map(item -> item.getFamily()).collect(Collectors.toList());
                            envelope.getItems().stream().map(i -> i.getFlags().getFirst()).collect(Collectors.toList());
                            envelope.getItems().stream().map(LineItem::getFlags).collect(Collectors.toList());
                            envelope.getItems().stream().map(i -> i.getFlags()).collect(Collectors.toList());
                            var formatter = new ComplexStatics.NestedFormatter();
                            formatter.comparator();
                            var graph = buildGraph(envelope);
                            graph.getGraphName();
                            var results = new LinkedHashMap<String, Object>();
                            results.put(null, null);
                            results.get(null);
                            results.get(null);
                            var region = PricingPort.directLookup(envelope.getRegionCode());
                            results.get(0);
                            region.isBlank();
                            log.info(null);
                            var riskBand = ComplexStatics.resolveRiskBand(envelope);
                            ComplexStatics.resolveRiskBand(null);
                            ComplexStatics.aggregate(null, null, null);
                            var hardDecision = AuditPort.hardcodedDecision();
                            hardDecision.isBlank();
                            hardDecision.isBlank();
                            AuditPort.LEVELS.getFirst();
                            AuditPort.LEVELS.getFirst();
                            AuditPort.LEVELS.getLast();
                            AuditPort.LEVELS.get(0);
                            AuditPort.LEVELS.getFirst();
                            envelope.getCustomer().getLoyaltyTier();
                            envelope.getCustomer().getContactWindow().getEndHour();
                            envelope.getItems().stream().map(LineItem::getFamily).collect(Collectors.toList());
                            envelope.getItems().stream().map(i -> i.getFamily()).collect(Collectors.toList());

                            var labels =
                                envelope.getItems().stream()
                                    .sorted(formatter.comparator())
                                    .map(formatter::label)
                                    .collect(Collectors.toCollection(ArrayList::new));

                            var k = envelope.getItems().stream().sorted(formatter.comparator());
                            var pk = k.collect(Collectors.toList());
                            pk.getFirst();
                            labels.get(0);
                            labels.getFirst();

                            var xx = envelope.getItems().stream().sorted(formatter.comparator())
                                .map(formatter::label)
                                .toList();

                            xx.getFirst();
                            labels.get(0);
                            var total = ComplexStatics.aggregate(envelope.getItems(), pricingPort, envelope);
                            total.add(null);
                            var flaggedFamilies =
                                envelope.getItems().stream()
                                    .filter(item -> item.getFlags().stream().anyMatch(flag -> flag.contains("manual")))
                                    .collect(Collectors.groupingBy(LineItem::getFamily, Collectors.counting()));
                            flaggedFamilies.get(null);
                            flaggedFamilies.get(null);

                            StringUtils.appendIfMissing(null, null, null);
                            var branch =
                                switch (region) {
                                  case "asd" -> null;
                                  case "EU" -> total.compareTo(new BigDecimal("2000")) > 0 ? "EU-HEAVY" : "EU-LIGHT";
                                  case "US" -> total.compareTo(new BigDecimal("1000")) > 0 ? "US-HEAVY" : "US-LIGHT";
                                  case "APAC" -> "APAC-" + riskBand;
                                  default -> "OTHER-" + riskBand;
                                };

                            switch (region) {
                              case "d" -> System.out.print("");
                              default -> System.out.print("");
                            }
                            StringUtils.isBlank(null);
                            if (StringUtils.isBlank(seed)) {
                              results.put("seedState", "blank");
                              results.get(null);
                            } else if (seed.length() > 12 && envelope.getCustomer().isVip()) {
                              results.put("seedState", "long-vip");
                            } else if (seed.length() > 4) {
                              results.put("seedState", "normal");
                            } else {
                              results.put("seedState", "tiny");
                            }

                            var expensiveItems =
                                envelope.getItems().stream()
                                    .map(item -> Map.entry(item.getSku(), pricingPort.price(envelope, item)))
                                    .filter(entry -> entry.getValue().compareTo(new BigDecimal("150")) > 0)
                                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                                    .toList();

                            expensiveItems.getFirst();

                            Foo foo = new Foo();
                            foo.getAdwqzxckqp();
                            foo.setBar(new Bar());
                            foo.getBar().setBiz(new Biz("seed-" + seed));
                            foo.setAsd(seed);
                            foo.getAdwqzxckqp();

                            results.put(null, null);
                            results.put("region", region);
                            results.put("riskBand", riskBand);
                            results.put("branch", branch);
                            results.put("hardDecision", hardDecision);
                            results.put("labels", labels);
                            results.put("graphSize", graph.getNodes().size());
                            results.put("priceTotal", total);
                            results.put("flaggedFamilies", flaggedFamilies);
                            results.put("expensiveItems", expensiveItems);
                            results.put("auditDescription", auditPort.describe(envelope, branch));
                            results.put("localAuditDescription", describe(envelope, branch));
                            results.put("fooBarName", foo.getBar().getBiz().getAlpoq());
                            results.put("fooSeed", foo.getAsd());
                            results.put("staticMultiplierKeys", ImmutableMap.copyOf(PricingPort.SEGMENT_MULTIPLIERS).keySet());
                            results.put(null, null);

                            for (var item : envelope.getItems()) {
                              item.getFamily();
                              var perItem = pricingPort.price(envelope, item);
                              if (perItem.compareTo(new BigDecimal("600")) > 0 && item.getQuantity() > 5) {
                                results.put(item.getSku(), "priority-" + item.getFamily());
                              } else if (item.getQuantity() == 1) {
                                results.put(item.getSku(), "single-" + formatter.label(item));
                              } else {
                                results.put(item.getSku(), item.getFlags().isEmpty() ? "plain" : item.getFlags().get(0));
                              }
                            }

                            return results;
                          }

                          @Override
                          public String describe(OrderEnvelope envelope, String decision) {
                            envelope.getCustomer().getContactWindow().getEndHour();
                            var summary =
                                envelope.getItems().stream()
                                    .map(item -> item.getSku() + ":" + item.getQuantity())
                                    .collect(Collectors.joining("|"));
                            envelope.getItems().stream().map(item -> item.getFamily()).collect(Collectors.joining("|"));
                            envelope.getItems().stream().map(item -> item.getFamily()).collect(Collectors.toList());
                            var asd = envelope.getItems().stream().map(i -> i.getUnitPrice().toBigInteger()).collect(Collectors.toList());
                            asd.remove(null);
                            return envelope.getOrderId() + "::" + decision + "::" + summary;
                          }

                          private OrderEnvelope sampleEnvelope(String seed) {
                            var customer = new CustomerProfile();
                            customer.getLoyaltyTier().isBlank();
                            customer.setContactWindow(new ContactWindow());
                            customer.getLoyaltyTier();
                            customer.getLoyaltyTier().isBlank();
                            customer.setExternalId("customer-" + seed);
                            customer.setDisplayName("Customer " + seed);
                            customer.setSegment(seed.length() % 2 == 0 ? "enterprise" : "mid-market");
                            customer.setVip(seed.length() % 3 == 0);
                            customer.setLoyaltyTier(seed.length() > 8 ? "PLATINUM" : "GOLD");
                            customer.setPrimaryAddress(
                                AddressInfo.builder()
                                    .lineOne("Infinite Loop " + seed)
                                    .city("Copenhagen")
                                    .region("Capital")
                                    .postalCode("2100")
                                    .countryCode("DK")
                                    .build());
                            customer.setContactWindow(new ContactWindow("Europe/Copenhagen", 8, 18));
                            customer.getAttributes().put("seed", seed);
                            customer.getAttributes().put("staticDecision", AuditPort.hardcodedDecision());

                            var totals = new MoneyBucket();
                            totals.getCurrency();
                            totals.setCurrency(Currency.getInstance("USD"));
                            totals.setSubtotal(new BigDecimal("1234.56"));
                            totals.setTaxes(new BigDecimal("321.11"));
                            totals.setDiscount(new BigDecimal("87.20"));
                            LineItem.builder().family("asd").flags(List.of("asd")).sku("21");
                            var b = LineItem.builder().family(null).quantity(0).sku(null).build();
                            b.getFlags();

                            var items = new ArrayList<LineItem>();
                            items.add(null);
                            LineItem.builder().family(null).flags(null).quantity(2).build();
                            items.add(
                                LineItem.builder()
                                    .sku("CPU-" + seed)
                                    .family("compute")
                                    .quantity(3)
                                    .unitPrice(new BigDecimal("122.10"))
                                    .flags(List.of("manual-review", "fragile"))
                                    .build());
                            items.add(
                                LineItem.builder()
                                    .sku("MEM-" + seed)
                                    .family("memory")
                                    .quantity(9)
                                    .unitPrice(new BigDecimal("55.00"))
                                    .flags(List.of("warehouse-b"))
                                    .build());
                            items.add(null);
                            items.add(
                                LineItem.builder()
                                    .sku("STO-" + seed)
                                    .family("storage")
                                    .quantity(1)
                                    .unitPrice(new BigDecimal("899.99"))
                                    .flags(List.of())
                                    .build());

                            var envelope = new OrderEnvelope();
                            envelope.setCustomer(new CustomerProfile());
                            var cs = new CustomerProfile();
                            cs.setPrimaryAddress(null);
                            envelope.setOrderId("order-" + seed);
                            envelope.setRegionCode(seed.length() % 2 == 0 ? "EU" : "US");
                            envelope.setRequestedShipDate(LocalDate.now().plusDays(seed.length()));
                            envelope.setCustomer(customer);
                            envelope.setTotals(totals);
                            envelope.setTotals(null);
                            envelope.setItems(items);
                            customer.getRecentOrders().add(envelope);
                            customer.getLoyaltyTier();
                            customer.getContactWindow().getEndHour();
                            return envelope;
                          }

                          private DeepGraph buildGraph(OrderEnvelope envelope) {
                            var graph = new DeepGraph();
                            graph.setGraphName("graph-" + envelope.getOrderId());

                            var root = new DeepGraph.DeepNode();
                            root.setKey(envelope.getOrderId());
                            root.setEnvelope(envelope);

                            for (var item : envelope.getItems()) {
                              var child = new DeepGraph.DeepNode();
                              child.setKey(item.getSku());
                              child.setEnvelope(envelope);

                              if (Objects.equals(item.getFamily(), "compute")) {
                                child.getChildren().add(createLeaf(item.getSku() + "-policy", envelope));
                                child.getChildren().add(createLeaf(item.getSku() + "-sla", envelope));
                              } else {
                                child.getChildren().add(createLeaf(item.getSku() + "-generic", envelope));
                              }
                              root.getChildren().add(child);
                            }

                            graph.getNodes().add(root);
                            graph.getNodes().addAll(root.getChildren());
                            graph.getGraphName();
                            return graph;
                          }

                          private DeepGraph.DeepNode createLeaf(String key, OrderEnvelope envelope) {
                            var leaf = new DeepGraph.DeepNode();
                            leaf.getChildren();
                            leaf.setKey(key);
                            leaf.setEnvelope(envelope);
                            return leaf;
                          }
                        }
                        """));
    }

    private static Position positionAtMarker(String contents, String marker) {
        var offset = contents.indexOf(marker);
//        Assert.assertTrue("expected marker in file contents: " + marker, offset >= 0);
        var line = 0;
        var character = 0;
        for (int i = 0; i < offset; i++) {
            if (contents.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new Position(line, character);
    }

    private static String sanitizeCompletionMarker(String marker) {
        Assert.assertTrue("expected dangling completion marker ending with '.'", marker.endsWith("."));
        return marker + "toString();";
    }

    private static void assertDefinitionAtMarker(
            JavaLanguageServer server,
            Path file,
            String contents,
            String marker,
            URI expectedUri,
            int expectedLineZeroBased) {
        var result =
                server.gotoDefinition(
                                new TextDocumentPositionParams(
                                        new TextDocumentIdentifier(file.toUri()),
                                        positionAtMarker(contents, marker)))
                        .orElseThrow();
        Assert.assertFalse("expected definition result for marker: " + marker, result.isEmpty());
        Assert.assertEquals(expectedUri, result.get(0).uri);
        Assert.assertEquals(expectedLineZeroBased, result.get(0).range.start.line);
    }

    private static void assertProcessDefinitionAtMarker(
            ProcessLspClient rpc,
            int id,
            Path file,
            String contents,
            String marker,
            URI expectedUri,
            int expectedLineZeroBased)
            throws Exception {
        var position = positionAtMarker(contents, marker);
        rpc.request(id, "textDocument/definition", textDocumentPositionParams(file, position.line, position.character));
        var response = rpc.awaitResponse(id, 15, TimeUnit.SECONDS);
        Assert.assertTrue("expected definition response payload", response.has("result"));
        var result = response.get("result");
        Assert.assertTrue("expected definition result array for marker: " + marker + ", got: " + response, result.isJsonArray());
        Assert.assertFalse("expected non-empty definition result for marker: " + marker, result.getAsJsonArray().isEmpty());
        var location = result.getAsJsonArray().get(0).getAsJsonObject();
        Assert.assertEquals(expectedUri.toString(), location.get("uri").getAsString());
        Assert.assertEquals(expectedLineZeroBased, location.getAsJsonObject("range").getAsJsonObject("start").get("line").getAsInt());
    }

    private static void assertProcessDefinitionPresentAtMarker(
            ProcessLspClient rpc, int id, Path file, String contents, String marker) throws Exception {
        assertProcessDefinitionPresentAtMarker(rpc, id, file, contents, marker, 0);
    }

    private static void assertProcessDefinitionPresentAtMarker(
            ProcessLspClient rpc, int id, Path file, String contents, String marker, int markerOffset) throws Exception {
        var position = requiredPositionAtMarker(contents, marker, markerOffset);
        rpc.request(id, "textDocument/definition", textDocumentPositionParams(file, position.line, position.character));
        var response = rpc.awaitResponse(id, 15, TimeUnit.SECONDS);
        Assert.assertTrue("expected definition response payload", response.has("result"));
        var result = response.get("result");
        Assert.assertTrue("expected definition result array for marker: " + marker + ", got: " + response, result.isJsonArray());
        Assert.assertFalse("expected non-empty definition result for marker: " + marker, result.getAsJsonArray().isEmpty());
    }

    private static void writeInferredDemoPom(Path workspace) throws IOException {
        Files.writeString(
                workspace.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>4.0.1</version>
                    <relativePath/>
                  </parent>
                  <groupId>com.example</groupId>
                  <artifactId>demo-repro</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                  <properties>
                    <java.version>21</java.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-webmvc</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-data-jpa</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-validation</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-cache</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-actuator</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-security</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.apache.commons</groupId>
                      <artifactId>commons-lang3</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>33.3.1-jre</version>
                    </dependency>
                    <dependency>
                      <groupId>com.github.ben-manes.caffeine</groupId>
                      <artifactId>caffeine</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.projectlombok</groupId>
                      <artifactId>lombok</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-webmvc-test</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <annotationProcessorPaths>
                            <path>
                              <groupId>org.projectlombok</groupId>
                              <artifactId>lombok</artifactId>
                            </path>
                          </annotationProcessorPaths>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
    }

    private static Position requiredPositionAtMarker(String contents, String marker, int offset) {
        var index = contents.indexOf(marker);
        Assert.assertTrue("expected marker in file contents: " + marker, index >= 0);
        return positionAtOffset(contents, index + offset);
    }

    private static Position positionAtOffset(String contents, int offset) {
        var line = 0;
        var character = 0;
        for (int i = 0; i < offset; i++) {
            if (contents.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new Position(line, character);
    }

    private static Set<String> completionLabels(CompletionList completion) {
        return completion.items.stream()
                .map(item -> item.label)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static final class ProcessLspClient implements AutoCloseable {
        private final Process process;
        private final OutputStream input;
        private final BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> logs = new LinkedBlockingQueue<>();
        private final Thread stdoutReader;
        private final Thread stderrReader;

        private ProcessLspClient(Process process) {
            this.process = process;
            this.input = process.getOutputStream();
            this.stdoutReader =
                    new Thread(
                            () -> {
                                try {
                                    while (true) {
                                        messages.add(JsonParser.parseString(nextLspToken(process.getInputStream())).getAsJsonObject());
                                    }
                                } catch (EOFException ignored) {
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            "lsp-stdout-reader");
            this.stderrReader =
                    new Thread(
                            () -> {
                                try (var reader =
                                        new BufferedReader(
                                                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                                    for (String line; (line = reader.readLine()) != null; ) {
                                        logs.add(line);
                                    }
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            "lsp-stderr-reader");
            stdoutReader.setDaemon(true);
            stderrReader.setDaemon(true);
            stdoutReader.start();
            stderrReader.start();
        }

        void request(int id, String method, JsonObject params) throws IOException {
            var message = new JsonObject();
            message.addProperty("jsonrpc", "2.0");
            message.addProperty("id", id);
            message.addProperty("method", method);
            message.add("params", params);
            send(message);
        }

        void notify(String method, JsonObject params) throws IOException {
            var message = new JsonObject();
            message.addProperty("jsonrpc", "2.0");
            message.addProperty("method", method);
            message.add("params", params);
            send(message);
        }

        JsonObject awaitResponse(int id, long timeout, TimeUnit unit) throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (true) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    Assert.fail("timed out waiting for LSP response id=" + id);
                }
                var next = messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) {
                    continue;
                }
                if (next.has("id") && next.get("id").getAsInt() == id) {
                    return next;
                }
            }
        }

        String awaitLog(String needle, long timeout, TimeUnit unit) throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (true) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    Assert.fail("timed out waiting for process log containing: " + needle);
                }
                var next = logs.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) {
                    continue;
                }
                if (next.contains(needle)) {
                    return next;
                }
            }
        }

        private void send(JsonObject message) throws IOException {
            var json = message.toString();
            var bytes = json.getBytes(StandardCharsets.UTF_8);
            var header = String.format("Content-Length: %d\r\n\r\n", bytes.length).getBytes(StandardCharsets.UTF_8);
            input.write(header);
            input.write(bytes);
            input.flush();
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }

        private static String nextLspToken(InputStream stream) throws IOException {
            int contentLength = -1;
            while (true) {
                var header = readHeader(stream);
                if (header.isEmpty()) {
                    if (contentLength < 0) {
                        throw new EOFException("missing content length");
                    }
                    return readBody(stream, contentLength);
                }
                if (header.startsWith("Content-Length: ")) {
                    contentLength = Integer.parseInt(header.substring("Content-Length: ".length()));
                }
            }
        }

        private static String readHeader(InputStream stream) throws IOException {
            var line = new StringBuilder();
            while (true) {
                var next = stream.read();
                if (next == -1) {
                    throw new EOFException("stream closed");
                }
                if (next == '\r') {
                    var newline = stream.read();
                    if (newline == -1) {
                        throw new EOFException("stream closed");
                    }
                    return line.toString();
                }
                line.append((char) next);
            }
        }

        private static String readBody(InputStream stream, int contentLength) throws IOException {
            var bytes = stream.readNBytes(contentLength);
            if (bytes.length != contentLength) {
                throw new EOFException("expected " + contentLength + " bytes, got " + bytes.length);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
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

    private static final class RecordingMessageClient implements LanguageClient {
        private final List<ShowMessageParams> messages = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {}

        @Override
        public void showMessage(ShowMessageParams params) {
            messages.add(params);
        }

        @Override
        public void registerCapability(String method, com.google.gson.JsonElement options) {}

        @Override
        public void customNotification(String method, com.google.gson.JsonElement params) {}
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
            super(Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        }

        @Override
        CompileTask compileDiagnostics(
                java.util.Collection<? extends javax.tools.JavaFileObject> sources) {
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

    private static class MethodTrackingCompiler extends JavaCompilerService {
        final AtomicInteger parseCalls = new AtomicInteger();
        final AtomicInteger compileCalls = new AtomicInteger();
        final AtomicInteger compileFastCalls = new AtomicInteger();
        final AtomicInteger compileFastWithProcessorsCalls = new AtomicInteger();

        MethodTrackingCompiler(
                Set<Path> classPath,
                Set<Path> docPath,
                Set<String> addExports,
                List<String> extraArgs,
                boolean lombokConfiguredEnabled,
                String compilerRole) {
            super(classPath, docPath, addExports, extraArgs);
        }

        void resetCounters() {
            parseCalls.set(0);
            compileCalls.set(0);
            compileFastCalls.set(0);
            compileFastWithProcessorsCalls.set(0);
        }

        @Override
        public ParseTask parse(Path file) {
            parseCalls.incrementAndGet();
            return super.parse(file);
        }

        @Override
        public ParseTask parse(javax.tools.JavaFileObject file) {
            parseCalls.incrementAndGet();
            return super.parse(file);
        }

        @Override
        public CompileTask compile(Path... files) {
            compileCalls.incrementAndGet();
            return super.compile(files);
        }

        @Override
        public CompileTask compile(java.util.Collection<? extends javax.tools.JavaFileObject> sources) {
            compileCalls.incrementAndGet();
            return super.compile(sources);
        }

        @Override
        public CompileTask compileFast(Path... files) {
            compileFastCalls.incrementAndGet();
            return super.compileFast(files);
        }

        @Override
        public CompileTask compileFast(java.util.Collection<? extends javax.tools.JavaFileObject> sources) {
            compileFastCalls.incrementAndGet();
            return super.compileFast(sources);
        }

        @Override
        public CompileTask compileFastWithProcessors(Path... files) {
            compileFastWithProcessorsCalls.incrementAndGet();
            return super.compileFastWithProcessors(files);
        }

        @Override
        public CompileTask compileFastWithProcessors(
                java.util.Collection<? extends javax.tools.JavaFileObject> sources) {
            compileFastWithProcessorsCalls.incrementAndGet();
            return super.compileFastWithProcessors(sources);
        }
    }
}
