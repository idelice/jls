package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class InlineField implements Rewrite {
    final String className, fieldName;

    InlineField(String className, String fieldName) {
        this.className = className;
        this.fieldName = fieldName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Rewrite.CANCELLED;
    }
}
