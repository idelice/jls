package org.javacs.hover;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.Element;

class FindHoverElement extends TreePathScanner<Element, Long> {

    private final JavacTask task;
    private CompilationUnitTree root;

    FindHoverElement(JavacTask task) {
        this.task = task;
    }

    @Override
    public Element visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public Element visitIdentifier(IdentifierTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getStartPosition(root, t);
        var end = pos.getEndPosition(root, t);
        if (start <= find && find < end) {
            return Trees.instance(task).getElement(getCurrentPath());
        }
        return super.visitIdentifier(t, find);
    }

    @Override
    public Element visitMemberSelect(MemberSelectTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getEndPosition(root, t.getExpression()) + 1;
        var end = pos.getEndPosition(root, t);
        if (start <= find && find < end) {
            return Trees.instance(task).getElement(getCurrentPath());
        }
        return super.visitMemberSelect(t, find);
    }

    @Override
    public Element visitMemberReference(MemberReferenceTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getStartPosition(root, t.getQualifierExpression()) + 2;
        var end = pos.getEndPosition(root, t);
        if (start <= find && find < end) {
            return Trees.instance(task).getElement(getCurrentPath());
        }
        return super.visitMemberReference(t, find);
    }

    @Override
    public Element visitAnnotation(AnnotationTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = pos.getStartPosition(root, t);
        var end = pos.getEndPosition(root, t);
        if (start <= find && find < end) {
            var annotationType = resolveAnnotationType(t);
            if (annotationType != null) {
                return annotationType;
            }
        }
        return super.visitAnnotation(t, find);
    }

    private Element resolveAnnotationType(AnnotationTree annotation) {
        var trees = Trees.instance(task);
        var direct = trees.getElement(new TreePath(getCurrentPath(), annotation.getAnnotationType()));
        if (direct != null && direct.getKind() == javax.lang.model.element.ElementKind.ANNOTATION_TYPE) {
            return direct;
        }
        var simpleOrQualified = annotation.getAnnotationType().toString();
        var elements = task.getElements();
        if (simpleOrQualified.contains(".")) {
            var explicit = elements.getTypeElement(simpleOrQualified);
            if (explicit != null) return explicit;
        } else {
            if (root != null) {
                for (var imp : root.getImports()) {
                    var imported = imp.getQualifiedIdentifier().toString();
                    if (imported.endsWith("." + simpleOrQualified)) {
                        var resolved = elements.getTypeElement(imported);
                        if (resolved != null) return resolved;
                    }
                }
                var packageName = root.getPackageName();
                if (packageName != null) {
                    var local = elements.getTypeElement(packageName + "." + simpleOrQualified);
                    if (local != null) return local;
                }
            }
            var javaLang = elements.getTypeElement("java.lang." + simpleOrQualified);
            if (javaLang != null) return javaLang;
        }
        return trees.getElement(getCurrentPath());
    }

    @Override
    public Element reduce(Element a, Element b) {
        if (a != null) return a;
        else return b;
    }
}
