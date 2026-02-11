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
    public boolean hasSlf4j = false;
    public BuilderSource builderSource = BuilderSource.NONE;

    // Field information
    public List<VariableTree> allFields = new ArrayList<>();
    public Map<String, VariableTree> fieldsByName = new HashMap<>();
    public Set<String> inheritedFieldNames = new HashSet<>();  // Track inherited fields for getter/setter generation
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

    // Precomputed generated-member indexes for hot-path lookups.
    private final Set<String> generatedGetterNames = new HashSet<>();
    private final Set<String> generatedSetterNames = new HashSet<>();
    private final Set<String> generatedSpecialMethodNames = new HashSet<>();
    private final Set<String> generatedBuilderMethodNames = new HashSet<>();
    private final Map<String, VariableTree> getterFieldByMethodName = new HashMap<>();
    private final Map<String, VariableTree> setterFieldByMethodName = new HashMap<>();
    private volatile boolean indexesInitialized = false;

    /**
     * Check if a method name is a generated getter.
     */
    public boolean isGeneratedGetter(String methodName) {
        ensureIndexes();
        return generatedGetterNames.contains(methodName);
    }

    /**
     * Check if a method name is a generated setter.
     */
    public boolean isGeneratedSetter(String methodName) {
        ensureIndexes();
        return generatedSetterNames.contains(methodName);
    }

    /**
     * Check if a method name is toString, equals, or hashCode (generated).
     */
    public boolean isGeneratedSpecialMethod(String methodName) {
        ensureIndexes();
        return generatedSpecialMethodNames.contains(methodName);
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
        ensureIndexes();
        return getterFieldByMethodName.get(methodName);
    }

    /**
     * Get the field that corresponds to a setter method.
     */
    public VariableTree fieldForSetter(String methodName) {
        ensureIndexes();
        return setterFieldByMethodName.get(methodName);
    }

    /**
     * Get all generated getter method names.
     */
    public List<String> getGeneratedGetterNames() {
        ensureIndexes();
        return new ArrayList<>(generatedGetterNames);
    }

    /**
     * Get all generated setter method names.
     */
    public List<String> getGeneratedSetterNames() {
        ensureIndexes();
        return new ArrayList<>(generatedSetterNames);
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
        ensureIndexes();
        return generatedBuilderMethodNames.contains(methodName);
    }

    public synchronized void rebuildGeneratedIndexes() {
        indexesInitialized = false;
        generatedGetterNames.clear();
        generatedSetterNames.clear();
        generatedSpecialMethodNames.clear();
        generatedBuilderMethodNames.clear();
        getterFieldByMethodName.clear();
        setterFieldByMethodName.clear();

        if (hasGetter || hasData || hasValue || !getterFields.isEmpty()) {
            for (var field : allFields) {
                if (!shouldGenerateGetter(field)) continue;
                var getter = getterName(field);
                generatedGetterNames.add(getter);
                getterFieldByMethodName.put(getter, field);
            }
            if ((hasGetter || hasData || hasValue) && !inheritedFieldNames.isEmpty()) {
                for (var fieldName : inheritedFieldNames) {
                    generatedGetterNames.add("get" + capitalize(fieldName));
                }
            }
        }

        if ((hasSetter || hasData || !setterFields.isEmpty())
                && (!allFields.isEmpty() || !inheritedFieldNames.isEmpty())) {
            for (var field : allFields) {
                if (!shouldGenerateSetter(field)) continue;
                var setter = setterName(field);
                generatedSetterNames.add(setter);
                setterFieldByMethodName.put(setter, field);
            }
            if ((hasSetter || hasData) && !inheritedFieldNames.isEmpty()) {
                for (var fieldName : inheritedFieldNames) {
                    generatedSetterNames.add("set" + capitalize(fieldName));
                }
            }
        }

        if (hasToString) {
            generatedSpecialMethodNames.add("toString");
        }
        if (hasEqualsAndHashCode) {
            generatedSpecialMethodNames.add("equals");
            generatedSpecialMethodNames.add("hashCode");
        }

        if (hasBuilder) {
            if (!explicitBuilderMethodNames.contains(buildMethodName)) {
                generatedBuilderMethodNames.add(buildMethodName);
            }
            for (var param : builderParams) {
                var name = param.getName().toString();
                if (!explicitBuilderMethodNames.contains(name)) {
                    generatedBuilderMethodNames.add(name);
                }
            }
        }
        indexesInitialized = true;
    }

    public void markIndexesDirty() {
        indexesInitialized = false;
    }

    private void ensureIndexes() {
        if (indexesInitialized) {
            return;
        }
        rebuildGeneratedIndexes();
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
     * Check if a variable is the Slf4j log variable.
     */
    public boolean isSlf4jLogVariable(String variableName) {
        return hasSlf4j && "log".equals(variableName);
    }

    /**
     * Check if a method is a Slf4j log method (e.g., log.info, log.debug).
     */
    public boolean isSlf4jLogMethod(String methodName) {
        if (!hasSlf4j) return false;
        return methodName.matches("(trace|debug|info|warn|error)");
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
