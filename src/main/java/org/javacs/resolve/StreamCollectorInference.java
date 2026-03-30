package org.javacs.resolve;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BlockTree;
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
import com.sun.source.tree.TypeCastTree;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.javacs.completion.TypeIndexRouter;
import org.javacs.resolve.ParseTypeResolver.TypeResolution;

final class StreamCollectorInference {
    interface Support {
        Optional<TypeResolution> resolveExpression(Tree expression, int depth);

        Optional<TypeResolution> resolveTypeTree(Tree tree, CompilationUnitTree sourceRoot, boolean staticContext);

        Optional<TypeResolution> resolveMember(TypeResolution receiverType, String memberName);

        Optional<TypeResolution> resolveInvocation(TypeResolution receiverType, String methodName, int argumentCount);
    }

    record Result(boolean handled, Optional<TypeResolution> resolution) {
        static Result notApplicable() {
            return new Result(false, Optional.empty());
        }

        static Result resolved(TypeResolution resolution) {
            return new Result(true, Optional.ofNullable(resolution));
        }

        static Result bailOut() {
            return new Result(true, Optional.empty());
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
    private static final String COLLECTION = "java.util.Collection";
    private static final String ARRAYS = "java.util.Arrays";
    private static final String STREAM = "java.util.stream.Stream";
    private static final String INT_STREAM = "java.util.stream.IntStream";
    private static final String LONG_STREAM = "java.util.stream.LongStream";
    private static final String DOUBLE_STREAM = "java.util.stream.DoubleStream";
    private static final String COLLECTORS = "java.util.stream.Collectors";
    private static final String LIST = "java.util.List";
    private static final String SET = "java.util.Set";
    private static final String MAP = "java.util.Map";
    private static final String LONG_BOXED = "java.lang.Long";

    private final CompilationUnitTree root;
    private final TypeIndexRouter index;
    private final Support support;

    StreamCollectorInference(CompilationUnitTree root, TypeIndexRouter index, Support support) {
        this.root = root;
        this.index = index == null ? TypeIndexRouter.EMPTY : index;
        this.support = support;
    }

    Result infer(MethodInvocationTree invocation, MemberSelectTree methodSelect, TypeResolution receiverType, int depth) {
        if (invocation == null || methodSelect == null || receiverType == null) {
            return Result.notApplicable();
        }
        var methodName = methodSelect.getIdentifier().toString();
        if (methodName.isBlank()) {
            return Result.notApplicable();
        }

        if (receiverType.staticContext()) {
            if (ARRAYS.equals(receiverType.qualifiedType()) && "stream".equals(methodName)) {
                return resolve("Arrays.stream", resolveArraysStream(invocation, depth));
            }
            if (STREAM.equals(receiverType.qualifiedType())) {
                return switch (methodName) {
                    case "of" -> resolve("Stream.of", resolveStreamOf(invocation, depth));
                    case "empty" -> resolve("Stream.empty", Optional.of(streamType(null)));
                    default -> resolve("Stream." + methodName, Optional.empty());
                };
            }
            return Result.notApplicable();
        }

        if ("parallelStream".equals(methodName) && !receiverType.staticContext()) {
            return resolve("Collection.parallelStream", Optional.of(streamType(receiverType.firstTypeArgument())));
        }

        if ("stream".equals(methodName)
                && !receiverType.staticContext()
                && isAssignableTo(receiverType.qualifiedType(), COLLECTION)) {
            return resolve("Collection." + methodName, Optional.of(streamType(receiverType.firstTypeArgument())));
        }

        if (!STREAM.equals(receiverType.qualifiedType())) {
            return Result.notApplicable();
        }

        return switch (methodName) {
            case "filter" -> resolve("Stream.filter", resolveFilter(receiverType, invocation));
            case "map" -> resolve("Stream.map", resolveMap(receiverType, invocation, depth));
            case "flatMap" -> resolve("Stream.flatMap", resolveFlatMap(receiverType, invocation, depth));
            case "collect" -> resolve("Stream.collect", resolveCollect(receiverType, invocation, depth));
            default -> Result.notApplicable();
        };
    }

    private Result resolve(String label, Optional<TypeResolution> resolution) {
        if (resolution.isPresent()) {
            var type = resolution.get();
            LOG.fine(
                    String.format(
                            "[stream-infer] op=%s outcome=resolved type=%s first=%s",
                            label,
                            type.qualifiedType(),
                            type.firstTypeArgument()));
            return Result.resolved(type);
        }
        LOG.fine(String.format("[stream-infer] op=%s outcome=bail", label));
        return Result.bailOut();
    }

    private Optional<TypeResolution> resolveArraysStream(MethodInvocationTree invocation, int depth) {
        if (invocation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        var first = support.resolveExpression(invocation.getArguments().getFirst(), depth + 1);
        if (first.isEmpty() || !first.get().arrayType()) {
            return Optional.empty();
        }
        var componentType = first.get().qualifiedType();
        if (componentType == null || componentType.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(streamTypeForArrayComponent(componentType));
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
            if (!sameElementType(candidate, resolved.get())) {
                return Optional.empty();
            }
        }
        return candidate == null ? Optional.empty() : Optional.of(streamType(typeName(candidate)));
    }

    private Optional<TypeResolution> resolveFilter(TypeResolution receiverType, MethodInvocationTree invocation) {
        if (invocation.getArguments().size() != 1) {
            return Optional.empty();
        }
        return Optional.of(streamType(receiverType.firstTypeArgument()));
    }

    private Optional<TypeResolution> resolveMap(TypeResolution receiverType, MethodInvocationTree invocation, int depth) {
        if (invocation.getArguments().size() != 1 || receiverType.firstTypeArgument() == null) {
            return Optional.empty();
        }
        var mapped = resolveMapperResult(invocation.getArguments().getFirst(), elementType(receiverType), depth + 1);
        return mapped.map(type -> streamType(typeName(type)));
    }

    private Optional<TypeResolution> resolveFlatMap(TypeResolution receiverType, MethodInvocationTree invocation, int depth) {
        if (invocation.getArguments().size() != 1 || receiverType.firstTypeArgument() == null) {
            return Optional.empty();
        }
        var mapped = resolveMapperResult(invocation.getArguments().getFirst(), elementType(receiverType), depth + 1);
        if (mapped.isEmpty()
                || !STREAM.equals(mapped.get().qualifiedType())
                || mapped.get().firstTypeArgument() == null
                || mapped.get().firstTypeArgument().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(streamType(mapped.get().firstTypeArgument()));
    }

    private Optional<TypeResolution> resolveCollect(TypeResolution receiverType, MethodInvocationTree invocation, int depth) {
        if (invocation.getArguments().size() != 1) {
            return Optional.empty();
        }
        var collector = invocation.getArguments().getFirst();
        if (!(collector instanceof MethodInvocationTree collectorInvocation)) {
            return Optional.empty();
        }
        var select = collectorInvocation.getMethodSelect();
        if (!(select instanceof MemberSelectTree collectorSelect)) {
            return Optional.empty();
        }
        var qualifier = support.resolveExpression(collectorSelect.getExpression(), depth + 1);
        if (qualifier.isEmpty()
                || !qualifier.get().staticContext()
                || !COLLECTORS.equals(qualifier.get().qualifiedType())) {
            return Optional.empty();
        }
        return switch (collectorSelect.getIdentifier().toString()) {
            case "toList" -> resolveToList(receiverType, collectorInvocation);
            case "toSet" -> resolveToSet(receiverType, collectorInvocation);
            case "toCollection" -> resolveToCollection(receiverType, collectorInvocation, depth + 1);
            case "counting" -> resolveCounting(collectorInvocation);
            case "groupingBy" -> resolveGroupingBy(receiverType, collectorInvocation, depth + 1);
            default -> Optional.empty();
        };
    }

    private Optional<TypeResolution> resolveToList(TypeResolution receiverType, MethodInvocationTree collectorInvocation) {
        if (!collectorInvocation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TypeResolution(LIST, false, false, receiverType.firstTypeArgument()));
    }

    private Optional<TypeResolution> resolveToSet(TypeResolution receiverType, MethodInvocationTree collectorInvocation) {
        if (!collectorInvocation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TypeResolution(SET, false, false, receiverType.firstTypeArgument()));
    }

    private Optional<TypeResolution> resolveToCollection(
            TypeResolution receiverType, MethodInvocationTree collectorInvocation, int depth) {
        if (collectorInvocation.getArguments().size() != 1) {
            return Optional.empty();
        }
        var supplier = collectorInvocation.getArguments().getFirst();
        if (!(supplier instanceof MemberReferenceTree constructorReference)
                || constructorReference.getMode() != MemberReferenceTree.ReferenceMode.NEW) {
            return Optional.empty();
        }
        var collectionType = support.resolveTypeTree(constructorReference.getQualifierExpression(), root, false);
        return collectionType.map(type -> type.withFirstTypeArgument(receiverType.firstTypeArgument()));
    }

    private Optional<TypeResolution> resolveCounting(MethodInvocationTree collectorInvocation) {
        if (!collectorInvocation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TypeResolution(LONG_BOXED, false, false));
    }

    private Optional<TypeResolution> resolveGroupingBy(
            TypeResolution receiverType, MethodInvocationTree collectorInvocation, int depth) {
        if (collectorInvocation.getArguments().isEmpty() || collectorInvocation.getArguments().size() > 2) {
            return Optional.empty();
        }
        if (receiverType.firstTypeArgument() == null || receiverType.firstTypeArgument().isBlank()) {
            return Optional.empty();
        }
        var classifier =
                resolveClassifierResult(collectorInvocation.getArguments().getFirst(), elementType(receiverType), depth + 1);
        if (classifier.isEmpty()) {
            return Optional.empty();
        }
        if (collectorInvocation.getArguments().size() == 1) {
            return Optional.of(new TypeResolution(MAP, false, false, typeName(classifier.get())));
        }
        var downstream = collectorInvocation.getArguments().get(1);
        if (!(downstream instanceof MethodInvocationTree downstreamInvocation)) {
            return Optional.empty();
        }
        var downstreamSelect = downstreamInvocation.getMethodSelect();
        if (!(downstreamSelect instanceof MemberSelectTree downstreamCollector)) {
            return Optional.empty();
        }
        var qualifier = support.resolveExpression(downstreamCollector.getExpression(), depth + 1);
        if (qualifier.isEmpty()
                || !qualifier.get().staticContext()
                || !COLLECTORS.equals(qualifier.get().qualifiedType())
                || !"counting".contentEquals(downstreamCollector.getIdentifier())
                || !downstreamInvocation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TypeResolution(MAP, false, false, typeName(classifier.get())));
    }

    private Optional<TypeResolution> resolveClassifierResult(Tree classifier, TypeResolution elementType, int depth) {
        if (classifier instanceof MemberReferenceTree reference) {
            return resolveMemberReferenceResult(reference, elementType, depth + 1);
        }
        if (classifier instanceof LambdaExpressionTree lambda) {
            return resolveSimpleLambdaResult(lambda, elementType, depth + 1);
        }
        return Optional.empty();
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
        if (reference == null
                || elementType == null
                || elementType.qualifiedType() == null
                || elementType.qualifiedType().isBlank()
                || reference.getMode() != MemberReferenceTree.ReferenceMode.INVOKE) {
            return Optional.empty();
        }
        var qualifier = support.resolveExpression(reference.getQualifierExpression(), depth + 1);
        if (qualifier.isEmpty()) {
            return Optional.empty();
        }
        TypeResolution receiver = qualifier.get();
        if (receiver.staticContext()) {
            if (!isAssignableTo(elementType.qualifiedType(), receiver.qualifiedType())) {
                return Optional.empty();
            }
            receiver = elementType;
            return support.resolveInvocation(receiver, reference.getName().toString(), 0);
        }
        return support.resolveInvocation(receiver, reference.getName().toString(), 1);
    }

    private Optional<TypeResolution> resolveSimpleLambdaResult(
            LambdaExpressionTree lambda, TypeResolution elementType, int depth) {
        if (lambda.getParameters().size() != 1 || elementType == null) {
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
        return resolveExpressionWithParameter(body.get(), parameterName, elementType, depth + 1);
    }

    private Optional<Tree> lambdaBodyExpression(Tree body) {
        if (body instanceof ExpressionTree expressionBody) {
            return Optional.of(expressionBody);
        }
        if (body instanceof BlockTree block
                && block.getStatements().size() == 1
                && block.getStatements().getFirst() instanceof ReturnTree returnTree
                && returnTree.getExpression() != null) {
            return Optional.of(returnTree.getExpression());
        }
        return Optional.empty();
    }

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
            var receiver =
                    resolveExpressionWithParameter(select.getExpression(), parameterName, parameterType, depth + 1);
            if (receiver.isEmpty()) {
                return referencesIdentifier(select, parameterName)
                        ? Optional.empty()
                        : support.resolveExpression(expression, depth + 1);
            }
            return support.resolveMember(receiver.get(), select.getIdentifier().toString());
        }
        if (expression instanceof MethodInvocationTree invocation) {
            var methodSelect = invocation.getMethodSelect();
            if (!(methodSelect instanceof MemberSelectTree select)) {
                return referencesIdentifier(invocation, parameterName)
                        ? Optional.empty()
                        : support.resolveExpression(expression, depth + 1);
            }
            if (referencesIdentifierInArguments(invocation, parameterName)) {
                return Optional.empty();
            }
            var receiver =
                    resolveExpressionWithParameter(select.getExpression(), parameterName, parameterType, depth + 1);
            if (receiver.isEmpty()) {
                return referencesIdentifier(select.getExpression(), parameterName)
                        ? Optional.empty()
                        : support.resolveExpression(expression, depth + 1);
            }
            var inferred = infer(invocation, select, receiver.get(), depth + 1);
            if (inferred.handled()) {
                return inferred.resolution();
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
        if (expression instanceof IdentifierTree
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

    private TypeResolution streamType(String firstTypeArgument) {
        return new TypeResolution(STREAM, false, false, firstTypeArgument);
    }

    private TypeResolution streamTypeForArrayComponent(String componentType) {
        return switch (componentType) {
            case "int" -> new TypeResolution(INT_STREAM, false, false);
            case "long" -> new TypeResolution(LONG_STREAM, false, false);
            case "double" -> new TypeResolution(DOUBLE_STREAM, false, false);
            default -> streamType(componentType);
        };
    }

    private TypeResolution elementType(TypeResolution streamType) {
        return new TypeResolution(streamType.firstTypeArgument(), false, false);
    }

    private boolean sameElementType(TypeResolution left, TypeResolution right) {
        return Objects.equals(typeName(left), typeName(right))
                && left.arrayType() == right.arrayType()
                && Objects.equals(left.firstTypeArgument(), right.firstTypeArgument());
    }

    private String typeName(TypeResolution resolution) {
        if (resolution == null || resolution.qualifiedType() == null || resolution.qualifiedType().isBlank()) {
            return null;
        }
        return resolution.arrayType() ? resolution.qualifiedType() + "[]" : resolution.qualifiedType();
    }

    private boolean isAssignableTo(String sourceType, String targetType) {
        if (sourceType == null || sourceType.isBlank() || targetType == null || targetType.isBlank()) {
            return false;
        }
        if (sourceType.equals(targetType)) {
            return true;
        }
        var sourceClass = loadRuntimeClass(sourceType);
        var targetClass = loadRuntimeClass(targetType);
        if (sourceClass.isPresent() && targetClass.isPresent() && targetClass.get().isAssignableFrom(sourceClass.get())) {
            return true;
        }
        var visited = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(sourceType);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (var superType : index.directSupertypes(current)) {
                if (superType == null || superType.isBlank()) {
                    continue;
                }
                if (targetType.equals(superType)) {
                    return true;
                }
                pending.addLast(superType);
            }
        }
        return false;
    }

    private Optional<Class<?>> loadRuntimeClass(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return Optional.empty();
        }
        var binaryName = qualifiedType;
        while (true) {
            try {
                return Optional.of(Class.forName(binaryName));
            } catch (ClassNotFoundException ignored) {
                var split = binaryName.lastIndexOf('.');
                if (split < 0) {
                    return Optional.empty();
                }
                binaryName = binaryName.substring(0, split) + "$" + binaryName.substring(split + 1);
            }
        }
    }
}
