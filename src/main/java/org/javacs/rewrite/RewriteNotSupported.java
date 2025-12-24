package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class RewriteNotSupported implements Rewrite {
    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return CANCELLED;
    }
}
