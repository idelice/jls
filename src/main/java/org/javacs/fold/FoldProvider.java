package org.javacs.fold;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.ParseTask;
import org.javacs.lsp.*;

public class FoldProvider {
    private static final Logger LOG = Logger.getLogger("main");

    final CompilerProvider compiler;

    public FoldProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<FoldingRange> foldingRanges(Path file) {
        var task = compiler.parse(file);
        var imports = new ArrayList<TreePath>();
        var blocks = new ArrayList<TreePath>();
        // TODO find comment trees
        class FindFoldingRanges extends TreePathScanner<Void, Void> {
            @Override
            public Void visitClass(ClassTree t, Void __) {
                blocks.add(getCurrentPath());
                return super.visitClass(t, null);
            }

            @Override
            public Void visitBlock(BlockTree t, Void __) {
                blocks.add(getCurrentPath());
                return super.visitBlock(t, null);
            }

            @Override
            public Void visitImport(ImportTree t, Void __) {
                imports.add(getCurrentPath());
                return null;
            }
        }
        new FindFoldingRanges().scan(task.root(), null);

        var all = new ArrayList<FoldingRange>();

        // Merge import ranges
        if (!imports.isEmpty()) {
            FoldingRange merged = null;
            for (var i : imports) {
                var r = asFoldingRange(task, i, FoldingRangeKind.Imports);
                if (r == null) {
                    continue;
                }
                if (merged == null) {
                    merged = r;
                    continue;
                }
                if (r.startLine <= merged.endLine + 1) {
                    merged =
                            new FoldingRange(
                                    merged.startLine,
                                    merged.startCharacter,
                                    r.endLine,
                                    r.endCharacter,
                                    FoldingRangeKind.Imports);
                } else {
                    all.add(merged);
                    merged = r;
                }
            }
            if (merged != null) {
                all.add(merged);
            }
        }

        // Convert blocks and comments
        for (var t : blocks) {
            var range = asFoldingRange(task, t, FoldingRangeKind.Region);
            if (range != null) {
                all.add(range);
            }
        }

        return all;
    }

    private FoldingRange asFoldingRange(ParseTask task, TreePath t, String kind) {
        var trees = Trees.instance(task.task());
        var pos = trees.getSourcePositions();
        var lines = t.getCompilationUnit().getLineMap();
        var start = (int) pos.getStartPosition(t.getCompilationUnit(), t.getLeaf());
        var end = (int) pos.getEndPosition(t.getCompilationUnit(), t.getLeaf());

        return rangeFromPositions(lines, start, end, t.getLeaf(), t.getCompilationUnit(), kind);
    }

    private static FoldingRange rangeFromPositions(
            LineMap lines, int start, int end, Tree leaf, CompilationUnitTree root, String kind) {
        if (start < 0 || end < 0) {
            LOG.fine(
                    String.format(
                            "[fold] skipping invalid source positions start=%d end=%d kind=%s",
                            start, end, kind));
            return null;
        }

        // If this is a class tree, adjust start position to '{'
        if (leaf instanceof ClassTree) {
            CharSequence content;
            try {
                content = root.getSourceFile().getCharContent(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (var i = start; i < content.length(); i++) {
                if (content.charAt(i) == '{') {
                    start = i;
                    break;
                }
            }
        }

        // Convert offset to 0-based line and character
        var startLine = (int) lines.getLineNumber(start) - 1;
        var startChar = (int) lines.getColumnNumber(start) - 1;
        var endLine = (int) lines.getLineNumber(end) - 1;
        var endChar = (int) lines.getColumnNumber(end) - 1;

        // If this is a block, move end position back one line so we don't fold the '}'
        if (leaf instanceof ClassTree || leaf instanceof BlockTree) {
            endLine--;
        }

        return new FoldingRange(startLine, startChar, endLine, endChar, kind);
    }
}
