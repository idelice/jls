package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;

class FindVariableAt extends TreeScanner<VariableTree, Integer> {
    private final SourcePositions pos;
    private final Trees trees;
    private CompilationUnitTree root;

    FindVariableAt(JavacTask task) {
        this.trees = Trees.instance(task);
        this.pos = trees.getSourcePositions();
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
    public VariableTree visitIdentifier(IdentifierTree t, Integer find) {
        // Check if this identifier refers to a variable and position matches
        if (pos.getStartPosition(root, t) <= find && find < pos.getEndPosition(root, t)) {
            var path = trees.getPath(root, t);
            if (path != null) {
                var element = trees.getElement(path);
                if (element != null && element.getKind() == javax.lang.model.element.ElementKind.LOCAL_VARIABLE) {
                    // Find the declaration tree for this variable
                    var declarationPath = trees.getPath(element);
                    if (declarationPath != null && declarationPath.getLeaf() instanceof VariableTree varTree) {
                        return varTree;
                    }
                }
            }
        }
        return super.visitIdentifier(t, find);
    }

    @Override
    public VariableTree reduce(VariableTree r1, VariableTree r2) {
        if (r1 != null) return r1;
        return r2;
    }
}
