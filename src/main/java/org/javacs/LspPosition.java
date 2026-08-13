package org.javacs;

import com.sun.source.tree.CompilationUnitTree;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

/** Converts javac source offsets without expanding tabs into visual columns. */
public final class LspPosition {
    private LspPosition() {}

    public static long offset(CompilationUnitTree root, Position position) {
        if (position.line < 0 || position.character < 0) return -1;
        try {
            var lineStart = root.getLineMap().getStartPosition(position.line + 1);
            return lineStart < 0 ? -1 : lineStart + position.character;
        } catch (IndexOutOfBoundsException e) {
            return -1;
        }
    }

    public static Position position(CompilationUnitTree root, long offset) {
        var line = root.getLineMap().getLineNumber(offset);
        var lineStart = root.getLineMap().getStartPosition(line);
        return new Position((int) line - 1, (int) (offset - lineStart));
    }

    public static Range range(CompilationUnitTree root, long start, long end) {
        return new Range(position(root, start), position(root, end));
    }
}
