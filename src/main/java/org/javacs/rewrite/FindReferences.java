package org.javacs.rewrite;

import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.function.Consumer;

public class FindReferences extends ReferenceScanner {
    @Override
    public Void visitVariable(VariableTree node, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitVariable(node, forEach);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree node, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitMemberReference(node, forEach);
    }
}
