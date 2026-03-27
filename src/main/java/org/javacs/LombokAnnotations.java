package org.javacs;

import com.sun.source.tree.ModifiersTree;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

public final class LombokAnnotations {
    private static final Set<String> LOGGING_ONLY = Set.of("Slf4j");
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


    public static boolean hasStructuralLombokAnnotation(ModifiersTree modifiers) {
        return hasAnnotation(modifiers, STRUCTURAL);
    }

    public static boolean hasLoggingOnlyLombokAnnotation(ModifiersTree modifiers) {
        return hasAnnotation(modifiers, LOGGING_ONLY);
    }


    public static boolean isLombokAnnotationType(String annotationType) {
        if (annotationType == null || annotationType.isBlank()) {
            return false;
        }
        if (annotationType.startsWith("lombok.")) {
            return true;
        }
        return KNOWN.contains(simpleName(annotationType));
    }

    public static boolean isStructuralLombokAnnotationType(String annotationType) {
        return isLombokAnnotationType(annotationType) && STRUCTURAL.contains(simpleName(annotationType));
    }


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
}
