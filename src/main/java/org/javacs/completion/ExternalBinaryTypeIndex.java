package org.javacs.completion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.tools.classfile.AccessFlags;
import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Descriptor;
import com.sun.source.tree.CompilationUnitTree;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.javacs.CompilerProvider;
import org.javacs.lsp.CompletionItemKind;

public final class ExternalBinaryTypeIndex {
    public static final ExternalBinaryTypeIndex EMPTY = new ExternalBinaryTypeIndex();

    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final String classPathFingerprint;
    private final Set<Path> classPathRoots;
    private final ClassLoader classLoader;
    private final Cache<String, Optional<TypeMemberIndex.TypeInfo>> typeCache;
    private final Cache<String, Optional<Path>> decompiledSourceCache;
    private final Cache<String, Optional<BinaryClassModel>> classFileCache;
    private final ExternalBinaryDecompiler decompiler;

    private ExternalBinaryTypeIndex() {
        this.compiler = null;
        this.classPathFingerprint = "";
        this.classPathRoots = Set.of();
        this.classLoader = ExternalBinaryTypeIndex.class.getClassLoader();
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

    public Optional<TypeMemberIndex.TypeInfo> typeInfo(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return Optional.empty();
        }
        return typeCache.get(qualifiedName, this::loadTypeInfo);
    }

    public boolean containsType(String qualifiedName) {
        return typeInfo(qualifiedName).isPresent();
    }

    public List<TypeMemberIndex.Member> members(String qualifiedName, boolean staticContext) {
        var type = typeInfo(qualifiedName);
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
        for (var member : members(qualifiedName, staticContext)) {
            if (Objects.equals(name, member.name)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
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
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!packageName.isBlank()) {
            var samePackage = packageName + "." + raw;
            if (containsType(samePackage)) {
                candidates.add(samePackage);
            }
        }

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
        if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        }
        return Optional.empty();
    }

    public Optional<Path> decompiledSourcePath(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return Optional.empty();
        }
        return decompiledSourceCache.get(qualifiedName, decompiler::decompileSourcePath);
    }

    private Optional<TypeMemberIndex.TypeInfo> loadTypeInfo(String qualifiedName) {
        try {
            var binaryClass = Class.forName(qualifiedName, false, classLoader);
            if (binaryClass.isArray()) {
                return Optional.empty();
            }
            var seen = new LinkedHashMap<String, TypeMemberIndex.Member>();
            for (var field : binaryClass.getFields()) {
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
                                    null);
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
            for (var method : binaryClass.getMethods()) {
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
                                    erasedParameterTypes);
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
            return Optional.of(
                    new TypeMemberIndex.TypeInfo(
                            qualifiedName,
                            binaryClass.getSimpleName(),
                            members,
                            false,
                            null));
        } catch (ClassNotFoundException | LinkageError ex) {
            LOG.fine(
                    String.format(
                            "[external-binary] reflect miss type=%s reason=%s",
                            qualifiedName,
                            ex.getClass().getSimpleName()));
            var fallback = classFileCache.get(qualifiedName, this::loadBinaryClassModel);
            if (fallback.isPresent()) {
                LOG.fine(String.format("[external-binary] classfile fallback type=%s", qualifiedName));
                return Optional.of(fallback.get().typeInfo());
            }
            LOG.fine(String.format("[external-binary] miss type=%s reason=%s", qualifiedName, ex.getClass().getSimpleName()));
            return Optional.empty();
        }
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
            } catch (IOException | ConstantPoolException | Descriptor.InvalidDescriptor ex) {
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
            throws IOException, ConstantPoolException, Descriptor.InvalidDescriptor {
        var classFile = ClassFile.read(input);
        var simpleName = simpleName(qualifiedName);
        var seenMembers = new LinkedHashMap<String, TypeMemberIndex.Member>();

        for (var field : classFile.fields) {
            if (field.access_flags.is(AccessFlags.ACC_SYNTHETIC)) {
                continue;
            }
            var name = field.getName(classFile.constant_pool);
            if (name == null || name.isBlank()) {
                continue;
            }
            var type = normalizeBinaryType(field.descriptor.getFieldType(classFile.constant_pool));
            var staticMember = field.access_flags.is(AccessFlags.ACC_STATIC);
            var privateMember = field.access_flags.is(AccessFlags.ACC_PRIVATE);
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
                            null);
            seenMembers.putIfAbsent(memberKey(member), member);
        }

        for (var method : classFile.methods) {
            if (method.access_flags.is(AccessFlags.ACC_SYNTHETIC)
                    || method.access_flags.is(AccessFlags.ACC_BRIDGE)) {
                continue;
            }
            var name = method.getName(classFile.constant_pool);
            if (name == null || name.isBlank() || "<clinit>".equals(name)) {
                continue;
            }
            var parameterTypes = parseBinaryParameterTypes(method.descriptor, classFile);
            var parameterNames = syntheticParameterNames(parameterTypes.length);
            if ("<init>".equals(name)) {
                if (classFile.access_flags.is(AccessFlags.ACC_ENUM)) {
                    var normalized = stripEnumConstructorPrefix(parameterTypes, parameterNames);
                    parameterTypes = normalized.parameterTypes();
                    parameterNames = normalized.parameterNames();
                }
                continue;
            }
            var returnType = normalizeBinaryType(method.descriptor.getReturnType(classFile.constant_pool));
            var staticMember = method.access_flags.is(AccessFlags.ACC_STATIC);
            var privateMember = method.access_flags.is(AccessFlags.ACC_PRIVATE);
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
                            parameterTypes);
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
        return member.kind
                + ":"
                + member.name
                + ":"
                + String.join(",", member.erasedParameterTypes == null ? new String[0] : member.erasedParameterTypes);
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

    private String[] parseBinaryParameterTypes(Descriptor descriptor, ClassFile classFile)
            throws ConstantPoolException, Descriptor.InvalidDescriptor {
        var raw = descriptor.getParameterTypes(classFile.constant_pool).trim();
        if (raw.isEmpty() || "()".equals(raw)) {
            return new String[0];
        }
        if (raw.startsWith("(") && raw.endsWith(")")) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }
        if (raw.isEmpty()) {
            return new String[0];
        }
        var parts = raw.split(",\\s*");
        var normalized = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            normalized[i] = normalizeBinaryType(parts[i]);
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

    private ParameterList stripEnumConstructorPrefix(String[] parameterTypes, String[] parameterNames) {
        if (parameterTypes.length >= 2
                && "java.lang.String".equals(parameterTypes[0])
                && "int".equals(parameterTypes[1])) {
            return new ParameterList(
                    java.util.Arrays.copyOfRange(parameterTypes, 2, parameterTypes.length),
                    java.util.Arrays.copyOfRange(parameterNames, 2, parameterNames.length));
        }
        return new ParameterList(parameterTypes, parameterNames);
    }

    private String normalizeBinaryType(String typeName) {
        if (typeName == null) {
            return "java.lang.Object";
        }
        return typeName.replace('$', '.');
    }

    private record ParameterList(String[] parameterTypes, String[] parameterNames) {}

    private record BinaryClassModel(String qualifiedName, String simpleName, List<TypeMemberIndex.Member> members) {
        private TypeMemberIndex.TypeInfo typeInfo() {
            return new TypeMemberIndex.TypeInfo(qualifiedName, simpleName, members, false, null);
        }
    }
}
