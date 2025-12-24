package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

public class ChangeMethodAccess implements Rewrite {
    final String className, methodName;
    final String[] erasedParameterTypes;
    /** 0 = private; 1 = package-private; 2 = protected; 3 = public */
    final int newAccess;

    public ChangeMethodAccess(String className, String methodName, String[] erasedParameterTypes, int newAccess) {
        this.className = className;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
        this.newAccess = newAccess;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return CANCELLED;
    }
}
