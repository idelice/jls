package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;

import com.google.gson.JsonObject;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.InlayHintParams;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextDocumentIdentifier;
import org.junit.Test;

public class InlayHintTest {
    private final JavaLanguageServer server =
            LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.SIMPLE_WORKSPACE_ROOT, diagnostic -> {});

    @Test
    public void parameterNameHints() {
        var settings = new JsonObject();
        var java = new JsonObject();
        var hints = new JsonObject();
        hints.addProperty("enabled", true);
        hints.addProperty("parameterNames", true);
        hints.addProperty("varTypes", true);
        java.add("inlayHints", hints);
        settings.add("java", java);
        var config = new org.javacs.lsp.DidChangeConfigurationParams();
        config.settings = settings;
        server.didChangeConfiguration(config);

        var path = Paths.get("src/test/examples/simple-project/InlayHintsSimple.java").toAbsolutePath().normalize();
        var uri = path.toUri();
        var range = new Range(new Position(0, 0), new Position(200, 0));
        var params = new InlayHintParams(new TextDocumentIdentifier(uri), range);
        List<InlayHint> hintsResult = server.inlayHint(params);

        var labels = hintsResult.stream().map(h -> h.label).collect(Collectors.toList());
        assertThat(labels, hasItems("label: ", "count: "));
        boolean hasStringType = labels.stream().anyMatch(l -> l.contains("String"));
        org.junit.Assert.assertTrue("expected var type hint to contain String", hasStringType);
    }
}
