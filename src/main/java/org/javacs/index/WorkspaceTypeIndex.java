package org.javacs.index;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayDeque;
import java.util.function.BiPredicate;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Modifier;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import org.javacs.LombokAnnotations;
import org.javacs.LombokStubInjector;
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
        public final String declarationKey;

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
                List<String> declaredTypes,
                String declarationKey) {
            this.sourcePath = sourcePath;
            this.sourceUri = sourceUri;
            this.packageName = packageName;
            this.imports = Collections.unmodifiableList(new ArrayList<>(imports));
            this.staticImports = Collections.unmodifiableList(new ArrayList<>(staticImports));
            this.declaredTypes = Collections.unmodifiableList(new ArrayList<>(declaredTypes));
            this.declarationKey = declarationKey == null ? "" : declarationKey;
        }
    }

    private final Map<String, IndexedType> typesByQualifiedName;
    private final Set<String> workspaceOwnedTypeNames;
    private final Map<String, Set<String>> subtypesByType;
    private final Map<Path, SourceFileSnapshot> sourceFiles;

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

    public WorkspaceTypeIndex restrictTo(Set<Path> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) return EMPTY;
        var types = new Object2ObjectLinkedOpenHashMap<String, IndexedType>();
        for (var entry : typesByQualifiedName.entrySet()) {
            var source = entry.getValue().sourcePath;
            if (source != null && sourceRoots.stream().anyMatch(source::startsWith)) {
                types.put(entry.getKey(), entry.getValue());
            }
        }
        var files = new Object2ObjectLinkedOpenHashMap<Path, SourceFileSnapshot>();
        for (var entry : sourceFiles.entrySet()) {
            if (sourceRoots.stream().anyMatch(entry.getKey()::startsWith)) {
                files.put(entry.getKey(), entry.getValue());
            }
        }
        return new WorkspaceTypeIndex(types, files);
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

    /** Return true when an update contains the same index-facing declarations for these files. */
    public boolean hasSameDeclarations(WorkspaceTypeIndex updates, Collection<Path> files) {
        if (updates == null) return false;
        for (var file : files) {
            var before = sourceFiles.get(file);
            var after = updates.sourceFiles.get(file);
            if (before == null || after == null
                    || !Objects.equals(before.declarationKey, after.declarationKey)) return false;
        }
        return true;
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
                members.add(withInheritedPriority(member));
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
                        return Optional.of(withInheritedPriority(member));
                    }
                } else if (Objects.equals(name, member.name)) {
                    return Optional.of(withInheritedPriority(member));
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
     * <p>Inherited members are resolved lazily at query time.
     *
     * <p>Record component accessors are synthesized from the parse tree without attribution.
     * Lombok members are read from the same injected AST used by the compiler.
     */
    public static WorkspaceTypeIndex fromParseTrees(List<ParseTask> parseTasks) {
        return fromParseTrees(parseTasks, __ -> false);
    }

    public static WorkspaceTypeIndex fromParseTrees(
            List<ParseTask> parseTasks, Predicate<String> knownType) {
        return fromParseTrees(parseTasks, (__, name) -> knownType.test(name), (__, ___) -> true);
    }

    public static WorkspaceTypeIndex fromParseTrees(
            List<ParseTask> parseTasks,
            BiPredicate<Path, String> knownType,
            BiPredicate<Path, Path> sourceVisible) {
        // Single-pass: parse each file, extract all metadata + members, discard AST immediately.
        // Supertype resolution is deferred to a post-pass using collected import/package data.
        var allQualifiedNames = new ObjectOpenHashSet<String>();
        var typeSources = new Object2ObjectOpenHashMap<String, Path>();
        var typeSourceUris = new Object2ObjectOpenHashMap<String, java.net.URI>();
        var typeKinds = new Object2ObjectOpenHashMap<String, Integer>();
        var typeModifiers = new Object2ObjectOpenHashMap<String, Set<Modifier>>();
        var nestedTypesByOwner = new Object2ObjectOpenHashMap<String, Set<String>>();
        var sourceFileSnapshots = new Object2ObjectLinkedOpenHashMap<Path, SourceFileSnapshot>();
        // Per-type: raw (unresolved) supertype/interface names from parse tree
        var rawSupertypes = new Object2ObjectOpenHashMap<String, String>();
        var rawInterfaces = new Object2ObjectOpenHashMap<String, List<String>>();
        // Per-type: which source file (for import resolution in post-pass)
        var typeSourceFile = new Object2ObjectOpenHashMap<String, Path>();
        // Members extracted per type
        var typeDirectMembers = new Object2ObjectOpenHashMap<String, Map<String, IndexedMember>>();

        for (int i = 0; i < parseTasks.size(); i++) {
            var parseTask = parseTasks.get(i);
            var root = parseTask.root();
            LombokStubInjector.injectParseTask(parseTask);
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

            // Extract types, members, raw supertypes — all in one scan
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree tree, Void p) {
                    var simpleName = tree.getSimpleName() == null ? null : tree.getSimpleName().toString();
                    if (simpleName == null || simpleName.isBlank()) {
                        return super.visitClass(tree, p);
                    }
                    var qualified = qualifiedNameStack.isEmpty()
                            ? (packageName.isBlank() ? simpleName : packageName + "." + simpleName)
                            : qualifiedNameStack.peek() + "." + simpleName;
                    if (!isValidIndexKey(qualified)) {
                        return super.visitClass(tree, p);
                    }

                    if (!qualifiedNameStack.isEmpty()) {
                        var parentName = qualifiedNameStack.peek();
                        nestedTypesByOwner
                                .computeIfAbsent(parentName, __ -> new ObjectLinkedOpenHashSet<>())
                                .add(qualified);
                    }

                    qualifiedNameStack.push(qualified);
                    allQualifiedNames.add(qualified);
                    typeKinds.put(qualified, parseTreeKindToCompletionItemKind(tree.getKind()));
                    typeModifiers.put(qualified, Set.copyOf(tree.getModifiers().getFlags()));
                    declaredTypesInFile.add(qualified);
                    if (finalSourcePath != null) {
                        typeSources.put(qualified, finalSourcePath);
                        typeSourceUris.put(qualified, finalSourceUri);
                        typeSourceFile.put(qualified, finalSourcePath);
                    }

                    // Store raw supertype/interface strings (resolve later)
                    if (tree.getExtendsClause() != null) {
                        var raw = TypeNames.normalize(tree.getExtendsClause().toString());
                        if (raw != null && !raw.isBlank()) rawSupertypes.put(qualified, raw);
                    }
                    var rawIfaceList = new ArrayList<String>();
                    for (var iface : tree.getImplementsClause()) {
                        var raw = TypeNames.normalize(iface.toString());
                        if (raw != null && !raw.isBlank()) rawIfaceList.add(raw);
                    }
                    if (!rawIfaceList.isEmpty()) rawInterfaces.put(qualified, rawIfaceList);

                    // Extract members
                    var seen = new Object2ObjectOpenHashMap<String, IndexedMember>();
                    typeDirectMembers.put(qualified, seen);
                    var enclosingIsInterface = tree.getKind() == Tree.Kind.INTERFACE
                            || tree.getKind() == Tree.Kind.ANNOTATION_TYPE;
                    for (var member : tree.getMembers()) {
                        if (member instanceof MethodTree method) {
                            addParseTreeMethod(qualified, method, seen, tree, isGeneratedClass(tree));
                        } else if (member instanceof VariableTree variable) {
                            addParseTreeField(
                                    qualified,
                                    variable,
                                    seen,
                                    enclosingIsInterface,
                                    isGeneratedClass(tree));
                        }
                    }
                    if (tree.getKind() == Tree.Kind.RECORD) {
                        addRecordComponentAccessorsFromParseTree(qualified, tree, seen);
                        addRecordCanonicalConstructorFromParseTree(qualified, tree, seen);
                    }

                    var result = super.visitClass(tree, p);
                    qualifiedNameStack.pop();
                    return result;
                }
            }.scan(root, null);

            if (finalSourcePath != null) {
                sourceFileSnapshots.put(finalSourcePath, new SourceFileSnapshot(
                        finalSourcePath, finalSourceUri, packageName,
                        explicitImports, staticImports, declaredTypesInFile, ""));
            }
            // Allow GC to reclaim this file's AST before parsing the next
            parseTasks.set(i, null);
        }
        // ASTs are now eligible for GC — only lightweight data structures remain.

        // === Post-pass: resolve raw supertype/interface names using collected workspace names ===
        var typeSupertypes = new Object2ObjectOpenHashMap<String, String>();
        var typeInterfacesList = new Object2ObjectOpenHashMap<String, List<String>>();

        for (var qualifiedName : allQualifiedNames) {
            var sourcePath = typeSources.get(qualifiedName);
            Predicate<String> workspaceContains = name -> {
                var declaredSource = typeSources.get(name);
                return declaredSource != null && sourceVisible.test(sourcePath, declaredSource)
                        || knownType.test(sourcePath, name);
            };
            var snapshot = sourcePath != null ? sourceFileSnapshots.get(sourcePath) : null;

            // Resolve supertype
            var rawSupertype = rawSupertypes.get(qualifiedName);
            if (rawSupertype != null) {
                if (rawSupertype.contains(".")) {
                    typeSupertypes.put(qualifiedName, rawSupertype);
                } else {
                    var resolved = resolveInEnclosingScopes(rawSupertype, qualifiedName, workspaceContains);
                    if (resolved == null && snapshot != null) {
                        resolved = resolveSimpleTypeNameFromSnapshot(rawSupertype, snapshot, workspaceContains);
                    }
                    if (resolved != null) typeSupertypes.put(qualifiedName, resolved);
                }
            }

            // Resolve interfaces
            var rawIfaceList = rawInterfaces.get(qualifiedName);
            if (rawIfaceList != null) {
                var resolved = new ArrayList<String>();
                for (var rawIface : rawIfaceList) {
                    if (rawIface.contains(".")) {
                        resolved.add(rawIface);
                    } else {
                        var r = resolveInEnclosingScopes(rawIface, qualifiedName, workspaceContains);
                        if (r == null && snapshot != null) {
                            r = resolveSimpleTypeNameFromSnapshot(rawIface, snapshot, workspaceContains);
                        }
                        if (r != null) resolved.add(r);
                    }
                }
                typeInterfacesList.put(qualifiedName, Collections.unmodifiableList(resolved));
            }
        }

        // === Build IndexedType entries from the injected AST ===
        var typeEntries = new Object2ObjectLinkedOpenHashMap<String, IndexedType>();

        for (var qualifiedName : allQualifiedNames) {
            var seen = typeDirectMembers.get(qualifiedName);
            var sourcePath = typeSources.get(qualifiedName);

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

        var finalizedSourceFiles = finalizeSourceFiles(sourceFileSnapshots, typeEntries);

        return new WorkspaceTypeIndex(
                        Collections.unmodifiableMap(typeEntries),
                        Collections.unmodifiableMap(finalizedSourceFiles));
    }

    /** Resolve a simple type name using import/package data from a SourceFileSnapshot. */
    private static String resolveSimpleTypeNameFromSnapshot(
            String simpleName, SourceFileSnapshot snapshot, Predicate<String> containsType) {
        if (simpleName == null || simpleName.isBlank()) return null;
        if (Character.isLowerCase(simpleName.charAt(0)) && simpleName.indexOf('.') < 0) return null;
        // Check explicit imports
        for (var imported : snapshot.imports) {
            if (!imported.endsWith(".*") && imported.endsWith("." + simpleName) && containsType.test(imported)) {
                return imported;
            }
        }
        // Check same package
        var candidates = new ObjectLinkedOpenHashSet<String>();
        if (snapshot.packageName != null && !snapshot.packageName.isBlank()) {
            var samePackage = snapshot.packageName + "." + simpleName;
            if (containsType.test(samePackage)) candidates.add(samePackage);
        }
        // Check wildcard imports
        for (var imported : snapshot.imports) {
            if (imported.endsWith(".*")) {
                var candidate = imported.substring(0, imported.length() - 1) + simpleName;
                if (containsType.test(candidate)) candidates.add(candidate);
            }
        }
        // Check java.lang
        var javaLang = "java.lang." + simpleName;
        if (containsType.test(javaLang)) candidates.add(javaLang);
        if (candidates.size() == 1) return candidates.iterator().next();
        return null;
    }

    private static Map<Path, SourceFileSnapshot> finalizeSourceFiles(
            Map<Path, SourceFileSnapshot> sourceFiles, Map<String, IndexedType> types) {
        var declaredTypesByFile = new Object2ObjectLinkedOpenHashMap<Path, List<String>>();
        for (var type : types.values()) {
            if (type.sourcePath != null) {
                declaredTypesByFile
                        .computeIfAbsent(type.sourcePath, __ -> new ArrayList<>())
                        .add(type.qualifiedName);
            }
        }
        var result = new Object2ObjectLinkedOpenHashMap<Path, SourceFileSnapshot>();
        for (var entry : sourceFiles.entrySet()) {
            var snapshot = entry.getValue();
            var declaredTypes = new ArrayList<>(
                    declaredTypesByFile.getOrDefault(entry.getKey(), snapshot.declaredTypes));
            declaredTypes.sort(String::compareTo);
            result.put(entry.getKey(), new SourceFileSnapshot(
                    snapshot.sourcePath,
                    snapshot.sourceUri,
                    snapshot.packageName,
                    snapshot.imports,
                    snapshot.staticImports,
                    declaredTypes,
                    declarationKey(snapshot, declaredTypes, types)));
        }
        return result;
    }

    private static String declarationKey(
            SourceFileSnapshot source, List<String> declaredTypes, Map<String, IndexedType> types) {
        var key = new StringBuilder();
        appendDeclarationValue(key, source.packageName);
        appendDeclarationValues(key, source.imports);
        appendDeclarationValues(key, source.staticImports);
        appendDeclarationValues(key, declaredTypes);
        for (var qualifiedName : declaredTypes) {
            var type = types.get(qualifiedName);
            if (type == null) continue;
            appendDeclarationValue(key, type.qualifiedName);
            appendDeclarationValue(key, type.superclass);
            appendDeclarationValues(key, type.interfaces);
            appendDeclarationValues(key, type.nestedTypes.stream().sorted().toList());
            appendDeclarationValue(key, Integer.toString(type.kind));
            appendDeclarationValues(
                    key, type.modifiers.stream().map(Modifier::name).sorted().toList());
            appendDeclarationValue(key, Integer.toString(type.members.size()));
            for (var member : type.members) {
                appendDeclarationValue(key, member.ownerType);
                appendDeclarationValue(key, member.name);
                appendDeclarationValue(key, Integer.toString(member.kind));
                appendDeclarationValue(key, Boolean.toString(member.isStatic));
                appendDeclarationValue(key, Boolean.toString(member.isPrivate));
                appendDeclarationValue(key, Boolean.toString(member.isProtected));
                appendDeclarationValue(key, Boolean.toString(member.isPublic));
                appendDeclarationValue(key, Boolean.toString(member.isAbstract));
                appendDeclarationValue(key, Integer.toString(member.priority));
                appendDeclarationValue(key, member.detail);
                appendDeclarationValue(key, member.returnType);
                appendDeclarationValue(key, member.declaredReturnType);
                appendDeclarationValues(key, member.parameterNames);
                appendDeclarationValues(key, member.erasedParameterTypes);
                appendDeclarationValues(key, member.declaredParameterTypes);
                appendDeclarationValue(key, member.canonicalKey);
                appendDeclarationValue(key, member.logicalKey);
                appendDeclarationValue(key, member.backingFieldName);
                appendDeclarationValue(key, Boolean.toString(member.synthetic));
                appendDeclarationValue(key, member.origin.name());
                appendDeclarationValue(key, member.provenance.name());
                appendDeclarationValues(
                        key, member.modifiers.stream().map(Modifier::name).sorted().toList());
                appendDeclarationValue(key, member.declarationOwnerType);
                appendDeclarationValue(key, member.targetDeclarationKey);
            }
        }
        return key.toString();
    }

    private static void appendDeclarationValues(StringBuilder target, List<String> values) {
        target.append(values == null ? -1 : values.size()).append(':');
        if (values != null) {
            for (var value : values) appendDeclarationValue(target, value);
        }
    }

    private static void appendDeclarationValues(StringBuilder target, String[] values) {
        target.append(values == null ? -1 : values.length).append(':');
        if (values != null) {
            for (var value : values) appendDeclarationValue(target, value);
        }
    }

    private static void appendDeclarationValue(StringBuilder target, String value) {
        target.append(value == null ? -1 : value.length()).append(':');
        if (value != null) target.append(value);
    }

    private static int parseTreeKindToCompletionItemKind(Tree.Kind kind) {
        return switch (kind) {
            case INTERFACE, ANNOTATION_TYPE -> CompletionItemKind.Interface;
            case ENUM -> CompletionItemKind.Enum;
            default -> CompletionItemKind.Class; // CLASS, RECORD
        };
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
            String ownerQualifiedName,
            MethodTree method,
            Map<String, IndexedMember> seen,
            ClassTree declaration,
            boolean enclosingIsGenerated) {
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
        var generated = method instanceof com.sun.tools.javac.tree.JCTree.JCMethodDecl jcMethod
                && (jcMethod.mods.flags & Flags.GENERATED_MEMBER) != 0;
        if (generated && "builder".equals(name) && !enclosingIsGenerated) {
            returnTypeStr = ownerQualifiedName + "." + returnTypeStr;
        } else if (generated && enclosingIsGenerated) {
            var separator = ownerQualifiedName.lastIndexOf('.');
            if (separator > 0) {
                returnTypeStr = "build".equals(name) && params.isEmpty()
                        ? ownerQualifiedName.substring(0, separator)
                        : ownerQualifiedName;
            }
        }
        var detail = isConstructor
                ? TypeNames.simpleName(ownerQualifiedName) + "(" + String.join(", ", declaredParamTypes) + ")"
                : returnTypeStr + " " + name + "(" + String.join(", ", declaredParamTypes) + ")";

        var origin = generated
                ? (isConstructor
                        ? IndexedMember.Origin.LOMBOK_CONSTRUCTOR
                        : enclosingIsGenerated || "builder".equals(name)
                                ? IndexedMember.Origin.LOMBOK_BUILDER
                                : IndexedMember.Origin.LOMBOK_ACCESSOR)
                : IndexedMember.Origin.DECLARED;
        var logicalKey = canonicalKey;
        var backingFieldName = (String) null;
        var declarationOwnerType = (String) null;
        var targetDeclarationKey = (String) null;
        if (generated && !isConstructor && !enclosingIsGenerated && !"builder".equals(name)) {
            var accessorField = generatedAccessorFieldName(name, declaration);
            if (accessorField != null) {
                backingFieldName = accessorField;
                logicalKey = IndexedMember.canonicalKey(
                        ownerQualifiedName, CompletionItemKind.Field, backingFieldName, null);
                declarationOwnerType = ownerQualifiedName;
                targetDeclarationKey = logicalKey;
            }
        } else if (generated && !isConstructor && enclosingIsGenerated) {
            var separator = ownerQualifiedName.lastIndexOf('.');
            if (separator > 0) {
                var outerOwner = ownerQualifiedName.substring(0, separator);
                if (!("build".equals(name) && params.isEmpty())) {
                    backingFieldName = params.isEmpty() ? name : params.get(0).getName().toString();
                    logicalKey = IndexedMember.canonicalKey(
                            outerOwner, CompletionItemKind.Field, backingFieldName, null);
                    declarationOwnerType = outerOwner;
                    targetDeclarationKey = logicalKey;
                }
            }
        }

        var next = new IndexedMember(
                ownerQualifiedName, name, kind,
                isStatic, isPrivate, isProtected, isPublic, isAbstract,
                0, detail, returnTypeStr, returnTypeStr,
                paramNames, erasedParamTypes, declaredParamTypes,
                canonicalKey, logicalKey, backingFieldName, generated,
                origin, Set.copyOf(flags), null, null);

        if (declarationOwnerType != null) {
            next = next.withNavigation(declarationOwnerType, targetDeclarationKey);
        }

        seen.putIfAbsent(memberStorageKey(next), next);
    }

    private static String generatedAccessorFieldName(String methodName, ClassTree declaration) {
        for (var member : declaration.getMembers()) {
            if (!(member instanceof VariableTree field)
                    || field.getName() == null
                    || field.getModifiers().getFlags().contains(Modifier.STATIC)) {
                continue;
            }
            if (field instanceof JCVariableDecl jcField
                    && (jcField.mods.flags & Flags.GENERATED_MEMBER) != 0) {
                continue;
            }
            var fieldName = field.getName().toString();
            var fieldType = field.getType() == null ? "Object" : field.getType().toString();
            var accessorInfo = LombokAnnotations.accessorInfo(
                    declaration.getModifiers(), field.getModifiers(), fieldName, fieldType);
            if (accessorInfo.isPresent()) {
                var info = accessorInfo.get();
                if (methodName.equals(info.getterName()) || methodName.equals(info.setterName())) {
                    return fieldName;
                }
            }
        }
        return null;
    }

    /**
     * Add a field member from the parse tree.
     *
     * <p>The type string is the raw parse-tree text (may include generics or be a simple name).
     * {@link org.javacs.resolve.ParseTypeResolver} resolves these at query time.
     */
    private static void addParseTreeField(
            String ownerQualifiedName, VariableTree variable, Map<String, IndexedMember> seen,
            boolean enclosingIsInterface, boolean enclosingIsGenerated) {
        var name = variable.getName() == null ? null : variable.getName().toString();
        if (name == null || name.isBlank()) return;

        var generated = variable instanceof com.sun.tools.javac.tree.JCTree.JCVariableDecl jcVariable
                && (jcVariable.mods.flags & Flags.GENERATED_MEMBER) != 0;
        // Lombok's builder stores values in private generated fields. They are implementation
        // details and must not leak into completion/member lookup.
        if (generated && enclosingIsGenerated && variable.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
            return;
        }

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

        var origin = generated
                ? (enclosingIsGenerated
                        ? IndexedMember.Origin.LOMBOK_BUILDER
                        : IndexedMember.Origin.LOMBOK_LOGGER)
                : IndexedMember.Origin.DECLARED;
        var next = new IndexedMember(
                ownerQualifiedName, name, kind,
                isStatic, isPrivate, isProtected, isPublic, false,
                0, typeStr + " " + name, typeStr, typeStr,
                null, null, null,
                canonicalKey, canonicalKey, null, generated,
                origin, Set.copyOf(flags), null, null);

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
        var paramNames = new ArrayList<String>();
        var erasedParamTypes = new ArrayList<String>();
        var declaredParamTypes = new ArrayList<String>();
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

    private static boolean isGeneratedClass(ClassTree tree) {
        return tree instanceof com.sun.tools.javac.tree.JCTree.JCClassDecl classDecl
                && (classDecl.mods.flags & Flags.GENERATED_MEMBER) != 0;
    }

    private static String memberStorageKey(IndexedMember member) {
        return IndexedMember.canonicalKey(
                member.ownerType, member.kind, member.name, member.erasedParameterTypes);
    }

}
