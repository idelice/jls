package org.javacs.markup;

import com.sun.source.tree.CompilationUnitTree;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

class RangeHelper {
    static Range range(CompilationUnitTree root, long start, long end) {
        try {
            return org.javacs.FileStore.range(root.getSourceFile().getCharContent(true).toString(), start, end);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
