package org.javacs.provider;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;

import org.javacs.*;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.index.IndexedMember;
import org.javacs.index.IndexedType;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;
import org.javacs.navigation.NavigationSymbolSupport;
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
    private record FieldTarget(TreePath path, String name) {}
    private record SelectedMethod(
            String ownerType,
            String methodName,
            int argumentCount,
            List<String> argumentTypes,
            IndexedMember member) {}

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
    private final Map<String, JavaFileObject> attachedExternalSources = new HashMap<>();
    private final Map<String, Optional<TypeSource>> openedTypeSources = new HashMap<>();
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
        var lombokUsed = compiler.lombokPresentOnClasspath();
        // Use an ATTR compile of the current file only. javac resolves cross-file types
        // automatically via SOURCE_PATH during attribution, so Trees.getElement(path) is
        // still fully typed. For cross-file declarations, Trees.getPath(element) either
        // succeeds (SOURCE_PATH files are entered into javac's compiledTopLevels) or returns
        // null and the fallback resolveElementCrossFile() handles it via the type index.
        // This reuses the completion ATTR cache — definition is always a cache hit.
        var compile = compiler.compileFastWithProcessors(file);

        try {
            var root = compile.root(file);
            long cursor;
            try {
                cursor =
                        FileStore.offset(
                                root.getSourceFile().getCharContent(true).toString(), line, column);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(compile).scan(root, cursor);
            if (path == null) {
                path = new FindMemberSelectAt(compile).scan(root, cursor);
            }
            if (path == null) {
                return unsupported(null);
            }
            if (isConstructorIdentifier(path)) {
                path = path.getParentPath();
            }
            var selectedName = selectedName(path);
            var trees = Trees.instance(compile.task);
            var element = trees.getElement(path);
            if (element == null) {
                if (lombokUsed) {
                    var builderField = lombokBuilderFieldFromReceiver(compile, trees, path, selectedName);
                    if (builderField.isPresent()) {
                        var target = builderField.get();
                        var location = FindHelper.location(compile, target.path(), target.name());
                        if (location != null) {
                            return new ResolvedSymbol(
                                    List.of(location), null, target.name(), false, null, target.name());
                        }
                    }
                }
                return unsupported(null);
            }
            var declarationPath = trees.getPath(element);
            // Edge case: constructors may not have a direct path; navigate to the enclosing class.
            if (declarationPath == null
                    && element.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR) {
                declarationPath = trees.getPath(element.getEnclosingElement());
            }
            // Edge case: record component accessor methods navigate to the record component.
            if (declarationPath == null
                    && element.getKind() == javax.lang.model.element.ElementKind.METHOD
                    && element.getEnclosingElement() instanceof javax.lang.model.element.TypeElement type
                    && type.getKind() == javax.lang.model.element.ElementKind.RECORD) {
                for (var member : type.getEnclosedElements()) {
                    if (member.getKind() == javax.lang.model.element.ElementKind.RECORD_COMPONENT
                            && member.getSimpleName().contentEquals(element.getSimpleName())) {
                        declarationPath = trees.getPath(member);
                        break;
                    }
                }
            }
            if (declarationPath != null) {
                // In-batch declaration found; resolve directly.
                CharSequence declarationName = element.getSimpleName();
                if (element.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR) {
                    declarationName = element.getEnclosingElement().getSimpleName();
                }
                var location = FindHelper.location(compile, declarationPath, declarationName);
                if (lombokUsed) {
                    var lombokField = lombokGeneratedField(
                            compile, trees, element, selectedName, declarationPath, location);
                    if (lombokField.isPresent()) {
                        declarationPath = lombokField.get().path();
                        declarationName = lombokField.get().name();
                        location = FindHelper.location(compile, declarationPath, declarationName);
                    }
                }
                if (location == null) {
                    return unsupported(declarationName.toString());
                }
                var enclosing = element.getEnclosingElement();
                var qualifiedType = element instanceof javax.lang.model.element.TypeElement t
                        ? t.getQualifiedName().toString()
                        : enclosing instanceof javax.lang.model.element.TypeElement t
                                ? t.getQualifiedName().toString()
                                : null;
                var isMethod =
                        element.getKind() == javax.lang.model.element.ElementKind.METHOD
                                || element.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR;
                var memberName = element instanceof javax.lang.model.element.TypeElement
                        ? null
                        : declarationName.toString();
                return new ResolvedSymbol(
                        List.of(location), qualifiedType, memberName, isMethod, null, declarationName.toString());
                // return resolve(parse, path, new ParseTypeResolver(parse, compiler, typeIndexRouter, cursor));
            }
            // declarationPath is null: element is in another file or is Lombok-generated.
            // Try Lombok same-file field navigation first (Lombok-generated method with field in batch).
            if (lombokUsed) {
                var lombokField = lombokGeneratedField(compile, trees, element, selectedName, null, null);
                if (lombokField.isPresent()) {
                    var target = lombokField.get();
                    var location = FindHelper.location(compile, target.path(), target.name());
                    if (location != null) {
                        return new ResolvedSymbol(
                                List.of(location), null, target.name(), false, null, target.name());
                    }
                }
            }
            return resolveElementCrossFile(element, selectedName, lombokUsed);
        } finally {
            compile.close();
        }
    }

    private ResolvedSymbol resolveElementCrossFile(
            javax.lang.model.element.Element element,
            String selectedName,
            boolean lombokUsed) {
        var kind = element.getKind();
        if (kind == javax.lang.model.element.ElementKind.CLASS
                || kind == javax.lang.model.element.ElementKind.INTERFACE
                || kind == javax.lang.model.element.ElementKind.ENUM
                || kind == javax.lang.model.element.ElementKind.RECORD
                || kind == javax.lang.model.element.ElementKind.ANNOTATION_TYPE) {
            var qualifiedName = ((javax.lang.model.element.TypeElement) element).getQualifiedName().toString();
            var simpleName = element.getSimpleName().toString();
            var locations = findTypeLocations(qualifiedName, simpleName);
            return new ResolvedSymbol(
                    locations.isEmpty() ? NOT_SUPPORTED : locations,
                    qualifiedName, null, false, null, simpleName);
        }
        if (!(element.getEnclosingElement() instanceof javax.lang.model.element.TypeElement owner)) {
            return unsupported(element.getSimpleName().toString());
        }
        var ownerQualified = owner.getQualifiedName().toString();
        var memberName = element.getSimpleName().toString();
        if (kind == javax.lang.model.element.ElementKind.FIELD
                || kind == javax.lang.model.element.ElementKind.ENUM_CONSTANT) {
            var locations = findFieldLocations(ownerQualified, memberName);
            return new ResolvedSymbol(
                    locations.isEmpty() ? NOT_SUPPORTED : locations,
                    ownerQualified, memberName, false, null, memberName);
        }
        if (kind == javax.lang.model.element.ElementKind.METHOD) {
            var exec = (javax.lang.model.element.ExecutableElement) element;
            var argCount = exec.getParameters().size();
            // Record component accessor: navigate to the component field regardless of arity.
            if (owner.getKind() == javax.lang.model.element.ElementKind.RECORD) {
                var fieldLocations = findFieldLocations(ownerQualified, memberName);
                if (!fieldLocations.isEmpty()) {
                    return new ResolvedSymbol(
                            fieldLocations, ownerQualified, memberName, false, null, memberName);
                }
            }
            if (lombokUsed) {
                // Builder chain: method on a generated Builder class (e.g. .field1(v).build()).
                var builderOwner = lombokBuilderOwner(owner);
                if (builderOwner.isPresent()) {
                    var outerQualified = builderOwner.get().getQualifiedName().toString();
                    var outerSimple = builderOwner.get().getSimpleName().toString();
                    // Builder setter methods share their name with the field in the outer class.
                    if (!memberName.equals("build") && !memberName.equals("toBuilder")) {
                        var fieldLocations = findFieldLocations(outerQualified, memberName);
                        if (!fieldLocations.isEmpty()) {
                            return new ResolvedSymbol(
                                    fieldLocations, outerQualified, memberName, false, null, memberName);
                        }
                    }
                    // build() / toBuilder() / unmatched builder setter → outer class declaration.
                    var classLocations = findTypeLocations(outerQualified, outerSimple);
                    return new ResolvedSymbol(
                            !classLocations.isEmpty() ? classLocations : NOT_SUPPORTED,
                            outerQualified, null, false, null, outerSimple);
                }
                // Lombok accessor: getX() / setX(v) / isX() → backing field (any arity).
                var fieldName = lombokAccessorFieldName(memberName);
                if (fieldName.isPresent()) {
                    var fieldLocations = findFieldLocations(ownerQualified, fieldName.get());
                    if (!fieldLocations.isEmpty()) {
                        return new ResolvedSymbol(
                                fieldLocations, ownerQualified, fieldName.get(), false, null, fieldName.get());
                    }
                }
                // Other Lombok-generated methods (builder(), toBuilder(), with(), etc.) → class.
                var classLocations = findTypeLocations(ownerQualified, owner.getSimpleName().toString());
                if (!classLocations.isEmpty()) {
                    return new ResolvedSymbol(
                            classLocations, ownerQualified, null, false, null, owner.getSimpleName().toString());
                }
            }
            var locations = findMethodLocations(ownerQualified, memberName, argCount, List.of());
            return new ResolvedSymbol(
                    locations.isEmpty() ? NOT_SUPPORTED : locations,
                    ownerQualified, memberName, true, null, memberName);
        }
        if (kind == javax.lang.model.element.ElementKind.CONSTRUCTOR) {
            var exec = (javax.lang.model.element.ExecutableElement) element;
            var argCount = exec.getParameters().size();
            var constructorName = owner.getSimpleName().toString();
            var locations = findConstructorLocations(ownerQualified, argCount);
            if (!locations.isEmpty()) {
                return new ResolvedSymbol(
                        locations, ownerQualified, constructorName, true, null, constructorName);
            }
            if (lombokUsed) {
                // Lombok-generated constructor (e.g. @AllArgsConstructor) → class declaration.
                var classLocations = findTypeLocations(ownerQualified, constructorName);
                return new ResolvedSymbol(
                        !classLocations.isEmpty() ? classLocations : NOT_SUPPORTED,
                        ownerQualified, null, false, null, constructorName);
            }
            return new ResolvedSymbol(NOT_SUPPORTED, ownerQualified, constructorName, true, null, constructorName);
        }
        return unsupported(memberName);
    }

    private boolean isConstructorIdentifier(TreePath path) {
        var parent = path.getParentPath();
        if (parent == null || !(parent.getLeaf() instanceof NewClassTree newClassTree)) {
            return false;
        }
        if (newClassTree.getClassBody() != null) {
            return false;
        }
        return newClassTree.getIdentifier() == path.getLeaf();
    }

    private Optional<FieldTarget> lombokGeneratedField(
            org.javacs.CompileTask compile,
            Trees trees,
            javax.lang.model.element.Element element,
            String selectedName,
            TreePath declarationPath,
            Location location) {
        if (selectedName == null || selectedName.isBlank()) {
            return Optional.empty();
        }
        var owner = lombokFieldOwner(element);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        var targetField = lombokAccessorFieldName(selectedName)
                .flatMap(fieldName -> findFieldInType(compile, trees, owner.get(), fieldName));
        if (targetField.isEmpty()) {
            targetField = lombokBuilderOwner(owner.get())
                    .flatMap(builderOwner -> findFieldInType(compile, trees, builderOwner, selectedName));
        }
        if (targetField.isEmpty()
                && lombokBuilderOwner(owner.get()).isPresent()) {
            targetField = findFieldInType(compile, trees, owner.get(), selectedName);
        }
        if (targetField.isEmpty()) {
            return Optional.empty();
        }
        if (element.getKind() == javax.lang.model.element.ElementKind.METHOD
                && location != null
                && !isLombokAnnotationPath(declarationPath)
                && declarationPath != null
                && declarationPath.getLeaf() instanceof MethodTree
                && locationContainsName(location, selectedName)) {
            return Optional.empty();
        }
        return targetField;
    }

    private String selectedName(TreePath path) {
        if (path == null) {
            return null;
        }
        var leaf = path.getLeaf();
        if (leaf instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (leaf instanceof MemberSelectTree memberSelect) {
            return memberSelect.getIdentifier().toString();
        }
        if (leaf instanceof MemberReferenceTree memberReference) {
            return memberReference.getName().toString();
        }
        if (leaf instanceof MethodTree method) {
            return method.getName().toString();
        }
        if (leaf instanceof VariableTree variable) {
            return variable.getName().toString();
        }
        if (leaf instanceof ClassTree cls) {
            return cls.getSimpleName().toString();
        }
        return null;
    }

    private Optional<javax.lang.model.element.TypeElement> lombokFieldOwner(
            javax.lang.model.element.Element element) {
        if (element.getEnclosingElement() instanceof javax.lang.model.element.TypeElement owner) {
            return Optional.of(owner);
        }
        if (element instanceof javax.lang.model.element.TypeElement owner) {
            return Optional.of(owner);
        }
        return Optional.empty();
    }

    private Optional<FieldTarget> lombokBuilderFieldFromReceiver(
            org.javacs.CompileTask compile, Trees trees, TreePath path, String selectedName) {
        if (selectedName == null || selectedName.isBlank()) {
            return Optional.empty();
        }
        if (!(path.getLeaf() instanceof MemberSelectTree memberSelect)) {
            return Optional.empty();
        }
        var receiverType = trees.getTypeMirror(new TreePath(path, memberSelect.getExpression()));
        if (receiverType == null) {
            return Optional.empty();
        }
        var receiverElement = compile.task.getTypes().asElement(receiverType);
        if (!(receiverElement instanceof javax.lang.model.element.TypeElement receiver)) {
            return Optional.empty();
        }
        return lombokBuilderOwner(receiver)
                .flatMap(builderOwner -> findFieldInType(compile, trees, builderOwner, selectedName));
    }

    private class FindMemberSelectAt extends com.sun.source.util.TreePathScanner<TreePath, Long> {
        private final org.javacs.CompileTask compile;
        private CompilationUnitTree root;

        FindMemberSelectAt(org.javacs.CompileTask compile) {
            this.compile = compile;
        }

        @Override
        public TreePath visitCompilationUnit(CompilationUnitTree tree, Long cursor) {
            root = tree;
            return super.visitCompilationUnit(tree, cursor);
        }

        @Override
        public TreePath visitMemberSelect(MemberSelectTree tree, Long cursor) {
            var positions = Trees.instance(compile.task).getSourcePositions();
            var start = (int) positions.getStartPosition(root, tree);
            var end = (int) positions.getEndPosition(root, tree);
            if (start >= 0 && end >= start) {
                var nameStart = FindHelper.findNameIn(root, tree.getIdentifier(), start, end);
                var nameEnd = nameStart + tree.getIdentifier().length();
                if (nameStart >= 0 && nameStart <= cursor && cursor <= nameEnd) {
                    return getCurrentPath();
                }
            }
            return super.visitMemberSelect(tree, cursor);
        }

        @Override
        public TreePath reduce(TreePath first, TreePath second) {
            return first != null ? first : second;
        }
    }

    private boolean isLombokAnnotationPath(TreePath path) {
        if (path == null || !(path.getLeaf() instanceof AnnotationTree annotation)) {
            return false;
        }
        var name = annotation.getAnnotationType().toString();
        if (name.startsWith("lombok.")) {
            return true;
        }
        return switch (name) {
            case "Getter",
                    "Setter",
                    "Data",
                    "Builder",
                    "Value",
                    "With",
                    "NoArgsConstructor",
                    "AllArgsConstructor",
                    "RequiredArgsConstructor" -> true;
            default -> false;
        };
    }

    private Optional<String> lombokAccessorFieldName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return Optional.of(decapitalize(methodName.substring(3)));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Optional.of(decapitalize(methodName.substring(2)));
        }
        if (methodName.startsWith("set") && methodName.length() > 3) {
            return Optional.of(decapitalize(methodName.substring(3)));
        }
        return Optional.empty();
    }

    private String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        if (value.length() > 1
                && Character.isUpperCase(value.charAt(0))
                && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private Optional<javax.lang.model.element.TypeElement> lombokBuilderOwner(
            javax.lang.model.element.TypeElement owner) {
        javax.lang.model.element.TypeElement current = owner;
        while (true) {
            var ownerName = current.getSimpleName().toString();
            if ((ownerName.endsWith("Builder") || ownerName.equals("builder"))
                    && current.getEnclosingElement() instanceof javax.lang.model.element.TypeElement enclosingType) {
                return Optional.of(enclosingType);
            }
            if (current.getEnclosingElement() instanceof javax.lang.model.element.TypeElement enclosingType) {
                current = enclosingType;
            } else {
                return Optional.empty();
            }
        }
    }

    private boolean locationContainsName(Location location, String name) {
        if (location == null || location.uri == null || name == null || name.isEmpty()) {
            return false;
        }
        try {
            var lines = java.nio.file.Files.readString(Path.of(location.uri)).split("\n", -1);
            var start = location.range.start;
            var end = location.range.end;
            if (start.line < 0 || start.line >= lines.length || end.line != start.line) {
                return false;
            }
            var lineText = lines[start.line];
            if (start.character < 0 || end.character > lineText.length() || end.character < start.character) {
                return false;
            }
            return lineText.substring(start.character, end.character).contains(name);
        } catch (RuntimeException | java.io.IOException e) {
            return false;
        }
    }

    private Optional<FieldTarget> findFieldInType(
            org.javacs.CompileTask compile,
            Trees trees,
            javax.lang.model.element.TypeElement type,
            String fieldName) {
        for (var member : type.getEnclosedElements()) {
            if (member.getKind() != javax.lang.model.element.ElementKind.FIELD) {
                continue;
            }
            if (!member.getSimpleName().contentEquals(fieldName)) {
                continue;
            }
            var path = trees.getPath(member);
            if (path != null) {
                return Optional.of(new FieldTarget(path, fieldName));
            }
        }
        var superclass = type.getSuperclass();
        if (superclass == null || superclass.getKind() == javax.lang.model.type.TypeKind.NONE) {
            return Optional.empty();
        }
        var superElement = compile.task.getTypes().asElement(superclass);
        if (superElement instanceof javax.lang.model.element.TypeElement superType) {
            return findFieldInType(compile, trees, superType, fieldName);
        }
        return Optional.empty();
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
            var visible = visibleLocalDeclaration(parse, types, identifier.getName().toString());
            if (visible.isPresent()) {
                return visible.get();
            }
            var parent = parentLeaf(path);
            if (parent instanceof NewClassTree newClassTree
                    && newClassTree.getIdentifier() == identifier) {
                var ownerType = typeIndexRouter.resolveType(newClassTree.getIdentifier().toString(), parse.root());
                if (ownerType.isPresent()) {
                    return resolveIndexedConstructorOwner(
                            ownerType.get(),
                            identifier.getName().toString(),
                            newClassTree.getArguments().size());
                }
            }
            if (parent instanceof MethodInvocationTree invocation
                    && invocation.getMethodSelect() == identifier) {
                var resolved = resolveUnqualifiedMethodInvocation(
                        parse, path, types, invocation, identifier.getName().toString());
                return resolved;
            }

            return resolveIdentifier(parse, path, identifier, types);
        }
        if (leaf instanceof MemberSelectTree memberSelect) {
            var parent = parentLeaf(path);
            if (parent instanceof MethodInvocationTree invocation
                    && invocation.getMethodSelect() == memberSelect) {
                var ownerType = typeIndexRouter.resolveType(memberSelect.getExpression().toString(), parse.root());
                if (ownerType.isPresent()) {
                    var earlyMethod =
                            methodEarlyReturn(
                                    ownerType.get(),
                                    memberSelect.getIdentifier().toString(),
                                    true,
                                    invocation.getArguments().size());
                    if (earlyMethod.isPresent()) {
                        return earlyMethod.get();
                    }
                }
            }
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
        var enclosingField = resolveEnclosingField(parse, path, name);
        if (enclosingField.isPresent()) {
            return enclosingField.get();
        }
        var inheritedField = types.resolveInheritedFieldMember(name)
                .map(
                        member -> {
                    var indexedField = bestMemberDeclarationLocation(member);
                            if (indexedField.isPresent() && !hasBackingField(member)) {
                                return new ResolvedSymbol(
                                        List.of(indexedField.get()),
                                        member.declarationOwnerType,
                                        name,
                                        false,
                                        member,
                                        name);
                            }
                            return resolveField(member.ownerType, name, member);
                        });
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

    private Optional<ResolvedSymbol> methodEarlyReturn(
            String ownerType, String methodName, boolean staticContext, int argumentCount) {
        IndexedMember member = null;
        for (var candidate : typeIndexRouter.ownerMembers(ownerType, staticContext)) {
            if (!isMethodMember(candidate) || !methodName.equals(candidate.name)) {
                continue;
            }
            var arity = candidate.erasedParameterTypes == null ? 0 : candidate.erasedParameterTypes.length;
            if (arity != argumentCount) {
                continue;
            }
            if (member != null && !java.util.Objects.equals(member.canonicalKey, candidate.canonicalKey)) {
                return Optional.empty();
            }
            member = candidate;
        }
        if (member == null
            || bestMemberDeclarationLocation(member).isEmpty()
                || hasBackingField(member)) {
            return Optional.empty();
        }
        return Optional.of(
                new ResolvedSymbol(
                List.of(bestMemberDeclarationLocation(member).get()),
                        member.declarationOwnerType,
                        methodName,
                        true,
                        member,
                        methodName));
    }

    private Optional<ResolvedSymbol> methodEarlyReturn(
            IndexedType ownerType, String methodName, boolean staticContext, int argumentCount) {
        if (ownerType == null) {
            return Optional.empty();
        }
        return methodEarlyReturn(ownerType.qualifiedName, methodName, staticContext, argumentCount);
    }

    private Optional<ResolvedSymbol> visibleLocalDeclaration(
            ParseTask parse, ParseTypeResolver types, String name) {
        return types.resolveVisibleDeclaration(name)
                .filter(local -> !(parentLeaf(local) instanceof ClassTree))
                .map(
                        local -> {
                            var location = FindHelper.location(parse, local, name);
                            if (location == null) {
                                return unsupported(name);
                            }
                            return new ResolvedSymbol(List.of(location), null, name, false, null, name);
                        });
    }

    private Optional<ParseTypeResolver.TypeResolution> resolveReceiver(
            ParseTypeResolver types, Tree expression) {
        if (expression == null) {
            return Optional.empty();
        }
        var resolved = types.resolveExpression(expression);
        if (resolved.isPresent()) {
            return resolved;
        }
        if (!(expression instanceof IdentifierTree identifier)) {
            return Optional.empty();
        }
        var visible = types.resolveVisibleDeclaration(identifier.getName().toString());
        if (visible.isEmpty() || !(visible.get().getLeaf() instanceof VariableTree variable)) {
            return Optional.empty();
        }
        if (variable.getType() != null) {
            var declared = types.resolveTypeTree(variable.getType(), false);
            if (declared.isPresent()) {
                return declared;
            }
        }
        if (variable.getInitializer() != null) {
            return types.resolveExpression(variable.getInitializer());
        }
        return Optional.empty();
    }

    private ResolvedSymbol resolveMemberSelect(
            ParseTask parse,
            TreePath path,
            MemberSelectTree memberSelect,
            ParseTypeResolver types) {
        var name = memberSelect.getIdentifier().toString();
        var receiver = resolveReceiver(types, memberSelect.getExpression());
        if (receiver.isEmpty()) {
            receiver =
                    typeIndexRouter
                            .resolveType(memberSelect.getExpression().toString(), parse.root())
                            .map(
                                    type ->
                                            new ParseTypeResolver.TypeResolution(
                                                    type.qualifiedName, true, false));
        }


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
            var member = typeIndexRouter.ownerMember(
                            receiver.get().qualifiedType(), name, receiver.get().staticContext())
                    .filter(this::isFieldLikeMember)
                    .orElse(null);
            if (member != null) {
                var targetOwner = member.declarationOwnerType;
                var indexedField = bestMemberDeclarationLocation(member);
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
            MemberReferenceTree memberReference, ParseTypeResolver types) {
        var name = memberReference.getName().toString();
        if ("new".equals(name)) {
            var receiver = resolveReceiver(types, memberReference.getQualifierExpression());
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
            return resolveQualifiedMethodInvocation(
                    types,
                    target.get().receiverType(),
                    name,
                    List.of(),
                    target.get().argumentCount(),
                    true);
        }
        var receiver = resolveReceiver(types, memberReference.getQualifierExpression());
        if (receiver.isEmpty()) {
            return unsupported(name);
        }
        // A type-qualified method reference like Type::method is ambiguous without a functional
        // target. Unlike normal member selects, fail closed here instead of guessing.
        if (receiver.get().staticContext()) {
            return unsupportedMethod(name);
        }
        var indexed = strictIndexedMethod(receiver.get().qualifiedType(), name, false, 0);
        if (indexed.isPresent()) {
            return indexed.get();
        }
        return resolveQualifiedMethodInvocation(types, receiver.get(), name, List.of(), 0, false);
    }

    /**
     * Resolve a call whose method select is a bare identifier instead of an explicit receiver.
     *
     * <p>Example: in {@code helper("x")}, first try the current enclosing type and its inherited
     * methods using only the method name plus argument count. If that is not enough to pick a
     * unique indexed method, compute argument types and try again before falling back to
     * materializing source locations.
     */
    private ResolvedSymbol resolveUnqualifiedMethodInvocation(
            ParseTask parse, TreePath path, ParseTypeResolver types, MethodInvocationTree invocation, String methodName) {
        var argCount = invocation.getArguments().size();
        var selected =
                selectEnclosingMethodSymbol(parse, path, methodName, argCount, List.of())
                        .or(() -> selectStaticImportMethodSymbol(parse.root(), methodName, argCount, List.of()));
        if (selected.isEmpty()) {
            var argTypes = NavigationSymbolSupport.argumentTypes(invocation.getArguments(), types);
            selected =
                    selectEnclosingMethodSymbol(parse, path, methodName, argCount, argTypes)
                            .or(() -> selectStaticImportMethodSymbol(parse.root(), methodName, argCount, argTypes));
        }
        if (selected.isPresent()) {
            var indexedResult =
                    resolveIndexedMethodResult(
                                    selected.get().ownerType(),
                                    selected.get().methodName(),
                                    selected.get().member())
                            .filter(result -> !result.locations().isEmpty());
            if (indexedResult.isPresent()) {
                return indexedResult.get();
            }
            return materializeSelectedMethod(selected.get());
        }
        return unsupportedMethod(methodName);
    }

    private Optional<SelectedMethod> selectEnclosingMethodSymbol(
            ParseTask parse, TreePath path, String methodName, int argCount, List<String> argTypes) {
        for (var current = nearestClass(path); current != null; current = nearestClass(current.getParentPath())) {
            var ownerType = declaredClassName(parse, current);
            var currentOwner = selectMethodOnOwner(ownerType, methodName, false, argCount, argTypes, false);
            if (currentOwner.isPresent()) {
                return currentOwner;
            }
            var staticOwner = selectMethodOnOwner(ownerType, methodName, true, argCount, argTypes, false);
            if (staticOwner.isPresent()) {
                return staticOwner;
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve a receiver-qualified call or method-reference target from index data before paying
     * for heavier source materialization.
     *
     * <p>Example: for {@code service.find(id)} or {@code formatter::label}, first try to select a
     * unique indexed method by owner, name, static/instance context, and argument count. Only if
     * that is still ambiguous do we compute argument types and retry.
     *
     * <p>When {@code strictArity} is true, the caller already knows the functional-target shape and
     * this method must fail closed on ambiguity instead of returning every overload.
     */
    private ResolvedSymbol resolveQualifiedMethodInvocation(
            ParseTypeResolver types,
            ParseTypeResolver.TypeResolution receiver,
            String methodName,
            List<? extends ExpressionTree> arguments,
            int argumentCount,
            boolean strictArity) {
        var earlyMethod =
                methodEarlyReturn(
                        receiver.qualifiedType(), methodName, receiver.staticContext(), argumentCount);
        if (earlyMethod.isPresent()) {
            return earlyMethod.get();
        }
        var member =
                lookupMethodOnOwner(
                                receiver.qualifiedType(),
                                methodName,
                                receiver.staticContext(),
                                argumentCount,
                                java.util.Collections.emptyList(),
                                strictArity)
                        .or(
                                () ->
                                        receiver.staticContext()
                                                ? Optional.empty()
                                                : lookupInheritedMethod(
                                                        receiver.qualifiedType(),
                                                        methodName,
                                                        argumentCount,
                                                        java.util.Collections.emptyList(),
                                                        strictArity))
                        .orElse(null);
        var indexed = resolveIndexedMethodResult(receiver.qualifiedType(), methodName, member);
        if (indexed.isPresent()) {
            return indexed.get();
        }
        List<String> argTypes = java.util.Collections.emptyList();
        if (member == null) {
            argTypes = NavigationSymbolSupport.argumentTypes(arguments, types);
            member =
                    lookupMethodOnOwner(
                                    receiver.qualifiedType(),
                                    methodName,
                                    receiver.staticContext(),
                                    argumentCount,
                                    argTypes,
                                    strictArity)
                            .orElse(null);
            if (member == null && !receiver.staticContext()) {
                member =
                        lookupInheritedMethod(
                                        receiver.qualifiedType(),
                                        methodName,
                                        argumentCount,
                                        argTypes,
                                        strictArity)
                                .orElse(null);
            }
            indexed = resolveIndexedMethodResult(receiver.qualifiedType(), methodName, member);
            if (indexed.isPresent()) {
                return indexed.get();
            }
        }
        if (strictArity && member == null) {
            return unsupportedMethod(methodName);
        }
        var indexedResult =
                resolveIndexedMethodResult(receiver.qualifiedType(), methodName, member)
                        .filter(result -> !result.locations().isEmpty());
        if (indexedResult.isPresent()) {
            return indexedResult.get();
        }
        return materializeSelectedMethod(
                new SelectedMethod(
                        receiver.qualifiedType(), methodName, argumentCount, argTypes, member));
    }

    private Optional<ResolvedSymbol> resolveIndexedMethodResult(
            String ownerType, String methodName, IndexedMember member) {
        if (member == null) {
            return Optional.empty();
        }
        var targetOwner = member.declarationOwnerType;
        var indexedLocation = bestMemberDeclarationLocation(member);
        if (indexedLocation.isEmpty()) {
            return Optional.empty();
        }
        if (member.backingFieldName != null && !member.backingFieldName.isBlank()) {
            return Optional.of(
                    new ResolvedSymbol(
                            List.of(indexedLocation.get()),
                            targetOwner,
                            member.backingFieldName,
                            false,
                            member,
                            member.backingFieldName));
        }
        return Optional.of(
                new ResolvedSymbol(
                        List.of(indexedLocation.get()),
                        targetOwner,
                        methodName,
                        true,
                        member,
                        methodName));
    }

    private ResolvedSymbol materializeSelectedMethod(SelectedMethod selected) {
        var member = selected.member();
        var targetOwner = member != null ? member.declarationOwnerType : selected.ownerType();
        if (hasBackingField(member)) {
            var linkedLocations = findFieldLocations(targetOwner, member.backingFieldName);
            if (!linkedLocations.isEmpty()) {
                return new ResolvedSymbol(
                        linkedLocations,
                        targetOwner,
                        member.backingFieldName,
                        false,
                        member,
                        member.backingFieldName);
            }
        }
        var direct =
                findMethodLocations(
                        targetOwner,
                        selected.methodName(),
                        selected.argumentCount(),
                        selected.argumentTypes());
        if (!direct.isEmpty()) {
            return new ResolvedSymbol(
                    direct,
                    targetOwner,
                    selected.methodName(),
                    true,
                    member,
                    selected.methodName());
        }
        return new ResolvedSymbol(
                NOT_SUPPORTED,
                targetOwner,
                selected.methodName(),
                true,
                member,
                selected.methodName());
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
        var resolved = typeIndexRouter.resolveType(annotationType.toString(), parse.root());
        if (resolved.isPresent()) {
            return resolveType(resolved.get(), simpleName);
        }
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
        var resolved = typeIndexRouter.resolveType(typeTree.toString(), parse.root());
        if (resolved.isPresent()) {
            return resolveType(resolved.get(), simpleName);
        }
        return resolveEnclosingNestedType(parse, path, simpleName)
                .map(qualifiedType -> resolveTypeName(qualifiedType, simpleName))
                .orElseGet(() -> unsupported(simpleName));
    }

    private ResolvedSymbol resolveConstructorInvocation(
            ParseTask parse, NewClassTree newClassTree, ParseTypeResolver types, String simpleName) {
        var indexedOwner = typeIndexRouter.resolveType(newClassTree.getIdentifier().toString(), parse.root());
        if (indexedOwner.isPresent()) {
            return resolveIndexedConstructorOwner(
                    indexedOwner.get(), simpleName, newClassTree.getArguments().size());
        }

        var ownerType =
                types.resolveTypeTree(newClassTree.getIdentifier(), true)
                        .map(ParseTypeResolver.TypeResolution::qualifiedType);
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

    private ResolvedSymbol resolveIndexedConstructorOwner(
            IndexedType ownerType, String simpleName, int argumentCount) {
        if (ownerType == null) {
            return unsupported(simpleName);
        }
        var constructors = findConstructorLocations(ownerType.qualifiedName, argumentCount);
        if (!constructors.isEmpty()) {
            return new ResolvedSymbol(
                    constructors, ownerType.qualifiedName, simpleName, true, null, simpleName);
        }
        return resolveType(ownerType, simpleName);
    }

    private ResolvedSymbol resolveType(IndexedType type, String simpleName) {
        if (type == null) {
            return unsupported(simpleName);
        }
        var declaration = bestTypeDeclarationLocation(type, simpleName);
        if (declaration.isPresent()) {
            return new ResolvedSymbol(
                    List.of(declaration.get()), type.qualifiedName, null, false, null, simpleName);
        }
        return resolveTypeName(type.qualifiedName, simpleName);
    }

    private ResolvedSymbol resolveTypeName(String qualifiedType, String simpleName) {
        var indexedType =
            typeIndexRouter.ownerTypeInfo(qualifiedType)
                .flatMap(type -> bestTypeDeclarationLocation(type, simpleName));
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
            var member = typeIndexRouter.ownerMember(ownerType, fieldName, true)
                    .filter(this::isFieldLikeMember)
                    .orElse(null);
            ResolvedSymbol resolved;
            if (member != null) {
                var indexedField = bestMemberDeclarationLocation(member);
                if (indexedField.isPresent() && !hasBackingField(member)) {
                    resolved = new ResolvedSymbol(
                            List.of(indexedField.get()),
                            member.declarationOwnerType,
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
        var targetOwner = indexed.declarationOwnerType;
        var location = bestMemberDeclarationLocation(indexed);
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
        var indexedResult =
                resolveIndexedMethodResult(ownerType, methodName, indexed)
                        .filter(result -> !result.locations().isEmpty());
        if (indexedResult.isPresent()) {
            return indexedResult;
        }
        return Optional.of(
                        materializeSelectedMethod(
                                new SelectedMethod(ownerType, methodName, argumentCount, List.of(), indexed)))
                .filter(resolved -> !resolved.locations().isEmpty());
    }

    /**
     * Pick the owner/member pair for an unqualified call before location lookup starts.
     *
     * <p>Example: for {@code helper("x")}, search the current enclosing type first because instance
     * methods beat static imports. If no unique match exists there, try the static-import owners
     * declared in the current compilation unit.
     */
    private Optional<SelectedMethod> selectUnqualifiedMethodSymbol(
            com.sun.source.tree.CompilationUnitTree root,
            Optional<String> currentOwnerType,
            String methodName,
            int argCount,
            List<String> argTypes) {
        if (currentOwnerType.isPresent()) {
            var currentOwner =
                    selectMethodOnOwner(
                            currentOwnerType.get(),
                            methodName,
                            false,
                            argCount,
                            argTypes,
                            false);
            if (currentOwner.isPresent()) {
                return currentOwner;
            }
            // Also probe static methods on the enclosing type — unqualified calls to static
            // methods in the same class are valid in static field initializers and static methods.
            var staticOwner =
                    selectMethodOnOwner(
                            currentOwnerType.get(),
                            methodName,
                            true,
                            argCount,
                            argTypes,
                            false);
            if (staticOwner.isPresent()) {
                return staticOwner;
            }
        }
        return selectStaticImportMethodSymbol(root, methodName, argCount, argTypes);
    }

    private Optional<SelectedMethod> selectMethodOnOwner(
            String ownerType,
            String methodName,
            boolean staticContext,
            int argCount,
            List<String> argTypes,
            boolean strictArity) {
        var member = lookupMethod(ownerType, methodName, staticContext, argCount, argTypes, strictArity).orElse(null);
        if (member == null) {
            return Optional.empty();
        }
        return Optional.of(
                new SelectedMethod(
                        ownerType,
                        methodName,
                        argCount,
                        argTypes == null ? List.of() : List.copyOf(argTypes),
                        member));
    }

    private Optional<SelectedMethod> selectStaticImportMethodSymbol(
            com.sun.source.tree.CompilationUnitTree root,
            String methodName,
            int argCount,
            List<String> argTypes) {
        SelectedMethod match = null;
        for (var ownerType : WorkspaceTypeIndex.staticImportOwnerTypes(methodName, root)) {
            var member = lookupMethod(ownerType, methodName, true, argCount, argTypes, false).orElse(null);
            if (member == null) {
                continue;
            }
            var selected =
                    new SelectedMethod(
                            ownerType,
                            methodName,
                            argCount,
                            argTypes == null ? List.of() : List.copyOf(argTypes),
                            member);
            if (match != null
                    && !java.util.Objects.equals(match.member().canonicalKey, selected.member().canonicalKey)) {
                return Optional.empty();
            }
            match = selected;
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

        var staticMember = typeIndexRouter.ownerMember(ownerType.get(), fieldName, true)
                .filter(this::isFieldLikeMember)
                .orElse(null);
        if (staticMember != null) {
            var indexedField = bestMemberDeclarationLocation(staticMember);
            if (indexedField.isPresent() && !hasBackingField(staticMember)) {
                return Optional.of(
                        new ResolvedSymbol(
                                List.of(indexedField.get()),
                                staticMember.declarationOwnerType,
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

        var instanceMember = typeIndexRouter.ownerMember(ownerType.get(), fieldName, false)
                .filter(this::isFieldLikeMember)
                .orElse(null);
        if (instanceMember != null) {
            var indexedField = bestMemberDeclarationLocation(instanceMember);
            if (indexedField.isPresent() && !hasBackingField(instanceMember)) {
                return Optional.of(
                        new ResolvedSymbol(
                                List.of(indexedField.get()),
                                instanceMember.declarationOwnerType,
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
        var location = FindHelper.location(parse, path, simpleName);
        if (location == null) {
            return new ResolvedSymbol(NOT_SUPPORTED, ownerType, simpleName, true, null, simpleName);
        }
        return new ResolvedSymbol(
                List.of(location), ownerType, simpleName, true, null, simpleName);
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

    private Optional<ResolvedSymbol> resolveEnclosingField(ParseTask parse, TreePath path, String fieldName) {
        for (var current = nearestClass(path); current != null; current = nearestClass(current.getParentPath())) {
            var ownerType = declaredClassName(parse, current);
            var classTree = (ClassTree) current.getLeaf();
            for (var memberTree : classTree.getMembers()) {
                if (!(memberTree instanceof VariableTree variable) || !variable.getName().contentEquals(fieldName)) {
                    continue;
                }
                var location = FindHelper.location(parse, new TreePath(current, variable), variable.getName());
                if (location != null) {
                    return Optional.of(new ResolvedSymbol(List.of(location), ownerType, fieldName, false, null, fieldName));
                }
            }
            var member = typeIndexRouter.ownerMember(ownerType, fieldName, false)
                    .filter(this::isFieldLikeMember)
                    .orElse(null);
            if (member == null) {
                member = typeIndexRouter.ownerMember(ownerType, fieldName, true)
                        .filter(this::isFieldLikeMember)
                        .orElse(null);
            }
            if (member == null) {
                continue;
            }
            var indexedField = bestMemberDeclarationLocation(member);
            if (indexedField.isPresent() && !hasBackingField(member)) {
                return Optional.of(
                        new ResolvedSymbol(
                                List.of(indexedField.get()),
                                member.declarationOwnerType,
                                fieldName,
                                false,
                                member,
                                fieldName));
            }
            return Optional.of(resolveField(ownerType, fieldName, member));
        }
        return Optional.empty();
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
        var direct = lookupMethodOnOwner(ownerType, methodName, staticContext, argumentCount, argTypes, strictArity);
        if (direct.isPresent() || staticContext) {
            return direct;
        }
        return lookupInheritedMethod(ownerType, methodName, argumentCount, argTypes, strictArity);
    }

    private Optional<IndexedMember> lookupMethodOnOwner(
            String ownerType,
            String methodName,
            boolean staticContext,
            int argumentCount,
            List<String> argTypes,
            boolean strictArity) {
        if (argTypes.size() == argumentCount && argTypes.stream().noneMatch(String::isBlank)) {
            var withArgs = typeIndexRouter.ownerMember(ownerType, methodName, staticContext, argTypes.toArray(String[]::new));
            var method = withArgs.filter(this::isMethodMember);
            if (method.isPresent()) {
                return method;
            }
        }
        return uniqueMethodByArity(ownerType, methodName, staticContext, argumentCount);
    }

    private Optional<IndexedMember> lookupInheritedMethod(
            String ownerType,
            String methodName,
            int argumentCount,
            List<String> argTypes,
            boolean strictArity) {
        var visited = new LinkedHashSet<String>();
        var queue = new ArrayList<>(typeIndexRouter.directSupertypes(ownerType));
        IndexedMember match = null;
        while (!queue.isEmpty()) {
            var current = queue.remove(0);
            if (!visited.add(current)) {
                continue;
            }
            var candidate = lookupMethodOnOwner(current, methodName, false, argumentCount, argTypes, strictArity);
            if (candidate.isPresent()) {
                if (match != null && !java.util.Objects.equals(match.canonicalKey, candidate.get().canonicalKey)) {
                    return Optional.empty();
                }
                match = candidate.get();
            }
            queue.addAll(typeIndexRouter.directSupertypes(current));
        }
        return Optional.ofNullable(match);
    }

    /**
     * Return a unique indexed method overload when method name and parameter count alone are
     * sufficient to disambiguate it.
     *
     * <p>Arity means parameter count. Example: {@code foo()} has arity {@code 0}, {@code foo(int)}
     * has arity {@code 1}, and {@code foo(String, int)} has arity {@code 2}.
     *
     * <p>If multiple different overloads share the same name and arity on the same owner, return
     * empty and let the caller use stricter matching.
     */
    private Optional<IndexedMember> uniqueMethodByArity(
            String ownerType, String methodName, boolean staticContext, int argumentCount) {
        if (ownerType == null || ownerType.isBlank() || methodName == null || methodName.isBlank() || argumentCount < 0) {
            return Optional.empty();
        }
        IndexedMember match = null;
        for (var candidate : typeIndexRouter.ownerMembers(ownerType, staticContext)) {
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
        var targetOwner = member != null ? member.declarationOwnerType : ownerType;
        var indexedLocation = typeIndexRouter.memberDeclarationLocation(member);
        if (indexedLocation.isPresent()) {
            var displayName = (member != null && member.backingFieldName != null && !member.backingFieldName.isBlank())
                    ? member.backingFieldName : fieldName;
            return new ResolvedSymbol(
                    List.of(indexedLocation.get()),
                    targetOwner,
                    displayName,
                    false,
                    member,
                    displayName);
        }
        if (hasBackingField(member)) {
            var linkedLocations = findFieldLocations(targetOwner, member.backingFieldName);
            if (!linkedLocations.isEmpty()) {
                return new ResolvedSymbol(
                        linkedLocations,
                        targetOwner,
                        member.backingFieldName,
                        false,
                        member,
                        member.backingFieldName);
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

    private Optional<Location> bestTypeDeclarationLocation(IndexedType type, String simpleName) {
        if (type == null) {
            return Optional.empty();
        }
        if (typeIndexRouter.ownerStore(type.qualifiedName) == TypeIndexRouter.OwnerStore.WORKSPACE) {
            var locations = findTypeLocations(type.qualifiedName, simpleName);
            if (!locations.isEmpty()) {
                return Optional.of(locations.get(0));
            }
        }
        return type.declarationLocation();
    }

    private Optional<Location> bestMemberDeclarationLocation(IndexedMember member) {
        if (member == null) {
            return Optional.empty();
        }
        var workspaceLocation = currentWorkspaceMemberDeclarationLocation(member);
        if (workspaceLocation.isPresent()) {
            return workspaceLocation;
        }
        return typeIndexRouter.memberDeclarationLocation(member);
    }

    private Optional<Location> currentWorkspaceMemberDeclarationLocation(IndexedMember member) {
        if (member == null
                || typeIndexRouter.ownerStore(member.declarationOwnerType) != TypeIndexRouter.OwnerStore.WORKSPACE) {
            return Optional.empty();
        }
        if (member.kind == CompletionItemKind.Method) {
            var argCount = member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length;
            var argTypes = member.declaredParameterTypes == null ? List.<String>of() : List.of(member.declaredParameterTypes);
            var locations = findMethodLocations(member.declarationOwnerType, member.name, argCount, argTypes);
            if (!locations.isEmpty()) {
                return Optional.of(locations.get(0));
            }
        }
        if (member.kind == CompletionItemKind.Field && !hasBackingField(member)) {
            var locations = findFieldLocations(member.declarationOwnerType, member.name);
            if (!locations.isEmpty()) {
                return Optional.of(locations.get(0));
            }
        }
        return Optional.empty();
    }

    // Location opening and declaration matching.
    private List<Location> findMethodLocations(
            String lookupOwner, String methodName, int argCount, List<String> argTypes) {
        var store = typeIndexRouter.ownerStore(lookupOwner);
        var source = openTypeSource(lookupOwner, store);
        if (source.isPresent()) {
            var locations = locateMethod(source.get(), methodName, argCount, argTypes);
            if (!locations.isEmpty()) {
                return locations;
            }
        }
        var decompiled = openDecompiledTypeSource(lookupOwner, store);
        return decompiled
                .map(typeSource -> locateMethod(typeSource, methodName, argCount, argTypes))
                .orElseGet(List::of);
    }

    private List<Location> findFieldLocations(String lookupOwner, String fieldName) {
        var store = typeIndexRouter.ownerStore(lookupOwner);
        var attached = findAttachedSourceFieldLocations(lookupOwner, fieldName, store);
        if (!attached.isEmpty()) {
            return attached;
        }
        var source = openTypeSource(lookupOwner, store);
        if (source.isPresent()) {
            var locations = locateField(source.get(), fieldName);
            if (!locations.isEmpty()) {
                return locations;
            }
        }
        var decompiled = openDecompiledTypeSource(lookupOwner, store);
        return decompiled.map(typeSource -> locateField(typeSource, fieldName)).orElseGet(List::of);
    }

    private List<Location> findAttachedSourceFieldLocations(
            String ownerType, String fieldName, TypeIndexRouter.OwnerStore store) {
        if (store != TypeIndexRouter.OwnerStore.EXTERNAL) {
            return List.of();
        }
        return attachedExternalSource(ownerType)
                .map(sourceFile -> findFieldLocationsInFile(sourceFile, fieldName))
                .orElseGet(List::of);
    }

    private List<Location> findConstructorLocations(String ownerType, int argCount) {
        var source = openTypeSource(ownerType, typeIndexRouter.ownerStore(ownerType));
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
        var store = typeIndexRouter.ownerStore(qualifiedType);
        var source = openTypeSource(qualifiedType, store);
        if (source.isPresent()) {
            var locations = locateType(source.get(), labelName);
            if (!locations.isEmpty()) {
                return locations;
            }
        }
        var decompiled = openDecompiledTypeSource(qualifiedType, store);
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
    private Optional<TypeSource> openTypeSource(
            String qualifiedType, TypeIndexRouter.OwnerStore store) {
        return openedTypeSources.computeIfAbsent(
                qualifiedType, key -> loadTypeSource(key, store));
    }

    private Optional<TypeSource> loadTypeSource(
            String qualifiedType, TypeIndexRouter.OwnerStore store) {
        return switch (store) {
            case WORKSPACE -> loadWorkspaceTypeSource(qualifiedType);
            case EXTERNAL -> loadExternalTypeSource(qualifiedType);
            case NONE -> Optional.empty();
        };
    }

    private Optional<TypeSource> loadWorkspaceTypeSource(String qualifiedType) {
        var workspace = typeIndexRouter.workspace().typeInfo(qualifiedType).orElse(null);
        if (workspace == null || workspace.sourcePath == null) {
            return Optional.empty();
        }
        return openParsedSource(compiler.parse(workspace.sourcePath), qualifiedType);
    }

    private Optional<TypeSource> loadExternalTypeSource(String qualifiedType) {
        var indexedExternal = typeIndexRouter.external().typeInfo(qualifiedType).orElse(null);
        if (indexedExternal != null && indexedExternal.sourcePath != null) {
            return openParsedSource(compiler.parse(indexedExternal.sourcePath), qualifiedType);
        }

        var direct =
                attachedExternalSource(qualifiedType)
                        .flatMap(source -> openParsedSource(compiler.parse(source), qualifiedType));
        if (direct.isPresent()) {
            return direct;
        }

        if (indexedExternal != null) {
            var nested = openIndexedOuterSource(indexedExternal, qualifiedType);
            if (nested.isPresent()) {
                return nested;
            }
        }

        for (var outer : fallbackOuterTypes(qualifiedType)) {
            if (typeIndexRouter.ownerStore(outer) != TypeIndexRouter.OwnerStore.EXTERNAL) {
                continue;
            }
            var nested =
                    attachedExternalSource(outer)
                            .flatMap(source -> openParsedSource(compiler.parse(source), qualifiedType));
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private Optional<TypeSource> openIndexedOuterSource(IndexedType indexedExternal, String qualifiedType) {
        for (var outer : nearestOuters(indexedExternal)) {
            if (typeIndexRouter.ownerStore(outer) != TypeIndexRouter.OwnerStore.EXTERNAL) {
                continue;
            }
            var nested =
                    attachedExternalSource(outer)
                            .flatMap(source -> openParsedSource(compiler.parse(source), qualifiedType));
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private Optional<TypeSource> openParsedSource(ParseTask parse, String qualifiedType) {
        return findTypePath(parse, qualifiedType).map(path -> new TypeSource(parse, path));
    }

    private Optional<JavaFileObject> attachedExternalSource(String qualifiedType) {
        var cached = attachedExternalSources.get(qualifiedType);
        if (cached != null) {
            return Optional.of(cached);
        }
        var source = compiler.findAnywhere(qualifiedType);
        if (source.isPresent() && source.get().getKind() == JavaFileObject.Kind.SOURCE) {
            attachedExternalSources.put(qualifiedType, source.get());
            return source;
        }
        return Optional.empty();
    }

    private List<String> nearestOuters(IndexedType indexed) {
        if (indexed == null || indexed.enclosingTypes.isEmpty()) {
            return List.of();
        }
        var ordered = new ArrayList<String>(indexed.enclosingTypes);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    private List<String> fallbackOuterTypes(String qualifiedType) {
        var outers = new ArrayList<String>();
        for (var i = qualifiedType.lastIndexOf('.'); i > 0; i = qualifiedType.lastIndexOf('.', i - 1)) {
            outers.add(qualifiedType.substring(0, i));
        }
        return outers;
    }

    private Optional<TypeSource> openDecompiledTypeSource(
            String qualifiedType, TypeIndexRouter.OwnerStore store) {
        if (store != TypeIndexRouter.OwnerStore.EXTERNAL) {
            return Optional.empty();
        }
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
            var uri = FindHelper.normalizeUri(sourceFile.toUri());
            return List.of(new Location(uri, range));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

}
