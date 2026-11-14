package org.javacs.rewrite;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.function.Consumer;

class FindFieldReferences extends ReferenceScanner {
    @Override
    public Void visitVariable(VariableTree tree, Consumer<TreePath> forEach) {
        var path = getCurrentPath();
        if (path.getParentPath().getLeaf() instanceof ClassTree) {
            forEach.accept(path);
        }
        return super.visitVariable(tree, forEach);
    }
}
