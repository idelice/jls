package org.javacs.navigation;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.FindNameAt;
import org.javacs.ParseTask;
import org.javacs.completion.TypeIndexRouter;
import org.javacs.completion.WorkspaceTypeIndex;
import org.javacs.index.IndexedMember;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;
import org.javacs.resolve.ParseTypeResolver;
import org.javacs.resolve.TypeNames;

/**
 * Resolves go-to-definition in two phases:
 *
 * <ol>
 *   <li>Determine the symbol identity under the cursor from parse-time information plus the
 *       published indexes.
 *   <li>Open the already-known declaration owner and turn the matching tree into an LSP location
 *       with {@link FindHelper}.
 * </ol>
 *
 * <p>The lookup order is intentionally shallow so each fallback stays easy to follow:
 * declarations first, then identifier uses, member selects, member references, constructors, and
 * finally unsupported nodes.
 */
public class DefinitionProvider {
    private record TypeSource(ParseTask task, TreePath classPath) {}
    public record ResolvedSymbol(
            List<Location> locations,
            String qualifiedType,
            String memberName,
            boolean method,
            IndexedMember indexMember,
            String simpleName) {}

    public static final List<Location> NOT_SUPPORTED = List.of();

    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndexRouter;
    private final ConcurrentHashMap<String, JavaFileObject> attachedExternalSources =
            new ConcurrentHashMap<>();
    private final Path file;
    private final int line;
    private final int column;

    public DefinitionProvider(CompilerProvider compiler, Path file, int line, int column) {
        this(compiler, TypeIndexRouter.EMPTY, file, line, column);
    }

    public DefinitionProvider(
            CompilerProvider compiler,
            TypeIndexRouter typeIndexRouter,
            Path file,
            int line,
            int column) {
        this.compiler = compiler;
        this.typeIndexRouter = typeIndexRouter == null ? TypeIndexRouter.EMPTY : typeIndexRouter;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        return resolveSymbol().locations();
    }

    public ResolvedSymbol resolveSymbol() {
        var parse = compiler.parse(file);
        long cursor;
        try {
            cursor =
                    FileStore.offset(
                            parse.root().getSourceFile().getCharContent(true).toString(), line, column);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        var path = new FindNameAt(parse).scan(parse.root(), cursor);
        if (path == null) {
            return unsupported(null);
        }
        return resolve(parse, path, new ParseTypeResolver(parse, compiler, typeIndexRouter, cursor));
    }

    // Cursor classification entrypoint.
    ResolvedSymbol resolve(ParseTask parse, TreePath path, ParseTypeResolver types) {
        var leaf = path.getLeaf();
        if (leaf instanceof ClassTree cls) {
            return typeDeclaration(parse, path, cls);
        }
        if (leaf instanceof MethodTree method) {
            return methodDeclaration(parse, path, method);
        }
        if (leaf instanceof VariableTree variable) {
            return variableDeclaration(parse, path, variable);
        }
        if (leaf instanceof AnnotationTree annotation) {
            return resolveAnnotation(parse, annotation);
        }
        if (leaf instanceof IdentifierTree identifier) {
            return resolveIdentifier(parse, path, identifier, types);
        }
        if (leaf instanceof MemberSelectTree memberSelect) {
            return resolveMemberSelect(parse, path, memberSelect, types);
        }
        if (leaf instanceof MemberReferenceTree memberReference) {
            return resolveMemberReference(memberReference, types);
        }
        if (leaf instanceof NewClassTree newClassTree) {
            return resolveConstructorInvocation(
                    parse, newClassTree, types, newClassTree.getIdentifier().toString());
        }
        return unsupported(null);
    }

    // Identifier, member, and constructor resolution.
    private ResolvedSymbol resolveIdentifier(
            ParseTask parse,
            TreePath path,
            IdentifierTree identifier,
            ParseTypeResolver types) {
        var name = identifier.getName().toString();
        var constructor = resolveConstructorIdentifier(parse, path, identifier, types);
        if (constructor.isPresent()) {
            return constructor.get();
        }
        var invocation = resolveUnqualifiedInvocation(parse, path, identifier, types);
        if (invocation.isPresent()) {
            return invocation.get();
        }
        var visible = resolveVisibleDeclaration(parse, types, name);
        if (visible.isPresent()) {
            return visible.get();
        }
        var enclosingField = resolveEnclosingField(types, name);
        if (enclosingField.isPresent()) {
            return enclosingField.get();
        }
        var inheritedField = resolveInheritedField(name, types);
        if (inheritedField.isPresent()) {
            return inheritedField.get();
        }
        var staticImportField = resolveStaticImportField(parse, name);
        if (staticImportField.isPresent()) {
            return staticImportField.get();
        }
        var switchField = resolveSwitchCaseField(path, name, types);
        if (switchField.isPresent()) {
            return switchField.get();
        }
        return resolveTypeTree(parse, path, identifier, name);
    }

    private Optional<ResolvedSymbol> resolveConstructorIdentifier(
            ParseTask parse,
            TreePath path,
            IdentifierTree identifier,
            ParseTypeResolver types) {
        var parent = parentLeaf(path);
        if (parent instanceof NewClassTree newClassTree
                && newClassTree.getIdentifier() == identifier) {
            return Optional.of(resolveConstructorInvocation(parse, newClassTree, types, identifier.getName().toString()));
        }
        return Optional.empty();
    }

    private Optional<ResolvedSymbol> resolveUnqualifiedInvocation(
            ParseTask parse,
            TreePath path,
            IdentifierTree identifier,
            ParseTypeResolver types) {
        var parent = parentLeaf(path);
        if (parent instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() == identifier) {
            return Optional.of(
                    resolveUnqualifiedMethodInvocation(
                            parse, types, invocation, identifier.getName().toString()));
        }
        return Optional.empty();
    }

    private ResolvedSymbol resolveMemberSelect(
            ParseTask parse,
            TreePath path,
            MemberSelectTree memberSelect,
            ParseTypeResolver types) {
        var name = memberSelect.getIdentifier().toString();
        var receiver =
                types.resolveExpression(memberSelect.getExpression())
                        .or(
                                () ->
                                        typeIndexRouter
                                                .resolveTypeName(memberSelect.getExpression().toString(), parse.root())
                                                .map(
                                                        qualifiedType ->
                                                                new ParseTypeResolver.TypeResolution(
                                                                        qualifiedType, true, false)));
        var parent = parentLeaf(path);
        if (parent instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() == memberSelect) {
            if (receiver.isEmpty()) {
                return unsupportedMethod(name);
            }
            var indexed =
                    strictIndexedMethod(
                            receiver.get().qualifiedType(),
                            name,
                            receiver.get().staticContext(),
                            invocation.getArguments().size());
            if (indexed.isPresent()) {
                return indexed.get();
            }
            return resolveQualifiedMethodInvocation(
                    types, receiver.get(), name, invocation.getArguments(), invocation.getArguments().size(), false);
        }
        if (receiver.isPresent()) {
            var member = typeIndexRouter.member(
                            receiver.get().qualifiedType(), name, receiver.get().staticContext())
                    .filter(this::isFieldLikeMember)
                    .orElse(null);
            if (member != null) {
                var targetOwner = indexedOwner(receiver.get().qualifiedType(), member);
                var indexedField = member.declarationLocation();
                if (indexedField.isPresent() && !hasBackingField(member)) {
                    return new ResolvedSymbol(
                            List.of(indexedField.get()), targetOwner, name, false, member, name);
                }
                return resolveField(receiver.get().qualifiedType(), name, member);
            }
            var nestedType =
                    typeIndexRouter.workspaceNestedType(receiver.get().qualifiedType(), name)
                            .map(qualifiedType -> resolveTypeName(qualifiedType, name));
            if (nestedType.isPresent()) {
                return nestedType.get();
            }
        }
        return resolveTypeTree(parse, path, memberSelect, name);
    }

    private ResolvedSymbol resolveMemberReference(
            MemberReferenceTree memberReference,
            ParseTypeResolver types) {
        var name = memberReference.getName().toString();
        if ("new".equals(name)) {
            var receiver = types.resolveExpression(memberReference.getQualifierExpression());
            if (receiver.isEmpty()) {
                return unsupported(name);
            }
            var ownerType = receiver.get().qualifiedType();
            return new ResolvedSymbol(
                    findConstructorLocations(ownerType, -1),
                    ownerType,
                    TypeNames.simpleName(ownerType),
                    true,
                    null,
                    TypeNames.simpleName(ownerType));
        }
        var target = types.resolveMethodReferenceTarget(memberReference);
        if (target.isPresent()) {
            var indexed =
                    strictIndexedMethod(
                            target.get().receiverType().qualifiedType(),
                            name,
                            target.get().receiverType().staticContext(),
                            target.get().argumentCount());
            if (indexed.isPresent()) {
                return indexed.get();
            }
            return unsupportedMethod(name);
        }
        var receiver = types.resolveExpression(memberReference.getQualifierExpression());
        if (receiver.isEmpty()) {
            return unsupported(name);
        }
        // A type-qualified method reference like Type::method is ambiguous without a functional
        // target. Unlike normal member selects, we fail closed here instead of pretending it is a
        // static call.
        if (receiver.get().staticContext()) {
            return unsupportedMethod(name);
        }
        var indexed = strictIndexedMethod(receiver.get().qualifiedType(), name, false, 0);
        if (indexed.isPresent()) {
            return indexed.get();
        }
        return resolveQualifiedMethodInvocation(types, receiver.get(), name, List.of(), 0, false);
    }

    private ResolvedSymbol resolveUnqualifiedMethodInvocation(
            ParseTask parse, ParseTypeResolver types, MethodInvocationTree invocation, String methodName) {
        var owner = types.currentEnclosingTypeName();
        if (owner.isPresent()) {
            var indexed = strictIndexedMethod(owner.get(), methodName, false, invocation.getArguments().size());
            if (indexed.isPresent()) {
                return indexed.get();
            }
        }
        var argTypes = NavigationSymbolSupport.argumentTypes(invocation.getArguments(), types);
        if (owner.isPresent()) {
            var member =
                    lookupMethod(owner.get(), methodName, false, invocation.getArguments().size(), argTypes, false)
                            .orElse(null);
            var resolved = resolveMethod(owner.get(), methodName, invocation.getArguments().size(), argTypes, member);
            if (!resolved.locations().isEmpty()) {
                return resolved;
            }
        }
        return resolveStaticImportMethod(parse.root(), methodName, invocation.getArguments().size(), argTypes)
                .orElseGet(() -> unsupportedMethod(methodName));
    }

    /**
     * Resolve a method call or method-reference target from index data plus parse-local argument
     * types.
     *
     * <p>When {@code strictArity} is true, the caller already knows the functional target shape and
     * this method must return empty on ambiguity. Example: {@code formatter::label} should not fall
     * back to name-only lookup and return every overload.
     */
    private ResolvedSymbol resolveQualifiedMethodInvocation(
            ParseTypeResolver types,
            ParseTypeResolver.TypeResolution receiver,
            String methodName,
            List<? extends ExpressionTree> arguments,
            int argumentCount,
            boolean strictArity) {
        var argTypes = NavigationSymbolSupport.argumentTypes(arguments, types);
        var member =
                lookupMethod(
                                receiver.qualifiedType(),
                                methodName,
                                receiver.staticContext(),
                                argumentCount,
                                argTypes,
                                strictArity)
                        .orElse(null);
        if (strictArity && member == null) {
            return unsupportedMethod(methodName);
        }
        return resolveMethod(
                receiver.qualifiedType(),
                methodName,
                argumentCount,
                argTypes,
                member);
    }

    private ResolvedSymbol resolveMethod(
            String ownerType,
            String methodName,
            int argCount,
            List<String> argTypes,
            IndexedMember member) {
        var targetOwner = indexedOwner(ownerType, member);
        var indexedLinked = indexedLinkedFieldLocation(member);
        if (indexedLinked.isPresent()) {
            return new ResolvedSymbol(
                    List.of(indexedLinked.get()),
                    linkedFieldOwner(targetOwner, member),
                    member.backingFieldName,
                    false,
                    member,
                    member.backingFieldName);
        }
        if (member != null) {
            var indexedMethod = member.declarationLocation();
            if (indexedMethod.isPresent()) {
                return new ResolvedSymbol(
                        List.of(indexedMethod.get()),
                        targetOwner,
                        methodName,
                        true,
                        member,
                        methodName);
            }
        }
        if (hasBackingField(member)) {
            var linkedOwner = linkedFieldOwner(targetOwner, member);
            var linkedField = member.backingFieldName;
            var linkedLocations = findFieldLocations(linkedOwner, linkedField);
            if (!linkedLocations.isEmpty()) {
                return new ResolvedSymbol(
                        linkedLocations,
                        linkedOwner,
                        linkedField,
                        false,
                        member,
                        linkedField);
            }
        }
        var direct = findMethodLocations(targetOwner, methodName, argCount, argTypes);
        if (!direct.isEmpty()) {
            return new ResolvedSymbol(direct, targetOwner, methodName, true, member, methodName);
        }
        return new ResolvedSymbol(NOT_SUPPORTED, targetOwner, methodName, true, member, methodName);
    }

    private ResolvedSymbol resolveAnnotation(ParseTask parse, AnnotationTree annotation) {
        var annotationType = annotation.getAnnotationType();
        if (annotationType == null) {
            return unsupported(null);
        }
        var simpleName = annotationType instanceof MemberSelectTree memberSelect
                ? memberSelect.getIdentifier().toString()
                : annotationType.toString();
        var annotationPath = Trees.instance(parse.task()).getPath(parse.root(), annotationType);
        return resolveTypeTree(parse, annotationPath, annotationType, simpleName);
    }

    /**
     * Resolve a type usage to a qualified type name before the location phase starts.
     *
     * <p>Workspace type identity must come from the index. External lookup only matters later,
     * when the owner type is already known and definition needs attached or decompiled source.
     */
    private ResolvedSymbol resolveTypeTree(
            ParseTask parse, TreePath path, Tree typeTree, String simpleName) {
        return typeIndexRouter.resolveTypeName(typeTree.toString(), parse.root())
                .or(() -> resolveEnclosingNestedType(parse, path, simpleName))
                .map(qualifiedType -> resolveTypeName(qualifiedType, simpleName))
                .orElseGet(() -> unsupported(simpleName));
    }

    private ResolvedSymbol resolveConstructorInvocation(
            ParseTask parse, NewClassTree newClassTree, ParseTypeResolver types, String simpleName) {
        var ownerType =
                types.resolveTypeTree(newClassTree.getIdentifier(), true)
                        .map(ParseTypeResolver.TypeResolution::qualifiedType)
                        .or(() -> typeIndexRouter.resolveTypeName(newClassTree.getIdentifier().toString(), parse.root()));
        if (ownerType.isEmpty()) {
            return unsupported(simpleName);
        }

        var currentType = types.currentEnclosingTypeName();
        if (currentType.isPresent() && currentType.get().equals(ownerType.get())) {
            return resolveTypeName(ownerType.get(), simpleName);
        }

        var constructors =
                findConstructorLocations(ownerType.get(), newClassTree.getArguments().size());
        if (!constructors.isEmpty()) {
            return new ResolvedSymbol(constructors, ownerType.get(), simpleName, true, null, simpleName);
        }
        return resolveTypeName(ownerType.get(), simpleName);
    }

    private ResolvedSymbol resolveTypeName(String qualifiedType, String simpleName) {
        var indexedType = typeIndexRouter.typeInfo(qualifiedType).flatMap(type -> type.declarationLocation());
        if (indexedType.isPresent()) {
            return new ResolvedSymbol(
                    List.of(indexedType.get()), qualifiedType, null, false, null, simpleName);
        }
        var typeLocations = findTypeLocations(qualifiedType, simpleName);
        if (typeLocations.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, qualifiedType, null, false, null, simpleName);
        }
        return new ResolvedSymbol(typeLocations, qualifiedType, null, false, null, simpleName);
    }

    private Optional<ResolvedSymbol> resolveStaticImportField(ParseTask parse, String fieldName) {
        ResolvedSymbol match = null;
        for (var ownerType : WorkspaceTypeIndex.staticImportOwnerTypes(fieldName, parse.root())) {
            var member = typeIndexRouter.member(ownerType, fieldName, true)
                    .filter(this::isFieldLikeMember)
                    .orElse(null);
            ResolvedSymbol resolved;
            if (member != null) {
                var indexedField = member.declarationLocation();
                if (indexedField.isPresent() && !hasBackingField(member)) {
                    resolved = new ResolvedSymbol(
                            List.of(indexedField.get()),
                            indexedOwner(ownerType, member),
                            fieldName,
                            false,
                            member,
                            fieldName);
                } else {
                    resolved = resolveField(ownerType, fieldName, member);
                }
            } else {
                resolved = resolveField(ownerType, fieldName, null);
            }
            if (resolved.locations().isEmpty()) {
                continue;
            }
            if (match != null && !sameLocations(match.locations(), resolved.locations())) {
                return Optional.empty();
            }
            match = resolved;
        }
        return Optional.ofNullable(match);
    }

    private Optional<ResolvedSymbol> strictIndexedMethod(
            String ownerType, String methodName, boolean staticContext, int argumentCount) {
        var member = uniqueMethodByArity(ownerType, methodName, staticContext, argumentCount);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        var indexed = member.get();
        var targetOwner = indexedOwner(ownerType, indexed);
        var location = indexed.declarationLocation();
        if (location.isPresent() && !hasBackingField(indexed)) {
            return Optional.of(
                    new ResolvedSymbol(
                            List.of(location.get()),
                            targetOwner,
                            methodName,
                            true,
                            indexed,
                            methodName));
        }
        return Optional.of(resolveMethod(ownerType, methodName, argumentCount, List.of(), indexed));
    }

    private Optional<ResolvedSymbol> resolveStaticImportMethod(
            com.sun.source.tree.CompilationUnitTree root,
            String methodName,
            int argCount,
            List<String> argTypes) {
        ResolvedSymbol match = null;
        for (var ownerType : WorkspaceTypeIndex.staticImportOwnerTypes(methodName, root)) {
            var member = lookupMethod(ownerType, methodName, true, argCount, argTypes, false).orElse(null);
            var resolved = resolveMethod(ownerType, methodName, argCount, argTypes, member);
            if (resolved.locations().isEmpty()) {
                continue;
            }
            if (match != null && !sameLocations(match.locations(), resolved.locations())) {
                return Optional.empty();
            }
            match = resolved;
        }
        return Optional.ofNullable(match);
    }

    private Optional<ResolvedSymbol> resolveSwitchCaseField(
            TreePath path, String fieldName, ParseTypeResolver types) {
        var labelPath = path.getParentPath();
        if (labelPath == null || labelPath.getLeaf().getKind() != Tree.Kind.CONSTANT_CASE_LABEL) {
            return Optional.empty();
        }
        var casePath = labelPath.getParentPath();
        if (casePath == null || !(casePath.getLeaf() instanceof CaseTree)) {
            return Optional.empty();
        }
        var switchPath = casePath.getParentPath();
        if (switchPath == null) {
            return Optional.empty();
        }

        Tree switchExpression = null;
        if (switchPath.getLeaf() instanceof SwitchTree switchTree) {
            switchExpression = switchTree.getExpression();
        } else if (switchPath.getLeaf() instanceof SwitchExpressionTree switchExpressionTree) {
            switchExpression = switchExpressionTree.getExpression();
        }
        if (switchExpression == null) {
            return Optional.empty();
        }

        var ownerType = types.resolveExpression(switchExpression).map(ParseTypeResolver.TypeResolution::qualifiedType);
        if (ownerType.isEmpty() || ownerType.get().isBlank()) {
            return Optional.empty();
        }

        var staticMember = typeIndexRouter.member(ownerType.get(), fieldName, true)
                .filter(this::isFieldLikeMember)
                .orElse(null);
        if (staticMember != null) {
            var indexedField = staticMember.declarationLocation();
            if (indexedField.isPresent() && !hasBackingField(staticMember)) {
                return Optional.of(
                        new ResolvedSymbol(
                                List.of(indexedField.get()),
                                indexedOwner(ownerType.get(), staticMember),
                                fieldName,
                                false,
                                staticMember,
                                fieldName));
            }
            var resolved = resolveField(ownerType.get(), fieldName, staticMember);
            if (!resolved.locations().isEmpty()) {
                return Optional.of(resolved);
            }
        }

        var instanceMember = typeIndexRouter.member(ownerType.get(), fieldName, false)
                .filter(this::isFieldLikeMember)
                .orElse(null);
        if (instanceMember != null) {
            var indexedField = instanceMember.declarationLocation();
            if (indexedField.isPresent() && !hasBackingField(instanceMember)) {
                return Optional.of(
                        new ResolvedSymbol(
                                List.of(indexedField.get()),
                                indexedOwner(ownerType.get(), instanceMember),
                                fieldName,
                                false,
                                instanceMember,
                                fieldName));
            }
            var resolved = resolveField(ownerType.get(), fieldName, instanceMember);
            if (!resolved.locations().isEmpty()) {
                return Optional.of(resolved);
            }
        }
        return Optional.empty();
    }

    private boolean sameLocations(List<Location> left, List<Location> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            var a = left.get(i);
            var b = right.get(i);
            if (!a.uri.equals(b.uri)) {
                return false;
            }
            if (a.range.start.line != b.range.start.line
                    || a.range.start.character != b.range.start.character
                    || a.range.end.line != b.range.end.line
                    || a.range.end.character != b.range.end.character) {
                return false;
            }
        }
        return true;
    }

    // Declaration targets under the cursor.
    private ResolvedSymbol typeDeclaration(ParseTask parse, TreePath path, ClassTree cls) {
        var qualifiedType = declaredClassName(parse, path);
        var location = FindHelper.location(parse, path, cls.getSimpleName());
        if (location == null) {
            return new ResolvedSymbol(
                    NOT_SUPPORTED, qualifiedType, null, false, null, cls.getSimpleName().toString());
        }
        return new ResolvedSymbol(
                List.of(location), qualifiedType, null, false, null, cls.getSimpleName().toString());
    }

    private ResolvedSymbol methodDeclaration(ParseTask parse, TreePath path, MethodTree method) {
        var ownerType = enclosingOwnerType(parse, path).orElse(null);
        var classPath = nearestClass(path);
        var simpleName =
                method.getName().contentEquals("<init>")
                        ? classPath != null
                                ? ((ClassTree) classPath.getLeaf()).getSimpleName().toString()
                                : method.getName().toString()
                        : method.getName().toString();
        var indexed = indexedMethodDeclaration(parse, path, ownerType, simpleName, method);

        var location = FindHelper.location(parse, path, simpleName);
        if (location == null) {
            return new ResolvedSymbol(NOT_SUPPORTED, ownerType, simpleName, true, null, simpleName);
        }
        return new ResolvedSymbol(
                List.of(location), ownerType, simpleName, true, indexed.orElse(null), simpleName);
    }

    private ResolvedSymbol variableDeclaration(ParseTask parse, TreePath path, VariableTree variable) {
        var ownerType = parentLeaf(path) instanceof ClassTree ? enclosingOwnerType(parse, path).orElse(null) : null;
        var location = FindHelper.location(parse, path, variable.getName());
        if (location == null) {
            return new ResolvedSymbol(
                    NOT_SUPPORTED,
                    ownerType,
                    variable.getName().toString(),
                    false,
                    null,
                    variable.getName().toString());
        }
        if (ownerType != null && parentLeaf(path) instanceof ClassTree) {
            var member = typeIndexRouter.member(ownerType, variable.getName().toString(), false)
                    .or(() -> typeIndexRouter.member(ownerType, variable.getName().toString(), true))
                    .orElse(null);
            return new ResolvedSymbol(
                    List.of(location),
                    ownerType,
                    variable.getName().toString(),
                    false,
                    member,
                    variable.getName().toString());
        }
        return new ResolvedSymbol(
                List.of(location),
                ownerType,
                variable.getName().toString(),
                false,
                null,
                variable.getName().toString());
    }

    private TreePath nearestClass(TreePath path) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree) {
                return cursor;
            }
        }
        return null;
    }

    /** Resolve visible local variables and parameters without treating class fields as locals. */
    private Optional<ResolvedSymbol> resolveVisibleDeclaration(
            ParseTask parse, ParseTypeResolver types, String name) {
        return types.resolveVisibleDeclaration(name)
                .filter(local -> !(parentLeaf(local) instanceof ClassTree))
                .map(local -> localDeclaration(parse, local, name));
    }

    private ResolvedSymbol localDeclaration(ParseTask parse, TreePath declarationPath, String name) {
        var location = FindHelper.location(parse, declarationPath, name);
        if (location == null) {
            return unsupported(name);
        }
        return new ResolvedSymbol(List.of(location), null, name, false, null, name);
    }

    private Optional<ResolvedSymbol> resolveEnclosingField(ParseTypeResolver types, String fieldName) {
        var ownerType = types.currentEnclosingTypeName();
        if (ownerType.isEmpty()) {
            return Optional.empty();
        }
        var member = typeIndexRouter.member(ownerType.get(), fieldName, false)
                .filter(this::isFieldLikeMember)
                .orElse(null);
        if (member == null) {
            member = typeIndexRouter.member(ownerType.get(), fieldName, true)
                    .filter(this::isFieldLikeMember)
                    .orElse(null);
        }
        if (member == null) {
            return Optional.empty();
        }
        var indexedField = member.declarationLocation();
        if (indexedField.isPresent() && !hasBackingField(member)) {
            return Optional.of(
                    new ResolvedSymbol(
                            List.of(indexedField.get()),
                            indexedOwner(ownerType.get(), member),
                            fieldName,
                            false,
                            member,
                            fieldName));
        }
        return Optional.of(resolveField(ownerType.get(), fieldName, member));
    }

    private Optional<ResolvedSymbol> resolveInheritedField(String fieldName, ParseTypeResolver types) {
        return types.resolveInheritedFieldMember(fieldName)
                .map(
                        member -> {
                            var indexedField = member.declarationLocation();
                            if (indexedField.isPresent() && !hasBackingField(member)) {
                                return new ResolvedSymbol(
                                        List.of(indexedField.get()),
                                        indexedOwner(member.ownerType, member),
                                        fieldName,
                                        false,
                                        member,
                                        fieldName);
                            }
                            return resolveField(member.ownerType, fieldName, member);
                        });
    }

    private Optional<String> enclosingOwnerType(ParseTask parse, TreePath path) {
        var classPath = nearestClass(path);
        return classPath == null ? Optional.empty() : Optional.of(declaredClassName(parse, classPath));
    }

    private String declaredClassName(ParseTask parse, TreePath path) {
        var classes = new java.util.ArrayList<String>();
        for (var current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof ClassTree classTree) {
                classes.add(classTree.getSimpleName().toString());
            }
        }
        java.util.Collections.reverse(classes);
        var packageName = parse.root().getPackageName() == null ? "" : parse.root().getPackageName().toString();
        return packageName.isEmpty() ? String.join(".", classes) : packageName + "." + String.join(".", classes);
    }

    private Optional<String> resolveEnclosingNestedType(ParseTask parse, TreePath path, String simpleName) {
        if (path == null || simpleName == null || simpleName.isBlank()) {
            return Optional.empty();
        }
        for (var current = nearestClass(path); current != null; current = nearestClass(current.getParentPath())) {
            var ownerType = declaredClassName(parse, current);
            var nested = typeIndexRouter.workspaceNestedType(ownerType, simpleName);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private Optional<IndexedMember> lookupMethod(
            String ownerType,
            String methodName,
            boolean staticContext,
            int argumentCount,
            List<String> argTypes,
            boolean strictArity) {
        if (argTypes.isEmpty() || argTypes.stream().noneMatch(String::isBlank)) {
            var withArgs =
                    typeIndexRouter.member(
                            ownerType,
                            methodName,
                            staticContext,
                            argTypes.toArray(String[]::new));
            var method = withArgs.filter(this::isMethodMember);
            if (method.isPresent()) {
                return method;
            }
        }
        var byArity = uniqueMethodByArity(ownerType, methodName, staticContext, argumentCount);
        if (byArity.isPresent()) {
            return byArity;
        }
        if (strictArity) {
            return Optional.empty();
        }
        return typeIndexRouter.member(ownerType, methodName, staticContext)
                .filter(this::isMethodMember);
    }

    private Optional<IndexedMember> uniqueMethodByArity(
            String ownerType, String methodName, boolean staticContext, int argumentCount) {
        if (ownerType == null || ownerType.isBlank() || methodName == null || methodName.isBlank() || argumentCount < 0) {
            return Optional.empty();
        }
        IndexedMember match = null;
        for (var candidate : typeIndexRouter.members(ownerType, staticContext)) {
            if (!isMethodMember(candidate)
                    || !methodName.equals(candidate.name)
                    || candidate.isStatic != staticContext) {
                continue;
            }
            var arity = candidate.erasedParameterTypes == null ? 0 : candidate.erasedParameterTypes.length;
            if (arity != argumentCount) {
                continue;
            }
            if (match != null && !java.util.Objects.equals(match.canonicalKey, candidate.canonicalKey)) {
                return Optional.empty();
            }
            match = candidate;
        }
        return Optional.ofNullable(match);
    }

    private Tree parentLeaf(TreePath path) {
        return path.getParentPath() == null ? null : path.getParentPath().getLeaf();
    }

    private ResolvedSymbol unsupported(String simpleName) {
        return new ResolvedSymbol(NOT_SUPPORTED, null, null, false, null, simpleName);
    }

    private ResolvedSymbol unsupportedMethod(String methodName) {
        return new ResolvedSymbol(NOT_SUPPORTED, null, methodName, true, null, methodName);
    }

    private ResolvedSymbol resolveField(
            String ownerType, String fieldName, IndexedMember member) {
        var targetOwner = indexedOwner(ownerType, member);
        var indexedLinked = indexedLinkedFieldLocation(member);
        if (indexedLinked.isPresent()) {
            return new ResolvedSymbol(
                    List.of(indexedLinked.get()),
                    linkedFieldOwner(targetOwner, member),
                    member.backingFieldName,
                    false,
                    member,
                    member.backingFieldName);
        }
        if (member != null) {
            var indexedField = member.declarationLocation();
            if (indexedField.isPresent()) {
                return new ResolvedSymbol(
                        List.of(indexedField.get()),
                        targetOwner,
                        fieldName,
                        false,
                        member,
                        fieldName);
            }
        }
        if (hasBackingField(member)) {
            var linkedOwner = linkedFieldOwner(targetOwner, member);
            var linkedField = member.backingFieldName;
            var linkedLocations = findFieldLocations(linkedOwner, linkedField);
            if (!linkedLocations.isEmpty()) {
                return new ResolvedSymbol(
                        linkedLocations,
                        linkedOwner,
                        linkedField,
                        false,
                        member,
                        linkedField);
            }
        }
        var fieldLocations = findFieldLocations(targetOwner, fieldName);
        return new ResolvedSymbol(
                fieldLocations.isEmpty() ? NOT_SUPPORTED : fieldLocations,
                targetOwner,
                fieldName,
                false,
                member,
                fieldName);
    }

    private Optional<IndexedMember> indexedMethodDeclaration(
            ParseTask parse, TreePath path, String ownerType, String methodName, MethodTree method) {
        if (ownerType == null || method.getName().contentEquals("<init>")) {
            return Optional.empty();
        }
        var parameterTypes =
                NavigationSymbolSupport.declaredParameterTypes(
                        parse, path, method, typeIndexRouter, compiler);
        if (parameterTypes.size() != method.getParameters().size()) {
            return Optional.empty();
        }
        var erased = parameterTypes.toArray(String[]::new);
        return typeIndexRouter.member(ownerType, methodName, false, erased)
                .or(() -> typeIndexRouter.member(ownerType, methodName, true, erased));
    }

    private boolean isFieldLikeMember(IndexedMember member) {
        if (member == null) {
            return false;
        }
        return member.kind == CompletionItemKind.Field;
    }

    private boolean isMethodMember(IndexedMember member) {
        if (member == null) {
            return false;
        }
        return member.kind == CompletionItemKind.Method;
    }

    private boolean hasBackingField(IndexedMember member) {
        return member != null
                && member.backingFieldName != null
                && !member.backingFieldName.isBlank();
    }

    // Location opening and declaration matching.
    private List<Location> findMethodLocations(
            String ownerType, String methodName, int argCount, List<String> argTypes) {
        var source = openTypeSource(ownerType);
        if (source.isPresent()) {
            var locations = locateMethod(source.get(), methodName, argCount, argTypes);
            if (!locations.isEmpty()) {
                return locations;
            }
        }
        var decompiled = openDecompiledTypeSource(ownerType);
        return decompiled
                .map(typeSource -> locateMethod(typeSource, methodName, argCount, argTypes))
                .orElseGet(List::of);
    }

    private List<Location> findFieldLocations(String ownerType, String fieldName) {
        var attached = findAttachedSourceFieldLocations(ownerType, fieldName);
        if (!attached.isEmpty()) {
            return attached;
        }
        var source = openTypeSource(ownerType);
        if (source.isPresent()) {
            var locations = locateField(source.get(), fieldName);
            if (!locations.isEmpty()) {
                return locations;
            }
        }
        var decompiled = openDecompiledTypeSource(ownerType);
        return decompiled.map(typeSource -> locateField(typeSource, fieldName)).orElseGet(List::of);
    }

    private List<Location> findAttachedSourceFieldLocations(String ownerType, String fieldName) {
        if (typeIndexRouter.isWorkspaceOwnedType(ownerType)) {
            return List.of();
        }
        var sourceFile = attachedExternalSources.get(ownerType);
        if (sourceFile == null) {
            var source = compiler.findAnywhere(ownerType);
            if (source.isEmpty() || source.get().getKind() != JavaFileObject.Kind.SOURCE) {
                return List.of();
            }
            attachedExternalSources.put(ownerType, source.get());
            sourceFile = source.get();
        }
        return findFieldLocationsInFile(sourceFile, fieldName);
    }

    private List<Location> findConstructorLocations(String ownerType, int argCount) {
        var source = openTypeSource(ownerType);
        if (source.isEmpty()) {
            return List.of();
        }
        var parse = source.get().task;
        var classPath = source.get().classPath;
        var classTree = (ClassTree) classPath.getLeaf();
        var constructorName = classTree.getSimpleName().toString();
        var results = new ArrayList<Location>();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof MethodTree method)) {
                continue;
            }
            if (method.getReturnType() != null) {
                continue;
            }
            if (argCount >= 0 && method.getParameters().size() != argCount) {
                continue;
            }
            var path = new TreePath(classPath, method);
            var location = FindHelper.location(parse, path, constructorName);
            if (location != null) {
                results.add(location);
            }
        }
        return results;
    }

    private List<Location> findTypeLocations(String qualifiedType, String labelName) {
        var source = openTypeSource(qualifiedType);
        if (source.isPresent()) {
            var locations = locateType(source.get(), labelName);
            if (!locations.isEmpty()) {
                return locations;
            }
        }
        var decompiled = openDecompiledTypeSource(qualifiedType);
        return decompiled.map(typeSource -> locateType(typeSource, labelName)).orElseGet(List::of);
    }

    private List<Location> locateMethod(
            TypeSource source, String methodName, int argCount, List<String> argTypes) {
        var parse = source.task;
        var classPath = source.classPath;
        var classTree = (ClassTree) classPath.getLeaf();
        var methods = new ArrayList<MethodTree>();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof MethodTree method)) {
                continue;
            }
            if (!method.getName().contentEquals(methodName)) {
                continue;
            }
            if (argCount >= 0 && method.getParameters().size() != argCount) {
                continue;
            }
            methods.add(method);
        }
        if (methods.isEmpty()) {
            return List.of();
        }
        var selected = methods;
        if (argCount >= 0 && !argTypes.isEmpty() && argTypes.stream().noneMatch(String::isBlank)) {
            var exact = new ArrayList<MethodTree>();
            for (var method : methods) {
                if (matchesArgumentTypes(parse, classPath, method, argTypes)) {
                    exact.add(method);
                }
            }
            if (!exact.isEmpty()) {
                selected = exact;
            }
        }
        var dedupe = new LinkedHashMap<String, Location>();
        for (var method : selected) {
            var path = new TreePath(classPath, method);
            var location = FindHelper.location(parse, path, methodName);
            if (location == null) {
                continue;
            }
            var key =
                    location.uri
                            + ":"
                            + location.range.start.line
                            + ":"
                            + location.range.start.character;
            dedupe.putIfAbsent(key, location);
        }
        return new ArrayList<>(dedupe.values());
    }

    private List<Location> locateField(TypeSource source, String fieldName) {
        var parse = source.task;
        var classPath = source.classPath;
        var classTree = (ClassTree) classPath.getLeaf();
        if (classTree.getKind() == Tree.Kind.RECORD) {
            var recordComponent = findRecordComponentLocation(parse, classPath, fieldName);
            if (recordComponent != null) {
                return List.of(recordComponent);
            }
        }
        for (var member : classTree.getMembers()) {
            if (!(member instanceof VariableTree variable)) {
                continue;
            }
            if (!variable.getName().contentEquals(fieldName)) {
                continue;
            }
            var path = new TreePath(classPath, variable);
            var location = FindHelper.location(parse, path, fieldName);
            if (location != null) {
                return List.of(location);
            }
        }
        return List.of();
    }

    private List<Location> locateType(TypeSource source, String labelName) {
        var location = FindHelper.location(source.task, source.classPath, labelName);
        if (location == null) {
            location = FindHelper.location(source.task, source.classPath);
        }
        return location == null ? List.of() : List.of(location);
    }

    /**
     * Open the source that defines a known owner type.
     *
     * <p>Workspace owners come from the workspace index. External owners may come from attached
     * source first and decompiled source later if no source file is available.
     */
    private Optional<TypeSource> openTypeSource(String qualifiedType) {
        var workspace = typeIndexRouter.workspace().typeInfo(qualifiedType).orElse(null);
        if (workspace != null && workspace.sourcePath != null) {
            var parse = compiler.parse(workspace.sourcePath);
            var path = findTypePath(parse, qualifiedType);
            if (path.isPresent()) {
                return Optional.of(new TypeSource(parse, path.get()));
            }
        }
        if (typeIndexRouter.isWorkspaceOwnedType(qualifiedType)) {
            return Optional.empty();
        }
        var source = compiler.findAnywhere(qualifiedType);
        if (source.isPresent() && source.get().getKind() == JavaFileObject.Kind.SOURCE) {
            attachedExternalSources.put(qualifiedType, source.get());
            var parse = compiler.parse(source.get());
            var path = findTypePath(parse, qualifiedType);
            if (path.isPresent()) {
                return Optional.of(new TypeSource(parse, path.get()));
            }
        }
        for (var i = qualifiedType.lastIndexOf('.'); i > 0; i = qualifiedType.lastIndexOf('.', i - 1)) {
            var outer = qualifiedType.substring(0, i);
            if (typeIndexRouter.isWorkspaceOwnedType(outer)) {
                continue;
            }
            var outerSource = compiler.findAnywhere(outer);
            if (outerSource.isEmpty() || outerSource.get().getKind() != JavaFileObject.Kind.SOURCE) {
                continue;
            }
            attachedExternalSources.put(outer, outerSource.get());
            var parse = compiler.parse(outerSource.get());
            var path = findTypePath(parse, qualifiedType);
            if (path.isPresent()) {
                return Optional.of(new TypeSource(parse, path.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<TypeSource> openDecompiledTypeSource(String qualifiedType) {
        var decompiledSource = typeIndexRouter.externalDecompiledSourcePath(qualifiedType);
        if (decompiledSource.isEmpty()) {
            return Optional.empty();
        }
        var parse = compiler.parse(decompiledSource.get());
        var path = findTypePath(parse, qualifiedType);
        return path.map(classPath -> new TypeSource(parse, classPath));
    }

    private Optional<TreePath> findTypePath(ParseTask parse, String qualifiedType) {
        var declared = declaredClassPath(parse, qualifiedType);
        if (declared.isPresent()) {
            return declared;
        }
        for (var declaration : parse.root().getTypeDecls()) {
            if (!(declaration instanceof ClassTree classTree)) {
                continue;
            }
            var path = Trees.instance(parse.task()).getPath(parse.root(), classTree);
            if (path != null) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    private Optional<TreePath> declaredClassPath(ParseTask parse, String qualifiedType) {
        final TreePath[] match = {null};
        new com.sun.source.util.TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                var current = getCurrentPath();
                if (current != null && qualifiedType.equals(declaredClassName(parse, current))) {
                    match[0] = current;
                    return null;
                }
                return super.visitClass(classTree, unused);
            }
        }.scan(parse.root(), null);
        return Optional.ofNullable(match[0]);
    }

    private boolean matchesArgumentTypes(
            ParseTask parse, TreePath classPath, MethodTree method, List<String> argTypes) {
        if (method.getParameters().size() != argTypes.size()) {
            return false;
        }
        if (argTypes.isEmpty() || argTypes.stream().anyMatch(String::isBlank)) {
            return false;
        }
        var parameterTypes =
                NavigationSymbolSupport.declaredParameterTypes(
                        parse, new TreePath(classPath, method), method, typeIndexRouter, compiler);
        if (parameterTypes.size() != argTypes.size()) {
            return false;
        }
        for (int i = 0; i < argTypes.size(); i++) {
            if (!parameterTypes.get(i).equals(argTypes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Location findRecordComponentLocation(ParseTask parse, TreePath classPath, String fieldName) {
        var positions = Trees.instance(parse.task()).getSourcePositions();
        var root = classPath.getCompilationUnit();
        var start = (int) positions.getStartPosition(root, classPath.getLeaf());
        var end = (int) positions.getEndPosition(root, classPath.getLeaf());
        if (start < 0 || end < start) {
            return null;
        }
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        var bodyStart = -1;
        for (int i = start; i <= end && i < contents.length(); i++) {
            if (contents.charAt(i) == '{') {
                bodyStart = i;
                break;
            }
        }
        if (bodyStart < 0) {
            return null;
        }
        var componentStart = FindHelper.findNameIn(root, fieldName, start, bodyStart);
        if (componentStart < 0) {
            return null;
        }
        var range = FileStore.range(contents.toString(), componentStart, componentStart + fieldName.length());
        return new Location(root.getSourceFile().toUri(), range);
    }

    private List<Location> findFieldLocationsInFile(JavaFileObject sourceFile, String fieldName) {
        if (sourceFile == null || fieldName == null || fieldName.isBlank()) {
            return List.of();
        }
        try {
            var contents = sourceFile.getCharContent(true).toString();
            var matcher = Pattern.compile("\\b" + Pattern.quote(fieldName) + "\\b").matcher(contents);
            if (!matcher.find()) {
                return List.of();
            }
            var range = FileStore.range(contents, matcher.start(), matcher.end());
            var uri = FindHelper.normalizeLocationUri(sourceFile.toUri());
            return List.of(new Location(uri, range));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String indexedOwner(String ownerType, IndexedMember member) {
        if (member != null && member.ownerType != null && !member.ownerType.isBlank()) {
            return member.ownerType;
        }
        return ownerType;
    }

    private String linkedFieldOwner(String ownerType, IndexedMember member) {
        if (member != null && member.logicalKey != null && !member.logicalKey.isBlank()) {
            var split = member.logicalKey.indexOf('#');
            if (split > 0) {
                return member.logicalKey.substring(0, split);
            }
        }
        return ownerType;
    }

    private Optional<Location> indexedLinkedFieldLocation(
            IndexedMember member) {
        if (member == null || !hasBackingField(member) || member.logicalKey == null || member.logicalKey.isBlank()) {
            return Optional.empty();
        }
        return typeIndexRouter.workspace()
                .memberByCanonicalKey(member.logicalKey)
                .flatMap(IndexedMember::declarationLocation);
    }

}
