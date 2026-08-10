package org.javacs.markup;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

class WarnUnused extends TreePathScanner<Void, Void> {
    private final Trees trees;
    private final Set<Element> declarations = new HashSet<>();
    private final Set<Element> used = new HashSet<>();

    WarnUnused(Trees trees) {
        this.trees = trees;
    }

    Set<Element> notUsed() {
        var result = new HashSet<>(declarations);
        result.removeAll(used);
        return result;
    }

    @Override
    public Void visitVariable(VariableTree variable, Void unused) {
        var element = trees.getElement(getCurrentPath());
        if (element != null
                && (element.getKind() == ElementKind.LOCAL_VARIABLE
                        || (element.getKind() == ElementKind.FIELD
                                && element.getModifiers().contains(Modifier.PRIVATE)))) {
            declarations.add(element);
        }
        return super.visitVariable(variable, unused);
    }

    @Override
    public Void visitIdentifier(IdentifierTree identifier, Void unused) {
        var element = trees.getElement(getCurrentPath());
        if (element != null) {
            used.add(element);
        }
        return super.visitIdentifier(identifier, unused);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree select, Void unused) {
        var element = trees.getElement(getCurrentPath());
        if (element != null) {
            used.add(element);
        }
        return super.visitMemberSelect(select, unused);
    }
}
