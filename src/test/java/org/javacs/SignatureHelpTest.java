package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;

public class SignatureHelpTest {
    @Test
    public void overriddenInterfaceMethodPreservesRealOverloads() {
        var labels = labels("/org/javacs/example/SignatureHelpOverrides.java", 18, 20);
        assertThat(labels, containsInAnyOrder(
                "setup(int numberOfChannels)", "setup(String configuration)"));
    }

    @Test
    public void overriddenNoArgumentInterfaceMethodHasOneSignature() {
        assertThat(labels("/org/javacs/example/SignatureHelpOverrides.java", 19, 26),
                contains("isBroadcast()"));
    }

    @Test
    public void overriddenSuperclassMethodPreservesInheritedAndDirectOverloads() {
        assertThat(labels("/org/javacs/example/SignatureHelpOverrides.java", 33, 20),
                containsInAnyOrder("setup(int channels)", "setup(long channels)", "setup(String configuration)"));
    }

    @Test
    public void overriddenMethodsHaveCorrectCompletionOverloadCounts() {
        var uri = FindResource.uri("/org/javacs/example/SignatureHelpOverrides.java");
        var path = java.nio.file.Paths.get(uri);
        server.completionIndexScheduler.ensureIndexed(path);
        server.completionIndexScheduler.awaitReady(10_000);
        var params = new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(17, 13));
        var items = server.completion(params).orElseThrow().items;
        for (var expected : java.util.Map.of(
                "setup", 1, "isBroadcast", 0, "equals", 0, "hashCode", 0, "wait", 2).entrySet()) {
            var matches = items.stream().filter(item -> item.label.equals(expected.getKey())).toList();
            assertThat(expected.getKey(), matches, hasSize(1));
            var data = JsonHelper.GSON.fromJson(matches.get(0).data, CompletionData.class);
            assertThat(expected.getKey(), data.plusOverloads, equalTo(expected.getValue()));
        }
    }

    @Test
    public void signatureHelp() {
        var help = doHelp("/org/javacs/example/SignatureHelp.java", 7, 36);
        assertThat(help.signatures, hasSize(2));
    }

    @Test
    public void partlyFilledIn() {
        var help = doHelp("/org/javacs/example/SignatureHelp.java", 8, 39);
        assertThat(help.signatures, hasSize(2));
        assertThat(help.activeSignature, equalTo(1));
        assertThat(help.activeParameter, equalTo(1));
    }

    @Test
    @Ignore("Low-latency signature help uses parse-only; cannot resolve local type constructors")
    public void constructor() {
        var help = doHelp("/org/javacs/example/SignatureHelp.java", 9, 27);
        assertThat(help.signatures, hasSize(1));
        assertThat(help.signatures.get(0).label, startsWith("SignatureHelp"));
    }

    @Test
    @Ignore("Low-latency signature help uses parse-only; cannot resolve platform type constructors")
    public void platformConstructor() {
        var help = doHelp("/org/javacs/example/SignatureHelp.java", 10, 26);
        assertThat(help.signatures, not(empty()));
        assertThat(help.signatures.get(0).label, startsWith("ArrayList"));
        // TODO
        // assertThat(help.signatures.get(0).documentation, not(nullValue()));
    }

    @Test
    public void overloads() {
        var labels = labels("/org/javacs/example/Overloads.java", 5, 15);
        assertThat(labels, hasItem(containsString("print(int i)")));
        assertThat(labels, hasItem(containsString("print(String s)")));
    }

    @Test
    public void localDoc() {
        var help = doHelp("/org/javacs/example/LocalMethodDoc.java", 5, 23);
        assertThat(help.signatures, not(empty()));
    }

    @Test
    @Ignore("Low-latency signature help intentionally avoids Lombok-generated symbols")
    public void lombokSetterSignature() {
        var help = doHelp("/org/javacs/example/LombokSignatureHelp.java", 15, 23);
        assertThat(help.signatures, hasSize(1));
        assertThat(help.signatures.get(0).label, containsString("setItems(List<String> items)"));
    }

    @Test
    @Ignore("Low-latency signature help intentionally avoids Lombok-generated symbols")
    public void lombokBuilderSetterSignature() {
        var help = doHelp("/org/javacs/example/LombokSignatureHelp.java", 16, 53);
        assertThat(help.signatures, hasSize(1));
        assertThat(help.signatures.get(0).label, containsString("items(List<String> items)"));
    }

    @Test
    public void lombokSignatureHelpDoesNotCrash() {
        var help = doHelp("/org/javacs/example/LombokSignatureHelp.java", 15, 23);
        assertThat(help.signatures, notNullValue());
    }

    @Test
    public void staticImportSignatureHelp() {
        var help = doHelp("/org/javacs/example/SignatureHelpStaticImport.java", 8, 23);
        assertThat(help.signatures, not(empty()));
        var label = help.signatures.get(0).label;
        assertThat(label, not(containsString("arg0")));
    }

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @AfterClass
    public static void shutdown() {
        server.shutdown();
    }

    private SignatureHelp doHelp(String file, int row, int column) {
        var document = new TextDocumentIdentifier();
        document.uri = FindResource.uri(file);
        server.completionIndexScheduler.ensureIndexed(java.nio.file.Paths.get(document.uri));
        server.completionIndexScheduler.awaitReady(10_000);
        var position = new Position();
        position.line = row - 1;
        position.character = column - 1;
        var p = new TextDocumentPositionParams();
        p.textDocument = document;
        p.position = position;
        var maybe = server.signatureHelp(p);
        if (maybe.isEmpty()) fail("not supported");
        return maybe.get();
    }

    private List<String> labels(String file, int row, int column) {
        var help = doHelp(file, row, column);
        var result = new ArrayList<String>();
        for (var s : help.signatures) {
            result.add(s.label);
        }
        return result;
    }
}
