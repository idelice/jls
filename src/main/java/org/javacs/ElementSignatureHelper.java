package org.javacs;

import java.util.List;
import java.util.StringJoiner;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public final class ElementSignatureHelper {

    private ElementSignatureHelper() {}

    public static String simpleTypeName(TypeMirror type) {
        return switch (type.getKind()) {
            case DECLARED -> {
                var declared = (DeclaredType) type;
                var name = declared.asElement().getSimpleName().toString();
                var args = declared.getTypeArguments();
                if (args.isEmpty()) yield name;
                var ja = new StringJoiner(", ");
                for (var a : args) ja.add(simpleTypeName(a));
                yield name + "<" + ja + ">";
            }
            case ARRAY -> simpleTypeName(((ArrayType) type).getComponentType()) + "[]";
            case WILDCARD -> "?";
            default -> type.toString();
        };
    }

    public static boolean hasSyntheticNames(List<? extends VariableElement> params) {
        for (var p : params) {
            if (p.getSimpleName().toString().matches("arg\\d+")) return true;
        }
        return false;
    }

    public static String[] parameterNames(ExecutableElement method, byte[] ownerClassBytes) {
        if (ownerClassBytes == null) return null;
        var erasedTypes = new String[method.getParameters().size()];
        for (int i = 0; i < erasedTypes.length; i++) {
            erasedTypes[i] = method.getParameters().get(i).asType().toString();
        }
        return ClassFileParameterNames.read(
                ownerClassBytes, method.getSimpleName().toString(), erasedTypes,
                method.getModifiers().contains(Modifier.STATIC));
    }

    public static String methodSignature(ExecutableElement method, byte[] ownerClassBytes) {
        var paramElements = method.getParameters();
        String[] resolvedNames = hasSyntheticNames(paramElements)
                ? parameterNames(method, ownerClassBytes) : null;
        var params = new StringJoiner(", ");
        for (int i = 0; i < paramElements.size(); i++) {
            var p = paramElements.get(i);
            var name = (resolvedNames != null && i < resolvedNames.length && resolvedNames[i] != null)
                    ? resolvedNames[i] : p.getSimpleName().toString();
            params.add(simpleTypeName(p.asType()) + " " + name);
        }
        return simpleTypeName(method.getReturnType()) + " " + method.getSimpleName() + "(" + params + ")";
    }

    public static String ownerQualifiedName(ExecutableElement method) {
        return method.getEnclosingElement() instanceof TypeElement type
                ? type.getQualifiedName().toString() : null;
    }
}
