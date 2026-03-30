package org.javacs.completion;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TreeVisitor;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import org.javacs.CompileTask;
import org.javacs.LombokAnnotations;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.resolve.TypeNames;

/**
 * Canonical store for indexed workspace type and member metadata.
 *
 * <p>This class owns the type graph, member keys, inherited-member traversal, and import-aware
 * type-name resolution over the published snapshot. Wrapper indexes should stay thin and route
 * requests here instead of recreating this logic in helper classes.
 *
 * <p>Synthetic workspace members such as Lombok accessors and builders are modeled here as normal
 * index entries. That keeps completion, definition, and references on the same symbol graph
 * instead of layering navigation-only fallback heuristics on top.
 */
public class WorkspaceTypeIndex {
    public static final WorkspaceTypeIndex EMPTY =
            new WorkspaceTypeIndex(Map.of(), Map.of(), Map.of(), Map.of());
    private static final Set<String> PRIMITIVE_TYPE_NAMES =
            Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char", "void");
    private static final Comparator<Member> MEMBER_ORDER =
            Comparator.comparingInt((Member member) -> member.priority)
                    .thenComparing(member -> member.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(member -> member.detail);

    /**
     * Indexed member metadata used by completion, definition, and references.
     *
     * <p>Debugging guide:
     *
     * <p>{@code ownerType}: the declaring or synthetic owner, for example
     * {@code com.example.LineItem} or {@code com.example.LineItem.LineItemBuilder}
     *
     * <p>{@code name}: the user-facing member name, for example {@code getSku}, {@code family},
     * or {@code build}
     *
     * <p>{@code canonicalKey}: the unique storage key for one concrete member signature, for
     * example {@code com.example.LineItem.LineItemBuilder#family(java.lang.String)}
     *
     * <p>{@code logicalKey}: the semantic identity shared across generated views of the same source
     * symbol, for example a Lombok getter, setter, or builder setter pointing back to
     * {@code com.example.LineItem#family}
     *
     * <p>{@code backingFieldName}: the source field name that a generated member maps back to
     *
     * <p>{@code priority}: 0 for declared members, 1 for inherited members, 2 for
     * {@code java.lang.Object} members
     *
     * <p>{@code synthetic}: true when the index created the member rather than reading a declared
     * source/binary member directly
     */
    public static class Member {
        public final String ownerType;
        public final String name;
        public final int kind;
        public final boolean isStatic;
        public final boolean isPrivate;
        public final int priority;
        public final String detail;
        public final String returnType;
        public final String[] parameterNames;
        public final String[] erasedParameterTypes;
        public final String canonicalKey;
        public final String logicalKey;
        public final String backingFieldName;
        public final boolean synthetic;

        public Member(
                String ownerType,
                String name,
                int kind,
                boolean isStatic,
                boolean isPrivate,
                int priority,
                String detail,
                String returnType,
                String[] parameterNames,
                String[] erasedParameterTypes,
                String canonicalKey,
                String logicalKey,
                String backingFieldName,
                boolean synthetic) {
            this.ownerType = ownerType;
            this.name = name;
            this.kind = kind;
            this.isStatic = isStatic;
            this.isPrivate = isPrivate;
            this.priority = priority;
            this.detail = detail;
            this.returnType = returnType;
            this.parameterNames = parameterNames;
            this.erasedParameterTypes = erasedParameterTypes;
            this.canonicalKey = canonicalKey;
            this.logicalKey = logicalKey;
            this.backingFieldName = backingFieldName;
            this.synthetic = synthetic;
        }
    }

    public static class TypeInfo {
        public final String qualifiedName;
        public final String simpleName;
        public final List<Member> members;
        public final boolean fromCompiledRoot;
        public final Path sourcePath;
        public final String superclass;
        public final List<String> interfaces;

        TypeInfo(
                String qualifiedName,
                String simpleName,
                List<Member> members,
                boolean fromCompiledRoot,
                Path sourcePath,
                String superclass,
                List<String> interfaces) {
            this.qualifiedName = qualifiedName;
            this.simpleName = simpleName;
            this.members = Collections.unmodifiableList(new ArrayList<>(members));
            this.fromCompiledRoot = fromCompiledRoot;
            this.sourcePath = sourcePath;
            this.superclass = superclass;
            this.interfaces = Collections.unmodifiableList(new ArrayList<>(interfaces));
        }
    }

    private final Map<String, TypeInfo> typesByQualifiedName;
    private final Map<Path, Set<String>> workspaceTypesByFile;
    private final Map<String, Set<String>> workspaceSupertypesByType;
    private final Map<String, Set<String>> subtypesByType;
    private static final Logger LOG = Logger.getLogger("main");

    private WorkspaceTypeIndex(
            Map<String, TypeInfo> typesByQualifiedName,
            Map<Path, Set<String>> workspaceTypesByFile,
            Map<String, Set<String>> workspaceSupertypesByType,
            Map<String, Set<String>> subtypesByType) {
        var verified = new Object2ObjectLinkedOpenHashMap<String, TypeInfo>();
        for (var entry : typesByQualifiedName.entrySet()) {
            var key = entry.getKey();
            var valid = key != null && (key.contains(".") || isPrimitiveTypeName(key));
            assert valid : "WorkspaceTypeIndex key must be fully qualified or primitive: " + key;
            if (!valid) {
                throw new IllegalStateException("WorkspaceTypeIndex key must be fully qualified or primitive: " + key);
            }
            verified.put(key, entry.getValue());
        }
        this.typesByQualifiedName = Collections.unmodifiableMap(verified);
        var verifiedWorkspaceTypes = new Object2ObjectLinkedOpenHashMap<Path, Set<String>>();
        for (var entry : workspaceTypesByFile.entrySet()) {
            verifiedWorkspaceTypes.put(
                    entry.getKey(),
                    Collections.unmodifiableSet(new ObjectLinkedOpenHashSet<>(entry.getValue())));
        }
        this.workspaceTypesByFile = Collections.unmodifiableMap(verifiedWorkspaceTypes);
        this.workspaceSupertypesByType = immutableSetMap(workspaceSupertypesByType);
        this.subtypesByType = immutableSetMap(subtypesByType);
    }

    private static Map<String, Set<String>> immutableSetMap(Map<String, Set<String>> source) {
        var copy = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        for (var entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableSet(new ObjectLinkedOpenHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public Map<String, TypeInfo> types() {
        return typesByQualifiedName;
    }

    public int size() {
        return typesByQualifiedName.size();
    }

    public boolean containsType(String qualifiedName) {
        return typesByQualifiedName.containsKey(qualifiedName);
    }

    public Optional<TypeInfo> typeInfo(String qualifiedName) {
        return Optional.ofNullable(typesByQualifiedName.get(qualifiedName));
    }

    public WorkspaceTypeIndex filterTypes(Predicate<TypeInfo> keep) {
        var nextTypes = new Object2ObjectLinkedOpenHashMap<String, TypeInfo>();
        for (var entry : typesByQualifiedName.entrySet()) {
            if (keep.test(entry.getValue())) {
                nextTypes.put(entry.getKey(), entry.getValue());
            }
        }

        var nextWorkspaceTypes = new Object2ObjectLinkedOpenHashMap<Path, Set<String>>();
        for (var entry : workspaceTypesByFile.entrySet()) {
            var kept = new ObjectLinkedOpenHashSet<String>();
            for (var qualifiedName : entry.getValue()) {
                if (nextTypes.containsKey(qualifiedName)) {
                    kept.add(qualifiedName);
                }
            }
            if (!kept.isEmpty()) {
                nextWorkspaceTypes.put(entry.getKey(), kept);
            }
        }

        var nextWorkspaceSupertypes = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        for (var entry : workspaceSupertypesByType.entrySet()) {
            if (!nextTypes.containsKey(entry.getKey())) {
                continue;
            }
            var kept = new ObjectLinkedOpenHashSet<String>();
            for (var superType : entry.getValue()) {
                if (nextTypes.containsKey(superType)) {
                    kept.add(superType);
                }
            }
            nextWorkspaceSupertypes.put(entry.getKey(), kept);
        }

        var nextSubtypes = invertSubtypeMap(nextWorkspaceSupertypes);
        return new WorkspaceTypeIndex(nextTypes, nextWorkspaceTypes, nextWorkspaceSupertypes, nextSubtypes);
    }

    public WorkspaceTypeIndex replaceWorkspaceDeclarations(WorkspaceTypeIndex updates, Set<Path> replacedFiles) {
        if ((updates == null || updates.workspaceTypesByFile.isEmpty()) && (replacedFiles == null || replacedFiles.isEmpty())) {
            return this;
        }
        var nextTypes = new Object2ObjectLinkedOpenHashMap<String, TypeInfo>(typesByQualifiedName);
        var nextWorkspaceTypes = new Object2ObjectLinkedOpenHashMap<Path, Set<String>>();
        for (var entry : workspaceTypesByFile.entrySet()) {
            nextWorkspaceTypes.put(entry.getKey(), new ObjectLinkedOpenHashSet<>(entry.getValue()));
        }
        var nextWorkspaceSupertypes = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        for (var entry : workspaceSupertypesByType.entrySet()) {
            nextWorkspaceSupertypes.put(entry.getKey(), new ObjectLinkedOpenHashSet<>(entry.getValue()));
        }
        var nextSubtypes = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        for (var entry : subtypesByType.entrySet()) {
            nextSubtypes.put(entry.getKey(), new ObjectLinkedOpenHashSet<>(entry.getValue()));
        }

        var filesToReplace = new ObjectLinkedOpenHashSet<Path>();
        if (replacedFiles != null) {
            filesToReplace.addAll(replacedFiles);
        }
        if (updates != null) {
            filesToReplace.addAll(updates.workspaceTypesByFile.keySet());
        }

        for (var file : filesToReplace) {
            var previousTypes = nextWorkspaceTypes.remove(file);
            if (previousTypes == null) {
                continue;
            }
            for (var qualifiedName : previousTypes) {
                removeWorkspaceHierarchy(qualifiedName, nextWorkspaceSupertypes, nextSubtypes);
                var existing = nextTypes.get(qualifiedName);
                if (existing != null && file.equals(existing.sourcePath)) {
                    nextTypes.remove(qualifiedName);
                }
            }
        }

        if (updates != null) {
            for (var entry : updates.workspaceTypesByFile.entrySet()) {
                var file = entry.getKey();
                var declaredTypes = new ObjectLinkedOpenHashSet<String>(entry.getValue());
                nextWorkspaceTypes.put(file, declaredTypes);
                for (var qualifiedName : declaredTypes) {
                    var typeInfo = updates.typesByQualifiedName.get(qualifiedName);
                    if (typeInfo != null) {
                        nextTypes.put(qualifiedName, typeInfo);
                    }
                    var supertypes =
                            new ObjectLinkedOpenHashSet<>(
                                    updates.workspaceSupertypesByType.getOrDefault(qualifiedName, Set.of()));
                    nextWorkspaceSupertypes.put(qualifiedName, supertypes);
                    for (var superType : supertypes) {
                        nextSubtypes
                                .computeIfAbsent(superType, __ -> new ObjectLinkedOpenHashSet<>())
                                .add(qualifiedName);
                    }
                }
            }
        }

        return new WorkspaceTypeIndex(nextTypes, nextWorkspaceTypes, nextWorkspaceSupertypes, nextSubtypes);
    }

    private void removeWorkspaceHierarchy(
            String qualifiedName, Map<String, Set<String>> supertypesByType, Map<String, Set<String>> subtypesByType) {
        var previousSupertypes = supertypesByType.remove(qualifiedName);
        if (previousSupertypes == null) {
            return;
        }
        for (var superType : previousSupertypes) {
            var subtypes = subtypesByType.get(superType);
            if (subtypes == null) {
                continue;
            }
            subtypes.remove(qualifiedName);
            if (subtypes.isEmpty()) {
                subtypesByType.remove(superType);
            }
        }
    }

    public List<Member> members(String qualifiedName, boolean staticContext) {
        var type = typesByQualifiedName.get(qualifiedName);
        if (type == null) {
            return List.of();
        }
        var list = new ArrayList<Member>();
        var seen = new ObjectLinkedOpenHashSet<String>();
        addDirectMembers(type, staticContext, list, seen);
        addInheritedMembers(qualifiedName, staticContext, list, seen);
        return list;
    }

    public Optional<Member> member(String qualifiedName, String name, boolean staticContext) {
        var type = typesByQualifiedName.get(qualifiedName);
        if (type == null) {
            return Optional.empty();
        }
        var direct = directMember(type, name, staticContext);
        if (direct.isPresent()) {
            return direct;
        }
        return inheritedMember(qualifiedName, name, staticContext, null);
    }

    public Optional<Member> member(String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var type = typesByQualifiedName.get(qualifiedName);
        if (type == null) {
            return Optional.empty();
        }
        var targetKey = canonicalMemberKey(qualifiedName, CompletionItemKind.Method, name, erasedParameterTypes);
        var direct = directMethodMember(type, staticContext, targetKey);
        if (direct.isPresent()) {
            return direct;
        }
        return inheritedMember(qualifiedName, name, staticContext, erasedParameterTypes);
    }

    public Optional<Member> memberByCanonicalKey(String canonicalKey) {
        if (canonicalKey == null || canonicalKey.isBlank()) {
            return Optional.empty();
        }
        var split = canonicalKey.indexOf('#');
        if (split <= 0) {
            return Optional.empty();
        }
        var ownerType = canonicalKey.substring(0, split);
        var type = typesByQualifiedName.get(ownerType);
        if (type == null) {
            return Optional.empty();
        }
        for (var member : type.members) {
            if (Objects.equals(member.canonicalKey, canonicalKey)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public Set<String> subtypes(String qualifiedName) {
        var subtypes = subtypesByType.get(qualifiedName);
        if (subtypes == null) {
            return Set.of();
        }
        return subtypes;
    }

    public Set<String> directSupertypes(String qualifiedName) {
        var type = typesByQualifiedName.get(qualifiedName);
        return directSupertypes(type);
    }

    public Set<String> relatedMethodKeys(String ownerType, String methodName, String[] erasedParameterTypes) {
        var parameterTypes = erasedParameterTypes == null ? new String[0] : erasedParameterTypes;
        var keys = new ObjectLinkedOpenHashSet<String>();
        var visitedTypes = new ObjectLinkedOpenHashSet<String>();
        var pending = new java.util.ArrayDeque<String>();
        pending.add(ownerType);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visitedTypes.add(current)) {
                continue;
            }
            member(current, methodName, false, parameterTypes).ifPresent(member -> keys.add(member.canonicalKey));
            member(current, methodName, true, parameterTypes).ifPresent(member -> keys.add(member.canonicalKey));
            for (var superType : directSupertypes(current)) {
                if (!superType.isBlank()) {
                    pending.addLast(superType);
                }
            }
            for (var subtype : subtypes(current)) {
                pending.addLast(subtype);
            }
        }
        return keys;
    }

    private void addDirectMembers(
            TypeInfo type, boolean staticContext, List<Member> members, Set<String> seenStorageKeys) {
        for (var member : type.members) {
            if (staticContext != member.isStatic) {
                continue;
            }
            var storageKey = memberStorageKey(member);
            if (!seenStorageKeys.add(storageKey)) {
                continue;
            }
            members.add(member);
        }
    }

    private void addInheritedMembers(
            String qualifiedName, boolean staticContext, List<Member> members, Set<String> seenStorageKeys) {
        var visited = new ObjectLinkedOpenHashSet<String>();
        var pending = new java.util.ArrayDeque<>(directSupertypes(qualifiedName));
        while (!pending.isEmpty()) {
            var superType = pending.removeFirst();
            if (!visited.add(superType)) {
                continue;
            }
            var type = typesByQualifiedName.get(superType);
            if (type == null) {
                continue;
            }
            for (var member : type.members) {
                if (staticContext != member.isStatic || member.isPrivate) {
                    continue;
                }
                var storageKey = memberStorageKey(member);
                if (!seenStorageKeys.add(storageKey)) {
                    continue;
                }
                members.add(member);
            }
            pending.addAll(directSupertypes(superType));
        }
    }

    private Optional<Member> directMember(TypeInfo type, String name, boolean staticContext) {
        for (var member : type.members) {
            if (staticContext != member.isStatic) {
                continue;
            }
            if (!Objects.equals(name, member.name)) {
                continue;
            }
            return Optional.of(member);
        }
        return Optional.empty();
    }

    private Optional<Member> directMethodMember(TypeInfo type, boolean staticContext, String targetKey) {
        for (var member : type.members) {
            if (staticContext != member.isStatic) {
                continue;
            }
            if (member.kind != CompletionItemKind.Method) {
                continue;
            }
            if (Objects.equals(targetKey, member.canonicalKey)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    private Optional<Member> inheritedMember(
            String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var visited = new ObjectLinkedOpenHashSet<String>();
        var pending = new java.util.ArrayDeque<>(directSupertypes(qualifiedName));
        while (!pending.isEmpty()) {
            var superType = pending.removeFirst();
            if (!visited.add(superType)) {
                continue;
            }
            var type = typesByQualifiedName.get(superType);
            if (type == null) {
                continue;
            }
            for (var member : type.members) {
                if (staticContext != member.isStatic || member.isPrivate) {
                    continue;
                }
                if (erasedParameterTypes != null) {
                    if (member.kind == CompletionItemKind.Method
                            && Objects.equals(name, member.name)
                            && Arrays.equals(
                                    erasedParameterTypes == null ? new String[0] : erasedParameterTypes,
                                    member.erasedParameterTypes == null ? new String[0] : member.erasedParameterTypes)) {
                        return Optional.of(member);
                    }
                } else if (Objects.equals(name, member.name)) {
                    return Optional.of(member);
                }
            }
            pending.addAll(directSupertypes(superType));
        }
        return Optional.empty();
    }

    public static String canonicalMemberKey(String ownerType, int kind, String name, String[] erasedParameterTypes) {
        if (kind == CompletionItemKind.Method || kind == CompletionItemKind.Constructor) {
            var params = erasedParameterTypes == null ? new String[0] : erasedParameterTypes;
            return ownerType + "#" + name + "(" + String.join(",", params) + ")";
        }
        return ownerType + "#" + name;
    }

    public static List<String> staticImportOwnerTypes(String memberName, CompilationUnitTree root) {
        if (memberName == null || memberName.isBlank() || root == null) {
            return List.of();
        }
        var owners = new ObjectLinkedOpenHashSet<String>();
        for (var importTree : root.getImports()) {
            if (!importTree.isStatic()) continue;
            var imported = importTree.getQualifiedIdentifier().toString();
            staticImportOwnerType(imported, memberName).ifPresent(owners::add);
        }
        return List.copyOf(owners);
    }

    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var raw = normalizeTypeName(typeName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (isPrimitiveTypeName(raw)) {
            return Optional.of(raw);
        }
        if (typesByQualifiedName.containsKey(raw)) {
            return Optional.of(raw);
        }
        if (raw.contains(".")) {
            var firstSegmentEnd = raw.indexOf('.');
            if (firstSegmentEnd <= 0) {
                return Optional.empty();
            }
            var firstSegment = raw.substring(0, firstSegmentEnd);
            var suffix = raw.substring(firstSegmentEnd);
            var resolvedPrefix = resolveSimpleName(firstSegment, root);
            if (resolvedPrefix.isEmpty()) {
                return Optional.empty();
            }
            var resolved = resolvedPrefix.get() + suffix;
            if (typesByQualifiedName.containsKey(resolved)) {
                return Optional.of(resolved);
            }
            return Optional.empty();
        }
        return resolveSimpleName(raw, root);
    }

    private Optional<String> resolveSimpleName(String simpleName, CompilationUnitTree root) {
        return resolveSimpleName(simpleName, root, typesByQualifiedName::containsKey);
    }

    private static Optional<String> staticImportOwnerType(String imported, String memberName) {
        if (imported == null || imported.isBlank()) {
            return Optional.empty();
        }
        if (imported.endsWith(".*")) {
            return Optional.of(imported.substring(0, imported.length() - 2));
        }
        if (!imported.endsWith("." + memberName)) {
            return Optional.empty();
        }
        return Optional.of(imported.substring(0, imported.length() - memberName.length() - 1));
    }

    public static boolean isPrimitiveTypeName(String typeName) {
        return PRIMITIVE_TYPE_NAMES.contains(typeName);
    }

    public static WorkspaceTypeIndex from(CompileTask task) {
        return from(task, true);
    }

    public static WorkspaceTypeIndex workspaceDeclarations(CompileTask task) {
        return from(task, false);
    }

    private static WorkspaceTypeIndex from(CompileTask task, boolean includeReferencedTypes) {
        var trees = Trees.instance(task.task);
        var elements = task.task.getElements();
        var types = task.task.getTypes();

        var rootDeclaredTypeSources = new Object2ObjectOpenHashMap<String, Path>();
        var rootDeclaredTypeTrees = new Object2ObjectOpenHashMap<String, ClassTree>();
        var collectedTypes = new Object2ObjectLinkedOpenHashMap<String, TypeElement>();
        var seenMirrors = new ObjectOpenHashSet<String>();
        var skippedInvalidTypeKeys = new ObjectLinkedOpenHashSet<String>();
        for (var root : task.roots) {
            Path sourcePath = null;
            var sourceUri = root.getSourceFile().toUri();
            if (sourceUri != null && "file".equals(sourceUri.getScheme())) {
                sourcePath = Paths.get(sourceUri);
            }
            var rootPath = sourcePath;
            new TreePathScanner<Void, Void>() {
                @Override
                    public Void visitClass(ClassTree tree, Void p) {
                        var element = trees.getElement(getCurrentPath());
                        if (element instanceof TypeElement typeElement) {
                            var qualifiedName = typeElement.getQualifiedName().toString();
                            if (isValidIndexKey(qualifiedName)) {
                            collectedTypes.putIfAbsent(qualifiedName, typeElement);
                            if (rootPath != null) {
                                rootDeclaredTypeSources.put(qualifiedName, rootPath);
                            }
                            rootDeclaredTypeTrees.putIfAbsent(qualifiedName, tree);
                            } else if (qualifiedName != null && !qualifiedName.isBlank()) {
                                skippedInvalidTypeKeys.add(qualifiedName);
                            }
                            if (LombokAnnotations.hasLoggingOnlyLombokAnnotation(tree.getModifiers())) {
                                collectNamedType(elements, "org.slf4j.Logger", collectedTypes);
                            }
                        }
                        return super.visitClass(tree, p);
                    }
            }.scan(root, null);

            if (includeReferencedTypes) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitVariable(tree, p);
                    }

                    @Override
                    public Void visitMethod(MethodTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMethod(tree, p);
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMethodInvocation(tree, p);
                    }

                    @Override
                    public Void visitIdentifier(IdentifierTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitIdentifier(tree, p);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMemberSelect(tree, p);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree tree, Void p) {
                        collectTypeMirror(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitNewClass(tree, p);
                    }
                }.scan(root, null);
            }
        }

        var typeEntries = new Object2ObjectLinkedOpenHashMap<String, TypeInfo>();
        var workspaceSupertypes = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        for (var collected : collectedTypes.values()) {
            var qualifiedName = collected.getQualifiedName().toString();
            if (!isValidIndexKey(qualifiedName)) {
                if (qualifiedName != null && !qualifiedName.isBlank()) {
                    skippedInvalidTypeKeys.add(qualifiedName);
                }
                continue;
            }
            var type = elements.getTypeElement(qualifiedName);
            if (type == null) {
                type = collected;
            } else if (type != collected) {
                LOG.fine(String.format("[completion] canonicalized type symbol %s for index extraction", qualifiedName));
            }

            var seen = new Object2ObjectOpenHashMap<String, Member>();
            for (var member : elements.getAllMembers(type)) {
                if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
                var kind = memberKind(member);
                if (kind == null) continue;

                var ownerElement = member.getEnclosingElement();
                if (!(ownerElement instanceof TypeElement ownerType)) continue;
                var ownerName = ownerType.getQualifiedName().toString();
                var declaredInOwner = qualifiedName.equals(ownerName);
                if (!declaredInOwner && member.getModifiers().contains(Modifier.PRIVATE)) continue;
                var declaredInObject = "java.lang.Object".equals(ownerName);
                var priority = declaredInOwner ? 0 : declaredInObject ? 2 : 1;
                var isStatic = member.getModifiers().contains(Modifier.STATIC);
                var isPrivate = member.getModifiers().contains(Modifier.PRIVATE);

                String detail;
                String returnType;
                String[] parameterNames = null;
                String[] erasedParameterTypes = null;
                if (member instanceof ExecutableElement executable) {
                    detail = executable.getReturnType() + " " + executable;
                    returnType = typeName(executable.getReturnType());
                    parameterNames = new String[executable.getParameters().size()];
                    erasedParameterTypes = new String[executable.getParameters().size()];
                    for (int i = 0; i < executable.getParameters().size(); i++) {
                        var param = executable.getParameters().get(i);
                        parameterNames[i] = param.getSimpleName().toString();
                        erasedParameterTypes[i] = types.erasure(param.asType()).toString();
                    }
                } else {
                    detail = member.asType() + " " + member.getSimpleName();
                    returnType = typeName(member.asType());
                }

                var next =
                        new Member(
                                ownerName,
                                member.getSimpleName().toString(),
                                kind,
                                isStatic,
                                isPrivate,
                                priority,
                                detail,
                                returnType,
                                parameterNames,
                                erasedParameterTypes,
                                canonicalMemberKey(ownerName, kind, member.getSimpleName().toString(), erasedParameterTypes),
                                canonicalMemberKey(ownerName, kind, member.getSimpleName().toString(), erasedParameterTypes),
                                null,
                                false);
                var key = memberStorageKey(next);
                var existing = seen.get(key);
                if (existing == null || next.priority < existing.priority) {
                    seen.put(key, next);
                }
            }
            var declaredTree = rootDeclaredTypeTrees.get(qualifiedName);
            addRecordComponentAccessors(qualifiedName, type, seen);
            addSyntheticLombokAccessors(qualifiedName, declaredTree, seen);
            addSyntheticSlf4jLoggerField(qualifiedName, declaredTree, seen);
            var sourcePath = rootDeclaredTypeSources.get(qualifiedName);
            var fromCompiledRoot = sourcePath != null;
            addSyntheticLombokBuilderType(
                    qualifiedName,
                    declaredTree,
                    sourcePath,
                    fromCompiledRoot,
                    seen,
                    typeEntries,
                    rootDeclaredTypeSources,
                    collectedTypes);
            var members = new ArrayList<>(seen.values());
            sortMembers(members);
            var superclass = directSuperclass(type);
            var interfaces = directInterfaces(type);
            var typeEntry =
                    new TypeInfo(
                            qualifiedName,
                            type.getSimpleName().toString(),
                            Collections.unmodifiableList(members),
                            fromCompiledRoot,
                            sourcePath,
                            superclass,
                            interfaces);
            typeEntries.put(qualifiedName, typeEntry);
            if (sourcePath != null) {
                var supertypes = new ObjectLinkedOpenHashSet<String>();
                if (superclass != null && !superclass.isBlank()) {
                    supertypes.add(superclass);
                }
                supertypes.addAll(interfaces);
                workspaceSupertypes.put(qualifiedName, supertypes);
            }
        }

        normalizeLombokBuilderTypes(typeEntries, rootDeclaredTypeTrees, rootDeclaredTypeSources);

        var workspaceTypes = new Object2ObjectLinkedOpenHashMap<Path, Set<String>>();
        for (var entry : rootDeclaredTypeSources.entrySet()) {
            workspaceTypes.computeIfAbsent(entry.getValue(), __ -> new ObjectLinkedOpenHashSet<>()).add(entry.getKey());
        }
        var subtypes = invertSubtypeMap(workspaceSupertypes);

        if (!skippedInvalidTypeKeys.isEmpty()) {
            LOG.fine(
                    String.format(
                            "[completion] skipped non-fqn types while building index: %s",
                            skippedInvalidTypeKeys));
        }
        return new WorkspaceTypeIndex(
                Collections.unmodifiableMap(typeEntries),
                Collections.unmodifiableMap(workspaceTypes),
                Collections.unmodifiableMap(workspaceSupertypes),
                Collections.unmodifiableMap(subtypes));
    }

    private static Map<String, Set<String>> invertSubtypeMap(Map<String, Set<String>> workspaceSupertypes) {
        var subtypes = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        for (var entry : workspaceSupertypes.entrySet()) {
            for (var superType : entry.getValue()) {
                subtypes.computeIfAbsent(superType, __ -> new ObjectLinkedOpenHashSet<>()).add(entry.getKey());
            }
        }
        return subtypes;
    }

    static void sortMembers(List<Member> members) {
        members.sort(MEMBER_ORDER);
    }

    static Set<String> directSupertypes(TypeInfo type) {
        if (type == null) {
            return Set.of();
        }
        var result = new ObjectLinkedOpenHashSet<String>();
        if (type.superclass != null && !type.superclass.isBlank()) {
            result.add(type.superclass);
        }
        result.addAll(type.interfaces);
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }

    static Optional<String> resolveSimpleName(
            String simpleName, CompilationUnitTree root, Predicate<String> containsType) {
        if (simpleName == null || simpleName.isBlank() || root == null || containsType == null) {
            return Optional.empty();
        }
        var candidates = new ObjectLinkedOpenHashSet<String>();
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!packageName.isBlank()) {
            var samePackage = packageName + "." + simpleName;
            if (containsType.test(samePackage)) {
                candidates.add(samePackage);
            }
        }

        for (var importTree : root.getImports()) {
            if (importTree.isStatic()) {
                continue;
            }
            var imported = importTree.getQualifiedIdentifier().toString();
            if (imported.endsWith("." + simpleName) && containsType.test(imported)) {
                candidates.add(imported);
            }
            if (imported.endsWith(".*")) {
                var candidate = imported.substring(0, imported.length() - 1) + simpleName;
                if (containsType.test(candidate)) {
                    candidates.add(candidate);
                }
            }
        }

        var javaLang = "java.lang." + simpleName;
        if (containsType.test(javaLang)) {
            candidates.add(javaLang);
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        }
        return Optional.empty();
    }

    static String normalizeTypeName(String typeName) {
        return TypeNames.normalize(typeName);
    }

    private static String directSuperclass(TypeElement type) {
        var mirror = type.getSuperclass();
        if (mirror == null || mirror.getKind() == javax.lang.model.type.TypeKind.NONE) {
            return null;
        }
        return typeName(mirror);
    }

    private static List<String> directInterfaces(TypeElement type) {
        var result = new ArrayList<String>();
        for (var iface : type.getInterfaces()) {
            var resolved = typeName(iface);
            if (resolved != null && !resolved.isBlank()) {
                result.add(resolved);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static Integer memberKind(Element member) {
        if (member.getKind() == ElementKind.METHOD) {
            return CompletionItemKind.Method;
        }
        if (member.getKind() == ElementKind.FIELD || member.getKind() == ElementKind.ENUM_CONSTANT) {
            return CompletionItemKind.Field;
        }
        if (member.getKind() == ElementKind.CLASS || member.getKind() == ElementKind.RECORD) {
            return CompletionItemKind.Class;
        }
        if (member.getKind() == ElementKind.INTERFACE || member.getKind() == ElementKind.ANNOTATION_TYPE) {
            return CompletionItemKind.Interface;
        }
        if (member.getKind() == ElementKind.ENUM) {
            return CompletionItemKind.Enum;
        }
        return null;
    }

    private static String typeName(TypeMirror mirror) {
        if (mirror == null) return null;
        if (mirror instanceof DeclaredType declaredType) {
            var element = declaredType.asElement();
            if (element instanceof TypeElement typeElement) {
                var qualifiedName = typeElement.getQualifiedName().toString();
                if (!qualifiedName.isBlank()) {
                    return qualifiedName;
                }
            }
            return declaredType.toString();
        }
        if (mirror instanceof TypeVariable typeVariable) {
            return typeName(typeVariable.getUpperBound());
        }
        if (mirror instanceof ArrayType arrayType) {
            var component = typeName(arrayType.getComponentType());
            if (component == null) {
                return null;
            }
            return component + "[]";
        }
        return mirror.toString();
    }

    private static void collectTypeMirror(
            TypeMirror mirror, Map<String, TypeElement> collectedTypes, Set<String> seenMirrors) {
        if (mirror == null) {
            return;
        }
        var key = mirror.getKind() + ":" + mirror;
        if (!seenMirrors.add(key)) {
            return;
        }
        if (mirror instanceof DeclaredType declaredType) {
            var element = declaredType.asElement();
            if (element instanceof TypeElement typeElement) {
                var qualifiedName = typeElement.getQualifiedName().toString();
                if (isValidIndexKey(qualifiedName)) {
                    collectedTypes.putIfAbsent(qualifiedName, typeElement);
                }
            }
            for (var argument : declaredType.getTypeArguments()) {
                collectTypeMirror(argument, collectedTypes, seenMirrors);
            }
            return;
        }
        if (mirror instanceof TypeVariable typeVariable) {
            collectTypeMirror(typeVariable.getUpperBound(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof ArrayType arrayType) {
            collectTypeMirror(arrayType.getComponentType(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof WildcardType wildcardType) {
            collectTypeMirror(wildcardType.getExtendsBound(), collectedTypes, seenMirrors);
            collectTypeMirror(wildcardType.getSuperBound(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof IntersectionType intersectionType) {
            for (var bound : intersectionType.getBounds()) {
                collectTypeMirror(bound, collectedTypes, seenMirrors);
            }
            return;
        }
        if (mirror instanceof UnionType unionType) {
            for (var alternative : unionType.getAlternatives()) {
                collectTypeMirror(alternative, collectedTypes, seenMirrors);
            }
        }
    }

    private static void collectNamedType(
            javax.lang.model.util.Elements elements,
            String qualifiedName,
            Map<String, TypeElement> collectedTypes) {
        if (!isValidIndexKey(qualifiedName) || collectedTypes.containsKey(qualifiedName)) {
            return;
        }
        var type = elements.getTypeElement(qualifiedName);
        if (type != null) {
            collectedTypes.put(qualifiedName, type);
        }
    }

    private static boolean isValidIndexKey(String key) {
        return key != null && !key.isBlank() && (key.contains(".") || isPrimitiveTypeName(key));
    }

    private static void addSyntheticLombokAccessors(
            String ownerQualifiedName, ClassTree declaration, Map<String, Member> seen) {
        if (declaration == null || !LombokAnnotations.hasAccessorLombokAnnotation(declaration.getModifiers())) {
            return;
        }
        for (var member : declaration.getMembers()) {
            if (!(member instanceof VariableTree variable)) {
                continue;
            }
            if (variable.getName() == null) {
                continue;
            }
            if (variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
                continue;
            }
            var fieldName = variable.getName().toString();
            if (fieldName.isBlank()) {
                continue;
            }
            var fieldType = resolvedFieldType(variable, fieldName, seen);
            var accessors =
                    LombokAnnotations.accessorInfo(
                            declaration.getModifiers(), variable.getModifiers(), fieldName, fieldType);
            if (accessors.isEmpty()) {
                continue;
            }
            var accessorInfo = accessors.get();
            if (accessorInfo.hasGetter()) {
                var getterName = accessorInfo.getterName();
                var fieldKey = canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Field, fieldName, null);
                putSyntheticMethod(
                        seen,
                        new Member(
                                ownerQualifiedName,
                                getterName,
                                CompletionItemKind.Method,
                                false,
                                false,
                                0,
                                fieldType + " " + getterName + "()",
                                fieldType,
                                new String[0],
                                new String[0],
                                canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Method, getterName, new String[0]),
                                fieldKey,
                                fieldName,
                                true));
            }
            if (accessorInfo.hasSetter()) {
                var setterName = accessorInfo.setterName();
                var erasedParameterTypes = new String[] {TypeNames.normalize(accessorInfo.fieldType())};
                var fieldKey = canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Field, fieldName, null);
                putSyntheticMethod(
                        seen,
                        new Member(
                                ownerQualifiedName,
                                setterName,
                                CompletionItemKind.Method,
                                false,
                                false,
                                0,
                                "void " + setterName + "(" + fieldType + " " + fieldName + ")",
                                "void",
                                new String[] {fieldName},
                                erasedParameterTypes,
                                canonicalMemberKey(
                                        ownerQualifiedName,
                                        CompletionItemKind.Method,
                                        setterName,
                                        erasedParameterTypes),
                                fieldKey,
                                fieldName,
                                true));
            }
        }
    }

    private static void addSyntheticLombokBuilderType(
            String ownerQualifiedName,
            ClassTree declaration,
            Path sourcePath,
            boolean fromCompiledRoot,
            Map<String, Member> ownerMembers,
            Map<String, TypeInfo> typeEntries,
            Map<String, Path> rootDeclaredTypeSources,
            Map<String, TypeElement> collectedTypes) {
        if (declaration == null || !LombokAnnotations.hasBuilderLombokAnnotation(declaration.getModifiers())) {
            return;
        }

        var builderQualifiedName = builderQualifiedName(ownerQualifiedName);
        if (typeEntries.containsKey(builderQualifiedName)
                || (collectedTypes != null && collectedTypes.containsKey(builderQualifiedName))) {
            return;
        }

        var fields = declaredInstanceFields(declaration, ownerMembers);
        if (fields.isEmpty()) {
            return;
        }

        putSyntheticMethod(
                ownerMembers,
                new Member(
                        ownerQualifiedName,
                        "builder",
                        CompletionItemKind.Method,
                        true,
                        false,
                        0,
                        builderQualifiedName + " builder()",
                        builderQualifiedName,
                        new String[0],
                        new String[0],
                        canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Method, "builder", new String[0]),
                        canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Method, "builder", new String[0]),
                        null,
                        true));

        var builderMembers = new ArrayList<Member>();
        for (var field : fields) {
            var fieldKey = canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Field, field.name, null);
            var erasedParameterTypes = new String[] {TypeNames.normalize(field.type)};
            builderMembers.add(
                    new Member(
                            builderQualifiedName,
                            field.name,
                            CompletionItemKind.Method,
                            false,
                            false,
                            0,
                            builderQualifiedName + " " + field.name + "(" + field.type + " " + field.name + ")",
                            builderQualifiedName,
                            new String[] {field.name},
                            erasedParameterTypes,
                            canonicalMemberKey(
                                    builderQualifiedName,
                                    CompletionItemKind.Method,
                                    field.name,
                                    erasedParameterTypes),
                            fieldKey,
                            field.name,
                            true));
        }
        builderMembers.add(
                new Member(
                        builderQualifiedName,
                        "build",
                        CompletionItemKind.Method,
                        false,
                        false,
                        0,
                        ownerQualifiedName + " build()",
                        ownerQualifiedName,
                        new String[0],
                        new String[0],
                        canonicalMemberKey(builderQualifiedName, CompletionItemKind.Method, "build", new String[0]),
                        canonicalMemberKey(builderQualifiedName, CompletionItemKind.Method, "build", new String[0]),
                        null,
                        true));
        sortMembers(builderMembers);
        typeEntries.put(
                builderQualifiedName,
                new TypeInfo(
                        builderQualifiedName,
                        TypeNames.simpleName(builderQualifiedName),
                        builderMembers,
                        fromCompiledRoot,
                        sourcePath,
                        null,
                        List.of()));
        if (sourcePath != null) {
            rootDeclaredTypeSources.put(builderQualifiedName, sourcePath);
        }
        LOG.fine(
                () ->
                        String.format(
                                "[completion] indexed synthetic lombok builder owner=%s builder=%s fields=%d",
                                ownerQualifiedName, builderQualifiedName, fields.size()));
    }

    private static void normalizeLombokBuilderTypes(
            Map<String, TypeInfo> typeEntries,
            Map<String, ClassTree> rootDeclaredTypeTrees,
            Map<String, Path> rootDeclaredTypeSources) {
        for (var entry : rootDeclaredTypeTrees.entrySet()) {
            var ownerQualifiedName = entry.getKey();
            var declaration = entry.getValue();
            if (declaration == null || !LombokAnnotations.hasBuilderLombokAnnotation(declaration.getModifiers())) {
                continue;
            }
            var ownerInfo = typeEntries.get(ownerQualifiedName);
            if (ownerInfo == null) {
                continue;
            }
            var fields = declaredInstanceFields(declaration, membersByStorageKey(ownerInfo.members));
            if (fields.isEmpty()) {
                continue;
            }
            var sourcePath = rootDeclaredTypeSources.get(ownerQualifiedName);
            var builderQualifiedName = builderQualifiedName(ownerQualifiedName);
            var existing = typeEntries.get(builderQualifiedName);
            if (existing == null) {
                typeEntries.put(
                        builderQualifiedName,
                        createSyntheticLombokBuilderType(ownerQualifiedName, fields, sourcePath));
            } else {
                typeEntries.put(
                        builderQualifiedName,
                        normalizeLombokBuilderType(existing, ownerQualifiedName, fields, sourcePath));
            }
            if (sourcePath != null) {
                rootDeclaredTypeSources.put(builderQualifiedName, sourcePath);
            }
        }
    }

    private static TypeInfo createSyntheticLombokBuilderType(
            String ownerQualifiedName, List<DeclaredField> fields, Path sourcePath) {
        var builderQualifiedName = builderQualifiedName(ownerQualifiedName);
        var builderMembers = new ArrayList<Member>();
        for (var field : fields) {
            builderMembers.add(linkedBuilderSetter(ownerQualifiedName, builderQualifiedName, field));
        }
        builderMembers.add(builderBuildMethod(ownerQualifiedName, builderQualifiedName));
        sortMembers(builderMembers);
        return new TypeInfo(
                builderQualifiedName,
                TypeNames.simpleName(builderQualifiedName),
                builderMembers,
                sourcePath != null,
                sourcePath,
                null,
                List.of());
    }

    private static TypeInfo normalizeLombokBuilderType(
            TypeInfo existing, String ownerQualifiedName, List<DeclaredField> fields, Path sourcePath) {
        var builderQualifiedName = existing.qualifiedName;
        var members = new Object2ObjectLinkedOpenHashMap<String, Member>();
        for (var member : existing.members) {
            members.put(memberStorageKey(member), member);
        }
        for (var field : fields) {
            var setter = linkedBuilderSetter(ownerQualifiedName, builderQualifiedName, field);
            var key = memberStorageKey(setter);
            var existingMember = members.get(key);
            if (existingMember == null) {
                members.put(key, setter);
                continue;
            }
            members.put(key, mergeLombokFieldLink(existingMember, setter));
        }
        var buildKey = memberStorageKey(builderBuildMethod(ownerQualifiedName, builderQualifiedName));
        members.putIfAbsent(buildKey, builderBuildMethod(ownerQualifiedName, builderQualifiedName));
        var normalizedMembers = new ArrayList<>(members.values());
        sortMembers(normalizedMembers);
        return new TypeInfo(
                builderQualifiedName,
                existing.simpleName,
                normalizedMembers,
                existing.fromCompiledRoot || sourcePath != null,
                existing.sourcePath != null ? existing.sourcePath : sourcePath,
                existing.superclass,
                existing.interfaces);
    }

    private static Member linkedBuilderSetter(
            String ownerQualifiedName, String builderQualifiedName, DeclaredField field) {
        var fieldKey = canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Field, field.name, null);
        var erasedParameterTypes = new String[] {TypeNames.normalize(field.type)};
        return new Member(
                builderQualifiedName,
                field.name,
                CompletionItemKind.Method,
                false,
                false,
                0,
                builderQualifiedName + " " + field.name + "(" + field.type + " " + field.name + ")",
                builderQualifiedName,
                new String[] {field.name},
                erasedParameterTypes,
                canonicalMemberKey(
                        builderQualifiedName,
                        CompletionItemKind.Method,
                        field.name,
                        erasedParameterTypes),
                fieldKey,
                field.name,
                true);
    }

    private static Member builderBuildMethod(String ownerQualifiedName, String builderQualifiedName) {
        return new Member(
                builderQualifiedName,
                "build",
                CompletionItemKind.Method,
                false,
                false,
                0,
                ownerQualifiedName + " build()",
                ownerQualifiedName,
                new String[0],
                new String[0],
                canonicalMemberKey(builderQualifiedName, CompletionItemKind.Method, "build", new String[0]),
                canonicalMemberKey(builderQualifiedName, CompletionItemKind.Method, "build", new String[0]),
                null,
                true);
    }

    private static void addSyntheticSlf4jLoggerField(
            String ownerQualifiedName, ClassTree declaration, Map<String, Member> seen) {
        if (declaration == null || !LombokAnnotations.hasLoggingOnlyLombokAnnotation(declaration.getModifiers())) {
            return;
        }
        var next =
                new Member(
                        ownerQualifiedName,
                        "log",
                        CompletionItemKind.Field,
                        true,
                        true,
                        0,
                        "org.slf4j.Logger log",
                        "org.slf4j.Logger",
                        null,
                        null,
                        canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Field, "log", null),
                        canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Field, "log", null),
                        null,
                        true);
        seen.putIfAbsent(memberStorageKey(next), next);
    }

    private static void putSyntheticMethod(Map<String, Member> seen, Member next) {
        var key = memberStorageKey(next);
        var existing = seen.get(key);
        if (existing == null) {
            seen.put(key, next);
            return;
        }
        if ((existing.backingFieldName == null || existing.backingFieldName.isBlank())
                && next.backingFieldName != null
                && !next.backingFieldName.isBlank()) {
            seen.put(key, mergeLombokFieldLink(existing, next));
        }
    }

    private static void addRecordComponentAccessors(
            String ownerQualifiedName, TypeElement type, Map<String, Member> seen) {
        for (var component : type.getRecordComponents()) {
            var accessor = component.getAccessor();
            if (accessor == null) {
                continue;
            }
            var accessorName = accessor.getSimpleName().toString();
            var key = canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Method, accessorName, new String[0]);
            var existing = seen.get(key);
            var logicalKey = canonicalMemberKey(ownerQualifiedName, CompletionItemKind.Field, component.getSimpleName().toString(), null);
            if (existing == null) {
                seen.put(
                        key,
                        new Member(
                                ownerQualifiedName,
                                accessorName,
                                CompletionItemKind.Method,
                                accessor.getModifiers().contains(Modifier.STATIC),
                                accessor.getModifiers().contains(Modifier.PRIVATE),
                                0,
                                accessor.getReturnType() + " " + accessorName + "()",
                                typeName(accessor.getReturnType()),
                                new String[0],
                                new String[0],
                                key,
                                logicalKey,
                                component.getSimpleName().toString(),
                                false));
                continue;
            }
            if (existing.backingFieldName == null || existing.backingFieldName.isBlank()) {
                seen.put(key, mergeFieldLink(existing, logicalKey, component.getSimpleName().toString(), false));
            }
        }
    }

    private static String memberStorageKey(Member member) {
        return canonicalMemberKey(member.ownerType, member.kind, member.name, member.erasedParameterTypes);
    }

    private static String builderQualifiedName(String ownerQualifiedName) {
        return ownerQualifiedName + "." + TypeNames.simpleName(ownerQualifiedName) + "Builder";
    }

    private static List<DeclaredField> declaredInstanceFields(ClassTree declaration, Map<String, Member> seen) {
        var fields = new ArrayList<DeclaredField>();
        if (declaration == null) {
            return fields;
        }
        for (var member : declaration.getMembers()) {
            if (!(member instanceof VariableTree variable)) {
                continue;
            }
            if (variable.getName() == null || variable.getName().contentEquals("")) {
                continue;
            }
            if (variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
                continue;
            }
            var fieldName = variable.getName().toString();
            if (fieldName.isBlank()) {
                continue;
            }
            fields.add(new DeclaredField(fieldName, resolvedFieldType(variable, fieldName, seen)));
        }
        return fields;
    }

    private static String resolvedFieldType(VariableTree variable, String fieldName, Map<String, Member> seen) {
        var fieldType = variable.getType() == null ? "java.lang.Object" : variable.getType().toString();
        for (var existing : seen.values()) {
            if (existing.kind != CompletionItemKind.Field || existing.isStatic) {
                continue;
            }
            if (!fieldName.equals(existing.name)) {
                continue;
            }
            if (existing.returnType == null || existing.returnType.isBlank()) {
                continue;
            }
            return existing.returnType;
        }
        return fieldType;
    }

    private record DeclaredField(String name, String type) {}

    private static Map<String, Member> membersByStorageKey(List<Member> members) {
        var seen = new Object2ObjectOpenHashMap<String, Member>();
        for (var member : members) {
            seen.put(memberStorageKey(member), member);
        }
        return seen;
    }

    private static Member mergeLombokFieldLink(Member existing, Member synthetic) {
        return mergeFieldLink(existing, synthetic.logicalKey, synthetic.backingFieldName, synthetic.synthetic);
    }

    private static Member mergeFieldLink(
            Member existing, String logicalKey, String backingFieldName, boolean synthetic) {
        return new Member(
                existing.ownerType,
                existing.name,
                existing.kind,
                existing.isStatic,
                existing.isPrivate,
                existing.priority,
                existing.detail,
                existing.returnType,
                existing.parameterNames,
                existing.erasedParameterTypes,
                existing.canonicalKey,
                logicalKey != null && !logicalKey.isBlank()
                        ? logicalKey
                        : existing.logicalKey,
                backingFieldName,
                        existing.synthetic || synthetic);
    }
}
