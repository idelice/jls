package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class ExtractVariable implements Rewrite {
    final String className;
    final JavaType type;
    final int startPosition, endPosition;

    ExtractVariable(String className, JavaType type, int startPosition, int endPosition) {
        this.className = className;
        this.type = type;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Rewrite.CANCELLED;
    }
}
