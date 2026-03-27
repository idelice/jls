package org.javacs.completion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.VariableTree;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ConstantPoolException;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
import org.javacs.lsp.CompletionItemKind;

/**
 * Dependency-only type metadata backed by the classpath.
 *
 * <p>This index serves jars, JDK classes, and decompiled dependency sources. Workspace-owned
 * candidates must be resolved before reaching this class. Any workspace hit here is a correctness
 * bug. Vineflower support here is only for read-only dependency inspection and navigation.
 */
public final class ExternalBinaryTypeIndex {
    public static final ExternalBinaryTypeIndex EMPTY = new ExternalBinaryTypeIndex();

    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final String classPathFingerprint;
    private final Set<Path> classPathRoots;
    private final ClassLoader classLoader;
    private final Cache<String, Optional<TypeMemberIndex.TypeInfo>> rawTypeCache;
    private final Cache<String, Optional<TypeMemberIndex.TypeInfo>> typeCache;
    private final Cache<String, Optional<Path>> decompiledSourceCache;
    private final Cache<String, Optional<BinaryClassModel>> classFileCache;
    private final ExternalBinaryDecompiler decompiler;

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

    public Optional<TypeMemberIndex.TypeInfo> typeInfo(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return Optional.empty();
        }
        return lookup(typeCache, qualifiedName, this::loadLinkedTypeInfo, "external_binary.type");
    }

    public boolean containsType(String qualifiedName) {
        return rawTypeInfo(qualifiedName).isPresent();
    }

    public List<TypeMemberIndex.Member> members(String qualifiedName, boolean staticContext) {
        var type = rawTypeInfo(qualifiedName);
        if (type.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<TypeMemberIndex.Member>();
        for (var member : type.get().members) {
            if (member.isStatic == staticContext) {
                result.add(member);
            }
        }
        return result;
    }

    public Optional<TypeMemberIndex.Member> member(String qualifiedName, String name, boolean staticContext) {
        for (var member : linkedMembers(qualifiedName, staticContext)) {
            if (Objects.equals(name, member.name)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public Optional<TypeMemberIndex.Member> member(
            String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var targetKey =
                TypeMemberIndex.canonicalMemberKey(
                        qualifiedName, CompletionItemKind.Method, name, erasedParameterTypes);
        for (var member : linkedMembers(qualifiedName, staticContext)) {
            if (Objects.equals(targetKey, member.canonicalKey)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    Optional<TypeMemberIndex.Member> rawMember(String qualifiedName, String name, boolean staticContext) {
        for (var member : members(qualifiedName, staticContext)) {
            if (Objects.equals(name, member.name)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    Optional<TypeMemberIndex.Member> rawMember(
            String qualifiedName, String name, boolean staticContext, String[] erasedParameterTypes) {
        var targetKey =
                TypeMemberIndex.canonicalMemberKey(
                        qualifiedName, CompletionItemKind.Method, name, erasedParameterTypes);
        for (var member : members(qualifiedName, staticContext)) {
            if (Objects.equals(targetKey, member.canonicalKey)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    private List<TypeMemberIndex.Member> linkedMembers(String qualifiedName, boolean staticContext) {
        return typeInfo(qualifiedName)
                .map(
                        info ->
                                info.members.stream()
                                        .filter(member -> member.isStatic == staticContext)
                                        .toList())
                .orElse(List.of());
    }

    public Optional<String> resolveTypeName(String typeName, CompilationUnitTree root) {
        if (typeName == null || typeName.isBlank() || root == null || compiler == null) {
            return Optional.empty();
        }
        var raw = normalizeTypeName(typeName);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (TypeMemberIndex.isPrimitiveTypeName(raw)) {
            return Optional.of(raw);
        }
        if (raw.contains(".") && containsType(raw)) {
            return Optional.of(raw);
        }

        var candidates = new java.util.LinkedHashSet<String>();

        for (var importTree : root.getImports()) {
            if (importTree.isStatic()) {
                continue;
            }
            var imported = importTree.getQualifiedIdentifier().toString();
            if (imported.endsWith("." + raw) && containsType(imported)) {
                candidates.add(imported);
            }
            if (imported.endsWith(".*")) {
                var candidate = imported.substring(0, imported.length() - 1) + raw;
                if (containsType(candidate)) {
                    candidates.add(candidate);
                }
            }
        }

        var javaLang = "java.lang." + raw;
        if (containsType(javaLang)) {
            candidates.add(javaLang);
        }
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!packageName.isBlank()) {
            var samePackage = packageName + "." + raw;
            if (containsType(samePackage)) {
                candidates.add(samePackage);
            }
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.getFirst());
        }
        return Optional.empty();
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

    private Optional<TypeMemberIndex.TypeInfo> rawTypeInfo(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return Optional.empty();
        }
        return lookup(rawTypeCache, qualifiedName, this::loadRawTypeInfo, "external_binary.type_raw");
    }

    private Optional<TypeMemberIndex.TypeInfo> loadLinkedTypeInfo(String qualifiedName) {
        var raw = rawTypeInfo(qualifiedName);
        return raw.map(this::applySourceLinks);
    }

    private Optional<TypeMemberIndex.TypeInfo> loadRawTypeInfo(String qualifiedName) {
        if (isWorkspaceOwnedCandidate(qualifiedName)) {
            LOG.fine(
                    String.format(
                            "[workspace-boundary] external_leak candidate=%s reason=externalTypeInfo caller=%s",
                            qualifiedName,
                            callerSummary()));
            return Optional.empty();
        }
        try {
            var binaryClass = Class.forName(qualifiedName, false, classLoader);
            if (binaryClass.isArray()) {
                return Optional.empty();
            }
            var publicFields = binaryClass.getFields();
            var seen = new LinkedHashMap<String, TypeMemberIndex.Member>();
            for (var field : publicFields) {
                try {
                    if (field.isSynthetic()) {
                        continue;
                    }
                    var declaring = field.getDeclaringClass().getName();
                    var priority = memberPriority(qualifiedName, declaring);
                    var member =
                            new TypeMemberIndex.Member(
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
                                    TypeMemberIndex.canonicalMemberKey(
                                            declaring, CompletionItemKind.Field, field.getName(), null),
                                    TypeMemberIndex.canonicalMemberKey(
                                            declaring, CompletionItemKind.Field, field.getName(), null),
                                    null,
                                    false);
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
            var publicMethods = binaryClass.getMethods();
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
                            new TypeMemberIndex.Member(
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
                                    TypeMemberIndex.canonicalMemberKey(
                                            declaring, CompletionItemKind.Method, method.getName(), erasedParameterTypes),
                                    TypeMemberIndex.canonicalMemberKey(
                                            declaring, CompletionItemKind.Method, method.getName(), erasedParameterTypes),
                                    null,
                                    false);
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
            members.sort(
                    java.util.Comparator.comparingInt((TypeMemberIndex.Member member) -> member.priority)
                            .thenComparing(member -> member.name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(member -> member.detail));
            var superclass =
                    binaryClass.getSuperclass() == null ? null : canonicalTypeName(binaryClass.getSuperclass());
            var interfaces =
                    java.util.Arrays.stream(binaryClass.getInterfaces())
                            .map(this::canonicalTypeName)
                            .collect(Collectors.toList());
            var linked = new TypeMemberIndex.TypeInfo(qualifiedName, binaryClass.getSimpleName(), members, false, null, superclass, interfaces);
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
                        new TypeMemberIndex.TypeInfo(
                                fallback.get().qualifiedName(),
                                fallback.get().simpleName(),
                                fallback.get().members(),
                                false,
                                null,
                                null,
                                List.of()));
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

    /**
     * Workspace-owned candidates must not be treated as external dependencies, including nested
     * names under workspace owners.
     */
    private boolean isWorkspaceOwnedCandidate(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return false;
        }
        if (compiler.findTypeDeclaration(qualifiedName) != org.javacs.CompilerProvider.NOT_FOUND) {
            return true;
        }
        for (var i = qualifiedName.lastIndexOf('.'); i > 0; i = qualifiedName.lastIndexOf('.', i - 1)) {
            var outer = qualifiedName.substring(0, i);
            if (compiler.findTypeDeclaration(outer) != org.javacs.CompilerProvider.NOT_FOUND) {
                return true;
            }
        }
        return false;
    }

    private String callerSummary() {
        var join = new StringJoiner(" > ");
        for (var frame : Thread.currentThread().getStackTrace()) {
            var className = frame.getClassName();
            if (className == null
                    || !className.startsWith("org.javacs.")
                    || className.equals(ExternalBinaryTypeIndex.class.getName())) {
                continue;
            }
            join.add(className + "#" + frame.getMethodName() + ":" + frame.getLineNumber());
            if (join.length() > 120) {
                break;
            }
        }
        return join.length() == 0 ? "<unknown>" : join.toString();
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
        var simpleName = simpleName(qualifiedName);
        var seenMembers = new LinkedHashMap<String, TypeMemberIndex.Member>();

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
                    new TypeMemberIndex.Member(
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
                            TypeMemberIndex.canonicalMemberKey(qualifiedName, CompletionItemKind.Field, name, null),
                            TypeMemberIndex.canonicalMemberKey(qualifiedName, CompletionItemKind.Field, name, null),
                            null,
                            false);
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
                    new TypeMemberIndex.Member(
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
                            TypeMemberIndex.canonicalMemberKey(qualifiedName, CompletionItemKind.Method, name, parameterTypes),
                            TypeMemberIndex.canonicalMemberKey(qualifiedName, CompletionItemKind.Method, name, parameterTypes),
                            null,
                            false);
            var key = memberKey(member);
            var existing = seenMembers.get(key);
            if (existing == null || member.priority < existing.priority) {
                seenMembers.put(key, member);
            }
        }

        var members = new ArrayList<>(seenMembers.values());
        members.sort(
                java.util.Comparator.comparingInt((TypeMemberIndex.Member member) -> member.priority)
                        .thenComparing(member -> member.name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(member -> member.detail));
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

    private String memberKey(TypeMemberIndex.Member member) {
        return member.canonicalKey;
    }

    private TypeMemberIndex.TypeInfo applySourceLinks(TypeMemberIndex.TypeInfo raw) {
        var linkedMembers = applySourceFieldLinks(raw.qualifiedName, raw.members);
        if (linkedMembers == raw.members) {
            return raw;
        }
        return new TypeMemberIndex.TypeInfo(
                raw.qualifiedName,
                raw.simpleName,
                linkedMembers,
                raw.fromCompiledRoot,
                raw.sourcePath,
                raw.superclass,
                raw.interfaces);
    }

    private List<TypeMemberIndex.Member> applySourceFieldLinks(String qualifiedName, List<TypeMemberIndex.Member> members) {
        var sourceMetadata = sourceLombokMetadata(qualifiedName, members);
        if (sourceMetadata.isEmpty()) {
            return members;
        }
        var linked = new ArrayList<TypeMemberIndex.Member>(members.size());
        for (var member : members) {
            var fieldName = sourceMetadata.get().accessorToField.get(member.canonicalKey);
            if (fieldName == null) {
                linked.add(member);
                continue;
            }
            var fieldKey =
                    TypeMemberIndex.canonicalMemberKey(
                            member.ownerType, CompletionItemKind.Field, fieldName, null);
            linked.add(
                    new TypeMemberIndex.Member(
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
                            member.synthetic));
        }
        return Collections.unmodifiableList(linked);
    }

    private Optional<SourceLombokMetadata> sourceLombokMetadata(
            String qualifiedName, List<TypeMemberIndex.Member> members) {
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
            var accessorToField = new LinkedHashMap<String, String>();
            var classGetter = hasLombokAnnotation(declaration.getModifiers(), "Data", "Getter", "Value");
            var classSetter = hasLombokAnnotation(declaration.getModifiers(), "Data", "Setter");
            if (!(classGetter || classSetter || hasAnyAccessorAnnotation(declaration.getModifiers()))) {
                return Optional.empty();
            }
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
                var suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                var fieldType = field.getType() == null ? "" : field.getType().toString();
                var booleanField = "boolean".equals(fieldType) || "Boolean".equals(fieldType) || "java.lang.Boolean".equals(fieldType);
                var getterEnabled = classGetter || hasLombokAnnotation(field.getModifiers(), "Getter");
                var setterEnabled = classSetter || hasLombokAnnotation(field.getModifiers(), "Setter");
                if (getterEnabled) {
                    var getterName = (booleanField ? "is" : "get") + suffix;
                    accessorToField.put(
                            TypeMemberIndex.canonicalMemberKey(
                                    qualifiedName, CompletionItemKind.Method, getterName, new String[0]),
                            fieldName);
                }
                if (setterEnabled) {
                    var setterName = "set" + suffix;
                    for (var candidate : members) {
                        if (candidate.kind != CompletionItemKind.Method || !Objects.equals(candidate.name, setterName)) {
                            continue;
                        }
                        accessorToField.put(candidate.canonicalKey, fieldName);
                    }
                }
            }
            return accessorToField.isEmpty() ? Optional.empty() : Optional.of(new SourceLombokMetadata(accessorToField));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private boolean hasAnyAccessorAnnotation(ModifiersTree modifiers) {
        return hasLombokAnnotation(modifiers, "Data", "Getter", "Setter", "Value");
    }

    private boolean hasLombokAnnotation(ModifiersTree modifiers, String... simpleNames) {
        var allowed = Set.of(simpleNames);
        for (var annotation : modifiers.getAnnotations()) {
            var annotationType = annotation.getAnnotationType().toString();
            if (!LombokAnnotations.isLombokAnnotationType(annotationType)) {
                continue;
            }
            if (allowed.contains(LombokAnnotations.simpleName(annotationType))) {
                return true;
            }
        }
        return false;
    }

    private String canonicalTypeName(Class<?> type) {
        if (type.isArray()) {
            return canonicalTypeName(type.getComponentType()) + "[]";
        }
        return type.getTypeName().replace('$', '.');
    }

    private static String normalizeTypeName(String typeName) {
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
            raw = "";
        }
        return raw;
    }

    private static String simpleName(String qualifiedName) {
        var index = qualifiedName.lastIndexOf('.');
        if (index < 0) {
            return qualifiedName;
        }
        return qualifiedName.substring(index + 1);
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

    private record BinaryClassModel(String qualifiedName, String simpleName, List<TypeMemberIndex.Member> members) {}
    private record SourceLombokMetadata(java.util.Map<String, String> accessorToField) {}
}
