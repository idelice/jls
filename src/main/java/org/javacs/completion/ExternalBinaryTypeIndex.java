package org.javacs.completion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.source.tree.CompilationUnitTree;
import java.io.IOException;
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
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.javacs.CompilerProvider;
import org.javacs.lsp.CompletionItemKind;

public final class ExternalBinaryTypeIndex {
    public static final ExternalBinaryTypeIndex EMPTY = new ExternalBinaryTypeIndex();

    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final String classPathFingerprint;
    private final ClassLoader classLoader;
    private final Cache<String, Optional<TypeMemberIndex.TypeInfo>> typeCache;
    private final Cache<String, Optional<Path>> stubCache;

    private ExternalBinaryTypeIndex() {
        this.compiler = null;
        this.classPathFingerprint = "";
        this.classLoader = ExternalBinaryTypeIndex.class.getClassLoader();
        this.typeCache = Caffeine.newBuilder().maximumSize(1).build();
        this.stubCache = Caffeine.newBuilder().maximumSize(1).build();
    }

    public ExternalBinaryTypeIndex(CompilerProvider compiler) {
        this.compiler = compiler;
        var classPath = compiler == null ? Set.<Path>of() : compiler.classPathRoots();
        this.classPathFingerprint = fingerprint(classPath);
        this.classLoader = buildClassLoader(classPath);
        this.typeCache =
                Caffeine.newBuilder()
                        .maximumSize(20_000)
                        .expireAfterAccess(Duration.ofMinutes(30))
                        .build();
        this.stubCache =
                Caffeine.newBuilder()
                        .maximumSize(5_000)
                        .expireAfterAccess(Duration.ofMinutes(30))
                        .build();
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

    public Optional<Path> stubSourcePath(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank() || compiler == null) {
            return Optional.empty();
        }
        return stubCache.get(qualifiedName, this::buildStubSourcePath);
    }

    private Optional<TypeMemberIndex.TypeInfo> loadTypeInfo(String qualifiedName) {
        try {
            var binaryClass = Class.forName(qualifiedName, false, classLoader);
            if (binaryClass.isArray()) {
                return Optional.empty();
            }
            var seen = new LinkedHashMap<String, TypeMemberIndex.Member>();
            for (var field : binaryClass.getFields()) {
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
            }
            for (var method : binaryClass.getMethods()) {
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
            LOG.fine(String.format("[external-binary] miss type=%s reason=%s", qualifiedName, ex.getClass().getSimpleName()));
            return Optional.empty();
        }
    }

    private Optional<Path> buildStubSourcePath(String qualifiedName) {
        var type = typeInfo(qualifiedName);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        try {
            var binaryClass = Class.forName(qualifiedName, false, classLoader);
            var base =
                    Path.of(System.getProperty("java.io.tmpdir"))
                            .resolve("jls-binary-stubs")
                            .resolve(classPathFingerprint);
            var packageName = packageName(qualifiedName);
            var relative = packageName.isBlank() ? Path.of("") : Path.of(packageName.replace('.', '/'));
            var dir = base.resolve(relative);
            Files.createDirectories(dir);
            var file = dir.resolve(binaryClass.getSimpleName() + ".java");
            Files.writeString(file, renderStubSource(binaryClass));
            return Optional.of(file);
        } catch (IOException | ClassNotFoundException | LinkageError ex) {
            LOG.fine(String.format("[external-binary] stub miss type=%s reason=%s", qualifiedName, ex.getClass().getSimpleName()));
            return Optional.empty();
        }
    }

    private String renderStubSource(Class<?> binaryClass) {
        var out = new StringBuilder();
        if (binaryClass.getPackageName() != null && !binaryClass.getPackageName().isBlank()) {
            out.append("package ").append(binaryClass.getPackageName()).append(";\n\n");
        }
        out.append(renderTypeHeader(binaryClass)).append(" {\n");
        for (var field : binaryClass.getFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            out.append("    ")
                    .append(renderField(field))
                    .append("\n");
        }
        var constructors = visibleConstructors(binaryClass);
        if (binaryClass.getFields().length > 0 && !constructors.isEmpty()) {
            out.append("\n");
        }
        for (var constructor : constructors) {
            out.append(renderConstructor(binaryClass, constructor));
        }
        if ((!constructors.isEmpty() || binaryClass.getFields().length > 0) && binaryClass.getMethods().length > 0) {
            out.append("\n");
        }
        for (var method : binaryClass.getMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            out.append(renderMethod(binaryClass, method));
        }
        out.append("}\n");
        return out.toString();
    }

    private String renderTypeHeader(Class<?> binaryClass) {
        var modifiers = sanitizeTypeModifiers(binaryClass.getModifiers(), binaryClass);
        var kind =
                binaryClass.isAnnotation()
                        ? "@interface"
                        : binaryClass.isInterface()
                                ? "interface"
                                : binaryClass.isEnum() ? "enum" : "class";
        var header = new StringBuilder();
        if (!modifiers.isBlank()) {
            header.append(modifiers).append(" ");
        }
        header.append(kind).append(" ").append(binaryClass.getSimpleName());
        return header.toString();
    }

    private String renderField(java.lang.reflect.Field field) {
        var modifiers = java.lang.reflect.Modifier.toString(field.getModifiers());
        var assignment = defaultValueExpression(field.getType());
        var out = new StringBuilder();
        if (!modifiers.isBlank()) {
            out.append(modifiers).append(" ");
        }
        out.append(canonicalTypeName(field.getType())).append(" ").append(field.getName());
        if (assignment != null) {
            out.append(" = ").append(assignment);
        }
        out.append(";");
        return out.toString();
    }

    private String renderConstructor(Class<?> owner, java.lang.reflect.Constructor<?> constructor) {
        var out = new StringBuilder("    ");
        var modifiers = sanitizeConstructorModifiers(constructor.getModifiers(), owner);
        if (!modifiers.isBlank()) {
            out.append(modifiers).append(" ");
        }
        out.append(owner.getSimpleName()).append("(");
        var parameters = new StringJoiner(", ");
        for (int i = 0; i < constructor.getParameterCount(); i++) {
            parameters.add(
                    canonicalTypeName(constructor.getParameterTypes()[i])
                            + " "
                            + constructor.getParameters()[i].getName());
        }
        out.append(parameters).append(")");
        if (constructor.getExceptionTypes().length > 0) {
            var thrown = new StringJoiner(", ");
            for (var exceptionType : constructor.getExceptionTypes()) {
                thrown.add(canonicalTypeName(exceptionType));
            }
            out.append(" throws ").append(thrown);
        }
        out.append(" {\n");
        out.append("    }\n");
        return out.toString();
    }

    private String renderMethod(Class<?> owner, java.lang.reflect.Method method) {
        var out = new StringBuilder("    ");
        var modifiers = sanitizeMethodModifiers(method.getModifiers(), owner);
        if (!modifiers.isBlank()) {
            out.append(modifiers).append(" ");
        }
        out.append(canonicalTypeName(method.getReturnType()))
                .append(" ")
                .append(method.getName())
                .append("(");
        var parameters = new StringJoiner(", ");
        for (int i = 0; i < method.getParameterCount(); i++) {
            parameters.add(canonicalTypeName(method.getParameterTypes()[i]) + " " + method.getParameters()[i].getName());
        }
        out.append(parameters).append(")");
        if (method.getExceptionTypes().length > 0) {
            var thrown = new StringJoiner(", ");
            for (var exceptionType : method.getExceptionTypes()) {
                thrown.add(canonicalTypeName(exceptionType));
            }
            out.append(" throws ").append(thrown);
        }
        if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                || (owner.isInterface() && !java.lang.reflect.Modifier.isStatic(method.getModifiers()))) {
            out.append(";\n");
            return out.toString();
        }
        out.append(" {\n");
        var returnExpression = defaultReturnStatement(method.getReturnType());
        if (!returnExpression.isBlank()) {
            out.append("        ").append(returnExpression).append("\n");
        }
        out.append("    }\n");
        return out.toString();
    }

    private String sanitizeTypeModifiers(int modifiers, Class<?> binaryClass) {
        var parts = new ArrayList<String>();
        if (java.lang.reflect.Modifier.isPublic(modifiers)) parts.add("public");
        if (java.lang.reflect.Modifier.isProtected(modifiers)) parts.add("protected");
        if (java.lang.reflect.Modifier.isAbstract(modifiers) && !binaryClass.isInterface()) parts.add("abstract");
        if (java.lang.reflect.Modifier.isFinal(modifiers) && !binaryClass.isEnum()) parts.add("final");
        return String.join(" ", parts);
    }

    private String sanitizeConstructorModifiers(int modifiers, Class<?> owner) {
        var parts = new ArrayList<String>();
        if (java.lang.reflect.Modifier.isPublic(modifiers)) parts.add("public");
        if (java.lang.reflect.Modifier.isProtected(modifiers)) parts.add("protected");
        if (owner.isEnum() && java.lang.reflect.Modifier.isPrivate(modifiers)) parts.add("private");
        return String.join(" ", parts);
    }

    private String sanitizeMethodModifiers(int modifiers, Class<?> owner) {
        var parts = new ArrayList<String>();
        if (java.lang.reflect.Modifier.isPublic(modifiers)) parts.add("public");
        if (java.lang.reflect.Modifier.isProtected(modifiers)) parts.add("protected");
        if (java.lang.reflect.Modifier.isStatic(modifiers)) parts.add("static");
        if (java.lang.reflect.Modifier.isFinal(modifiers) && !owner.isInterface()) parts.add("final");
        if (java.lang.reflect.Modifier.isAbstract(modifiers) && !owner.isInterface()) parts.add("abstract");
        return String.join(" ", parts);
    }

    private String defaultReturnStatement(Class<?> type) {
        if (Void.TYPE.equals(type)) {
            return "";
        }
        return "return " + defaultValueExpression(type) + ";";
    }

    private String defaultValueExpression(Class<?> type) {
        if (!type.isPrimitive()) {
            return "null";
        }
        if (Boolean.TYPE.equals(type)) return "false";
        if (Character.TYPE.equals(type)) return "'\\0'";
        if (Long.TYPE.equals(type)) return "0L";
        if (Float.TYPE.equals(type)) return "0f";
        if (Double.TYPE.equals(type)) return "0d";
        return "0";
    }

    private List<java.lang.reflect.Constructor<?>> visibleConstructors(Class<?> binaryClass) {
        if (binaryClass.isInterface() || binaryClass.isAnnotation()) {
            return List.of();
        }
        var result = new ArrayList<java.lang.reflect.Constructor<?>>();
        for (var constructor : binaryClass.getDeclaredConstructors()) {
            if (constructor.isSynthetic()) {
                continue;
            }
            var modifiers = constructor.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(modifiers)
                    && !java.lang.reflect.Modifier.isProtected(modifiers)
                    && !(binaryClass.isEnum() && java.lang.reflect.Modifier.isPrivate(modifiers))) {
                continue;
            }
            result.add(constructor);
        }
        result.sort(java.util.Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount));
        return result;
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

    private static String packageName(String qualifiedName) {
        var index = qualifiedName.lastIndexOf('.');
        if (index < 0) {
            return "";
        }
        return qualifiedName.substring(0, index);
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
}
