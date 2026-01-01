package org.javacs.inlay;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import com.sun.source.tree.CompilationUnitTree;
import org.javacs.CompileTask;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import java.util.ArrayList;
import java.util.List;

public class InlayHintProvider extends TreePathScanner<Void, Void> {
    private final CompileTask task;
    private final CompilationUnitTree root;
    private final Trees trees;
    private final Range range;
    private final List<InlayHint> hints = new ArrayList<>();
    private final String contents;

    public InlayHintProvider(CompileTask task, CompilationUnitTree root, Range range) {
        this.task = task;
        this.root = root;
        this.trees = Trees.instance(task.task);
        this.range = range;
        String text = "";
        try {
            text = root.getSourceFile().getCharContent(false).toString();
        } catch (Exception e) {
            // Ignore content read failures; line-based filters will be skipped.
        }
        this.contents = text;
    }

    public List<InlayHint> hints() {
        scan(root, null);
        return hints;
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        handleInvocation(getCurrentPath(), node.getArguments());
        return super.visitMethodInvocation(node, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree node, Void unused) {
        handleInvocation(getCurrentPath(), node.getArguments());
        return super.visitNewClass(node, unused);
    }

    private void handleInvocation(TreePath path, List<? extends ExpressionTree> args) {
        if (args == null || args.isEmpty()) return;
        if (isInAnnotation(path)) return;
        Element el = trees.getElement(path);
        if (!(el instanceof ExecutableElement)) return;
        var method = (ExecutableElement) el;
        var params = method.getParameters();
        if (params == null || params.isEmpty()) return;
        boolean isVarargs = method.isVarArgs();
        int lastIndex = params.size() - 1;

        for (int i = 0; i < args.size(); i++) {
            VariableElement param;
            if (i < params.size()) {
                param = params.get(i);
            } else if (isVarargs) {
                param = params.get(lastIndex);
            } else {
                break;
            }

            var name = param.getSimpleName().toString();
            if (name == null || name.isBlank()) continue;
            var arg = args.get(i);
            if (shouldSkip(arg, name)) continue;

            var pos = positionFor(arg);
            if (pos == null) continue;
            if (!inRange(pos)) continue;
            if (isAnnotationLine(pos)) continue;

            var hint = new InlayHint();
            hint.position = pos;
            hint.label = name + ":";
            hint.kind = 2; // Parameter
            hint.paddingRight = true;
            hints.add(hint);
        }
    }

    private boolean isInAnnotation(TreePath path) {
        for (var p = path; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof AnnotationTree) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSkip(ExpressionTree arg, String paramName) {
        if (arg instanceof AssignmentTree) {
            var left = ((AssignmentTree) arg).getVariable();
            if (left != null && paramName.equals(left.toString())) {
                return true;
            }
        }
        return false;
    }

    private Position positionFor(ExpressionTree arg) {
        var pos = trees.getSourcePositions().getStartPosition(root, arg);
        if (pos < 0) return null;
        var lines = root.getLineMap();
        int line = (int) lines.getLineNumber(pos);
        long lineStart = lines.getStartPosition(line);
        int col = (int) (pos - lineStart);
        return new Position(line - 1, col);
    }

    private boolean inRange(Position pos) {
        if (range == null || range == Range.NONE || range.start == null || range.end == null) return true;
        if (pos.line < range.start.line || pos.line > range.end.line) return false;
        if (pos.line == range.start.line && pos.character < range.start.character) return false;
        if (pos.line == range.end.line && pos.character > range.end.character) return false;
        return true;
    }

    private boolean isAnnotationLine(Position pos) {
        if (contents.isEmpty()) return false;
        var lines = root.getLineMap();
        int lineIndex = pos.line + 1; // LineMap is 1-based
        long start = lines.getStartPosition(lineIndex);
        if (start < 0) return false;
        long end = lines.getStartPosition(lineIndex + 1);
        if (end < 0) {
            end = contents.length();
        }
        if (start >= end || end > contents.length()) return false;
        var line = contents.substring((int) start, (int) end).trim();
        return line.startsWith("@");
    }
}
