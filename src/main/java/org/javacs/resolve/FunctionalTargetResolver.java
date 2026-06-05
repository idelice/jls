package org.javacs.resolve;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import org.javacs.index.TypeIndexRouter;
import org.javacs.resolve.ParseTypeResolver.MethodReferenceTarget;
import org.javacs.resolve.ParseTypeResolver.TypeResolution;

/**
 * Narrow parse-time helper for stream/lambda definition support.
 *
 * <p>This class does not try to model the full Stream API. It only answers the questions needed
 * to resolve definition targets inside common functional arguments using parse-local data plus the
 * published indexes.
 *
 * <p>Examples:
 *
 * <pre>{@code
 * items.stream().map(LineItem::getFamily)
 * items.stream().map(item -> item.getFamily())
 * items.stream().flatMap(LineItem::tagsStream)
 * }</pre>
 *
 * <p>If a target is ambiguous or would require semantic attribution, this helper returns empty so
 * callers can fail closed instead of speculating.
 */
final class FunctionalTargetResolver {
    interface Support {
        Optional<TypeResolution> resolveExpression(Tree expression, int depth);

        Optional<TypeResolution> resolveTypeTree(Tree tree, CompilationUnitTree sourceRoot, boolean staticContext);

        Optional<TypeResolution> resolveMember(TypeResolution receiverType, String memberName);

        Optional<TypeResolution> resolveInvocation(TypeResolution receiverType, String methodName, int argumentCount);

        Optional<TypeResolution> resolveSamParameterType(TypeResolution functionalType);

        Optional<Integer> resolveSamArity(TypeResolution functionalType);
    }

    private record FunctionalContext(TypeResolution parameterType, int samArity) {}

    private static final Logger LOG = Logger.getLogger("main");
    private static final String ARRAYS = "java.util.Arrays";
    private static final String COLLECTION = "java.util.Collection";
    private static final String COLLECTORS = "java.util.stream.Collectors";
    private static final String DOUBLE_STREAM = "java.util.stream.DoubleStream";
    private static final String INT_STREAM = "java.util.stream.IntStream";
    private static final String LIST = "java.util.List";
    private static final String LONG_STREAM = "java.util.stream.LongStream";
    private static final String MAP = "java.util.Map";
    private static final String LONG_WRAPPER = "java.lang.Long";
    private static final String SET = "java.util.Set";
    private static final String STREAM = "java.util.stream.Stream";

    private final CompilationUnitTree root;
    private final TypeIndexRouter index;
    private final Support support;

    FunctionalTargetResolver(CompilationUnitTree root, TypeIndexRouter index, Support support) {
        this.root = root;
        this.index = index == null ? TypeIndexRouter.EMPTY : index;
        this.support = support;
    }

    Optional<TypeResolution> resolveInvocationReturnType(
            MethodInvocationTree invocation, MemberSelectTree methodSelect, TypeResolution receiverType, int depth) {
        if (invocation == null || methodSelect == null || receiverType == null) {
            return Optional.empty();
        }
        var methodName = methodSelect.getIdentifier().toString();
        if (methodName.isBlank()) {
            return Optional.empty();
        }

        if (receiverType.staticContext()) {
            if (ARRAYS.equals(receiverType.qualifiedType()) && "stream".equals(methodName)) {
                var resolved = resolveArraysStream(invocation, depth + 1);
                logInvocation(methodName, resolved, "arrays");
                return resolved;
            }
            if (STREAM.equals(receiverType.qualifiedType()) && "of".equals(methodName)) {
                var resolved = resolveStreamOf(invocation, depth + 1);
                logInvocation(methodName, resolved, "stream_of");
                return resolved;
            }
            return Optional.empty();
        }

        if ("stream".equals(methodName) || "parallelStream".equals(methodName)) {
            var resolved = resolveCollectionStream(receiverType);
            logInvocation(methodName, resolved, "collection");
            return resolved;
        }
        if (!isStreamType(receiverType.qualifiedType())) {
            return Optional.empty();
        }

        Optional<TypeResolution> resolved =
                switch (methodName) {
                    case "filter" -> Optional.of(streamType(receiverType.firstTypeArgument()));
                    case "map" -> resolveMap(invocation.getArguments().isEmpty() ? null : invocation.getArguments().getFirst(), receiverType, depth + 1);
                    case "flatMap" -> resolveFlatMap(invocation.getArguments().isEmpty() ? null : invocation.getArguments().getFirst(), receiverType, depth + 1);
                    case "collect" -> resolveCollect(invocation, receiverType, depth + 1);
                    default -> Optional.empty();
                };
        logInvocation(methodName, resolved, "stream_chain");
        return resolved;
    }

    Optional<TypeResolution> resolveLambdaParameterType(
            LambdaExpressionTree lambda, TreePath lambdaPath, MethodInvocationTree invocation, int argumentIndex, int depth) {
        if (lambda == null || lambdaPath == null || invocation == null || argumentIndex < 0) {
            return Optional.empty();
        }
        var context = resolveFunctionalContext(lambdaPath, invocation, argumentIndex, depth + 1);
        if (context.isEmpty()) {
            return Optional.empty();
        }
        LOG.fine(
                String.format(
                        "[functional-target] lambda_param owner=%s arg=%d type=%s",
                        methodName(invocation),
                        argumentIndex,
                        typeName(context.get().parameterType())));
        return Optional.of(context.get().parameterType());
    }

    /**
     * Resolve the effective receiver and arity behind a method reference argument.
     *
     * <p>For {@code items.stream().map(LineItem::getFamily)}, the qualifier is a type name, but the
     * real receiver at invocation time is the stream element type {@code LineItem} and the method
     * arity is {@code 0}. For {@code formatter::label}, the receiver stays bound to the formatter
     * instance and the arity is whatever the SAM still requires.
     */
    Optional<MethodReferenceTarget> resolveMethodReferenceTarget(
            MemberReferenceTree reference, TreePath referencePath, int depth) {
        if (reference == null || referencePath == null || reference.getMode() != MemberReferenceTree.ReferenceMode.INVOKE) {
            return Optional.empty();
        }
        Optional<FunctionalContext> functional = Optional.empty();
        var parent = referencePath.getParentPath();
        if (parent != null) {
            if (parent.getLeaf() instanceof VariableTree variable
                    && variable.getInitializer() == reference
                    && variable.getType() != null) {
                var functionalType = support.resolveTypeTree(variable.getType(), root, false);
                if (functionalType.isPresent()) {
                    functional =
                            support.resolveSamArity(functionalType.get())
                                    .map(arity -> new FunctionalContext(functionalType.get(), arity));
                }
            } else if (parent.getLeaf() instanceof AssignmentTree assignment
                    && assignment.getExpression() == reference) {
                var functionalType = support.resolveExpression(assignment.getVariable(), depth + 1);
                if (functionalType.isPresent()) {
                    functional =
                            support.resolveSamArity(functionalType.get())
                                    .map(arity -> new FunctionalContext(functionalType.get(), arity));
                }
            }
        }
        if (functional.isEmpty()) {
            var invocationContext = enclosingInvocation(referencePath, reference);
            if (invocationContext.isEmpty()) {
                return Optional.empty();
            }
            var invocationPath = invocationContext.get();
            var invocation = (MethodInvocationTree) invocationPath.getLeaf();
            var argumentIndex = invocation.getArguments().indexOf(reference);
            if (argumentIndex < 0) {
                return Optional.empty();
            }
            functional = resolveFunctionalContext(invocationPath, invocation, argumentIndex, depth + 1);
            if (functional.isEmpty()) {
                return Optional.empty();
            }
        }
        var qualifierType = support.resolveTypeTree(reference.getQualifierExpression(), root, true);
        if (qualifierType.isPresent()) {
            if (functional.get().parameterType() != null
                    && Objects.equals(
                            functional.get().parameterType().qualifiedType(),
                            qualifierType.get().qualifiedType())) {
                var target =
                        new MethodReferenceTarget(
                                functional.get().parameterType(),
                                Math.max(0, functional.get().samArity() - 1));
                LOG.fine(
                        String.format(
                                "[functional-target] method_ref owner=%s mode=unbound_instance arity=%d",
                                target.receiverType().qualifiedType(),
                                target.argumentCount()));
                return Optional.of(target);
            }
            var target = new MethodReferenceTarget(qualifierType.get(), functional.get().samArity());
            LOG.fine(
                    String.format(
                            "[functional-target] method_ref owner=%s mode=static arity=%d",
                            target.receiverType().qualifiedType(),
                            target.argumentCount()));
            return Optional.of(target);
        }
        var qualifier = support.resolveExpression(reference.getQualifierExpression(), depth + 1);
        if (qualifier.isEmpty()) {
            return Optional.empty();
        }
        var owner = qualifier.get();
        var target = new MethodReferenceTarget(owner, functional.get().samArity());
        LOG.fine(
                String.format(
                        "[functional-target] method_ref owner=%s mode=%s arity=%d",
                        target.receiverType().qualifiedType(),
                        owner.staticContext() ? "static" : "bound_instance",
                        target.argumentCount()));
        return Optional.of(target);
    }

    /**
     * Identify the functional argument slot that gives meaning to the current lambda or method
     * reference.
     *
     * <p>Example: in {@code stream.map(item -> item.getFamily())}, argument {@code 0} is the mapper
     * and its parameter type is the stream element type. In
     * {@code collect(groupingBy(Item::getFamily))}, the classifier context comes from the enclosing
     * stream receiver rather than the collector call itself.
     */
    private Optional<FunctionalContext> resolveFunctionalContext(
            TreePath invocationPath, MethodInvocationTree invocation, int argumentIndex, int depth) {
        if (invocation == null || argumentIndex < 0) {
            return Optional.empty();
        }
        var select = invocation.getMethodSelect();
        if (!(select instanceof MemberSelectTree memberSelectTree)) {
            return Optional.empty();
        }
        var receiver = support.resolveExpression(memberSelectTree.getExpression(), depth + 1);
        if (receiver.isPresent() && !receiver.get().staticContext() && isStreamType(receiver.get().qualifiedType()) && argumentIndex == 0) {
            var elementType = streamElementType(receiver.get());
            if (elementType.isEmpty()) {
                LOG.fine(
                        String.format(
                                "[functional-target] context owner=%s outcome=%s reason=%s",
                                methodName(invocation),
                                "empty",
                                "missing_element_type"));

                return Optional.empty();
            }
            return switch (memberSelectTree.getIdentifier().toString()) {
                case "map", "flatMap", "filter", "anyMatch", "allMatch", "noneMatch" -> Optional.of(new FunctionalContext(elementType.get(), 1));
                default -> Optional.empty();
            };
        }
        if (receiver.isPresent()
                && receiver.get().staticContext()
                && COLLECTORS.equals(receiver.get().qualifiedType())
                && "groupingBy".contentEquals(memberSelectTree.getIdentifier())
                && argumentIndex == 0) {
            return resolveGroupingByClassifierContext(invocationPath, depth + 1);
        }
        return Optional.empty();
    }

    private Optional<FunctionalContext> resolveGroupingByClassifierContext(TreePath invocationPath, int depth) {
        for (var parent = invocationPath.getParentPath(); parent != null; parent = parent.getParentPath()) {
            if (!(parent.getLeaf() instanceof MethodInvocationTree collectInvocation)) {
                continue;
            }
            var select = collectInvocation.getMethodSelect();
            if (!(select instanceof MemberSelectTree collectSelect) || !"collect".contentEquals(collectSelect.getIdentifier())) {
                continue;
            }
            var receiver = support.resolveExpression(collectSelect.getExpression(), depth + 1);
            if (receiver.isEmpty() || receiver.get().staticContext() || !isStreamType(receiver.get().qualifiedType())) {
                return Optional.empty();
            }
            return streamElementType(receiver.get()).map(type -> new FunctionalContext(type, 1));
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveCollectionStream(TypeResolution receiverType) {
        if (receiverType == null
                || receiverType.staticContext()
                || receiverType.firstTypeArgument() == null
                || receiverType.firstTypeArgument().isBlank()
                || !isAssignableTo(receiverType.qualifiedType(), COLLECTION)) {
            return Optional.empty();
        }
        return Optional.of(streamType(receiverType.firstTypeArgument()));
    }

    private Optional<TypeResolution> resolveArraysStream(MethodInvocationTree invocation, int depth) {
        if (invocation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        var arg = invocation.getArguments().getFirst();
        var first = support.resolveExpression(arg, depth + 1);
        if (first.isEmpty() || !first.get().arrayType()) {
            // Fallback: values() inside an enum returns an array of the enclosing enum type
            var enumType = resolveEnumValuesCall(arg);
            if (enumType.isPresent()) {
                return Optional.of(streamType(enumType.get()));
            }
            return Optional.empty();
        }
        return Optional.of(streamTypeForArrayComponent(first.get().qualifiedType()));
    }

    /**
     * Detect {@code values()} call inside an enum and return the enum's qualified name.
     * {@code values()} is a synthetic method not present in indexes.
     */
    private Optional<String> resolveEnumValuesCall(Tree arg) {
        if (!(arg instanceof MethodInvocationTree call)) return Optional.empty();
        if (!(call.getMethodSelect() instanceof IdentifierTree id)) return Optional.empty();
        if (!"values".contentEquals(id.getName()) || !call.getArguments().isEmpty()) return Optional.empty();
        // Find the first enum in this compilation unit
        var pkg = root.getPackageName();
        var prefix = pkg != null ? pkg.toString() + "." : "";
        for (var decl : root.getTypeDecls()) {
            if (decl instanceof ClassTree classTree && classTree.getKind() == Tree.Kind.ENUM) {
                return Optional.of(prefix + classTree.getSimpleName().toString());
            }
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveStreamOf(MethodInvocationTree invocation, int depth) {
        if (invocation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        TypeResolution candidate = null;
        for (var argument : invocation.getArguments()) {
            var resolved = resolveTrivialExpression(argument, depth + 1);
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            if (candidate == null) {
                candidate = resolved.get();
                continue;
            }
            if (!sameType(candidate, resolved.get())) {
                return Optional.empty();
            }
        }
        return candidate == null ? Optional.empty() : Optional.of(streamType(typeName(candidate)));
    }

    private Optional<TypeResolution> resolveMap(Tree mapper, TypeResolution streamType, int depth) {
        var elementType = streamElementType(streamType);
        if (elementType.isEmpty()) {
            return Optional.empty();
        }
        var result = resolveMapperResult(mapper, elementType.get(), depth + 1);
        return result.map(type -> streamType(typeName(type)));
    }

    private Optional<TypeResolution> resolveFlatMap(Tree mapper, TypeResolution streamType, int depth) {
        var elementType = streamElementType(streamType);
        if (elementType.isEmpty()) {
            return Optional.empty();
        }
        var result = resolveMapperResult(mapper, elementType.get(), depth + 1);
        if (result.isEmpty() || !isStreamType(result.get().qualifiedType()) || result.get().firstTypeArgument() == null) {
            return Optional.empty();
        }
        return Optional.of(streamType(result.get().firstTypeArgument()));
    }

    private Optional<TypeResolution> resolveCollect(
            MethodInvocationTree invocation, TypeResolution streamType, int depth) {
        if (invocation.getArguments().size() != 1) {
            return Optional.empty();
        }
        var collector = invocation.getArguments().getFirst();
        if (!(collector instanceof MethodInvocationTree collectorInvocation)
                || !(collectorInvocation.getMethodSelect() instanceof MemberSelectTree collectorSelect)) {
            return Optional.empty();
        }
        var owner = support.resolveExpression(collectorSelect.getExpression(), depth + 1);
        if (owner.isEmpty()
                || !owner.get().staticContext()
                || !COLLECTORS.equals(owner.get().qualifiedType())) {
            return Optional.empty();
        }
        return switch (collectorSelect.getIdentifier().toString()) {
            case "toList" -> resolveCollectedSequenceType(streamType, LIST);
            case "toSet" -> resolveCollectedSequenceType(streamType, SET);
            case "toCollection" -> resolveCollectedToCollection(collectorInvocation, streamType, depth + 1);
            case "counting" -> Optional.of(new TypeResolution(LONG_WRAPPER, false, false));
            case "groupingBy" -> resolveGroupingBy(collectorInvocation, streamType, depth + 1);
            default -> Optional.empty();
        };
    }

    private Optional<TypeResolution> resolveCollectedSequenceType(
            TypeResolution streamType, String collectionType) {
        return Optional.of(
                new TypeResolution(collectionType, false, false)
                        .withFirstTypeArgument(streamElementType(streamType).map(this::typeName).orElse(null)));
    }

    private Optional<TypeResolution> resolveCollectedToCollection(
            MethodInvocationTree collectorInvocation, TypeResolution streamType, int depth) {
        if (collectorInvocation.getArguments().size() != 1) {
            return Optional.empty();
        }
        var supplier = collectorInvocation.getArguments().getFirst();
        if (!(supplier instanceof MemberReferenceTree reference)
                || reference.getMode() != MemberReferenceTree.ReferenceMode.NEW) {
            return Optional.empty();
        }
        return support.resolveTypeTree(reference.getQualifierExpression(), root, true)
                .map(
                        type ->
                                new TypeResolution(type.qualifiedType(), false, false)
                                        .withFirstTypeArgument(streamElementType(streamType).map(this::typeName).orElse(null)));
    }

    private Optional<TypeResolution> resolveGroupingBy(
            MethodInvocationTree collectorInvocation, TypeResolution streamType, int depth) {
        if (collectorInvocation.getArguments().isEmpty() || collectorInvocation.getArguments().size() > 2) {
            return Optional.empty();
        }
        var elementType = streamElementType(streamType);
        if (elementType.isEmpty()) {
            return Optional.empty();
        }
        var classifier = collectorInvocation.getArguments().getFirst();
        var keyType = resolveMapperResult(classifier, elementType.get(), depth + 1);
        if (keyType.isEmpty()
                && !(classifier instanceof MemberReferenceTree)
                && !(classifier instanceof LambdaExpressionTree lambda
                        && lambdaBodyExpression(lambda.getBody()).isPresent())) {
            return Optional.empty();
        }
        if (collectorInvocation.getArguments().size() == 2) {
            var downstream = collectorInvocation.getArguments().get(1);
            if (!(downstream instanceof MethodInvocationTree downstreamInvocation)
                    || !(downstreamInvocation.getMethodSelect() instanceof MemberSelectTree downstreamSelect)) {
                return Optional.empty();
            }
            var downstreamOwner = support.resolveExpression(downstreamSelect.getExpression(), depth + 1);
            if (downstreamOwner.isEmpty()
                    || !downstreamOwner.get().staticContext()
                    || !COLLECTORS.equals(downstreamOwner.get().qualifiedType())
                    || !"counting".contentEquals(downstreamSelect.getIdentifier())) {
                return Optional.empty();
            }
        }
        return Optional.of(
                new TypeResolution(MAP, false, false)
                        .withFirstTypeArgument(keyType.map(this::typeName).orElse(null)));
    }

    private Optional<TypeResolution> resolveMapperResult(Tree mapper, TypeResolution elementType, int depth) {
        if (mapper instanceof MemberReferenceTree reference) {
            return resolveMemberReferenceResult(reference, elementType, depth + 1);
        }
        if (mapper instanceof LambdaExpressionTree lambda) {
            return resolveSimpleLambdaResult(lambda, elementType, depth + 1);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMemberReferenceResult(
            MemberReferenceTree reference, TypeResolution elementType, int depth) {
        if (reference == null || elementType == null || reference.getMode() != MemberReferenceTree.ReferenceMode.INVOKE) {
            return Optional.empty();
        }

        var qualifierType = support.resolveTypeTree(reference.getQualifierExpression(), root, true);
        if (qualifierType.isPresent()
                && isAssignableTo(elementType.qualifiedType(), qualifierType.get().qualifiedType())) {
            return support.resolveInvocation(elementType, reference.getName().toString(), 0);
        }

        var qualifier = support.resolveExpression(reference.getQualifierExpression(), depth + 1);
        if (qualifier.isEmpty()) {
            return Optional.empty();
        }

        return support.resolveInvocation(qualifier.get(), reference.getName().toString(), 1);
    }

    private Optional<TypeResolution> resolveSimpleLambdaResult(
            LambdaExpressionTree lambda, TypeResolution parameterType, int depth) {
        if (lambda.getParameters().size() != 1 || parameterType == null) {
            return Optional.empty();
        }
        var parameter = lambda.getParameters().getFirst();
        var parameterName = parameter.getName() == null ? "" : parameter.getName().toString();
        if (parameterName.isBlank()) {
            return Optional.empty();
        }
        var body = lambdaBodyExpression(lambda.getBody());
        if (body.isEmpty()) {
            return Optional.empty();
        }
        return resolveExpressionWithParameter(body.get(), parameterName, parameterType, depth + 1);
    }

    private Optional<Tree> lambdaBodyExpression(Tree body) {
        if (body instanceof ExpressionTree expressionTree) {
            return Optional.of(expressionTree);
        }
        if (body instanceof BlockTree blockTree
                && blockTree.getStatements().size() == 1
                && blockTree.getStatements().getFirst() instanceof ReturnTree returnTree
                && returnTree.getExpression() != null) {
            return Optional.of(returnTree.getExpression());
        }
        return Optional.empty();
    }

    /**
     * Evaluate a small lambda body by treating the lambda parameter as a known typed receiver.
     *
     * <p>This intentionally handles only simple expression forms such as {@code item.getFamily()} or
     * {@code item.getTags().stream()}. Once the body depends on more control flow or reuses the
     * parameter in nested arguments, the method returns empty instead of approximating.
     */
    private Optional<TypeResolution> resolveExpressionWithParameter(
            Tree expression, String parameterName, TypeResolution parameterType, int depth) {
        if (expression == null) {
            return Optional.empty();
        }
        if (expression instanceof ParenthesizedTree parenthesized) {
            return resolveExpressionWithParameter(parenthesized.getExpression(), parameterName, parameterType, depth + 1);
        }
        if (expression instanceof IdentifierTree identifier) {
            if (identifier.getName().contentEquals(parameterName)) {
                return Optional.of(parameterType);
            }
            return support.resolveExpression(expression, depth + 1);
        }
        if (expression instanceof MemberSelectTree select) {
            var receiver = resolveExpressionWithParameter(select.getExpression(), parameterName, parameterType, depth + 1);
            if (receiver.isEmpty()) {
                return referencesIdentifier(select, parameterName) ? Optional.empty() : support.resolveExpression(expression, depth + 1);
            }
            return support.resolveMember(receiver.get(), select.getIdentifier().toString());
        }
        if (expression instanceof MethodInvocationTree invocation) {
            if (!(invocation.getMethodSelect() instanceof MemberSelectTree select)) {
                return referencesIdentifier(invocation, parameterName) ? Optional.empty() : support.resolveExpression(expression, depth + 1);
            }
            if (referencesIdentifierInArguments(invocation, parameterName)) {
                return Optional.empty();
            }
            var receiver = resolveExpressionWithParameter(select.getExpression(), parameterName, parameterType, depth + 1);
            if (receiver.isEmpty()) {
                return referencesIdentifier(select.getExpression(), parameterName)
                        ? Optional.empty()
                        : support.resolveExpression(expression, depth + 1);
            }
            var inferred = resolveInvocationReturnType(invocation, select, receiver.get(), depth + 1);
            if (inferred.isPresent()) {
                return inferred;
            }
            return support.resolveInvocation(receiver.get(), select.getIdentifier().toString(), invocation.getArguments().size());
        }
        if (expression instanceof LiteralTree
                || expression instanceof NewClassTree
                || expression instanceof NewArrayTree
                || expression instanceof TypeCastTree
                || expression instanceof ArrayAccessTree) {
            return support.resolveExpression(expression, depth + 1);
        }
        return referencesIdentifier(expression, parameterName) ? Optional.empty() : support.resolveExpression(expression, depth + 1);
    }

    private boolean referencesIdentifierInArguments(MethodInvocationTree invocation, String identifier) {
        for (var argument : invocation.getArguments()) {
            if (referencesIdentifier(argument, identifier)) {
                return true;
            }
        }
        return false;
    }

    private boolean referencesIdentifier(Tree tree, String identifier) {
        if (tree == null || identifier == null || identifier.isBlank()) {
            return false;
        }
        if (tree instanceof IdentifierTree found) {
            return found.getName().contentEquals(identifier);
        }
        if (tree instanceof ParenthesizedTree parenthesized) {
            return referencesIdentifier(parenthesized.getExpression(), identifier);
        }
        if (tree instanceof MemberSelectTree select) {
            return referencesIdentifier(select.getExpression(), identifier);
        }
        if (tree instanceof MethodInvocationTree invocation) {
            if (referencesIdentifier(invocation.getMethodSelect(), identifier)) {
                return true;
            }
            for (var argument : invocation.getArguments()) {
                if (referencesIdentifier(argument, identifier)) {
                    return true;
                }
            }
            return false;
        }
        if (tree instanceof LambdaExpressionTree lambda) {
            for (var parameter : lambda.getParameters()) {
                if (parameter.getName() != null && parameter.getName().contentEquals(identifier)) {
                    return false;
                }
            }
            return lambdaBodyExpression(lambda.getBody()).map(body -> referencesIdentifier(body, identifier)).orElse(false);
        }
        return false;
    }

    private Optional<TypeResolution> resolveTrivialExpression(Tree expression, int depth) {
        if (expression == null) {
            return Optional.empty();
        }
        if (expression instanceof ParenthesizedTree parenthesized) {
            return resolveTrivialExpression(parenthesized.getExpression(), depth + 1);
        }
        if (expression.getKind() == Kind.IDENTIFIER
                || expression instanceof LiteralTree
                || expression instanceof MemberSelectTree
                || expression instanceof NewClassTree
                || expression instanceof NewArrayTree
                || expression instanceof TypeCastTree
                || expression instanceof ArrayAccessTree) {
            return support.resolveExpression(expression, depth + 1);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> streamElementType(TypeResolution streamType) {
        if (streamType == null || streamType.qualifiedType() == null || streamType.qualifiedType().isBlank()) {
            return Optional.empty();
        }
        return switch (streamType.qualifiedType()) {
            case INT_STREAM -> Optional.of(new TypeResolution("int", false, false));
            case LONG_STREAM -> Optional.of(new TypeResolution("long", false, false));
            case DOUBLE_STREAM -> Optional.of(new TypeResolution("double", false, false));
            case STREAM -> streamType.firstTypeArgument() == null || streamType.firstTypeArgument().isBlank()
                    ? Optional.empty()
                    : Optional.of(new TypeResolution(streamType.firstTypeArgument(), false, false));
            default -> Optional.empty();
        };
    }

    private TypeResolution streamType(String firstTypeArgument) {
        return new TypeResolution(STREAM, false, false,
                firstTypeArgument == null ? List.of() : List.of(firstTypeArgument));
    }

    private TypeResolution streamTypeForArrayComponent(String componentType) {
        return switch (componentType) {
            case "int" -> new TypeResolution(INT_STREAM, false, false);
            case "long" -> new TypeResolution(LONG_STREAM, false, false);
            case "double" -> new TypeResolution(DOUBLE_STREAM, false, false);
            default -> streamType(componentType);
        };
    }

    private boolean sameType(TypeResolution left, TypeResolution right) {
        return Objects.equals(typeName(left), typeName(right))
                && left.arrayType() == right.arrayType()
                && Objects.equals(left.firstTypeArgument(), right.firstTypeArgument());
    }

    private boolean isStreamType(String qualifiedType) {
        return STREAM.equals(qualifiedType)
                || INT_STREAM.equals(qualifiedType)
                || LONG_STREAM.equals(qualifiedType)
                || DOUBLE_STREAM.equals(qualifiedType);
    }

    private boolean isAssignableTo(String sourceType, String targetType) {
        if (sourceType == null || sourceType.isBlank() || targetType == null || targetType.isBlank()) {
            return false;
        }
        if (sourceType.equals(targetType)) {
            return true;
        }
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(sourceType);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            for (var superType : index.directSupertypes(current)) {
                if (targetType.equals(superType)) {
                    return true;
                }
                if (superType != null && !superType.isBlank()) {
                    pending.addLast(superType);
                }
            }
        }
        return false;
    }

    private Optional<TreePath> enclosingInvocation(TreePath start, Tree argument) {
        for (var path = start; path != null; path = path.getParentPath()) {
            if (!(path.getLeaf() instanceof MethodInvocationTree invocation)) {
                continue;
            }
            if (invocation.getArguments().contains(argument)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    private void logInvocation(String methodName, Optional<TypeResolution> resolved, String mode) {
        if (resolved.isPresent()) {
            LOG.fine(
                    String.format(
                            "[functional-target] invocation owner=%s mode=%s outcome=resolved type=%s",
                            methodName,
                            mode,
                            typeName(resolved.get())));
            return;
        }
        LOG.fine(
                String.format(
                        "[functional-target] invocation owner=%s mode=%s outcome=empty",
                        methodName,
                        mode));
    }

    private String methodName(MethodInvocationTree invocation) {
        if (invocation == null) {
            return "";
        }
        var select = invocation.getMethodSelect();
        if (select instanceof MemberSelectTree memberSelectTree) {
            return memberSelectTree.getIdentifier().toString();
        }
        if (select instanceof IdentifierTree identifierTree) {
            return identifierTree.getName().toString();
        }
        return select.toString();
    }

    private String typeName(TypeResolution resolution) {
        if (resolution == null || resolution.qualifiedType() == null || resolution.qualifiedType().isBlank()) {
            return "";
        }
        return resolution.arrayType() ? resolution.qualifiedType() + "[]" : resolution.qualifiedType();
    }
}
