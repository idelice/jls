package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.Before;
import org.junit.Test;

public class WarningsTest {
    private static final String PROCESSOR_WARNING_CODE = "compiler.warn.proc.messager";
    private static List<String> errors = new ArrayList<>();

    protected static final JavaLanguageServer server =
            LanguageServerFixture.getJavaLanguageServer(WarningsTest::onError);

    private static void onError(Diagnostic error) {
        if (PROCESSOR_WARNING_CODE.equals(error.code)) {
            return;
        }
        var string = String.format("%s(%d)", error.code, error.range.start.line + 1);
        errors.add(string);
    }

    @Before
    public void setup() {
        errors.clear();
    }

    @Test
    public void wrongType() {
        var file = FindResource.path("org/javacs/err/WrongType.java");
        server.lint(List.of(file));
        assertThat(errors, hasItem("compiler.err.prob.found.req(5)"));
    }

    @Test
    public void clearOpenDocumentDiagnosticsIncrementally() {
        var server = LanguageServerFixture.getJavaLanguageServer(WarningsTest::onError);
        var file = FindResource.path("org/javacs/err/ClearErrorIncrementally.java");
        open(server, file);
        server.lint(List.of(file));
        assertTrue(
                "expected initial open-document diagnostics",
                errors.contains("unused_local(5)") || errors.contains("compiler.err.prob.found.req(5)"));
        // Change 1 to "1"
        var newContents =
                "package org.javacs.err;\n\npublic class ClearErrorIncrementally {\n    void test() {\n        String x = \"1\";\n    }\n}";
        edit(server, file, newContents);
        errors.clear();
        server.lint(List.of(file));
        assertThat(errors, contains("unused_local(5)"));
        // Delete line `String x = "1";`
        newContents =
                "package org.javacs.err;\n\npublic class ClearErrorIncrementally {\n    void test() {\n        }\n}";
        edit(server, file, newContents);
        errors.clear();
        server.lint(List.of(file));
        assertThat(errors, empty());
    }

    private static int editVersion = 1;

    private void open(Path file) {
        open(server, file);
    }

    private void open(JavaLanguageServer server, Path file) {
        var open = new DidOpenTextDocumentParams();
        open.textDocument.uri = file.toUri();
        try {
            open.textDocument.text = Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        open.textDocument.version = editVersion++;
        open.textDocument.languageId = "java";
        server.didOpenTextDocument(open);
    }

    private void edit(Path file, String contents) {
        edit(server, file, contents);
    }

    private void edit(JavaLanguageServer server, Path file, String contents) {
        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = file.toUri();
        change.textDocument.version = editVersion++;
        var evt = new TextDocumentContentChangeEvent();
        evt.text = contents;
        change.contentChanges.add(evt);
        server.didChangeTextDocument(change);
    }

    @Test
    public void unused() {
        server.lint(List.of(FindResource.path("org/javacs/warn/Unused.java")));
        assertThat(errors, hasItem("unused_local(7)")); // int unusedLocal
        assertThat(errors, hasItem("unused_field(10)")); // int unusedPrivate
        assertThat(errors, hasItem("unused_local(13)")); // int unusedLocalInLambda
        assertThat(errors, hasItem("unused_method(16)")); // int unusedMethod() { ... }
        assertThat(errors, hasItem("unused_method(22)")); // private Unused(int i) { }
        assertThat(errors, hasItem("unused_class(24)")); // private class UnusedClass { }
        assertThat(errors, hasItem("unused_method(26)")); // void unusedSelfReference() { ... }
        assertThat(errors, not("unused_param(6)")); // test(int unusedParam)
        assertThat(errors, not("unused_param(12)")); // unusedLambdaParam -> {};
        assertThat(errors, not(hasItem("unused_method(20)"))); // private Unused() { }
        assertThat(errors, hasItem("unused_method(30)")); // private void unusedMutuallyRecursive1() { ... }
        assertThat(errors, hasItem("unused_method(34)")); // private void unusedMutuallyRecursive2() { ... }
        assertThat(errors, not(hasItem("unused_method(38)"))); // private int usedByUnusedVar() { ... }
        assertThat(errors, not(hasItem("unused_throw(46)"))); // void notActuallyThrown() throws Exception { }
    }

    @Test
    public void pseudoUsed() {
        server.lint(List.of(FindResource.path("org/javacs/warn/PseudoUsed.java")));
        assertThat(errors, not(hasItem("unused_method(8)"))); // void pseudoUsed(int)
    }

    @Test
    public void interfaceConst() {
        server.lint(List.of(FindResource.path("org/javacs/warn/InterfaceConst.java")));
        assertThat(errors, empty());
    }

    @Test
    public void targetedDiagnosticsDoNotExpandPackagePrivateCompanions() {
        server.lint(List.of(FindResource.path("org/javacs/example/ReferenceGotoPackagePrivate.java")));
        assertThat(errors, contains("compiler.err.cant.resolve.location(5)"));
    }

    @Test
    public void notThrown() {
        server.lint(List.of(FindResource.path("org/javacs/warn/NotThrown.java")));
        assertThat(errors, hasItem("unused_throws(6)"));
        assertThat(errors, not(hasItem("unused_throws(8)")));
    }

    @Test
    public void notThrownConstructor() {
        server.lint(List.of(FindResource.path("org/javacs/warn/NotThrownConstructor.java")));
        assertThat(errors, empty());
    }

    @Test
    public void unusedLombokSlf4jHasNonZeroRange() {
        var diags = new ArrayList<Diagnostic>();
        var srv = LanguageServerFixture.getJavaLanguageServer(diags::add);
        srv.lint(List.of(FindResource.path("org/javacs/warn/UnusedSlf4j.java")));
        var slf4jDiag = diags.stream()
                .filter(d -> "unused_field".equals(d.code) && d.message.contains("log"))
                .findFirst();
        assertTrue("expected unused_field diagnostic for 'log'", slf4jDiag.isPresent());
        var range = slf4jDiag.get().range;
        assertTrue(
                "diagnostic range should not be zero-width",
                range.start.line != range.end.line || range.start.character != range.end.character);
    }

    // TODO warn on type.equals(otherType)
    // TODO warn on map.get(wrongKeyType)
}
