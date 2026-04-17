package org.javacs.index;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.VariableTree;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ConstantPoolException;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.function.Function;
import org.javacs.CompilerProvider;
import org.javacs.CacheAudit;
import org.javacs.FindHelper;
import org.javacs.LombokAnnotations;
import org.javacs.completion.ExternalBinaryDecompiler;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.resolve.TypeNames;

/**
 * Dependency-only type metadata backed by the classpath.
 *
 * <p>This index serves jars, JDK classes, and decompiled dependency sources. Workspace-owned
 * candidates must be resolved before reaching this class. Any workspace hit here is a correctness
 * bug. The published member shape is shared with the workspace index via
 * {@link IndexedMember}; every member emitted here is stamped with
 * {@link IndexedMember.Provenance#EXTERNAL_BINARY}. Vineflower support here is only for
 * read-only dependency inspection and navigation.
 */
public final class ExternalBinaryTypeIndex {
    public static final ExternalBinaryTypeIndex EMPTY = new ExternalBinaryTypeIndex();

    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final String classPathFingerprint;
    private final Set<Path> classPathRoots;
    private final ClassLoader classLoader;
    private final Cache<String, Optional<IndexedType>> rawTypeCache;
    private final Cache<String, Optional<IndexedType>> typeCache;
    private final Cache<String, Optional<Path>> decompiledSourceCache;
    private final Cache<String, Optional<BinaryClassModel>> classFileCache;
    private final ExternalBinaryDecompiler decompiler;

    /**
     * Empty dependency index used when no external lookup is available.
     *
     * <p>All constructor fields are initialized to harmless defaults:
     *
     * <pre>{@code
     * compiler = null
     * classPathRoots = []
     * classPathFingerprint = ""
     * }</pre>
     */
    private ExternalBinaryTypeIndex() {
        this.compiler = null;
        this.classPathFingerprint = "";
        this.classPathRoots = Set.of();
        this.classLoader = ExternalBinaryTypeIndex.class.getClassLoader();
        this.rawTypeCache = Caffeine.newBuilder().maximumSize(1).build();
        this.typeCache = Caffeine.newBuilder().maximumSize(1).build();
        this.decompiledSourceCache = Caffeine.newBuilder().maximumSize(1).build();
        this.classFileCache = Caffeine.newBuilder().maximumSize(1).build();
        this.decompiler = new ExternalBinaryDecompiler(Set.of(), "", classLoader);
    }

    /**
     * Build a dependency-only index for jars and JDK classes visible on the compiler classpath.
     *
     * <p>Constructor fields mean:
     *
     * <p>{@code compiler}: source of classpath roots and attached source lookup
     *
     * <p>{@code classPathRoots}: the concrete jars/directories that will be scanned and loaded
     *
     * <p>{@code classPathFingerprint}: stable cache key derived from those roots so decompiled
     * artifacts can be reused safely
     *
     * <p>{@code classLoader}: runtime loader used for reflection-based dependency metadata
     *
     * <p>The caches store already-decoded dependency facts. They do not hold workspace symbols.
     *
     * <p>Example:
     *
     * <pre>{@code
     * compiler.classPathRoots() = [
     *   /repo/.m2/repository/org/projectlombok/lombok-1.18.30.jar,
     *   /repo/.m2/repository/com/google/guava/guava-33.0.0.jar
     * ]
     * }</pre>
     *
     * <p>That gives this index enough information to answer lookups for dependency types such as
     * {@code com.google.common.collect.ImmutableList} without consulting workspace source.
     */
    public ExternalBinaryTypeIndex(CompilerProvider compiler) {
        this.compiler = compiler;
        var classPath = compiler == null ? Set.<Path>of() : compiler.classPathRoots();
        this.classPathRoots = Set.copyOf(classPath);
        this.classPathFingerprint = fingerprint(classPath);
        this.classLoader = buildClassLoader(classPath);
        this.rawTypeCache =
                Caffeine.newBuilder()
                        .maximumSize(20_000)
                        .expireAfterAccess(Duration.ofMinutes(30))
                        .build();
        this.typeCache =
                Caffeine.newBuilder()
                        .maximumSize(20_000)
                        .expireAfterAccess(Duration.ofMinutes(30))
                        .build();
        this.decompiledSourceCache =
                Caffeine.newBuilder()
                        .maximumSize(5_000)
                        .expireAfterAccess(Duration.ofMinutes(30))
                        .build();
        this.classFileCache =
                Caffeine.newBuilder()
                        .maximumSize(20_000)
                        .expireAfterAccess(Duration.ofMinutes(30))
                        .build();
        this.decompiler = new ExternalBinaryDecompiler(this.classPathRoots, this.classPathFingerprint, this.classLoader);
    }

    public int size() {
        return (int)typeCache.estimatedSize();
    }

    public Optional<IndexedType> typeInfo(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return Optional.empty();
        }
        return lookup(typeCache, qualifiedName, this::loadLinkedTypeInfo, "external_binary.type");
    }

    public boolean containsType(String qualifiedName) {
        return rawTypeInfo(qualifiedName).isPresent();
    }

    public List<IndexedMember> members(String qualifiedName, boolean staticContext) {
        var type = rawTypeInfo(qualifiedName);
        if (type.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<IndexedMember>();
        for (var member : type.get().members) {
            if (member.isStatic == staticContext) {
                result.add(ensureExternalProvenance(member));
            }
        }
        return result;
    }

    public Optional<IndexedMember> member(String qualifiedName, String name, boolean staticContext) {
        var linked = linkedMembers(qualifiedName, staticContext);
        for (var member : linked) {
            if (Objects.equals(name, member.name)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public Optional<IndexedMember> member(
            String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var targetKey =
                IndexedMember.canonicalKey(
                        qualifiedName, CompletionItemKind.Method, name, erasedParameterTypes);
        for (var member : linkedMembers(qualifiedName, staticContext)) {
            if (Objects.equals(targetKey, member.canonicalKey)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public Optional<IndexedMember> rawMember(String qualifiedName, String name, boolean staticContext) {
        for (var member : members(qualifiedName, staticContext)) {
            if (Objects.equals(name, member.name)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public Optional<IndexedMember> rawMember(
            String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var targetKey =
                IndexedMember.canonicalKey(
                        qualifiedName, CompletionItemKind.Method, name, erasedParameterTypes);
        for (var member : members(qualifiedName, staticContext)) {
            if (Objects.equals(targetKey, member.canonicalKey)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    private List<IndexedMember> linkedMembers(String qualifiedName, boolean staticContext) {
        return typeInfo(qualifiedName)
                .map(
                        info ->
                                info.members.stream()
                                        .filter(member -> member.isStatic == staticContext)
                                        .map(this::ensureExternalProvenance)
                                        .toList())
                .orElse(List.of());
    }

    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        if (typeName == null || typeName.isBlank() || root == null || compiler == null) {
            return Optional.empty();
        }
        var raw = TypeNames.normalize(typeName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (TypeNames.isPrimitive(raw)) {
            return Optional.of(raw);
        }
        if (raw.contains(".") && containsType(raw)) {
            return Optional.of(raw);
        }

        return TypeNames.resolveSimpleName(raw, root, this::containsType);
    }

    public Optional<IndexedType> resolveType(String typeName, CompilationUnitTree root) {
        return resolveTypeName(typeName, root).flatMap(this::typeInfo);
    }

    public Optional<Path> decompiledSourcePath(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return Optional.empty();
        }
        return lookup(
                decompiledSourceCache,
                qualifiedName,
                decompiler::decompileSourcePath,
                "external_binary.decompiled_source");
    }

    private Optional<IndexedType> rawTypeInfo(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return Optional.empty();
        }
        return lookup(rawTypeCache, qualifiedName, this::loadRawTypeInfo, "external_binary.type_raw");
    }

    private Optional<IndexedType> loadLinkedTypeInfo(String qualifiedName) {
        var raw = rawTypeInfo(qualifiedName);
        return raw.map(this::applySourceLinks);
    }

    private Optional<IndexedType> loadRawTypeInfo(String qualifiedName) {
        try {
            var binaryClass = Class.forName(qualifiedName, false, classLoader);
            if (binaryClass.isArray()) {
                return Optional.empty();
            }
            var publicFields = visibleFields(binaryClass);
            var seen = new LinkedHashMap<String, IndexedMember>();
            for (var field : publicFields) {
                try {
                    if (field.isSynthetic()) {
                        continue;
                    }
                    var declaring = field.getDeclaringClass().getName();
                    var priority = memberPriority(qualifiedName, declaring);
                    var member =
                            new IndexedMember(
                                    declaring,
                                    field.getName(),
                                    CompletionItemKind.Field,
                                    java.lang.reflect.Modifier.isStatic(field.getModifiers()),
                                    java.lang.reflect.Modifier.isPrivate(field.getModifiers()),
                                    priority,
                                    field.getType().getTypeName() + " " + field.getName(),
                                    canonicalTypeName(field.getType()),
                                    null,
                                    null,
                                    IndexedMember.canonicalKey(
                                            declaring, CompletionItemKind.Field, field.getName(), null),
                                    IndexedMember.canonicalKey(
                                            declaring, CompletionItemKind.Field, field.getName(), null),
                                    null,
                                    false,
                                    IndexedMember.Provenance.EXTERNAL_BINARY);
                    seen.putIfAbsent(memberKey(member), member);
                } catch (TypeNotPresentException | LinkageError ex) {
                    LOG.fine(
                            String.format(
                                    "[external-binary] skip field owner=%s field=%s reason=%s",
                                    qualifiedName,
                                    field.getName(),
                            ex.getClass().getSimpleName()));
                }
            }
            var publicMethods = visibleMethods(binaryClass);
            for (var method : publicMethods) {
                try {
                    if (method.isSynthetic() || method.isBridge()) {
                        continue;
                    }
                    var declaring = method.getDeclaringClass().getName();
                    var priority = memberPriority(qualifiedName, declaring);
                    var parameterNames = new String[method.getParameterCount()];
                    var erasedParameterTypes = new String[method.getParameterCount()];
                    var parameters = new StringJoiner(", ");
                    for (int i = 0; i < method.getParameterCount(); i++) {
                        var parameter = method.getParameters()[i];
                        parameterNames[i] = parameter.getName();
                        erasedParameterTypes[i] = method.getParameterTypes()[i].getTypeName();
                        parameters.add(canonicalTypeName(method.getParameterTypes()[i]) + " " + parameter.getName());
                    }
                    var detail =
                            canonicalTypeName(method.getReturnType())
                                    + " "
                                    + method.getName()
                                    + "("
                                    + parameters
                                    + ")";
                    var member =
                            new IndexedMember(
                                    declaring,
                                    method.getName(),
                                    CompletionItemKind.Method,
                                    java.lang.reflect.Modifier.isStatic(method.getModifiers()),
                                    java.lang.reflect.Modifier.isPrivate(method.getModifiers()),
                                    priority,
                                    detail,
                                    canonicalTypeName(method.getReturnType()),
                                    parameterNames,
                                    erasedParameterTypes,
                                    IndexedMember.canonicalKey(
                                            declaring, CompletionItemKind.Method, method.getName(), erasedParameterTypes),
                                    IndexedMember.canonicalKey(
                                            declaring, CompletionItemKind.Method, method.getName(), erasedParameterTypes),
                                    null,
                                    false,
                                    IndexedMember.Provenance.EXTERNAL_BINARY);
                    var key = memberKey(member);
                    var existing = seen.get(key);
                    if (existing == null || member.priority < existing.priority) {
                        seen.put(key, member);
                    }
                } catch (TypeNotPresentException | LinkageError ex) {
                    LOG.fine(
                            String.format(
                                    "[external-binary] skip method owner=%s method=%s reason=%s",
                                    qualifiedName,
                                    method.getName(),
                            ex.getClass().getSimpleName()));
                }
            }
            var members = new ArrayList<>(seen.values());
            IndexedMember.sort(members);
            var superclass =
                    binaryClass.getSuperclass() == null ? null : canonicalTypeName(binaryClass.getSuperclass());
            var interfaces =
                    java.util.Arrays.stream(binaryClass.getInterfaces())
                            .map(this::canonicalTypeName)
                            .collect(Collectors.toList());
            var linked =
                    new IndexedType(
                            qualifiedName,
                            binaryClass.getSimpleName(),
                            members,
                            false,
                            null,
                            superclass,
                            interfaces,
                            IndexedMember.Provenance.EXTERNAL_BINARY);
            return Optional.of(linked);
        } catch (ClassNotFoundException | LinkageError ex) {
            LOG.fine(
                    String.format(
                            "[external-binary] reflect miss type=%s reason=%s",
                            qualifiedName,
                            ex.getClass().getSimpleName()));
            var fallback =
                    lookup(
                            classFileCache,
                            qualifiedName,
                            this::loadBinaryClassModel,
                            "external_binary.classfile");
            if (fallback.isPresent()) {
                LOG.fine(String.format("[external-binary] classfile fallback type=%s", qualifiedName));
                return Optional.of(
                        new IndexedType(
                                fallback.get().qualifiedName(),
                                fallback.get().simpleName(),
                                fallback.get().members(),
                                false,
                                null,
                                null,
                                List.of(),
                                IndexedMember.Provenance.EXTERNAL_BINARY));
            }
            LOG.fine(String.format("[external-binary] miss type=%s reason=%s", qualifiedName, ex.getClass().getSimpleName()));
            return Optional.empty();
        }
    }

    private <T> Optional<T> lookup(
            Cache<String, Optional<T>> cache,
            String key,
            Function<String, Optional<T>> loader,
            String metricName) {
        var cached = cache.getIfPresent(key);
        if (cached != null) {
            CacheAudit.hit(metricName);
            return cached;
        }
        CacheAudit.miss(metricName);
        var loaded = loader.apply(key);
        cache.put(key, loaded);
        CacheAudit.load(metricName);
        CacheAudit.store(metricName);
        return loaded;
    }
    private Optional<BinaryClassModel> loadBinaryClassModel(String qualifiedName) {
        var relative = qualifiedName.replace('.', '/') + ".class";
        for (var root : classPathRoots) {
            try {
                if (Files.isDirectory(root)) {
                    var classFile = root.resolve(relative);
                    if (!Files.isRegularFile(classFile)) {
                        continue;
                    }
                    try (var in = Files.newInputStream(classFile)) {
                        return Optional.of(parseBinaryClassModel(qualifiedName, in));
                    }
                }
                if (!Files.isRegularFile(root)) {
                    continue;
                }
                try (var jar = new JarFile(root.toFile())) {
                    var entry = jar.getJarEntry(relative);
                    if (entry == null) {
                        continue;
                    }
                    try (var in = jar.getInputStream(entry)) {
                        return Optional.of(parseBinaryClassModel(qualifiedName, in));
                    }
                }
            } catch (IOException | ConstantPoolException ex) {
                LOG.fine(
                        String.format(
                                "[external-binary] classfile miss type=%s root=%s reason=%s",
                                qualifiedName,
                                root.getFileName(),
                                ex.getClass().getSimpleName()));
            }
        }
        return Optional.empty();
    }

    private BinaryClassModel parseBinaryClassModel(String qualifiedName, InputStream input)
            throws IOException, ConstantPoolException {
        var classFile = ClassFile.of().parse(input.readAllBytes());
        var simpleName = TypeNames.simpleName(qualifiedName);
        var seenMembers = new LinkedHashMap<String, IndexedMember>();

        for (var field : classFile.fields()) {
            if (field.flags().has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            var name = field.fieldName().stringValue();
            if (name == null || name.isBlank()) {
                continue;
            }
            var type = normalizeBinaryType(field.fieldTypeSymbol());
            var staticMember = field.flags().has(AccessFlag.STATIC);
            var privateMember = field.flags().has(AccessFlag.PRIVATE);
            var member =
                    new IndexedMember(
                            qualifiedName,
                            name,
                            CompletionItemKind.Field,
                            staticMember,
                            privateMember,
                            0,
                            type + " " + name,
                            type,
                            null,
                            null,
                            IndexedMember.canonicalKey(qualifiedName, CompletionItemKind.Field, name, null),
                            IndexedMember.canonicalKey(qualifiedName, CompletionItemKind.Field, name, null),
                            null,
                            false,
                            IndexedMember.Provenance.EXTERNAL_BINARY);
            seenMembers.putIfAbsent(memberKey(member), member);
        }

        for (var method : classFile.methods()) {
            if (method.flags().has(AccessFlag.SYNTHETIC)
                    || method.flags().has(AccessFlag.BRIDGE)) {
                continue;
            }
            var name = method.methodName().stringValue();
            if (name == null || name.isBlank() || "<clinit>".equals(name)) {
                continue;
            }
            var methodType = method.methodTypeSymbol();
            var parameterTypes = parseBinaryParameterTypes(methodType);
            var parameterNames = syntheticParameterNames(parameterTypes.length);
            if ("<init>".equals(name)) {
                continue;
            }
            var returnType = normalizeBinaryType(methodType.returnType());
            var staticMember = method.flags().has(AccessFlag.STATIC);
            var privateMember = method.flags().has(AccessFlag.PRIVATE);
            var detail =
                    returnType
                            + " "
                            + name
                            + "("
                            + renderParameters(parameterTypes, parameterNames)
                            + ")";
            var member =
                    new IndexedMember(
                            qualifiedName,
                            name,
                            CompletionItemKind.Method,
                            staticMember,
                            privateMember,
                            0,
                            detail,
                            returnType,
                            parameterNames,
                            parameterTypes,
                            IndexedMember.canonicalKey(qualifiedName, CompletionItemKind.Method, name, parameterTypes),
                            IndexedMember.canonicalKey(qualifiedName, CompletionItemKind.Method, name, parameterTypes),
                            null,
                            false,
                            IndexedMember.Provenance.EXTERNAL_BINARY);
            var key = memberKey(member);
            var existing = seenMembers.get(key);
            if (existing == null || member.priority < existing.priority) {
                seenMembers.put(key, member);
            }
        }

        var members = new ArrayList<>(seenMembers.values());
        IndexedMember.sort(members);
        return new BinaryClassModel(qualifiedName, simpleName, members);
    }

    private int memberPriority(String targetType, String declaringType) {
        if (Objects.equals(targetType, declaringType)) {
            return 0;
        }
        if (Objects.equals("java.lang.Object", declaringType)) {
            return 2;
        }
        return 1;
    }

    private List<Field> visibleFields(Class<?> binaryClass) {
        var ordered = new LinkedHashMap<String, Field>();
        collectVisibleFields(binaryClass, ordered, new HashSet<>());
        return new ArrayList<>(ordered.values());
    }

    private void collectVisibleFields(Class<?> type, LinkedHashMap<String, Field> ordered, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        for (var field : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isPublic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            ordered.putIfAbsent(field.getName(), field);
        }
        collectVisibleFields(type.getSuperclass(), ordered, visited);
        for (var iface : type.getInterfaces()) {
            collectVisibleFields(iface, ordered, visited);
        }
    }

    private List<Method> visibleMethods(Class<?> binaryClass) {
        var ordered = new LinkedHashMap<String, Method>();
        collectVisibleMethods(binaryClass, ordered, new HashSet<>());
        return new ArrayList<>(ordered.values());
    }

    private void collectVisibleMethods(Class<?> type, LinkedHashMap<String, Method> ordered, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        for (var method : type.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    || method.isSynthetic()
                    || method.isBridge()) {
                continue;
            }
            var key = method.getName() + Arrays.toString(method.getParameterTypes());
            ordered.putIfAbsent(key, method);
        }
        collectVisibleMethods(type.getSuperclass(), ordered, visited);
        for (var iface : type.getInterfaces()) {
            collectVisibleMethods(iface, ordered, visited);
        }
    }

    private String memberKey(IndexedMember member) {
        return member.canonicalKey;
    }

    private IndexedMember ensureExternalProvenance(IndexedMember member) {
        if (member == null || member.provenance == IndexedMember.Provenance.EXTERNAL_BINARY) {
            return member;
        }
        return new IndexedMember(
                member.ownerType,
                member.name,
                member.kind,
                member.isStatic,
                member.isPrivate,
                member.isProtected,
                member.isPublic,
                member.isAbstract,
                member.priority,
                member.detail,
                member.returnType,
                member.declaredReturnType,
                member.parameterNames,
                member.erasedParameterTypes,
                member.declaredParameterTypes,
                member.canonicalKey,
                member.logicalKey,
                member.backingFieldName,
                member.synthetic,
                member.origin,
                IndexedMember.Provenance.EXTERNAL_BINARY,
                member.modifiers,
                member.sourceUri,
                member.declarationRange);
    }

    private IndexedType applySourceLinks(IndexedType raw) {
        var linkedMembers = applySourceFieldLinks(raw.qualifiedName, raw.members);
        if (linkedMembers == raw.members) {
            return raw;
        }
        return new IndexedType(
                raw.qualifiedName,
                raw.simpleName,
                linkedMembers,
                raw.fromCompiledRoot,
                raw.sourcePath,
                raw.superclass,
                raw.interfaces,
                raw.provenance);
    }

    private List<IndexedMember> applySourceFieldLinks(
            String qualifiedName, List<IndexedMember> members) {
        var sourceMetadata = sourceLombokMetadata(qualifiedName, members);
        if (sourceMetadata.isEmpty()) {
            return members;
        }
        var linked =
                new ArrayList<IndexedMember>(
                        members.size() + sourceMetadata.get().syntheticAccessors().size());
        for (var member : members) {
            var fieldName = sourceMetadata.get().accessorToField().get(member.canonicalKey);
            if (fieldName == null) {
                linked.add(member);
                continue;
            }
            var fieldKey =
                    IndexedMember.canonicalKey(
                            member.ownerType, CompletionItemKind.Field, fieldName, null);
            linked.add(
                    new IndexedMember(
                            member.ownerType,
                            member.name,
                            member.kind,
                            member.isStatic,
                            member.isPrivate,
                            member.priority,
                            member.detail,
                            member.returnType,
                            member.parameterNames,
                            member.erasedParameterTypes,
                            member.canonicalKey,
                            fieldKey,
                            fieldName,
                            member.synthetic,
                            member.provenance));
        }
        linked.addAll(sourceMetadata.get().syntheticAccessors());
        IndexedMember.sort(linked);
        return Collections.unmodifiableList(linked);
    }

    private Optional<SourceLombokMetadata> sourceLombokMetadata(
            String qualifiedName, List<IndexedMember> members) {
        if (compiler == null) {
            return Optional.empty();
        }
        var source = compiler.findAnywhere(qualifiedName);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        try {
            var parse = compiler.parse(source.get());
            var declaration = FindHelper.findType(parse, qualifiedName);
            if (declaration == null) {
                return Optional.empty();
            }
            if (!LombokAnnotations.hasAccessorLombokAnnotation(declaration.getModifiers())) {
                return Optional.empty();
            }
            var accessorToField = new LinkedHashMap<String, String>();
            var syntheticAccessors = new ArrayList<IndexedMember>();
            for (var member : declaration.getMembers()) {
                if (!(member instanceof VariableTree field)) {
                    continue;
                }
                if (field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)) {
                    continue;
                }
                var fieldName = field.getName().toString();
                if (fieldName.isBlank()) {
                    continue;
                }
                var fieldType = field.getType() == null ? "" : field.getType().toString();
                var accessors =
                        LombokAnnotations.accessorInfo(
                                declaration.getModifiers(), field.getModifiers(), fieldName, fieldType);
                if (accessors.isEmpty()) {
                    continue;
                }
                var accessorInfo = accessors.get();
                if (accessorInfo.hasGetter()) {
                    var getterKey =
                            IndexedMember.canonicalKey(
                                    qualifiedName,
                                    CompletionItemKind.Method,
                                    accessorInfo.getterName(),
                                    new String[0]);
                    accessorToField.put(getterKey, fieldName);
                    if (members.stream().noneMatch(candidate -> Objects.equals(candidate.canonicalKey, getterKey))) {
                        var normalizedFieldType = TypeNames.normalize(accessorInfo.fieldType());
                        syntheticAccessors.add(
                                new IndexedMember(
                                        qualifiedName,
                                        accessorInfo.getterName(),
                                        CompletionItemKind.Method,
                                        false,
                                        false,
                                        false,
                                        true,
                                        false,
                                        0,
                                        normalizedFieldType + " " + accessorInfo.getterName() + "()",
                                        normalizedFieldType,
                                        normalizedFieldType,
                                        new String[0],
                                        new String[0],
                                        new String[0],
                                        getterKey,
                                        IndexedMember.canonicalKey(
                                                qualifiedName,
                                                CompletionItemKind.Field,
                                                fieldName,
                                                null),
                                        fieldName,
                                        true,
                                        IndexedMember.Origin.LOMBOK_ACCESSOR,
                                        IndexedMember.Provenance.EXTERNAL_BINARY,
                                        Set.of(javax.lang.model.element.Modifier.PUBLIC),
                                        null,
                                        null));
                    }
                }
                if (accessorInfo.hasSetter()) {
                    var setterName = accessorInfo.setterName();
                    var normalizedFieldType = TypeNames.normalize(accessorInfo.fieldType());
                    var setterKey =
                            IndexedMember.canonicalKey(
                                    qualifiedName,
                                    CompletionItemKind.Method,
                                    setterName,
                                    new String[] {normalizedFieldType});
                    accessorToField.put(setterKey, fieldName);
                    for (var candidate : members) {
                        if (candidate.kind != CompletionItemKind.Method || !Objects.equals(candidate.name, setterName)) {
                            continue;
                        }
                        accessorToField.put(candidate.canonicalKey, fieldName);
                    }
                    if (members.stream().noneMatch(candidate -> Objects.equals(candidate.canonicalKey, setterKey))) {
                        syntheticAccessors.add(
                                new IndexedMember(
                                        qualifiedName,
                                        setterName,
                                        CompletionItemKind.Method,
                                        false,
                                        false,
                                        false,
                                        true,
                                        false,
                                        0,
                                        "void " + setterName + "(" + normalizedFieldType + " " + fieldName + ")",
                                        "void",
                                        "void",
                                        new String[] {fieldName},
                                        new String[] {normalizedFieldType},
                                        new String[] {normalizedFieldType},
                                        setterKey,
                                        IndexedMember.canonicalKey(
                                                qualifiedName,
                                                CompletionItemKind.Field,
                                                fieldName,
                                                null),
                                        fieldName,
                                        true,
                                        IndexedMember.Origin.LOMBOK_ACCESSOR,
                                        IndexedMember.Provenance.EXTERNAL_BINARY,
                                        Set.of(javax.lang.model.element.Modifier.PUBLIC),
                                        null,
                                        null));
                    }
                }
            }
            if (accessorToField.isEmpty() && syntheticAccessors.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(
                    new SourceLombokMetadata(
                            accessorToField, List.copyOf(syntheticAccessors)));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String canonicalTypeName(Class<?> type) {
        if (type.isArray()) {
            return canonicalTypeName(type.getComponentType()) + "[]";
        }
        return type.getTypeName().replace('$', '.');
    }

    private static String fingerprint(Set<Path> classPath) {
        return Integer.toHexString(
                classPath.stream()
                        .map(path -> path.toAbsolutePath().normalize().toString())
                        .sorted()
                        .collect(Collectors.joining("|"))
                        .hashCode());
    }

    private static ClassLoader buildClassLoader(Set<Path> classPath) {
        var urls =
                classPath.stream()
                        .map(Path::toUri)
                        .map(
                                uri -> {
                                    try {
                                        return uri.toURL();
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                        .toArray(URL[]::new);
        return new URLClassLoader(urls, ExternalBinaryTypeIndex.class.getClassLoader());
    }

    private String[] parseBinaryParameterTypes(MethodTypeDesc methodType) {
        if (methodType == null || methodType.parameterCount() == 0) {
            return new String[0];
        }
        var normalized = new String[methodType.parameterCount()];
        for (int i = 0; i < methodType.parameterCount(); i++) {
            normalized[i] = normalizeBinaryType(methodType.parameterType(i));
        }
        return normalized;
    }

    private String renderParameters(String[] parameterTypes, String[] parameterNames) {
        var parameters = new StringJoiner(", ");
        for (int i = 0; i < parameterTypes.length; i++) {
            parameters.add(parameterTypes[i] + " " + parameterNames[i]);
        }
        return parameters.toString();
    }

    private String[] syntheticParameterNames(int count) {
        var names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = "arg" + i;
        }
        return names;
    }

       private String normalizeBinaryType(ClassDesc typeDesc) {
        if (typeDesc == null) {
            return "java.lang.Object";
        }
        return normalizeBinaryDescriptor(typeDesc.descriptorString());
    }

    private String normalizeBinaryDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return "java.lang.Object";
        }
        int dimensions = 0;
        while (dimensions < descriptor.length() && descriptor.charAt(dimensions) == '[') {
            dimensions++;
        }
        var baseDescriptor = descriptor.substring(dimensions);
        String baseType;
        switch (baseDescriptor) {
            case "B":
                baseType = "byte";
                break;
            case "C":
                baseType = "char";
                break;
            case "D":
                baseType = "double";
                break;
            case "F":
                baseType = "float";
                break;
            case "I":
                baseType = "int";
                break;
            case "J":
                baseType = "long";
                break;
            case "S":
                baseType = "short";
                break;
            case "Z":
                baseType = "boolean";
                break;
            case "V":
                baseType = "void";
                break;
            default:
                if (baseDescriptor.startsWith("L") && baseDescriptor.endsWith(";")) {
                    baseType =
                            baseDescriptor
                                    .substring(1, baseDescriptor.length() - 1)
                                    .replace('/', '.')
                                    .replace('$', '.');
                } else {
                    baseType = baseDescriptor.replace('/', '.').replace('$', '.');
                }
                break;
        }
        return baseType + "[]".repeat(dimensions);
    }

    private record BinaryClassModel(
            String qualifiedName, String simpleName, List<IndexedMember> members) {}
    private record SourceLombokMetadata(
            java.util.Map<String, String> accessorToField,
            List<IndexedMember> syntheticAccessors) {}
}
