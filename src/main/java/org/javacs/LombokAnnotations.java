package org.javacs;

import com.sun.source.tree.ModifiersTree;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Centralized Lombok annotation semantics used across compiler, indexing, completion, and
 * navigation code.
 *
 * <p>This class is the only place that should know which annotations count as Lombok, which ones
 * change the source model, and how generated accessor names should map back to source fields.
 */
public final class LombokAnnotations {
    private static final Set<String> LOGGING_ONLY = Set.of("Slf4j");
    private static final Set<String> ACCESSOR_RELATED = Set.of("Data", "Getter", "Setter", "Value");
    private static final Set<String> KNOWN =
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
                    "With",
                    "Slf4j");

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
                    "@(?:lombok\\.(?:experimental\\.)?)?"
                            + "(Data|Getter|Setter|Builder|Value|SuperBuilder|RequiredArgsConstructor|AllArgsConstructor|NoArgsConstructor|EqualsAndHashCode|ToString|With)\\b");

    private LombokAnnotations() {}

    /** Returns whether the modifiers include a Lombok annotation that changes the declared shape. */
    public static boolean hasStructuralLombokAnnotation(ModifiersTree modifiers) {
        return hasAnnotation(modifiers, STRUCTURAL);
    }

    /** Returns whether the modifiers include Lombok annotations that only add logging helpers. */
    public static boolean hasLoggingOnlyLombokAnnotation(ModifiersTree modifiers) {
        return hasAnnotation(modifiers, LOGGING_ONLY);
    }

    /** Returns whether Lombok accessor generation may apply to the declaration. */
    public static boolean hasAccessorLombokAnnotation(ModifiersTree modifiers) {
        return hasAnnotation(modifiers, ACCESSOR_RELATED);
    }

    /** Returns whether Lombok builder generation may apply to the declaration. */
    public static boolean hasBuilderLombokAnnotation(ModifiersTree modifiers) {
        return hasAnnotation(modifiers, "Builder", "SuperBuilder");
    }

    /** Returns whether the supplied type name refers to a supported Lombok annotation. */
    public static boolean isLombokAnnotationType(String annotationType) {
        if (annotationType == null || annotationType.isBlank()) {
            return false;
        }
        if (annotationType.startsWith("lombok.")) {
            return true;
        }
        return KNOWN.contains(simpleName(annotationType));
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

    /** Returns whether the modifiers contain any of the requested Lombok annotations. */
    public static boolean hasAnnotation(ModifiersTree modifiers, String... allowedSimpleNames) {
        return hasAnnotation(modifiers, Set.of(allowedSimpleNames));
    }

    /**
     * Resolves Lombok-generated accessor names for a field.
     *
     * <p>The caller supplies the class and field modifiers plus the field type/name. The result
     * indicates which accessor methods Lombok would synthesize for that field.
     */
    public static Optional<AccessorInfo> accessorInfo(
            ModifiersTree classModifiers, ModifiersTree fieldModifiers, String fieldName, String fieldType) {
        if (fieldName == null || fieldName.isBlank()) {
            return Optional.empty();
        }
        var classGetter = hasAnnotation(classModifiers, "Data", "Getter", "Value");
        var classSetter = hasAnnotation(classModifiers, "Data", "Setter");
        var getterEnabled = classGetter || hasAnnotation(fieldModifiers, "Getter");
        var setterEnabled = classSetter || hasAnnotation(fieldModifiers, "Setter");
        if (!getterEnabled && !setterEnabled) {
            return Optional.empty();
        }
        var normalizedType = fieldType == null ? "" : fieldType.trim();
        var suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        return Optional.of(
                new AccessorInfo(
                        fieldName,
                        normalizedType,
                        getterEnabled ? (isBooleanType(normalizedType) ? "is" : "get") + suffix : null,
                        setterEnabled ? "set" + suffix : null));
    }

    /**
     * Best-effort accessor-to-field mapping used when compiled metadata does not retain a Lombok
     * field link.
     */
    public static Optional<String> backingFieldNameForAccessor(String accessorName, int parameterCount) {
        if (accessorName == null || accessorName.isBlank()) {
            return Optional.empty();
        }
        if (parameterCount == 0) {
            if (accessorName.startsWith("get") && accessorName.length() > 3) {
                return Optional.of(decapitalizeAccessorSuffix(accessorName.substring(3)));
            }
            if (accessorName.startsWith("is") && accessorName.length() > 2) {
                return Optional.of(decapitalizeAccessorSuffix(accessorName.substring(2)));
            }
            return Optional.empty();
        }
        if (parameterCount == 1 && accessorName.startsWith("set") && accessorName.length() > 3) {
            return Optional.of(decapitalizeAccessorSuffix(accessorName.substring(3)));
        }
        return Optional.empty();
    }

    private static String decapitalizeAccessorSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return "";
        }
        if (suffix.length() > 1 && Character.isUpperCase(suffix.charAt(0)) && Character.isUpperCase(suffix.charAt(1))) {
            return suffix;
        }
        return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
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
        if (modifiers == null) {
            return false;
        }
        for (var annotation : modifiers.getAnnotations()) {
            var annotationType = annotation.getAnnotationType().toString();
            if (!isLombokAnnotationType(annotationType)) {
                continue;
            }
            if (allowedSimpleNames.contains(simpleName(annotationType))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBooleanType(String fieldType) {
        return "boolean".equals(fieldType)
                || "Boolean".equals(fieldType)
                || "java.lang.Boolean".equals(fieldType);
    }
}
