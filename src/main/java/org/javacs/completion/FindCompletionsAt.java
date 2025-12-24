package org.javacs.completion;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

class FindCompletionsAt extends TreePathScanner<TreePath, Long> {
    private final JavacTask task;
    private CompilationUnitTree root;

    FindCompletionsAt(JavacTask task) {
        this.task = task;
    }

    @Override
    public TreePath visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        return reduce(super.visitCompilationUnit(t, find), getCurrentPath());
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
    public TreePath visitMemberSelect(MemberSelectTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getEndPosition(root, t.getExpression()) + 1;
        var end = pos.getEndPosition(root, t);
        if (start <= find && find <= end) {
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
