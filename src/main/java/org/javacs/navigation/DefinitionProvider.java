package org.javacs.navigation;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
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
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.FindNameAt;
import org.javacs.ParseTask;
import org.javacs.completion.TypeMemberIndex;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;

public class DefinitionProvider {
    private final CompilerProvider compiler;
    private final TypeMemberIndex completionIndex;
    private final Path file;
    private final int line;
    private final int column;

    public static final List<Location> NOT_SUPPORTED = List.of();
    private static final Logger LOG = Logger.getLogger("main");

    public record ResolvedSymbol(
            List<Location> locations,
            String qualifiedType,
            String memberName,
            boolean method,
            TypeMemberIndex.Member indexMember,
            String simpleName) {}

    public DefinitionProvider(CompilerProvider compiler, Path file, int line, int column) {
        this(compiler, TypeMemberIndex.EMPTY, file, line, column);
    }

    public DefinitionProvider(CompilerProvider compiler, TypeMemberIndex completionIndex, Path file, int line, int column) {
        this.compiler = compiler;
        this.completionIndex = completionIndex == null ? TypeMemberIndex.EMPTY : completionIndex;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        return resolveSymbol().locations();
    }

    public ResolvedSymbol resolveSymbol() {
        var parse = compiler.parse(file);
        var cursor = parse.root.getLineMap().getPosition(line, column);
        var path = new FindNameAt(parse).scan(parse.root, cursor);
        if (path == null) return new ResolvedSymbol(NOT_SUPPORTED, null, null, false, null, null);
        var resolver = new ParseTypeResolver(parse, completionIndex, compiler, cursor);
        return resolve(parse, path, resolver);
    }

    private ResolvedSymbol resolve(ParseTask parse, TreePath path, ParseTypeResolver types) {
        var leaf = path.getLeaf();
        LOG.fine(String.format("[perf] definition_path kind=%s leaf=%s", leaf.getKind(), leaf));
        if (leaf instanceof ClassTree cls) {
            return fromDeclaration(parse, path, cls.getSimpleName().toString());
        }
        if (leaf instanceof MethodTree method) {
            var name = method.getName().toString();
            if ("<init>".equals(name)) {
                var parent = nearestClass(path);
                if (parent != null) {
                    name = ((ClassTree) parent.getLeaf()).getSimpleName().toString();
                }
            }
            return fromDeclaration(parse, path, name);
        }
        if (leaf instanceof VariableTree variable) {
            return fromDeclaration(parse, path, variable.getName().toString());
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
            return resolveUnqualifiedMethodInvocation(types, invocation, name);
        }

        if (parent instanceof NewClassTree newClassTree && newClassTree.getIdentifier() == identifier) {
            return resolveConstructorInvocation(parse, newClassTree, types, name);
        }

        var local = types.resolveVisibleDeclaration(name);
        if (local.isPresent()) {
            var location = FindHelper.location(parse, local.get(), name);
            if (location != null) {
                return new ResolvedSymbol(List.of(location), null, name, false, null, name);
            }
        }

        var field = findFieldInEnclosingClass(parse, path, name);
        if (field.isPresent()) {
            var location = FindHelper.location(parse, field.get(), name);
            if (location != null) {
                return new ResolvedSymbol(List.of(location), null, name, false, null, name);
            }
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
            var member = completionIndex.member(receiver.get().qualifiedType, name, receiver.get().staticContext);
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
            if (completionIndex.types().containsKey(nestedType)) {
                return resolveTypeName(nestedType, name);
            }
            if (compiler.findAnywhere(nestedType).isPresent()) {
                return resolveTypeName(nestedType, name);
            }
        }

        LOG.fine(
                String.format(
                        "[perf] definition_member_unresolved expr=%s member=%s receiver=%s",
                        memberSelect.getExpression(),
                        name,
                        receiver.map(r -> r.qualifiedType).orElse("<empty>")));

        return resolveTypeTree(parse, memberSelect, name);
    }

    private ResolvedSymbol resolveMemberReference(TreePath path, MemberReferenceTree memberReference, ParseTypeResolver types) {
        var name = memberReference.getName().toString();
        var receiver = types.resolveExpression(memberReference.getQualifierExpression());
        if (receiver.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, null, name, true, null, name);
        }
        return resolveQualifiedMethodInvocation(types, memberReference.getQualifierExpression(), name, List.of());
    }

    private ResolvedSymbol resolveUnqualifiedMethodInvocation(
            ParseTypeResolver types, MethodInvocationTree invocation, String methodName) {
        var owner = types.currentEnclosingTypeName();
        if (owner.isEmpty()) {
            return new ResolvedSymbol(NOT_SUPPORTED, null, methodName, true, null, methodName);
        }
        var argTypes = resolveArgumentTypes(invocation.getArguments(), types);
        return resolveMethod(owner.get(), methodName, invocation.getArguments().size(), argTypes, null);
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
        var member = completionIndex.member(receiver.get().qualifiedType, methodName, receiver.get().staticContext).orElse(null);
        var ownerType = member != null ? member.ownerType : receiver.get().qualifiedType;
        return resolveMethod(ownerType, methodName, arguments.size(), argTypes, member);
    }

    private ResolvedSymbol resolveMethodFromMember(
            String ownerType, String methodName, int argCount, List<String> argTypes, TypeMemberIndex.Member member) {
        return resolveMethod(ownerType, methodName, argCount, argTypes, member);
    }

    private ResolvedSymbol resolveFieldFromMember(String ownerType, String fieldName, TypeMemberIndex.Member member) {
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
        var typeLocation = findTypeLocation(fallbackOwner, methodName);
        if (!typeLocation.isEmpty()) {
            return new ResolvedSymbol(typeLocation, fallbackOwner, methodName, true, member, methodName);
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
        var resolved = resolveTypeName(parse, newClassTree.getIdentifier().toString());
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
        var source = compiler.findAnywhere(ownerType);
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

    private ResolvedSymbol fromDeclaration(ParseTask parse, TreePath path, String name) {
        var location = FindHelper.location(parse, path, name);
        if (location == null) {
            return new ResolvedSymbol(NOT_SUPPORTED, null, name, false, null, name);
        }
        return new ResolvedSymbol(List.of(location), null, name, false, null, name);
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

    private List<Location> findMethodLocations(String ownerType, String methodName, int argCount, List<String> argTypes) {
        var source = openTypeSource(ownerType);
        if (source.isEmpty()) {
            return List.of();
        }
        var parse = source.get().task;
        var classPath = source.get().classPath;
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
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var indexed = completionIndex.resolveTypeName(typeName, parse.root);
        if (indexed.isPresent()) {
            return indexed;
        }
        var raw = typeName.trim();
        while (raw.endsWith("[]")) {
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
            return Optional.empty();
        }
        if (raw.isBlank()) {
            return Optional.empty();
        }
        if (TypeMemberIndex.isPrimitiveTypeName(raw)) {
            return Optional.of(raw);
        }
        if (raw.contains(".")) {
            if (compiler.findAnywhere(raw).isPresent()) {
                return Optional.of(raw);
            }
        }
        var packageName = parse.root.getPackageName() == null ? "" : parse.root.getPackageName().toString();
        if (!packageName.isBlank()) {
            var candidate = packageName + "." + raw;
            if (compiler.findAnywhere(candidate).isPresent()) {
                return Optional.of(candidate);
            }
        }
        for (var importTree : parse.root.getImports()) {
            if (importTree.isStatic()) continue;
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

    private List<Location> findFieldLocations(String ownerType, String fieldName) {
        var source = openTypeSource(ownerType);
        if (source.isEmpty()) {
            LOG.fine(String.format("[perf] definition_field_source_missing owner=%s field=%s", ownerType, fieldName));
            return List.of();
        }
        var parse = source.get().task;
        var classPath = source.get().classPath;
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
        LOG.fine(String.format("[perf] definition_field_not_found owner=%s field=%s", ownerType, fieldName));
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
        var path = source.get().classPath;
        var location = FindHelper.location(source.get().task, path, labelName);
        if (location == null) {
            location = FindHelper.location(source.get().task, path);
        }
        if (location == null) {
            return List.of();
        }
        return List.of(location);
    }

    private Optional<TypeSource> openTypeSource(String qualifiedType) {
        var type = completionIndex.types().get(qualifiedType);
        if (type != null && type.sourcePath != null) {
            var parse = compiler.parse(type.sourcePath);
            var classPath = findClassPath(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }

        var anywhere = compiler.findAnywhere(qualifiedType);
        if (anywhere.isPresent()) {
            var parse = compiler.parse(anywhere.get());
            var classPath = findClassPath(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }

        for (var i = qualifiedType.lastIndexOf('.'); i > 0; i = qualifiedType.lastIndexOf('.', i - 1)) {
            var outer = qualifiedType.substring(0, i);
            var outerSource = compiler.findAnywhere(outer);
            if (outerSource.isEmpty()) {
                continue;
            }
            var parse = compiler.parse(outerSource.get());
            var classPath = findClassPath(parse, qualifiedType);
            if (classPath.isPresent()) {
                return Optional.of(new TypeSource(parse, classPath.get()));
            }
        }
        return Optional.empty();
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

    private static final class TypeSource {
        final ParseTask task;
        final TreePath classPath;

        TypeSource(ParseTask task, TreePath classPath) {
            this.task = task;
            this.classPath = classPath;
        }
    }
}
