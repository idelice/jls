package org.javacs.navigation;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompileTask;
import org.javacs.FindNameAt;
import org.javacs.LspPosition;
import org.javacs.lsp.Position;

public class NavigationHelper {
    public static TreePath findPath(CompileTask task, Path file, int line, int column) {
        for (var root : task.roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) {
                var cursor = LspPosition.offset(root, new Position(line - 1, column - 1));
                return new FindNameAt(task).scan(root, cursor);
            }
        }
        throw new RuntimeException("file not found");
    }

    public static Element findElement(CompileTask task, Path file, int line, int column) {
        return findElement(task, findPath(task, file, line, column));
    }

    public static Element findElement(CompileTask task, TreePath path) {
        if (path == null) return null;
        var element = task.trees.getElement(path);
        if (element != null && element.asType().getKind() == TypeKind.ERROR
                && path.getLeaf() instanceof MemberSelectTree select) {
            var resolved = resolveChain(task, path, select);
            if (resolved != null) element = resolved;
        }
        return element;
    }

    /**
     * Resolves a method in a chained call by walking the chain forward from the root.
     * Collects the chain of method names from root to target, then resolves each
     * method's return type step by step to find the correct declaring type for the target.
     */
    private static Element resolveChain(CompileTask task, TreePath path, MemberSelectTree target) {
        var targetName = target.getIdentifier().toString();

        // Collect the chain: walk inward to find the root, recording method names along the way
        var chain = new ArrayList<String>();
        var expr = target.getExpression();
        while (expr instanceof MethodInvocationTree inv) {
            var ms = inv.getMethodSelect();
            if (ms instanceof MemberSelectTree mst) {
                chain.add(mst.getIdentifier().toString());
                expr = mst.getExpression();
            } else {
                break;
            }
        }
        // Resolve the root expression's type
        var rootPath = TreePath.getPath(path.getCompilationUnit(), expr);
        if (rootPath == null) return null;
        var type = task.trees.getTypeMirror(rootPath);
        if (type == null || type.getKind() != TypeKind.DECLARED) return null;

        // Walk forward through the chain, resolving each method's return type
        for (var methodName : chain.reversed()) {
            var method = findMethod(task, type, methodName);
            if (method == null) return null;
            var memberType = task.types.asMemberOf((DeclaredType) type, method);
            if (!(memberType instanceof ExecutableType executable)) return null;
            type = executable.getReturnType();
            if (type == null || type.getKind() != TypeKind.DECLARED) return null;
        }

        // Now 'type' is the declaring type of the target method
        return findMethod(task, type, targetName);
    }

    private static ExecutableElement findMethod(CompileTask task, TypeMirror type, String name) {
        if (type.getKind() != TypeKind.DECLARED) return null;
        var typeEl = (TypeElement) ((DeclaredType) type).asElement();
        ExecutableElement found = null;
        for (var m : task.elements.getAllMembers(typeEl)) {
            if (m.getKind() == ElementKind.METHOD && m.getSimpleName().contentEquals(name)) {
                if (found != null) return null;
                found = (ExecutableElement) m;
            }
        }
        return found;
    }

    public static boolean isLocal(Element element) {
        if (element.getModifiers().contains(Modifier.PRIVATE)) {
            return true;
        }
        switch (element.getKind()) {
            case EXCEPTION_PARAMETER:
            case LOCAL_VARIABLE:
            case PARAMETER:
            case TYPE_PARAMETER:
                return true;
            default:
                return false;
        }
    }

    public static boolean isMember(Element element) {
        switch (element.getKind()) {
            case ENUM_CONSTANT:
            case FIELD:
            case METHOD:
            case CONSTRUCTOR:
            case RECORD_COMPONENT:
                return true;
            default:
                return false;
        }
    }

    public static boolean isType(Element element) {
        switch (element.getKind()) {
            case ANNOTATION_TYPE:
            case CLASS:
            case ENUM:
            case INTERFACE:
                return true;
            default:
                return false;
        }
    }
}
