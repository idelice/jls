package org.javacs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.InlayHintParams;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentItem;
import org.junit.Assert;
import org.junit.Test;

public class InlayHintTest {

    @Test
    public void globalTypeLookupCandidateRequiresUppercaseSimpleName() {
        Assert.assertTrue(InlayHintService.isGlobalTypeLookupCandidate("Foo"));
        Assert.assertTrue(InlayHintService.isGlobalTypeLookupCandidate("java.util.Map"));
        Assert.assertFalse(InlayHintService.isGlobalTypeLookupCandidate("foo"));
        Assert.assertFalse(InlayHintService.isGlobalTypeLookupCandidate("com.example.demo.service.log"));
    }

    @Test
    public void sourceParameterNameLookupSkipsSlf4jOwners() {
        Assert.assertFalse(InlayHintService.shouldResolveSourceParameterNames("org.slf4j.Logger"));
        Assert.assertTrue(InlayHintService.shouldResolveSourceParameterNames("test.InlayHints"));
    }

    @Test
    public void workspaceSourceGuardOnlyAllowsWorkspaceFiles() throws IOException {
        var workspace = Files.createTempDirectory("jls-inlay-workspace-guard");
        var file = workspace.resolve("InlayHints.java");
        Files.writeString(file, "class InlayHints {}");
        var external = Files.createTempFile("jls-inlay-external", ".java");
        Files.writeString(external, "class External {}");
        try {
            FileStore.setWorkspaceRoots(java.util.Set.of(workspace));
            Assert.assertTrue(InlayHintService.isWorkspaceSource(file));
            Assert.assertFalse(InlayHintService.isWorkspaceSource(external));
        } finally {
            FileStore.reset();
        }
    }

    @Test
    public void varSimpleMethodShowsInferredTypeHint() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    static class Foo {}

                    Foo compute() {
                        return new Foo();
                    }

                    void test() {
                        var foo = compute();
                    }
                }
                """;

        try (var session = open(content)) {
            var hints = session.hints(singleLineRange(content, "var foo = compute();"));
            Assert.assertEquals(List.of(": Foo"), labels(hints));
        }
    }

    @Test
    public void varStringLiteralSuppressesHint() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    void test() {
                        var foo = "hello";
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertTrue(session.hints(singleLineRange(content, "var foo = \"hello\";")).isEmpty());
        }
    }

    @Test
    public void varNewClassSuppressesHint() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    static class Foo {}

                    void test() {
                        var foo = new Foo();
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertTrue(session.hints(singleLineRange(content, "var foo = new Foo();")).isEmpty());
        }
    }

    @Test
    public void parameterHintBasic() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    void someMethod(int x, int y) {}

                    void test() {
                        someMethod(1, 2);
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertEquals(List.of("x:", "y:"), labels(session.hints(singleLineRange(content, "someMethod(1, 2);"))));
        }
    }

    @Test
    public void parameterNameMatchSuppressesHint() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    void setName(String name) {}

                    void test(String name) {
                        setName(name);
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertTrue(session.hints(singleLineRange(content, "setName(name);")).isEmpty());
        }
    }

    @Test
    public void methodNameMatchSuppressesHint() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    void setName(String name) {}

                    void test() {
                        setName("John");
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertTrue(session.hints(singleLineRange(content, "setName(\"John\");")).isEmpty());
        }
    }

    @Test
    public void builderPatternShowsHints() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    static class Builder {
                        Builder name(String name) { return this; }
                        Builder age(int age) { return this; }
                    }

                    void test() {
                        new Builder().name("John").age(20);
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertEquals(
                    List.of("name:", "age:"),
                    labels(session.hints(singleLineRange(content, "new Builder().name(\"John\").age(20);"))));
        }
    }

    @Test
    public void constructorParameterHintsShow() throws Exception {
        var content =
                """
                package test;

                record MyRec2(String value) {}

                class InlayHints {
                    void test() {
                        var r = new MyRec2("asd");
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertEquals(
                    List.of("value:"),
                    labels(session.hints(singleLineRange(content, "var r = new MyRec2(\"asd\");"))));
        }
    }

    @Test
    public void objectParameterHintShowsForSubtypeArgument() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    static class StringUtils {
                        static boolean isEmpty(Object value) { return value == null; }
                    }

                    static class ThisPoj {
                        String getAlpoq() { return ""; }
                    }

                    void test(ThisPoj asd) {
                        if (StringUtils.isEmpty(asd.getAlpoq())) {
                        }
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertEquals(
                    List.of("value:"),
                    labels(session.hints(singleLineRange(content, "if (StringUtils.isEmpty(asd.getAlpoq())) {"))));
        }
    }

    @Test
    public void nestedInvocationHintRemainsWhenOuterSetterIsSuppressed() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    static class StringUtils {
                        static boolean isEmpty(Object value) { return value == null; }
                    }

                    static class ThisPoj {
                        String getAlpoq() { return ""; }
                        void setAlpoq(String alpoq) {}
                    }

                    void test(ThisPoj asd) {
                        asd.setAlpoq(StringUtils.isEmpty(asd.getAlpoq()) ? null : "");
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertEquals(
                    List.of("value:"),
                    labels(session.hints(singleLineRange(content, "asd.setAlpoq(StringUtils.isEmpty(asd.getAlpoq()) ? null : \"\");"))));
        }
    }

    @Test
    public void nonSetterMethodNameContainingParameterStillShowsHint() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    static class StringUtils {
                        static String cleanPath(String path) { return path; }
                    }

                    void test() {
                        StringUtils.cleanPath("asd");
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertEquals(
                    List.of("path:"),
                    labels(session.hints(singleLineRange(content, "StringUtils.cleanPath(\"asd\");"))));
        }
    }

    @Test
    public void enumConstantProducesNoHints() throws Exception {
        var content =
                """
                package test;

                enum Foo {
                    HELLO("value");

                    Foo(String value) {}
                }
                """;

        try (var session = open(content)) {
            Assert.assertTrue(session.hints(fullRange(content)).isEmpty());
        }
    }

    @Test
    public void interfaceDeclarationProducesNoHints() throws Exception {
        var content =
                """
                package test;

                interface Foo {
                    void doSomething(String foo);
                }
                """;

        try (var session = open(content)) {
            Assert.assertTrue(session.hints(fullRange(content)).isEmpty());
        }
    }

    @Test
    public void interfaceMethodInvocationShowsHints() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    interface Service {
                        void doSomething(String greeting);
                    }

                    void test(Service service) {
                        service.doSomething("hello");
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertEquals(
                    List.of("greeting:"),
                    labels(session.hints(singleLineRange(content, "service.doSomething(\"hello\");"))));
        }
    }

    @Test
    public void inlayHintsOnlyReturnRequestedRange() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    void someMethod(int x, int y) {}

                    void test() {
                        someMethod(1, 2);
                        someMethod(3, 4);
                    }
                }
                """;

        try (var session = open(content)) {
            Assert.assertEquals(
                    List.of("x:", "y:"),
                    labels(session.hints(singleLineRange(content, "someMethod(1, 2);"))));
        }
    }

    @Test
    public void inlayHintRequestDoesNotInvokeCompileBatch() throws Exception {
        var content =
                """
                package test;

                class InlayHints {
                    void someMethod(int x, int y) {}

                    void test() {
                        someMethod(1, 2);
                    }
                }
                """;

        try (var session = open(content)) {
            CompileBatch.resetPerfCounters();
            var hints = session.hints(singleLineRange(content, "someMethod(1, 2);"));
            Assert.assertEquals(List.of("x:", "y:"), labels(hints));
            Assert.assertEquals(0L, CompileBatch.perfCounters().fullBatches);
            Assert.assertEquals(0L, CompileBatch.perfCounters().analyzeInvocations);
            Assert.assertEquals(0L, CompileBatch.perfCounters().apEnabledBatches);
        }
    }

    private List<String> labels(List<InlayHint> hints) {
        return hints.stream().map(hint -> hint.label).collect(Collectors.toList());
    }

    private Session open(String content) throws Exception {
        var workspace = Files.createTempDirectory("jls-inlay");
        var file = workspace.resolve("InlayHints.java");
        Files.writeString(file, content);

        var server = LanguageServerFixture.getJavaLanguageServer(workspace, diagnostic -> {});
        Thread.sleep(500);

        var open = new DidOpenTextDocumentParams();
        open.textDocument = new TextDocumentItem();
        open.textDocument.uri = file.toUri();
        open.textDocument.languageId = "java";
        open.textDocument.version = 1;
        open.textDocument.text = content;
        server.didOpenTextDocument(open);

        return new Session(workspace, file, server);
    }

    private Range singleLineRange(String content, String needle) {
        var line = lineOf(content, needle);
        return new Range(new Position(line, 0), new Position(line, 200));
    }

    private Range fullRange(String content) {
        var lines = content.split("\n", -1).length;
        return new Range(new Position(0, 0), new Position(lines - 1, 0));
    }

    private int lineOf(String content, String needle) {
        var lines = content.split("\n", -1);
        for (var i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("Missing line containing: " + needle);
    }

    private void deleteRecursively(Path root) throws IOException {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(
                path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private final class Session implements AutoCloseable {
        private final Path workspace;
        private final Path file;
        private final JavaLanguageServer server;

        private Session(Path workspace, Path file, JavaLanguageServer server) {
            this.workspace = workspace;
            this.file = file;
            this.server = server;
        }

        private List<InlayHint> hints(Range range) {
            return server.inlayHint(new InlayHintParams(new TextDocumentIdentifier(file.toUri()), range));
        }

        @Override
        public void close() throws Exception {
            server.shutdown();
            deleteRecursively(workspace);
        }
    }
}
