package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class ExtractConstant implements Rewrite {
    final String className, fieldName;
    final JavaType type;
    final int startPosition, endPosition;

    ExtractConstant(String className, String fieldName, JavaType type, int startPosition, int endPosition) {
        this.className = className;
        this.fieldName = fieldName;
        this.type = type;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Rewrite.CANCELLED;
    }
}
