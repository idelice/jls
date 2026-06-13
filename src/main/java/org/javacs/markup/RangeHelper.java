package org.javacs.markup;

import com.sun.source.tree.CompilationUnitTree;
import org.javacs.FileStore;
import org.javacs.lsp.Range;

class RangeHelper {
    static Range range(CompilationUnitTree root, long start, long end) {
        try {
            var content = root.getSourceFile().getCharContent(true).toString();
            // Guard against javac reporting positions beyond file end on malformed source
            var safeEnd = Math.min(end, content.length());
            var safeStart = Math.min(start, safeEnd);
            return FileStore.range(content, safeStart, safeEnd);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
