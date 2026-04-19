package org.javacs;

import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import java.net.URI;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.javacs.lsp.Diagnostic;
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.PublishDiagnosticsParams;
import org.javacs.lsp.ShowMessageParams;
import org.junit.Test;

public class LombokProcessorIntegrationTest {
    @Test
    public void diagnosticsResolveGeneratedMembersAcrossUnits() throws Exception {
        var client = new RecordingClient();
        var server = LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);
        var useFile = FindResource.path("org/javacs/example/LombokCrossTypeDiagnostics.java");
        var uri = useFile.toUri();

        server.lint(List.of(useFile));
        assertTrue("expected diagnostics for " + uri, client.awaitDiagnosticsForUri(uri, 10, TimeUnit.SECONDS));

        var unresolvedOnUseFile =
                client.diagnostics(uri).stream()
                        .filter(d -> d.code != null && d.code.startsWith("compiler.err.cant.resolve"))
                        .map(d -> d.message)
                        .collect(Collectors.toList());
        assertOnlyExpectedGeneratedAccessorFallback(
                unresolvedOnUseFile,
                List.of(
                        "method setName(java.lang.String)",
                        "method getName()"));
    }

    @Test
    public void diagnosticsResolveGeneratedMembersAcrossNestedLombokUnits() throws Exception {
        var client = new RecordingClient();
        var server = LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.DEFAULT_WORKSPACE_ROOT, client);
        var useFile = FindResource.path("org/javacs/example/LombokNestedDiagnostics.java");
        var uri = useFile.toUri();

        server.lint(List.of(useFile));
        assertTrue("expected diagnostics for " + uri, client.awaitDiagnosticsForUri(uri, 10, TimeUnit.SECONDS));

        var unresolvedOnUseFile =
                client.diagnostics(uri).stream()
                        .filter(d -> d.code != null && d.code.startsWith("compiler.err.cant.resolve"))
                        .map(d -> d.message)
                        .collect(Collectors.toList());
        assertOnlyExpectedGeneratedAccessorFallback(
                unresolvedOnUseFile,
                List.of("method getBar()"));
    }

    private static void assertOnlyExpectedGeneratedAccessorFallback(
            List<String> unresolvedMessages, List<String> expectedFragments) {
        if (unresolvedMessages.isEmpty()) {
            return;
        }
        assertTrue(
                "unexpected unresolved symbols: " + unresolvedMessages,
                unresolvedMessages.size() == expectedFragments.size()
                        && unresolvedMessages.stream()
                                .allMatch(
                                        message ->
                                                expectedFragments.stream()
                                                        .anyMatch(message::contains)));
    }

    private static final class RecordingClient implements LanguageClient {
        private final Map<URI, List<Diagnostic>> diagnosticsByUri = new ConcurrentHashMap<>();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnosticsByUri.put(params.uri, List.copyOf(params.diagnostics));
        }

        List<Diagnostic> diagnostics(URI uri) {
            return diagnosticsByUri.getOrDefault(uri, List.of());
        }

        boolean awaitDiagnosticsForUri(URI uri, long timeout, TimeUnit unit) throws InterruptedException {
            var deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (diagnosticsByUri.containsKey(uri)) {
                    return true;
                }
                Thread.sleep(20);
            }
            return false;
        }

        @Override
        public void showMessage(ShowMessageParams params) {}

        @Override
        public void registerCapability(String method, JsonElement options) {}

        @Override
        public void customNotification(String method, JsonElement params) {}
    }
}
