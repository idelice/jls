package org.javacs.navigation;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.FindNameAt;
import org.javacs.ParseTask;
import org.javacs.TypeLookupBoundary;
import org.javacs.completion.CompositeTypeIndex;
import org.javacs.completion.TypeMemberIndex;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;

public class DefinitionProvider {
    private final CompilerProvider compiler;
    private final CompositeTypeIndex completionIndex;
    private final TypeLookupBoundary typeLookup;
    private final Path file;
    private final int line;
    private final int column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public record ResolvedSymbol(
            List<Location> locations,
            String qualifiedType,
            String memberName,
            boolean method,
            TypeMemberIndex.Member indexMember,
            String simpleName) {}

    public DefinitionProvider(CompilerProvider compiler, Path file, int line, int column) {
        this(compiler, CompositeTypeIndex.EMPTY, file, line, column);
    }

    public DefinitionProvider(CompilerProvider compiler, CompositeTypeIndex completionIndex, Path file, int line, int column) {
        this.compiler = compiler;
        this.completionIndex = completionIndex == null ? CompositeTypeIndex.EMPTY : completionIndex;
        this.typeLookup = new TypeLookupBoundary(compiler, this.completionIndex);
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
            cursor = FileStore.offset(parse.root.getSourceFile().getCharContent(true).toString(), line, column);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        var path = new FindNameAt(parse).scan(parse.root, cursor);
        if (path == null) return new ResolvedSymbol(NOT_SUPPORTED, null, null, false, null, null);
        return resolve(parse, path, cursor);
    }

    ResolvedSymbol resolve(ParseTask parse, TreePath path, long cursor) {
        var resolver = new ParseTypeResolver(parse, completionIndex, compiler, cursor);
        return resolve(parse, path, resolver);
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
        if (leaf instanceof IdentifierTree identifier) {
            return resolveIdentifier(parse, path, identifier, types);
        }
        if (leaf instanceof MemberSelectTree memberSelect) {
            return resolveMemberSelect(parse, path, memberSelect, types);
        }
        if (leaf instanceof MemberReferenceTree memberReference) {
            return resolveMemberReference(path, memberReference, types);
        }
        if (leaf instanceof NewClassTree newClassTree) {
            return resolveConstructorInvocation(parse, newClassTree, types, newClassTree.getIdentifier().toString());
        }
        return new ResolvedSymbol(NOT_SUPPORTED, null, null, false, null, null);
    }

    private ResolvedSymbol resolveIdentifier(ParseTask parse, TreePath path, IdentifierTree identifier, ParseTypeResolver types) {
        var name = identifier.getName().toString();
        var parent = path.getParentPath() != null ? path.getParentPath().getLeaf() : null;

        if (parent instanceof MethodInvocationTree invocation && invocation.getMethodSelect() == identifier) {
            return resolveUnqualifiedMethodInvocation(parse, types, invocation, name);
        }

        if (parent instanceof NewClassTree newClassTree && newClassTree.getIdentifier() == identifier) {
            return resolveConstructorInvocation(parse, newClassTree, types, name);
        }

        var local = types.resolveVisibleDeclaration(name);
        if (local.isPresent()) {
            if (local.get().getLeaf() instanceof VariableTree variable
                    && local.get().getParentPath() != null
                    && local.get().getParentPath().getLeaf() instanceof ClassTree) {
                return variableDeclaration(parse, local.get(), variable);
            }
            var location = FindHelper.location(parse, local.get(), name);
            if (location != null) {
                return new ResolvedSymbol(List.of(location), null, name, false, null, name);
            }
        }

        var field = findFieldInEnclosingClass(parse, path, name);
        if (field.isPresent()) {
            var location = FindHelper.location(parse, field.get(), name);
            if (location != null) {
                var owner = qualifiedClassName(parse, nearestClass(field.get()));
                return resolvedFieldSymbol(owner, name, List.of(location));
            }
        }
        var inheritedField = types.resolveInheritedFieldMember(name);
        if (inheritedField.isPresent()) {
            return resolveFieldFromMember(inheritedField.get().ownerType, name, inheritedField.get());
        }

        var typeParameter = findTypeParameterInEnclosingScopes(path, name);
        if (typeParameter.isPresent()) {
            var location = FindHelper.location(parse, typeParameter.get(), name);
            if (location != null) {
                return new ResolvedSymbol(List.of(location), null, name, false, null, name);
            }
        }

        var nestedType = findNestedTypeInEnclosingScopes(parse, path, name);
        if (nestedType.isPresent()) {
            return resolveTypeName(nestedType.get(), name);
        }

        var staticImportField = resolveStaticImportField(parse, name);
        if (staticImportField.isPresent()) {
            return staticImportField.get();
        }

        var switchCaseField = resolveSwitchCaseField(path, name, types);
        if (switchCaseField.isPresent()) {
            return switchCaseField.get();
        }

        return resolveTypeTree(parse, identifier, name);
    }

    private ResolvedSymbol resolveMemberSelect(
            ParseTask parse, TreePath path, MemberSelectTree memberSelect, ParseTypeResolver types) {
        var name = memberSelect.getIdentifier().toString();
        var parent = path.getParentPath() != null ? path.getParentPath().getLeaf() : null;
        if (parent instanceof MethodInvocationTree invocation && invocation.getMethodSelect() == memberSelect) {
            return resolveQualifiedMethodInvocation(types, memberSelect.getExpression(), name, invocation.getArguments());
        }

        var receiver = types.resolveExpression(memberSelect.getExpression());
        if (receiver.isPresent()) {
            var member = lookupMember(receiver.get().qualifiedType, name, receiver.get().staticContext);
            if (member.isPresent()) {
                var selected = member.get();
                if (selected.kind == CompletionItemKind.Method || selected.kind == CompletionItemKind.Constructor) {
                    return resolveMethodFromMember(selected.ownerType, name, -1, List.of(), selected);
                }
                return resolveFieldFromMember(selected.ownerType, name, selected);
            }

            var fields = findFieldLocations(receiver.get().qualifiedType, name);
            if (!fields.isEmpty()) {
                return new ResolvedSymbol(fields, receiver.get().qualifiedType, name, false, null, name);
            }

            var nestedType = receiver.get().qualifiedType + "." + name;
            if (typeLookup.resolveWorkspaceNestedType(receiver.get().qualifiedType, name).isPresent()) {
                return resolveTypeName(nestedType, name);
            }
        }

        return resolveTypeTree(parse, memberSelect, name);
    }

    private ResolvedSymbol resolveMemberReference(TreePath path, MemberReferenceTree memberReference, ParseTypeResolver types) {
        var name = memberReference.getName().toString();
        var receiver = types.resolveExpression(memberReference.getQualifierExpression());
        if (receiver.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, null, name, true, null, name);
        }
        if ("new".equals(name)) {
            var ownerType = receiver.get().qualifiedType;
            return new ResolvedSymbol(findConstructorLocations(ownerType, -1), ownerType, simpleName(ownerType), true, null, simpleName(ownerType));
        }
        return resolveQualifiedMethodInvocation(types, memberReference.getQualifierExpression(), name, List.of());
    }

    private ResolvedSymbol resolveUnqualifiedMethodInvocation(
            ParseTask parse, ParseTypeResolver types, MethodInvocationTree invocation, String methodName) {
        var argTypes = resolveArgumentTypes(invocation.getArguments(), types);
        var owner = types.currentEnclosingTypeName();
        if (owner.isPresent()) {
            TypeMemberIndex.Member member = null;
            if (hasResolvedArgumentTypes(argTypes)) {
                member = lookupMember(owner.get(), methodName, false, argTypes.toArray(String[]::new)).orElse(null);
            }
            if (member == null) {
                member = lookupMember(owner.get(), methodName, false).orElse(null);
            }
            var resolved = resolveMethod(owner.get(), methodName, invocation.getArguments().size(), argTypes, member);
            if (!resolved.locations().isEmpty()) {
                return resolved;
            }
        }
        var staticImport = resolveStaticImportMethod(parse.root, methodName, invocation.getArguments().size(), argTypes);
        if (staticImport.isPresent()) {
            return staticImport.get();
        }
        return new ResolvedSymbol(NOT_SUPPORTED, null, methodName, true, null, methodName);
    }

    private ResolvedSymbol resolveQualifiedMethodInvocation(
            ParseTypeResolver types,
            ExpressionTree receiverExpression,
            String methodName,
            List<? extends ExpressionTree> arguments) {
        var receiver = types.resolveExpression(receiverExpression);
        if (receiver.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, null, methodName, true, null, methodName);
        }
        var argTypes = resolveArgumentTypes(arguments, types);
        var ownerType = receiver.get().qualifiedType;
        var memberWithArgs =
                hasResolvedArgumentTypes(argTypes)
                        ? lookupMember(
                                ownerType,
                                methodName,
                                receiver.get().staticContext,
                                argTypes.toArray(String[]::new))
                        : Optional.<TypeMemberIndex.Member>empty();
        var memberWithoutArgs =
                memberWithArgs.isPresent()
                        ? Optional.<TypeMemberIndex.Member>empty()
                        : lookupMember(ownerType, methodName, receiver.get().staticContext);
        var member = memberWithArgs.or(() -> memberWithoutArgs).orElse(null);
        var resolvedOwnerType = member != null ? member.ownerType : ownerType;
        return resolveMethod(resolvedOwnerType, methodName, arguments.size(), argTypes, member);
    }

    private ResolvedSymbol resolveMethodFromMember(
            String ownerType, String methodName, int argCount, List<String> argTypes, TypeMemberIndex.Member member) {
        return resolveMethod(ownerType, methodName, argCount, argTypes, member);
    }

    private ResolvedSymbol resolveFieldFromMember(String ownerType, String fieldName, TypeMemberIndex.Member member) {
        if (member != null && member.backingFieldName != null && !member.backingFieldName.isBlank()) {
            var linked = resolveLinkedField(member);
            if (linked != null) {
                return linked;
            }
        }
        var locations = findFieldLocations(ownerType, fieldName);
        if (!locations.isEmpty()) {
            return new ResolvedSymbol(locations, ownerType, fieldName, false, member, fieldName);
        }
        var typeLocation = findTypeLocation(ownerType, fieldName);
        if (!typeLocation.isEmpty()) {
            return new ResolvedSymbol(typeLocation, ownerType, fieldName, false, member, fieldName);
        }
        return new ResolvedSymbol(NOT_SUPPORTED, ownerType, fieldName, false, member, fieldName);
    }

    private ResolvedSymbol resolveMethod(
            String ownerType, String methodName, int argCount, List<String> argTypes, TypeMemberIndex.Member member) {
        if (member != null && member.backingFieldName != null && !member.backingFieldName.isBlank()) {
            var linked = resolveLinkedField(member);
            if (linked != null) {
                return linked;
            }
            if (isWorkspaceType(member.ownerType)) {
                return new ResolvedSymbol(NOT_SUPPORTED, member.ownerType, member.backingFieldName, false, member, member.backingFieldName);
            }
        }
        var locations = findMethodLocations(ownerType, methodName, argCount, argTypes);
        if (!locations.isEmpty()) {
            return new ResolvedSymbol(locations, ownerType, methodName, true, member, methodName);
        }
        var fallbackOwner = member != null ? member.ownerType : ownerType;
        if (!fallbackOwner.equals(ownerType)) {
            locations = findMethodLocations(fallbackOwner, methodName, argCount, argTypes);
            if (!locations.isEmpty()) {
                return new ResolvedSymbol(locations, fallbackOwner, methodName, true, member, methodName);
            }
        }
        return new ResolvedSymbol(NOT_SUPPORTED, fallbackOwner, methodName, true, member, methodName);
    }

    private ResolvedSymbol resolveTypeTree(ParseTask parse, Tree typeTree, String simpleName) {
        var resolved = resolveTypeName(parse, typeTree.toString());
        if (resolved.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, null, null, false, null, simpleName);
        }
        return resolveTypeName(resolved.get(), simpleName);
    }

    private ResolvedSymbol resolveConstructorInvocation(
            ParseTask parse, NewClassTree newClassTree, ParseTypeResolver types, String simpleName) {
        var resolved =
                types.resolveTypeTree(newClassTree.getIdentifier(), true).map(type -> type.qualifiedType);
        if (resolved.isEmpty()) {
            resolved = resolveTypeName(parse, newClassTree.getIdentifier().toString());
        }
        if (resolved.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, null, null, false, null, simpleName);
        }
        var ownerType = resolved.get();
        var currentType = types.currentEnclosingTypeName();
        if (currentType.isPresent() && currentType.get().equals(ownerType)) {
            return resolveTypeName(ownerType, simpleName);
        }
        if (isJarBackedType(ownerType)) {
            return resolveTypeName(ownerType, simpleName);
        }
        var constructors = findConstructorLocations(ownerType, newClassTree.getArguments().size());
        if (!constructors.isEmpty()) {
            return new ResolvedSymbol(constructors, ownerType, simpleName, true, null, simpleName);
        }
        return resolveTypeName(ownerType, simpleName);
    }

    private boolean isJarBackedType(String ownerType) {
        var workspaceSource = typeLookup.findWorkspaceDeclaration(ownerType);
        if (workspaceSource != null && !workspaceSource.equals(CompilerProvider.NOT_FOUND)) {
            return false;
        }
        var source = typeLookup.findExternalSource(ownerType, "isJarBackedType");
        if (source.isEmpty()) {
            return false;
        }
        var uri = source.get().toUri();
        if (uri == null) {
            return false;
        }
        if ("jar".equals(uri.getScheme())) {
            return true;
        }
        var path = uri.getPath();
        return path != null && path.contains("jls-jar-sources");
    }

    private ResolvedSymbol resolveTypeName(String qualifiedType, String simpleName) {
        var locations = findTypeLocation(qualifiedType, simpleName);
        if (locations.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, qualifiedType, null, false, null, simpleName);
        }
        return new ResolvedSymbol(locations, qualifiedType, null, false, null, simpleName);
    }

    private Optional<ResolvedSymbol> resolveStaticImportField(ParseTask parse, String fieldName) {
        ResolvedSymbol match = null;
        for (var ownerType : TypeMemberIndex.staticImportOwnerTypes(fieldName, parse.root)) {
            var member = lookupMember(ownerType, fieldName, true).orElse(null);
            var resolved =
                    member != null
                            ? resolveFieldFromMember(ownerType, fieldName, member)
                            : resolveStaticFieldWithoutIndex(ownerType, fieldName);
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
            com.sun.source.tree.CompilationUnitTree root, String methodName, int argCount, List<String> argTypes) {
        ResolvedSymbol match = null;
        for (var ownerType : TypeMemberIndex.staticImportOwnerTypes(methodName, root)) {
            var member = lookupMember(ownerType, methodName, true).orElse(null);
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

    private Optional<ResolvedSymbol> resolveSwitchCaseField(TreePath path, String fieldName, ParseTypeResolver types) {
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

        var ownerType = types.resolveExpression(switchExpression).map(type -> type.qualifiedType);
        if (ownerType.isEmpty() || ownerType.get().isBlank()) {
            return Optional.empty();
        }

        var member =
                lookupMember(ownerType.get(), fieldName, true)
                        .or(() -> lookupMember(ownerType.get(), fieldName, false))
                        .orElse(null);
        var resolved = member != null
                ? resolveFieldFromMember(ownerType.get(), fieldName, member)
                : resolveStaticFieldWithoutIndex(ownerType.get(), fieldName);
        if (resolved.locations().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    private ResolvedSymbol resolveStaticFieldWithoutIndex(String ownerType, String fieldName) {
        var locations = findFieldLocations(ownerType, fieldName);
        if (!locations.isEmpty()) {
            return new ResolvedSymbol(locations, ownerType, fieldName, false, null, fieldName);
        }
        return new ResolvedSymbol(NOT_SUPPORTED, ownerType, fieldName, false, null, fieldName);
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

    private ResolvedSymbol fromDeclaration(ParseTask parse, TreePath path, String name) {
        var location = FindHelper.location(parse, path, name);
        if (location == null) {
            return new ResolvedSymbol(NOT_SUPPORTED, null, name, false, null, name);
        }
        return new ResolvedSymbol(List.of(location), null, name, false, null, name);
    }

    private ResolvedSymbol typeDeclaration(ParseTask parse, TreePath path, ClassTree cls) {
        var qualifiedType = qualifiedClassName(parse, path);
        var location = FindHelper.location(parse, path, cls.getSimpleName());
        if (location == null) {
            return new ResolvedSymbol(NOT_SUPPORTED, qualifiedType, null, false, null, cls.getSimpleName().toString());
        }
        return new ResolvedSymbol(List.of(location), qualifiedType, null, false, null, cls.getSimpleName().toString());
    }

    private ResolvedSymbol methodDeclaration(ParseTask parse, TreePath path, MethodTree method) {
        var classPath = nearestClass(path);
        var ownerType = classPath == null ? null : qualifiedClassName(parse, classPath);
        var simpleName = method.getName().contentEquals("<init>")
                ? classPath != null ? ((ClassTree) classPath.getLeaf()).getSimpleName().toString() : method.getName().toString()
                : method.getName().toString();
        if (ownerType != null && !method.getName().contentEquals("<init>")) {
            var indexed = findIndexedMethod(parse, ownerType, simpleName, method);
            if (indexed.isPresent()) {
                var overridden = findOverriddenDeclaration(indexed.get());
                if (!overridden.locations().isEmpty()) {
                    return overridden;
                }
            }
        }
        var location = FindHelper.location(parse, path, simpleName);
        if (location == null) {
            return new ResolvedSymbol(NOT_SUPPORTED, ownerType, simpleName, true, null, simpleName);
        }
        return new ResolvedSymbol(
                List.of(location),
                ownerType,
                simpleName,
                true,
                findIndexedMethod(parse, ownerType, simpleName, method).orElse(null),
                simpleName);
    }

    private ResolvedSymbol variableDeclaration(ParseTask parse, TreePath path, VariableTree variable) {
        var parent = path.getParentPath() != null ? path.getParentPath().getLeaf() : null;
        var memberField = parent instanceof ClassTree;
        var ownerType = memberField ? qualifiedClassName(parse, path.getParentPath()) : null;
        var location = FindHelper.location(parse, path, variable.getName());
        if (location == null) {
            return new ResolvedSymbol(NOT_SUPPORTED, ownerType, variable.getName().toString(), false, null, variable.getName().toString());
        }
        if (memberField && ownerType != null) {
            return resolvedFieldSymbol(ownerType, variable.getName().toString(), List.of(location));
        }
        return new ResolvedSymbol(List.of(location), ownerType, variable.getName().toString(), false, null, variable.getName().toString());
    }

    private Optional<TreePath> findFieldInEnclosingClass(ParseTask parse, TreePath path, String fieldName) {
        var classPath = nearestClass(path);
        if (classPath == null) {
            return Optional.empty();
        }
        var classTree = (ClassTree) classPath.getLeaf();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof VariableTree variable)) continue;
            if (!variable.getName().contentEquals(fieldName)) continue;
            return Optional.of(new TreePath(classPath, variable));
        }
        return Optional.empty();
    }

    private TreePath nearestClass(TreePath path) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree) {
                return cursor;
            }
        }
        return null;
    }

    private Optional<TreePath> findTypeParameterInEnclosingScopes(TreePath path, String name) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            var leaf = cursor.getLeaf();
            if (leaf instanceof MethodTree method) {
                for (TypeParameterTree parameter : method.getTypeParameters()) {
                    if (parameter.getName().contentEquals(name)) {
                        return Optional.of(new TreePath(cursor, parameter));
                    }
                }
            }
            if (leaf instanceof ClassTree cls) {
                for (TypeParameterTree parameter : cls.getTypeParameters()) {
                    if (parameter.getName().contentEquals(name)) {
                        return Optional.of(new TreePath(cursor, parameter));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> findNestedTypeInEnclosingScopes(ParseTask parse, TreePath path, String simpleName) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (!(cursor.getLeaf() instanceof ClassTree cls)) {
                continue;
            }
            for (var member : cls.getMembers()) {
                if (!(member instanceof ClassTree nested)) continue;
                if (!nested.getSimpleName().contentEquals(simpleName)) continue;
                var owner = qualifiedClassName(parse, cursor);
                return Optional.of(owner + "." + simpleName);
            }
        }
        return Optional.empty();
    }

    private String qualifiedClassName(ParseTask parse, TreePath classPath) {
        var names = new ArrayList<String>();
        for (var cursor = classPath; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree cls) {
                names.add(cls.getSimpleName().toString());
            }
        }
        java.util.Collections.reverse(names);
        var pkg = parse.root.getPackageName() == null ? "" : parse.root.getPackageName().toString();
        if (pkg.isBlank()) {
            return String.join(".", names);
        }
        return pkg + "." + String.join(".", names);
    }

    private List<String> resolveArgumentTypes(List<? extends ExpressionTree> args, ParseTypeResolver types) {
        var result = new ArrayList<String>(args.size());
        for (var argument : args) {
            var resolved = types.resolveExpression(argument);
            result.add(resolved.map(type -> canonicalType(type.qualifiedType)).orElse(""));
        }
        return result;
    }

    private boolean hasResolvedArgumentTypes(List<String> argTypes) {
        return !argTypes.isEmpty() && argTypes.stream().noneMatch(String::isBlank);
    }

    private ResolvedSymbol resolvedFieldSymbol(String ownerType, String fieldName, List<Location> locations) {
        var member = lookupMember(ownerType, fieldName, false).or(() -> lookupMember(ownerType, fieldName, true)).orElse(null);
        return new ResolvedSymbol(locations, ownerType, fieldName, false, member, fieldName);
    }

    private Optional<TypeMemberIndex.Member> findIndexedMethod(
            ParseTask parse, String ownerType, String methodName, MethodTree method) {
        if (ownerType == null) {
            return Optional.empty();
        }
        var erasedParameterTypes = new String[method.getParameters().size()];
        for (int i = 0; i < method.getParameters().size(); i++) {
            var parameter = method.getParameters().get(i);
            if (parameter.getType() == null) {
                return Optional.empty();
            }
            erasedParameterTypes[i] =
                    canonicalType(resolveTypeName(parse, parameter.getType().toString()).orElse(parameter.getType().toString()));
        }
        return lookupMember(ownerType, methodName, false, erasedParameterTypes)
                .or(() -> lookupMember(ownerType, methodName, true, erasedParameterTypes));
    }

    private ResolvedSymbol findOverriddenDeclaration(TypeMemberIndex.Member method) {
        var pending = new java.util.ArrayDeque<String>(completionIndex.directSupertypes(method.ownerType));
        var visited = new java.util.LinkedHashSet<String>();
        while (!pending.isEmpty()) {
            var superType = pending.removeFirst();
            if (!visited.add(superType)) {
                continue;
            }
            var override =
                    lookupMember(superType, method.name, false, method.erasedParameterTypes)
                            .or(() -> lookupMember(superType, method.name, true, method.erasedParameterTypes));
            if (override.isPresent()) {
                var locations =
                        findMethodLocations(
                                override.get().ownerType,
                                override.get().name,
                                override.get().erasedParameterTypes == null ? -1 : override.get().erasedParameterTypes.length,
                                override.get().erasedParameterTypes == null
                                        ? List.of()
                                        : java.util.Arrays.stream(override.get().erasedParameterTypes)
                                                .map(type -> canonicalType(type))
                                                .toList());
                if (!locations.isEmpty()) {
                    return new ResolvedSymbol(
                            locations,
                            override.get().ownerType,
                            override.get().name,
                            true,
                            override.get(),
                            override.get().name);
                }
            }
            pending.addAll(completionIndex.directSupertypes(superType));
        }
        return new ResolvedSymbol(NOT_SUPPORTED, method.ownerType, method.name, true, method, method.name);
    }

    private ResolvedSymbol resolveLinkedField(TypeMemberIndex.Member member) {
        var fieldName = member.backingFieldName;
        if (fieldName == null || fieldName.isBlank()) {
            return null;
        }
        var locations = findFieldLocations(member.ownerType, fieldName);
        if (!locations.isEmpty()) {
            return resolvedFieldSymbol(member.ownerType, fieldName, locations);
        }
        return null;
    }

    private boolean isWorkspaceType(String qualifiedType) {
        return completionIndex.workspace().typeInfo(qualifiedType)
                .map(info -> info.sourcePath != null)
                .orElse(false);
    }

    private Optional<TypeMemberIndex.Member> lookupMember(String ownerType, String memberName, boolean staticContext) {
        if (completionIndex.isWorkspaceOwnedType(ownerType, compiler)) {
            return completionIndex.workspace().member(ownerType, memberName, staticContext);
        }
        return completionIndex.member(ownerType, memberName, staticContext);
    }

    private Optional<TypeMemberIndex.Member> lookupMember(
            String ownerType, String memberName, boolean staticContext, String[] erasedParameterTypes) {
        if (completionIndex.isWorkspaceOwnedType(ownerType, compiler)) {
            return completionIndex.workspace().member(ownerType, memberName, staticContext, erasedParameterTypes);
        }
        return completionIndex.member(ownerType, memberName, staticContext, erasedParameterTypes);
    }

    private List<Location> findMethodLocations(String ownerType, String methodName, int argCount, List<String> argTypes) {
        var source = openTypeSource(ownerType);
        if (source.isEmpty()) {
            return List.of();
        }
        var locations = findMethodLocations(source.get(), methodName, argCount, argTypes);
        if (!locations.isEmpty()) {
            return locations;
        }
        var decompiled = openDecompiledTypeSource(ownerType);
        if (decompiled.isPresent()) {
            return findMethodLocations(decompiled.get(), methodName, argCount, argTypes);
        }
        return List.of();
    }

    private List<Location> findMethodLocations(
            TypeSource source, String methodName, int argCount, List<String> argTypes) {
        var parse = source.task;
        var classPath = source.classPath;
        var classTree = (ClassTree) classPath.getLeaf();
        var methods = new ArrayList<MethodTree>();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof MethodTree method)) continue;
            if (!method.getName().contentEquals(methodName)) continue;
            if (argCount >= 0 && method.getParameters().size() != argCount) continue;
            methods.add(method);
        }
        if (methods.isEmpty()) {
            return List.of();
        }

        var exact = new ArrayList<MethodTree>();
        if (argCount >= 0 && !argTypes.isEmpty() && argTypes.stream().noneMatch(String::isBlank)) {
            for (var method : methods) {
                if (matchesArgumentTypes(parse, method, argTypes)) {
                    exact.add(method);
                }
            }
        }

        var selected = !exact.isEmpty() ? exact : methods;
        var dedupe = new LinkedHashMap<String, Location>();
        for (var method : selected) {
            var path = new TreePath(classPath, method);
            var location = FindHelper.location(parse, path, methodName);
            if (location == null) continue;
            var key = location.uri + ":" + location.range.start.line + ":" + location.range.start.character;
            dedupe.putIfAbsent(key, location);
        }
        return new ArrayList<>(dedupe.values());
    }

    private boolean matchesArgumentTypes(ParseTask parse, MethodTree method, List<String> argTypes) {
        if (method.getParameters().size() != argTypes.size()) return false;
        for (int i = 0; i < argTypes.size(); i++) {
            var argType = argTypes.get(i);
            if (argType.isBlank()) return false;
            var parameter = method.getParameters().get(i);
            if (parameter.getType() == null) return false;
            var resolved = resolveTypeName(parse, parameter.getType().toString());
            var parameterType = resolved.orElse(parameter.getType().toString());
            if (!canonicalType(parameterType).equals(argType)) {
                return false;
            }
        }
        return true;
    }

    private Optional<String> resolveTypeName(ParseTask parse, String typeName) {
        return typeLookup.resolveTypeName(typeName, parse.root);
    }

    private List<Location> findFieldLocations(String ownerType, String fieldName) {
        var source = openTypeSource(ownerType);
        if (source.isEmpty()) {
            return List.of();
        }
        var locations = findFieldLocations(source.get(), fieldName);
        if (!locations.isEmpty()) {
            return locations;
        }
        var decompiled = openDecompiledTypeSource(ownerType);
        if (decompiled.isPresent()) {
            return findFieldLocations(decompiled.get(), fieldName);
        }
        return List.of();
    }

    private List<Location> findFieldLocations(TypeSource source, String fieldName) {
        var parse = source.task;
        var classPath = source.classPath;
        var classTree = (ClassTree) classPath.getLeaf();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof VariableTree variable)) continue;
            if (!variable.getName().contentEquals(fieldName)) continue;
            var path = new TreePath(classPath, variable);
            var location = FindHelper.location(parse, path, fieldName);
            if (location != null) {
                return List.of(location);
            }
        }
        if (classTree.getKind() == Tree.Kind.RECORD) {
            var location = FindHelper.locationStrict(parse, classPath, fieldName);
            if (location != null) {
                return List.of(location);
            }
        }
        return List.of();
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
            if (!(member instanceof MethodTree method)) continue;
            if (method.getReturnType() != null) continue;
            if (argCount >= 0 && method.getParameters().size() != argCount) continue;
            var path = new TreePath(classPath, method);
            var location = FindHelper.location(parse, path, constructorName);
            if (location != null) {
                results.add(location);
            }
        }
        return results;
    }

    private List<Location> findTypeLocation(String qualifiedType, String labelName) {
        var source = openTypeSource(qualifiedType);
        if (source.isEmpty()) {
            return List.of();
        }
        var locations = findTypeLocation(source.get(), labelName);
        if (!locations.isEmpty()) {
            return locations;
        }
        var decompiled = openDecompiledTypeSource(qualifiedType);
        if (decompiled.isPresent()) {
            return findTypeLocation(decompiled.get(), labelName);
        }
        return List.of();
    }

    private List<Location> findTypeLocation(TypeSource source, String labelName) {
        var path = source.classPath;
        var location = FindHelper.location(source.task, path, labelName);
        if (location == null) {
            location = FindHelper.location(source.task, path);
        }
        if (location == null) {
            return List.of();
        }
        return List.of(location);
    }

    private Optional<TypeSource> openTypeSource(String qualifiedType) {
        var type = completionIndex.workspace().typeInfo(qualifiedType).orElse(null);
        if (type != null && type.sourcePath != null) {
            var parse = compiler.parse(type.sourcePath);
            var classPath = findClassPath(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }

        var workspaceSource = typeLookup.findWorkspaceDeclaration(qualifiedType);
        if (workspaceSource != null && !workspaceSource.equals(CompilerProvider.NOT_FOUND)) {
            var parse = compiler.parse(workspaceSource);
            var classPath = findClassPath(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }

        var anywhere = typeLookup.findExternalSource(qualifiedType, "openTypeSource");
        if (anywhere.isPresent()) {
            var parse = compiler.parse(anywhere.get());
            var classPath = findClassPath(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }

        for (var i = qualifiedType.lastIndexOf('.'); i > 0; i = qualifiedType.lastIndexOf('.', i - 1)) {
            var outer = qualifiedType.substring(0, i);
            var workspaceOuter = typeLookup.findWorkspaceDeclaration(outer);
            if (workspaceOuter != null && !workspaceOuter.equals(CompilerProvider.NOT_FOUND)) {
                var parse = compiler.parse(workspaceOuter);
                var classPath = findClassPath(parse, qualifiedType);
                if (classPath.isPresent()) {
                    return Optional.of(new TypeSource(parse, classPath.get()));
                }
            }
            var outerSource = typeLookup.findExternalSource(outer, "openTypeSourceOuter");
            if (outerSource.isEmpty()) {
                continue;
            }
            var parse = compiler.parse(outerSource.get());
            var classPath = findClassPath(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }
        var decompiledSource = completionIndex.externalDecompiledSourcePath(qualifiedType);
        if (decompiledSource.isPresent()) {
            var parse = compiler.parse(decompiledSource.get());
            var classPath = findClassPath(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<TypeSource> openDecompiledTypeSource(String qualifiedType) {
        var decompiledSource = completionIndex.externalDecompiledSourcePath(qualifiedType);
        if (decompiledSource.isEmpty()) {
            return Optional.empty();
        }
        var parse = compiler.parse(decompiledSource.get());
        var classPath = findClassPath(parse, qualifiedType);
        if (classPath.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TypeSource(parse, classPath.get()));
    }

    private Optional<TreePath> findClassPath(ParseTask parse, String qualifiedType) {
        try {
            var classTree = FindHelper.findType(parse, qualifiedType);
            var path = Trees.instance(parse.task).getPath(parse.root, classTree);
            return Optional.ofNullable(path);
        } catch (RuntimeException notFound) {
            return Optional.empty();
        }
    }

    private static String canonicalType(String typeName) {
        var raw = typeName == null ? "" : typeName.trim();
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        if (raw.startsWith("? extends ")) {
            raw = raw.substring("? extends ".length()).trim();
        } else if (raw.startsWith("? super ")) {
            raw = raw.substring("? super ".length()).trim();
        }
        return switch (raw) {
            case "byte", "java.lang.Byte" -> "java.lang.Byte";
            case "short", "java.lang.Short" -> "java.lang.Short";
            case "int", "java.lang.Integer" -> "java.lang.Integer";
            case "long", "java.lang.Long" -> "java.lang.Long";
            case "float", "java.lang.Float" -> "java.lang.Float";
            case "double", "java.lang.Double" -> "java.lang.Double";
            case "boolean", "java.lang.Boolean" -> "java.lang.Boolean";
            case "char", "java.lang.Character" -> "java.lang.Character";
            default -> raw;
        };
    }

    private static String simpleName(String qualifiedType) {
        var index = qualifiedType.lastIndexOf('.');
        return index >= 0 ? qualifiedType.substring(index + 1) : qualifiedType;
    }

    private static final class TypeSource {
        final ParseTask task;
        final TreePath classPath;

        TypeSource(ParseTask task, TreePath classPath) {
            this.task = task;
            this.classPath = classPath;
        }
    }
}
