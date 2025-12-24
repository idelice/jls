package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

public interface Rewrite {
    /** Perform a rewrite across the entire codebase. */
    Map<Path, TextEdit[]> rewrite(CompilerProvider compiler);
    /** CANCELLED signals that the rewrite couldn't be completed. */
    Map<Path, TextEdit[]> CANCELLED = Map.of();

    Rewrite NOT_SUPPORTED = new RewriteNotSupported();
}
