package org.javacs;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.TreeScanner;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;

/**
 * Centralized Lombok annotation semantics used across compiler, indexing, completion, and
 * navigation code.
 *
 * <p>This class is the only place that should know which annotations count as Lombok, which ones
 * change the source model, and how generated accessor names should map back to source fields.
 */
public final class LombokAnnotations {
    private static final Map<String, String> ANNOTATION_PACKAGES = Map.ofEntries(
            Map.entry("Data", "lombok"),
            Map.entry("Getter", "lombok"),
            Map.entry("Setter", "lombok"),
            Map.entry("Builder", "lombok"),
            Map.entry("Value", "lombok"),
            Map.entry("AllArgsConstructor", "lombok"),
            Map.entry("NoArgsConstructor", "lombok"),
            Map.entry("RequiredArgsConstructor", "lombok"),
            Map.entry("NonNull", "lombok"),
            Map.entry("ToString", "lombok"),
            Map.entry("EqualsAndHashCode", "lombok"),
            Map.entry("With", "lombok"),
            Map.entry("Singular", "lombok"),
            Map.entry("CustomLog", "lombok"),
            Map.entry("SuperBuilder", "lombok.experimental"),
            Map.entry("NonFinal", "lombok.experimental"),
            Map.entry("Slf4j", "lombok.extern.slf4j"),
            Map.entry("XSlf4j", "lombok.extern.slf4j"),
            Map.entry("Log", "lombok.extern.java"),
            Map.entry("Log4j", "lombok.extern.log4j"),
            Map.entry("Log4j2", "lombok.extern.log4j"),
            Map.entry("CommonsLog", "lombok.extern.apachecommons"),
            Map.entry("Flogger", "lombok.extern.flogger"),
            Map.entry("JBossLog", "lombok.extern.jbosslog"));

    private static final Set<String> LOGGING_ONLY = ANNOTATION_PACKAGES.entrySet().stream()
            .filter(entry -> entry.getValue().startsWith("lombok.extern.")
                    || entry.getKey().equals("CustomLog"))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    static final String DEFAULT_LOG_FIELD_NAME = "log";
    public static final Set<String> KNOWN = ANNOTATION_PACKAGES.keySet().stream()
            .filter(name -> !name.equals("Singular")
                    && !name.equals("NonFinal")
                    && !name.equals("CustomLog"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static final Set<String> STRUCTURAL =
            Set.of(
                    "Data",
                    "Getter",
                    "Setter",
                    "Builder",
                    "Value",
                    "SuperBuilder",
                    "AllArgsConstructor",
                    "NoArgsConstructor",
                    "RequiredArgsConstructor",
                    "ToString",
                    "EqualsAndHashCode",
                    "With");

    private static final Pattern SOURCE_EXPANSION_PATTERN =
            Pattern.compile(
                    "@(?:lombok(?:\\.[A-Za-z_$][A-Za-z\\d_$]*)*\\.)?"
                            + "(Data|Getter|Setter|Builder|Value|SuperBuilder|RequiredArgsConstructor|AllArgsConstructor|NoArgsConstructor|EqualsAndHashCode|ToString|With|Slf4j|Log|Log4j|Log4j2|CommonsLog|Flogger|JBossLog|XSlf4j|CustomLog)\\b");

    private LombokAnnotations() {}

    /** Returns whether this source file currently contains a structural Lombok annotation. */
    public static boolean hasStructuralLombokAnnotation(CompilationUnitTree root) {
        var found = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitAnnotation(AnnotationTree annotation, Void unused) {
                if (isStructuralLombokAnnotationType(root, annotation)) {
                    found[0] = true;
                }
                return found[0] ? null : super.visitAnnotation(annotation, unused);
            }
        }.scan(root, null);
        return found[0];
    }

    public static boolean hasStructuralLombokAnnotation(
            CompilationUnitTree root, ModifiersTree modifiers) {
        return hasAnnotation(root, modifiers, STRUCTURAL);
    }

    public static boolean hasLoggingOnlyLombokAnnotation(
            CompilationUnitTree root, ModifiersTree modifiers) {
        return hasAnnotation(root, modifiers, LOGGING_ONLY);
    }

    /** Returns whether the supplied type name refers to a supported Lombok annotation. */
    public static boolean isLombokAnnotationType(String annotationType) {
        if (annotationType == null || annotationType.isBlank()) {
            return false;
        }
        var simpleName = simpleName(annotationType);
        var expectedPackage = ANNOTATION_PACKAGES.get(simpleName);
        if (expectedPackage == null) return false;
        return annotationType.indexOf('.') < 0
                || annotationType.equals(expectedPackage + "." + simpleName);
    }

    public static Optional<String> qualifiedAnnotationName(String annotationName) {
        var simpleName = simpleName(annotationName);
        var annotationPackage = ANNOTATION_PACKAGES.get(simpleName);
        return annotationPackage == null
                ? Optional.empty()
                : Optional.of(annotationPackage + "." + simpleName);
    }

    /** Returns whether the supplied type name refers to a Lombok annotation that changes members. */
    public static boolean isStructuralLombokAnnotationType(String annotationType) {
        return isLombokAnnotationType(annotationType) && STRUCTURAL.contains(simpleName(annotationType));
    }

    /** Reduces a qualified annotation type name such as {@code lombok.Data} to {@code Data}. */
    public static String simpleName(String annotationType) {
        if (annotationType == null || annotationType.isBlank()) {
            return annotationType;
        }
        var lastDot = annotationType.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < annotationType.length()) {
            return annotationType.substring(lastDot + 1);
        }
        return annotationType;
    }

    /** Performs the fast source scan used to decide whether Lombok source expansion may be needed. */
    public static boolean sourceMayRequireLombokExpansion(Path file, int lineLimit) {
        try (var reader = FileStore.lines(file)) {
            return sourceMayRequireLombokExpansion(reader, lineLimit);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean sourceMayRequireLombokExpansion(BufferedReader reader, int lineLimit)
            throws IOException {
        for (int i = 0; i < lineLimit; i++) {
            var line = reader.readLine();
            if (line == null) {
                return false;
            }
            if (SOURCE_EXPANSION_PATTERN.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAnnotation(
            CompilationUnitTree root, ModifiersTree modifiers, String... allowedSimpleNames) {
        return hasAnnotation(root, modifiers, Set.of(allowedSimpleNames));
    }

    /**
     * Resolves Lombok-generated accessor names for a field.
     *
     * <p>The caller supplies the class and field modifiers plus the field type/name. The result
     * indicates which accessor methods Lombok would synthesize for that field.
     */
    public static Optional<AccessorInfo> accessorInfo(
            ModifiersTree classModifiers, ModifiersTree fieldModifiers, String fieldName, String fieldType) {
        return accessorInfo(null, classModifiers, fieldModifiers, fieldName, fieldType);
    }

    public static Optional<AccessorInfo> accessorInfo(
            CompilationUnitTree root, ModifiersTree classModifiers, ModifiersTree fieldModifiers,
            String fieldName, String fieldType) {
        if (fieldName == null || fieldName.isBlank()) {
            return Optional.empty();
        }
        var getterEnabled = hasAnnotation(root, classModifiers, Set.of("Data", "Value"));
        var setterEnabled = hasAnnotation(root, classModifiers, Set.of("Data"));
        for (var annotation : classModifiers.getAnnotations()) {
            var name = simpleName(annotation.getAnnotationType().toString());
            if (!isLombokAnnotation(root, annotation)) continue;
            var none = annotation.getArguments().stream()
                    .map(Object::toString)
                    .map(value -> value.replace(" ", ""))
                    .anyMatch(value -> value.endsWith("AccessLevel.NONE")
                            || value.equals("NONE")
                            || value.equals("value=NONE"));
            if (name.equals("Getter")) getterEnabled = !none;
            if (name.equals("Setter")) setterEnabled = !none;
        }
        for (var annotation : fieldModifiers.getAnnotations()) {
            var name = simpleName(annotation.getAnnotationType().toString());
            if (!isLombokAnnotation(root, annotation)) continue;
            var none = annotation.getArguments().stream()
                    .map(Object::toString)
                    .map(value -> value.replace(" ", ""))
                    .anyMatch(value -> value.endsWith("AccessLevel.NONE")
                            || value.equals("NONE")
                            || value.equals("value=NONE"));
            if (name.equals("Getter")) getterEnabled = !none;
            if (name.equals("Setter")) setterEnabled = !none;
        }
        setterEnabled &= !fieldModifiers.getFlags().contains(Modifier.FINAL);
        if (!getterEnabled && !setterEnabled) {
            return Optional.empty();
        }
        var normalizedType = fieldType == null ? "" : fieldType.trim();
        var booleanField = isBooleanType(normalizedType);
        var booleanPrefix = booleanField
                && fieldName.length() > 2
                && fieldName.startsWith("is")
                && Character.isUpperCase(fieldName.charAt(2));
        var suffix = booleanPrefix
                ? fieldName.substring(2)
                : Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        return Optional.of(
                new AccessorInfo(
                        fieldName,
                        normalizedType,
                        getterEnabled ? (booleanPrefix ? fieldName : (booleanField ? "is" : "get") + suffix) : null,
                        setterEnabled ? "set" + suffix : null));
    }

    public record AccessorInfo(String fieldName, String fieldType, String getterName, String setterName) {
        public boolean hasGetter() {
            return getterName != null && !getterName.isBlank();
        }

        public boolean hasSetter() {
            return setterName != null && !setterName.isBlank();
        }
    }

    private static boolean hasAnnotation(ModifiersTree modifiers, Set<String> allowedSimpleNames) {
        return hasAnnotation(null, modifiers, allowedSimpleNames);
    }

    private static boolean hasAnnotation(
            CompilationUnitTree root, ModifiersTree modifiers, Set<String> allowedSimpleNames) {
        if (modifiers == null) {
            return false;
        }
        for (var annotation : modifiers.getAnnotations()) {
            var annotationType = annotation.getAnnotationType().toString();
            if (!isLombokAnnotation(root, annotation)) {
                continue;
            }
            if (allowedSimpleNames.contains(simpleName(annotationType))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStructuralLombokAnnotationType(
            CompilationUnitTree root, AnnotationTree annotation) {
        return isLombokAnnotation(root, annotation)
                && STRUCTURAL.contains(simpleName(annotation.getAnnotationType().toString()));
    }

    public static boolean isLombokAnnotation(CompilationUnitTree root, AnnotationTree annotation) {
        var name = annotation.getAnnotationType().toString();
        var simple = simpleName(name);
        var expectedPackage = ANNOTATION_PACKAGES.get(simple);
        if (expectedPackage == null) return false;
        var qualifiedName = expectedPackage + "." + simple;
        if (name.indexOf('.') >= 0) return name.equals(qualifiedName);
        if (root == null) return isLombokAnnotationType(name);
        var hasExplicitImport = false;
        for (ImportTree importTree : root.getImports()) {
            if (importTree.isStatic()) continue;
            var imported = importTree.getQualifiedIdentifier().toString();
            if (!imported.endsWith(".*") && imported.endsWith("." + simple)) {
                hasExplicitImport = true;
                if (!imported.equals(qualifiedName)) return false;
            }
        }
        if (hasExplicitImport) return true;
        var hasMatchingWildcard = false;
        for (ImportTree importTree : root.getImports()) {
            if (importTree.isStatic()) continue;
            var imported = importTree.getQualifiedIdentifier().toString();
            if (imported.equals(expectedPackage + ".*")) {
                hasMatchingWildcard = true;
                break;
            }
        }
        if (!hasMatchingWildcard) return false;
        return !samePackageTypeMayShadow(root, simple);
    }

    private static boolean samePackageTypeMayShadow(CompilationUnitTree root, String simpleName) {
        for (var declaration : root.getTypeDecls()) {
            if (declaration instanceof ClassTree cls
                    && cls.getSimpleName().contentEquals(simpleName)) return true;
        }
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        var expectedFileName = simpleName + ".java";
        for (var source : FileStore.list(packageName)) {
            if (source.getFileName().toString().equals(expectedFileName)) return true;
        }
        return false;
    }

    private static boolean isBooleanType(String fieldType) {
        return "boolean".equals(fieldType);
    }

    /**
     * Lowercases first char unless first two chars are uppercase (to preserve "URLParser" style).
     *
     * @param value the string to decapitalize
     * @return the decapitalized string
     */
    public static String decapitalize(String value) {
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

    /**
     * Reverse-map accessor method name to field name. "getFoo" → "foo", "isBar" → "bar", "setBaz" →
     * "baz", "toString" → empty.
     *
     * @param methodName the accessor method name
     * @return an Optional containing the field name, or empty if not an accessor
     */
    public static Optional<String> accessorFieldName(String methodName) {
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

    // uses parsed CST — annotations already resolved, no file I/O
    public static boolean hasLogAnnotation(CompilationUnitTree root) {
        var found = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override public Void visitClass(ClassTree cls, Void unused) {
                if (hasAnnotation(root, cls.getModifiers(), LOGGING_ONLY)) found[0] = true;
                return found[0] ? null : super.visitClass(cls, unused);
            }
        }.scan(root, null);
        return found[0];
    }
}
