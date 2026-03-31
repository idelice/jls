package org.javacs.resolve;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Modifier;
import org.javacs.CompilerProvider;
import org.javacs.LombokAnnotations;
import org.javacs.ParseTask;
import org.javacs.ExternalTypeLookup;
import org.javacs.completion.TypeIndexRouter;
import org.javacs.completion.FindCompletionsAt;
import org.javacs.completion.WorkspaceTypeIndex;
import org.javacs.lsp.CompletionItemKind;

/**
 * Parse-first type resolver used by fast providers that must stay on the parse/index boundary and
 * avoid a semantic compile on the hot path.
 *
 * <p>The resolution order is intentionally layered:
 *
 * <ol>
 *   <li>Lexical locals and parameters.
 *   <li>Enclosing fields and nested types.
 *   <li>Workspace and external index members/types.
 *   <li>Source-backed workspace fallback when the index does not fully describe the current file.
 *   <li>Invocation, lambda, SAM, and stream inference.
 *   <li>External reflective inference for dependency-only behavior.
 * </ol>
 */
public final class ParseTypeResolver {
    public record TypeResolution(
            String qualifiedType, boolean staticContext, boolean arrayType, String firstTypeArgument) {
        public TypeResolution(String qualifiedType, boolean staticContext, boolean arrayType) {
            this(qualifiedType, staticContext, arrayType, null);
        }

        public TypeResolution withFirstTypeArgument(String nextFirstTypeArgument) {
            return new TypeResolution(qualifiedType, staticContext, arrayType, nextFirstTypeArgument);
        }
    }

    private record CandidateType(TypeResolution resolution, int depth, long start) {}

    private static final class CandidatePath {
        TreePath path;
        int depth;
        long start;

        CandidatePath(TreePath path, int depth, long start) {
            this.path = path;
            this.depth = depth;
            this.start = start;
        }
    }

    private static final class SourceClassInfo {
        final CompilationUnitTree sourceRoot;
        final TreePath classPath;
        final ClassTree classTree;

        SourceClassInfo(CompilationUnitTree sourceRoot, TreePath classPath) {
            this.sourceRoot = sourceRoot;
            this.classPath = classPath;
            this.classTree = (ClassTree) classPath.getLeaf();
        }
    }

    private static final int MAX_RESOLVE_DEPTH = 24;

    private final CompilationUnitTree root;
    private final SourcePositions positions;
    private final CompilerProvider compiler;
    private final ExternalTypeLookup typeLookup;
    private final TypeIndexRouter index;
    private final StreamCollectorInference streamCollectorInference;
    private final long cursor;
    private final TreePath cursorPath;
    private ClassLoader externalClassLoader;
    private TypeResolution thisType;
    private TypeResolution superType;

    public ParseTypeResolver(ParseTask parseTask, CompilerProvider compiler, TypeIndexRouter index, long cursor) {
        this.root = parseTask.root();
        this.positions = Trees.instance(parseTask.task()).getSourcePositions();
        this.compiler = compiler;
        this.index = index == null ? TypeIndexRouter.EMPTY : index;
        this.typeLookup = new ExternalTypeLookup(compiler, this.index);
        this.cursor = cursor;
        this.cursorPath = new FindCompletionsAt(parseTask.task()).scan(parseTask.root(), cursor);
        this.streamCollectorInference =
                new StreamCollectorInference(
                        root,
                        this.index,
                        new StreamCollectorInference.Support() {
                            @Override
                            public Optional<TypeResolution> resolveExpression(Tree expression, int depth) {
                                return ParseTypeResolver.this.resolveExpression(expression, depth);
                            }

                            @Override
                            public Optional<TypeResolution> resolveTypeTree(
                                    Tree tree, CompilationUnitTree sourceRoot, boolean staticContext) {
                                return ParseTypeResolver.this.resolveTypeTree(tree, sourceRoot, staticContext);
                            }

                            @Override
                            public Optional<TypeResolution> resolveMember(TypeResolution receiverType, String memberName) {
                                return ParseTypeResolver.this.resolveDirectMemberType(receiverType, memberName);
                            }

                            @Override
                            public Optional<TypeResolution> resolveInvocation(
                                    TypeResolution receiverType, String methodName, int argumentCount) {
                                return ParseTypeResolver.this.resolveUniqueInvocationReturnType(
                                        receiverType, methodName, argumentCount);
                            }
                        });
    }

    // Public entrypoints.
    public Optional<TypeResolution> resolve(Tree expression, String fallbackIdentifier) {
        if (expression != null) {
            var direct = resolveExpression(expression, 0);
            if (direct.isPresent()) {
                return direct;
            }
        }
        if (fallbackIdentifier == null || fallbackIdentifier.isBlank()) {
            return Optional.empty();
        }
        return resolveIdentifier(fallbackIdentifier, 0);
    }

    public Optional<TypeResolution> resolveExpression(Tree expression) {
        return resolveExpression(expression, 0);
    }

    public Optional<String> currentEnclosingTypeName() {
        return resolveThisType().map(TypeResolution::qualifiedType);
    }

    public boolean isEnclosingInstanceType(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank() || cursorPath == null) {
            return false;
        }
        var blockedByStaticContext = false;
        for (var path = cursorPath; path != null; path = path.getParentPath()) {
            var leaf = path.getLeaf();
            if (leaf instanceof MethodTree methodTree
                    && methodTree.getModifiers() != null
                    && methodTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                blockedByStaticContext = true;
                continue;
            }
            if (leaf instanceof BlockTree blockTree) {
                var parent = path.getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree && blockTree.isStatic()) {
                    blockedByStaticContext = true;
                }
                continue;
            }
            if (!(leaf instanceof ClassTree classTree)) {
                continue;
            }
            var className = qualifiedClassName(root, path);
            if (qualifiedType.equals(className)) {
                return !blockedByStaticContext;
            }
            if (classTree.getModifiers() != null && classTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                blockedByStaticContext = true;
            }
        }
        return false;
    }

    // Expression dispatch.
    private Optional<TypeResolution> resolveExpression(Tree expression, int depth) {
        if (expression == null || depth > MAX_RESOLVE_DEPTH) {
            return Optional.empty();
        }
        if (expression instanceof ParenthesizedTree parenthesized) {
            return resolveExpression(parenthesized.getExpression(), depth + 1);
        }
        if (expression instanceof IdentifierTree identifier) {
            return resolveIdentifier(identifier.getName().toString(), depth + 1);
        }
        if (expression instanceof NewClassTree newClassTree) {
            return resolveTypeTree(newClassTree.getIdentifier(), root, false);
        }
        if (expression instanceof NewArrayTree newArrayTree) {
            if (newArrayTree.getType() == null) {
                return Optional.empty();
            }
            var component = resolveTypeTree(newArrayTree.getType(), root, false);
            return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType(), false, true));
        }
        if (expression instanceof MethodInvocationTree invocationTree) {
            return resolveMethodInvocation(invocationTree, depth + 1);
        }
        if (expression instanceof MemberSelectTree memberSelectTree) {
            return resolveMemberSelect(memberSelectTree, depth + 1);
        }
        if (expression instanceof TypeCastTree castTree) {
            return resolveTypeTree(castTree.getType(), root, false);
        }
        if (expression instanceof ArrayAccessTree arrayAccessTree) {
            var array = resolveExpression(arrayAccessTree.getExpression(), depth + 1);
            if (array.isEmpty()) {
                return Optional.empty();
            }
            var type = array.get();
            if (!type.arrayType() || type.qualifiedType() == null || type.qualifiedType().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TypeResolution(type.qualifiedType(), false, false));
        }
        if (expression instanceof LiteralTree literal) {
            return literalType(literal);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMethodInvocation(MethodInvocationTree invocationTree, int depth) {
        var select = invocationTree.getMethodSelect();
        if (select instanceof IdentifierTree identifier) {
            return resolveThisType()
                    .flatMap(
                            current ->
                                    resolveMethodReturnType(
                                            current, identifier.getName().toString(), false));
        }
        if (select instanceof MemberSelectTree memberSelectTree) {
            var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
            if (receiver.isEmpty()) {
                return Optional.empty();
            }
            var streamOrCollector = streamCollectorInference.infer(invocationTree, memberSelectTree, receiver.get(), depth + 1);
            if (streamOrCollector.handled()) {
                return streamOrCollector.resolution();
            }
            return resolveMethodReturnType(
                    receiver.get(),
                    memberSelectTree.getIdentifier().toString(),
                    receiver.get().staticContext());
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMemberSelect(MemberSelectTree memberSelectTree, int depth) {
        var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
        if (receiver.isEmpty()) {
            return resolveTypeTree(memberSelectTree, root, true);
        }
        return resolveDirectMemberType(receiver.get(), memberSelectTree.getIdentifier().toString());
    }

    private Optional<TypeResolution> resolveIdentifier(String identifier, int depth) {
        if ("this".equals(identifier)) {
            return resolveThisType();
        }
        if ("super".equals(identifier)) {
            return resolveSuperType();
        }
        var variable = resolveVisibleVariable(identifier, depth + 1);
        if (variable.isPresent()) {
            return variable;
        }
        var implicitLogger = resolveImplicitSlf4jLogger(identifier);
        if (implicitLogger.isPresent()) {
            return implicitLogger;
        }
        var enclosingField = resolveEnclosingField(identifier);
        if (enclosingField.isPresent()) {
            return enclosingField;
        }
        var nested = resolveNestedTypeInEnclosingScopes(identifier);
        if (nested.isPresent()) {
            return Optional.of(new TypeResolution(nested.get(), true, false));
        }
        var type = typeLookup.resolveTypeName(identifier, root);
        return type.map(s -> new TypeResolution(s, true, false));
    }

    private Optional<TypeResolution> resolveEnclosingField(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        var current = resolveThisType();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        var field = resolveMemberFromIndexOrSource(current.get().qualifiedType(), identifier, false);
        if (field.isEmpty() || field.get().kind != CompletionItemKind.Field) {
            return Optional.empty();
        }
        return returnTypeOf(field.get());
    }

    private Optional<TypeResolution> resolveMethodReturnType(
            TypeResolution receiverType, String methodName, boolean staticContext) {
        if (receiverType == null
                || receiverType.qualifiedType() == null
                || receiverType.qualifiedType().isBlank()
                || methodName == null
                || methodName.isBlank()) {
            return Optional.empty();
        }
        return resolveMemberFromIndexOrSource(receiverType.qualifiedType(), methodName, staticContext)
                .flatMap(member -> resolveMemberReturnType(receiverType, member));
    }

    private Optional<TypeResolution> resolveMemberReturnType(
            TypeResolution receiverType, WorkspaceTypeIndex.Member member) {
        var external = resolveExternalMethodReturnType(receiverType, member);
        return external.isPresent() ? external : returnTypeOf(member);
    }

    /**
     * Prefer index metadata first, then fall back to source only for non-workspace owners.
     *
     * <p>This keeps workspace symbol identity strict and index-backed while still allowing
     * source-backed dependency lookups.
     */
    private Optional<WorkspaceTypeIndex.Member> resolveMemberFromIndexOrSource(
            String ownerType, String memberName, boolean staticContext) {
        var indexed = resolveIndexedMember(ownerType, memberName, staticContext, null);
        if (indexed.isPresent()) {
            return indexed;
        }
        if (index.isWorkspaceOwnedType(ownerType, compiler)) {
            return Optional.empty();
        }
        return resolveSourceMember(ownerType, memberName, staticContext);
    }

    private Optional<WorkspaceTypeIndex.Member> resolveIndexedMember(
            String ownerType, String memberName, boolean staticContext, String[] erasedParameterTypes) {
        if (index.isWorkspaceOwnedType(ownerType, compiler)) {
            return erasedParameterTypes == null
                    ? index.workspace().member(ownerType, memberName, staticContext)
                    : index.workspace().member(ownerType, memberName, staticContext, erasedParameterTypes);
        }
        return erasedParameterTypes == null
                ? index.external().rawMember(ownerType, memberName, staticContext)
                : index.external().rawMember(ownerType, memberName, staticContext, erasedParameterTypes);
    }

    private Optional<WorkspaceTypeIndex.Member> resolveSourceMember(
            String ownerType, String memberName, boolean staticContext) {
        var info = sourceClassInfo(ownerType);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        for (var member : info.get().classTree.getMembers()) {
            if (member instanceof VariableTree field) {
                if (!field.getName().contentEquals(memberName)) {
                    continue;
                }
                var isStatic =
                        field.getModifiers() != null
                                && field.getModifiers().getFlags().contains(Modifier.STATIC);
                if (isStatic != staticContext) {
                    continue;
                }
                var fieldType = resolveTypeTree(field.getType(), info.get().sourceRoot, false);
                if (fieldType.isEmpty()) {
                    continue;
                }
                return Optional.of(
                        new WorkspaceTypeIndex.Member(
                                ownerType,
                                memberName,
                                CompletionItemKind.Field,
                                isStatic,
                                field.getModifiers() != null
                                        && field.getModifiers().getFlags().contains(Modifier.PRIVATE),
                                0,
                                field.getType() + " " + memberName,
                                typeName(fieldType.get()),
                                null,
                                null,
                                WorkspaceTypeIndex.canonicalMemberKey(ownerType, CompletionItemKind.Field, memberName, null),
                                WorkspaceTypeIndex.canonicalMemberKey(ownerType, CompletionItemKind.Field, memberName, null),
                                null,
                                false));
            }
            if (member instanceof MethodTree method) {
                if (!method.getName().contentEquals(memberName)) {
                    continue;
                }
                var isStatic =
                        method.getModifiers() != null
                                && method.getModifiers().getFlags().contains(Modifier.STATIC);
                if (isStatic != staticContext) {
                    continue;
                }
                var returnType =
                        method.getReturnType() == null
                                ? Optional.<TypeResolution>empty()
                                : resolveTypeTree(method.getReturnType(), info.get().sourceRoot, false);
                var parameterNames = new String[method.getParameters().size()];
                var erasedParameterTypes = new String[method.getParameters().size()];
                for (int i = 0; i < method.getParameters().size(); i++) {
                    var parameter = method.getParameters().get(i);
                    parameterNames[i] = parameter.getName().toString();
                    erasedParameterTypes[i] = parameter.getType().toString();
                }
                return Optional.of(
                        new WorkspaceTypeIndex.Member(
                                ownerType,
                                memberName,
                                CompletionItemKind.Method,
                                isStatic,
                                method.getModifiers() != null
                                        && method.getModifiers().getFlags().contains(Modifier.PRIVATE),
                                0,
                                method.toString(),
                                returnType.map(ParseTypeResolver::typeName).orElse(null),
                                parameterNames,
                                erasedParameterTypes,
                                WorkspaceTypeIndex.canonicalMemberKey(
                                        ownerType, CompletionItemKind.Method, memberName, erasedParameterTypes),
                                WorkspaceTypeIndex.canonicalMemberKey(
                                        ownerType, CompletionItemKind.Method, memberName, erasedParameterTypes),
                                null,
                                false));
            }
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveImplicitSlf4jLogger(String identifier) {
        if (!"log".equals(identifier)) {
            return Optional.empty();
        }
        var classPath = enclosingClassPath();
        if (classPath == null) {
            return Optional.empty();
        }
        var classTree = (ClassTree) classPath.getLeaf();
        if (!LombokAnnotations.hasLoggingOnlyLombokAnnotation(classTree.getModifiers())) {
            return Optional.empty();
        }
        var ownerType = qualifiedClassName(root, classPath);
        var loggerMember =
                index.isWorkspaceOwnedType(ownerType, compiler)
                        ? index.workspace().member(ownerType, "log", false)
                        : index.member(ownerType, "log", false);
        if (loggerMember.isPresent() && loggerMember.get().returnType != null) {
            return Optional.of(new TypeResolution(loggerMember.get().returnType, false, false));
        }
        loggerMember =
                index.isWorkspaceOwnedType(ownerType, compiler)
                        ? index.workspace().member(ownerType, "log", true)
                        : index.member(ownerType, "log", true);
        if (loggerMember.isPresent() && loggerMember.get().returnType != null) {
            return Optional.of(new TypeResolution(loggerMember.get().returnType, false, false));
        }
        var loggerType = "org.slf4j.Logger";
        if (!index.containsType(loggerType)) {
            return Optional.empty();
        }
        return Optional.of(new TypeResolution(loggerType, false, false));
    }

    // Source-name and nested-type fallback.
    private Optional<String> resolveNestedTypeInEnclosingScopes(String simpleName) {
        for (var classPath = enclosingClassPath();
                classPath != null;
                classPath = parentClassPath(classPath.getParentPath())) {
            var owner = qualifiedClassName(root, classPath);
            var candidate = owner + "." + simpleName;
            if (index.workspace().containsType(candidate) || sourceClassInfo(candidate).isPresent()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private TreePath parentClassPath(TreePath from) {
        for (var cursor = from; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree) {
                return cursor;
            }
        }
        return null;
    }

    // Lexical scope resolution.
    private Optional<TypeResolution> resolveVisibleVariable(String targetName, int depth) {
        final CandidateType[] best = new CandidateType[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void p) {
                if (!containsCursor(classTree)) {
                    return null;
                }
                for (var member : classTree.getMembers()) {
                    if (member instanceof VariableTree) {
                        scan(member, p);
                    } else if (containsCursor(member)) {
                        scan(member, p);
                    }
                }
                return null;
            }

            @Override
            public Void visitMethod(MethodTree methodTree, Void p) {
                if (!containsCursor(methodTree)) {
                    return null;
                }
                for (var parameter : methodTree.getParameters()) {
                    consider(parameter, getCurrentPath(), depth + 1);
                }
                if (methodTree.getBody() != null) {
                    scan(methodTree.getBody(), p);
                }
                return null;
            }

            @Override
            public Void visitBlock(BlockTree blockTree, Void p) {
                if (!containsCursor(blockTree)) {
                    return null;
                }
                for (var statement : blockTree.getStatements()) {
                    var start = startOf(statement);
                    if (start >= 0 && start >= cursor) {
                        break;
                    }
                    scan(statement, p);
                }
                return null;
            }

            @Override
            public Void visitVariable(VariableTree variableTree, Void p) {
                consider(variableTree, getCurrentPath(), depth + 1);
                return null;
            }

            private void consider(VariableTree variableTree, TreePath path, int nextDepth) {
                if (!variableTree.getName().contentEquals(targetName)) {
                    return;
                }
                var start = startOf(variableTree);
                if (start < 0 || start >= cursor) {
                    return;
                }
                var resolved = resolveVariableType(variableTree, path, nextDepth);
                if (resolved.isEmpty()) {
                    return;
                }
                var candidate = new CandidateType(resolved.get(), depth(path), start);
                if (best[0] == null
                        || candidate.depth() > best[0].depth()
                        || (candidate.depth() == best[0].depth() && candidate.start() > best[0].start())) {
                    best[0] = candidate;
                }
            }
        }.scan(root, null);

        if (best[0] == null) {
            return Optional.empty();
        }
        return Optional.of(best[0].resolution());
    }

    private Optional<TypeResolution> resolveVariableType(VariableTree variableTree, TreePath path, int depth) {
        if (variableTree.getType() != null && !"var".equals(variableTree.getType().toString())) {
            return resolveTypeTree(variableTree.getType(), root, false);
        }
        var enhancedFor = resolveEnhancedForVariableType(variableTree, path, depth + 1);
        if (enhancedFor.isPresent()) {
            return enhancedFor;
        }
        var lambda = resolveLambdaParameterType(variableTree, path, depth + 1);
        if (lambda.isPresent()) {
            return lambda;
        }
        if (variableTree.getInitializer() != null) {
            return resolveExpression(variableTree.getInitializer(), depth + 1);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveEnhancedForVariableType(
            VariableTree variableTree, TreePath path, int depth) {
        var parent = path == null ? null : path.getParentPath();
        if (parent == null || !(parent.getLeaf() instanceof EnhancedForLoopTree loop) || loop.getVariable() != variableTree) {
            return Optional.empty();
        }
        var iterableType = resolveExpression(loop.getExpression(), depth + 1);
        if (iterableType.isEmpty()) {
            return Optional.empty();
        }
        if (iterableType.get().arrayType()) {
            return Optional.of(new TypeResolution(iterableType.get().qualifiedType(), false, false));
        }
        if (iterableType.get().firstTypeArgument() == null || iterableType.get().firstTypeArgument().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new TypeResolution(iterableType.get().firstTypeArgument(), false, false));
    }

    private Optional<TypeResolution> resolveLambdaParameterType(
            VariableTree variableTree, TreePath path, int depth) {
        var parent = path == null ? null : path.getParentPath();
        if (parent == null || !(parent.getLeaf() instanceof LambdaExpressionTree lambda)) {
            return Optional.empty();
        }
        if (lambda.getParameters().size() != 1 || lambda.getParameters().get(0) != variableTree) {
            return Optional.empty();
        }
        var invocationPath = parent.getParentPath();
        if (invocationPath == null || !(invocationPath.getLeaf() instanceof MethodInvocationTree invocation)) {
            return Optional.empty();
        }
        var argumentIndex = -1;
        for (int i = 0; i < invocation.getArguments().size(); i++) {
            if (invocation.getArguments().get(i) == lambda) {
                argumentIndex = i;
                break;
            }
        }
        if (argumentIndex < 0) {
            return Optional.empty();
        }
        var functionalType = resolveInvocationArgumentType(invocation, argumentIndex, depth + 1);
        if (functionalType.isEmpty()) {
            return Optional.empty();
        }
        return resolveSamParameterType(functionalType.get());
    }

    /** Resolve a source type tree into a qualified type before any declaration lookup begins. */
    private Optional<TypeResolution> resolveTypeTree(Tree tree, CompilationUnitTree sourceRoot, boolean staticContext) {
        if (tree == null) {
            return Optional.empty();
        }
        if (tree instanceof AnnotatedTypeTree annotatedTypeTree) {
            return resolveTypeTree(annotatedTypeTree.getUnderlyingType(), sourceRoot, staticContext);
        }
        if (tree instanceof ParameterizedTypeTree parameterizedTypeTree) {
            var raw = resolveTypeTree(parameterizedTypeTree.getType(), sourceRoot, staticContext);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            var firstTypeArgument = firstTypeArgument(parameterizedTypeTree, sourceRoot);
            return Optional.of(raw.get().withFirstTypeArgument(firstTypeArgument.orElse(null)));
        }
        if (tree instanceof ArrayTypeTree arrayTypeTree) {
            var component = resolveTypeTree(arrayTypeTree.getType(), sourceRoot, staticContext);
            return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType(), staticContext, true));
        }
        var typeName = tree.toString();
        var resolved = typeLookup.resolveTypeName(typeName, sourceRoot);
        if (resolved.isEmpty()) {
            resolved = resolveTypeNameInSource(typeName, sourceRoot);
        }
        return resolved.map(type -> new TypeResolution(type, staticContext, false));
    }

    private Optional<String> firstTypeArgument(ParameterizedTypeTree parameterizedTypeTree, CompilationUnitTree sourceRoot) {
        if (parameterizedTypeTree.getTypeArguments().isEmpty()) {
            return Optional.empty();
        }
        var resolved = resolveTypeTree(parameterizedTypeTree.getTypeArguments().get(0), sourceRoot, false);
        return resolved.map(ParseTypeResolver::typeName);
    }

    private Optional<TypeResolution> resolveThisType() {
        if (thisType != null) {
            return Optional.of(thisType);
        }
        var classPath = enclosingClassPath();
        if (classPath == null) {
            return Optional.empty();
        }
        var qualified = qualifiedClassName(root, classPath);
        thisType = new TypeResolution(qualified, false, false);
        return Optional.of(thisType);
    }

    private Optional<TypeResolution> resolveSuperType() {
        if (superType != null) {
            return Optional.of(superType);
        }
        var classPath = enclosingClassPath();
        if (classPath == null) {
            return Optional.empty();
        }
        var classTree = (ClassTree) classPath.getLeaf();
        if (classTree.getExtendsClause() == null) {
            superType = new TypeResolution("java.lang.Object", false, false);
            return Optional.of(superType);
        }
        var resolved = resolveTypeTree(classTree.getExtendsClause(), root, false);
        resolved.ifPresent(typeResolution -> superType = typeResolution);
        return resolved;
    }

    // Workspace member/type resolution.
    private Optional<TypeResolution> returnTypeOf(WorkspaceTypeIndex.Member member) {
        var workspaceSource = resolveWorkspaceSourceMemberType(member);
        if (workspaceSource.isPresent()) {
            return workspaceSource;
        }
        if (member.returnType == null || member.returnType.isBlank()) {
            return Optional.empty();
        }
        var type = member.returnType;
        var isArray = type.endsWith("[]");
        if (isArray) {
            type = type.substring(0, type.length() - 2);
        }
        var resolved = typeLookup.resolveTypeName(type, root);
        if (resolved.isEmpty()) {
            resolved = resolveTypeNameInCurrentSource(type);
        }
        return resolved.map(
                next ->
                        new TypeResolution(
                                next, false, isArray, firstTypeArgumentFromTypeName(member.returnType).orElse(null)));
    }

    /** Recover a member return type directly from workspace source when the index payload is incomplete. */
    private Optional<TypeResolution> resolveWorkspaceSourceMemberType(WorkspaceTypeIndex.Member member) {
        if (member == null || !index.isWorkspaceOwnedType(member.ownerType, compiler)) {
            return Optional.empty();
        }
        var info = sourceClassInfo(member.ownerType);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        if (member.backingFieldName != null && !member.backingFieldName.isBlank()) {
            for (var candidate : info.get().classTree.getMembers()) {
                if (candidate instanceof VariableTree field && field.getName().contentEquals(member.backingFieldName)) {
                    return resolveTypeTree(field.getType(), info.get().sourceRoot, false);
                }
            }
        }
        for (var candidate : info.get().classTree.getMembers()) {
            if (member.kind == CompletionItemKind.Field && candidate instanceof VariableTree field && field.getName().contentEquals(member.name)) {
                return resolveTypeTree(field.getType(), info.get().sourceRoot, false);
            }
            if (member.kind == CompletionItemKind.Method
                    && candidate instanceof MethodTree method
                    && method.getName().contentEquals(member.name)
                    && method.getReturnType() != null
                    && method.getParameters().size() == (member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length)) {
                return resolveTypeTree(method.getReturnType(), info.get().sourceRoot, false);
            }
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveDirectMemberType(TypeResolution receiverType, String memberName) {
        if (receiverType == null || memberName == null || memberName.isBlank()) {
            return Optional.empty();
        }
        if (receiverType.arrayType() && "length".equals(memberName)) {
            return Optional.of(new TypeResolution("int", false, false));
        }
        if (receiverType.staticContext() && "class".equals(memberName)) {
            return Optional.of(new TypeResolution("java.lang.Class", false, false));
        }
        var member =
                resolveMemberFromIndexOrSource(
                        receiverType.qualifiedType(),
                        memberName,
                        receiverType.staticContext());
        if (member.isEmpty()) {
            return Optional.empty();
        }
        return returnTypeOf(member.get());
    }

    // Invocation, lambda, and SAM inference.
    private Optional<TypeResolution> resolveUniqueInvocationReturnType(
            TypeResolution receiverType, String methodName, int argumentCount) {
        if (receiverType == null
                || receiverType.qualifiedType() == null
                || receiverType.qualifiedType().isBlank()
                || methodName == null
                || methodName.isBlank()
                || argumentCount < 0) {
            return Optional.empty();
        }
        var indexed = uniqueIndexedMethod(receiverType, methodName, argumentCount);
        if (indexed.isPresent()) {
            return resolveMemberReturnType(receiverType, indexed.get());
        }
        var source = resolveUniqueSourceMethodReturnType(receiverType, methodName, argumentCount);
        if (source.isPresent()) {
            return source;
        }
        if (!canUseExternalInference(receiverType.qualifiedType())) {
            return Optional.empty();
        }
        return resolveReflectiveInvocationReturnType(receiverType, methodName, argumentCount);
    }

    private Optional<WorkspaceTypeIndex.Member> uniqueIndexedMethod(
            TypeResolution receiverType, String methodName, int argumentCount) {
        WorkspaceTypeIndex.Member match = null;
        for (var member : index.members(receiverType.qualifiedType(), receiverType.staticContext())) {
            if (member.kind != CompletionItemKind.Method
                    || !Objects.equals(member.name, methodName)
                    || member.isStatic != receiverType.staticContext()) {
                continue;
            }
            var arity = member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length;
            if (arity != argumentCount) {
                continue;
            }
            if (match != null && !Objects.equals(match.canonicalKey, member.canonicalKey)) {
                return Optional.empty();
            }
            match = member;
        }
        return Optional.ofNullable(match);
    }

    private Optional<TypeResolution> resolveUniqueSourceMethodReturnType(
            TypeResolution receiverType, String methodName, int argumentCount) {
        var info = sourceClassInfo(receiverType.qualifiedType());
        if (info.isEmpty()) {
            return Optional.empty();
        }
        MethodTree match = null;
        for (var candidate : info.get().classTree.getMembers()) {
            if (!(candidate instanceof MethodTree method)
                    || !method.getName().contentEquals(methodName)
                    || method.getParameters().size() != argumentCount) {
                continue;
            }
            var isStatic =
                    method.getModifiers() != null
                            && method.getModifiers().getFlags().contains(Modifier.STATIC);
            if (isStatic != receiverType.staticContext()) {
                continue;
            }
            if (match != null) {
                return Optional.empty();
            }
            match = method;
        }
        if (match == null || match.getReturnType() == null) {
            return Optional.empty();
        }
        return resolveTypeTree(match.getReturnType(), info.get().sourceRoot, false);
    }

    private Optional<TypeResolution> resolveInvocationArgumentType(
            MethodInvocationTree invocation, int argumentIndex, int depth) {
        var select = invocation.getMethodSelect();
        if (select instanceof IdentifierTree identifier) {
            var current = resolveThisType();
            if (current.isEmpty()) {
                return Optional.empty();
            }
            return resolveMethodArgumentType(
                    current.get(), current.get().qualifiedType(), identifier.getName().toString(), false, invocation, argumentIndex);
        }
        if (select instanceof MemberSelectTree memberSelectTree) {
            var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
            if (receiver.isEmpty()) {
                return Optional.empty();
            }
            return resolveMethodArgumentType(
                    receiver.get(),
                    receiver.get().qualifiedType(),
                    memberSelectTree.getIdentifier().toString(),
                    receiver.get().staticContext(),
                    invocation,
                    argumentIndex);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMethodArgumentType(
            TypeResolution receiverType,
            String ownerType,
            String methodName,
            boolean staticContext,
            MethodInvocationTree invocation,
            int argumentIndex) {
        var source = resolveSourceMethodArgumentType(ownerType, methodName, staticContext, invocation, argumentIndex);
        if (source.isPresent()) {
            return source;
        }
        if (!canUseExternalInference(ownerType)) {
            return Optional.empty();
        }
        return resolveExternalMethodArgumentType(receiverType, ownerType, methodName, staticContext, invocation, argumentIndex);
    }

    private Optional<TypeResolution> resolveSourceMethodArgumentType(
            String ownerType,
            String methodName,
            boolean staticContext,
            MethodInvocationTree invocation,
            int argumentIndex) {
        var info = sourceClassInfo(ownerType);
        if (info.isEmpty()) {
            return Optional.empty();
        }
        var matches = new ArrayList<MethodTree>();
        for (var member : info.get().classTree.getMembers()) {
            if (!(member instanceof MethodTree method)) {
                continue;
            }
            if (!method.getName().contentEquals(methodName)) {
                continue;
            }
            var isStatic =
                    method.getModifiers() != null
                            && method.getModifiers().getFlags().contains(Modifier.STATIC);
            if (isStatic != staticContext || method.getParameters().size() != invocation.getArguments().size()) {
                continue;
            }
            matches.add(method);
        }
        if (matches.size() != 1) {
            return Optional.empty();
        }
        return resolveTypeTree(matches.getFirst().getParameters().get(argumentIndex).getType(), info.get().sourceRoot, false);
    }

    private Optional<TypeResolution> resolveExternalMethodArgumentType(
            TypeResolution receiverType,
            String ownerType,
            String methodName,
            boolean staticContext,
            MethodInvocationTree invocation,
            int argumentIndex) {
        var method = resolveReflectiveMethod(ownerType, methodName, staticContext, invocation.getArguments().size());
        if (method.isEmpty()) {
            return Optional.empty();
        }
        var bindings = bindingsForMethodOwner(receiverType, method.get().getDeclaringClass());
        var parameterTypes = method.get().getGenericParameterTypes();
        if (argumentIndex < 0 || argumentIndex >= parameterTypes.length) {
            return Optional.empty();
        }
        return resolveReflectiveType(parameterTypes[argumentIndex], bindings);
    }

    private Optional<TypeResolution> resolveSamParameterType(TypeResolution functionalType) {
        if (functionalType == null || functionalType.qualifiedType() == null || functionalType.qualifiedType().isBlank()) {
            return Optional.empty();
        }
        if (isWorkspaceType(functionalType.qualifiedType())) {
            return resolveSourceSamParameterType(functionalType);
        }
        return resolveExternalSamParameterType(functionalType);
    }

    private Optional<TypeResolution> resolveSourceSamParameterType(TypeResolution functionalType) {
        var info = sourceClassInfo(functionalType.qualifiedType());
        if (info.isEmpty()) {
            return Optional.empty();
        }
        MethodTree sam = null;
        for (var member : info.get().classTree.getMembers()) {
            if (!(member instanceof MethodTree method)) {
                continue;
            }
            var modifiers = method.getModifiers() == null ? Set.<Modifier>of() : method.getModifiers().getFlags();
            if (modifiers.contains(Modifier.DEFAULT) || modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE)) {
                continue;
            }
            if (method.getBody() != null) {
                continue;
            }
            if (sam != null) {
                return Optional.empty();
            }
            sam = method;
        }
        if (sam == null || sam.getParameters().size() != 1) {
            return Optional.empty();
        }
        return resolveTypeTree(sam.getParameters().get(0).getType(), info.get().sourceRoot, false);
    }

    // External reflective inference.
    private Optional<TypeResolution> resolveExternalMethodReturnType(TypeResolution receiverType, WorkspaceTypeIndex.Member member) {
        if (receiverType == null
                || member == null
                || member.ownerType == null
                || member.name == null
                || isWorkspaceType(member.ownerType)) {
            return Optional.empty();
        }
        var method =
                resolveReflectiveMethod(
                        member.ownerType,
                        member.name,
                        member.isStatic,
                        member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length);
        if (method.isEmpty()) {
            return Optional.empty();
        }
        var bindings = bindingsForMethodOwner(receiverType, method.get().getDeclaringClass());
        return resolveReflectiveType(method.get().getGenericReturnType(), bindings);
    }

    private Optional<TypeResolution> resolveReflectiveInvocationReturnType(
            TypeResolution receiverType, String methodName, int argumentCount) {
        var method =
                resolveReflectiveMethod(
                        receiverType.qualifiedType(),
                        methodName,
                        receiverType.staticContext(),
                        argumentCount);
        if (method.isEmpty()) {
            return Optional.empty();
        }
        var bindings = bindingsForMethodOwner(receiverType, method.get().getDeclaringClass());
        return resolveReflectiveType(method.get().getGenericReturnType(), bindings);
    }

    private Optional<TypeResolution> resolveExternalSamParameterType(TypeResolution functionalType) {
        var rawClass = loadExternalClass(functionalType.qualifiedType());
        if (rawClass.isEmpty()) {
            return Optional.empty();
        }
        var sam = singleAbstractMethod(rawClass.get());
        if (sam.isEmpty() || sam.get().getParameterCount() != 1) {
            return Optional.empty();
        }
        var bindings = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
        var vars = rawClass.get().getTypeParameters();
        if (vars.length > 0 && functionalType.firstTypeArgument() != null && !functionalType.firstTypeArgument().isBlank()) {
            bindings.put(vars[0], simpleResolution(functionalType.firstTypeArgument()));
        }
        return resolveReflectiveType(sam.get().getGenericParameterTypes()[0], bindings);
    }

    private Optional<Method> resolveReflectiveMethod(
            String ownerType, String methodName, boolean staticContext, int parameterCount) {
        var rawClass = loadExternalClass(ownerType);
        if (rawClass.isEmpty()) {
            return Optional.empty();
        }
        Method match = null;
        for (var method : rawClass.get().getMethods()) {
            if (!method.getName().equals(methodName)
                    || method.getParameterCount() != parameterCount
                    || staticContext != java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (match != null && !sameReflectiveSignature(match, method)) {
                return Optional.empty();
            }
            match = method;
        }
        return Optional.ofNullable(match);
    }

    private boolean sameReflectiveSignature(Method left, Method right) {
        return left.getDeclaringClass().equals(right.getDeclaringClass())
                && Objects.deepEquals(left.getParameterTypes(), right.getParameterTypes());
    }

    private Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindingsForMethodOwner(
            TypeResolution receiverType, Class<?> declaringClass) {
        if (receiverType == null || receiverType.qualifiedType() == null || receiverType.qualifiedType().isBlank()) {
            return Map.of();
        }
        var receiverClass = loadExternalClass(receiverType.qualifiedType());
        if (receiverClass.isEmpty()) {
            return Map.of();
        }
        var initial = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
        var receiverVars = receiverClass.get().getTypeParameters();
        if (receiverVars.length > 0 && receiverType.firstTypeArgument() != null && !receiverType.firstTypeArgument().isBlank()) {
            initial.put(receiverVars[0], simpleResolution(receiverType.firstTypeArgument()));
        }
        return resolveBindingsForClass(receiverClass.get(), initial, declaringClass).orElse(Map.of());
    }

    private Optional<Map<java.lang.reflect.TypeVariable<?>, TypeResolution>> resolveBindingsForClass(
            Class<?> current,
            Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindings,
            Class<?> target) {
        if (current.equals(target)) {
            return Optional.of(bindings);
        }
        var candidates = new ArrayList<Type>();
        Collections.addAll(candidates, current.getGenericInterfaces());
        if (current.getGenericSuperclass() != null) {
            candidates.add(current.getGenericSuperclass());
        }
        for (var candidate : candidates) {
            var raw = rawClass(candidate);
            if (raw.isEmpty()) {
                continue;
            }
            var nextBindings = bindingsForSuperType(candidate, bindings);
            if (raw.get().equals(target)) {
                return Optional.of(nextBindings);
            }
            var nested = resolveBindingsForClass(raw.get(), nextBindings, target);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindingsForSuperType(
            Type superType, Map<java.lang.reflect.TypeVariable<?>, TypeResolution> currentBindings) {
        if (!(superType instanceof ParameterizedType parameterized) || !(parameterized.getRawType() instanceof Class<?> rawClass)) {
            return Map.of();
        }
        var next = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
        var vars = rawClass.getTypeParameters();
        var args = parameterized.getActualTypeArguments();
        for (int i = 0; i < vars.length && i < args.length; i++) {
            var resolved = resolveReflectiveType(args[i], currentBindings);
            if (resolved.isPresent()) {
                next.put(vars[i], resolved.get());
            }
        }
        return next;
    }

    private Optional<TypeResolution> resolveReflectiveType(
            Type type, Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindings) {
        if (type instanceof Class<?> rawClass) {
            if (rawClass.isArray()) {
                return Optional.of(new TypeResolution(rawClass.getComponentType().getName(), false, true));
            }
            return Optional.of(new TypeResolution(rawClass.getName(), false, false));
        }
        if (type instanceof ParameterizedType parameterized) {
            var raw = resolveReflectiveType(parameterized.getRawType(), bindings);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            var firstArg =
                    parameterized.getActualTypeArguments().length == 0
                            ? Optional.<TypeResolution>empty()
                            : resolveReflectiveType(parameterized.getActualTypeArguments()[0], bindings);
            return Optional.of(raw.get().withFirstTypeArgument(firstArg.map(ParseTypeResolver::typeName).orElse(null)));
        }
        if (type instanceof java.lang.reflect.TypeVariable<?> typeVariable) {
            var bound = bindings.get(typeVariable);
            if (bound != null) {
                return Optional.of(bound);
            }
            var bounds = typeVariable.getBounds();
            return bounds.length == 0 ? Optional.empty() : resolveReflectiveType(bounds[0], bindings);
        }
        if (type instanceof java.lang.reflect.WildcardType wildcard) {
            if (wildcard.getLowerBounds().length > 0) {
                return resolveReflectiveType(wildcard.getLowerBounds()[0], bindings);
            }
            for (var upper : wildcard.getUpperBounds()) {
                var resolved = resolveReflectiveType(upper, bindings);
                if (resolved.isPresent() && !"java.lang.Object".equals(resolved.get().qualifiedType())) {
                    return resolved;
                }
            }
            return Optional.empty();
        }
        if (type instanceof GenericArrayType arrayType) {
            var component = resolveReflectiveType(arrayType.getGenericComponentType(), bindings);
            return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType(), false, true));
        }
        return Optional.empty();
    }

    private Optional<Class<?>> rawClass(Type type) {
        if (type instanceof Class<?> rawClass) {
            return Optional.of(rawClass);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawClass) {
            return Optional.of(rawClass);
        }
        return Optional.empty();
    }

    private Optional<Class<?>> loadExternalClass(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return Optional.empty();
        }
        var loader = externalClassLoader();
        if (loader == null) {
            return Optional.empty();
        }
        var binaryName = qualifiedType;
        while (true) {
            try {
                return Optional.of(Class.forName(binaryName, false, loader));
            } catch (ClassNotFoundException ignored) {
                var split = binaryName.lastIndexOf('.');
                if (split < 0) {
                    return Optional.empty();
                }
                binaryName = binaryName.substring(0, split) + "$" + binaryName.substring(split + 1);
            }
        }
    }

    private ClassLoader externalClassLoader() {
        if (externalClassLoader != null) {
            return externalClassLoader;
        }
        var urls =
                compiler.classPathRoots().stream()
                        .map(Path::toUri)
                        .map(
                                uri -> {
                                    try {
                                        return uri.toURL();
                                    } catch (java.io.IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                        .toArray(URL[]::new);
        externalClassLoader = new URLClassLoader(urls, ParseTypeResolver.class.getClassLoader());
        return externalClassLoader;
    }

    private Optional<Method> singleAbstractMethod(Class<?> rawClass) {
        Method found = null;
        for (var method : rawClass.getMethods()) {
            if (!java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                    || method.getDeclaringClass().equals(Object.class)) {
                continue;
            }
            if (found != null && !sameReflectiveSignature(found, method)) {
                return Optional.empty();
            }
            found = method;
        }
        return Optional.ofNullable(found);
    }

    private Optional<String> firstTypeArgumentFromTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var start = typeName.indexOf('<');
        var end = typeName.lastIndexOf('>');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        var first = typeName.substring(start + 1, end).trim();
        var comma = first.indexOf(',');
        if (comma >= 0) {
            first = first.substring(0, comma).trim();
        }
        while (first.startsWith("? extends ")) {
            first = first.substring("? extends ".length()).trim();
        }
        while (first.startsWith("? super ")) {
            first = first.substring("? super ".length()).trim();
        }
        if (first.isBlank() || "?".equals(first)) {
            return Optional.empty();
        }
        var resolved = typeLookup.resolveTypeName(first, root);
        if (resolved.isEmpty()) {
            resolved = resolveTypeNameInCurrentSource(first);
        }
        return resolved;
    }

    private boolean isWorkspaceType(String qualifiedType) {
        return qualifiedType != null
                && !qualifiedType.isBlank()
                && index.isWorkspaceOwnedType(qualifiedType, compiler);
    }

    private boolean canUseExternalInference(String qualifiedType) {
        return qualifiedType != null && !qualifiedType.isBlank() && !isWorkspaceType(qualifiedType);
    }

    // Source-backed workspace lookup and source-name fallback.
    private Optional<String> resolveTypeNameInCurrentSource(String typeName) {
        return resolveTypeNameInSource(typeName, root);
    }

    private Optional<String> resolveTypeNameInSource(String typeName, CompilationUnitTree sourceRoot) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var raw = typeName.replace('$', '.');
        if (raw.contains(".") && declaredClassPathInRoot(sourceRoot, raw) != null) {
            return Optional.of(raw);
        }
        final String[] match = new String[1];
        final boolean[] ambiguous = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                var qualified = qualifiedClassName(sourceRoot, getCurrentPath());
                var simple = classTree.getSimpleName().toString();
                if (!simple.equals(raw)) {
                    return super.visitClass(classTree, unused);
                }
                if (match[0] == null) {
                    match[0] = qualified;
                } else if (!match[0].equals(qualified)) {
                    ambiguous[0] = true;
                }
                return super.visitClass(classTree, unused);
            }
        }.scan(sourceRoot, null);
        if (ambiguous[0]) {
            return Optional.empty();
        }
        return Optional.ofNullable(match[0]);
    }

    private Optional<SourceClassInfo> sourceClassInfo(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return Optional.empty();
        }
        var current = declaredClassPathInRoot(root, qualifiedType);
        if (current != null) {
            return Optional.of(new SourceClassInfo(root, current));
        }
        var declaration = compiler.findTypeDeclaration(qualifiedType);
        if (declaration == null || declaration.equals(CompilerProvider.NOT_FOUND)) {
            return Optional.empty();
        }
        var parsed = compiler.parse(declaration);
        var classPath = declaredClassPathInRoot(parsed.root(), qualifiedType);
        return classPath == null ? Optional.empty() : Optional.of(new SourceClassInfo(parsed.root(), classPath));
    }

    private TreePath declaredClassPathInRoot(CompilationUnitTree sourceRoot, String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return null;
        }
        final TreePath[] match = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                if (qualifiedType.equals(qualifiedClassName(sourceRoot, getCurrentPath()))) {
                    match[0] = getCurrentPath();
                    return null;
                }
                return super.visitClass(classTree, unused);
            }
        }.scan(sourceRoot, null);
        return match[0];
    }

    private Optional<TypeResolution> literalType(LiteralTree literal) {
        var value = literal.getValue();
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String) {
            return Optional.of(new TypeResolution("java.lang.String", false, false));
        }
        if (value instanceof Integer) {
            return Optional.of(new TypeResolution("java.lang.Integer", false, false));
        }
        if (value instanceof Long) {
            return Optional.of(new TypeResolution("java.lang.Long", false, false));
        }
        if (value instanceof Float) {
            return Optional.of(new TypeResolution("java.lang.Float", false, false));
        }
        if (value instanceof Double) {
            return Optional.of(new TypeResolution("java.lang.Double", false, false));
        }
        if (value instanceof Boolean) {
            return Optional.of(new TypeResolution("java.lang.Boolean", false, false));
        }
        if (value instanceof Character) {
            return Optional.of(new TypeResolution("java.lang.Character", false, false));
        }
        return Optional.empty();
    }

    // Cursor and scope utilities.
    private TreePath enclosingClassPath() {
        final TreePath[] best = new TreePath[1];
        final int[] bestDepth = {-1};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void p) {
                if (!containsCursor(classTree)) {
                    return null;
                }
                var depth = depth(getCurrentPath());
                if (depth > bestDepth[0]) {
                    bestDepth[0] = depth;
                    best[0] = getCurrentPath();
                }
                return super.visitClass(classTree, p);
            }
        }.scan(root, null);
        return best[0];
    }

    private String qualifiedClassName(CompilationUnitTree sourceRoot, TreePath classPath) {
        var classes = new ArrayList<String>();
        for (var cursorPath = classPath; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
            if (cursorPath.getLeaf() instanceof ClassTree classTree) {
                classes.add(classTree.getSimpleName().toString());
            }
        }
        Collections.reverse(classes);
        var packageName = sourceRoot.getPackageName() == null ? "" : sourceRoot.getPackageName().toString();
        return packageName.isEmpty() ? String.join(".", classes) : packageName + "." + String.join(".", classes);
    }

    private boolean containsCursor(Tree tree) {
        var start = startOf(tree);
        var end = endOf(tree);
        if (start < 0 || end < 0) {
            return false;
        }
        return start <= cursor && cursor <= end;
    }

    private long startOf(Tree tree) {
        return positions.getStartPosition(root, tree);
    }

    private long endOf(Tree tree) {
        return positions.getEndPosition(root, tree);
    }

    private int depth(TreePath path) {
        var depth = 0;
        for (var cursorPath = path; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
            depth++;
        }
        return depth;
    }

    private static TypeResolution simpleResolution(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return new TypeResolution("", false, false);
        }
        var array = qualifiedType.endsWith("[]");
        return new TypeResolution(
                array ? qualifiedType.substring(0, qualifiedType.length() - 2) : qualifiedType, false, array);
    }

    private static String typeName(TypeResolution resolution) {
        if (resolution == null || resolution.qualifiedType() == null) {
            return null;
        }
        return resolution.arrayType() ? resolution.qualifiedType() + "[]" : resolution.qualifiedType();
    }

    /** Return the nearest local declaration that is visible at the cursor position. */
    public Optional<TreePath> resolveVisibleDeclaration(String targetName) {
        var best = new CandidatePath(null, -1, -1);
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void p) {
                if (!containsCursor(classTree)) {
                    return null;
                }
                for (var member : classTree.getMembers()) {
                    if (member instanceof VariableTree) {
                        scan(member, p);
                    } else if (containsCursor(member)) {
                        scan(member, p);
                    }
                }
                return null;
            }

            @Override
            public Void visitMethod(MethodTree methodTree, Void p) {
                if (!containsCursor(methodTree)) {
                    return null;
                }
                for (var parameter : methodTree.getParameters()) {
                    consider(parameter, new TreePath(getCurrentPath(), parameter));
                }
                if (methodTree.getBody() != null) {
                    scan(methodTree.getBody(), p);
                }
                return null;
            }

            @Override
            public Void visitBlock(BlockTree blockTree, Void p) {
                if (!containsCursor(blockTree)) {
                    return null;
                }
                for (var statement : blockTree.getStatements()) {
                    var start = startOf(statement);
                    if (start >= 0 && start >= cursor) {
                        break;
                    }
                    if (statement instanceof VariableTree variableTree) {
                        consider(variableTree, new TreePath(getCurrentPath(), variableTree));
                    }
                    if (containsCursor(statement)) {
                        scan(statement, p);
                    }
                }
                return null;
            }

            @Override
            public Void visitVariable(VariableTree variableTree, Void p) {
                consider(variableTree, getCurrentPath());
                return null;
            }

            private void consider(VariableTree variableTree, TreePath path) {
                if (!variableTree.getName().contentEquals(targetName)) {
                    return;
                }
                var start = startOf(variableTree);
                if (start < 0 || start >= cursor) {
                    return;
                }
                var candidate = new CandidatePath(path, depth(path), start);
                if (isBetter(candidate, best)) {
                    best.path = candidate.path;
                    best.depth = candidate.depth;
                    best.start = candidate.start;
                }
            }
        }.scan(root, null);
        return Optional.ofNullable(best.path);
    }

    public Optional<WorkspaceTypeIndex.Member> resolveInheritedFieldMember(String identifier) {
        var owner = resolveThisType();
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        var member = resolveIndexedMember(owner.get().qualifiedType(), identifier, false, null);
        if (member.isEmpty() || member.get().kind != CompletionItemKind.Field) {
            return Optional.empty();
        }
        return member;
    }

    public Optional<TypeResolution> resolveTypeTree(Tree tree, boolean staticContext) {
        return resolveTypeTree(tree, root, staticContext);
    }

    private boolean isBetter(CandidatePath next, CandidatePath existing) {
        return existing.path == null
                || next.depth > existing.depth
                || (next.depth == existing.depth && next.start > existing.start);
    }
}
