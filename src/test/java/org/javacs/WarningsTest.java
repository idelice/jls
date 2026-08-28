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
        assertThat(errors, hasItem("unused_local(5)"));
        // Delete line `String x = "1";`
        newContents =
                "package org.javacs.err;\n\npublic class ClearErrorIncrementally {\n    void test() {\n        }\n}";
        edit(server, file, newContents);
        errors.clear();
        server.lint(List.of(file));
        assertThat(errors, not(hasItem("unused_local(5)")));
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
        var unused = errors.stream().filter(error -> error.startsWith("unused_")).toList();
        assertThat(
                unused,
                containsInAnyOrder(
                        "unused_local(7)",
                        "unused_field(10)",
                        "unused_local(13)",
                        "unused_local(43)",
                        "unused_class(24)"));
    }

    @Test
    public void interfaceConst() {
        server.lint(List.of(FindResource.path("org/javacs/warn/InterfaceConst.java")));
        assertThat(errors, empty());
    }

    @Test
    public void targetedDiagnosticsDoNotExpandPackagePrivateCompanions() {
        server.lint(List.of(FindResource.path("org/javacs/example/ReferenceGotoPackagePrivate.java")));
        assertThat(errors, hasItem("compiler.err.cant.resolve.location(5)"));
    }

    @Test
    public void unusedImport() {
        var diags = new ArrayList<Diagnostic>();
        var srv = LanguageServerFixture.getJavaLanguageServer(diags::add);
        srv.lint(List.of(FindResource.path("org/javacs/warn/UnusedImport.java")));
        var importDiag = diags.stream()
                .filter(d -> "unused_import".equals(d.code) && d.message.contains("Map"))
                .findFirst();
        assertTrue("expected unused_import diagnostic for 'Map'", importDiag.isPresent());
    }

    @Test
    public void recordFieldsDoNotWarnUnused() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedRecordFields.java")));
        var fieldWarnings = errors.stream().filter(e -> e.startsWith("unused_field")).toList();
        // Only the private class field should warn, not the record components
        assertThat(fieldWarnings, containsInAnyOrder("unused_field(8)"));
    }

    @Test
    public void unusedPrivateTypesWarn() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedPrivateTypes.java")));
        var classWarnings = errors.stream().filter(e -> e.startsWith("unused_class")).toList();
        assertThat(classWarnings, containsInAnyOrder("unused_class(5)", "unused_class(8)"));
    }

    @Test
    public void usedPrivateTypesDoNotWarn() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedPrivateTypes.java")));
        var classWarnings = errors.stream().filter(e -> e.startsWith("unused_class")).toList();
        // UsedRecord and UsedInner should NOT appear
        assertThat(classWarnings, not(hasItem(containsString("11"))));
        assertThat(classWarnings, not(hasItem(containsString("14"))));
    }

    @Test
    public void diagnosticMessageStripsPackageNames() {
        var diags = new ArrayList<Diagnostic>();
        var srv = LanguageServerFixture.getJavaLanguageServer(diags::add);
        srv.lint(List.of(FindResource.path("org/javacs/err/MissingOverride.java")));
        var diag = diags.stream()
                .filter(d -> d.code != null && d.code.contains("does.not.override.abstract"))
                .findFirst();
        assertTrue("expected does.not.override.abstract error", diag.isPresent());
        assertThat(diag.get().message, not(containsString("java.lang.")));
    }

    @Test
    public void diagnosticMessageSimplifiesCantResolve() {
        var diags = new ArrayList<Diagnostic>();
        var srv = LanguageServerFixture.getJavaLanguageServer(diags::add);
        srv.lint(List.of(FindResource.path("org/javacs/err/CantResolve.java")));
        var diag = diags.stream()
                .filter(d -> d.code != null && d.code.contains("cant.resolve"))
                .findFirst();
        assertTrue("expected cant.resolve error", diag.isPresent());
        assertThat(diag.get().message, is("cannot resolve symbol 'totals'"));
    }

    // TODO warn on type.equals(otherType)
    // TODO warn on map.get(wrongKeyType)
}
