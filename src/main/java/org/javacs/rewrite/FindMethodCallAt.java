package org.javacs.rewrite;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.*;

class FindMethodCallAt extends TreeScanner<MethodInvocationTree, Integer> {
    private final SourcePositions pos;
    private CompilationUnitTree root;

    FindMethodCallAt(JavacTask task) {
        pos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public MethodInvocationTree visitCompilationUnit(CompilationUnitTree t, Integer find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public MethodInvocationTree visitMethodInvocation(MethodInvocationTree t, Integer find) {
        var smaller = super.visitMethodInvocation(t, find);
        if (smaller != null) {
            return smaller;
        }
        if (pos.getStartPosition(root, t) <= find && find < pos.getEndPosition(root, t)) {
            return t;
        }
        return null;
    }

    @Override
    public MethodInvocationTree reduce(MethodInvocationTree r1, MethodInvocationTree r2) {
        if (r1 != null) return r1;
        return r2;
    }
}
