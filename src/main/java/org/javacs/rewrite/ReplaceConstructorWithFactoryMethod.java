package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class ReplaceConstructorWithFactoryMethod implements Rewrite {
    final String className;
    final String[] erasedParameterTypes;
    final String factoryMethodName;

    ReplaceConstructorWithFactoryMethod(String className, String[] erasedParameterTypes, String factoryMethodName) {
        this.className = className;
        this.erasedParameterTypes = erasedParameterTypes;
        this.factoryMethodName = factoryMethodName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return CANCELLED;
    }
}
