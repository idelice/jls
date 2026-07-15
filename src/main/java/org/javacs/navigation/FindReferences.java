package org.javacs.navigation;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import org.javacs.CompileTask;

public class FindReferences extends TreePathScanner<Void, List<TreePath>> {
    final Trees trees;
    final Element find;
    final Elements elements;
    final Types types;

    public FindReferences(Trees trees, Element find) {
        this.trees = trees;
        this.find = find;
        this.elements = null;
        this.types = null;
    }

    public FindReferences(CompileTask task, Element find) {
        this.trees = task.trees;
        this.find = find;
        this.elements = task.elements;
        this.types = task.types;
    }

    @Override
    public Void visitIdentifier(IdentifierTree t, List<TreePath> list) {
        if (check()) {
            list.add(getCurrentPath());
        }
        return super.visitIdentifier(t, list);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree t, List<TreePath> list) {
        if (check()) {
            list.add(getCurrentPath());
        }
        return super.visitMemberSelect(t, list);
    }

    @Override
    public Void visitNewClass(NewClassTree t, List<TreePath> list) {
        if (check()) {
            list.add(getCurrentPath());
        }
        return super.visitNewClass(t, list);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree t, List<TreePath> list) {
        if (check()) {
            list.add(getCurrentPath());
        }
        return super.visitMemberReference(t, list);
    }

    @Override
    public Void visitMethod(MethodTree t, List<TreePath> list) {
        if (check()) {
            list.add(getCurrentPath());
        }
        return super.visitMethod(t, list);
    }

    boolean check() {
        var path = getCurrentPath();
        var candidate = trees.getElement(path);
        if (candidate != null && candidate.asType().getKind() != TypeKind.ERROR) {
            if (!find.equals(candidate)
                    && !isRecordComponentEquivalent(find, candidate)
                    && !isOverrideEquivalent(find, candidate)) {
                return false;
            }
        } else {
            // Attribution failed (lombok/chain breakage) — fall back to scoped name matching
            if (!isUnresolvedMemberMatch(path)) {
                return false;
            }
        }
        var pos = trees.getSourcePositions();
        // Skip elements without positions. This can happen, e.g. for var types.
        if (pos.getStartPosition(path.getCompilationUnit(), path.getLeaf()) == Diagnostic.NOPOS ||
            pos.getEndPosition(path.getCompilationUnit(), path.getLeaf()) == Diagnostic.NOPOS) {
            return false;
        }
        return true;
    }

    /**
     * Fallback for unresolved elements: match by method name + argument count + declaring type
     * imported in the file. Only applies to method invocations via member select.
     */
    private boolean isUnresolvedMemberMatch(TreePath path) {
        if (!(find instanceof ExecutableElement targetMethod)) return false;
        var leaf = path.getLeaf();
        // Must be a member select with matching name
        if (!(leaf instanceof MemberSelectTree memberSelect)) return false;
        if (!memberSelect.getIdentifier().contentEquals(find.getSimpleName())) return false;
        // Must be inside a method invocation with matching argument count
        var parent = path.getParentPath();
        if (parent == null || !(parent.getLeaf() instanceof MethodInvocationTree invocation)) return false;
        if (invocation.getArguments().size() != targetMethod.getParameters().size()) return false;
        // The file must import or reference the declaring type
        var ownerType = find.getEnclosingElement();
        if (!(ownerType instanceof TypeElement declaringType)) return false;
        var qualifiedName = declaringType.getQualifiedName().toString();
        var simpleName = declaringType.getSimpleName().toString();
        var root = path.getCompilationUnit();
        return fileReferencesType(root, qualifiedName, simpleName);
    }

    /** Check if a compilation unit imports or directly references the given type. */
    private boolean fileReferencesType(CompilationUnitTree root, String qualifiedName, String simpleName) {
        var lastDot = qualifiedName.lastIndexOf('.');
        var targetPackage = lastDot > 0 ? qualifiedName.substring(0, lastDot) : "";
        // Check explicit imports
        for (var imp : root.getImports()) {
            var imported = imp.getQualifiedIdentifier().toString();
            if (imported.equals(qualifiedName)) return true;
            // Star import of the package
            if (imported.endsWith(".*")) {
                var pkg = imported.substring(0, imported.length() - 2);
                if (pkg.equals(targetPackage)) return true;
            }
            // Any import from the same package (file has visibility into that package)
            var importLastDot = imported.lastIndexOf('.');
            if (importLastDot > 0 && imported.substring(0, importLastDot).equals(targetPackage)) return true;
        }
        // Same package as declaring type
        var filePackage = root.getPackageName();
        if (filePackage != null && filePackage.toString().equals(targetPackage)) return true;
        return false;
    }

    /**
     * Check if two methods are in an override relationship (one overrides the other,
     * or both override a common ancestor method).
     */
    private boolean isOverrideEquivalent(Element a, Element b) {
        if (elements == null || types == null) return false;
        if (a == null || b == null) return false;
        if (!(a instanceof ExecutableElement methodA) || !(b instanceof ExecutableElement methodB)) return false;
        if (!a.getSimpleName().contentEquals(b.getSimpleName())) return false;
        var ownerA = a.getEnclosingElement();
        var ownerB = b.getEnclosingElement();
        if (!(ownerA instanceof TypeElement typeA) || !(ownerB instanceof TypeElement typeB)) return false;
        // Check if B overrides A (e.g. implementation overrides interface method)
        if (elements.overrides(methodB, methodA, typeB)) return true;
        // Check if A overrides B (e.g. searching from impl, finding interface declaration)
        if (elements.overrides(methodA, methodB, typeA)) return true;
        return false;
    }

    /**
     * Treat a compact constructor PARAMETER, record accessor METHOD, or record FIELD
     * as equivalent when they share the same name and belong to the same record.
     */
    private static boolean isRecordComponentEquivalent(Element a, Element b) {
        if (a == null || b == null) return false;
        if (!a.getSimpleName().contentEquals(b.getSimpleName())) return false;
        var recordA = enclosingRecord(a);
        var recordB = enclosingRecord(b);
        if (recordA == null || recordB == null) return false;
        return recordA.equals(recordB);
    }

    /** Returns the enclosing record if the element is a record-related member, else null. */
    private static TypeElement enclosingRecord(Element e) {
        switch (e.getKind()) {
            case RECORD_COMPONENT, FIELD -> {
                if (e.getEnclosingElement() instanceof TypeElement te
                        && te.getKind() == ElementKind.RECORD) return te;
            }
            case PARAMETER -> {
                var ctor = e.getEnclosingElement();
                if (ctor != null && ctor.getKind() == ElementKind.CONSTRUCTOR
                        && ctor.getEnclosingElement() instanceof TypeElement te
                        && te.getKind() == ElementKind.RECORD) return te;
            }
            case METHOD -> {
                if (e instanceof ExecutableElement method
                        && method.getParameters().isEmpty()
                        && e.getEnclosingElement() instanceof TypeElement te
                        && te.getKind() == ElementKind.RECORD) return te;
            }
            default -> {}
        }
        return null;
    }
}
