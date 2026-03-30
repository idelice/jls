package org.javacs.resolve;

import com.sun.source.tree.CompilationUnitTree;
import java.util.List;
import org.javacs.completion.WorkspaceTypeIndex;

/**
 * Shared type-name helpers for providers that need to normalize user/source names without
 * dragging in provider-specific symbol logic.
 */
public final class TypeNames {
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

    /**
     * Resolve owner types introduced by static imports through the existing index helper so
     * providers stop reimplementing the same import scan.
     */
    public static List<String> staticImportOwnerTypes(String memberName, CompilationUnitTree root) {
        return WorkspaceTypeIndex.staticImportOwnerTypes(memberName, root);
    }
}
