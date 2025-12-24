package org.javacs.rewrite;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class AutoAddOverrides implements Rewrite {
    private final Path file;

    public AutoAddOverrides(Path file) {
        this.file = file;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var missing = new ArrayList<TreePath>();
            new FindMissingOverride(task.task).scan(task.root(), missing);
            var list = addOverrides(task, missing);
            return Map.of(file, list.toArray(new TextEdit[list.size()]));
        }
    }

    private List<TextEdit> addOverrides(CompileTask task, List<TreePath> missing) {
        var edits = new ArrayList<TextEdit>();
        var pos = Trees.instance(task.task).getSourcePositions();
        for (var t : missing) {
            var lines = t.getCompilationUnit().getLineMap();
            var methodStart = pos.getStartPosition(t.getCompilationUnit(), t.getLeaf());
            var insertLine = lines.getLineNumber(methodStart);
            var indent = methodStart - lines.getPosition(insertLine, 0);
            var insertText = new StringBuilder();
            for (var i = 0; i < indent; i++) insertText.append(' ');
            insertText.append("@Override");
            insertText.append('\n');
            var insertPosition = new Position((int) insertLine - 1, 0);
            var insert = new TextEdit(new Range(insertPosition, insertPosition), insertText.toString());
            edits.add(insert);
        }
        return edits;
    }
}
