package org.javacs.markup;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

class WarnUnused extends TreePathScanner<Void, Void> {
    private final Trees trees;
    private final Set<Element> declarations = new HashSet<>();
    private final Set<Element> used = new HashSet<>();
    private final Set<Element> privateTypes = new HashSet<>();
    private final Set<Element> usedTypes = new HashSet<>();

    WarnUnused(Trees trees) {
        this.trees = trees;
    }

    Set<Element> notUsed() {
        var result = new HashSet<>(declarations);
        result.removeAll(used);
        return result;
    }

    Set<Element> unusedPrivateTypes() {
        var result = new HashSet<>(privateTypes);
        result.removeAll(usedTypes);
        return result;
    }

    @Override
    public Void visitClass(ClassTree classTree, Void unused) {
        var element = trees.getElement(getCurrentPath());
        if (element instanceof TypeElement typeElement
                && typeElement.getModifiers().contains(Modifier.PRIVATE)
                && typeElement.getEnclosingElement() != null
                && typeElement.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
            privateTypes.add(typeElement);
        }
        return super.visitClass(classTree, unused);
    }

    @Override
    public Void visitVariable(VariableTree variable, Void unused) {
        var element = trees.getElement(getCurrentPath());
        if (element != null
                && !isGeneratedMember(element)
                && (element.getKind() == ElementKind.LOCAL_VARIABLE
                        || (element.getKind() == ElementKind.FIELD
                                && element.getModifiers().contains(Modifier.PRIVATE)
                                && element.getEnclosingElement().getKind() != ElementKind.RECORD))) {
            declarations.add(element);
        }
        return super.visitVariable(variable, unused);
    }

    private boolean isGeneratedMember(Element element) {
        return element instanceof Symbol symbol
                && (symbol.flags() & Flags.GENERATED_MEMBER) != 0;
    }

    @Override
    public Void visitIdentifier(IdentifierTree identifier, Void unused) {
        var element = trees.getElement(getCurrentPath());
        if (element != null) {
            used.add(element);
            if (element instanceof TypeElement) usedTypes.add(element);
        }
        return super.visitIdentifier(identifier, unused);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree select, Void unused) {
        var element = trees.getElement(getCurrentPath());
        if (element != null) {
            used.add(element);
            if (element instanceof TypeElement) usedTypes.add(element);
        }
        return super.visitMemberSelect(select, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree newClass, Void unused) {
        var element = trees.getElement(getCurrentPath());
        if (element != null && element.getEnclosingElement() instanceof TypeElement typeElement) {
            usedTypes.add(typeElement);
        }
        return super.visitNewClass(newClass, unused);
    }
}
