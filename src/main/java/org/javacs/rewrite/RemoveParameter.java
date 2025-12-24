package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class RemoveParameter implements Rewrite {
    final String className, methodName;
    final String[] erasedParameterTypes;
    final int parameterToRemove;

    RemoveParameter(String className, String methodName, String[] erasedParameterTypes, int parameterToRemove) {
        this.className = className;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
        this.parameterToRemove = parameterToRemove;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Rewrite.CANCELLED;
    }
}
