package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.lang.reflect.Method;
import org.javacs.lsp.FoldingRangeParams;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.fold.FoldProvider;
import org.junit.Test;

public class FoldProviderTest {

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void foldingRangeDoesNotCrashOnIncompleteAssignment() {
        var params = new FoldingRangeParams();
        params.textDocument = new TextDocumentIdentifier(FindResource.uri("/org/javacs/example/IncompleteAssignment.java"));
        var ranges = server.foldingRange(params);
        assertThat(ranges, notNullValue());
    }

    @Test
    public void foldingRangeSkipsInvalidOffsets() throws Exception {
        Method rangeFromPositions =
                FoldProvider.class.getDeclaredMethod(
                        "rangeFromPositions",
                        com.sun.source.tree.LineMap.class,
                        int.class,
                        int.class,
                        com.sun.source.tree.Tree.class,
                        com.sun.source.tree.CompilationUnitTree.class,
                        String.class);
        rangeFromPositions.setAccessible(true);
        var range = rangeFromPositions.invoke(null, null, -1, 72, null, null, "region");
        assertThat(range, nullValue());
    }
}
