package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class ExtractVariable implements Rewrite {
    final String className;
    final JavaType type;
    final int startPosition, endPosition;

    public ExtractVariable(String className, JavaType type, int startPosition, int endPosition) {
        this.className = className;
        this.type = type;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var file = compiler.findTypeDeclaration(className);
        if (file == null || file == CompilerProvider.NOT_FOUND) return CANCELLED;

        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var trees = Trees.instance(task.task);
            var pos = trees.getSourcePositions();
            var source = root.getSourceFile().getCharContent(true);

            // Skip leading whitespace in the selected range — shift+V starts at column 0
            var adjStart = startPosition;
            while (adjStart < endPosition && Character.isWhitespace(source.charAt(adjStart))) {
                adjStart++;
            }
            if (adjStart >= endPosition) return CANCELLED;

            var expressionText = source.subSequence(adjStart, endPosition).toString();

            // Strip trailing semicolons and whitespace — shift+V selects the full line
            expressionText = expressionText.replaceAll(";\\s*$", "").stripTrailing();

            if (expressionText.isEmpty() || isSimpleIdentifier(expressionText)) {
                return CANCELLED;
            }

            var enclosingStmt =
                    new FindEnclosingStatement(task.task, root, adjStart, endPosition).scan(root, null);

            if (enclosingStmt == null) return CANCELLED;

            long stmtStart = pos.getStartPosition(root, enclosingStmt);
            long stmtEnd = pos.getEndPosition(root, enclosingStmt);
            if (adjStart < stmtStart || endPosition > stmtEnd) {
                return CANCELLED;
            }

            var varName = generateVariableName(expressionText);
            var typeName = type.name;

            var stmtLine = (int) root.getLineMap().getLineNumber(stmtStart);
            var stmtLineStart = root.getLineMap().getStartPosition(stmtLine);
            var stmtIndent = (int) (stmtStart - stmtLineStart);
            var indent = " ".repeat(stmtIndent);

            var insertPos = new Position(stmtLine - 1, 0);
            var declaration = indent + typeName + " " + varName + " = " + expressionText + ";\n";
            TextEdit edit1 = new TextEdit(new Range(insertPos, insertPos), declaration);

            var exprStartLine = (int) root.getLineMap().getLineNumber(adjStart);
            var exprStartCol = (int) root.getLineMap().getColumnNumber(adjStart);
            var exprEndLine = (int) root.getLineMap().getLineNumber(endPosition);
            var exprEndCol = (int) root.getLineMap().getColumnNumber(endPosition);
            var exprRange =
                    new Range(
                            new Position(exprStartLine - 1, exprStartCol - 1),
                            new Position(exprEndLine - 1, exprEndCol - 1));
            TextEdit edit2 = new TextEdit(exprRange, varName);

            // edit1 is at the statement line (lower offset), edit2 at the expression
            // (higher offset). Apply in reverse order: edit2 first, then edit1, so that
            // edit1's line insertion does not shift edit2's position.
            TextEdit[] edits = {edit1, edit2};
            return Map.of(file, edits);
        } catch (IOException e) {
            return CANCELLED;
        }
    }

    private static boolean isSimpleIdentifier(String text) {
        if (text == null || text.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(text.charAt(0))) return false;
        for (int i = 1; i < text.length(); i++) {
            if (!Character.isJavaIdentifierPart(text.charAt(i))) return false;
        }
        return true;
    }

    static String generateVariableName(String text) {
        // Method call pattern: something.methodName(args) or methodName(args)
        int paren = text.indexOf('(');
        if (paren > 0) {
            var before = text.substring(0, paren).trim();
            int dot = before.lastIndexOf('.');
            var name = (dot >= 0 && dot < before.length() - 1) ? before.substring(dot + 1) : before;
            if (!name.isEmpty() && Character.isJavaIdentifierStart(name.charAt(0))) {
                return name;
            }
        }

        // Member/field access pattern: something.field
        int dot = text.lastIndexOf('.');
        if (dot > 0 && dot < text.length() - 1) {
            var member = text.substring(dot + 1);
            if (!member.isEmpty()
                    && Character.isJavaIdentifierStart(member.charAt(0))
                    && !member.contains("(")) {
                return member;
            }
        }

        return "extracted";
    }

    /**
     * TreeScanner that finds the innermost statement-level tree containing a given source position
     * range.
     */
    private static class FindEnclosingStatement extends TreeScanner<Tree, Void> {
        private final SourcePositions pos;
        private final CompilationUnitTree root;
        private final int startPos, endPos;

        FindEnclosingStatement(JavacTask task, CompilationUnitTree root, int startPos, int endPos) {
            this.pos = Trees.instance(task).getSourcePositions();
            this.root = root;
            this.startPos = startPos;
            this.endPos = endPos;
        }

        private boolean contains(Tree t) {
            long start = pos.getStartPosition(root, t);
            long end = pos.getEndPosition(root, t);
            return start <= startPos && endPos <= end;
        }

        @Override
        public Tree visitExpressionStatement(ExpressionStatementTree t, Void p) {
            Tree inner = super.visitExpressionStatement(t, p);
            if (inner != null) return inner;
            return contains(t) ? t : null;
        }

        @Override
        public Tree visitVariable(VariableTree t, Void p) {
            Tree inner = super.visitVariable(t, p);
            if (inner != null) return inner;
            return contains(t) ? t : null;
        }

        @Override
        public Tree visitReturn(ReturnTree t, Void p) {
            Tree inner = super.visitReturn(t, p);
            if (inner != null) return inner;
            return contains(t) ? t : null;
        }

        @Override
        public Tree reduce(Tree r1, Tree r2) {
            if (r1 != null) return r1;
            return r2;
        }
    }
}
