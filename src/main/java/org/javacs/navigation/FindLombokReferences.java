package org.javacs.navigation;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import org.javacs.CompileTask;

/**
 * Finds references to Lombok-generated accessors (and the underlying field) for a specific class.
 * Matches by name AND verifies the reference belongs to the target class, either by resolving
 * the element or by checking the receiver's type.
 */
public class FindLombokReferences extends TreePathScanner<Void, List<TreePath>> {
    private final Trees trees;
    private final Types types;
    private final Set<String> names;
    private final String targetClassName;
    private final TypeMirror targetType;

    public FindLombokReferences(CompileTask task, Set<String> names, String targetClassName) {
        this.trees = task.trees;
        this.types = task.types;
        this.names = names;
        this.targetClassName = targetClassName;
        var target = task.elements.getTypeElement(targetClassName);
        this.targetType = target == null ? null : task.types.erasure(target.asType());
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
            if (!(enclosing instanceof TypeElement te)) return false;
            if (te.getQualifiedName().contentEquals(targetClassName)) return true;

            if (!(element instanceof Symbol symbol)
                    || (symbol.flags() & Flags.GENERATED_MEMBER) == 0) return false;

            // A Lombok @Builder accessor is declared on the generated nested
            // FooBuilder type, not on Foo. Treat that one generated owner as
            // belonging to Foo so field references include fluent builder calls.
            var builderOwner = te.getEnclosingElement();
            return builderOwner instanceof TypeElement owner
                    && owner.getQualifiedName().contentEquals(targetClassName)
                    && te.getSimpleName().contentEquals(owner.getSimpleName() + "Builder");
        }

        // Element didn't resolve — check the receiver or enclosing class type.
        var leaf = path.getLeaf();
        ExpressionTree receiver = switch (leaf) {
            case MemberSelectTree ms -> ms.getExpression();
            case MemberReferenceTree mr -> mr.getQualifierExpression();
            default -> null;
        };
        TreePath receiverPath;
        if (receiver != null) {
            receiverPath = new TreePath(path, receiver);
        } else if (leaf instanceof IdentifierTree && path.getParentPath() != null
                && path.getParentPath().getLeaf() instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() == leaf) {
            receiverPath = path.getParentPath();
            while (receiverPath != null && !(receiverPath.getLeaf() instanceof ClassTree)) {
                receiverPath = receiverPath.getParentPath();
            }
            if (receiverPath == null) return false;
        } else {
            return false;
        }

        var receiverType = trees.getTypeMirror(receiverPath);
        return targetType != null
                && receiverType != null
                && receiverType.getKind() != TypeKind.ERROR
                && types.isSubtype(types.erasure(receiverType), targetType);
    }
}
