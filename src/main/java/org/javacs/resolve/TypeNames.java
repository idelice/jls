package org.javacs.resolve;

import com.sun.source.tree.CompilationUnitTree;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.Set;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Shared type-name helpers for providers that need to normalize user/source names without
 * dragging in provider-specific symbol logic.
 */
public final class TypeNames {
    private static final Set<String> PRIMITIVE_TYPE_NAMES =
            Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char", "void");

    private TypeNames() {}

    /** Normalize source-facing type text to a stable qualified-name form when possible. */
    public static String normalize(String typeName) {
        var raw = typeName == null ? "" : typeName.trim();
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
            return "";
        }
        return raw.replace('$', '.').trim();
    }

    /** Canonicalize primitive and boxed names to the boxed java.lang form used by symbol matching. */
    public static String canonicalBoxed(String typeName) {
        return switch (normalize(typeName)) {
            case "byte", "java.lang.Byte" -> "java.lang.Byte";
            case "short", "java.lang.Short" -> "java.lang.Short";
            case "int", "java.lang.Integer" -> "java.lang.Integer";
            case "long", "java.lang.Long" -> "java.lang.Long";
            case "float", "java.lang.Float" -> "java.lang.Float";
            case "double", "java.lang.Double" -> "java.lang.Double";
            case "boolean", "java.lang.Boolean" -> "java.lang.Boolean";
            case "char", "java.lang.Character" -> "java.lang.Character";
            default -> normalize(typeName);
        };
    }

    /** Return the simple name portion of a qualified type or member owner. */
    public static String simpleName(String qualifiedType) {
        var normalized = normalize(qualifiedType);
        var index = normalized.lastIndexOf('.');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    public static boolean isPrimitive(String typeName) {
        return PRIMITIVE_TYPE_NAMES.contains(normalize(typeName));
    }

    public static Optional<String> resolveSimpleName(
            String simpleName, CompilationUnitTree root, Predicate<String> containsType) {
        if (simpleName == null || simpleName.isBlank() || root == null || containsType == null) {
            return Optional.empty();
        }
        for (var importTree : root.getImports()) {
            if (importTree.isStatic()) {
                continue;
            }
            var imported = importTree.getQualifiedIdentifier().toString();
            if (!imported.endsWith(".*") && imported.endsWith("." + simpleName) && containsType.test(imported)) {
                return Optional.of(imported);
            }
        }
        var candidates = new ObjectLinkedOpenHashSet<String>();
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!packageName.isBlank()) {
            var samePackage = packageName + "." + simpleName;
            if (containsType.test(samePackage)) {
                candidates.add(samePackage);
            }
        }

        for (var importTree : root.getImports()) {
            if (importTree.isStatic()) {
                continue;
            }
            var imported = importTree.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) {
                var candidate = imported.substring(0, imported.length() - 1) + simpleName;
                if (containsType.test(candidate)) {
                    candidates.add(candidate);
                }
            }
        }

        var javaLang = "java.lang." + simpleName;
        if (containsType.test(javaLang)) {
            candidates.add(javaLang);
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        }
        return Optional.empty();
    }
}
