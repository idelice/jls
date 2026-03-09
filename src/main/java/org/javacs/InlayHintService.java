package org.javacs;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TreeVisitor;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.logging.Logger;
import javax.lang.model.element.Modifier;
import javax.tools.JavaFileObject;
import org.javacs.completion.TypeMemberIndex;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.InlayHintKind;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

class InlayHintService {
    private static final Map<String, String> BOXED_TYPES =
            Map.of(
                    "boolean", "java.lang.Boolean",
                    "byte", "java.lang.Byte",
                    "short", "java.lang.Short",
                    "int", "java.lang.Integer",
                    "long", "java.lang.Long",
                    "float", "java.lang.Float",
                    "double", "java.lang.Double",
                    "char", "java.lang.Character");

    private final JavaCompilerService compiler;
    private final TypeMemberIndex index;

    InlayHintService(JavaCompilerService compiler, TypeMemberIndex index) {
        this.compiler = compiler;
        this.index = index == null ? TypeMemberIndex.EMPTY : index;
    }

    List<InlayHint> inlayHints(Path file, Range range) {
        var parse = compiler.parse(file);
        return new Request(parse, range).run();
    }

    private final class Request extends TreePathScanner<Void, Void> {
        private final ParseTask parse;
        private final CompilationUnitTree root;
        private final SourcePositions positions;
        private final int rangeStartLine;
        private final int rangeEndLine;
        private final List<InlayHint> hints = new ArrayList<>();
        private int nodesProcessed;

        Request(ParseTask parse, Range range) {
            this.parse = parse;
            this.root = parse.root;
            this.positions = Trees.instance(parse.task).getSourcePositions();
            this.rangeStartLine = range == null || range.start == null ? 0 : Math.max(0, range.start.line);
            this.rangeEndLine =
                    range == null || range.end == null
                            ? Integer.MAX_VALUE
                            : Math.max(rangeStartLine, range.end.line);
        }

        List<InlayHint> run() {
            LOG.info(
                    String.format(
                            "inlay_hint_request range_start=%d range_end=%d",
                            rangeStartLine, rangeEndLine));
            scan(root, null);
            hints.sort(
                    java.util.Comparator.comparingInt((InlayHint hint) -> hint.position.line)
                            .thenComparingInt(hint -> hint.position.character));
            LOG.info("inlay_hint_nodes_processed=" + nodesProcessed);
            LOG.info("inlay_hint_result_count=" + hints.size());
            return hints;
        }

        @Override
        public Void scan(Tree tree, Void unused) {
            if (tree == null) {
                return null;
            }
            if (!intersectsRange(tree)) {
                return null;
            }
            nodesProcessed++;
            return super.scan(tree, unused);
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            addVarHint(tree);
            return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            addParameterHints(tree);
            return super.visitMethodInvocation(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            addConstructorHints(tree);
            return super.visitNewClass(tree, unused);
        }

        private void addVarHint(VariableTree tree) {
            if (isInsideEnumConstant(getCurrentPath())) {
                return;
            }
            if (tree.getType() != null && !"var".equals(tree.getType().toString())) {
                return;
            }
            var initializer = tree.getInitializer();
            if (initializer == null || isObviousVarInitializer(initializer)) {
                return;
            }
            var inferred = resolveExpression(new TreePath(getCurrentPath(), initializer));
            if (inferred.isEmpty()) {
                return;
            }
            var type = inferred.get();
            var hint = new InlayHint();
            hint.position = variableNameEnd(tree);
            hint.label = ": " + displayType(type);
            hint.kind = InlayHintKind.Type;
            hint.paddingLeft = true;
            hints.add(hint);
        }

        private void addParameterHints(MethodInvocationTree tree) {
            if (isInsideEnumConstant(getCurrentPath()) || isInsideAnnotation(getCurrentPath())) {
                return;
            }
            var resolved = resolveMethod(getCurrentPath(), tree);
            if (resolved.isEmpty()) {
                return;
            }
            var method = resolved.get();
            var arguments = tree.getArguments();
            var count = Math.min(arguments.size(), method.parameterNames.length);
            for (var i = 0; i < count; i++) {
                var parameterName = method.parameterNames[i];
                if (parameterName == null || parameterName.isBlank() || isSyntheticParameterName(parameterName)) {
                    continue;
                }
                var argument = arguments.get(i);
                if (shouldSuppressParameterHint(tree, argument, parameterName, method.methodName)) {
                    continue;
                }
                var start = startPosition(argument);
                if (start < 0) {
                    continue;
                }
                var hint = new InlayHint();
                hint.position = positionAt(start);
                hint.label = parameterName + ":";
                hint.kind = InlayHintKind.Parameter;
                hint.paddingRight = true;
                hints.add(hint);
            }
        }

        private void addConstructorHints(NewClassTree tree) {
            if (isInsideEnumConstant(getCurrentPath()) || isInsideAnnotation(getCurrentPath())) {
                return;
            }
            var resolved = resolveConstructor(getCurrentPath(), tree);
            if (resolved.isEmpty()) {
                return;
            }
            addArgumentHints(tree.getArguments(), resolved.get());
        }

        private void addArgumentHints(List<? extends ExpressionTree> arguments, ResolvedMethod method) {
            var count = Math.min(arguments.size(), method.parameterNames.length);
            for (var i = 0; i < count; i++) {
                var parameterName = method.parameterNames[i];
                if (parameterName == null || parameterName.isBlank() || isSyntheticParameterName(parameterName)) {
                    continue;
                }
                var argument = arguments.get(i);
                if (shouldSuppressParameterHint(null, argument, parameterName, method.methodName)) {
                    continue;
                }
                var start = startPosition(argument);
                if (start < 0) {
                    continue;
                }
                var hint = new InlayHint();
                hint.position = positionAt(start);
                hint.label = parameterName + ":";
                hint.kind = InlayHintKind.Parameter;
                hint.paddingRight = true;
                hints.add(hint);
            }
        }

        private Optional<ResolvedType> resolveExpression(TreePath expressionPath) {
            if (expressionPath == null) {
                return Optional.empty();
            }
            return resolveExpression(expressionPath, 0);
        }

        private Optional<ResolvedType> resolveExpression(TreePath expressionPath, int depth) {
            if (expressionPath == null || depth > 24) {
                return Optional.empty();
            }
            var expression = expressionPath.getLeaf();
            if (expression instanceof ParenthesizedTree parenthesized) {
                return resolveExpression(new TreePath(expressionPath, parenthesized.getExpression()), depth + 1);
            }
            if (expression instanceof LiteralTree literal) {
                return literalType(literal);
            }
            if (expression instanceof IdentifierTree identifier) {
                return resolveIdentifier(expressionPath, identifier.getName().toString(), depth + 1);
            }
            if (expression instanceof NewClassTree newClassTree) {
                return resolveTypeTree(expressionPath, newClassTree.getIdentifier(), false);
            }
            if (expression instanceof NewArrayTree newArrayTree) {
                if (newArrayTree.getType() == null) {
                    return Optional.empty();
                }
                var component = resolveTypeTree(expressionPath, newArrayTree.getType(), false);
                if (component.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(component.get().asArray());
            }
            if (expression instanceof MethodInvocationTree invocationTree) {
                var method = resolveMethod(expressionPath, invocationTree, depth + 1);
                if (method.isEmpty() || method.get().returnType == null || method.get().returnType.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(ResolvedType.fromTypeName(method.get().returnType, false));
            }
            if (expression instanceof MemberSelectTree memberSelectTree) {
                return resolveMemberSelect(expressionPath, memberSelectTree, depth + 1);
            }
            if (expression instanceof TypeCastTree castTree) {
                return resolveTypeTree(expressionPath, castTree.getType(), false);
            }
            if (expression instanceof ArrayAccessTree arrayAccessTree) {
                var array = resolveExpression(new TreePath(expressionPath, arrayAccessTree.getExpression()), depth + 1);
                if (array.isEmpty() || !array.get().arrayType) {
                    return Optional.empty();
                }
                return Optional.of(array.get().asElementType());
            }
            return Optional.empty();
        }

        private Optional<ResolvedType> resolveMemberSelect(
                TreePath expressionPath, MemberSelectTree memberSelectTree, int depth) {
            var receiver =
                    resolveExpression(new TreePath(expressionPath, memberSelectTree.getExpression()), depth + 1);
            if (receiver.isEmpty()) {
                return resolveTypeName(memberSelectTree.toString(), expressionPath)
                        .map(type -> new ResolvedType(type, true, false));
            }
            if (receiver.get().arrayType && "length".equals(memberSelectTree.getIdentifier().toString())) {
                return Optional.of(new ResolvedType("int", false, false));
            }
            var sourceField =
                    resolveSourceField(receiver.get().qualifiedName, memberSelectTree.getIdentifier().toString());
            if (sourceField.isPresent()) {
                return sourceField;
            }
            var member =
                    index.member(
                            receiver.get().qualifiedName,
                            memberSelectTree.getIdentifier().toString(),
                            receiver.get().staticContext);
            if (member.isEmpty() || member.get().returnType == null || member.get().returnType.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(ResolvedType.fromTypeName(member.get().returnType, false));
        }

        private Optional<ResolvedType> resolveIdentifier(TreePath usagePath, String identifier, int depth) {
            if ("this".equals(identifier)) {
                return currentEnclosingType(usagePath).map(type -> new ResolvedType(type, false, false));
            }
            if ("super".equals(identifier)) {
                return currentSuperType(usagePath).map(type -> new ResolvedType(type, false, false));
            }
            var visibleVariable = resolveVisibleVariable(usagePath, identifier, depth + 1);
            if (visibleVariable.isPresent()) {
                return visibleVariable;
            }
            var implicitLogger = resolveImplicitSlf4jLogger(usagePath, identifier);
            if (implicitLogger.isPresent()) {
                return implicitLogger;
            }
            return resolveTypeName(identifier, usagePath).map(type -> new ResolvedType(type, true, false));
        }

        private Optional<ResolvedType> resolveVisibleVariable(TreePath usagePath, String name, int depth) {
            var child = usagePath;
            for (var current = usagePath.getParentPath(); current != null; current = current.getParentPath()) {
                var leaf = current.getLeaf();
                if (leaf instanceof BlockTree blockTree) {
                    var resolved = findVisibleVariableInBlock(current, blockTree, child, name, depth + 1);
                    if (resolved.isPresent()) {
                        return resolved;
                    }
                } else if (leaf instanceof MethodTree methodTree) {
                    for (var parameter : methodTree.getParameters()) {
                        if (parameter.getName().contentEquals(name)) {
                            return resolveVariableType(new TreePath(current, parameter), parameter, depth + 1);
                        }
                    }
                } else if (leaf instanceof ForLoopTree forLoopTree) {
                    for (var initializer : forLoopTree.getInitializer()) {
                        if (initializer instanceof VariableTree variableTree
                                && variableTree.getName().contentEquals(name)) {
                            return resolveVariableType(new TreePath(current, variableTree), variableTree, depth + 1);
                        }
                    }
                } else if (leaf instanceof EnhancedForLoopTree enhancedForLoopTree) {
                    var variable = enhancedForLoopTree.getVariable();
                    if (variable.getName().contentEquals(name)) {
                        return resolveVariableType(new TreePath(current, variable), variable, depth + 1);
                    }
                } else if (leaf instanceof CatchTree catchTree) {
                    var parameter = catchTree.getParameter();
                    if (parameter != null && parameter.getName().contentEquals(name)) {
                        return resolveVariableType(new TreePath(current, parameter), parameter, depth + 1);
                    }
                } else if (leaf instanceof ClassTree classTree) {
                    for (var member : classTree.getMembers()) {
                        if (member instanceof VariableTree variableTree
                                && variableTree.getType() != null
                                && variableTree.getName().contentEquals(name)) {
                            return resolveVariableType(new TreePath(current, variableTree), variableTree, depth + 1);
                        }
                    }
                }
                child = current;
            }
            return Optional.empty();
        }

        private Optional<ResolvedType> findVisibleVariableInBlock(
                TreePath blockPath, BlockTree blockTree, TreePath child, String name, int depth) {
            for (var statement : blockTree.getStatements()) {
                if (statement == child.getLeaf()) {
                    break;
                }
                if (statement instanceof VariableTree variableTree && variableTree.getName().contentEquals(name)) {
                    return resolveVariableType(new TreePath(blockPath, variableTree), variableTree, depth + 1);
                }
            }
            return Optional.empty();
        }

        private Optional<ResolvedType> resolveVariableType(TreePath variablePath, VariableTree variable, int depth) {
            if (variable.getType() != null && !"var".equals(variable.getType().toString())) {
                return resolveTypeTree(variablePath, variable.getType(), false);
            }
            if (variable.getInitializer() != null) {
                return resolveExpression(new TreePath(variablePath, variable.getInitializer()), depth + 1);
            }
            return Optional.empty();
        }

        private Optional<ResolvedType> resolveTypeTree(TreePath contextPath, Tree tree, boolean staticContext) {
            if (tree == null) {
                return Optional.empty();
            }
            if (tree instanceof AnnotatedTypeTree annotatedTypeTree) {
                return resolveTypeTree(contextPath, annotatedTypeTree.getUnderlyingType(), staticContext);
            }
            if (tree instanceof ParameterizedTypeTree parameterizedTypeTree) {
                return resolveTypeTree(contextPath, parameterizedTypeTree.getType(), staticContext);
            }
            if (tree instanceof ArrayTypeTree arrayTypeTree) {
                var component = resolveTypeTree(contextPath, arrayTypeTree.getType(), staticContext);
                return component.map(ResolvedType::asArray);
            }
            if (tree instanceof PrimitiveTypeTree primitiveTypeTree) {
                return Optional.of(new ResolvedType(primitiveTypeTree.toString(), staticContext, false));
            }
            return resolveTypeName(tree.toString(), contextPath).map(type -> new ResolvedType(type, staticContext, false));
        }

        private Optional<ResolvedMethod> resolveMethod(TreePath invocationPath, MethodInvocationTree invocationTree) {
            return resolveMethod(invocationPath, invocationTree, 0);
        }

        private Optional<ResolvedMethod> resolveMethod(
                TreePath invocationPath, MethodInvocationTree invocationTree, int depth) {
                var arguments = resolveArgumentTypes(invocationPath, invocationTree.getArguments(), depth + 1);
            if (invocationTree.getMethodSelect() instanceof IdentifierTree identifierTree) {
                var ownerType = currentEnclosingType(invocationPath);
                if (ownerType.isEmpty()) {
                    return Optional.empty();
                }
                return selectMethod(
                        collectMethods(ownerType.get(), identifierTree.getName().toString(), false, false),
                        arguments);
            }
            if (invocationTree.getMethodSelect() instanceof MemberSelectTree memberSelectTree) {
                var receiver =
                        resolveExpression(
                                new TreePath(invocationPath, memberSelectTree.getExpression()), depth + 1);
                if (receiver.isEmpty()) {
                    return Optional.empty();
                }
                return selectMethod(
                        collectMethods(
                                receiver.get().qualifiedName,
                                memberSelectTree.getIdentifier().toString(),
                                receiver.get().staticContext,
                                true),
                        arguments);
            }
            return Optional.empty();
        }

        private Optional<ResolvedMethod> resolveConstructor(TreePath newClassPath, NewClassTree newClassTree) {
            return resolveConstructor(newClassPath, newClassTree, 0);
        }

        private Optional<ResolvedMethod> resolveConstructor(TreePath newClassPath, NewClassTree newClassTree, int depth) {
            var ownerType = resolveTypeTree(newClassPath, newClassTree.getIdentifier(), false);
            if (ownerType.isEmpty()) {
                return Optional.empty();
            }
            var arguments = resolveArgumentTypes(newClassPath, newClassTree.getArguments(), depth + 1);
            return selectMethod(collectConstructors(ownerType.get().canonicalName()), arguments);
        }

        private List<Optional<ResolvedType>> resolveArgumentTypes(
                TreePath invocationPath, List<? extends ExpressionTree> arguments, int depth) {
            var types = new ArrayList<Optional<ResolvedType>>(arguments.size());
            for (var argument : arguments) {
                types.add(resolveExpression(new TreePath(invocationPath, argument), depth + 1));
            }
            return types;
        }

        private List<ResolvedMethod> collectMethods(
                String ownerType, String methodName, boolean staticContext, boolean strictStaticContext) {
            var bySignature = new LinkedHashMap<String, ResolvedMethod>();
            for (var method : sourceMethods(ownerType, methodName, staticContext, strictStaticContext)) {
                bySignature.put(signatureKey(method), method);
            }
            for (var method : indexedMethods(ownerType, methodName, staticContext, strictStaticContext)) {
                bySignature.putIfAbsent(signatureKey(method), method);
            }
            return new ArrayList<>(bySignature.values());
        }

        private List<ResolvedMethod> collectConstructors(String ownerType) {
            var bySignature = new LinkedHashMap<String, ResolvedMethod>();
            for (var method : sourceConstructors(ownerType)) {
                bySignature.put(signatureKey(method), method);
            }
            return new ArrayList<>(bySignature.values());
        }

        private List<ResolvedMethod> sourceMethods(
                String ownerType, String methodName, boolean staticContext, boolean strictStaticContext) {
            var typeTree = findLocalType(ownerType);
            if (typeTree == null) {
                return List.of();
            }
            var methods = new ArrayList<ResolvedMethod>();
            for (var member : typeTree.getMembers()) {
                if (!(member instanceof MethodTree methodTree)) {
                    continue;
                }
                if (!methodTree.getName().contentEquals(methodName)) {
                    continue;
                }
                var isStatic = methodTree.getModifiers().getFlags().contains(Modifier.STATIC);
                if (strictStaticContext && staticContext != isStatic) {
                    continue;
                }
                methods.add(sourceMethod(ownerType, methodTree));
            }
            return methods;
        }

        private ResolvedMethod sourceMethod(String ownerType, MethodTree methodTree) {
            var parameterTypes = new String[methodTree.getParameters().size()];
            var parameterNames = new String[methodTree.getParameters().size()];
            for (var i = 0; i < methodTree.getParameters().size(); i++) {
                var parameter = methodTree.getParameters().get(i);
                parameterNames[i] = parameter.getName().toString();
                parameterTypes[i] =
                        resolveTypeTree(getCurrentPath(), parameter.getType(), false)
                                .map(ResolvedType::canonicalName)
                                .orElse(parameter.getType().toString());
            }
            var returnType =
                    methodTree.getReturnType() == null
                            ? null
                            : resolveTypeTree(getCurrentPath(), methodTree.getReturnType(), false)
                                    .map(ResolvedType::canonicalName)
                                    .orElse(methodTree.getReturnType().toString());
            return new ResolvedMethod(
                    ownerType, methodTree.getName().toString(), returnType, parameterTypes, parameterNames, true);
        }

        private List<ResolvedMethod> indexedMethods(
                String ownerType, String methodName, boolean staticContext, boolean strictStaticContext) {
            var methods = new ArrayList<ResolvedMethod>();
            var contexts = strictStaticContext ? List.of(staticContext) : List.of(false, true);
            for (var context : contexts) {
                for (var member : index.members(ownerType, context)) {
                    if (member.kind != CompletionItemKind.Method || !Objects.equals(member.name, methodName)) {
                        continue;
                    }
                    var parameterTypes =
                            member.erasedParameterTypes == null ? new String[0] : member.erasedParameterTypes;
                    var parameterNames = member.parameterNames == null ? new String[0] : member.parameterNames;
                    var resolved =
                            new ResolvedMethod(ownerType, member.name, member.returnType, parameterTypes, parameterNames);
                    if (needsSourceParameterNames(resolved) && shouldResolveSourceParameterNames(ownerType)) {
                        var sourceNames = sourceParameterNames(ownerType, methodName, parameterTypes);
                        if (sourceNames.isPresent()) {
                            resolved = resolved.withParameterNames(sourceNames.get());
                        }
                    }
                    methods.add(resolved);
                }
            }
            return methods;
        }

        private List<ResolvedMethod> sourceConstructors(String ownerType) {
            var methods = new ArrayList<ResolvedMethod>();
            var localType = findLocalType(ownerType);
            if (localType != null) {
                methods.addAll(constructorsFromClass(ownerType, localType));
                return methods;
            }
            var source = openTypeSource(ownerType);
            if (source.isPresent()) {
                var classTree = findTypeInCompilationUnit(source.get().root, ownerType);
                if (classTree != null) {
                    methods.addAll(constructorsFromClass(ownerType, classTree));
                }
            }
            return methods;
        }

        private List<ResolvedMethod> constructorsFromClass(String ownerType, ClassTree classTree) {
            var methods = new ArrayList<ResolvedMethod>();
            for (var member : classTree.getMembers()) {
                if (!(member instanceof MethodTree methodTree) || methodTree.getReturnType() != null) {
                    continue;
                }
                methods.add(sourceConstructor(ownerType, classTree.getSimpleName().toString(), methodTree));
            }
            if (methods.isEmpty() && classTree.getKind() == Tree.Kind.RECORD) {
                methods.add(recordConstructor(ownerType, classTree));
            }
            return methods;
        }

        private ResolvedMethod sourceConstructor(String ownerType, String constructorName, MethodTree methodTree) {
            var parameterTypes = new String[methodTree.getParameters().size()];
            var parameterNames = new String[methodTree.getParameters().size()];
            for (var i = 0; i < methodTree.getParameters().size(); i++) {
                var parameter = methodTree.getParameters().get(i);
                parameterNames[i] = parameter.getName().toString();
                parameterTypes[i] =
                        resolveTypeTree(getCurrentPath(), parameter.getType(), false)
                                .map(ResolvedType::canonicalName)
                                .orElse(parameter.getType().toString());
            }
            return new ResolvedMethod(ownerType, constructorName, ownerType, parameterTypes, parameterNames, true);
        }

        private ResolvedMethod recordConstructor(String ownerType, ClassTree classTree) {
            var components = new ArrayList<VariableTree>();
            for (var member : classTree.getMembers()) {
                if (!(member instanceof VariableTree variableTree) || variableTree.getType() == null) {
                    continue;
                }
                components.add(variableTree);
            }
            var parameterTypes = new String[components.size()];
            var parameterNames = new String[components.size()];
            for (var i = 0; i < components.size(); i++) {
                var component = components.get(i);
                parameterNames[i] = component.getName().toString();
                parameterTypes[i] =
                        resolveTypeTree(getCurrentPath(), component.getType(), false)
                                .map(ResolvedType::canonicalName)
                                .orElse(component.getType().toString());
            }
            return new ResolvedMethod(
                    ownerType, classTree.getSimpleName().toString(), ownerType, parameterTypes, parameterNames, true);
        }

        private boolean needsSourceParameterNames(ResolvedMethod method) {
            if (method.parameterNames.length == 0) {
                return false;
            }
            for (var name : method.parameterNames) {
                if (!isSyntheticParameterName(name)) {
                    return false;
                }
            }
            return true;
        }

        private Optional<String[]> sourceParameterNames(String ownerType, String methodName, String[] parameterTypes) {
            var typeTree = findLocalType(ownerType);
            if (typeTree != null) {
                for (var member : typeTree.getMembers()) {
                    if (!(member instanceof MethodTree methodTree)) {
                        continue;
                    }
                    if (!methodTree.getName().contentEquals(methodName)) {
                        continue;
                    }
                    var candidate = sourceMethod(ownerType, methodTree);
                    if (sameSignature(candidate.parameterTypes, parameterTypes)) {
                        return Optional.of(candidate.parameterNames);
                    }
                }
            }
            try {
                var source = openTypeSource(ownerType);
                if (source.isEmpty()) {
                    return Optional.empty();
                }
                var method = FindHelper.findMethod(source.get(), ownerType, methodName, parameterTypes);
                var names = new String[method.getParameters().size()];
                for (var i = 0; i < method.getParameters().size(); i++) {
                    names[i] = method.getParameters().get(i).getName().toString();
                }
                return Optional.of(names);
            } catch (RuntimeException missingSourceMethod) {
                return Optional.empty();
            }
        }

        private Optional<ParseTask> openTypeSource(String ownerType) {
            var typeInfo = index.types().get(ownerType);
            if (typeInfo != null && isWorkspaceSource(typeInfo.sourcePath)) {
                return Optional.of(compiler.parse(typeInfo.sourcePath));
            }
            var source = compiler.findAnywhere(ownerType);
            if (source.isPresent() && isWorkspaceSource(source.get())) {
                return Optional.of(compiler.parse(source.get()));
            }
            return Optional.empty();
        }

        private Optional<ResolvedMethod> selectMethod(
                List<ResolvedMethod> candidates, List<Optional<ResolvedType>> arguments) {
            ResolvedMethod best = null;
            int bestScore = Integer.MIN_VALUE;
            for (var candidate : candidates) {
                var score = methodScore(candidate, arguments);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            return bestScore < 0 ? Optional.empty() : Optional.ofNullable(best);
        }

        private int methodScore(ResolvedMethod candidate, List<Optional<ResolvedType>> arguments) {
            if (candidate.parameterTypes.length != arguments.size()) {
                return -1;
            }
            var score = 0;
            for (var i = 0; i < arguments.size(); i++) {
                var argument = arguments.get(i);
                if (argument.isEmpty()) {
                    continue;
                }
                if (!isCompatible(argument.get(), candidate.parameterTypes[i])) {
                    return -1;
                }
                score += 2;
            }
            if (candidate.fromSource) {
                score += 1;
            }
            return score;
        }

        private boolean isCompatible(ResolvedType argument, String parameterType) {
            if (parameterType == null || parameterType.isBlank()) {
                return true;
            }
            var argumentType = boxedType(argument.canonicalName());
            var expectedType = boxedType(canonicalTypeName(parameterType));
            if ("java.lang.Object".equals(expectedType) && !TypeMemberIndex.isPrimitiveTypeName(argumentType)) {
                return true;
            }
            return Objects.equals(argumentType, expectedType);
        }

        private Optional<ResolvedType> resolveSourceField(String ownerType, String memberName) {
            var typeTree = findLocalType(ownerType);
            if (typeTree != null) {
                for (var member : typeTree.getMembers()) {
                    if (member instanceof VariableTree variableTree
                            && variableTree.getType() != null
                            && variableTree.getName().contentEquals(memberName)) {
                        return resolveTypeTree(getCurrentPath(), variableTree.getType(), false);
                    }
                }
            }
            return Optional.empty();
        }

        private Optional<String> resolveTypeName(String typeName, TreePath contextPath) {
            if (typeName == null || typeName.isBlank()) {
                return Optional.empty();
            }
            var raw = canonicalTypeName(typeName);
            if (raw.isBlank()) {
                return Optional.empty();
            }
            if (TypeMemberIndex.isPrimitiveTypeName(raw)) {
                return Optional.of(raw);
            }
            var localQualified = resolveLocalQualifiedType(raw, contextPath);
            if (localQualified.isPresent()) {
                return localQualified;
            }
            var indexed = index.resolveTypeName(raw, root);
            if (indexed.isPresent()) {
                return indexed;
            }
            if (!isGlobalTypeLookupCandidate(raw)) {
                LOG.fine("inlay_hint_skip_global_type_lookup name=" + raw);
                return Optional.empty();
            }
            if (raw.contains(".") && compiler.findAnywhere(raw).isPresent()) {
                return Optional.of(raw);
            }
            var packageName = packageName();
            if (!packageName.isBlank()) {
                var samePackage = packageName + "." + raw;
                if (compiler.findAnywhere(samePackage).isPresent()) {
                    return Optional.of(samePackage);
                }
            }
            for (ImportTree importTree : root.getImports()) {
                if (importTree.isStatic()) {
                    continue;
                }
                var imported = importTree.getQualifiedIdentifier().toString();
                if (imported.endsWith("." + raw) && compiler.findAnywhere(imported).isPresent()) {
                    return Optional.of(imported);
                }
                if (imported.endsWith(".*")) {
                    var candidate = imported.substring(0, imported.length() - 1) + raw;
                    if (compiler.findAnywhere(candidate).isPresent()) {
                        return Optional.of(candidate);
                    }
                }
            }
            var javaLang = "java.lang." + raw;
            if (compiler.findAnywhere(javaLang).isPresent()) {
                return Optional.of(javaLang);
            }
            return Optional.empty();
        }

        private Optional<ResolvedType> resolveImplicitSlf4jLogger(TreePath contextPath, String identifier) {
            if (!"log".equals(identifier)) {
                return Optional.empty();
            }
            var classPath = enclosingClassPath(contextPath);
            if (classPath == null) {
                return Optional.empty();
            }
            var classTree = (ClassTree) classPath.getLeaf();
            if (!hasSlf4jAnnotation(classTree.getModifiers())) {
                return Optional.empty();
            }
            var loggerType = "org.slf4j.Logger";
            if (!index.types().containsKey(loggerType)) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedType(loggerType, false, false));
        }

        private Optional<String> resolveLocalQualifiedType(String raw, TreePath contextPath) {
            if (raw.contains(".")) {
                var local = findLocalType(raw);
                if (local != null) {
                    return Optional.of(raw);
                }
                var packageName = packageName();
                if (!packageName.isBlank()) {
                    var samePackage = packageName + "." + raw;
                    if (findLocalType(samePackage) != null) {
                        return Optional.of(samePackage);
                    }
                }
                return Optional.empty();
            }

            for (var classPath = enclosingClassPath(contextPath);
                    classPath != null;
                    classPath = enclosingClassPath(classPath.getParentPath())) {
                var owner = qualifiedClassName(classPath);
                var candidate = owner + "." + raw;
                if (findLocalType(candidate) != null) {
                    return Optional.of(candidate);
                }
            }
            for (var typeDecl : root.getTypeDecls()) {
                if (typeDecl instanceof ClassTree classTree && classTree.getSimpleName().contentEquals(raw)) {
                    return Optional.of(qualifiedTopLevelName(classTree));
                }
            }
            return Optional.empty();
        }

        private String packageName() {
            return root.getPackageName() == null ? "" : root.getPackageName().toString();
        }

        private Optional<String> currentEnclosingType(TreePath path) {
            var classPath = enclosingClassPath(path);
            if (classPath == null) {
                return Optional.empty();
            }
            return Optional.of(qualifiedClassName(classPath));
        }

        private Optional<String> currentSuperType(TreePath path) {
            var classPath = enclosingClassPath(path);
            if (classPath == null) {
                return Optional.empty();
            }
            var classTree = (ClassTree) classPath.getLeaf();
            if (classTree.getExtendsClause() == null) {
                return Optional.of("java.lang.Object");
            }
            return resolveTypeTree(classPath, classTree.getExtendsClause(), false).map(ResolvedType::canonicalName);
        }

        private TreePath enclosingClassPath(TreePath path) {
            for (var current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof ClassTree) {
                    return current;
                }
            }
            return null;
        }

        private String qualifiedClassName(TreePath classPath) {
            var names = new ArrayList<String>();
            for (var current = classPath; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof ClassTree classTree) {
                    names.add(0, classTree.getSimpleName().toString());
                }
            }
            var packageName = packageName();
            return packageName.isBlank() ? String.join(".", names) : packageName + "." + String.join(".", names);
        }

        private String qualifiedTopLevelName(ClassTree classTree) {
            var packageName = packageName();
            var simpleName = classTree.getSimpleName().toString();
            return packageName.isBlank() ? simpleName : packageName + "." + simpleName;
        }

        private ClassTree findLocalType(String qualifiedType) {
            if (qualifiedType == null || qualifiedType.isBlank()) {
                return null;
            }
            return findTypeInCompilationUnit(root, qualifiedType);
        }

        private ClassTree findTypeInCompilationUnit(CompilationUnitTree unit, String qualifiedType) {
            if (unit == null || qualifiedType == null || qualifiedType.isBlank()) {
                return null;
            }
            var packageTree = unit.getPackageName();
            var packageName = packageTree == null ? "" : packageTree.toString();
            var relative = qualifiedType;
            if (!packageName.isBlank()) {
                var prefix = packageName + ".";
                if (!qualifiedType.startsWith(prefix)) {
                    return null;
                }
                relative = qualifiedType.substring(prefix.length());
            }
            var segments = relative.split("\\.");
            ClassTree current = null;
            List<? extends Tree> candidates = unit.getTypeDecls();
            for (var segment : segments) {
                current = null;
                for (var candidate : candidates) {
                    if (candidate instanceof ClassTree classTree
                            && classTree.getSimpleName().contentEquals(segment)) {
                        current = classTree;
                        candidates = classTree.getMembers();
                        break;
                    }
                }
                if (current == null) {
                    return null;
                }
            }
            return current;
        }

        private boolean shouldSuppressParameterHint(
                MethodInvocationTree invocationTree,
                ExpressionTree argument,
                String parameterName,
                String methodName) {
            if (argument instanceof IdentifierTree identifierTree
                    && identifierTree.getName().contentEquals(parameterName)) {
                return true;
            }
            var normalizedMethodName = normalizeName(methodName);
            var normalizedParameterName = normalizeName(parameterName);
            if (normalizedMethodName.length() <= normalizedParameterName.length()) {
                return false;
            }
            for (var prefix : METHOD_NAME_SUPPRESSION_PREFIXES) {
                if (normalizedMethodName.equals(prefix + normalizedParameterName)) {
                    return true;
                }
            }
            return false;
        }

        private String normalizeName(String name) {
            return name == null ? "" : name.replace("_", "").toLowerCase();
        }

        private boolean isObviousVarInitializer(ExpressionTree initializer) {
            return initializer instanceof LiteralTree
                    || initializer instanceof NewClassTree
                    || initializer instanceof NewArrayTree;
        }

        private boolean isInsideEnumConstant(TreePath path) {
            for (var current = path; current != null; current = current.getParentPath()) {
                if (isEnumConstantPath(current)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isEnumConstantPath(TreePath path) {
            if (path == null || !(path.getLeaf() instanceof VariableTree variableTree)) {
                return false;
            }
            var parent = path.getParentPath();
            if (parent == null || parent.getLeaf().getKind() != Tree.Kind.ENUM) {
                return false;
            }
            return variableTree.getType() == null
                    || variableTree.getInitializer() == null
                    || variableTree.getInitializer() instanceof NewClassTree;
        }

        private boolean isInsideAnnotation(TreePath path) {
            for (var current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf().getKind() == Tree.Kind.ANNOTATION) {
                    return true;
                }
            }
            return false;
        }

        private boolean intersectsRange(Tree tree) {
            if (tree == root) {
                return true;
            }
            var start = startPosition(tree);
            var end = endPosition(tree);
            if (start < 0 && end < 0) {
                return false;
            }
            if (start < 0) {
                start = end;
            }
            if (end < 0) {
                end = start;
            }
            var startLine = Math.max(0, lineAt(start));
            var endLine = Math.max(startLine, lineAt(Math.max(start, end - 1)));
            return endLine >= rangeStartLine && startLine <= rangeEndLine;
        }

        private int startPosition(Tree tree) {
            return (int) positions.getStartPosition(root, tree);
        }

        private int endPosition(Tree tree) {
            return (int) positions.getEndPosition(root, tree);
        }

        private Position variableNameEnd(VariableTree tree) {
            var start = startPosition(tree);
            var end = endPosition(tree);
            var nameStart = FindHelper.findNameIn(root, tree.getName(), start, Math.max(start + 1, end));
            if (nameStart >= 0) {
                return positionAt(nameStart + tree.getName().length());
            }
            return positionAt(Math.max(start, end));
        }

        private Position positionAt(int offset) {
            var line = lineAt(offset);
            var column = columnAt(offset);
            return new Position(line, column);
        }

        private int lineAt(int offset) {
            return (int) root.getLineMap().getLineNumber(Math.max(0, offset)) - 1;
        }

        private int columnAt(int offset) {
            return (int) root.getLineMap().getColumnNumber(Math.max(0, offset)) - 1;
        }

        private Optional<ResolvedType> literalType(LiteralTree literal) {
            var value = literal.getValue();
            if (value == null) {
                return Optional.empty();
            }
            if (value instanceof String) return Optional.of(new ResolvedType("java.lang.String", false, false));
            if (value instanceof Integer) return Optional.of(new ResolvedType("java.lang.Integer", false, false));
            if (value instanceof Long) return Optional.of(new ResolvedType("java.lang.Long", false, false));
            if (value instanceof Float) return Optional.of(new ResolvedType("java.lang.Float", false, false));
            if (value instanceof Double) return Optional.of(new ResolvedType("java.lang.Double", false, false));
            if (value instanceof Boolean) return Optional.of(new ResolvedType("java.lang.Boolean", false, false));
            if (value instanceof Character) return Optional.of(new ResolvedType("java.lang.Character", false, false));
            return Optional.empty();
        }

        private String displayType(ResolvedType type) {
            var name = simpleTypeName(type);
            return type.arrayType ? name + "[]" : name;
        }

        private String simpleTypeName(ResolvedType type) {
            var qualified = type.qualifiedName;
            var simple = qualified.contains(".") ? qualified.substring(qualified.lastIndexOf('.') + 1) : qualified;
            return simple;
        }

        private boolean isSyntheticParameterName(String name) {
            return name == null || name.matches("arg\\d+");
        }

        private String signatureKey(ResolvedMethod method) {
            return method.methodName + ":" + String.join(",", method.parameterTypes);
        }
    }

    private static String boxedType(String typeName) {
        return BOXED_TYPES.getOrDefault(typeName, typeName);
    }

    private static final List<String> METHOD_NAME_SUPPRESSION_PREFIXES =
            List.of("set", "get", "is", "has", "with");

    static boolean isGlobalTypeLookupCandidate(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        var lastDot = typeName.lastIndexOf('.');
        var simpleName = lastDot == -1 ? typeName : typeName.substring(lastDot + 1);
        if (simpleName.isBlank()) {
            return false;
        }
        return Character.isUpperCase(simpleName.charAt(0));
    }

    static boolean shouldResolveSourceParameterNames(String ownerType) {
        if (ownerType == null || ownerType.isBlank()) {
            return false;
        }
        return !ownerType.startsWith("org.slf4j.");
    }

    static boolean isWorkspaceSource(Path path) {
        return path != null && FileStore.contains(path);
    }

    static boolean isWorkspaceSource(JavaFileObject source) {
        if (source == null) {
            return false;
        }
        var uri = source.toUri();
        if (uri == null || !"file".equals(uri.getScheme())) {
            return false;
        }
        try {
            return isWorkspaceSource(Path.of(uri));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasSlf4jAnnotation(ModifiersTree modifiers) {
        if (modifiers == null) {
            return false;
        }
        for (var annotation : modifiers.getAnnotations()) {
            if (isSlf4jAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSlf4jAnnotation(AnnotationTree annotation) {
        if (annotation == null) {
            return false;
        }
        var type = annotation.getAnnotationType();
        if (type instanceof IdentifierTree identifier) {
            return "Slf4j".equals(identifier.getName().toString());
        }
        if (type instanceof MemberSelectTree select) {
            return "lombok.extern.slf4j.Slf4j".equals(select.toString())
                    || "Slf4j".equals(select.getIdentifier().toString());
        }
        return false;
    }

    private static String canonicalTypeName(String typeName) {
        var raw = typeName == null ? "" : typeName.trim();
        var arraySuffix = "";
        while (raw.endsWith("[]")) {
            arraySuffix += "[]";
            raw = raw.substring(0, raw.length() - 2);
        }
        var genericStart = raw.indexOf('<');
        if (genericStart >= 0) {
            raw = raw.substring(0, genericStart);
        }
        if (raw.startsWith("? extends ")) {
            raw = raw.substring("? extends ".length()).trim();
        } else if (raw.startsWith("? super ")) {
            raw = raw.substring("? super ".length()).trim();
        } else if ("?".equals(raw)) {
            raw = "";
        }
        return raw + arraySuffix;
    }

    private static boolean sameSignature(String[] left, String[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (var i = 0; i < left.length; i++) {
            if (!Objects.equals(canonicalTypeName(left[i]), canonicalTypeName(right[i]))) {
                return false;
            }
        }
        return true;
    }

    private static final class ResolvedType {
        final String qualifiedName;
        final boolean staticContext;
        final boolean arrayType;

        ResolvedType(String qualifiedName, boolean staticContext, boolean arrayType) {
            this.qualifiedName = qualifiedName;
            this.staticContext = staticContext;
            this.arrayType = arrayType;
        }

        static ResolvedType fromTypeName(String typeName, boolean staticContext) {
            var canonical = canonicalTypeName(typeName);
            if (canonical.endsWith("[]")) {
                return new ResolvedType(canonical.substring(0, canonical.length() - 2), staticContext, true);
            }
            return new ResolvedType(canonical, staticContext, false);
        }

        ResolvedType asArray() {
            return new ResolvedType(qualifiedName, staticContext, true);
        }

        ResolvedType asElementType() {
            return new ResolvedType(qualifiedName, false, false);
        }

        String canonicalName() {
            return arrayType ? qualifiedName + "[]" : qualifiedName;
        }
    }

    private static final class ResolvedMethod {
        final String ownerType;
        final String methodName;
        final String returnType;
        final String[] parameterTypes;
        final String[] parameterNames;
        final boolean fromSource;

        ResolvedMethod(
                String ownerType,
                String methodName,
                String returnType,
                String[] parameterTypes,
                String[] parameterNames) {
            this(ownerType, methodName, returnType, parameterTypes, parameterNames, false);
        }

        ResolvedMethod(
                String ownerType,
                String methodName,
                String returnType,
                String[] parameterTypes,
                String[] parameterNames,
                boolean fromSource) {
            this.ownerType = ownerType;
            this.methodName = methodName;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes == null ? new String[0] : parameterTypes;
            this.parameterNames = parameterNames == null ? new String[0] : parameterNames;
            this.fromSource = fromSource;
        }

        ResolvedMethod withParameterNames(String[] parameterNames) {
            return new ResolvedMethod(ownerType, methodName, returnType, parameterTypes, parameterNames, fromSource);
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
