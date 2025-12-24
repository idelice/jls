package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.function.Consumer;

class FindFieldReferences extends TreePathScanner<Void, Consumer<TreePath>> {

    @Override
    public Void visitVariable(VariableTree t, Consumer<TreePath> forEach) {
        var path = getCurrentPath();
        if (path.getParentPath().getLeaf() instanceof ClassTree) {
            forEach.accept(path);
        }
        return super.visitVariable(t, forEach);
    }

    @Override
    public Void visitIdentifier(IdentifierTree t, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitIdentifier(t, forEach);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree t, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitMemberSelect(t, forEach);
    }
}
