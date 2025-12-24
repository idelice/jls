package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class CreateMissingField implements Rewrite {
    final String className, fieldName;
    final JavaType fieldType;

    CreateMissingField(String className, String fieldName, JavaType fieldType) {
        this.className = className;
        this.fieldName = fieldName;
        this.fieldType = fieldType;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Rewrite.CANCELLED;
    }
}
