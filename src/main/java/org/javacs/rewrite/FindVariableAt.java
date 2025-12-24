package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;

class FindVariableAt extends TreeScanner<VariableTree, Integer> {
    private final SourcePositions pos;
    private CompilationUnitTree root;

    FindVariableAt(JavacTask task) {
        pos = Trees.instance(task).getSourcePositions();
    }

    @Override
    public VariableTree visitCompilationUnit(CompilationUnitTree t, Integer find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public VariableTree visitVariable(VariableTree t, Integer find) {
        var smaller = super.visitVariable(t, find);
        if (smaller != null) {
            return smaller;
        }
        if (pos.getStartPosition(root, t) <= find && find < pos.getEndPosition(root, t)) {
            return t;
        }
        return null;
    }

    @Override
    public VariableTree reduce(VariableTree r1, VariableTree r2) {
        if (r1 != null) return r1;
        return r2;
    }
}
