package org.javacs.navigation;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import org.javacs.CompileTask;

/**
 * Finds references to Lombok-generated accessors (and the underlying field) for a specific class.
 * Matches by name AND verifies the reference belongs to the target class, either by resolving
 * the element or by checking the receiver's type.
 */
public class FindLombokReferences extends TreePathScanner<Void, List<TreePath>> {
    private final Trees trees;
    private final Set<String> names;
    private final String targetClassName;

    public FindLombokReferences(CompileTask task, Set<String> names, String targetClassName) {
        this.trees = task.trees;
        this.names = names;
        this.targetClassName = targetClassName;
    }

    @Override
    public Void visitIdentifier(IdentifierTree t, List<TreePath> list) {
        if (names.contains(t.getName().toString()) && belongsToTarget()) list.add(getCurrentPath());
        return super.visitIdentifier(t, list);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree t, List<TreePath> list) {
        if (names.contains(t.getIdentifier().toString()) && belongsToTarget()) list.add(getCurrentPath());
        return super.visitMemberSelect(t, list);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree t, List<TreePath> list) {
        if (names.contains(t.getName().toString()) && belongsToTarget()) list.add(getCurrentPath());
        return super.visitMemberReference(t, list);
    }

    /**
     * Check whether the current tree node belongs to the target class.
     * First tries direct element resolution (works when .class is on classpath).
     * Falls back to checking receiver type for member selects/references where the
     * member itself doesn't resolve (e.g. Lombok accessor not in .class yet).
     */
    private boolean belongsToTarget() {
        var path = getCurrentPath();
        var pos = trees.getSourcePositions();
        if (pos.getStartPosition(path.getCompilationUnit(), path.getLeaf()) == Diagnostic.NOPOS) return false;

        // Try resolving the element directly — covers most cases
        var element = trees.getElement(path);
        if (element != null && element.asType().getKind() != TypeKind.ERROR) {
            var enclosing = element.getEnclosingElement();
            return enclosing instanceof TypeElement te
                    && te.getQualifiedName().contentEquals(targetClassName);
        }

        // Element didn't resolve — check receiver type for member access expressions
        var leaf = path.getLeaf();
        ExpressionTree receiver = switch (leaf) {
            case MemberSelectTree ms -> ms.getExpression();
            case MemberReferenceTree mr -> mr.getQualifierExpression();
            default -> null;
        };
        if (receiver == null) return false;

        var receiverType = trees.getTypeMirror(new TreePath(path, receiver));
        return receiverType != null
                && receiverType.getKind() == TypeKind.DECLARED
                && ((TypeElement) ((DeclaredType) receiverType).asElement())
                        .getQualifiedName().contentEquals(targetClassName);
    }
}
