package org.javacs.provider;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaFileObject;

import org.javacs.*;
import org.javacs.index.IndexedMember;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.Location;
import org.javacs.navigation.NavigationSymbolSupport;

/**
 * Resolves go-to-definition using an ATTR compile of the current file.
 *
 * <p>The primary entry point is {@link #find()}, which calls {@link #resolveSymbol()} to compile
 * the current file and locate the declaration via javac's {@link Trees#getElement} and {@link
 * Trees#getPath} APIs. When the declaration is in another file, {@link #resolveElementCrossFile}
 * opens the source via the type index.
 */
public class DefinitionProvider {

    private record TypeSource(ParseTask task, TreePath classPath) {}

    private record FieldTarget(TreePath path, String name) {}

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
    private final Map<String, Optional<TypeSource>> openedTypeSources = new HashMap<>();
    private final Map<String, Optional<TypeSource>> openedWorkspaceSources = new HashMap<>();
    private final Map<String, Optional<TypeSource>> openedExternalSources = new HashMap<>();
    private final Path file;
    private final int line;
    private final int column;

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
        // Use an ATTR compile of the current file only. javac resolves cross-file types
        // automatically via SOURCE_PATH during attribution, so Trees.getElement(path) is
        // still fully typed. For cross-file declarations, Trees.getPath(element) either
        // succeeds (SOURCE_PATH files are entered into javac's compiledTopLevels) or returns
        // null and the fallback resolveElementCrossFile() handles it via the type index.
        // This reuses the completion ATTR cache — definition is always a cache hit.
        try (var compile = compiler.compile(file)) {
            var root = compile.root(file);
            long cursor;
            try {
                cursor =
                        FileStore.offset(
                                root.getSourceFile().getCharContent(true).toString(), line, column);
            } catch (IOException e) {
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
            var trees = compile.trees;
            var element = trees.getElement(path);
            // Handle case where local variable name shadows a method name
            // e.g. var packageName = packageName(className);
            String mismatchName = null;
            if (element != null
                    && element.getKind() != ElementKind.METHOD
                    && element.getKind() != ElementKind.CONSTRUCTOR) {
                if (path.getLeaf() instanceof IdentifierTree mismatchId
                        && path.getParentPath() != null
                        && path.getParentPath().getLeaf() instanceof MethodInvocationTree mismatchInv
                        && mismatchInv.getMethodSelect() == path.getLeaf()) {
                    mismatchName = mismatchId.getName().toString();
                } else if (path.getLeaf() instanceof VariableTree varTree
                        && varTree.getInitializer() != null) {
                    mismatchName = varTree.getName().toString();
                }
            }
            if (mismatchName != null) {
                var encClassPath = nearestClass(path);
                if (encClassPath != null && encClassPath.getLeaf() instanceof ClassTree encClass) {
                    var allLocs = new ArrayList<Location>();
                    for (var m : encClass.getMembers()) {
                        if (!(m instanceof MethodTree mt)) continue;
                        if (!mt.getName().contentEquals(mismatchName)) continue;
                        var mPath = new TreePath(encClassPath, m);
                        var l = FindHelper.location(compile, mPath, mismatchName);
                        if (l != null) allLocs.add(l);
                    }
                    if (!allLocs.isEmpty()) {
                        var encClassEl = trees.getElement(encClassPath);
                        var qualName =
                                encClassEl instanceof TypeElement te
                                        ? te.getQualifiedName().toString()
                                        : null;
                        return new ResolvedSymbol(
                                allLocs, qualName, mismatchName, true, null, mismatchName);
                    }
                }
            }
            if (element == null) {
                if (compiler.lombokPresentOnClasspath()) {
                    var builderField =
                            lombokBuilderFieldFromReceiver(compile, trees, path, selectedName);
                    if (builderField.isPresent()) {
                        var target = builderField.get();
                        var location = FindHelper.location(compile, target.path(), target.name());
                        if (location != null) {
                            return new ResolvedSymbol(
                                    List.of(location),
                                    null,
                                    target.name(),
                                    false,
                                    null,
                                    target.name());
                        }
                    }
                }
                return unsupported(null);
            }
            var declarationPath = trees.getPath(element);

            if (element.getKind() == ElementKind.METHOD
                    && element instanceof ExecutableElement method) {
                var enclosingClass =
                        (TypeElement) method.getEnclosingElement();
                var enclosingType = enclosingClass.asType();
                var types = compile.types;
                var elements = compile.elements;
                for (var superClass : types.directSupertypes(enclosingType)) {
                    var e = (TypeElement) types.asElement(superClass);
                    for (var other : e.getEnclosedElements()) {
                        if (!(other instanceof ExecutableElement otherMethod))
                            continue;
                        if (elements.overrides(method, otherMethod, enclosingClass)) {
                            element = otherMethod;
                            declarationPath = trees.getPath(otherMethod);
                            if (declarationPath != null) {
                                break;
                            }
                        }
                    }
                    if (element != method) break;
                }
            }

            // Edge case: constructors may not have a direct path; navigate to the enclosing class.
            if (declarationPath == null
                    && element.getKind() == ElementKind.CONSTRUCTOR) {
                declarationPath = trees.getPath(element.getEnclosingElement());
            }
            // Edge case: record component accessor methods navigate to the record component.
            if (declarationPath == null
                    && element.getKind() == ElementKind.METHOD
                    && element.getEnclosingElement()
                            instanceof TypeElement type
                    && type.getKind() == ElementKind.RECORD) {
                for (var member : type.getEnclosedElements()) {
                    if (member.getKind()
                                    == ElementKind.RECORD_COMPONENT
                            && member.getSimpleName().contentEquals(element.getSimpleName())) {
                        declarationPath = trees.getPath(member);
                        break;
                    }
                }
                // trees.getPath may return null for synthetic record components in ATTR compiles;
                // fall back to scanning the in-batch compilation unit directly.
                if (declarationPath == null) {
                    var componentName = element.getSimpleName().toString();
                    var ownerName = type.getQualifiedName().toString();
                    for (var batchRoot : compile.roots) {
                        var classTree = FindHelper.findType(
                                new ParseTask(compile.task, batchRoot), ownerName);
                        if (classTree == null) continue;
                        var classPath = new TreePath(new TreePath(batchRoot), classTree);
                        for (var member : classTree.getMembers()) {
                            if (member instanceof VariableTree vt
                                    && vt.getName().contentEquals(componentName)) {
                                declarationPath = new TreePath(classPath, member);
                                break;
                            }
                        }
                        if (declarationPath != null) break;
                    }
                }
            }
            if (declarationPath != null) {
                // In-batch declaration found; resolve directly.
                CharSequence declarationName = element.getSimpleName();
                if (element.getKind() == ElementKind.CONSTRUCTOR) {
                    declarationName = element.getEnclosingElement().getSimpleName();
                }
                var location = FindHelper.location(compile, declarationPath, declarationName);
                if (compiler.lombokPresentOnClasspath()) {
                    var lombokField =
                            lombokGeneratedField(
                                    compile,
                                    trees,
                                    element,
                                    selectedName,
                                    declarationPath,
                                    location);
                    if (lombokField.isPresent()) {
                        declarationPath = lombokField.get().path();
                        declarationName = lombokField.get().name();
                        location =
                                FindHelper.location(
                                        compile, declarationPath, declarationName);
                    }
                }
                if (location == null) {
                    return unsupported(declarationName.toString());
                }
                var enclosing = element.getEnclosingElement();
                var qualifiedType =
                        element instanceof TypeElement t
                                ? t.getQualifiedName().toString()
                                : enclosing instanceof TypeElement t
                                        ? t.getQualifiedName().toString()
                                        : null;
                var isMethod =
                        element.getKind() == ElementKind.METHOD
                                || element.getKind()
                                        == ElementKind.CONSTRUCTOR;
                var memberName =
                        element instanceof TypeElement
                                ? null
                                : declarationName.toString();
                return new ResolvedSymbol(
                        List.of(location),
                        qualifiedType,
                        memberName,
                        isMethod,
                        null,
                        declarationName.toString());
            }
            // declarationPath is null: element is in another file or is Lombok-generated.
            // Try Lombok same-file field navigation first (Lombok-generated method with field in
            // batch).
            if (compiler.lombokPresentOnClasspath()) {
                var lombokField =
                        lombokGeneratedField(compile, trees, element, selectedName, null, null);
                if (lombokField.isPresent()) {
                    var target = lombokField.get();
                    var location = FindHelper.location(compile, target.path(), target.name());
                    if (location != null) {
                        return new ResolvedSymbol(
                                List.of(location),
                                null,
                                target.name(),
                                false,
                                null,
                                target.name());
                    }
                }
            }
            return resolveElementCrossFile(element);
        }
    }

    private ResolvedSymbol resolveElementCrossFile(
            Element element) {
        var kind = element.getKind();
        if (kind == ElementKind.CLASS
                || kind == ElementKind.INTERFACE
                || kind == ElementKind.ENUM
                || kind == ElementKind.RECORD
                || kind == ElementKind.ANNOTATION_TYPE) {
            var typeElement = (TypeElement) element;
            var qualifiedName = typeElement.getQualifiedName().toString();
            var simpleName = element.getSimpleName().toString();
            var source = openSourceForElement(typeElement);
            var locations = source.map(s -> locateType(s, simpleName)).orElseGet(List::of);
            return new ResolvedSymbol(
                    locations.isEmpty() ? NOT_SUPPORTED : locations,
                    qualifiedName,
                    null,
                    false,
                    null,
                    simpleName);
        }
        if (!(element.getEnclosingElement() instanceof TypeElement owner)) {
            return unsupported(element.getSimpleName().toString());
        }
        var ownerQualified = owner.getQualifiedName().toString();
        var memberName = element.getSimpleName().toString();
        if (kind == ElementKind.FIELD
                || kind == ElementKind.ENUM_CONSTANT) {
            var source = openSourceForElement(owner);
            var locations = source.map(s -> locateField(s, memberName)).orElseGet(List::of);
            return new ResolvedSymbol(
                    locations.isEmpty() ? NOT_SUPPORTED : locations,
                    ownerQualified,
                    memberName,
                    false,
                    null,
                    memberName);
        }
        if (kind == ElementKind.METHOD) {
            var exec = (ExecutableElement) element;
            var argCount = exec.getParameters().size();
            var ownerSimple = owner.getSimpleName().toString();

            // Record component accessor: always navigate to the component field.
            if (owner.getKind() == ElementKind.RECORD) {
                var source = openSourceForElement(owner);
                var fieldLocations =
                        source.map(s -> locateField(s, memberName)).orElseGet(List::of);
                if (!fieldLocations.isEmpty()) {
                    return new ResolvedSymbol(
                            fieldLocations,
                            ownerQualified,
                            memberName,
                            false,
                            null,
                            memberName);
                }
            }

            if (compiler.lombokPresentOnClasspath()) {
                // Builder chain: method on a generated Builder class (e.g. .field1(v).build()).
                var builderOwner = LombokAnnotations.builderOwner(owner);
                if (builderOwner.isPresent()) {
                    var outerQualified = builderOwner.get().getQualifiedName().toString();
                    var outerSimple = builderOwner.get().getSimpleName().toString();
                    var outerSource = openSourceForElement(builderOwner.get());
                    // Builder setter methods share their name with the field in the outer class.
                    if (!memberName.equals("build") && !memberName.equals("toBuilder")) {
                        var fieldLocations =
                                outerSource
                                        .map(s -> locateField(s, memberName))
                                        .orElseGet(List::of);
                        if (!fieldLocations.isEmpty()) {
                            return new ResolvedSymbol(
                                    fieldLocations,
                                    outerQualified,
                                    memberName,
                                    false,
                                    null,
                                    memberName);
                        }
                    }
                    // build() / toBuilder() / unmatched builder setter → outer class declaration.
                    var classLocations =
                            outerSource
                                    .map(s -> locateType(s, outerSimple))
                                    .orElseGet(List::of);
                    return new ResolvedSymbol(
                            !classLocations.isEmpty() ? classLocations : NOT_SUPPORTED,
                            outerQualified,
                            null,
                            false,
                            null,
                            outerSimple);
                }
            }

            // 1. workspace — look up method in workspace source.
            var workspaceSource = openWorkspaceSourceForElement(owner);
            var wsLocations =
                    workspaceSource
                            .map(s -> locateMethod(s, memberName, argCount, List.of()))
                            .orElseGet(List::of);
            if (!wsLocations.isEmpty()) {
                return new ResolvedSymbol(
                        wsLocations, ownerQualified, memberName, true, null, memberName);
            }

            // 2. workspace - lombok — Lombok heuristics on workspace source.
            if (compiler.lombokPresentOnClasspath() && workspaceSource.isPresent()) {
                var fieldName = LombokAnnotations.accessorFieldName(memberName);
                if (fieldName.isPresent()) {
                    var fieldLocations =
                            workspaceSource
                                    .map(s -> locateField(s, fieldName.get()))
                                    .orElseGet(List::of);
                    if (!fieldLocations.isEmpty()) {
                        return new ResolvedSymbol(
                                fieldLocations,
                                ownerQualified,
                                fieldName.get(),
                                false,
                                null,
                                fieldName.get());
                    }
                }
                var classLocations =
                        workspaceSource
                                .map(s -> locateType(s, ownerSimple))
                                .orElseGet(List::of);
                if (!classLocations.isEmpty()) {
                    return new ResolvedSymbol(
                            classLocations, ownerQualified, null, false, null, ownerSimple);
                }
            }

            // 3. external — look up method in decompiled source.
            var externalSource = openExternalSourceForElement(owner);
            var extLocations =
                    externalSource
                            .map(s -> locateMethod(s, memberName, argCount, List.of()))
                            .orElseGet(List::of);
            if (!extLocations.isEmpty()) {
                return new ResolvedSymbol(
                        extLocations, ownerQualified, memberName, true, null, memberName);
            }

            // 4. external - lombok — Lombok heuristics on decompiled source.
            if (compiler.lombokPresentOnClasspath() && externalSource.isPresent()) {
                var fieldName = LombokAnnotations.accessorFieldName(memberName);
                if (fieldName.isPresent()) {
                    var fieldLocations =
                            externalSource
                                    .map(s -> locateField(s, fieldName.get()))
                                    .orElseGet(List::of);
                    if (!fieldLocations.isEmpty()) {
                        return new ResolvedSymbol(
                                fieldLocations,
                                ownerQualified,
                                fieldName.get(),
                                false,
                                null,
                                fieldName.get());
                    }
                }
                var classLocations =
                        externalSource
                                .map(s -> locateType(s, ownerSimple))
                                .orElseGet(List::of);
                if (!classLocations.isEmpty()) {
                    return new ResolvedSymbol(
                            classLocations, ownerQualified, null, false, null, ownerSimple);
                }
            }

            return new ResolvedSymbol(
                    NOT_SUPPORTED, ownerQualified, memberName, true, null, memberName);
        }
        if (kind == ElementKind.CONSTRUCTOR) {
            var exec = (ExecutableElement) element;
            var argCount = exec.getParameters().size();
            var constructorName = owner.getSimpleName().toString();
            var source = openSourceForElement(owner);
            if (source.isPresent()) {
                var s = source.get();
                var classTree = (ClassTree) s.classPath.getLeaf();
                var results = new ArrayList<Location>();
                for (var member : classTree.getMembers()) {
                    if (!(member instanceof MethodTree method)) continue;
                    if (method.getReturnType() != null) continue;
                    if (argCount >= 0 && method.getParameters().size() != argCount) continue;
                    var methodPath = new TreePath(s.classPath, method);
                    var location = FindHelper.location(s.task, methodPath, constructorName);
                    if (location != null) results.add(location);
                }
                if (!results.isEmpty()) {
                    return new ResolvedSymbol(
                            results, ownerQualified, constructorName, true, null, constructorName);
                }
                if (compiler.lombokPresentOnClasspath()) {
                    // Lombok-generated constructor (e.g. @AllArgsConstructor) → class declaration.
                    var classLocations = locateType(s, constructorName);
                    return new ResolvedSymbol(
                            !classLocations.isEmpty() ? classLocations : NOT_SUPPORTED,
                            ownerQualified,
                            null,
                            false,
                            null,
                            constructorName);
                }
            }
            return new ResolvedSymbol(
                    NOT_SUPPORTED, ownerQualified, constructorName, true, null, constructorName);
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
            CompileTask compile,
            Trees trees,
            Element element,
            String selectedName,
            TreePath declarationPath,
            Location location) {
        if (selectedName == null || selectedName.isBlank()) {
            return Optional.empty();
        }
        var owner = LombokAnnotations.fieldOwner(element);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        var targetField =
                LombokAnnotations.accessorFieldName(selectedName)
                        .flatMap(
                                fieldName ->
                                        findFieldInType(compile, trees, owner.get(), fieldName));
        if (targetField.isEmpty()) {
            targetField =
                    LombokAnnotations.builderOwner(owner.get())
                            .flatMap(
                                    builderOwner ->
                                            findFieldInType(
                                                    compile, trees, builderOwner, selectedName));
        }
        if (targetField.isEmpty()
                && LombokAnnotations.builderOwner(owner.get()).isPresent()) {
            targetField = findFieldInType(compile, trees, owner.get(), selectedName);
        }
        if (targetField.isEmpty()) {
            return Optional.empty();
        }
        if (element.getKind() == ElementKind.METHOD
                && location != null
                && !LombokAnnotations.isLombokAnnotationTree(declarationPath)
                && declarationPath != null
                && declarationPath.getLeaf() instanceof MethodTree
                && LombokAnnotations.locationContainsName(location, selectedName)) {
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

    private Optional<FieldTarget> lombokBuilderFieldFromReceiver(
            CompileTask compile,
            Trees trees,
            TreePath path,
            String selectedName) {
        if (selectedName == null || selectedName.isBlank()) {
            return Optional.empty();
        }
        if (!(path.getLeaf() instanceof MemberSelectTree memberSelect)) {
            return Optional.empty();
        }
        var receiverType =
                trees.getTypeMirror(new TreePath(path, memberSelect.getExpression()));
        if (receiverType == null) {
            return Optional.empty();
        }
        var receiverElement = compile.types.asElement(receiverType);
        if (!(receiverElement instanceof TypeElement receiver)) {
            return Optional.empty();
        }
        return LombokAnnotations.builderOwner(receiver)
                .flatMap(
                        builderOwner ->
                                findFieldInType(compile, trees, builderOwner, selectedName));
    }

    private class FindMemberSelectAt
            extends TreePathScanner<TreePath, Long> {
        private final CompileTask compile;
        private CompilationUnitTree root;

        FindMemberSelectAt(CompileTask compile) {
            this.compile = compile;
        }

        @Override
        public TreePath visitCompilationUnit(CompilationUnitTree tree, Long cursor) {
            root = tree;
            return super.visitCompilationUnit(tree, cursor);
        }

        @Override
        public TreePath visitMemberSelect(MemberSelectTree tree, Long cursor) {
            var positions = compile.trees.getSourcePositions();
            var end = (int) positions.getEndPosition(root, tree);
            // Only match cursor on the right-hand identifier (method name),
            // never on the expression (variable name) which may share the same name.
            var exprEnd = (int) positions.getEndPosition(root, tree.getExpression());
            if (exprEnd >= 0 && exprEnd < end) {
                var nameStart =
                        FindHelper.findNameIn(
                                root, tree.getIdentifier(), exprEnd, end, cursor);
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

    private Optional<FieldTarget> findFieldInType(
            CompileTask compile,
            Trees trees,
            TypeElement type,
            String fieldName) {
        for (var member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.FIELD) {
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
        if (superclass == null || superclass.getKind() == TypeKind.NONE) {
            return Optional.empty();
        }
        var superElement = compile.types.asElement(superclass);
        if (superElement instanceof TypeElement superType) {
            return findFieldInType(compile, trees, superType, fieldName);
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

    private String declaredClassName(ParseTask parse, TreePath path) {
        var classes = new ArrayList<String>();
        for (var current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof ClassTree classTree) {
                classes.add(classTree.getSimpleName().toString());
            }
        }
        Collections.reverse(classes);
        var packageName =
                parse.root().getPackageName() == null
                        ? ""
                        : parse.root().getPackageName().toString();
        return packageName.isEmpty()
                ? String.join(".", classes)
                : packageName + "." + String.join(".", classes);
    }

    private ResolvedSymbol unsupported(String simpleName) {
        return new ResolvedSymbol(NOT_SUPPORTED, null, null, false, null, simpleName);
    }

    // Location opening and declaration matching.

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
        if (argCount >= 0
                && !argTypes.isEmpty()
                && argTypes.stream().noneMatch(String::isBlank)) {
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

    // Source opening.

    private Optional<TypeSource> openParsedSource(ParseTask parse, String qualifiedType) {
        return findTypePath(parse, qualifiedType).map(path -> new TypeSource(parse, path));
    }

    /**
     * Open a parse-only {@link TypeSource} for the given owner element without touching the type
     * index.
     *
     * <p>The element is cast to {@link ClassSymbol}. If {@code
     * sourcefile} is non-null and of kind {@link javax.tools.JavaFileObject.Kind#SOURCE} (workspace
     * source or attached external source), it is parsed directly. For binary-only types javac sets
     * {@code sourcefile} to an internal {@code ClassReader$SourceFileObject} whose kind is {@code
     * CLASS} — calling {@code getCharContent()} on it throws {@link
     * UnsupportedOperationException}. Those cases fall through to Vineflower decompilation.
     */
    private Optional<TypeSource> openSourceForElement(
            TypeElement typeElement) {
        var qualifiedName = typeElement.getQualifiedName().toString();
        return openedTypeSources.computeIfAbsent(
                qualifiedName,
                key -> {
                    if (typeElement instanceof ClassSymbol sym
                            && sym.sourcefile != null
                            && sym.sourcefile.getKind()
                                    == JavaFileObject.Kind.SOURCE
                            && sym.sourcefile.toUri().isAbsolute()) {
                        // Real source-backed file (workspace source or attached source jar).
                        // ClassReader$SourceFileObject is excluded because its toUri() returns a
                        // relative URI (just the bare filename from the SourceFile bytecode
                        // attribute)
                        // and its getCharContent() throws UnsupportedOperationException.
                        return openParsedSource(compiler.parse(sym.sourcefile), key);
                    }
                    // Binary-only (JDK platform types, jars without attached sources, or external
                    // types whose ClassSymbol.sourcefile is a ClassReader$SourceFileObject):
                    // decompile with Vineflower.
                    return compiler.decompileClass(key)
                            .map(compiler::parse)
                            .flatMap(parse -> openParsedSource(parse, key));
                });
    }

    /** Returns source only if this element has an absolute-URI workspace source file. */
    private Optional<TypeSource> openWorkspaceSourceForElement(
            TypeElement typeElement) {
        if (!(typeElement instanceof ClassSymbol sym)
                || sym.sourcefile == null
                || sym.sourcefile.getKind() != JavaFileObject.Kind.SOURCE
                || !sym.sourcefile.toUri().isAbsolute()) {
            return Optional.empty();
        }
        var qualifiedName = typeElement.getQualifiedName().toString();
        return openedWorkspaceSources.computeIfAbsent(
                qualifiedName, key -> openParsedSource(compiler.parse(sym.sourcefile), key));
    }

    /** Returns decompiled source only if this element does not have a workspace source file. */
    private Optional<TypeSource> openExternalSourceForElement(
            TypeElement typeElement) {
        if (typeElement instanceof ClassSymbol sym
                && sym.sourcefile != null
                && sym.sourcefile.getKind() == JavaFileObject.Kind.SOURCE
                && sym.sourcefile.toUri().isAbsolute()) {
            return Optional.empty();
        }
        var qualifiedName = typeElement.getQualifiedName().toString();
        return openedExternalSources.computeIfAbsent(
                qualifiedName,
                key ->
                        compiler
                                .decompileClass(key)
                                .map(compiler::parse)
                                .flatMap(parse -> openParsedSource(parse, key)));
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
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                var current = getCurrentPath();
                if (current != null
                        && qualifiedType.equals(declaredClassName(parse, current))) {
                    match[0] = current;
                    return null;
                }
                return super.visitClass(classTree, unused);
            }
        }.scan(parse.root(), null);
        return Optional.ofNullable(match[0]);
    }

    private boolean matchesArgumentTypes(
            ParseTask parse,
            TreePath classPath,
            MethodTree method,
            List<String> argTypes) {
        if (method.getParameters().size() != argTypes.size()) {
            return false;
        }
        if (argTypes.isEmpty() || argTypes.stream().anyMatch(String::isBlank)) {
            return false;
        }
        var parameterTypes =
                NavigationSymbolSupport.declaredParameterTypes(
                        parse,
                        new TreePath(classPath, method),
                        method,
                        typeIndexRouter,
                        compiler);
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

    private Location findRecordComponentLocation(
            ParseTask parse, TreePath classPath, String fieldName) {
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
        } catch (IOException e) {
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
        var componentStart =
                FindHelper.findNameIn(root, fieldName, start, bodyStart);
        if (componentStart < 0) {
            return null;
        }
        var range =
                FileStore.range(
                        contents.toString(),
                        componentStart,
                        componentStart + fieldName.length());
        return new Location(root.getSourceFile().toUri(), range);
    }
}
