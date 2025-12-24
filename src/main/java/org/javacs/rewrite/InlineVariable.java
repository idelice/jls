package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class InlineVariable implements Rewrite {
    final String className;
    final int position;

    InlineVariable(String className, int position) {
        this.className = className;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Rewrite.CANCELLED;
    }
}
