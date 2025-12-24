package org.javacs.rewrite;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class AddSuppressWarningAnnotation implements Rewrite {
    final String className, methodName;
    final String[] erasedParameterTypes;

    public AddSuppressWarningAnnotation(String className, String methodName, String[] erasedParameterTypes) {
        this.className = className;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var file = compiler.findTypeDeclaration(className);
        if (file == CompilerProvider.NOT_FOUND) {
            return CANCELLED;
        }
        try (var task = compiler.compile(file)) {
            var trees = Trees.instance(task.task);
            var methodElement = FindHelper.findMethod(task, className, methodName, erasedParameterTypes);
            var methodTree = trees.getTree(methodElement);
            var pos = trees.getSourcePositions();
            var startMethod = (int) pos.getStartPosition(task.root(), methodTree);
            var lines = task.root().getLineMap();
            var line = (int) lines.getLineNumber(startMethod);
            var column = (int) lines.getColumnNumber(startMethod);
            var startLine = (int) lines.getStartPosition(line);
            var indent = " ".repeat(startMethod - startLine);
            var insertText = "@SuppressWarnings(\"unchecked\")\n" + indent;
            var insertPoint = new Position(line - 1, column - 1);
            var insert = new TextEdit(new Range(insertPoint, insertPoint), insertText);
            TextEdit[] edits = {insert};
            return Map.of(file, edits);
        }
    }
}
