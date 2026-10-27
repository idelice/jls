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
import javax.lang.model.type.WildcardType;

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
            case WILDCARD -> {
                var wildcard = (WildcardType) type;
                if (wildcard.getExtendsBound() != null) yield "? extends " + simpleTypeName(wildcard.getExtendsBound());
                if (wildcard.getSuperBound() != null) yield "? super " + simpleTypeName(wildcard.getSuperBound());
                yield "?";
            }
            default -> type.toString();
        };
    }

    public static boolean hasSyntheticNames(List<? extends VariableElement> params) {
        for (var p : params) {
            if (p.getSimpleName().toString().matches("arg\\d+")) return true;
        }
        return false;
    }

    public static String[] parameterNames(CompileTask task, ExecutableElement method, byte[] ownerClassBytes) {
        var names = method.getParameters().stream()
                .map(parameter -> parameter.getSimpleName().toString()).toArray(String[]::new);
        if (!hasSyntheticNames(method.getParameters()) || ownerClassBytes == null) return names;
        var fromDebugInfo = ClassFileParameterNames.read(ownerClassBytes, method.getSimpleName().toString(),
                FindHelper.erasedParameterTypes(task, method), method.getModifiers().contains(Modifier.STATIC));
        return fromDebugInfo == null ? names : fromDebugInfo;
    }

    public static String[] parameterNamesFromSource(
            CompileTask task, ExecutableElement method, ParseTask source, String[] fallback) {
        try {
            var declaration = FindHelper.findMethod(source, ownerQualifiedName(method),
                    method.getSimpleName().toString(), FindHelper.erasedParameterTypes(task, method));
            if (declaration.getParameters().size() != fallback.length) return fallback;
            return declaration.getParameters().stream()
                    .map(parameter -> parameter.getName().toString()).toArray(String[]::new);
        } catch (RuntimeException missingDeclaration) {
            return fallback;
        }
    }

    public static String methodParams(ExecutableElement method, String[] parameterNames) {
        var params = new StringJoiner(", ");
        for (int i = 0; i < method.getParameters().size(); i++) {
            var parameter = method.getParameters().get(i);
            var name = parameterNames != null && i < parameterNames.length && parameterNames[i] != null
                    ? parameterNames[i] : parameter.getSimpleName().toString();
            params.add(simpleTypeName(parameter.asType()) + " " + name);
        }
        return "(" + params + ")";
    }

    public static String ownerQualifiedName(ExecutableElement method) {
        return method.getEnclosingElement() instanceof TypeElement type
                ? type.getQualifiedName().toString() : null;
    }
}
