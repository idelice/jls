package org.javacs.rewrite;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.function.Consumer;

/** Shared scanner for rename/reference collection: reports identifiers and member selects. */
abstract class ReferenceScanner extends TreePathScanner<Void, Consumer<TreePath>> {
    @Override
    public Void visitIdentifier(IdentifierTree tree, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitIdentifier(tree, forEach);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree tree, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitMemberSelect(tree, forEach);
    }
}
