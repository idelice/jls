package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.javacs.completion.TypeMemberIndex;
import org.javacs.navigation.ReferenceProvider;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.DidSaveTextDocumentParams;
import org.javacs.lsp.DiagnosticSeverity;
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.Position;
import org.javacs.lsp.ReferenceParams;
import org.javacs.lsp.PublishDiagnosticsParams;
import org.javacs.lsp.Range;
import org.javacs.lsp.ShowMessageParams;
import org.javacs.lsp.TextDocumentContentChangeEvent;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentItem;
import org.javacs.lsp.TextDocumentPositionParams;
import org.junit.Before;
import org.junit.Test;

public class LspPerformanceTest {
    private static final Path MEMBER_FILE = FindResource.path("org/javacs/example/AutocompleteMember.java");
    private static final Path HELLO_FILE = FindResource.path("org/javacs/example/HelloWorld.java");
    private static final Path LOMBOK_MEMBER_FILE = FindResource.path("org/javacs/example/LombokCrossTypeCompletion.java");
    private static final Path LOMBOK_MODEL_FILE = FindResource.path("org/javacs/example/LombokCrossTypeModel.java");
    private static final Path LARGE_FILE = FindResource.path("org/javacs/example/LargeFile.java");

    @Before
    public void resetPerfCounters() {
        CompileBatch.resetPerfCounters();
    }

    @Test
    public void completionUsesReadOnlySnapshotWithoutCompilation() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        open(server, MEMBER_FILE, 1, FileStore.contents(MEMBER_FILE));
        server.lint(List.of(MEMBER_FILE));
        CompileBatch.resetPerfCounters();

        var maybe = server.completion(completionPosition(MEMBER_FILE, 5, 14));
        assertTrue(maybe.isPresent());

        var counters = CompileBatch.perfCounters();
        assertThat(counters.fullBatches, is(0L));
        assertThat(counters.analyzeInvocations, is(0L));
        assertThat(counters.apEnabledBatches, is(0L));
    }

    @Test
    public void hoverDoesNotTriggerFullCompile() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/SymbolUnderCursor.java");
        open(server, file, 1, FileStore.contents(file));
        server.lint(List.of(file));
        CompileBatch.resetPerfCounters();

        var result = server.hover(completionPosition(file, 12, 23));
        assertTrue(result.isPresent());

        var counters = CompileBatch.perfCounters();
        assertThat(counters.fullBatches, is(0L));
        assertThat(counters.analyzeInvocations, is(0L));
        assertThat(counters.apEnabledBatches, is(0L));
        assertThat(counters.analyzeInvocations, is(0L));
        assertThat(counters.apEnabledBatches, is(0L));
    }

    @Test
    public void definitionDoesNotTriggerFullCompile() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/Goto.java");
        open(server, file, 1, FileStore.contents(file));
        server.lint(List.of(file));
        CompileBatch.resetPerfCounters();

        var result = server.gotoDefinition(completionPosition(file, 17, 14));
        assertTrue(result.isPresent());

        var counters = CompileBatch.perfCounters();
        assertThat(counters.fullBatches, is(0L));
    }

    @Test
    public void referencesDoNotTriggerFullCompile() {
        var referenceContext = referenceContext(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);
        var file = FindResource.path("org/javacs/example/GotoOther.java");
        CompileBatch.resetPerfCounters();

        var result = new ReferenceProvider(referenceContext.compiler, referenceContext.index, file, 6, 30).find();
        assertTrue(!result.isEmpty());

        var counters = CompileBatch.perfCounters();
        assertThat(counters.fullBatches, is(0L));
        assertThat(counters.analyzeInvocations, is(0L));
        assertThat(counters.apEnabledBatches, is(0L));
    }

    @Test
    public void referencesInLombokProjectDoNotRunAnnotationProcessors() {
        var referenceContext = referenceContext(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT);
        CompileBatch.resetPerfCounters();

        var result =
                new ReferenceProvider(referenceContext.compiler, referenceContext.index, LOMBOK_MEMBER_FILE, 5, 25)
                        .find();
        assertTrue(!result.isEmpty());

        var counters = CompileBatch.perfCounters();
        assertThat(counters.fullBatches, is(0L));
        assertThat(counters.analyzeInvocations, is(0L));
        assertThat(counters.apEnabledBatches, is(0L));
    }

    @Test
    public void interactiveSequenceAvoidsFullCompileUntilSaveDiagnostics() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var file = FindResource.path("org/javacs/example/AutocompleteMember.java");
        var original = FileStore.contents(file);
        open(server, file, 1, original);
        server.lint(List.of(file));

        change(server, file, 2, original + "\n// interactive-sequence");

        CompileBatch.resetPerfCounters();
        server.completion(completionPosition(file, 5, 14));
        assertThat("completion should not use full compile", CompileBatch.perfCounters().fullBatches, is(0L));

        CompileBatch.resetPerfCounters();
        server.hover(completionPosition(file, 5, 14));
        assertThat("hover should not use full compile", CompileBatch.perfCounters().fullBatches, is(0L));
        assertThat("hover should not analyze", CompileBatch.perfCounters().analyzeInvocations, is(0L));

        CompileBatch.resetPerfCounters();
        server.gotoDefinition(completionPosition(file, 5, 14));
        assertThat("definition should not use full compile", CompileBatch.perfCounters().fullBatches, is(0L));
        assertThat("definition should not analyze", CompileBatch.perfCounters().analyzeInvocations, is(0L));

        CompileBatch.resetPerfCounters();
        var refs = new ReferenceParams();
        refs.textDocument = new TextDocumentIdentifier(file.toUri());
        refs.position = new Position(4, 13);
        server.findReferences(refs);
        assertThat("references should not use full compile", CompileBatch.perfCounters().fullBatches, is(0L));

        CompileBatch.resetPerfCounters();
        var save = new DidSaveTextDocumentParams();
        save.textDocument = new TextDocumentIdentifier(file.toUri());
        server.didSaveTextDocument(save);
        var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos && CompileBatch.perfCounters().fullBatches == 0L) {
            Thread.sleep(25);
        }
        assertThat(
                "save-triggered diagnostics should use full compile",
                CompileBatch.perfCounters().fullBatches > 0,
                is(true));
    }

    @Test
    public void rapidTypingCompletionLatency() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var original = FileStore.contents(MEMBER_FILE);
        open(server, MEMBER_FILE, 1, original);
        server.lint(List.of(MEMBER_FILE));

        var latencies = new ArrayList<Long>();
        var version = 2;
        for (int i = 0; i < 30; i++) {
            var updated = original + "\n// rapid-typing-" + i;
            change(server, MEMBER_FILE, version++, updated);
            latencies.add(timeCompletion(server, MEMBER_FILE, 5, 14));
        }

        assertThat(median(latencies), lessThan(30L));
    }

    @Test
    public void dotCompletionSpamLatency() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        open(server, MEMBER_FILE, 1, FileStore.contents(MEMBER_FILE));
        server.lint(List.of(MEMBER_FILE));

        var latencies = new ArrayList<Long>();
        for (int i = 0; i < 40; i++) {
            latencies.add(timeCompletion(server, MEMBER_FILE, 5, 14));
        }

        assertThat(percentile95(latencies), lessThan(30L));
    }

    @Test
    public void lombokDotCompletionSpamLatencyAfterWarmup() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        open(server, LOMBOK_MEMBER_FILE, 1, FileStore.contents(LOMBOK_MEMBER_FILE));
        server.lint(List.of(LOMBOK_MEMBER_FILE));

        var latencies = new ArrayList<Long>();
        for (int i = 0; i < 40; i++) {
            latencies.add(timeCompletion(server, LOMBOK_MEMBER_FILE, 6, 15));
        }

        assertThat(percentile95(latencies), lessThan(30L));
    }

    @Test
    public void diagnosticsRunAsyncWithoutBlockingCompletion() throws Exception {
        var client = new TrackingClient();
        var server = LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);

        var memberContents = FileStore.contents(MEMBER_FILE);
        open(server, MEMBER_FILE, 1, memberContents);
        var largeContents = FileStore.contents(LARGE_FILE);
        open(server, LARGE_FILE, 1, largeContents);

        change(server, LARGE_FILE, 2, largeContents + "\n// large-change");

        // Warm interactive completion cache before measuring overlap with diagnostics.
        timeCompletion(server, MEMBER_FILE, 5, 14);

        var maxLatency = 0L;
        for (int i = 0; i < 50; i++) {
            maxLatency = Math.max(maxLatency, timeCompletion(server, MEMBER_FILE, 5, 14));
            Thread.sleep(20);
        }

        assertTrue("expected async diagnostics to publish", client.awaitDiagnostics(10, TimeUnit.SECONDS));
        assertThat(maxLatency, lessThan(30L));
    }

    @Test
    public void repeatedLintAfterCompletionKeepsDiagnosticsAvailable() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var original = FileStore.contents(MEMBER_FILE);
        open(server, MEMBER_FILE, 1, original);
        server.lint(List.of(MEMBER_FILE));

        var first = server.completion(completionPosition(MEMBER_FILE, 5, 14));
        assertTrue(first.isPresent());
        assertTrue(!first.get().items.isEmpty());

        change(server, MEMBER_FILE, 2, original + "\n// keep-updating");
        server.lint(List.of(MEMBER_FILE));

        var second = server.completion(completionPosition(MEMBER_FILE, 5, 14));
        assertTrue(second.isPresent());
        assertTrue(!second.get().items.isEmpty());
    }

    @Test
    public void repeatedLombokLintWithTrailingDotKeepsMemberCompletion() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var original = FileStore.contents(LOMBOK_MEMBER_FILE);
        open(server, LOMBOK_MEMBER_FILE, 1, original);
        server.lint(List.of(LOMBOK_MEMBER_FILE));

        var version = 2;
        for (int i = 0; i < 8; i++) {
            change(server, LOMBOK_MEMBER_FILE, version++, original + "\n// lombok-typing-" + i);
            server.lint(List.of(LOMBOK_MEMBER_FILE));

            var completion = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 15));
            assertTrue(completion.isPresent());
            assertTrue(
                    "expected lombok member completion after repeated lint",
                    completion.get().items.stream().anyMatch(item -> "getName".equals(item.label)));
        }
    }

    @Test
    public void firstDotCompletionShowsPojoMembersWithoutRetype() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    static class MSReference {}\n"
                        + "    static class Txn {\n"
                        + "        private MSReference msref;\n"
                        + "        public MSReference getMsref() { return msref; }\n"
                        + "        public void setMsref(MSReference msref) { this.msref = msref; }\n"
                        + "    }\n"
                        + "\n"
                        + "    void test() {\n"
                        + "        var txn = new Txn();\n"
                        + "        txn.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var completion = server.completion(completionPosition(MEMBER_FILE, 13, 13));
        assertTrue(completion.isPresent());
        var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
        assertTrue("first dot completion should include getter", labels.contains("getMsref"));
        assertTrue("first dot completion should include setter", labels.contains("setMsref"));
    }

    @Test
    public void firstDotCompletionShowsLombokMembersWithoutRetype() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var original = FileStore.contents(LOMBOK_MEMBER_FILE);
        open(server, LOMBOK_MEMBER_FILE, 1, original);
        server.lint(List.of(LOMBOK_MEMBER_FILE));

        var completion = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 15));
        assertTrue(completion.isPresent());
        var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
        assertTrue("first dot completion should include lombok getter", labels.contains("getName"));
        assertTrue("first dot completion should include lombok setter", labels.contains("setName"));
    }

    @Test
    public void staleSecondDotAfterSelectedLombokMemberKeepsGeneratedMembers() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var original = FileStore.contents(LOMBOK_MEMBER_FILE);
        open(server, LOMBOK_MEMBER_FILE, 1, original);
        server.lint(List.of(LOMBOK_MEMBER_FILE));

        var first = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 15));
        assertTrue(first.isPresent());
        assertTrue(
                "expected first lombok member completion",
                first.get().items.stream().anyMatch(item -> "getName".equals(item.label)));

        var updated = original.replace("        model.\n", "        model.getName();\n        model.\n");
        var version = 2;
        for (int i = 0; i < 8; i++) {
            change(server, LOMBOK_MEMBER_FILE, version++, updated + "\n// stale-lombok-second-dot-" + i);
            var completion = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 7, 15));
            assertTrue(completion.isPresent());
            var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
            assertTrue("expected lombok getter on second dot", labels.contains("getName"));
            assertTrue("expected lombok setter on second dot", labels.contains("setName"));
        }
    }

    @Test
    public void immediateDotCompletionAfterDidChangeUsesReceiverRecovery() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var withDot = FileStore.contents(MEMBER_FILE);
        var withoutDot = withDot.replace("this.", "this");
        open(server, MEMBER_FILE, 1, withoutDot);
        server.lint(List.of(MEMBER_FILE));

        change(server, MEMBER_FILE, 2, withDot);
        var completion = server.completion(completionPosition(MEMBER_FILE, 5, 14));
        assertTrue(completion.isPresent());
        assertTrue(
                "expected member completion immediately after typing dot",
                completion.get().items.stream().anyMatch(item -> "testMethods".equals(item.label)));
    }

    @Test
    public void incrementalRangeTypingKeepsVariableAndMemberCompletionStable() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    public void test() {\n"
                        + "        String cur = \"\";\n"
                        + "        cur.charAt(0);\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        changeRange(server, MEMBER_FILE, 2, range(5, 22, 5, 22), "\n        ");
        changeRange(server, MEMBER_FILE, 3, range(6, 8, 6, 8), "c");
        changeRange(server, MEMBER_FILE, 4, range(6, 9, 6, 9), "ur");

        var variableCompletion = server.completion(completionPosition(MEMBER_FILE, 7, 11));
        assertTrue(variableCompletion.isPresent());
        assertTrue(
                "expected local variable completion for cur",
                variableCompletion.get().items.stream().anyMatch(item -> "cur".equals(item.label)));

        changeRange(server, MEMBER_FILE, 5, range(6, 11, 6, 11), ".");
        var memberCompletion = server.completion(completionPosition(MEMBER_FILE, 7, 13));
        assertTrue(memberCompletion.isPresent());
        assertTrue(
                "expected String member completion after incremental dot typing",
                memberCompletion.get().items.stream().anyMatch(item -> "charAt".equals(item.label)));
    }

    @Test
    public void dotCompletionDoesNotEchoReceiverName() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    void test() {\n"
                        + "        var txn = new Object();\n"
                        + "        txn.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);

        var completion = server.completion(completionPosition(MEMBER_FILE, 6, 13));
        assertTrue(completion.isPresent());
        assertTrue(
                "member completion must not include receiver name itself",
                completion.get().items.stream().noneMatch(item -> "txn".equals(item.label)));
    }

    @Test
    public void diagnosticsClearAfterDidChangeWithoutSave() throws Exception {
        var client = new TrackingClient();
        var server = LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);
        var original = FileStore.contents(MEMBER_FILE);
        open(server, MEMBER_FILE, 1, original);
        var uri = MEMBER_FILE.toUri();

        assertTrue(client.awaitDiagnosticsForUri(uri, 10, TimeUnit.SECONDS));
        assertTrue("expected initial parse error diagnostics", client.hasErrorDiagnostics(uri));

        var fixed = original.replace("this.", "this.testMethodsPrivate();");
        change(server, MEMBER_FILE, 2, fixed);

        assertTrue("expected diagnostics to clear without save", client.awaitNoErrorDiagnostics(uri, 10, TimeUnit.SECONDS));
    }

    @Test
    public void staleSnapshotDoesNotUsePreviousReceiverContext() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var original = FileStore.contents(MEMBER_FILE);
        open(server, MEMBER_FILE, 1, original);
        server.lint(List.of(MEMBER_FILE));

        var baseline = server.completion(completionPosition(MEMBER_FILE, 5, 14));
        assertTrue(baseline.isPresent());
        assertTrue(baseline.get().items.stream().anyMatch(item -> "testFields".equals(item.label)));

        var changed = original.replace("this.", "String.");
        change(server, MEMBER_FILE, 2, changed);

        var immediate = server.completion(completionPosition(MEMBER_FILE, 5, 16));
        assertTrue(immediate.isPresent());
        var labels = immediate.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
        assertTrue("stale snapshot leaked old receiver members", !labels.contains("testFields"));
    }

    @Test
    public void staleSnapshotDoesNotDriveIdentifierCompletion() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    public void test() {\n"
                        + "        var txn = new Object();\n"
                        + "        txn.toString();\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        changeRange(server, MEMBER_FILE, 2, range(5, 8, 5, 8), "\n        ");
        changeRange(server, MEMBER_FILE, 3, range(5, 8, 5, 8), "t");

        var completion = server.completion(completionPosition(MEMBER_FILE, 6, 10));
        assertTrue(completion.isPresent());
        var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
        assertTrue("expected local identifier completion for txn", labels.contains("txn"));
        assertTrue(
                "stale snapshot leaked unrelated single member result",
                !(labels.size() == 1 && labels.contains("toString")));
    }

    @Test
    public void staleDotCompletionKeepsJdkMemberResults() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import java.util.Currency;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    public void test() {\n"
                        + "        Currency cur = Currency.getInstance(\"USD\");\n"
                        + "        cur.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var version = 2;
        for (int i = 0; i < 12; i++) {
            change(server, MEMBER_FILE, version++, text + "\n// stale-dot-" + i);
            var completion = server.completion(completionPosition(MEMBER_FILE, 8, 13));
            assertTrue(completion.isPresent());
            assertTrue(
                    "expected Currency member completion while snapshot is stale",
                    completion.get().items.stream().anyMatch(item -> "getCurrencyCode".equals(item.label)));
        }
    }

    @Test
    public void staleMemberContinuationAfterDotShowsMembersBeforeKeywords() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import java.util.Currency;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    public void test() {\n"
                        + "        Currency cur = Currency.getInstance(\"USD\");\n"
                        + "        cur.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var typed = text.replace("cur.", "cur.g");
        change(server, MEMBER_FILE, 2, typed);
        var completion = server.completion(completionPosition(MEMBER_FILE, 8, 14));
        assertTrue(completion.isPresent());
        assertTrue(
                "expected member completion after typing first char after dot",
                completion.get().items.stream().anyMatch(item -> "getCurrencyCode".equals(item.label)));
        assertTrue(
                "member continuation should not prioritize keyword completions",
                completion.get().items.stream()
                                .limit(Math.min(5, completion.get().items.size()))
                                .noneMatch(item -> item.kind == org.javacs.lsp.CompletionItemKind.Keyword));
    }

    @Test
    public void staleMemberCompletionAtDotCharacterUsesMemberContext() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    public void test() {\n"
                        + "        String one = \"\";\n"
                        + "        one.charAt(0);\n"
                        + "        one.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var changed = text.replace("one", "two");
        change(server, MEMBER_FILE, 2, changed);

        var onDot = server.completion(completionPosition(MEMBER_FILE, 7, 12));
        assertTrue(onDot.isPresent());
        assertTrue(
                "expected member completion when cursor is on dot",
                onDot.get().items.stream().anyMatch(item -> "charAt".equals(item.label)));
        assertTrue(
                "member completion should not prioritize keywords",
                onDot.get().items.stream()
                                .limit(Math.min(5, onDot.get().items.size()))
                                .noneMatch(item -> item.kind == org.javacs.lsp.CompletionItemKind.Keyword));

        var afterDot = server.completion(completionPosition(MEMBER_FILE, 7, 13));
        assertTrue(afterDot.isPresent());
        assertTrue(
                "expected member completion when cursor is after dot",
                afterDot.get().items.stream().anyMatch(item -> "charAt".equals(item.label)));
    }

    @Test
    public void staleSecondDotAfterSelectedMemberKeepsPojoMembers() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var initial =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    static class MSReference {}\n"
                        + "    static class Txn {\n"
                        + "        private MSReference msref;\n"
                        + "        public MSReference getMsref() { return msref; }\n"
                        + "        public void setMsref(MSReference msref) { this.msref = msref; }\n"
                        + "    }\n"
                        + "\n"
                        + "    void test() {\n"
                        + "        {\n"
                        + "            Object txn = new Object();\n"
                        + "            txn.toString();\n"
                        + "        }\n"
                        + "        var txn = new Txn();\n"
                        + "        txn.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, initial);
        server.lint(List.of(MEMBER_FILE));

        var first = server.completion(completionPosition(MEMBER_FILE, 17, 13));
        assertTrue(first.isPresent());
        assertTrue("expected first member completion", first.get().items.stream().anyMatch(item -> "getMsref".equals(item.label)));

        var updated =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    static class MSReference {}\n"
                        + "    static class Txn {\n"
                        + "        private MSReference msref;\n"
                        + "        public MSReference getMsref() { return msref; }\n"
                        + "        public void setMsref(MSReference msref) { this.msref = msref; }\n"
                        + "    }\n"
                        + "\n"
                        + "    void test() {\n"
                        + "        {\n"
                        + "            Object txn = new Object();\n"
                        + "            txn.toString();\n"
                        + "        }\n"
                        + "        var txn = new Txn();\n"
                        + "        txn.getMsref();\n"
                        + "        txn.\n"
                        + "    }\n"
                        + "}\n";

        var version = 2;
        for (int i = 0; i < 8; i++) {
            change(server, MEMBER_FILE, version++, updated + "\n// stale-second-dot-" + i);
            var completion = server.completion(completionPosition(MEMBER_FILE, 18, 13));
            assertTrue(completion.isPresent());
            var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
            assertTrue("expected Txn getter after selecting previous member", labels.contains("getMsref"));
            assertTrue("expected Txn setter after selecting previous member", labels.contains("setMsref"));
        }
    }

    @Test
    public void incrementalTxnTypingDotAlwaysReturnsMembersNotIdentifierFallback() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var template =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    static class MSReference {}\n"
                        + "    static class Txn {\n"
                        + "        private MSReference msref;\n"
                        + "        public MSReference getMsref() { return msref; }\n"
                        + "        public void setMsref(MSReference msref) { this.msref = msref; }\n"
                        + "    }\n"
                        + "\n"
                        + "    void test() {\n"
                        + "        var txn = new Txn();\n"
                        + "        txn.getMsref();\n"
                        + "        %s\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, String.format(template, ""));
        server.lint(List.of(MEMBER_FILE));

        var version = 2;
        for (int i = 0; i < 10; i++) {
            change(server, MEMBER_FILE, version++, String.format(template, "t") + "// phase-t-" + i + "\n");
            change(server, MEMBER_FILE, version++, String.format(template, "txn") + "// phase-txn-" + i + "\n");
            change(server, MEMBER_FILE, version++, String.format(template, "txn.") + "// phase-dot-" + i + "\n");

            var completion = server.completion(completionPosition(MEMBER_FILE, 14, 13));
            assertTrue(completion.isPresent());
            var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
            assertTrue("expected Txn getter on first dot completion", labels.contains("getMsref"));
            assertTrue("expected Txn setter on first dot completion", labels.contains("setMsref"));
            assertTrue("member completion should not include receiver identifier", !labels.contains("txn"));
        }
    }

    @Test
    public void foreignPrivateFieldsAreHiddenFromMemberCompletion() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    static class SecretBox {\n"
                        + "        private String hidden;\n"
                        + "        public String getHidden() { return hidden; }\n"
                        + "    }\n"
                        + "\n"
                        + "    void test() {\n"
                        + "        var box = new SecretBox();\n"
                        + "        box.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var completion = server.completion(completionPosition(MEMBER_FILE, 11, 13));
        assertTrue(completion.isPresent());
        var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
        assertTrue("expected public getter in member completion", labels.contains("getHidden"));
        assertTrue("private fields must not be suggested for foreign receiver", !labels.contains("hidden"));
    }

    @Test
    public void varFromGetterReturnTypeStillShowsNestedMembers() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    static class ThisPoj {\n"
                        + "        private String foo;\n"
                        + "        public String getFoo() { return foo; }\n"
                        + "    }\n"
                        + "\n"
                        + "    void test() {\n"
                        + "        var ksk = new ThisPoj();\n"
                        + "        var asdww = ksk.getFoo();\n"
                        + "        asdww.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var completion = server.completion(completionPosition(MEMBER_FILE, 12, 15));
        assertTrue(completion.isPresent());
        assertTrue(
                "expected nested String member completion for var receiver",
                completion.get().items.stream().anyMatch(item -> "toCharArray".equals(item.label)));
    }

    @Test
    public void lombokTypedMemberPrefixRemainsStableDuringRapidChanges() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var original = FileStore.contents(LOMBOK_MEMBER_FILE);
        open(server, LOMBOK_MEMBER_FILE, 1, original);
        server.lint(List.of(LOMBOK_MEMBER_FILE));

        var typed = original.replace("        model.\n", "        model.get\n");
        var version = 2;
        for (int i = 0; i < 10; i++) {
            change(server, LOMBOK_MEMBER_FILE, version++, typed + "// typed-prefix-" + i + "\n");
            var completion = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 18));
            assertTrue(completion.isPresent());
            var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
            assertTrue("expected lombok getter for typed member prefix", labels.contains("getName"));
        }
    }

    @Test
    public void restoringLombokReceiverAfterTemporaryObjectEditShowsMembersImmediately() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var original = FileStore.contents(LOMBOK_MEMBER_FILE);
        open(server, LOMBOK_MEMBER_FILE, 1, original);
        server.lint(List.of(LOMBOK_MEMBER_FILE));

        var detached = original.replace("var model = new LombokCrossTypeModel();", "Object model = new Object();");
        change(server, LOMBOK_MEMBER_FILE, 2, detached);
        server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 15));

        change(server, LOMBOK_MEMBER_FILE, 3, original);
        var immediate = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 15));
        assertTrue(immediate.isPresent());
        var labels = immediate.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
        assertTrue("expected lombok getter immediately after restoring receiver type", labels.contains("getName"));
        assertTrue("expected lombok setter immediately after restoring receiver type", labels.contains("setName"));
    }

    @Test
    public void repeatedMemberCompletionOrderingIsStable() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import java.util.Currency;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    public void test() {\n"
                        + "        Currency cur = Currency.getInstance(\"USD\");\n"
                        + "        cur.getCurrencyCode();\n"
                        + "        cur.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        List<String> baseline = null;
        for (int i = 0; i < 12; i++) {
            var completion = server.completion(completionPosition(MEMBER_FILE, 9, 13));
            assertTrue(completion.isPresent());
            var labels =
                    completion.get().items.stream()
                            .limit(12)
                            .map(item -> item.label)
                            .collect(Collectors.toList());
            assertTrue("expected JDK member completion", labels.contains("getCurrencyCode"));
            if (baseline == null) {
                baseline = labels;
            } else {
                assertThat("member completion ordering drifted", labels, is(baseline));
            }
            assertTrue(
                    "member completion should not prioritize keywords",
                    completion.get().items.stream()
                                    .limit(Math.min(5, completion.get().items.size()))
                                    .noneMatch(item -> item.kind == org.javacs.lsp.CompletionItemKind.Keyword));
        }
    }

    @Test
    public void rapidCompletionRequestsUseDeterministicSnapshotResults() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import java.util.Currency;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    public void test() {\n"
                        + "        Currency cur = Currency.getInstance(\"USD\");\n"
                        + "        cur.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        List<String> baseline = null;
        for (int i = 0; i < 50; i++) {
            var completion = server.completion(completionPosition(MEMBER_FILE, 8, 13));
            assertTrue(completion.isPresent());
            var labels =
                    completion.get().items.stream()
                            .map(item -> item.label)
                            .collect(Collectors.toList());
            if (baseline == null) {
                baseline = labels;
            } else {
                assertThat("rapid completion request returned nondeterministic members", labels, is(baseline));
            }
        }
    }

    @Test
    public void crossFilePojoUpdateAppearsAfterDebouncedIncrementalIndexRefresh() throws Exception {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var modelOriginal = FileStore.contents(LOMBOK_MODEL_FILE);
        var useOriginal = FileStore.contents(LOMBOK_MEMBER_FILE);

        open(server, LOMBOK_MODEL_FILE, 1, modelOriginal);
        open(server, LOMBOK_MEMBER_FILE, 1, useOriginal);

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Set<String> baselineLabels = Set.of();
        while (System.nanoTime() < deadline) {
            var baseline = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 15));
            assertTrue(baseline.isPresent());
            baselineLabels = baseline.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
            if (baselineLabels.contains("getName")) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue("expected baseline lombok getter", baselineLabels.contains("getName"));
        assertTrue("unexpected future field getter in baseline", !baselineLabels.contains("getTitle"));

        var modelUpdated = modelOriginal.replace("private String name;", "private String title;");
        change(server, LOMBOK_MODEL_FILE, 2, modelUpdated);

        var beforeSwitch = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 15));
        assertTrue(beforeSwitch.isPresent());
        var beforeSwitchLabels = beforeSwitch.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
        assertTrue("snapshot switched too early after unsaved change", beforeSwitchLabels.contains("getName"));
        assertTrue("new getter appeared before debounced refresh", !beforeSwitchLabels.contains("getTitle"));

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Set<String> afterSwitchLabels = Set.of();
        while (System.nanoTime() < deadline) {
            var afterSwitch = server.completion(completionPosition(LOMBOK_MEMBER_FILE, 6, 15));
            assertTrue(afterSwitch.isPresent());
            afterSwitchLabels = afterSwitch.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
            if (afterSwitchLabels.contains("getTitle") && !afterSwitchLabels.contains("getName")) {
                break;
            }
            Thread.sleep(50);
        }

        assertTrue("updated getter missing after incremental refresh", afterSwitchLabels.contains("getTitle"));
        assertTrue("ghost getter leaked from old snapshot", !afterSwitchLabels.contains("getName"));
    }

    @Test
    public void completionForTypeANeverLeaksTypeBMembers() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "class A {\n"
                        + "    String aOnly() { return \"\"; }\n"
                        + "}\n"
                        + "\n"
                        + "class B {\n"
                        + "    String bOnly() { return \"\"; }\n"
                        + "}\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    void test() {\n"
                        + "        A a = new A();\n"
                        + "        a.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        for (int i = 0; i < 20; i++) {
            var completion = server.completion(completionPosition(MEMBER_FILE, 14, 11));
            assertTrue(completion.isPresent());
            var labels = completion.get().items.stream().map(item -> item.label).collect(Collectors.toSet());
            assertTrue("expected receiver-specific member from A", labels.contains("aOnly"));
            assertTrue("ghost member from B leaked into A completion", !labels.contains("bOnly"));
        }
    }

    @Test
    public void staleReceiverWithConflictingNameStillResolvesStringMembers() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import java.math.BigDecimal;\n"
                        + "import java.util.Currency;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    void alpha() {\n"
                        + "        int[] t = new int[0];\n"
                        + "        t.length;\n"
                        + "    }\n"
                        + "\n"
                        + "    void beta() {\n"
                        + "        String t = \"\";\n"
                        + "        Currency cur = Currency.getInstance(\"USD\");\n"
                        + "        t = new BigDecimal(\"192\")\n"
                        + "                .add(new BigDecimal(\"21\"))\n"
                        + "                .setScale(cur.getDefaultFractionDigits()).toPlainString();\n"
                        + "        t.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var version = 2;
        for (int i = 0; i < 8; i++) {
            var shifted = text.replace("    void alpha()", "    // stale-shift-" + i + "\n    void alpha()");
            change(server, MEMBER_FILE, version++, shifted);
            var completion = server.completion(completionPosition(MEMBER_FILE, 19, 11));
            assertTrue(completion.isPresent());
            assertTrue(
                    "expected String members despite same-name receiver in another scope",
                    completion.get().items.stream().anyMatch(item -> "toCharArray".equals(item.label)));
        }
    }

    @Test
    public void staleLowercaseReceiverNameCollisionDoesNotReturnKeywordOnlyClass() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    static class t {}\n"
                        + "\n"
                        + "    void test() {\n"
                        + "        String t = \"\";\n"
                        + "        t.\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var version = 2;
        for (int i = 0; i < 8; i++) {
            var shifted = text + "\n// shift-" + i;
            change(server, MEMBER_FILE, version++, shifted);
            var completion = server.completion(completionPosition(MEMBER_FILE, 8, 11));
            assertTrue(completion.isPresent());
            assertTrue(
                    "expected String members for lowercase receiver collision",
                    completion.get().items.stream().anyMatch(item -> "toCharArray".equals(item.label)));
            assertTrue(
                    "keyword-only class result leaked from type collision",
                    completion.get().items.stream()
                                    .limit(Math.min(3, completion.get().items.size()))
                                    .noneMatch(item -> item.kind == org.javacs.lsp.CompletionItemKind.Keyword));
        }
    }

    @Test
    public void chainedInterfaceDotCompletionRemainsMemberOnlyDuringRapidChanges() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    interface Two {\n"
                        + "        String two();\n"
                        + "    }\n"
                        + "\n"
                        + "    interface One {\n"
                        + "        Two one();\n"
                        + "    }\n"
                        + "\n"
                        + "    void test(One myInt) {\n"
                        + "        myInt.one().\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var version = 2;
        for (int i = 0; i < 10; i++) {
            change(server, MEMBER_FILE, version++, text + "\n// chain-" + i);
            var completion = server.completion(completionPosition(MEMBER_FILE, 13, 21));
            assertTrue(completion.isPresent());
            assertTrue(
                    "expected chained interface member completion",
                    completion.get().items.stream().anyMatch(item -> "two".equals(item.label)));
            assertTrue(
                    "expected chained member completion to avoid keyword-only fallback",
                    completion.get().items.stream()
                                    .limit(Math.min(5, completion.get().items.size()))
                                    .noneMatch(item -> item.kind == org.javacs.lsp.CompletionItemKind.Keyword));
        }
    }

    @Test
    public void directlyImportedClassRanksBeforeSameSimpleNameAlternatives() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import java.util.Date;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    void test() {\n"
                        + "        Dat\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var completion = server.completion(completionPosition(MEMBER_FILE, 7, 12));
        assertTrue(completion.isPresent());
        assertTrue("expected at least one completion item", !completion.get().items.isEmpty());
        assertTrue("expected first completion to be Date", "Date".equals(completion.get().items.get(0).label));
        assertTrue(
                "expected directly imported Date to rank first",
                "java.util.Date".equals(completion.get().items.get(0).detail));
    }

    @Test
    public void importedTypeCaseInsensitiveExactMatchComesFirst() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import java.util.Currency;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    void test() {\n"
                        + "        currency;\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var completion = server.completion(completionPosition(MEMBER_FILE, 7, 17));
        assertTrue(completion.isPresent());
        assertTrue("expected at least one completion item", !completion.get().items.isEmpty());
        assertTrue(
                "expected imported type to be first for exact case-insensitive match",
                "Currency".equals(completion.get().items.get(0).label));
    }

    @Test
    public void importedTypeExactMatchOutranksLocalsAndKeywords() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import java.util.Currency;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    void test() {\n"
                        + "        String serviceOne = \"\";\n"
                        + "        String switcher = \"\";\n"
                        + "        currency;\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var completion = server.completion(completionPosition(MEMBER_FILE, 9, 17));
        assertTrue(completion.isPresent());
        assertTrue("expected at least one completion item", !completion.get().items.isEmpty());
        assertTrue("expected Currency to be first", "Currency".equals(completion.get().items.get(0).label));
    }

    @Test
    public void importedStringUtilsExactMatchStaysFirstDuringRapidChanges() {
        var server = LanguageServerFixture.getJavaLanguageServer();
        var text =
                "package org.javacs.example;\n"
                        + "\n"
                        + "import org.springframework.util.StringUtils;\n"
                        + "\n"
                        + "public class AutocompleteMember {\n"
                        + "    void test() {\n"
                        + "        stringutils\n"
                        + "    }\n"
                        + "}\n";
        open(server, MEMBER_FILE, 1, text);
        server.lint(List.of(MEMBER_FILE));

        var version = 2;
        for (int i = 0; i < 10; i++) {
            change(server, MEMBER_FILE, version++, text + "\n// str-exact-" + i);
            var completion = server.completion(completionPosition(MEMBER_FILE, 7, 20));
            assertTrue(completion.isPresent());
            assertTrue("expected at least one completion item", !completion.get().items.isEmpty());
            var first = completion.get().items.get(0);
            assertTrue("expected StringUtils to rank first", "StringUtils".equals(first.label));
            assertTrue(
                    "expected directly imported StringUtils detail",
                    "org.springframework.util.StringUtils".equals(first.detail));
        }
    }

    @Test
    public void diagnosticsDoNotRequireSaveAfterRapidBrokenThenFixedChange() throws Exception {
        var client = new TrackingClient();
        var server = LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);
        var original = FileStore.contents(HELLO_FILE);
        open(server, HELLO_FILE, 1, original);
        var uri = HELLO_FILE.toUri();

        assertTrue(client.awaitDiagnosticsForUri(uri, 10, TimeUnit.SECONDS));
        assertTrue("expected clean initial diagnostics", !client.hasErrorDiagnostics(uri));

        var broken = original.replace("System.out.println(\"Hello world!\");", "this.;");
        change(server, HELLO_FILE, 2, broken);
        Thread.sleep(250);
        change(server, HELLO_FILE, 3, original);

        assertTrue(
                "expected diagnostics to settle to no-error state without save",
                client.awaitNoErrorDiagnosticsStable(uri, 12, TimeUnit.SECONDS, 400));
    }

    private long timeCompletion(JavaLanguageServer server, Path file, int line, int column) {
        var started = System.nanoTime();
        var maybe = server.completion(completionPosition(file, line, column));
        assertTrue(maybe.isPresent());
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private TextDocumentPositionParams completionPosition(Path file, int line, int column) {
        return new TextDocumentPositionParams(
                new TextDocumentIdentifier(file.toUri()), new Position(line - 1, column - 1));
    }

    private void open(JavaLanguageServer server, Path file, int version, String text) {
        var open = new DidOpenTextDocumentParams();
        var document = new TextDocumentItem();
        document.uri = file.toUri();
        document.version = version;
        document.languageId = "java";
        document.text = text;
        open.textDocument = document;
        server.didOpenTextDocument(open);
    }

    private void change(JavaLanguageServer server, Path file, int version, String text) {
        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = version;
        var delta = new TextDocumentContentChangeEvent();
        delta.text = text;
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);
    }

    private void changeRange(JavaLanguageServer server, Path file, int version, Range range, String text) {
        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = version;
        var delta = new TextDocumentContentChangeEvent();
        delta.range = range;
        delta.text = text;
        change.contentChanges.add(delta);
        server.didChangeTextDocument(change);
    }

    private Range range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter));
    }

    private long median(List<Long> values) {
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private long percentile95(List<Long> values) {
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        var index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(index, 0));
    }

    private ReferenceContext referenceContext(Path workspaceRoot) {
        FileStore.reset();
        FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
        var infer = new InferConfig(workspaceRoot);
        var compiler =
                new JavaCompilerService(
                        infer.classPath(), infer.buildDocPath(), Collections.emptySet(), Collections.emptySet());
        TypeMemberIndex index;
        try (var task = compiler.compile(FileStore.all().toArray(Path[]::new))) {
            index = TypeMemberIndex.from(task);
        }
        return new ReferenceContext(compiler, index);
    }

    private static class ReferenceContext {
        final JavaCompilerService compiler;
        final TypeMemberIndex index;

        ReferenceContext(JavaCompilerService compiler, TypeMemberIndex index) {
            this.compiler = compiler;
            this.index = index;
        }
    }

    private static class TrackingClient implements LanguageClient {
        private final CountDownLatch diagnosticsPublished = new CountDownLatch(1);
        private final AtomicInteger publishCalls = new AtomicInteger();
        private final Map<URI, List<org.javacs.lsp.Diagnostic>> latestDiagnosticsByUri =
                new ConcurrentHashMap<>();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            publishCalls.incrementAndGet();
            latestDiagnosticsByUri.put(params.uri, List.copyOf(params.diagnostics));
            diagnosticsPublished.countDown();
        }

        boolean awaitDiagnostics(long timeout, TimeUnit unit) throws InterruptedException {
            return diagnosticsPublished.await(timeout, unit) && publishCalls.get() > 0;
        }

        boolean awaitDiagnosticsForUri(URI uri, long timeout, TimeUnit unit) throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (latestDiagnosticsByUri.containsKey(uri)) {
                    return true;
                }
                Thread.sleep(20);
            }
            return false;
        }

        boolean hasErrorDiagnostics(URI uri) {
            var diagnostics = latestDiagnosticsByUri.getOrDefault(uri, List.of());
            return diagnostics.stream().anyMatch(d -> d.severity == DiagnosticSeverity.Error);
        }

        boolean awaitNoErrorDiagnostics(URI uri, long timeout, TimeUnit unit) throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (latestDiagnosticsByUri.containsKey(uri) && !hasErrorDiagnostics(uri)) {
                    return true;
                }
                Thread.sleep(20);
            }
            return false;
        }

        boolean awaitNoErrorDiagnosticsStable(URI uri, long timeout, TimeUnit unit, long stableMillis)
                throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (!awaitNoErrorDiagnostics(uri, 1, TimeUnit.SECONDS)) {
                    continue;
                }
                var stableUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stableMillis);
                var stable = true;
                while (System.nanoTime() < stableUntil) {
                    if (hasErrorDiagnostics(uri)) {
                        stable = false;
                        break;
                    }
                    Thread.sleep(20);
                }
                if (stable) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void showMessage(ShowMessageParams params) {}

        @Override
        public void registerCapability(String method, JsonElement options) {}

        @Override
        public void refreshInlayHints() {}

        @Override
        public void customNotification(String method, JsonElement params) {}
    }
}
