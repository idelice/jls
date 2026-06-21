package org.javacs.navigation;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.List;
import java.util.Set;
import javax.tools.Diagnostic;

public class FindLombokReferences extends TreePathScanner<Void, List<TreePath>> {
    private final JavacTask task;
    private final Set<String> names;

    public FindLombokReferences(JavacTask task, Set<String> names) {
        this.task = task;
        this.names = names;
    }

    @Override
    public Void visitIdentifier(IdentifierTree t, List<TreePath> list) {
        if (check(t.getName().toString())) list.add(getCurrentPath());
        return super.visitIdentifier(t, list);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree t, List<TreePath> list) {
        if (check(t.getIdentifier().toString())) list.add(getCurrentPath());
        return super.visitMemberSelect(t, list);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree t, List<TreePath> list) {
        if (check(t.getName().toString())) list.add(getCurrentPath());
        return super.visitMemberReference(t, list);
    }

    private boolean check(String name) {
        if (!names.contains(name)) return false;
        var path = getCurrentPath();
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        return pos.getStartPosition(path.getCompilationUnit(), path.getLeaf()) != Diagnostic.NOPOS
                && pos.getEndPosition(path.getCompilationUnit(), path.getLeaf()) != Diagnostic.NOPOS;
    }
}
