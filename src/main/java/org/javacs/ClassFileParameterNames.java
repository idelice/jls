package org.javacs;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

/**
 * Reads parameter names from a classfile's LocalVariableTable. No reflection, no caching, no
 * source-JAR parsing — just reads the bytes javac already loaded from the classpath.
 */
public final class ClassFileParameterNames {

    public static String[] read(byte[] classBytes, String methodName, String[] erasedParameterTypes, boolean isStatic) {
        ClassModel cm;
        try {
            cm = ClassFile.of().parse(classBytes);
        } catch (Exception e) {
            return null;
        }
        for (var m : cm.methods()) {
            if (!m.methodName().equalsString(methodName)) continue;
            if (!parameterTypesMatch(m.methodTypeSymbol(), erasedParameterTypes)) continue;
            return readFromLvt(m, erasedParameterTypes.length, isStatic);
        }
        return null;
    }

    private static boolean parameterTypesMatch(MethodTypeDesc descriptor, String[] erasedTypes) {
        if (descriptor.parameterCount() != erasedTypes.length) return false;
        for (int i = 0; i < erasedTypes.length; i++) {
            if (!descriptorToQualifiedName(descriptor.parameterType(i)).equals(erasedTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private static String[] readFromLvt(MethodModel method, int paramCount, boolean isStatic) {
        var code = method.findAttribute(Attributes.code()).orElse(null);
        if (code == null) return null;
        var lvt = code.findAttribute(Attributes.localVariableTable()).orElse(null);
        if (lvt == null) return null;
        int slot = isStatic ? 0 : 1;
        var names = new String[paramCount];
        int found = 0;
        var paramTypes = method.methodTypeSymbol();
        for (int i = 0; i < paramCount; i++) {
            for (var entry : lvt.localVariables()) {
                if (entry.slot() == slot && entry.startPc() == 0) {
                    names[i] = entry.name().stringValue();
                    found++;
                    break;
                }
            }
            var desc = paramTypes.parameterType(i).descriptorString();
            slot += ("J".equals(desc) || "D".equals(desc)) ? 2 : 1;
        }
        return found == paramCount ? names : null;
    }

    /** Converts a classfile type descriptor to the qualified dotted form that types.erasure().toString() produces. */
    static String descriptorToQualifiedName(ClassDesc desc) {
        if (desc.isPrimitive()) return desc.displayName();
        if (desc.isArray()) return descriptorToQualifiedName(desc.componentType()) + "[]";
        // Reference type: descriptor is "Lpackage/Name;" — convert to dotted form.
        // Inner classes use $ in classfiles but types.erasure().toString() uses dots.
        var d = desc.descriptorString();
        return d.substring(1, d.length() - 1).replace('/', '.').replace('$', '.');
    }
}
