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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.FindNameAt;
import org.javacs.ParseTask;
import org.javacs.completion.TypeIndexRouter;
import org.javacs.completion.WorkspaceTypeIndex;
import org.javacs.lsp.Location;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.resolve.ParseTypeResolver;

/**
 * Resolves go-to-definition targets in three steps:
 *
 * <ol>
 *   <li>Classify the tree under the cursor by syntax shape.
 *   <li>Resolve the symbol identity using parse-time type information plus the workspace/external
 *       indexes.
 *   <li>Delegate declaration opening to {@link DefinitionLocationLookup}.
 * </ol>
 *
 * <p>The lookup order is intentionally shallow so each fallback is visible while debugging:
 * declarations first, then identifier uses, member selects, member references, constructors, and
 * finally unsupported nodes.
 */
public class DefinitionProvider {
    public static final List<Location> NOT_SUPPORTED = List.of();
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final TypeIndexRouter completionIndex;
    private final DefinitionLocationLookup locations;
    private final Path file;
    private final int line;
    private final int column;

    public record ResolvedSymbol(
            List<Location> locations,
            String qualifiedType,
            String memberName,
            boolean method,
            WorkspaceTypeIndex.Member indexMember,
            String simpleName) {}

    public DefinitionProvider(CompilerProvider compiler, Path file, int line, int column) {
        this(compiler, TypeIndexRouter.EMPTY, file, line, column);
    }

    public DefinitionProvider(
            CompilerProvider compiler,
            TypeIndexRouter completionIndex,
            Path file,
            int line,
            int column) {
        this.compiler = compiler;
        this.completionIndex = completionIndex == null ? TypeIndexRouter.EMPTY : completionIndex;
        this.locations = new DefinitionLocationLookup(compiler, this.completionIndex);
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
        return resolve(parse, path, cursor);
    }

    ResolvedSymbol resolve(ParseTask parse, TreePath path, long cursor) {
        return resolve(parse, path, new ParseTypeResolver(parse, compiler, completionIndex, cursor));
    }

    private ResolvedSymbol resolve(ParseTask parse, TreePath path, ParseTypeResolver types) {
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
        return resolveTypeTree(parse, identifier, name);
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
        var receiver = types.resolveExpression(memberSelect.getExpression());
        var parent = parentLeaf(path);
        if (parent instanceof MethodInvocationTree invocation
                && invocation.getMethodSelect() == memberSelect) {
            if (receiver.isEmpty()) {
                return unsupportedMethod(name);
            }
            return resolveQualifiedMethodInvocation(
                    types, receiver.get(), name, invocation.getArguments());
        }
        if (receiver.isPresent()) {
            var member =
                    lookupField(
                            receiver.get().qualifiedType(), name, receiver.get().staticContext());
            if (member != null) {
                return resolveField(receiver.get().qualifiedType(), name, member);
            }
            var nestedType =
                    completionIndex.workspaceNestedType(receiver.get().qualifiedType(), name)
                            .map(qualifiedType -> resolveTypeName(qualifiedType, name));
            if (nestedType.isPresent()) {
                return nestedType.get();
            }
        }
        return resolveTypeTree(parse, memberSelect, name);
    }

    private ResolvedSymbol resolveMemberReference(
            MemberReferenceTree memberReference,
            ParseTypeResolver types) {
        var name = memberReference.getName().toString();
        var receiver = types.resolveExpression(memberReference.getQualifierExpression());
        if (receiver.isEmpty()) {
            return unsupported(name);
        }
        if ("new".equals(name)) {
            var ownerType = receiver.get().qualifiedType();
            return new ResolvedSymbol(
                    locations.findConstructorLocations(ownerType, -1),
                    ownerType,
                    simpleName(ownerType),
                    true,
                    null,
                    simpleName(ownerType));
        }
        return resolveQualifiedMethodInvocation(types, receiver.get(), name, List.of());
    }

    private ResolvedSymbol resolveUnqualifiedMethodInvocation(
            ParseTask parse, ParseTypeResolver types, MethodInvocationTree invocation, String methodName) {
        var argTypes = NavigationSymbolSupport.argumentTypes(invocation.getArguments(), types);
        var owner = types.currentEnclosingTypeName();
        if (owner.isPresent()) {
            var member = lookupMethod(owner.get(), methodName, false, argTypes).orElse(null);
            var resolved = resolveMethod(owner.get(), methodName, invocation.getArguments().size(), argTypes, member);
            if (!resolved.locations().isEmpty()) {
                return resolved;
            }
        }
        return resolveStaticImportMethod(parse.root(), methodName, invocation.getArguments().size(), argTypes)
                .orElseGet(() -> unsupportedMethod(methodName));
    }

    private ResolvedSymbol resolveQualifiedMethodInvocation(
            ParseTypeResolver types,
            ParseTypeResolver.TypeResolution receiver,
            String methodName,
            List<? extends ExpressionTree> arguments) {
        var argTypes = NavigationSymbolSupport.argumentTypes(arguments, types);
        var member =
                lookupMethod(
                                receiver.qualifiedType(),
                                methodName,
                                receiver.staticContext(),
                                argTypes)
                        .orElse(null);
        return resolveMethod(
                receiver.qualifiedType(),
                methodName,
                arguments.size(),
                argTypes,
                member);
    }

    private ResolvedSymbol resolveMethod(
            String ownerType,
            String methodName,
            int argCount,
            List<String> argTypes,
            WorkspaceTypeIndex.Member member) {
        var target = locations.resolveMethodTarget(ownerType, methodName, argCount, argTypes, member);
        return resolvedTarget(target, member);
    }

    private ResolvedSymbol resolveAnnotation(ParseTask parse, AnnotationTree annotation) {
        var annotationType = annotation.getAnnotationType();
        if (annotationType == null) {
            return unsupported(null);
        }
        var simpleName = annotationType instanceof MemberSelectTree memberSelect
                ? memberSelect.getIdentifier().toString()
                : annotationType.toString();
        return resolveTypeTree(parse, annotationType, simpleName);
    }

    private ResolvedSymbol resolveTypeTree(ParseTask parse, Tree typeTree, String simpleName) {
        return completionIndex.resolveTypeName(typeTree.toString(), parse.root())
                .map(qualifiedType -> resolveTypeName(qualifiedType, simpleName))
                .orElseGet(() -> unsupported(simpleName));
    }

    private ResolvedSymbol resolveConstructorInvocation(
            ParseTask parse, NewClassTree newClassTree, ParseTypeResolver types, String simpleName) {
        var ownerType =
                types.resolveTypeTree(newClassTree.getIdentifier(), true)
                        .map(ParseTypeResolver.TypeResolution::qualifiedType)
                        .or(() -> completionIndex.resolveTypeName(newClassTree.getIdentifier().toString(), parse.root()));
        if (ownerType.isEmpty()) {
            return unsupported(simpleName);
        }

        var currentType = types.currentEnclosingTypeName();
        if (currentType.isPresent() && currentType.get().equals(ownerType.get())) {
            return resolveTypeName(ownerType.get(), simpleName);
        }

        var constructors =
                locations.findConstructorLocations(ownerType.get(), newClassTree.getArguments().size());
        if (!constructors.isEmpty()) {
            return new ResolvedSymbol(constructors, ownerType.get(), simpleName, true, null, simpleName);
        }
        return resolveTypeName(ownerType.get(), simpleName);
    }

    private ResolvedSymbol resolveTypeName(String qualifiedType, String simpleName) {
        var typeLocations = locations.findTypeLocation(qualifiedType, simpleName);
        if (typeLocations.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, qualifiedType, null, false, null, simpleName);
        }
        return new ResolvedSymbol(typeLocations, qualifiedType, null, false, null, simpleName);
    }

    private Optional<ResolvedSymbol> resolveStaticImportField(ParseTask parse, String fieldName) {
        ResolvedSymbol match = null;
        for (var ownerType : WorkspaceTypeIndex.staticImportOwnerTypes(fieldName, parse.root())) {
            var member = lookupField(ownerType, fieldName, true);
            var resolved = resolveField(ownerType, fieldName, member);
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

    private Optional<ResolvedSymbol> resolveStaticImportMethod(
            com.sun.source.tree.CompilationUnitTree root,
            String methodName,
            int argCount,
            List<String> argTypes) {
        ResolvedSymbol match = null;
        for (var ownerType : WorkspaceTypeIndex.staticImportOwnerTypes(methodName, root)) {
            var member = lookupMethod(ownerType, methodName, true, argTypes).orElse(null);
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

        var staticMember = lookupField(ownerType.get(), fieldName, true);
        var resolved = resolveField(ownerType.get(), fieldName, staticMember);
        if (!resolved.locations().isEmpty()) {
            return Optional.of(resolved);
        }

        var instanceMember = lookupField(ownerType.get(), fieldName, false);
        resolved = resolveField(ownerType.get(), fieldName, instanceMember);
        if (!resolved.locations().isEmpty()) {
            return Optional.of(resolved);
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
            var member = completionIndex.ownerMember(ownerType, variable.getName().toString(), false, compiler)
                    .or(() -> completionIndex.ownerMember(ownerType, variable.getName().toString(), true, compiler))
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

    private ResolvedSymbol resolvedTarget(
            DefinitionLocationLookup.MemberTarget target, WorkspaceTypeIndex.Member member) {
        if (target == null) {
            return unsupported(null);
        }
        return new ResolvedSymbol(
                target.locations().isEmpty() ? NOT_SUPPORTED : target.locations(),
                target.ownerType(),
                target.memberName(),
                target.method(),
                member,
                target.memberName());
    }

    private TreePath nearestClass(TreePath path) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree) {
                return cursor;
            }
        }
        return null;
    }

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
        var member = lookupField(ownerType.get(), fieldName, false);
        if (member == null) {
            member = lookupField(ownerType.get(), fieldName, true);
        }
        if (member == null) {
            return Optional.empty();
        }
        return Optional.of(resolveField(ownerType.get(), fieldName, member));
    }

    private Optional<ResolvedSymbol> resolveInheritedField(String fieldName, ParseTypeResolver types) {
        return types.resolveInheritedFieldMember(fieldName)
                .map(member -> resolveField(member.ownerType, fieldName, member));
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

    private WorkspaceTypeIndex.Member lookupField(
            String ownerType, String fieldName, boolean staticContext) {
        return completionIndex.ownerMember(ownerType, fieldName, staticContext, compiler)
                .map(member -> preferWorkspaceMember(ownerType, member))
                .filter(this::isFieldLikeMember)
                .orElse(null);
    }

    private Optional<WorkspaceTypeIndex.Member> lookupMethod(
            String ownerType, String methodName, boolean staticContext, List<String> argTypes) {
        if (NavigationSymbolSupport.hasResolvedTypes(argTypes)) {
            var withArgs =
                    completionIndex.ownerMember(
                            ownerType,
                            methodName,
                            staticContext,
                            argTypes.toArray(String[]::new),
                            compiler);
            var method = withArgs.map(member -> preferWorkspaceMember(ownerType, member)).filter(this::isMethodMember);
            if (method.isPresent()) {
                return method;
            }
        }
        return completionIndex.ownerMember(ownerType, methodName, staticContext, compiler)
                .map(member -> preferWorkspaceMember(ownerType, member))
                .filter(this::isMethodMember);
    }

    private WorkspaceTypeIndex.Member preferWorkspaceMember(
            String ownerType, WorkspaceTypeIndex.Member member) {
        if (member == null || member.canonicalKey == null || member.canonicalKey.isBlank()) {
            return member;
        }
        if (!completionIndex.isWorkspaceOwnedType(ownerType, compiler)
                && (member.ownerType == null || !completionIndex.isWorkspaceOwnedType(member.ownerType, compiler))) {
            return member;
        }
        var workspaceMember = completionIndex.workspace().memberByCanonicalKey(member.canonicalKey).orElse(null);
        if (workspaceMember == null) {
            return member;
        }
        var memberHasFieldLink =
                member.backingFieldName != null
                        && !member.backingFieldName.isBlank()
                        && member.logicalKey != null
                        && !member.logicalKey.isBlank()
                        && !member.logicalKey.equals(member.canonicalKey);
        var workspaceHasFieldLink =
                workspaceMember.backingFieldName != null
                        && !workspaceMember.backingFieldName.isBlank()
                        && workspaceMember.logicalKey != null
                        && !workspaceMember.logicalKey.isBlank()
                        && !workspaceMember.logicalKey.equals(workspaceMember.canonicalKey);
        if (!memberHasFieldLink && workspaceHasFieldLink) {
            return workspaceMember;
        }
        return workspaceMember;
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
            String ownerType, String fieldName, WorkspaceTypeIndex.Member member) {
        return resolvedTarget(locations.resolveFieldTarget(ownerType, fieldName, member), member);
    }

    private Optional<WorkspaceTypeIndex.Member> indexedMethodDeclaration(
            ParseTask parse, TreePath path, String ownerType, String methodName, MethodTree method) {
        if (ownerType == null || method.getName().contentEquals("<init>")) {
            return Optional.empty();
        }
        var parameterTypes =
                NavigationSymbolSupport.declaredParameterTypes(
                        parse, path, method, completionIndex, compiler);
        if (parameterTypes.size() != method.getParameters().size()) {
            return Optional.empty();
        }
        var erased = parameterTypes.toArray(String[]::new);
        return completionIndex.ownerMember(ownerType, methodName, false, erased, compiler)
                .or(() -> completionIndex.ownerMember(ownerType, methodName, true, erased, compiler));
    }

    private static String simpleName(String qualifiedType) {
        return NavigationSymbolSupport.simpleTypeName(qualifiedType);
    }

    private boolean isFieldLikeMember(WorkspaceTypeIndex.Member member) {
        if (member == null) {
            return false;
        }
        return member.kind == CompletionItemKind.Field;
    }

    private boolean isMethodMember(WorkspaceTypeIndex.Member member) {
        if (member == null) {
            return false;
        }
        return member.kind == CompletionItemKind.Method;
    }
}
