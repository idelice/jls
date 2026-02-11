package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import org.javacs.lsp.FoldingRangeParams;
import org.javacs.lsp.TextDocumentIdentifier;
import org.junit.Test;

public class FoldingRangeTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void foldingRangeOnParseErrorDoesNotThrow() {
        var uri = FindResource.uri("/org/javacs/example/FixParseErrorBefore.java");
        var params = new FoldingRangeParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        var ranges = server.foldingRange(params);
        assertThat(ranges, notNullValue());
        for (var r : ranges) {
            assertThat(r.startLine, greaterThanOrEqualTo(0));
            assertThat(r.endLine, greaterThanOrEqualTo(0));
        }
    }
}
