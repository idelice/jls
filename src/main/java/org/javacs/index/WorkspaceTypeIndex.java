package org.javacs.index;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.function.Predicate;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import org.javacs.CompileTask;
import org.javacs.FindHelper;
import org.javacs.LombokAnnotations;
import org.javacs.ParseTask;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;
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
    public static final WorkspaceTypeIndex EMPTY = new WorkspaceTypeIndex(Map.of(), Map.of());
    public static final class SourceFileSnapshot {
        public final Path sourcePath;
        public final URI sourceUri;
        public final String packageName;
        public final List<String> imports;
        public final List<String> staticImports;
        public final List<String> declaredTypes;

        /**
         * Immutable summary of one source file as seen by the workspace index.
         *
         * <p>This answers: "What simple-name and import context does this file contribute without
         * reparsing it?"
         *
         * <p>Use this when a request-time resolver needs file-level visibility facts such as
         * package, imports, static imports, or declared type names. It is the right snapshot for
         * simple-name resolution in the current file, and the wrong snapshot for member lookup or
         * type-graph traversal.
         *
         * <p>Examples:
         *
         * <pre>{@code
         * sourcePath = /workspace/src/com/example/OrderService.java
         * packageName = "com.example"
         * imports = ["java.util.List", "java.util.stream.Collectors"]
         * staticImports = ["java.util.Map.entry"]
         * declaredTypes = ["com.example.OrderService", "com.example.OrderService.Builder"]
         * }</pre>
         */
        SourceFileSnapshot(
                Path sourcePath,
                URI sourceUri,
                String packageName,
                List<String> imports,
                List<String> staticImports,
                List<String> declaredTypes) {
            this.sourcePath = sourcePath;
            this.sourceUri = sourceUri;
            this.packageName = packageName;
            this.imports = Collections.unmodifiableList(new ArrayList<>(imports));
            this.staticImports = Collections.unmodifiableList(new ArrayList<>(staticImports));
            this.declaredTypes = Collections.unmodifiableList(new ArrayList<>(declaredTypes));
        }
    }

    private final Map<String, IndexedType> typesByQualifiedName;
    private final Set<String> workspaceOwnedTypeNames;
    private final Map<String, Set<String>> subtypesByType;
    private final Map<Path, SourceFileSnapshot> sourceFiles;
    private static final Logger LOG = Logger.getLogger("main");

    private WorkspaceTypeIndex(
            Map<String, IndexedType> typesByQualifiedName,
            Map<Path, SourceFileSnapshot> sourceFiles) {
        var verified = new Object2ObjectLinkedOpenHashMap<String, IndexedType>();
        for (var entry : typesByQualifiedName.entrySet()) {
            var key = entry.getKey();
            var valid = key != null && (key.contains(".") || TypeNames.isPrimitive(key));
            assert valid : "WorkspaceTypeIndex key must be fully qualified or primitive: " + key;
            if (!valid) {
                throw new IllegalStateException("WorkspaceTypeIndex key must be fully qualified or primitive: " + key);
            }
            verified.put(key, entry.getValue());
        }
        this.typesByQualifiedName = Collections.unmodifiableMap(verified);
        this.workspaceOwnedTypeNames = Collections.unmodifiableSet(workspaceOwnedTypeNames(verified));
        this.sourceFiles = Collections.unmodifiableMap(new Object2ObjectLinkedOpenHashMap<>(sourceFiles));
        this.subtypesByType = Collections.unmodifiableMap(invertSubtypeMap(verified));
    }

    private static Set<String> workspaceOwnedTypeNames(Map<String, IndexedType> source) {
        var owned = new ObjectLinkedOpenHashSet<String>();
        for (var entry : source.entrySet()) {
            owned.add(entry.getKey());
            var info = entry.getValue();
            if (info == null || info.enclosingTypes == null) {
                continue;
            }
            owned.addAll(info.enclosingTypes);
        }
        return owned;
    }

    public Map<String, IndexedType> types() {
        return typesByQualifiedName;
    }

    public int size() {
        return typesByQualifiedName.size();
    }

    public boolean containsType(String qualifiedName) {
        return typesByQualifiedName.containsKey(qualifiedName);
    }

    public boolean ownsTypeOrEnclosingType(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return false;
        }
        if (workspaceOwnedTypeNames.contains(qualifiedName)) {
            return true;
        }
        for (var i = qualifiedName.lastIndexOf('.'); i > 0; i = qualifiedName.lastIndexOf('.', i - 1)) {
            var outer = qualifiedName.substring(0, i);
            if (workspaceOwnedTypeNames.contains(outer)) {
                return true;
            }
        }
        return false;
    }

    public Optional<IndexedType> typeInfo(String qualifiedName) {
        return Optional.ofNullable(typesByQualifiedName.get(qualifiedName));
    }

    public Optional<SourceFileSnapshot> sourceFile(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sourceFiles.get(file));
    }

    public WorkspaceTypeIndex filterTypes(Predicate<IndexedType> keep) {
        var nextTypes = new Object2ObjectLinkedOpenHashMap<String, IndexedType>();
        for (var entry : typesByQualifiedName.entrySet()) {
            if (keep.test(entry.getValue())) {
                nextTypes.put(entry.getKey(), entry.getValue());
            }
        }

        var nextSourceFiles = new Object2ObjectLinkedOpenHashMap<Path, SourceFileSnapshot>();
        for (var entry : sourceFiles.entrySet()) {
            var keptTypes = new ArrayList<String>();
            for (var qualifiedName : entry.getValue().declaredTypes) {
                if (nextTypes.containsKey(qualifiedName)) {
                    keptTypes.add(qualifiedName);
                }
            }
            if (!keptTypes.isEmpty()) {
                var snapshot = entry.getValue();
                nextSourceFiles.put(
                        entry.getKey(),
                        new SourceFileSnapshot(
                                snapshot.sourcePath,
                                snapshot.sourceUri,
                                snapshot.packageName,
                                snapshot.imports,
                                snapshot.staticImports,
                                keptTypes));
            }
        }
        return new WorkspaceTypeIndex(nextTypes, nextSourceFiles);
    }

    /**
     * Replace the published declarations for a set of source files.
     *
     * <p>This is a file-granular snapshot update:
     *
     * <ol>
     *   <li>remove the old declared types for every replaced file
     *   <li>drop the old {@link SourceFileSnapshot} entries for those files
     *   <li>install the new file snapshots and declared types from {@code updates}
     * </ol>
     *
     * <p>Example:
     *
     * <pre>{@code
     * before:
     *   /src/A.java -> ["com.example.A", "com.example.A.Helper"]
     *
     * after replacing /src/A.java with a snapshot that only declares com.example.A:
     *   old A.Helper is removed from the published workspace index
     * }</pre>
     */
    public WorkspaceTypeIndex replaceWorkspaceDeclarations(WorkspaceTypeIndex updates, Set<Path> replacedFiles) {
        if ((updates == null || updates.sourceFiles.isEmpty())
                && (replacedFiles == null || replacedFiles.isEmpty())) {
            return this;
        }
        var nextTypes = new Object2ObjectLinkedOpenHashMap<String, IndexedType>(typesByQualifiedName);
        var nextSourceFiles = new Object2ObjectLinkedOpenHashMap<Path, SourceFileSnapshot>(sourceFiles);

        var filesToReplace = new ObjectLinkedOpenHashSet<Path>();
        if (replacedFiles != null) {
            filesToReplace.addAll(replacedFiles);
        }
        if (updates != null) {
            filesToReplace.addAll(updates.sourceFiles.keySet());
        }

        for (var file : filesToReplace) {
            var previousSnapshot = nextSourceFiles.remove(file);
            if (previousSnapshot == null) {
                continue;
            }
            for (var qualifiedName : previousSnapshot.declaredTypes) {
                var existing = nextTypes.get(qualifiedName);
                if (existing != null && file.equals(existing.sourcePath)) {
                    nextTypes.remove(qualifiedName);
                }
            }
        }

        if (updates != null) {
            for (var entry : updates.sourceFiles.entrySet()) {
                var file = entry.getKey();
                var snapshot = entry.getValue();
                nextSourceFiles.put(file, snapshot);
                for (var qualifiedName : snapshot.declaredTypes) {
                    var typeInfo = updates.typesByQualifiedName.get(qualifiedName);
                    if (typeInfo != null) {
                        nextTypes.put(qualifiedName, typeInfo);
                    }
                }
            }
        }

        return new WorkspaceTypeIndex(nextTypes, nextSourceFiles);
    }

    public List<IndexedMember> members(String qualifiedName, boolean staticContext) {
        var type = typesByQualifiedName.get(qualifiedName);
        if (type == null) {
            return List.of();
        }
        var list = new ArrayList<IndexedMember>();
        var seen = new ObjectLinkedOpenHashSet<String>();
        addDirectMembers(type, staticContext, list, seen);
        addInheritedMembers(qualifiedName, staticContext, list, seen);
        return list;
    }

    public List<IndexedMember> constructors(String qualifiedName) {
        var type = typesByQualifiedName.get(qualifiedName);
        if (type == null) return List.of();
        return type.members.stream()
                .filter(m -> m.kind == CompletionItemKind.Constructor)
                .toList();
    }

    public Optional<IndexedMember> member(String qualifiedName, String name, boolean staticContext) {
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

    public Optional<IndexedMember> member(String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var type = typesByQualifiedName.get(qualifiedName);
        if (type == null) {
            return Optional.empty();
        }
        var targetKey = IndexedMember.canonicalKey(qualifiedName, CompletionItemKind.Method, name, erasedParameterTypes);
        var direct = directMethodMember(type, staticContext, targetKey);
        if (direct.isPresent()) {
            return direct;
        }
        return inheritedMember(qualifiedName, name, staticContext, erasedParameterTypes);
    }

    public Optional<IndexedMember> memberByCanonicalKey(String canonicalKey) {
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
        if (type == null || type.directSupertypes.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(type.directSupertypes);
    }

    /**
     * Return the method keys related to one declaration across the indexed type hierarchy.
     *
     * <p>This walks the owner type, its direct/transitive supertypes, and indexed subtypes to
     * gather matching method keys for the same erased signature. The result is used for features
     * like references/navigation where overrides and inherited declarations should be treated as
     * one related group.
     *
     * <p>Example:
     *
     * <pre>{@code
     * ownerType = "com.example.Base"
     * methodName = "handle"
     * erasedParameterTypes = ["java.lang.String"]
     *
     * -> {
     *      "com.example.Base#handle(java.lang.String)",
     *      "com.example.Child#handle(java.lang.String)"
     *    }
     * }</pre>
     *
     * <p>A more explicit name would be {@code relatedOverrideMethodKeys}, but the current name is
     * kept to avoid churn.
     */
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
            IndexedType type, boolean staticContext, List<IndexedMember> members, Set<String> seenStorageKeys) {
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
            String qualifiedName, boolean staticContext, List<IndexedMember> members, Set<String> seenStorageKeys) {
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

    private Optional<IndexedMember> directMember(IndexedType type, String name, boolean staticContext) {
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

    private Optional<IndexedMember> directMethodMember(IndexedType type, boolean staticContext, String targetKey) {
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

    private Optional<IndexedMember> inheritedMember(
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

    /**
     * Resolve a source-facing type name against the published workspace snapshot only.
     *
     * <p>This answers: "If the current file mentions {@code Foo} or {@code Outer.Inner}, which
     * workspace type does that refer to without reparsing other files?"
     *
     * <p>The lookup order is intentionally small:
     *
     * <ol>
     *   <li>exact qualified-name hit
     *   <li>current-file declared types from {@link SourceFileSnapshot}
     *   <li>simple-name/import resolution within the workspace snapshot
     * </ol>
     *
     * <p>Examples:
     *
     * <pre>{@code
     * // In NestedDefinitionExample.java
     * raw = "ResolvedSymbol"
     * -> "com.example.demo.NestedDefinitionExample.ResolvedSymbol"
     *
     * raw = "java.util.List"
     * -> "java.util.List"
     * }</pre>
     */
    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var raw = TypeNames.normalize(typeName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (TypeNames.isPrimitive(raw)) {
            return Optional.of(raw);
        }
        if (typesByQualifiedName.containsKey(raw)) {
            return Optional.of(raw);
        }
        if (root != null && root.getSourceFile() != null) {
            var sourceUri = root.getSourceFile().toUri();
            if (sourceUri != null && "file".equals(sourceUri.getScheme())) {
                var snapshot = sourceFiles.get(Paths.get(sourceUri));
                if (snapshot != null) {
                    String declaredMatch = null;
                    var packaged =
                            snapshot.packageName == null || snapshot.packageName.isBlank()
                                    ? raw
                                    : snapshot.packageName + "." + raw;
                    for (var declaredType : snapshot.declaredTypes) {
                        if (!Objects.equals(declaredType, raw)
                                && !Objects.equals(declaredType, packaged)
                                && !Objects.equals(TypeNames.simpleName(declaredType), raw)
                                && !(raw.contains(".") && declaredType.endsWith("." + raw))) {
                            continue;
                        }
                        if (declaredMatch != null && !Objects.equals(declaredMatch, declaredType)) {
                            return Optional.empty();
                        }
                        declaredMatch = declaredType;
                    }
                    if (declaredMatch != null) {
                        return Optional.of(declaredMatch);
                    }
                }
            }
        }
        if (raw.contains(".")) {
            var firstSegmentEnd = raw.indexOf('.');
            if (firstSegmentEnd <= 0) {
                return Optional.empty();
            }
            var firstSegment = raw.substring(0, firstSegmentEnd);
            var suffix = raw.substring(firstSegmentEnd);
            var resolvedPrefix =
                    TypeNames.resolveSimpleName(firstSegment, root, typesByQualifiedName::containsKey);
            if (resolvedPrefix.isEmpty()) {
                return Optional.empty();
            }
            var resolved = resolvedPrefix.get() + suffix;
            if (typesByQualifiedName.containsKey(resolved)) {
                return Optional.of(resolved);
            }
            return Optional.empty();
        }
        return TypeNames.resolveSimpleName(raw, root, typesByQualifiedName::containsKey);
    }

    public Optional<IndexedType> resolveType(String typeName, CompilationUnitTree root) {
        return resolveTypeName(typeName, root).flatMap(this::typeInfo);
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

    public static WorkspaceTypeIndex from(CompileTask task) {
        return from(task, true);
    }

    public static WorkspaceTypeIndex workspaceDeclarations(CompileTask task) {
        return from(task, false);
    }

    /**
     * Build a workspace type index from parse trees only, without attribution.
     *
     * <p>This is ~15x faster than {@link #from(CompileTask)} because it skips javac's
     * type-attribution phase. Member type names are raw strings from the parse tree (may be simple
     * names rather than fully qualified), but are sufficient for bootstrap completion candidate
     * lists. {@link org.javacs.resolve.ParseTypeResolver} resolves these at query time.
     *
     * <p>Inherited workspace members are resolved by walking the superclass chain after all direct
     * members are collected. External inherited members are resolved lazily at query time via
     * {@link ExternalBinaryTypeIndex}.
     *
     * <p>Record component accessors are synthesized from the parse tree without attribution.
     * Lombok synthetics use the same parse-tree-based path as the compiled index.
     */
    public static WorkspaceTypeIndex fromParseTrees(java.util.List<ParseTask> parseTasks) {
        // === Phase 1: Scan all roots — collect type names, class trees, and file metadata ===
        var allQualifiedNames = new ObjectOpenHashSet<String>();
        var typeClassTrees = new Object2ObjectOpenHashMap<String, ClassTree>();
        var typeSources = new Object2ObjectOpenHashMap<String, Path>();
        var typeSourceUris = new Object2ObjectOpenHashMap<String, java.net.URI>();
        var typeKinds = new Object2ObjectOpenHashMap<String, Integer>();
        var typeModifiers = new Object2ObjectOpenHashMap<String, Set<Modifier>>();
        var nestedTypesByOwner = new Object2ObjectOpenHashMap<String, Set<String>>();
        var typeRoots = new Object2ObjectOpenHashMap<String, CompilationUnitTree>();
        var sourceFileSnapshots = new Object2ObjectLinkedOpenHashMap<Path, SourceFileSnapshot>();

        for (var parseTask : parseTasks) {
            collectParseTypeMetadata(parseTask.root(), allQualifiedNames, typeClassTrees,
                    typeSources, typeSourceUris, typeKinds, typeModifiers,
                    nestedTypesByOwner, typeRoots, sourceFileSnapshots);
        }

        // Predicate used to resolve simple type names via imports/same-package lookup
        Predicate<String> workspaceContains = allQualifiedNames::contains;

        // === Phase 2: Extract direct members from parse trees ===
        var typeDirectMembers =
                new Object2ObjectOpenHashMap<String, Map<String, IndexedMember>>();
        var typeSupertypes = new Object2ObjectOpenHashMap<String, String>();
        var typeInterfacesList = new Object2ObjectOpenHashMap<String, java.util.List<String>>();

        for (var qualifiedName : allQualifiedNames) {
            var classTree = typeClassTrees.get(qualifiedName);
            var root = typeRoots.get(qualifiedName);
            var seen = new Object2ObjectOpenHashMap<String, IndexedMember>();
            typeDirectMembers.put(qualifiedName, seen);

            typeSupertypes.put(qualifiedName,
                    resolveParseSupertypeFromTree(classTree.getExtendsClause(), root, workspaceContains, qualifiedName));
            typeInterfacesList.put(qualifiedName,
                    resolveParseInterfacesFromTree(classTree, root, workspaceContains, qualifiedName));

            var enclosingIsInterface = classTree.getKind() == Tree.Kind.INTERFACE
                    || classTree.getKind() == Tree.Kind.ANNOTATION_TYPE;
            for (var member : classTree.getMembers()) {
                if (member instanceof MethodTree method) {
                    addParseTreeMethod(qualifiedName, method, seen);
                } else if (member instanceof VariableTree variable) {
                    addParseTreeField(qualifiedName, variable, seen, enclosingIsInterface);
                }
            }

            // Synthesize record component accessors without attribution
            if (classTree.getKind() == Tree.Kind.RECORD) {
                addRecordComponentAccessorsFromParseTree(qualifiedName, classTree, seen);
            }
        }

        // === Phase 3: Walk workspace inheritance chain — add inherited members ===
        for (var qualifiedName : allQualifiedNames) {
            var seen = typeDirectMembers.get(qualifiedName);
            var visited = new ObjectOpenHashSet<String>();
            visited.add(qualifiedName);
            addInheritedWorkspaceMembers(qualifiedName, seen, typeDirectMembers,
                    typeSupertypes, typeInterfacesList, visited);
        }

        // === Phase 4: Synthetics + build IndexedType entries ===
        var typeEntries = new Object2ObjectLinkedOpenHashMap<String, IndexedType>();

        for (var qualifiedName : allQualifiedNames) {
            var seen = typeDirectMembers.get(qualifiedName);
            var classTree = typeClassTrees.get(qualifiedName);
            var sourcePath = typeSources.get(qualifiedName);

            addSyntheticLombokAccessors(qualifiedName, classTree, seen);
            addSyntheticSlf4jLoggerField(qualifiedName, classTree, seen);
            addSyntheticLombokBuilderType(qualifiedName, classTree, sourcePath,
                    sourcePath != null, seen, typeEntries, typeSources, null);

            var members = new ArrayList<>(seen.values());
            IndexedMember.sort(members);

            var nestedTypes =
                    nestedTypesByOwner.containsKey(qualifiedName)
                            ? List.copyOf(nestedTypesByOwner.get(qualifiedName))
                            : List.<String>of();

            typeEntries.put(qualifiedName, new IndexedType(
                    qualifiedName,
                    TypeNames.simpleName(qualifiedName),
                    members,
                    sourcePath != null,
                    sourcePath,
                    typeSourceUris.get(qualifiedName),
                    typeSupertypes.get(qualifiedName),
                    typeInterfacesList.getOrDefault(qualifiedName, List.of()),
                    nestedTypes,
                    typeKinds.getOrDefault(qualifiedName, CompletionItemKind.Class),
                    typeModifiers.getOrDefault(qualifiedName, Set.of()),
                    null,
                    IndexedMember.Provenance.WORKSPACE));
        }

        normalizeLombokBuilderTypes(typeEntries, typeClassTrees, typeSources);

        return new WorkspaceTypeIndex(
                        Collections.unmodifiableMap(typeEntries),
                        Collections.unmodifiableMap(sourceFileSnapshots));
    }

    /** First-pass scanner: collects all qualified type names and per-file metadata from a single root. */
    private static void collectParseTypeMetadata(
            CompilationUnitTree root,
            Set<String> allQualifiedNames,
            Map<String, ClassTree> typeClassTrees,
            Map<String, Path> typeSources,
            Map<String, java.net.URI> typeSourceUris,
            Map<String, Integer> typeKinds,
            Map<String, Set<Modifier>> typeModifiers,
            Map<String, Set<String>> nestedTypesByOwner,
            Map<String, CompilationUnitTree> typeRoots,
            Map<Path, SourceFileSnapshot> sourceFileSnapshots) {
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        Path sourcePath = null;
        java.net.URI sourceUri = null;
        var sourceUriObj = root.getSourceFile().toUri();
        if (sourceUriObj != null && "file".equals(sourceUriObj.getScheme())) {
            sourcePath = Paths.get(sourceUriObj);
            sourceUri = sourceUriObj;
        }
        final var finalSourcePath = sourcePath;
        final var finalSourceUri = sourceUri;

        var explicitImports = new ArrayList<String>();
        var staticImports = new ArrayList<String>();
        for (var importTree : root.getImports()) {
            var imported = importTree.getQualifiedIdentifier().toString();
            if (importTree.isStatic()) staticImports.add(imported);
            else explicitImports.add(imported);
        }

        var declaredTypesInFile = new ArrayList<String>();
        var qualifiedNameStack = new ArrayDeque<String>();

        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree tree, Void p) {
                var simpleName = tree.getSimpleName() == null ? null : tree.getSimpleName().toString();
                if (simpleName == null || simpleName.isBlank()) {
                    return super.visitClass(tree, p); // anonymous class — skip
                }
                var qualified = qualifiedNameStack.isEmpty()
                        ? (packageName.isBlank() ? simpleName : packageName + "." + simpleName)
                        : qualifiedNameStack.peek() + "." + simpleName;
                if (!isValidIndexKey(qualified)) {
                    return super.visitClass(tree, p);
                }

                // Record nesting relationship before pushing
                if (!qualifiedNameStack.isEmpty()) {
                    var parentName = qualifiedNameStack.peek();
                    nestedTypesByOwner
                            .computeIfAbsent(parentName, __ -> new ObjectLinkedOpenHashSet<>())
                            .add(qualified);
                }

                qualifiedNameStack.push(qualified);
                allQualifiedNames.add(qualified);
                typeClassTrees.put(qualified, tree);
                typeRoots.put(qualified, root);
                typeKinds.put(qualified, parseTreeKindToCompletionItemKind(tree.getKind()));
                typeModifiers.put(qualified, Set.copyOf(tree.getModifiers().getFlags()));
                declaredTypesInFile.add(qualified);
                if (finalSourcePath != null) {
                    typeSources.put(qualified, finalSourcePath);
                    typeSourceUris.put(qualified, finalSourceUri);
                }

                var result = super.visitClass(tree, p);
                qualifiedNameStack.pop();
                return result;
            }
        }.scan(root, null);

        if (finalSourcePath != null) {
            sourceFileSnapshots.put(finalSourcePath, new SourceFileSnapshot(
                    finalSourcePath, finalSourceUri, packageName,
                    explicitImports, staticImports, declaredTypesInFile));
        }
    }

    private static int parseTreeKindToCompletionItemKind(Tree.Kind kind) {
        return switch (kind) {
            case INTERFACE, ANNOTATION_TYPE -> CompletionItemKind.Interface;
            case ENUM -> CompletionItemKind.Enum;
            default -> CompletionItemKind.Class; // CLASS, RECORD
        };
    }

    private static String resolveParseSupertypeFromTree(
            Tree extendsClause, CompilationUnitTree root, Predicate<String> containsType,
            String ownerQualifiedName) {
        if (extendsClause == null) return null;
        var baseName = TypeNames.normalize(extendsClause.toString());
        if (baseName == null || baseName.isBlank()) return null;
        if (baseName.contains(".")) return baseName; // already qualified
        // Try sibling/ancestor inner-class scopes before import-based resolution.
        // e.g. "Sub extends Super" inside CompleteMembers — Super resolves to
        // org.javacs.example.CompleteMembers.Super, not a top-level import.
        var enclosingCandidate = resolveInEnclosingScopes(baseName, ownerQualifiedName, containsType);
        if (enclosingCandidate != null) return enclosingCandidate;
        return TypeNames.resolveSimpleName(baseName, root, containsType).orElse(null);
    }

    private static java.util.List<String> resolveParseInterfacesFromTree(
            ClassTree classTree, CompilationUnitTree root, Predicate<String> containsType,
            String ownerQualifiedName) {
        var result = new ArrayList<String>();
        for (var iface : classTree.getImplementsClause()) {
            var baseName = TypeNames.normalize(iface.toString());
            if (baseName == null || baseName.isBlank()) continue;
            if (baseName.contains(".")) {
                result.add(baseName);
            } else {
                var enclosing = resolveInEnclosingScopes(baseName, ownerQualifiedName, containsType);
                if (enclosing != null) result.add(enclosing);
                else TypeNames.resolveSimpleName(baseName, root, containsType).ifPresent(result::add);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Walk enclosing class scopes of {@code ownerQualifiedName} (innermost first) looking for a
     * sibling nested class named {@code simpleName}.
     *
     * <p>For example, given owner {@code com.example.Outer.Sub} and simple name {@code Super}:
     * <ol>
     *   <li>try {@code com.example.Outer.Super} — found → return it
     *   <li>try {@code com.example.Super} — not a known class → skip
     * </ol>
     *
     * @return the qualified name if found in {@code containsType}, otherwise {@code null}
     */
    private static String resolveInEnclosingScopes(
            String simpleName, String ownerQualifiedName, Predicate<String> containsType) {
        var dot = ownerQualifiedName.lastIndexOf('.');
        while (dot > 0) {
            var prefix = ownerQualifiedName.substring(0, dot);
            if (containsType.test(prefix)) {
                // prefix is a known workspace class — try a nested type with this name
                var candidate = prefix + "." + simpleName;
                if (containsType.test(candidate)) return candidate;
            }
            dot = prefix.lastIndexOf('.');
        }
        return null;
    }

    /**
     * Add a method member from the parse tree.
     *
     * <p>Constructors ({@code <init>}) are skipped. Parameter types use
     * {@link TypeNames#normalize} to strip generics for the erased-parameter-types slot (best
     * effort at parse time). The raw declared type string is stored in
     * {@code declaredParameterTypes}.
     */
    private static void addParseTreeMethod(
            String ownerQualifiedName, MethodTree method, Map<String, IndexedMember> seen) {
        var name = method.getName() == null ? null : method.getName().toString();
        if (name == null || name.isBlank() || "<init>".equals(name)) return;

        var flags = method.getModifiers().getFlags();
        var isStatic = flags.contains(Modifier.STATIC);
        var isPrivate = flags.contains(Modifier.PRIVATE);
        var isProtected = flags.contains(Modifier.PROTECTED);
        var isPublic = flags.contains(Modifier.PUBLIC);
        var isAbstract = flags.contains(Modifier.ABSTRACT);

        var returnTypeStr = method.getReturnType() == null ? "void" : method.getReturnType().toString();

        var params = method.getParameters();
        var paramNames = new String[params.size()];
        var erasedParamTypes = new String[params.size()];
        var declaredParamTypes = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            var param = params.get(i);
            paramNames[i] = param.getName() == null ? "arg" + i : param.getName().toString();
            var rawType = param.getType() == null ? "Object" : param.getType().toString();
            declaredParamTypes[i] = rawType;
            erasedParamTypes[i] = TypeNames.normalize(rawType); // strip generics, keep array
        }

        var canonicalKey = IndexedMember.canonicalKey(
                ownerQualifiedName, CompletionItemKind.Method, name, erasedParamTypes);
        var detail = returnTypeStr + " " + name + "(" + String.join(", ", declaredParamTypes) + ")";

        var next = new IndexedMember(
                ownerQualifiedName, name, CompletionItemKind.Method,
                isStatic, isPrivate, isProtected, isPublic, isAbstract,
                0, detail, returnTypeStr, returnTypeStr,
                paramNames, erasedParamTypes, declaredParamTypes,
                canonicalKey, canonicalKey, null, false,
                IndexedMember.Origin.DECLARED, Set.copyOf(flags), null, null);

        seen.putIfAbsent(memberStorageKey(next), next);
    }

    /**
     * Add a field member from the parse tree.
     *
     * <p>The type string is the raw parse-tree text (may include generics or be a simple name).
     * {@link org.javacs.resolve.ParseTypeResolver} resolves these at query time.
     */
    private static void addParseTreeField(
            String ownerQualifiedName, VariableTree variable, Map<String, IndexedMember> seen,
            boolean enclosingIsInterface) {
        var name = variable.getName() == null ? null : variable.getName().toString();
        if (name == null || name.isBlank()) return;

        var flags = variable.getModifiers().getFlags();
        // Detect enum constants via internal javac flag (ENUM is not a javax.lang.model.element.Modifier).
        var isEnumConstant = variable instanceof JCVariableDecl jcVar
                && (jcVar.mods.flags & Flags.ENUM) != 0;
        var isStatic = flags.contains(Modifier.STATIC)
                // Interface fields are implicitly public static final — no explicit STATIC in source.
                || (enclosingIsInterface && !flags.contains(Modifier.PRIVATE));
        var isPrivate = flags.contains(Modifier.PRIVATE);
        var isProtected = flags.contains(Modifier.PROTECTED);
        var isPublic = flags.contains(Modifier.PUBLIC)
                // Interface fields are implicitly public.
                || (enclosingIsInterface && !isPrivate && !isProtected);

        // For enum constants, use the owner type as the return type so that isEnumCaseConstant
        // can match them even in the parse-only index (where the type string is the simple name).
        var rawTypeStr = variable.getType() == null ? "Object" : variable.getType().toString();
        var typeStr = isEnumConstant ? ownerQualifiedName : rawTypeStr;
        var kind = isEnumConstant ? CompletionItemKind.EnumMember : CompletionItemKind.Field;
        var canonicalKey = IndexedMember.canonicalKey(
                ownerQualifiedName, kind, name, null);

        var next = new IndexedMember(
                ownerQualifiedName, name, kind,
                isStatic, isPrivate, isProtected, isPublic, false,
                0, typeStr + " " + name, typeStr, typeStr,
                null, null, null,
                canonicalKey, canonicalKey, null, false,
                IndexedMember.Origin.DECLARED, Set.copyOf(flags), null, null);

        seen.putIfAbsent(memberStorageKey(next), next);
    }

    /**
     * Synthesize record component accessor methods from the parse tree without attribution.
     *
     * <p>Record components in the parse tree appear as {@link VariableTree} members with no
     * explicit access modifier ({@code public}/{@code private}/{@code protected}), no
     * {@code static} modifier, and no initializer expression. This reliably distinguishes them
     * from explicit instance fields (which always carry at least one visibility or other modifier
     * in real-world code).
     */
    private static void addRecordComponentAccessorsFromParseTree(
            String ownerQualifiedName, ClassTree classTree, Map<String, IndexedMember> seen) {
        // Record component accessor methods are synthesized by javac during desugaring and are NOT
        // present in classTree.getMembers() at parse time. However, the backing fields for each
        // component ARE present, marked with the internal Flags.RECORD bit (PRIVATE | FINAL | RECORD).
        // Synthesize the public accessor methods from those backing field entries.
        for (var member : classTree.getMembers()) {
            if (!(member instanceof VariableTree vt)) continue;
            if (!(vt instanceof JCVariableDecl jcVar)) continue;
            if ((jcVar.mods.flags & Flags.RECORD) == 0) continue;

            var name = jcVar.getName() == null ? null : jcVar.getName().toString();
            if (name == null || name.isBlank()) continue;

            var typeStr = jcVar.vartype == null ? "Object" : jcVar.vartype.toString();
            var accessorKey = IndexedMember.canonicalKey(
                    ownerQualifiedName, CompletionItemKind.Method, name, new String[0]);
            var logicalKey = IndexedMember.canonicalKey(
                    ownerQualifiedName, CompletionItemKind.Field, name, null);

            if (!seen.containsKey(accessorKey)) {
                seen.put(accessorKey, new IndexedMember(
                        ownerQualifiedName, name, CompletionItemKind.Method,
                        false, false, false, true, false,
                        0, typeStr + " " + name + "()", typeStr, typeStr,
                        new String[0], new String[0], new String[0],
                        accessorKey, logicalKey, name, true,
                        IndexedMember.Origin.RECORD_COMPONENT, Set.of(Modifier.PUBLIC), null, null)
                        .withNavigation(ownerQualifiedName, logicalKey));
            }
        }
    }

    /**
     * Walk the workspace superclass and interface chains to add inherited members.
     *
     * <p>Only workspace-declared types are walked here. External inherited members are resolved
     * lazily at completion-query time via {@link ExternalBinaryTypeIndex}.
     *
     * <p>Declared members (priority 0) in the child always win over inherited members (priority 1).
     * The {@code visited} set prevents infinite loops on cyclic class hierarchies.
     */
    private static void addInheritedWorkspaceMembers(
            String qualifiedName,
            Map<String, IndexedMember> seen,
            Map<String, Map<String, IndexedMember>> typeDirectMembers,
            Map<String, String> typeSupertypes,
            Map<String, java.util.List<String>> typeInterfaces,
            Set<String> visited) {
        var superclass = typeSupertypes.get(qualifiedName);
        if (superclass != null && !superclass.isBlank() && visited.add(superclass)) {
            var parentMembers = typeDirectMembers.get(superclass);
            if (parentMembers != null) {
                copyInheritedParseMembers(parentMembers, seen);
                // Recurse: grandparent members reach the child transitively
                addInheritedWorkspaceMembers(superclass, seen, typeDirectMembers,
                        typeSupertypes, typeInterfaces, visited);
            }
        }
        var ifaces = typeInterfaces.get(qualifiedName);
        if (ifaces != null) {
            for (var iface : ifaces) {
                if (!iface.isBlank() && visited.add(iface)) {
                    var ifaceMembers = typeDirectMembers.get(iface);
                    if (ifaceMembers != null) {
                        copyInheritedParseMembers(ifaceMembers, seen);
                        addInheritedWorkspaceMembers(iface, seen, typeDirectMembers,
                                typeSupertypes, typeInterfaces, visited);
                    }
                }
            }
        }
    }

    /** Copy non-private parent members into the child's seen map at inherited priority. */
    private static void copyInheritedParseMembers(
            Map<String, IndexedMember> parentMembers, Map<String, IndexedMember> childSeen) {
        for (var entry : parentMembers.entrySet()) {
            var parentMember = entry.getValue();
            if (parentMember.isPrivate) continue;
            childSeen.putIfAbsent(entry.getKey(), withInheritedPriority(parentMember));
        }
    }

    private static IndexedMember withInheritedPriority(IndexedMember member) {
        return new IndexedMember(
                member.ownerType, member.name, member.kind,
                member.isStatic, member.isPrivate, member.isProtected, member.isPublic, member.isAbstract,
                1,
                member.detail, member.returnType, member.declaredReturnType,
                member.parameterNames, member.erasedParameterTypes, member.declaredParameterTypes,
                member.canonicalKey, member.logicalKey, member.backingFieldName, member.synthetic,
                member.origin, member.provenance, member.modifiers, member.sourceUri,
                member.declarationRange, member.declarationOwnerType, member.targetDeclarationKey);
    }

    private static WorkspaceTypeIndex from(CompileTask task, boolean includeReferencedTypes) {
        var trees = Trees.instance(task.task);
        var elements = task.task.getElements();
        var types = task.task.getTypes();

        var rootDeclaredTypeSources = new Object2ObjectOpenHashMap<String, Path>();
        var rootDeclaredTypeTrees = new Object2ObjectOpenHashMap<String, ClassTree>();
        var rootDeclaredTypeLocations = new Object2ObjectOpenHashMap<String, Location>();
        var memberDeclarationLocations = new Object2ObjectOpenHashMap<String, Location>();
        var typeKinds = new Object2ObjectOpenHashMap<String, Integer>();
        var typeModifiers = new Object2ObjectOpenHashMap<String, Set<Modifier>>();
        var nestedTypesByOwner = new Object2ObjectOpenHashMap<String, Set<String>>();
        var sourceFileSnapshots = new Object2ObjectLinkedOpenHashMap<Path, SourceFileSnapshot>();
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
            var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
            var explicitImports = new ArrayList<String>();
            var staticImports = new ArrayList<String>();
            for (var importTree : root.getImports()) {
                var imported = importTree.getQualifiedIdentifier().toString();
                if (importTree.isStatic()) {
                    staticImports.add(imported);
                } else {
                    explicitImports.add(imported);
                }
            }
            var declaredTypesInFile = new ArrayList<String>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree tree, Void p) {
                    var element = trees.getElement(getCurrentPath());
                    if (element instanceof TypeElement typeElement) {
                        var qualifiedName = typeElement.getQualifiedName().toString();
                        if (isValidIndexKey(qualifiedName)) {
                            collectedTypes.putIfAbsent(qualifiedName, typeElement);
                            declaredTypesInFile.add(qualifiedName);
                            if (rootPath != null) {
                                rootDeclaredTypeSources.put(qualifiedName, rootPath);
                            }
                            rootDeclaredTypeTrees.putIfAbsent(qualifiedName, tree);
                            typeKinds.putIfAbsent(qualifiedName, memberKind(typeElement));
                            typeModifiers.putIfAbsent(qualifiedName, Set.copyOf(typeElement.getModifiers()));
                            var location = FindHelper.location(task, getCurrentPath(), tree.getSimpleName());
                            if (location != null) {
                                rootDeclaredTypeLocations.putIfAbsent(qualifiedName, location);
                            }
                            var parent = getCurrentPath().getParentPath();
                            if (parent != null) {
                                var parentElement = trees.getElement(parent);
                                if (parentElement instanceof TypeElement parentType) {
                                    nestedTypesByOwner
                                            .computeIfAbsent(
                                                    parentType.getQualifiedName().toString(),
                                                    __ -> new ObjectLinkedOpenHashSet<>())
                                            .add(qualifiedName);
                                }
                            }
                        } else if (qualifiedName != null && !qualifiedName.isBlank()) {
                            skippedInvalidTypeKeys.add(qualifiedName);
                        }
                        if (LombokAnnotations.hasLoggingOnlyLombokAnnotation(tree.getModifiers())) {
                            collectNamedType(elements, "org.slf4j.Logger", collectedTypes);
                        }
                    }
                    return super.visitClass(tree, p);
                }

                @Override
                public Void visitVariable(VariableTree tree, Void p) {
                    captureDeclaredMember(tree.getName() == null ? null : tree.getName().toString(), null);
                    return super.visitVariable(tree, p);
                }

                @Override
                public Void visitMethod(MethodTree tree, Void p) {
                    var parent = getCurrentPath().getParentPath();
                    var parentLeaf = parent == null ? null : parent.getLeaf();
                    var label =
                            tree.getReturnType() == null && parentLeaf instanceof ClassTree classTree
                                    ? classTree.getSimpleName().toString()
                                    : tree.getName().toString();
                    captureDeclaredMember(label, tree);
                    return super.visitMethod(tree, p);
                }

                private void captureDeclaredMember(String label, MethodTree methodTree) {
                    if (label == null || label.isBlank()) {
                        return;
                    }
                    var current = getCurrentPath();
                    var parent = current == null ? null : current.getParentPath();
                    if (parent == null || !(parent.getLeaf() instanceof ClassTree)) {
                        return;
                    }
                    var element = trees.getElement(current);
                    if (element == null || element.getEnclosingElement() == null) {
                        return;
                    }
                    if (!(element.getEnclosingElement() instanceof TypeElement ownerType)) {
                        return;
                    }
                    var kind = memberKind(element);
                    if (kind == null) {
                        return;
                    }
                    String[] erasedParameterTypes = null;
                    if (element instanceof ExecutableElement executable) {
                        erasedParameterTypes = new String[executable.getParameters().size()];
                        for (int i = 0; i < executable.getParameters().size(); i++) {
                            erasedParameterTypes[i] = types.erasure(executable.getParameters().get(i).asType()).toString();
                        }
                    }
                    var key =
                            IndexedMember.canonicalKey(
                                    ownerType.getQualifiedName().toString(),
                                    kind,
                                    label,
                                    erasedParameterTypes);
                    var location = FindHelper.location(task, current, label);
                    if (location != null) {
                        memberDeclarationLocations.putIfAbsent(key, location);
                    }
                }
            }.scan(root, null);
            if (rootPath != null) {
                sourceFileSnapshots.put(
                        rootPath,
                        new SourceFileSnapshot(
                                rootPath,
                                sourceUri,
                                packageName,
                                explicitImports,
                                staticImports,
                                declaredTypesInFile));
            }

            if (includeReferencedTypes) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree tree, Void p) {
                        collectReferencedTypes(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitVariable(tree, p);
                    }

                    @Override
                    public Void visitMethod(MethodTree tree, Void p) {
                        collectReferencedTypes(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMethod(tree, p);
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
                        collectReferencedTypes(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMethodInvocation(tree, p);
                    }

                    @Override
                    public Void visitIdentifier(IdentifierTree tree, Void p) {
                        collectReferencedTypes(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitIdentifier(tree, p);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree tree, Void p) {
                        collectReferencedTypes(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitMemberSelect(tree, p);
                    }

                    @Override
                    public Void visitNewClass(NewClassTree tree, Void p) {
                        collectReferencedTypes(trees.getTypeMirror(getCurrentPath()), collectedTypes, seenMirrors);
                        return super.visitNewClass(tree, p);
                    }
                }.scan(root, null);
            }
        }

        var typeEntries = new Object2ObjectLinkedOpenHashMap<String, IndexedType>();
        for (var collected : collectedTypes.values()) {
            var qualifiedName = collected.getQualifiedName().toString();
            if (!isValidIndexKey(qualifiedName)) {
                if (qualifiedName != null && !qualifiedName.isBlank()) {
                    skippedInvalidTypeKeys.add(qualifiedName);
                }
                continue;
            }
            var type = safeTypeElement(elements, qualifiedName);
            if (type == null) {
                type = collected;
            } else if (type != collected) {
                LOG.fine(String.format("[completion] canonicalized type symbol %s for index extraction", qualifiedName));
            }

            var seen = new Object2ObjectOpenHashMap<String, IndexedMember>();
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
                var modifiers = Set.copyOf(member.getModifiers());
                var isStatic = modifiers.contains(Modifier.STATIC);
                var isPrivate = modifiers.contains(Modifier.PRIVATE);
                var isProtected = modifiers.contains(Modifier.PROTECTED);
                var isPublic = modifiers.contains(Modifier.PUBLIC);
                var isAbstract = modifiers.contains(Modifier.ABSTRACT);

                String detail;
                String returnType;
                String[] parameterNames = null;
                String[] erasedParameterTypes = null;
                String declaredReturnType = null;
                String[] declaredParameterTypes = null;
                if (member instanceof ExecutableElement executable) {
                    detail = executable.getReturnType() + " " + executable;
                    returnType = typeName(executable.getReturnType());
                    declaredReturnType = normalizeDeclaredType(executable.getReturnType());
                    parameterNames = new String[executable.getParameters().size()];
                    erasedParameterTypes = new String[executable.getParameters().size()];
                    declaredParameterTypes = new String[executable.getParameters().size()];
                    for (int i = 0; i < executable.getParameters().size(); i++) {
                        var param = executable.getParameters().get(i);
                        parameterNames[i] = param.getSimpleName().toString();
                        erasedParameterTypes[i] = types.erasure(param.asType()).toString();
                        declaredParameterTypes[i] = normalizeDeclaredType(param.asType());
                    }
                } else {
                    detail = member.asType() + " " + member.getSimpleName();
                    returnType = typeName(member.asType());
                    declaredReturnType = normalizeDeclaredType(member.asType());
                }
                var canonicalKey =
                        IndexedMember.canonicalKey(
                                ownerName, kind, member.getSimpleName().toString(), erasedParameterTypes);
                var memberLocation = memberDeclarationLocations.get(canonicalKey);

                var next =
                        new IndexedMember(
                                ownerName,
                                member.getSimpleName().toString(),
                                kind,
                                isStatic,
                                isPrivate,
                                isProtected,
                                isPublic,
                                isAbstract,
                                priority,
                                detail,
                                returnType,
                                declaredReturnType,
                                parameterNames,
                                erasedParameterTypes,
                                declaredParameterTypes,
                                canonicalKey,
                                canonicalKey,
                                null,
                                false,
                                IndexedMember.Origin.DECLARED,
                                modifiers,
                                memberLocation == null ? null : memberLocation.uri,
                                memberLocation == null ? null : memberLocation.range);
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
            IndexedMember.sort(members);
            var superclass = directSuperclass(type);
            var interfaces = directInterfaces(type);
            var typeLocation = rootDeclaredTypeLocations.get(qualifiedName);
            var nestedTypes =
                    nestedTypesByOwner.containsKey(qualifiedName)
                            ? List.copyOf(nestedTypesByOwner.get(qualifiedName))
                            : List.<String>of();
            var typeEntry =
                    new IndexedType(
                            qualifiedName,
                            type.getSimpleName().toString(),
                            Collections.unmodifiableList(members),
                            fromCompiledRoot,
                            sourcePath,
                            typeLocation == null ? (sourcePath == null ? null : sourcePath.toUri()) : typeLocation.uri,
                            superclass,
                            interfaces,
                            nestedTypes,
                            typeKinds.getOrDefault(qualifiedName, memberKind(type)),
                            typeModifiers.getOrDefault(qualifiedName, Set.copyOf(type.getModifiers())),
                            typeLocation == null ? null : typeLocation.range,
                            IndexedMember.Provenance.WORKSPACE);
            typeEntries.put(qualifiedName, typeEntry);
        }

        normalizeLombokBuilderTypes(typeEntries, rootDeclaredTypeTrees, rootDeclaredTypeSources);

        if (!skippedInvalidTypeKeys.isEmpty()) {
            LOG.fine(
                    String.format(
                            "[completion] skipped non-fqn types while building index: %s",
                            skippedInvalidTypeKeys));
        }
        return new WorkspaceTypeIndex(
                Collections.unmodifiableMap(typeEntries), Collections.unmodifiableMap(sourceFileSnapshots));
    }

    private static Map<String, Set<String>> invertSubtypeMap(Map<String, IndexedType> typesByQualifiedName) {
        var subtypes = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        for (var entry : typesByQualifiedName.entrySet()) {
            for (var superType : entry.getValue().directSupertypes) {
                subtypes.computeIfAbsent(superType, __ -> new ObjectLinkedOpenHashSet<>()).add(entry.getKey());
            }
        }
        return subtypes;
    }

    /**
     * Return the direct superclass name to store on the indexed type snapshot.
     *
     * <p>This is the immediate graph edge only. It is not transitive.
     *
     * <p>Examples:
     *
     * <pre>{@code
     * class Child extends Base {}
     * -> "com.example.Base"
     *
     * class Plain {}
     * -> null
     * }</pre>
     */
    private static String directSuperclass(TypeElement type) {
        var mirror = type.getSuperclass();
        if (mirror == null || mirror.getKind() == javax.lang.model.type.TypeKind.NONE) {
            return null;
        }
        return typeName(mirror);
    }

    /**
     * Return only the immediate interface edges for the indexed type snapshot.
     *
     * <p>Examples:
     *
     * <pre>{@code
     * class Service implements Runnable, AutoCloseable {}
     * -> ["java.lang.Runnable", "java.lang.AutoCloseable"]
     * }</pre>
     */
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

    /**
     * Convert a javac {@link TypeMirror} into the stable indexed type name used for owner/member
     * lookup and type-graph edges.
     *
     * <p>This favors lookup stability over source fidelity:
     *
     * <ul>
     *   <li>declared types become qualified names
     *   <li>type variables collapse to their upper bound
     *   <li>arrays keep their {@code []} suffix
     * </ul>
     *
     * <p>Examples:
     *
     * <pre>{@code
     * List<String>      -> "java.util.List"
     * T extends Number  -> "java.lang.Number"
     * String[]          -> "java.lang.String[]"
     * }</pre>
     *
     * <p>If you want the declared signature text instead of the stable lookup name, use
     * {@link #normalizeDeclaredType(TypeMirror)}.
     */
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

    /**
     * Convert a javac {@link TypeMirror} into the declared signature text stored on indexed
     * members.
     *
     * <p>Unlike {@link #typeName(TypeMirror)}, this preserves generic structure because the goal is
     * to remember what the declaration said, not just what lookup owner it belongs to.
     *
     * <p>Examples:
     *
     * <pre>{@code
     * List<String> -> "java.util.List<java.lang.String>"
     * T            -> "T"
     * Foo.Bar      -> "com.example.Foo.Bar"
     * }</pre>
     *
     * <p>A more explicit name would be {@code declaredSignatureType}, but the current name is kept
     * to avoid churn.
     */
    private static String normalizeDeclaredType(TypeMirror mirror) {
        if (mirror == null) {
            return null;
        }
        return mirror.toString().replace('$', '.');
    }

    /**
     * Recursively collect declared types reachable from a type mirror into the workspace snapshot.
     *
     * <p>This is broader than a direct declared-type read. It follows nested generic arguments,
     * bounds, arrays, wildcards, intersections, and unions so referenced workspace types can be
     * published without reparsing unrelated files later.
     *
     * <p>Examples:
     *
     * <pre>{@code
     * List<Order>           -> collect java.util.List and com.example.Order
     * T extends Customer    -> collect com.example.Customer
     * Result<A | B>         -> collect A and B
     * }</pre>
     *
     * <p>A more explicit name would be {@code collectReferencedTypes}; that is the name used here
     * because the old {@code collectTypeMirror} name hid the recursive graph-building behavior.
     */
    private static void collectReferencedTypes(
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
                collectReferencedTypes(argument, collectedTypes, seenMirrors);
            }
            return;
        }
        if (mirror instanceof TypeVariable typeVariable) {
            collectReferencedTypes(typeVariable.getUpperBound(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof ArrayType arrayType) {
            collectReferencedTypes(arrayType.getComponentType(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof WildcardType wildcardType) {
            collectReferencedTypes(wildcardType.getExtendsBound(), collectedTypes, seenMirrors);
            collectReferencedTypes(wildcardType.getSuperBound(), collectedTypes, seenMirrors);
            return;
        }
        if (mirror instanceof IntersectionType intersectionType) {
            for (var bound : intersectionType.getBounds()) {
                collectReferencedTypes(bound, collectedTypes, seenMirrors);
            }
            return;
        }
        if (mirror instanceof UnionType unionType) {
            for (var alternative : unionType.getAlternatives()) {
                collectReferencedTypes(alternative, collectedTypes, seenMirrors);
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
        var type = safeTypeElement(elements, qualifiedName);
        if (type != null) {
            collectedTypes.put(qualifiedName, type);
        }
    }

    private static TypeElement safeTypeElement(javax.lang.model.util.Elements elements, String qualifiedName) {
        try {
            return elements.getTypeElement(qualifiedName);
        } catch (AssertionError e) {
            LOG.fine(String.format("[completion] failed to resolve type element %s from javac elements", qualifiedName));
            return null;
        }
    }

    private static boolean isValidIndexKey(String key) {
        return key != null && !key.isBlank() && (key.contains(".") || TypeNames.isPrimitive(key));
    }

    private static void addSyntheticLombokAccessors(
            String ownerQualifiedName, ClassTree declaration, Map<String, IndexedMember> seen) {
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
                var fieldKey = IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Field, fieldName, null);
                putSyntheticMethod(
                        seen,
                        new IndexedMember(
                                ownerQualifiedName,
                                getterName,
                                CompletionItemKind.Method,
                                false,
                                false,
                                false,
                                true,
                                false,
                                0,
                                fieldType + " " + getterName + "()",
                                fieldType,
                                fieldType,
                                new String[0],
                                new String[0],
                                new String[0],
                                IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Method, getterName, new String[0]),
                                fieldKey,
                                fieldName,
                                true,
                                IndexedMember.Origin.LOMBOK_ACCESSOR,
                                Set.of(Modifier.PUBLIC),
                                null,
                                null).withNavigation(ownerQualifiedName, fieldKey));
            }
            if (accessorInfo.hasSetter()) {
                var setterName = accessorInfo.setterName();
                var erasedParameterTypes = new String[] {TypeNames.normalize(accessorInfo.fieldType())};
                var fieldKey = IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Field, fieldName, null);
                putSyntheticMethod(
                        seen,
                        new IndexedMember(
                                ownerQualifiedName,
                                setterName,
                                CompletionItemKind.Method,
                                false,
                                false,
                                false,
                                true,
                                false,
                                0,
                                "void " + setterName + "(" + fieldType + " " + fieldName + ")",
                                "void",
                                "void",
                                new String[] {fieldName},
                                erasedParameterTypes,
                                new String[] {fieldType},
                                IndexedMember.canonicalKey(
                                        ownerQualifiedName,
                                        CompletionItemKind.Method,
                                        setterName,
                                        erasedParameterTypes),
                                fieldKey,
                                fieldName,
                                true,
                                IndexedMember.Origin.LOMBOK_ACCESSOR,
                                Set.of(Modifier.PUBLIC),
                                null,
                                null).withNavigation(ownerQualifiedName, fieldKey));
            }
        }
    }

    private static void addSyntheticLombokBuilderType(
            String ownerQualifiedName,
            ClassTree declaration,
            Path sourcePath,
            boolean fromCompiledRoot,
            Map<String, IndexedMember> ownerMembers,
            Map<String, IndexedType> typeEntries,
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
                new IndexedMember(
                        ownerQualifiedName,
                        "builder",
                        CompletionItemKind.Method,
                        true,
                        false,
                        false,
                        true,
                        false,
                        0,
                        builderQualifiedName + " builder()",
                        builderQualifiedName,
                        builderQualifiedName,
                        new String[0],
                        new String[0],
                        new String[0],
                        IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Method, "builder", new String[0]),
                        IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Method, "builder", new String[0]),
                        null,
                        true,
                        IndexedMember.Origin.LOMBOK_BUILDER,
                        Set.of(Modifier.PUBLIC, Modifier.STATIC),
                        null,
                        null));

        var builderMembers = new ArrayList<IndexedMember>();
        for (var field : fields) {
            var fieldKey = IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Field, field.name, null);
            var erasedParameterTypes = new String[] {TypeNames.normalize(field.type)};
            builderMembers.add(
                    new IndexedMember(
                            builderQualifiedName,
                            field.name,
                            CompletionItemKind.Method,
                            false,
                            false,
                            false,
                            true,
                            false,
                            0,
                            builderQualifiedName + " " + field.name + "(" + field.type + " " + field.name + ")",
                            builderQualifiedName,
                            builderQualifiedName,
                            new String[] {field.name},
                            erasedParameterTypes,
                            new String[] {field.type},
                            IndexedMember.canonicalKey(
                                    builderQualifiedName,
                                    CompletionItemKind.Method,
                                    field.name,
                                    erasedParameterTypes),
                            fieldKey,
                            field.name,
                            true,
                            IndexedMember.Origin.LOMBOK_BUILDER,
                            Set.of(Modifier.PUBLIC),
                            null,
                            null).withNavigation(ownerQualifiedName, fieldKey));
        }
        builderMembers.add(
                new IndexedMember(
                        builderQualifiedName,
                        "build",
                        CompletionItemKind.Method,
                        false,
                        false,
                        false,
                        true,
                        false,
                        0,
                        ownerQualifiedName + " build()",
                        ownerQualifiedName,
                        ownerQualifiedName,
                        new String[0],
                        new String[0],
                        new String[0],
                        IndexedMember.canonicalKey(builderQualifiedName, CompletionItemKind.Method, "build", new String[0]),
                        IndexedMember.canonicalKey(builderQualifiedName, CompletionItemKind.Method, "build", new String[0]),
                        null,
                        true,
                        IndexedMember.Origin.LOMBOK_BUILDER,
                        Set.of(Modifier.PUBLIC),
                        null,
                        null));
        IndexedMember.sort(builderMembers);
        typeEntries.put(
                builderQualifiedName,
                new IndexedType(
                        builderQualifiedName,
                        TypeNames.simpleName(builderQualifiedName),
                        builderMembers,
                        fromCompiledRoot,
                        sourcePath,
                        null,
                        List.of(),
                        IndexedMember.Provenance.WORKSPACE));
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
            Map<String, IndexedType> typeEntries,
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

    private static IndexedType createSyntheticLombokBuilderType(
            String ownerQualifiedName, List<DeclaredField> fields, Path sourcePath) {
        var builderQualifiedName = builderQualifiedName(ownerQualifiedName);
        var builderMembers = new ArrayList<IndexedMember>();
        for (var field : fields) {
            builderMembers.add(linkedBuilderSetter(ownerQualifiedName, builderQualifiedName, field));
        }
        builderMembers.add(builderBuildMethod(ownerQualifiedName, builderQualifiedName));
        IndexedMember.sort(builderMembers);
        return new IndexedType(
                builderQualifiedName,
                TypeNames.simpleName(builderQualifiedName),
                builderMembers,
                sourcePath != null,
                sourcePath,
                null,
                List.of(),
                IndexedMember.Provenance.WORKSPACE);
    }

    private static IndexedType normalizeLombokBuilderType(
            IndexedType existing, String ownerQualifiedName, List<DeclaredField> fields, Path sourcePath) {
        var builderQualifiedName = existing.qualifiedName;
        var members = new Object2ObjectLinkedOpenHashMap<String, IndexedMember>();
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
        IndexedMember.sort(normalizedMembers);
        return new IndexedType(
                builderQualifiedName,
                existing.simpleName,
                normalizedMembers,
                existing.fromCompiledRoot || sourcePath != null,
                existing.sourcePath != null ? existing.sourcePath : sourcePath,
                existing.superclass,
                existing.interfaces,
                existing.provenance);
    }

    private static IndexedMember linkedBuilderSetter(
            String ownerQualifiedName, String builderQualifiedName, DeclaredField field) {
        var fieldKey = IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Field, field.name, null);
        var erasedParameterTypes = new String[] {TypeNames.normalize(field.type)};
        return new IndexedMember(
                builderQualifiedName,
                field.name,
                CompletionItemKind.Method,
                false,
                false,
                false,
                true,
                false,
                0,
                builderQualifiedName + " " + field.name + "(" + field.type + " " + field.name + ")",
                builderQualifiedName,
                builderQualifiedName,
                new String[] {field.name},
                erasedParameterTypes,
                new String[] {field.type},
                IndexedMember.canonicalKey(
                        builderQualifiedName,
                        CompletionItemKind.Method,
                        field.name,
                        erasedParameterTypes),
                fieldKey,
                field.name,
                true,
                IndexedMember.Origin.LOMBOK_BUILDER,
                Set.of(Modifier.PUBLIC),
                null,
                null).withNavigation(ownerQualifiedName, fieldKey);
    }

    private static IndexedMember builderBuildMethod(String ownerQualifiedName, String builderQualifiedName) {
        return new IndexedMember(
                builderQualifiedName,
                "build",
                CompletionItemKind.Method,
                false,
                false,
                false,
                true,
                false,
                0,
                ownerQualifiedName + " build()",
                ownerQualifiedName,
                ownerQualifiedName,
                new String[0],
                new String[0],
                new String[0],
                IndexedMember.canonicalKey(builderQualifiedName, CompletionItemKind.Method, "build", new String[0]),
                IndexedMember.canonicalKey(builderQualifiedName, CompletionItemKind.Method, "build", new String[0]),
                null,
                true,
                IndexedMember.Origin.LOMBOK_BUILDER,
                Set.of(Modifier.PUBLIC),
                null,
                null);
    }

    private static void addSyntheticSlf4jLoggerField(
            String ownerQualifiedName, ClassTree declaration, Map<String, IndexedMember> seen) {
        if (declaration == null || !LombokAnnotations.hasLoggingOnlyLombokAnnotation(declaration.getModifiers())) {
            return;
        }
        var next =
                new IndexedMember(
                        ownerQualifiedName,
                        "log",
                        CompletionItemKind.Field,
                        true,
                        true,
                        false,
                        false,
                        false,
                        0,
                        "org.slf4j.Logger log",
                        "org.slf4j.Logger",
                        "org.slf4j.Logger",
                        null,
                        null,
                        null,
                        IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Field, "log", null),
                        IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Field, "log", null),
                        null,
                        true,
                        IndexedMember.Origin.LOMBOK_LOGGER,
                        Set.of(Modifier.PRIVATE, Modifier.STATIC),
                        null,
                        null);
        seen.putIfAbsent(memberStorageKey(next), next);
    }

    private static void putSyntheticMethod(Map<String, IndexedMember> seen, IndexedMember next) {
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
            String ownerQualifiedName, TypeElement type, Map<String, IndexedMember> seen) {
        for (var component : type.getRecordComponents()) {
            var accessor = component.getAccessor();
            if (accessor == null) {
                continue;
            }
            var accessorName = accessor.getSimpleName().toString();
            var key = IndexedMember.canonicalKey(ownerQualifiedName, CompletionItemKind.Method, accessorName, new String[0]);
            var existing = seen.get(key);
            var logicalKey =
                    IndexedMember.canonicalKey(
                            ownerQualifiedName,
                            CompletionItemKind.Field,
                            component.getSimpleName().toString(),
                            null);
            if (existing == null) {
                seen.put(
                        key,
                        new IndexedMember(
                                ownerQualifiedName,
                                accessorName,
                                CompletionItemKind.Method,
                                accessor.getModifiers().contains(Modifier.STATIC),
                                accessor.getModifiers().contains(Modifier.PRIVATE),
                                accessor.getModifiers().contains(Modifier.PROTECTED),
                                accessor.getModifiers().contains(Modifier.PUBLIC),
                                accessor.getModifiers().contains(Modifier.ABSTRACT),
                                0,
                                accessor.getReturnType() + " " + accessorName + "()",
                                typeName(accessor.getReturnType()),
                                normalizeDeclaredType(accessor.getReturnType()),
                                new String[0],
                                new String[0],
                                new String[0],
                                key,
                                logicalKey,
                                component.getSimpleName().toString(),
                                true,
                                IndexedMember.Origin.RECORD_COMPONENT,
                                Set.copyOf(accessor.getModifiers()),
                                null,
                                null).withNavigation(ownerQualifiedName, logicalKey));
                continue;
            }
            if (existing.backingFieldName == null || existing.backingFieldName.isBlank()) {
                seen.put(
                        key,
                        mergeFieldLink(
                                existing,
                                logicalKey,
                                component.getSimpleName().toString(),
                                true,
                                IndexedMember.Origin.RECORD_COMPONENT)
                                        .withNavigation(ownerQualifiedName, logicalKey));
            }
        }
    }

    private static String memberStorageKey(IndexedMember member) {
        return IndexedMember.canonicalKey(
                member.ownerType, member.kind, member.name, member.erasedParameterTypes);
    }

    private static String builderQualifiedName(String ownerQualifiedName) {
        return ownerQualifiedName + "." + TypeNames.simpleName(ownerQualifiedName) + "Builder";
    }

    private static List<DeclaredField> declaredInstanceFields(ClassTree declaration, Map<String, IndexedMember> seen) {
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

    private static String resolvedFieldType(VariableTree variable, String fieldName, Map<String, IndexedMember> seen) {
        var fieldType = variable.getType() == null ? "java.lang.Object" : variable.getType().toString();
        for (var existing : seen.values()) {
            if (existing.kind != CompletionItemKind.Field || existing.isStatic) {
                continue;
            }
            if (!fieldName.equals(existing.name)) {
                continue;
            }
            if (existing.declaredReturnType != null && !existing.declaredReturnType.isBlank()) {
                return existing.declaredReturnType;
            }
            if (existing.returnType != null && !existing.returnType.isBlank()) {
                return existing.returnType;
            }
        }
        return fieldType;
    }

    private record DeclaredField(String name, String type) {}

    private static Map<String, IndexedMember> membersByStorageKey(List<IndexedMember> members) {
        var seen = new Object2ObjectOpenHashMap<String, IndexedMember>();
        for (var member : members) {
            seen.put(memberStorageKey(member), member);
        }
        return seen;
    }

    private static IndexedMember mergeLombokFieldLink(IndexedMember existing, IndexedMember synthetic) {
        return mergeFieldLink(
                        existing,
                        synthetic.logicalKey,
                        synthetic.backingFieldName,
                        synthetic.synthetic,
                        synthetic.origin)
                .withNavigation(synthetic.declarationOwnerType, synthetic.targetDeclarationKey);
    }

    private static IndexedMember mergeFieldLink(
            IndexedMember existing,
            String logicalKey,
            String backingFieldName,
            boolean synthetic,
            IndexedMember.Origin origin) {
        return new IndexedMember(
                existing.ownerType,
                existing.name,
                existing.kind,
                existing.isStatic,
                existing.isPrivate,
                existing.isProtected,
                existing.isPublic,
                existing.isAbstract,
                existing.priority,
                existing.detail,
                existing.returnType,
                existing.declaredReturnType,
                existing.parameterNames,
                existing.erasedParameterTypes,
                existing.declaredParameterTypes,
                existing.canonicalKey,
                logicalKey != null && !logicalKey.isBlank()
                        ? logicalKey
                        : existing.logicalKey,
                backingFieldName,
                existing.synthetic || synthetic,
                origin == null ? existing.origin : origin,
                existing.modifiers,
                existing.sourceUri,
                existing.declarationRange);
    }
}
