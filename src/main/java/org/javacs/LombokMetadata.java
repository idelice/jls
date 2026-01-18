package org.javacs;

import com.sun.source.tree.VariableTree;
import java.util.*;

/**
 * Metadata about Lombok-generated members for a class.
 * Extracted by analyzing Lombok annotations on a ClassTree.
 */
public class LombokMetadata {
    public enum BuilderSource {
        NONE,
        CLASS,
        METHOD,
        CONSTRUCTOR
    }

    // Annotation presence flags
    public boolean hasData = false;
    public boolean hasGetter = false;
    public boolean hasSetter = false;
    public boolean hasToString = false;
    public boolean hasEqualsAndHashCode = false;
    public boolean hasAllArgsConstructor = false;
    public boolean hasRequiredArgsConstructor = false;
    public boolean hasNoArgsConstructor = false;
    public boolean hasAllArgsConstructorAnnotation = false;
    public boolean hasRequiredArgsConstructorAnnotation = false;
    public boolean hasNoArgsConstructorAnnotation = false;
    public boolean hasBuilderAnnotation = false;
    public boolean hasBuilder = false;
    public boolean hasValue = false;
    public boolean hasExplicitConstructor = false;
    public boolean isRecord = false;
    public BuilderSource builderSource = BuilderSource.NONE;

    // Field information
    public List<VariableTree> allFields = new ArrayList<>();
    public Map<String, VariableTree> fieldsByName = new HashMap<>();
    public Set<String> excludedFromEquals = new HashSet<>();
    public Set<String> excludedFromToString = new HashSet<>();
    public Set<String> explicitMethodNames = new HashSet<>();
    public Set<String> explicitInnerTypeNames = new HashSet<>();
    public Set<String> getterFields = new HashSet<>();
    public Set<String> setterFields = new HashSet<>();
    public Set<String> explicitBuilderMethodNames = new HashSet<>();
    public String builderMethodName = "builder";
    public String builderClassName = "";
    public String buildMethodName = "build";
    public List<VariableTree> builderParams = new ArrayList<>();
    public Map<String, VariableTree> builderParamsByName = new HashMap<>();

    /**
     * Check if a method name is a generated getter.
     */
    public boolean isGeneratedGetter(String methodName) {
        for (var field : allFields) {
            if (methodName.equals(getterName(field)) && shouldGenerateGetter(field)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a method name is a generated setter.
     */
    public boolean isGeneratedSetter(String methodName) {
        for (var field : allFields) {
            if (methodName.equals(setterName(field)) && shouldGenerateSetter(field)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a method name is toString, equals, or hashCode (generated).
     */
    public boolean isGeneratedSpecialMethod(String methodName) {
        if (hasToString && methodName.equals("toString")) return true;
        if (hasEqualsAndHashCode && methodName.equals("equals")) return true;
        if (hasEqualsAndHashCode && methodName.equals("hashCode")) return true;
        return false;
    }

    /**
     * Check if a method is a generated constructor.
     */
    public boolean isGeneratedConstructor(String signature) {
        // Simple check: constructors have specific parameter counts
        if (hasAllArgsConstructor && signature.contains("(")) return true;
        if (hasRequiredArgsConstructor && signature.contains("(")) return true;
        if (hasNoArgsConstructor && signature.equals("()")) return true;
        return false;
    }

    /**
     * Get the field that corresponds to a getter method.
     */
    public VariableTree fieldForGetter(String methodName) {
        for (var field : allFields) {
            if (methodName.equals(getterName(field)) && shouldGenerateGetter(field)) {
                return field;
            }
        }
        return null;
    }

    /**
     * Get the field that corresponds to a setter method.
     */
    public VariableTree fieldForSetter(String methodName) {
        for (var field : allFields) {
            if (methodName.equals(setterName(field)) && shouldGenerateSetter(field)) {
                return field;
            }
        }
        return null;
    }

    /**
     * Get all generated getter method names.
     */
    public List<String> getGeneratedGetterNames() {
        var names = new ArrayList<String>();
        if (hasGetter || hasData || hasValue || !getterFields.isEmpty()) {
            for (var field : allFields) {
                if (!shouldGenerateGetter(field)) continue;
                names.add(getterName(field));
            }
        }
        return names;
    }

    /**
     * Get all generated setter method names.
     */
    public List<String> getGeneratedSetterNames() {
        var names = new ArrayList<String>();
        if ((hasSetter || hasData || !setterFields.isEmpty()) && !allFields.isEmpty()) {
            for (var field : allFields) {
                if (!shouldGenerateSetter(field)) continue;
                names.add(setterName(field));
            }
        }
        return names;
    }

    /**
     * Get constructor parameter names (in order).
     */
    public List<String> getConstructorParameterNames() {
        var names = new ArrayList<String>();
        if (hasAllArgsConstructor) {
            for (var field : allFields) {
                if (!isStatic(field)) {
                    names.add(field.getName().toString());
                }
            }
        } else if (hasRequiredArgsConstructor) {
            for (var field : allFields) {
                if (isRequiredField(field)) {
                    names.add(field.getName().toString());
                }
            }
        }
        return names;
    }

    public String getterNameForField(String fieldName) {
        var field = fieldsByName.get(fieldName);
        if (field == null) return null;
        return getterName(field);
    }

    public String setterNameForField(String fieldName) {
        var field = fieldsByName.get(fieldName);
        if (field == null) return null;
        if (!shouldGenerateSetter(field)) return null;
        return setterName(field);
    }

    public boolean isGeneratedBuilderMethod(String methodName) {
        if (!hasBuilder) return false;
        if (explicitBuilderMethodNames.contains(methodName)) return false;
        if (methodName.equals(buildMethodName)) return true;
        for (var param : builderParams) {
            if (param.getName().contentEquals(methodName)) {
                return true;
            }
        }
        return false;
    }

    public List<String> getBuilderSetterNames() {
        var names = new ArrayList<String>();
        if (!hasBuilder) return names;
        for (var param : builderParams) {
            var name = param.getName().toString();
            if (explicitBuilderMethodNames.contains(name)) continue;
            names.add(name);
        }
        return names;
    }

    public VariableTree builderParamForName(String name) {
        return builderParamsByName.get(name);
    }

    /**
     * Generate getter name for a field.
     * "name" -> "getName()"
     * "active" (boolean) -> "isActive()"
     */
    private String getterName(VariableTree field) {
        var fieldName = field.getName().toString();
        var typeName = field.getType().toString();
        var isBoolean = typeName.equals("boolean");

        if (isBoolean && fieldName.startsWith("is") && fieldName.length() > 2
                && Character.isUpperCase(fieldName.charAt(2))) {
            return fieldName;
        } else if (isBoolean) {
            return "is" + capitalize(fieldName);
        } else {
            return "get" + capitalize(fieldName);
        }
    }

    /**
     * Generate setter name for a field.
     * "name" -> "setName()"
     */
    private String setterName(VariableTree field) {
        var fieldName = field.getName().toString();
        var typeName = field.getType().toString();
        var isBoolean = typeName.equals("boolean");
        if (isBoolean && fieldName.startsWith("is") && fieldName.length() > 2
                && Character.isUpperCase(fieldName.charAt(2))) {
            return "set" + fieldName.substring(2);
        }
        return "set" + capitalize(fieldName);
    }

    /**
     * Check if a field is final.
     */
    private boolean isFinal(VariableTree field) {
        for (var mod : field.getModifiers().getFlags()) {
            if (mod.name().equals("FINAL")) {
                return true;
            }
        }
        return false;
    }

    private boolean isStatic(VariableTree field) {
        for (var mod : field.getModifiers().getFlags()) {
            if (mod.name().equals("STATIC")) {
                return true;
            }
        }
        return false;
    }

    private boolean isRequiredField(VariableTree field) {
        if (isStatic(field)) return false;
        if (field.getInitializer() != null) return false;
        if (isFinal(field)) return true;
        return hasNonNullAnnotation(field);
    }

    private boolean hasNonNullAnnotation(VariableTree field) {
        for (var annotation : field.getModifiers().getAnnotations()) {
            var annotationType = annotation.getAnnotationType().toString();
            var simpleName = annotationType.substring(annotationType.lastIndexOf('.') + 1);
            if (simpleName.equals("NonNull")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldGenerateGetter(VariableTree field) {
        if (isStatic(field)) return false;
        var fieldName = field.getName().toString();
        if (!(hasGetter || hasData || hasValue || getterFields.contains(fieldName))) return false;
        return !explicitMethodNames.contains(getterName(field));
    }

    private boolean shouldGenerateSetter(VariableTree field) {
        if (isStatic(field)) return false;
        var fieldName = field.getName().toString();
        if (!(hasSetter || hasData || setterFields.contains(fieldName))) return false;
        if (isFinal(field)) return false;
        return !explicitMethodNames.contains(setterName(field));
    }

    /**
     * Capitalize first letter.
     * "name" -> "Name"
     */
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
