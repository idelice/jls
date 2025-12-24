package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class ExtractMethod implements Rewrite {
    final String className, methodName;
    final String[] parameterNames;
    final JavaType[] parameterTypes;
    final JavaType returnType;
    final int startPosition, endPosition;

    ExtractMethod(
            String className,
            String methodName,
            String[] parameterNames,
            JavaType[] parameterTypes,
            JavaType returnType,
            int startPosition,
            int endPosition) {
        this.className = className;
        this.methodName = methodName;
        this.parameterNames = parameterNames;
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return Rewrite.CANCELLED;
    }
}
