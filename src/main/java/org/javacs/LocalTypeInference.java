package org.javacs;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.Map;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

public final class LocalTypeInference {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");
    private static final String DEBUG_COMPLETION_RESOLUTION_PROP = "jls.debug.completion.resolution";

    private LocalTypeInference() {}

    public static DeclaredType inferDeclaredTypeOfVarIdentifier(
            CompileTask task, TreePath identifierPath, LombokMetadataCache cache) {
        var debugResolution = Boolean.getBoolean(DEBUG_COMPLETION_RESOLUTION_PROP);
        if (identifierPath == null || !(identifierPath.getLeaf() instanceof IdentifierTree)) {
            return null;
        }
        var trees = Trees.instance(task.task);
        var element = trees.getElement(identifierPath);
        if (!(element instanceof VariableElement)) {
            return null;
        }
        var variable = (VariableElement) element;
        if (variable.asType() instanceof DeclaredType && isResolvedType(variable.asType())) {
            if (debugResolution) {
                LOG.info(
                        "[local-type-infer] var="
                                + variable.getSimpleName()
                                + " declared_type="
                                + variable.asType()
                                + " source=element");
            }
            return (DeclaredType) variable.asType();
        }
        var declarationPath = trees.getPath(element);
        if (declarationPath == null || !(declarationPath.getLeaf() instanceof VariableTree)) {
            declarationPath = findVariableDeclarationPath(task, identifierPath, variable.getSimpleName().toString());
        }
        if (declarationPath == null || !(declarationPath.getLeaf() instanceof VariableTree)) {
            return null;
        }
        var declaration = (VariableTree) declarationPath.getLeaf();
        if (!(declaration.getType() instanceof IdentifierTree)) {
            return null;
        }
        var typeIdent = (IdentifierTree) declaration.getType();
        if (!typeIdent.getName().contentEquals("var")) {
            return null;
        }
        if (declaration.getInitializer() == null) {
            return null;
        }
        var initializerPath = new TreePath(declarationPath, declaration.getInitializer());
        var inferred = inferExpressionType(task, initializerPath, cache, Map.of());
        if (inferred instanceof DeclaredType && isResolvedType(inferred)) {
            if (debugResolution) {
                LOG.info(
                        "[local-type-infer] var="
                                + variable.getSimpleName()
                                + " inferred_type="
                                + inferred
                                + " source=initializer");
            }
            return (DeclaredType) inferred;
        }
        if (debugResolution) {
            LOG.info(
                    "[local-type-infer] var="
                            + variable.getSimpleName()
                            + " inferred_type="
                            + inferred
                            + " source=initializer_unresolved");
        }
        return null;
    }

    public static TypeMirror inferExpressionType(
            CompileTask task,
            TreePath expressionPath,
            LombokMetadataCache cache,
            Map<String, TypeMirror> localTypes) {
        var trees = Trees.instance(task.task);
        var type = trees.getTypeMirror(expressionPath);
        if (isResolvedType(type)) {
            return type;
        }

        var leaf = expressionPath.getLeaf();
        if (leaf instanceof IdentifierTree) {
            var inferred = localTypes.get(((IdentifierTree) leaf).getName().toString());
            if (isResolvedType(inferred)) {
                return inferred;
            }
        }

        if (leaf instanceof MethodInvocationTree) {
            var invocation = (MethodInvocationTree) leaf;
            var lombokResolved = LombokHandler.resolveMethodInvocationReturnType(task, invocation, expressionPath, cache);
            if (isResolvedType(lombokResolved)) {
                return lombokResolved;
            }
            var scopeResolved = resolveReturnTypeFromScopeReceiver(task, invocation, localTypes);
            if (isResolvedType(scopeResolved)) {
                return scopeResolved;
            }
        }

        if (type != null && type.getKind() == TypeKind.ERROR) {
            var resolved = LombokHandler.resolveLombokGeneratedMethodType(task, type.toString(), cache);
            if (isResolvedType(resolved)) {
                return resolved;
            }
        }
        return type;
    }

    private static TreePath findVariableDeclarationPath(CompileTask task, TreePath usagePath, String variableName) {
        if (usagePath == null || variableName == null || variableName.isEmpty()) {
            return null;
        }
        var root = usagePath.getCompilationUnit();
        if (root == null) {
            return null;
        }
        var trees = Trees.instance(task.task);
        var sourcePositions = trees.getSourcePositions();
        var usageStart = sourcePositions.getStartPosition(root, usagePath.getLeaf());
        if (usageStart == javax.tools.Diagnostic.NOPOS) {
            return null;
        }
        class Finder extends TreePathScanner<Void, Void> {
            private TreePath best;
            private long bestEnd = Long.MIN_VALUE;

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (node.getName().contentEquals(variableName)) {
                    var end = sourcePositions.getEndPosition(root, node);
                    if (end != javax.tools.Diagnostic.NOPOS && end <= usageStart && end > bestEnd) {
                        bestEnd = end;
                        best = getCurrentPath();
                    }
                }
                return super.visitVariable(node, unused);
            }
        }
        var finder = new Finder();
        finder.scan(root, null);
        return finder.best;
    }

    private static TypeMirror resolveReturnTypeFromScopeReceiver(
            CompileTask task, MethodInvocationTree invocation, Map<String, TypeMirror> localTypes) {
        if (!(invocation.getMethodSelect() instanceof MemberSelectTree)) {
            return null;
        }
        var memberSelect = (MemberSelectTree) invocation.getMethodSelect();
        if (!(memberSelect.getExpression() instanceof IdentifierTree)) {
            return null;
        }
        var receiverName = ((IdentifierTree) memberSelect.getExpression()).getName().toString();
        var receiverType = localTypes.get(receiverName);
        if (!(receiverType instanceof DeclaredType)) {
            return null;
        }
        var receiverElement = (TypeElement) ((DeclaredType) receiverType).asElement();
        var methodName = memberSelect.getIdentifier().toString();
        var argumentCount = invocation.getArguments().size();
        for (var member : task.task.getElements().getAllMembers(receiverElement)) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            var method = (ExecutableElement) member;
            if (!method.getSimpleName().contentEquals(methodName)) {
                continue;
            }
            if (method.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            if (method.getParameters().size() != argumentCount) {
                continue;
            }
            return method.getReturnType();
        }
        return null;
    }

    private static boolean isResolvedType(TypeMirror type) {
        return type != null && type.getKind() != TypeKind.ERROR && type.getKind() != TypeKind.NONE;
    }
}
