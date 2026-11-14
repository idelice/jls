package org.javacs.rewrite;

import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import java.util.function.Consumer;

class FindMethodReferences extends ReferenceScanner {
    @Override
    public Void visitMethod(MethodTree tree, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitMethod(tree, forEach);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree tree, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitMemberReference(tree, forEach);
    }
}
