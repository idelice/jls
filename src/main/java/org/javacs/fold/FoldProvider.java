package org.javacs.fold;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.javacs.CompilerProvider;
import org.javacs.ParseTask;
import org.javacs.lsp.*;

public class FoldProvider {

    final CompilerProvider compiler;

    public FoldProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<FoldingRange> foldingRanges(Path file) {
        var task = compiler.parse(file);
        var imports = new ArrayList<TreePath>();
        var blocks = new ArrayList<TreePath>();
        // TODO find comment trees
        var comments = new ArrayList<TreePath>();
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
        new FindFoldingRanges().scan(task.root, null);

        var all = new ArrayList<FoldingRange>();

        // Merge import ranges
        if (!imports.isEmpty()) {
            var merged = asFoldingRange(task, imports.get(0), FoldingRangeKind.Imports);
            for (var i : imports) {
                var r = asFoldingRange(task, i, FoldingRangeKind.Imports);
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
            all.add(merged);
        }

        // Convert blocks and comments
        for (var t : blocks) {
            all.add(asFoldingRange(task, t, FoldingRangeKind.Region));
        }
        for (var t : comments) {
            all.add(asFoldingRange(task, t, FoldingRangeKind.Region));
        }

        return all;
    }

    private FoldingRange asFoldingRange(ParseTask task, TreePath t, String kind) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var lines = t.getCompilationUnit().getLineMap();
        var start = (int) pos.getStartPosition(t.getCompilationUnit(), t.getLeaf());
        var end = (int) pos.getEndPosition(t.getCompilationUnit(), t.getLeaf());

        // If this is a class tree, adjust start position to '{'
        if (t.getLeaf() instanceof ClassTree) {
            CharSequence content;
            try {
                content = t.getCompilationUnit().getSourceFile().getCharContent(true);
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
        if (t.getLeaf() instanceof ClassTree || t.getLeaf() instanceof BlockTree) {
            endLine--;
        }

        return new FoldingRange(startLine, startChar, endLine, endChar, kind);
    }
}
