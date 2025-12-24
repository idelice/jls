package org.javacs.rewrite;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class AddException implements Rewrite {
    final String className, methodName;
    final String[] erasedParameterTypes;
    final String exceptionType;

    public AddException(String className, String methodName, String[] erasedParameterTypes, String exceptionType) {
        this.className = className;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
        this.exceptionType = exceptionType;
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
            var lines = task.root().getLineMap();
            var startBody = pos.getStartPosition(task.root(), methodTree.getBody());
            var line = (int) lines.getLineNumber(startBody);
            var column = (int) lines.getColumnNumber(startBody);
            var insertPos = new Position(line - 1, column - 1);
            var packageName = "";
            var simpleName = exceptionType;
            var lastDot = simpleName.lastIndexOf('.');
            if (lastDot != -1) {
                packageName = exceptionType.substring(0, lastDot);
                simpleName = exceptionType.substring(lastDot + 1);
            }
            String insertText;
            if (methodTree.getThrows().isEmpty()) {
                insertText = "throws " + simpleName + " ";
            } else {
                insertText = ", " + simpleName + " ";
            }
            var insertThrows = new TextEdit(new Range(insertPos, insertPos), insertText);
            // TODO add import if needed
            TextEdit[] edits = {insertThrows};
            return Map.of(file, edits);
        }
    }
}
