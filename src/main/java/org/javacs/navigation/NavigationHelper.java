package org.javacs.navigation;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompileTask;
import org.javacs.FindNameAt;

public class NavigationHelper {
    private static final Logger LOG = Logger.getLogger("main");

    public static Element findElement(CompileTask task, Path file, int line, int column) {
        for (var root : task.roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) {
                var trees = task.trees;
                var cursor = root.getLineMap().getPosition(line, column);
                var path = new FindNameAt(task).scan(root, cursor);
                if (path == null) return null;
                var element = trees.getElement(path);
                if (element != null && element.asType().getKind() == TypeKind.ERROR
                        && path.getLeaf() instanceof MemberSelectTree select) {
                    var resolved = resolveChain(task, path, select);
                    if (resolved != null) element = resolved;
                }
                return element;
            }
        }
        throw new RuntimeException("file not found");
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
        Collections.reverse(chain);

        // Resolve the root expression's type
        var rootPath = TreePath.getPath(path.getCompilationUnit(), expr);
        if (rootPath == null) return null;
        var type = task.trees.getTypeMirror(rootPath);
        if (type == null || type.getKind() != TypeKind.DECLARED) return null;

        // Walk forward through the chain, resolving each method's return type
        for (var methodName : chain) {
            var method = findMethod(task, type, methodName);
            if (method == null) return null;
            type = method.getReturnType();
            if (type == null || type.getKind() != TypeKind.DECLARED) return null;
        }

        // Now 'type' is the declaring type of the target method
        return findMethod(task, type, targetName);
    }

    private static ExecutableElement findMethod(CompileTask task, TypeMirror type, String name) {
        if (type.getKind() != TypeKind.DECLARED) return null;
        var typeEl = (TypeElement) ((DeclaredType) type).asElement();
        for (var m : task.elements.getAllMembers(typeEl)) {
            if (m.getKind() == ElementKind.METHOD && m.getSimpleName().contentEquals(name)) {
                return (ExecutableElement) m;
            }
        }
        return null;
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
