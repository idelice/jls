package org.javacs.rewrite;

import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.ParseTask;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class ConvertVariableToStatement implements Rewrite {
    final Path file;
    final int position;

    public ConvertVariableToStatement(Path file, int position) {
        this.file = file;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var task = compiler.parse(file);
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var lines = task.root.getLineMap();
        var variable = findVariable(task, position);
        if (variable == null) {
            return CANCELLED;
        }
        var expression = variable.getInitializer();
        if (expression == null) {
            return CANCELLED;
        }
        if (!isExpressionStatement(expression)) {
            return CANCELLED;
        }
        var start = pos.getStartPosition(task.root, variable);
        var end = pos.getStartPosition(task.root, expression);
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var startPos = new Position(startLine - 1, startColumn - 1);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        var endPos = new Position(endLine - 1, endColumn - 1);
        var delete = new Range(startPos, endPos);
        var edit = new TextEdit(delete, "");
        TextEdit[] edits = {edit};
        return Map.of(file, edits);
    }

    static VariableTree findVariable(ParseTask task, int position) {
        return new FindVariableAt(task.task).scan(task.root, position);
    }

    /** https://docs.oracle.com/javase/specs/jls/se13/html/jls-14.html#jls-14.8 */
    static boolean isExpressionStatement(Tree t) {
        switch (t.getKind()) {
            case ASSIGNMENT:
            case PREFIX_INCREMENT:
            case PREFIX_DECREMENT:
            case POSTFIX_INCREMENT:
            case POSTFIX_DECREMENT:
            case METHOD_INVOCATION:
            case NEW_CLASS:
                return true;
            default:
                return false;
        }
    }
}
