package org.javacs.rewrite;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.Map;
import javax.lang.model.element.Modifier;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class ConvertFieldToBlock implements Rewrite {
    final Path file;
    final int position;

    public ConvertFieldToBlock(Path file, int position) {
        this.file = file;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var task = compiler.parse(file);
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var lines = task.root.getLineMap();
        var variable = ConvertVariableToStatement.findVariable(task, position);
        if (variable == null) {
            return CANCELLED;
        }
        var expression = variable.getInitializer();
        if (!ConvertVariableToStatement.isExpressionStatement(expression)) {
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
        var deleteLhs = new Range(startPos, endPos);
        var fixLhs = new TextEdit(deleteLhs, "{ ");
        if (variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
            fixLhs.newText = "static { ";
        }
        var right = pos.getEndPosition(task.root, variable);
        var rightLine = (int) lines.getLineNumber(right);
        var rightColumn = (int) lines.getColumnNumber(right);
        var rightPos = new Position(rightLine - 1, rightColumn - 1);
        var insertRight = new Range(rightPos, rightPos);
        var fixRhs = new TextEdit(insertRight, " }");
        TextEdit[] edits = {fixLhs, fixRhs};
        return Map.of(file, edits);
    }
}
