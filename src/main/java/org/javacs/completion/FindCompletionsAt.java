package org.javacs.completion;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

public class FindCompletionsAt extends TreePathScanner<TreePath, Long> {
    private final JavacTask task;
    private CompilationUnitTree root;
    private TreePath enclosingScope;
    private TreePath enclosingAnnotation;

    public FindCompletionsAt(JavacTask task) {
        this.task = task;
    }

    @Override
    public TreePath visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        enclosingScope = null;
        enclosingAnnotation = null;
        var found = super.visitCompilationUnit(t, find);
        if (found != null) return found;
        if (enclosingAnnotation != null) return enclosingAnnotation;
        return enclosingScope == null ? getCurrentPath() : enclosingScope;
    }

    @Override
    public TreePath visitAnnotation(AnnotationTree tree, Long cursor) {
        var positions = Trees.instance(task).getSourcePositions();
        var start = positions.getStartPosition(root, tree);
        var end = positions.getEndPosition(root, tree);
        if (start <= cursor && cursor <= end) enclosingAnnotation = getCurrentPath();
        return super.visitAnnotation(tree, cursor);
    }

    @Override
    public TreePath visitClass(ClassTree tree, Long cursor) {
        rememberScope(tree, cursor);
        return super.visitClass(tree, cursor);
    }

    @Override
    public TreePath visitMethod(MethodTree tree, Long cursor) {
        rememberScope(tree, cursor);
        return super.visitMethod(tree, cursor);
    }

    @Override
    public TreePath visitBlock(BlockTree tree, Long cursor) {
        rememberScope(tree, cursor);
        return super.visitBlock(tree, cursor);
    }

    private void rememberScope(com.sun.source.tree.Tree tree, long cursor) {
        var positions = Trees.instance(task).getSourcePositions();
        var start = positions.getStartPosition(root, tree);
        var end = positions.getEndPosition(root, tree);
        if (start >= 0 && start <= cursor && (end < 0 || cursor <= end)) enclosingScope = getCurrentPath();
    }

    @Override
    public TreePath visitIdentifier(IdentifierTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getStartPosition(root, t);
        var end = pos.getEndPosition(root, t);
        if (start <= find && find <= end) {
            return getCurrentPath();
        }
        return super.visitIdentifier(t, find);
    }

    @Override
    public TreePath visitLiteral(LiteralTree tree, Long cursor) {
        var positions = Trees.instance(task).getSourcePositions();
        var start = positions.getStartPosition(root, tree);
        var end = positions.getEndPosition(root, tree);
        if (start <= cursor && cursor < end) return getCurrentPath();
        return super.visitLiteral(tree, cursor);
    }

    @Override
    public TreePath visitMemberSelect(MemberSelectTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var expressionEnd = pos.getEndPosition(root, t.getExpression());
        if (expressionEnd < 0) {
            return super.visitMemberSelect(t, find);
        }
        var start = expressionEnd + 1; // dot position
        var end = pos.getEndPosition(root, t);
        if (end < start) {
            // In erroneous trees (eg `foo.`), javac can report the member-select end before the dot.
            end = start;
        }
        // Allow one character beyond the reported end so completion works at cursor-after-dot.
        if (start <= find && find <= end + 1) {
            return getCurrentPath();
        }
        return super.visitMemberSelect(t, find);
    }

    @Override
    public TreePath visitMemberReference(MemberReferenceTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getEndPosition(root, t.getQualifierExpression()) + 2;
        var end = pos.getEndPosition(root, t);
        if (start <= find && find <= end) {
            return getCurrentPath();
        }
        return super.visitMemberReference(t, find);
    }

    @Override
    public TreePath visitCase(CaseTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getStartPosition(root, t) + "case".length();
        var end = pos.getEndPosition(root, t.getExpression());
        if (start <= find && find <= end) {
            return getCurrentPath().getParentPath();
        }
        return super.visitCase(t, find);
    }

    @Override
    public TreePath visitImport(ImportTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getStartPosition(root, t.getQualifiedIdentifier());
        var end = pos.getEndPosition(root, t.getQualifiedIdentifier());
        if (start <= find && find <= end) {
            return getCurrentPath();
        }
        return super.visitImport(t, find);
    }

    @Override
    public TreePath visitErroneous(ErroneousTree t, Long find) {
        if (t.getErrorTrees() == null) return null;
        for (var e : t.getErrorTrees()) {
            var found = scan(e, find);
            if (found != null) return found;
        }
        return null;
    }

    @Override
    public TreePath reduce(TreePath a, TreePath b) {
        if (a != null) return a;
        return b;
    }
}
