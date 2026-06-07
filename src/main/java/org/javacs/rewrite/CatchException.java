package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.action.FindMethodDeclarationAt;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class CatchException implements Rewrite {
    final String className;
    final String exceptionType;
    final int startPosition, endPosition;

    public CatchException(String className, String exceptionType, int startPosition, int endPosition) {
        this.className = className;
        this.exceptionType = exceptionType;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var file = compiler.findTypeDeclaration(className);
        if (file == CompilerProvider.NOT_FOUND) {
            return CANCELLED;
        }
        try (var task = compiler.compileFast(file)) {
            var root = task.root(file);
            var trees = Trees.instance(task.task);
            var pos = trees.getSourcePositions();
            var lines = root.getLineMap();

            var method = new FindMethodDeclarationAt(task.task).scan(root, (long) startPosition);
            if (method == null) {
                return CANCELLED;
            }

            var body = method.getBody();
            if (body == null) {
                return CANCELLED;
            }

            // Find ALL statements that overlap with the selected range
            var targetStatements = new ArrayList<Tree>();
            for (var stmt : body.getStatements()) {
                var stmtStart = pos.getStartPosition(root, stmt);
                var stmtEnd = pos.getEndPosition(root, stmt);
                if (stmtStart < endPosition && stmtEnd > startPosition) {
                    targetStatements.add(stmt);
                }
            }
            if (targetStatements.isEmpty()) {
                return CANCELLED;
            }

            if (isInsideTryBlock(root, targetStatements.get(0), task.task)) {
                return CANCELLED;
            }

            var firstStmt = targetStatements.get(0);
            var lastStmt = targetStatements.get(targetStatements.size() - 1);
            var stmtStart = pos.getStartPosition(root, firstStmt);
            var stmtEnd = pos.getEndPosition(root, lastStmt);

            CharSequence source;
            try {
                source = root.getSourceFile().getCharContent(true);
            } catch (IOException e) {
                return CANCELLED;
            }
            var originalSource = source.subSequence((int) stmtStart, (int) stmtEnd).toString();

            var stmtLine = lines.getLineNumber(stmtStart);
            var stmtLineStartPos = lines.getStartPosition(stmtLine);
            var indent = (int) (stmtStart - stmtLineStartPos);

            var exType = exceptionType;
            if (exType == null || exType.isEmpty()) {
                exType = "Exception";
            }
            var simpleName = exType;
            var lastDot = simpleName.lastIndexOf('.');
            if (lastDot != -1) {
                simpleName = exType.substring(lastDot + 1);
            }

            var outerIndent = " ".repeat(indent);
            var innerIndent = " ".repeat(indent + 4);

            var originalLines = originalSource.split("\n", -1);
            var indentedLines = new String[originalLines.length];
            for (int i = 0; i < originalLines.length; i++) {
                indentedLines[i] = innerIndent + originalLines[i].stripLeading();
            }
            var indentedSource = String.join("\n", indentedLines);

            var replacement = outerIndent + "try {\n"
                    + indentedSource + "\n"
                    + outerIndent + "} catch (" + simpleName + " e) {\n"
                    + innerIndent + "// TODO: handle exception\n"
                    + outerIndent + "}";

            var stmtStartLine = (int) lines.getLineNumber(stmtStart);
            var stmtStartCol = (int) lines.getColumnNumber(stmtStart);
            var stmtEndLine = (int) lines.getLineNumber(stmtEnd);
            var stmtEndCol = (int) lines.getColumnNumber(stmtEnd);

            var rangeStart = new Position(stmtStartLine - 1, stmtStartCol - 1);
            var rangeEnd = new Position(stmtEndLine - 1, stmtEndCol - 1);
            var range = new Range(rangeStart, rangeEnd);

            var edit = new TextEdit(range, replacement);
            return Map.of(file, new TextEdit[] {edit});
        }
    }

    private static boolean isInsideTryBlock(CompilationUnitTree root, Tree target, JavacTask task) {
        var trees = Trees.instance(task);
        var path = trees.getPath(root, target);
        while (path != null) {
            if (path.getLeaf().getKind() == Tree.Kind.TRY) {
                return true;
            }
            path = path.getParentPath();
        }
        return false;
    }

}
