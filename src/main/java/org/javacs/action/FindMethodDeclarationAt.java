package org.javacs.action;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.*;

class FindMethodDeclarationAt extends TreeScanner<MethodTree, Long> {
    private final SourcePositions pos;
    private CompilationUnitTree root;

    FindMethodDeclarationAt(JavacTask task) {
        pos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public MethodTree visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public MethodTree visitMethod(MethodTree t, Long find) {
        var smaller = super.visitMethod(t, find);
        if (smaller != null) {
            return smaller;
        }
        if (pos.getStartPosition(root, t) <= find && find < pos.getEndPosition(root, t)) {
            return t;
        }
        return null;
    }

    @Override
    public MethodTree reduce(MethodTree r1, MethodTree r2) {
        if (r1 != null) return r1;
        return r2;
    }
}
