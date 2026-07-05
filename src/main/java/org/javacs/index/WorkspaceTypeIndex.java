package org.javacs.index;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
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
import javax.lang.model.element.Modifier;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import org.javacs.LombokAnnotations;
import org.javacs.ParseTask;
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
        var pending = new ArrayDeque<>(directSupertypes(qualifiedName));
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
                if (member.kind == CompletionItemKind.Constructor) {
                    continue; // constructors are not inherited
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
        var pending = new ArrayDeque<>(directSupertypes(qualifiedName));
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
                addRecordCanonicalConstructorFromParseTree(qualifiedName, classTree, seen);
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
            Map<String, URI> typeSourceUris,
            Map<String, Integer> typeKinds,
            Map<String, Set<Modifier>> typeModifiers,
            Map<String, Set<String>> nestedTypesByOwner,
            Map<String, CompilationUnitTree> typeRoots,
            Map<Path, SourceFileSnapshot> sourceFileSnapshots) {
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        Path sourcePath = null;
        URI sourceUri = null;
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

    private static List<String> resolveParseInterfacesFromTree(
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
        if (name == null || name.isBlank()) return;

        var isConstructor = "<init>".equals(name);
        var flags = method.getModifiers().getFlags();
        var isStatic = flags.contains(Modifier.STATIC);
        var isPrivate = flags.contains(Modifier.PRIVATE);
        var isProtected = flags.contains(Modifier.PROTECTED);
        var isPublic = flags.contains(Modifier.PUBLIC);
        var isAbstract = flags.contains(Modifier.ABSTRACT);

        var kind = isConstructor ? CompletionItemKind.Constructor : CompletionItemKind.Method;
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
                ownerQualifiedName, kind, name, erasedParamTypes);
        var detail = isConstructor
                ? TypeNames.simpleName(ownerQualifiedName) + "(" + String.join(", ", declaredParamTypes) + ")"
                : returnTypeStr + " " + name + "(" + String.join(", ", declaredParamTypes) + ")";

        var next = new IndexedMember(
                ownerQualifiedName, name, kind,
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
     * Synthesize the canonical constructor for a record from its components.
     * Only adds one if no explicit constructor with matching arity was already indexed.
     */
    private static void addRecordCanonicalConstructorFromParseTree(
            String ownerQualifiedName, ClassTree classTree, Map<String, IndexedMember> seen) {
        var paramNames = new java.util.ArrayList<String>();
        var erasedParamTypes = new java.util.ArrayList<String>();
        var declaredParamTypes = new java.util.ArrayList<String>();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof VariableTree vt)) continue;
            if (!(vt instanceof JCVariableDecl jcVar)) continue;
            if ((jcVar.mods.flags & Flags.RECORD) == 0) continue;
            var name = jcVar.getName() == null ? null : jcVar.getName().toString();
            if (name == null || name.isBlank()) continue;
            paramNames.add(name);
            var typeStr = jcVar.vartype == null ? "Object" : jcVar.vartype.toString();
            declaredParamTypes.add(typeStr);
            erasedParamTypes.add(TypeNames.normalize(typeStr));
        }
        var pNames = paramNames.toArray(String[]::new);
        var ePTypes = erasedParamTypes.toArray(String[]::new);
        var dPTypes = declaredParamTypes.toArray(String[]::new);
        var canonicalKey = IndexedMember.canonicalKey(
                ownerQualifiedName, CompletionItemKind.Constructor, "<init>", ePTypes);
        if (seen.containsKey(canonicalKey)) return; // explicit constructor already indexed
        var simpleName = TypeNames.simpleName(ownerQualifiedName);
        var detail = simpleName + "(" + String.join(", ", dPTypes) + ")";
        seen.put(canonicalKey, new IndexedMember(
                ownerQualifiedName, "<init>", CompletionItemKind.Constructor,
                false, false, false, true, false,
                0, detail, "void", "void",
                pNames, ePTypes, dPTypes,
                canonicalKey, canonicalKey, null, true,
                IndexedMember.Origin.RECORD_COMPONENT, Set.of(Modifier.PUBLIC), null, null));
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
            if (parentMember.kind == CompletionItemKind.Constructor) continue; // constructors are not inherited
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


    private static Map<String, Set<String>> invertSubtypeMap(Map<String, IndexedType> typesByQualifiedName) {
        var subtypes = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        for (var entry : typesByQualifiedName.entrySet()) {
            for (var superType : entry.getValue().directSupertypes) {
                subtypes.computeIfAbsent(superType, __ -> new ObjectLinkedOpenHashSet<>()).add(entry.getKey());
            }
        }
        return subtypes;
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

            // Add builder() static factory method to owner if not already present
            var builderMethodKey = IndexedMember.canonicalKey(
                    ownerQualifiedName, CompletionItemKind.Method, "builder", new String[0]);
            var hasBuilderMethod = ownerInfo.members.stream()
                    .anyMatch(m -> builderMethodKey.equals(m.canonicalKey));
            if (!hasBuilderMethod) {
                var ownerMembers = new ArrayList<>(ownerInfo.members);
                ownerMembers.add(builderFactoryMethod(ownerQualifiedName, builderQualifiedName));
                IndexedMember.sort(ownerMembers);
                typeEntries.put(ownerQualifiedName, new IndexedType(
                        ownerInfo.qualifiedName,
                        ownerInfo.simpleName,
                        ownerMembers,
                        ownerInfo.sourcePath,
                        ownerInfo.sourceUri,
                        ownerInfo.superclass,
                        ownerInfo.interfaces,
                        ownerInfo.nestedTypes,
                        ownerInfo.kind,
                        ownerInfo.modifiers,
                        ownerInfo.declarationRange,
                        ownerInfo.provenance));
            }

            // Create or merge builder type
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
                existing.sourcePath != null ? existing.sourcePath : sourcePath,
                existing.superclass,
                existing.interfaces,
                existing.provenance);
    }

    private static IndexedMember builderFactoryMethod(String ownerQualifiedName, String builderQualifiedName) {
        return new IndexedMember(
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
                null);
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
