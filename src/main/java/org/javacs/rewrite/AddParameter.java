package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class AddParameter implements Rewrite {
    final String className, methodName;
    final String[] erasedParameterTypes;
    final JavaType newParameterType;
    final int insertAfterParameter;

    AddParameter(
            String className,
            String methodName,
            String[] erasedParameterTypes,
            JavaType newParameterType,
            int insertAfterParameter) {
        this.className = className;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
        this.newParameterType = newParameterType;
        this.insertAfterParameter = insertAfterParameter;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Rewrite.CANCELLED;
    }
}
