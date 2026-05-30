package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;

public class FindNameAt extends TreePathScanner<TreePath, Long> {
    private final JavacTask task;
    private CompilationUnitTree root;
    private ClassTree surroundingClass;

    public FindNameAt(CompileTask task) {
        this.task = task.task;
    }

    public FindNameAt(ParseTask task) {
        this.task = task.task();
    }

    @Override
    public TreePath visitCompilationUnit(CompilationUnitTree t, Long find) {
        root = t;
        return super.visitCompilationUnit(t, find);
    }

    @Override
    public TreePath visitClass(ClassTree t, Long find) {
        var push = surroundingClass;
        surroundingClass = t;
        if (contains(t, t.getSimpleName(), find)) {
            surroundingClass = push;
            return getCurrentPath();
        }
        var result = super.visitClass(t, find);
        surroundingClass = push;
        return result;
    }

    @Override
    public TreePath visitMethod(MethodTree t, Long find) {
        var name = t.getName();
        if (name.contentEquals("<init>")) {
            name = surroundingClass.getSimpleName();
        }
        // Only match the cursor on the method name token itself, not on same-named
        // identifiers inside the body (e.g. a recursive call or a local variable).
        var pos = Trees.instance(task).getSourcePositions();
        var start = (int) pos.getStartPosition(root, t);
        var end = t.getBody() != null
                ? (int) pos.getStartPosition(root, t.getBody())
                : (int) pos.getEndPosition(root, t);
        if (start >= 0 && end > start) {
            var nameStart = FindHelper.findNameIn(root, name, start, end, find);
            if (nameStart >= 0 && nameStart <= find && find <= nameStart + name.length()) {
                return getCurrentPath();
            }
        }
        return super.visitMethod(t, find);
    }

    @Override
    public TreePath visitIdentifier(IdentifierTree t, Long find) {
        if (contains(t, t.getName(), find)) {
            return getCurrentPath();
        }
        return super.visitIdentifier(t, find);
    }

    @Override
    public TreePath visitMemberSelect(MemberSelectTree t, Long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var end = (int) pos.getEndPosition(root, t);
        // Only match cursor on the right-hand identifier (method name),
        // never on the expression (variable name) which may share the same name.
        var exprEnd = (int) pos.getEndPosition(root, t.getExpression());
        if (exprEnd >= 0 && exprEnd < end) {
            var nameStart = FindHelper.findNameIn(root, t.getIdentifier(), exprEnd, end, find);
            var nameEnd = nameStart + t.getIdentifier().length();
            if (nameStart >= 0 && nameStart <= find && find <= nameEnd) {
                return getCurrentPath();
            }
        }
        return super.visitMemberSelect(t, find);
    }

    @Override
    public TreePath visitMemberReference(MemberReferenceTree t, Long find) {
        if (contains(t, t.getName(), find)) {
            return getCurrentPath();
        }
        return super.visitMemberReference(t, find);
    }

    @Override
    public TreePath visitAnnotation(AnnotationTree t, Long find) {
        var name = annotationName(t);
        if (name != null && contains(t, name, find)) {
            return getCurrentPath();
        }
        return super.visitAnnotation(t, find);
    }

    @Override
    public TreePath visitVariable(VariableTree t, Long find) {
        // Only match the cursor on the variable name itself, not on same-named identifiers
        // in the initializer (e.g. `var canonicalKey = IndexedMember.canonicalKey(...)`).
        var pos = Trees.instance(task).getSourcePositions();
        var start = (int) pos.getStartPosition(root, t);
        var end = t.getInitializer() != null
                ? (int) pos.getStartPosition(root, t.getInitializer())
                : (int) pos.getEndPosition(root, t);
        if (start >= 0 && end > start) {
            var nameStart = FindHelper.findNameIn(root, t.getName(), start, end, find);
            if (nameStart >= 0 && nameStart <= find && find <= nameStart + t.getName().length()) {
                return getCurrentPath();
            }
        }
        return super.visitVariable(t, find);
    }

    @Override
    public TreePath visitNewClass(NewClassTree t, Long find) {
        var start = Trees.instance(task).getSourcePositions().getStartPosition(root, t);
        var end = start + "new".length();
        if (start <= find && find < end) {
            return getCurrentPath();
        }
        return super.visitNewClass(t, find);
    }

    @Override
    public TreePath reduce(TreePath r1, TreePath r2) {
        if (r1 != null) return r1;
        return r2;
    }

    private boolean contains(Tree t, CharSequence name, long find) {
        var pos = Trees.instance(task).getSourcePositions();
        var start = (int) pos.getStartPosition(root, t);
        var end = (int) pos.getEndPosition(root, t);
        if (start == -1 || end == -1) return false;
        start = FindHelper.findNameIn(root, name, start, end, find);
        end = start + name.length();
        if (start == -1 || end == -1) return false;
        return start <= find && find <= end;
    }

    private CharSequence annotationName(AnnotationTree annotation) {
        var type = annotation.getAnnotationType();
        if (type instanceof IdentifierTree identifier) {
            return identifier.getName();
        }
        if (type instanceof MemberSelectTree memberSelect) {
            return memberSelect.getIdentifier();
        }
        var text = type == null ? "" : type.toString();
        var lastDot = text.lastIndexOf('.');
        return lastDot >= 0 ? text.substring(lastDot + 1) : text;
    }
}
