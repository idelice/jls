package org.javacs.lombok;

import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompileTask;
import org.javacs.rewrite.GenerateGettersSetters;
import org.javacs.StringSearch;

/**
 * Lightweight, processor-free synthesis of Lombok-like accessors for completion. We mirror the
 * naming used by Lombok for @Getter, @Setter, @Data/@Value, and @With.
 *
 * This is intentionally minimal and only covers accessors/withers. Builders and other features
 * still require a real annotation-processing pass (e.g. on save/lint).
 */
public final class LombokSyntheticMembers {
    private LombokSyntheticMembers() {}

    public static List<SyntheticMethod> syntheticMembers(CompileTask task, TypeElement type, String partial) {
        var result = new ArrayList<SyntheticMethod>();
        if (!isLombokAnnotated(type)) return result;
        for (var enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            var field = (VariableElement) enclosed;
            if (field.getModifiers().contains(Modifier.STATIC)) continue;
            var typeMirror = field.asType();
            var name = field.getSimpleName().toString();
            // getter
            var getter = getterName(field, typeMirror);
            if (matchesPartial(getter, partial)) {
                result.add(new SyntheticMethod(getter, typeMirror.toString(), false));
            }
            // setter (skip finals)
            if (!field.getModifiers().contains(Modifier.FINAL)) {
                var setter = setterName(field);
                if (matchesPartial(setter, partial)) {
                    result.add(new SyntheticMethod(setter, "void", true));
                }
            }
            // wither
            if (hasWith(type)) {
                var with = "with" + capitalize(name);
                if (matchesPartial(with, partial)) {
                    result.add(new SyntheticMethod(with, type.asType().toString(), true));
                }
            }
        }
        return result;
    }

    public record SyntheticMethod(String name, String returnType, boolean hasParam) {}

    private static boolean matchesPartial(String name, String partial) {
        return StringSearch.matchesPartialName(name, partial == null ? "" : partial);
    }

    private static boolean isLombokAnnotated(TypeElement type) {
        for (var ann : type.getAnnotationMirrors()) {
            var qname = ann.getAnnotationType().toString();
            if (qname.startsWith("lombok.")) return true;
        }
        return false;
    }

    private static boolean hasWith(TypeElement type) {
        for (var ann : type.getAnnotationMirrors()) {
            var qname = ann.getAnnotationType().toString();
            if (qname.equals("lombok.With")) return true;
            if (qname.equals("lombok.Wither")) return true; // legacy Lombok
            if (qname.equals("lombok.Value")) return true; // Lombok generates withers for @Value
        }
        return false;
    }

    private static String getterName(VariableElement field, TypeMirror type) {
        var name = field.getSimpleName().toString();
        var cap = capitalize(name);
        if (type.getKind() == TypeKind.BOOLEAN && name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            return name;
        }
        if (type.getKind() == TypeKind.BOOLEAN) return "is" + cap;
        return "get" + cap;
    }

    private static String setterName(VariableElement field) {
        return "set" + capitalize(field.getSimpleName().toString());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
