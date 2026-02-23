package org.javacs.hint;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompilerProvider;
import org.javacs.hover.ShortTypePrinter;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.InlayHintKind;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

public class InlayHintProvider {
    public List<InlayHint> inlayHints(CompilerProvider compiler, Path file, Range range) {
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var trees = Trees.instance(task.task);
            var positions = trees.getSourcePositions();
            var lines = root.getLineMap();
            var source = root.getSourceFile().getCharContent(true).toString();
            var filter = asOffsetRange(lines, range);
            var result = new ArrayList<InlayHint>();

            class FindInlayHints extends TreePathScanner<Void, Void> {
                @Override
                public Void visitVariable(VariableTree variable, Void aVoid) {
                    // Skip redundant hints when initializer already spells out the type.
                    if (isRedundantVarInitializer(variable.getInitializer())) {
                        return super.visitVariable(variable, aVoid);
                    }
                    var declarationStart = positions.getStartPosition(root, variable);
                    var declarationEnd = positions.getEndPosition(root, variable);
                    if (declarationEnd < 0) declarationEnd = declarationStart;
                    if (!isVarDeclaration(variable, source, declarationStart, declarationEnd)) {
                        return super.visitVariable(variable, aVoid);
                    }
                    if (!intersects(filter[0], filter[1], declarationStart, declarationEnd)) {
                        return super.visitVariable(variable, aVoid);
                    }

                    TypeMirror inferred = null;
                    var element = trees.getElement(getCurrentPath());
                    if (element instanceof javax.lang.model.element.VariableElement) {
                        inferred = ((javax.lang.model.element.VariableElement) element).asType();
                    }
                    if (inferred == null || inferred.getKind() == TypeKind.ERROR) {
                        inferred = trees.getTypeMirror(getCurrentPath());
                    }
                    if (inferred == null || inferred.getKind() == TypeKind.ERROR) {
                        return super.visitVariable(variable, aVoid);
                    }

                    var type = variable.getType();
                    var insertAt = positions.getEndPosition(root, type);
                    if (insertAt < 0) {
                        insertAt = findVarTokenEndOffset(variable, source, declarationStart, declarationEnd);
                    }
                    if (insertAt < 0) return super.visitVariable(variable, aVoid);

                    var line = (int) lines.getLineNumber(insertAt);
                    var column = (int) lines.getColumnNumber(insertAt);
                    if (line <= 0 || column <= 0) return super.visitVariable(variable, aVoid);

                    var hint = new InlayHint(new Position(line - 1, column - 1), ": " + ShortTypePrinter.NO_PACKAGE.print(inferred));
                    hint.kind = InlayHintKind.Type;
                    hint.paddingLeft = true;
                    result.add(hint);
                    return super.visitVariable(variable, aVoid);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree invocation, Void aVoid) {
                    addArgumentTypeHints(invocation.getArguments(), getCurrentPath());
                    return super.visitMethodInvocation(invocation, aVoid);
                }

                @Override
                public Void visitNewClass(NewClassTree invocation, Void aVoid) {
                    addArgumentTypeHints(invocation.getArguments(), getCurrentPath());
                    return super.visitNewClass(invocation, aVoid);
                }

                private void addArgumentTypeHints(List<? extends ExpressionTree> arguments, TreePath invocationPath) {
                    for (var argument : arguments) {
                        if (!isHintableArgument(argument)) continue;

                        var start = positions.getStartPosition(root, argument);
                        var end = positions.getEndPosition(root, argument);
                        if (end < 0) end = start;
                        if (!intersects(filter[0], filter[1], start, end)) continue;

                        var argumentPath = new TreePath(invocationPath, argument);
                        var type = trees.getTypeMirror(argumentPath);
                        if (type == null || type.getKind() == TypeKind.ERROR) continue;

                        var line = (int) lines.getLineNumber(start);
                        var column = (int) lines.getColumnNumber(start);
                        if (line <= 0 || column <= 0) continue;

                        var hint = new InlayHint(new Position(line - 1, column - 1), ShortTypePrinter.NO_PACKAGE.print(type) + ":");
                        hint.kind = InlayHintKind.Type;
                        hint.paddingRight = true;
                        result.add(hint);
                    }
                }
            }

            new FindInlayHints().scan(root, null);
            LOG.fine("[inlay-hints] computed " + result.size() + " hints for " + file.getFileName());
            return result;
        } catch (Exception e) {
            LOG.warning("Failed to compute inlay hints for " + file + ": " + e.getMessage());
            return List.of();
        }
    }

    private boolean isVarKeyword(Tree type) {
        return type != null && "var".equals(type.toString());
    }

    private boolean isHintableArgument(ExpressionTree argument) {
        switch (argument.getKind()) {
            case STRING_LITERAL:
            case INT_LITERAL:
            case LONG_LITERAL:
            case FLOAT_LITERAL:
            case DOUBLE_LITERAL:
            case BOOLEAN_LITERAL:
            case CHAR_LITERAL:
            case NULL_LITERAL:
                return true;
            default:
                return false;
        }
    }

    private boolean isRedundantVarInitializer(ExpressionTree initializer) {
        if (initializer == null) return false;
        switch (initializer.getKind()) {
            case NEW_CLASS:
            case NEW_ARRAY:
            case TYPE_CAST:
                return true;
            case MEMBER_SELECT:
                var select = (MemberSelectTree) initializer;
                return select.getIdentifier().contentEquals("class");
            default:
                return false;
        }
    }

    private boolean isVarDeclaration(VariableTree variable, String source, long declarationStart, long declarationEnd) {
        if (isVarKeyword(variable.getType())) return true;
        var name = variable.getName().toString();
        if (name.isBlank() || declarationStart < 0) return false;
        var start = (int) declarationStart;
        var end = declarationEnd > declarationStart ? (int) declarationEnd : start + 120;
        end = Math.min(end + 1, source.length());
        if (start >= end || start < 0 || start >= source.length()) return false;
        var snippet = source.substring(start, end);
        var nameIndex = snippet.indexOf(name);
        if (nameIndex < 0) return false;
        var beforeName = snippet.substring(0, nameIndex);
        return VAR_KEYWORD.matcher(beforeName).find();
    }

    private long findVarTokenEndOffset(VariableTree variable, String source, long declarationStart, long declarationEnd) {
        var name = variable.getName().toString();
        if (name.isBlank() || declarationStart < 0) return -1;
        var start = (int) declarationStart;
        var end = declarationEnd > declarationStart ? (int) declarationEnd : start + 120;
        end = Math.min(end + 1, source.length());
        if (start >= end || start < 0 || start >= source.length()) return -1;
        var snippet = source.substring(start, end);
        var nameIndex = snippet.indexOf(name);
        if (nameIndex < 0) return -1;
        var beforeName = snippet.substring(0, nameIndex);
        var matcher = VAR_KEYWORD.matcher(beforeName);
        if (!matcher.find()) return -1;
        return start + matcher.end();
    }

    private static long[] asOffsetRange(com.sun.source.tree.LineMap lines, Range range) {
        if (range == null || range == Range.NONE || range.start == null || range.end == null) {
            return new long[] {0, Long.MAX_VALUE};
        }
        try {
            var start = lines.getPosition(range.start.line + 1, range.start.character + 1);
            var end = lines.getPosition(range.end.line + 1, range.end.character + 1);
            if (start < 0 || end < 0) return new long[] {0, Long.MAX_VALUE};
            if (end < start) return new long[] {end, start};
            return new long[] {start, end};
        } catch (Exception ignored) {
            return new long[] {0, Long.MAX_VALUE};
        }
    }

    private static boolean intersects(long rangeStart, long rangeEnd, long start, long end) {
        if (start < 0 || end < 0) return false;
        return start <= rangeEnd && rangeStart <= end;
    }

    private static final Pattern VAR_KEYWORD = Pattern.compile("\\bvar\\b");
    private static final Logger LOG = Logger.getLogger("main");
}
