package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

class CatchException implements Rewrite {
    final String className;
    final String exceptionType;
    final int position;

    CatchException(String className, String exceptionType, int position) {
        this.className = className;
        this.exceptionType = exceptionType;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return CANCELLED;
    }
}
