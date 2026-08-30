package org.javacs;

import com.sun.source.tree.*;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.util.logging.Logger;

/**
 * Injects Lombok-generated method/field/constructor stubs into parsed ASTs before the Enter phase.
 *
 * <p>This runs between task.parse() and impl.enter() in CompileBatch. The injected stubs have
 * correct signatures so javac's attribution phase resolves calls to Lombok-generated members.
 * No annotation processing needed — stubs are injected directly into the parse tree.
 *
 * <p>Stubs have trivial bodies (return null/0/false or empty) since we only need signatures
 * for type resolution, not runtime execution.
 */
public class LombokStubInjector {
    private static final Logger LOG = Logger.getLogger("main");

    private final TreeMaker make;
    private final Names names;
    private CompilationUnitTree root;

    private boolean hasAnnotation(ModifiersTree modifiers, String... names) {
        return LombokAnnotations.hasAnnotation(root, modifiers, names);
    }

    LombokStubInjector(Context context) {
        this.make = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }


    /**
     * Inject Lombok stubs into all class declarations in the compilation unit.
     * Call this after parse() but before enter().
     */
    public static void injectParseTask(ParseTask parseTask) {
        var task = (JavacTaskImpl) parseTask.task();
        new LombokStubInjector(task.getContext()).inject(parseTask.root());
    }

    void inject(CompilationUnitTree root) {
        this.root = root;
        if (!LombokAnnotations.hasStructuralLombokAnnotation(root)
                && !LombokAnnotations.hasLogAnnotation(root)) return;
        var unit = (JCCompilationUnit) root;
        for (var def : unit.defs) {
            if (def instanceof JCClassDecl classDecl) {
                injectClass(classDecl);
            }
        }
    }

    private void injectClass(JCClassDecl classDecl) {
        make.at(classDecl.pos);
        var loggerCount = injectLogger(classDecl);
        var mods = classDecl.getModifiers();
        var structural = LombokAnnotations.hasStructuralLombokAnnotation(root, mods);
        var hasData = hasAnnotation(mods, "Data");
        var hasValue = hasAnnotation(mods, "Value");
        var hasNoArgs = hasAnnotation(mods, "NoArgsConstructor");
        var hasAllArgs = hasAnnotation(mods, "AllArgsConstructor");
        var hasRequiredArgs = hasAnnotation(mods, "RequiredArgsConstructor");
        var hasBuilder = hasAnnotation(mods, "Builder");

        if (classDecl.getKind() == Tree.Kind.INTERFACE
                || classDecl.getKind() == Tree.Kind.ANNOTATION_TYPE) {
            recurseNested(classDecl);
            return;
        }

        var accessorCount = injectAccessors(classDecl);
        var constructorCount = 0;
        var builderCount = 0;
        if (structural) {
            var instanceFields = collectInstanceFields(classDecl);
            var explicitConstructorWritten = hasExplicitConstructorAny(classDecl);
            var simpleBuilder = hasBuilder && supportsSimpleBuilder(classDecl, instanceFields);
            constructorCount = injectConstructors(
                    classDecl,
                    instanceFields,
                    hasData,
                    hasValue,
                    hasNoArgs,
                    hasAllArgs,
                    hasRequiredArgs,
                    simpleBuilder && !explicitConstructorWritten
                            && !hasNoArgs && !hasRequiredArgs && !hasAllArgs);
            if (hasBuilder) {
                builderCount = injectBuilder(classDecl, instanceFields, explicitConstructorWritten);
            }
        }
        if (structural || loggerCount > 0 || accessorCount > 0) {
            LOG.info("[lombok-source-compile] class=" + classDecl.getSimpleName()
                    + " accessors=" + accessorCount + " loggers=" + loggerCount
                    + " constructors=" + constructorCount + " builders=" + builderCount);
        }
        recurseNested(classDecl);
    }

    private int injectLogger(JCClassDecl classDecl) {
        if ((classDecl.getKind() == Tree.Kind.INTERFACE || classDecl.getKind() == Tree.Kind.ANNOTATION_TYPE)
                || !LombokAnnotations.hasLoggingOnlyLombokAnnotation(root, classDecl.getModifiers())
                || hasField(classDecl, LombokAnnotations.DEFAULT_LOG_FIELD_NAME)) {
            return 0;
        }
        var loggerType = loggingType(classDecl.getModifiers());
        if (loggerType == null) return 0;
        classDecl.defs = classDecl.defs.append(createLogField(loggerType));
        return 1;
    }

    private int injectAccessors(JCClassDecl classDecl) {
        var injected = 0;
        var classModifiers = classDecl.getModifiers();
        for (var member : classDecl.defs) {
            if (!(member instanceof JCVariableDecl field)
                    || (field.mods.flags & Flags.STATIC) != 0
                    || (field.mods.flags & Flags.ENUM) != 0
                    || field.vartype == null) {
                continue;
            }
            var fieldName = field.getName().toString();
            if (fieldName.isBlank()) continue;
            var accessorInfo = LombokAnnotations.accessorInfo(
                    root, classModifiers, field.getModifiers(), fieldName, field.vartype.toString());
            if (accessorInfo.isEmpty()) continue;
            var info = accessorInfo.get();
            if (info.hasGetter() && !hasExplicitMethod(classDecl, info.getterName(), 0)) {
                classDecl.defs = classDecl.defs.append(
                        createGetter(
                                info.getterName(), field.vartype, fieldName,
                                accessorFlags(classModifiers, field.getModifiers(), "Getter")));
                injected++;
            }
            if (info.hasSetter() && !hasExplicitMethod(classDecl, info.setterName(), 1)) {
                classDecl.defs = classDecl.defs.append(
                        createSetter(
                                info.setterName(), field.vartype, fieldName,
                                accessorFlags(classModifiers, field.getModifiers(), "Setter")));
                injected++;
            }
        }
        return injected;
    }

    private long accessorFlags(
            ModifiersTree classModifiers, ModifiersTree fieldModifiers, String annotationName) {
        var fieldAccess = declaredAccessFlags(fieldModifiers, annotationName, "value");
        if (fieldAccess != null) return fieldAccess;
        var classAccess = declaredAccessFlags(classModifiers, annotationName, "value");
        return classAccess == null ? Flags.PUBLIC : classAccess;
    }

    private Long declaredAccessFlags(
            ModifiersTree modifiers, String annotationName, String argumentName) {
        for (var annotation : modifiers.getAnnotations()) {
            if (!LombokAnnotations.isLombokAnnotation(root, annotation)
                    || !LombokAnnotations.simpleName(annotation.getAnnotationType().toString())
                            .equals(annotationName)) {
                continue;
            }
            for (var argument : annotation.getArguments()) {
                ExpressionTree value = argument;
                if (argument instanceof AssignmentTree assignment) {
                    if (!assignment.getVariable().toString().equals(argumentName)) continue;
                    value = assignment.getExpression();
                } else if (!argumentName.equals("value")) {
                    continue;
                }
                var access = LombokAnnotations.simpleName(value.toString());
                return switch (access) {
                    case "PROTECTED" -> (long) Flags.PROTECTED;
                    case "PACKAGE", "MODULE" -> 0L;
                    case "PRIVATE" -> (long) Flags.PRIVATE;
                    default -> (long) Flags.PUBLIC;
                };
            }
            return (long) Flags.PUBLIC;
        }
        return null;
    }

    private Long constructorFlags(ModifiersTree modifiers, String annotationName) {
        for (var annotation : modifiers.getAnnotations()) {
            if (!LombokAnnotations.isLombokAnnotation(root, annotation)
                    || !LombokAnnotations.simpleName(annotation.getAnnotationType().toString())
                            .equals(annotationName)) {
                continue;
            }
            for (var argument : annotation.getArguments()) {
                if (argument instanceof AssignmentTree assignment
                        && assignment.getVariable().toString().equals("access")
                        && LombokAnnotations.simpleName(assignment.getExpression().toString())
                                .equals("NONE")) {
                    return null;
                }
            }
        }
        var access = declaredAccessFlags(modifiers, annotationName, "access");
        return access == null ? (long) Flags.PUBLIC : access;
    }

    private int injectConstructors(
            JCClassDecl classDecl,
            List<JCVariableDecl> instanceFields,
            boolean hasData,
            boolean hasValue,
            boolean hasNoArgs,
            boolean hasAllArgsAnnotation,
            boolean hasRequiredArgs,
            boolean builderAllArgs) {
        var injected = 0;
        var isEnum = classDecl.getKind() == Tree.Kind.ENUM;
        if (hasNoArgs) {
            var accessFlags = constructorFlags(classDecl.getModifiers(), "NoArgsConstructor");
            if (accessFlags != null) {
                var factoryName = stringLiteralArgument(
                        classDecl.getModifiers(), "NoArgsConstructor", "staticName");
                classDecl.defs = classDecl.defs.append(
                        createConstructor(
                                List.nil(), isEnum,
                                factoryName == null ? accessFlags : Flags.PRIVATE));
                injected++;
                injected += injectStaticConstructorFactory(
                        classDecl, factoryName, List.nil(), accessFlags);
            }
        }
        var writtenConstructor = hasExplicitConstructorAny(classDecl);
        var explicitConstructorAnnotation = hasNoArgs || hasRequiredArgs || hasAllArgsAnnotation;
        var valueAllArgs = hasValue && !writtenConstructor && !explicitConstructorAnnotation;
        if (hasAllArgsAnnotation || valueAllArgs || builderAllArgs) {
            var allArgsFields = instanceFields.stream()
                    .filter(f -> !((f.mods.flags & Flags.FINAL) != 0 && f.init != null))
                    .filter(f -> !(hasValue && f.init != null
                            && !hasAnnotation(f.getModifiers(), "NonFinal")))
                    .collect(List.collector());
            if (hasAllArgsAnnotation || !hasExplicitConstructor(classDecl, allArgsFields.size())) {
                Long accessFlags;
                if (builderAllArgs) {
                    accessFlags = 0L;
                } else if (hasAllArgsAnnotation) {
                    accessFlags = constructorFlags(classDecl.getModifiers(), "AllArgsConstructor");
                } else {
                    accessFlags = (long) Flags.PUBLIC;
                }
                if (accessFlags != null) {
                    var factoryName = builderAllArgs
                            ? null
                            : hasAllArgsAnnotation
                            ? stringLiteralArgument(
                                    classDecl.getModifiers(), "AllArgsConstructor", "staticName")
                            : valueAllArgs
                                    ? stringLiteralArgument(
                                            classDecl.getModifiers(), "Value", "staticConstructor")
                                    : null;
                    classDecl.defs = classDecl.defs.append(
                            createConstructor(
                                    allArgsFields, isEnum,
                                    factoryName == null ? accessFlags : Flags.PRIVATE));
                    injected++;
                    injected += injectStaticConstructorFactory(
                            classDecl, factoryName, allArgsFields, accessFlags);
                }
            }
        }
        if (hasRequiredArgs) {
            var requiredFields = instanceFields.stream()
                    .filter(f -> f.init == null
                            && ((f.mods.flags & Flags.FINAL) != 0
                            || hasAnnotation(f.getModifiers(), "NonNull")))
                    .collect(List.collector());
            var accessFlags = constructorFlags(
                    classDecl.getModifiers(), "RequiredArgsConstructor");
            if (accessFlags != null) {
                var factoryName = stringLiteralArgument(
                        classDecl.getModifiers(), "RequiredArgsConstructor", "staticName");
                classDecl.defs = classDecl.defs.append(
                        createConstructor(
                                requiredFields, isEnum,
                                factoryName == null ? accessFlags : Flags.PRIVATE));
                injected++;
                injected += injectStaticConstructorFactory(
                        classDecl, factoryName, requiredFields, accessFlags);
            }
        }
        if (hasData && !hasNoArgs && !hasAllArgsAnnotation && !hasRequiredArgs
                && !hasExplicitConstructorAny(classDecl)) {
            var requiredFields = instanceFields.stream()
                    .filter(f -> f.init == null
                            && ((f.mods.flags & Flags.FINAL) != 0
                            || hasAnnotation(f.getModifiers(), "NonNull")))
                    .collect(List.collector());
            var factoryName = stringLiteralArgument(
                    classDecl.getModifiers(), "Data", "staticConstructor");
            classDecl.defs = classDecl.defs.append(createConstructor(
                    requiredFields, isEnum,
                    factoryName == null ? Flags.PUBLIC : Flags.PRIVATE));
            injected++;
            injected += injectStaticConstructorFactory(
                    classDecl, factoryName, requiredFields, Flags.PUBLIC);
        }
        return injected;
    }

    private String stringLiteralArgument(
            ModifiersTree modifiers, String annotationName, String argumentName) {
        for (var annotation : modifiers.getAnnotations()) {
            if (!LombokAnnotations.isLombokAnnotation(root, annotation)
                    || !LombokAnnotations.simpleName(annotation.getAnnotationType().toString())
                            .equals(annotationName)) {
                continue;
            }
            for (var argument : annotation.getArguments()) {
                if (argument instanceof AssignmentTree assignment
                        && assignment.getVariable().toString().equals(argumentName)
                        && assignment.getExpression() instanceof LiteralTree literal
                        && literal.getValue() instanceof String value
                        && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private int injectStaticConstructorFactory(
            JCClassDecl owner, String factoryName, List<JCVariableDecl> fields, long accessFlags) {
        if (factoryName == null) return 0;
        owner.defs = owner.defs.append(
                createStaticConstructorFactory(owner, factoryName, fields, accessFlags));
        return 1;
    }

    private JCMethodDecl createStaticConstructorFactory(
            JCClassDecl owner, String factoryName, List<JCVariableDecl> fields, long accessFlags) {
        var copier = new TreeCopier<Void>(make);
        var typeParameters = owner.typarams.stream()
                .map(parameter -> make.TypeParameter(
                        parameter.name, copier.copy(parameter.bounds)))
                .collect(List.collector());
        var params = fields.stream()
                .map(field -> make.VarDef(
                        make.Modifiers(Flags.PARAMETER),
                        field.name,
                        copier.copy(field.vartype),
                        null))
                .collect(List.collector());
        var arguments = fields.stream()
                .<JCExpression>map(field -> make.Ident(field.name))
                .collect(List.collector());
        var body = make.Block(0L, List.of(make.Return(make.NewClass(
                null, List.nil(), ownerType(owner), arguments, null))));
        return make.MethodDef(
                make.Modifiers(accessFlags | Flags.STATIC | Flags.GENERATED_MEMBER),
                names.fromString(factoryName),
                ownerType(owner),
                typeParameters,
                params,
                List.nil(),
                body,
                null);
    }

    private JCExpression ownerType(JCClassDecl owner) {
        var type = make.Ident(owner.name);
        if (owner.typarams.isEmpty()) return type;
        var arguments = owner.typarams.stream()
                .<JCExpression>map(parameter -> make.Ident(parameter.name))
                .collect(List.collector());
        return make.TypeApply(type, arguments);
    }

    private int injectBuilder(
            JCClassDecl classDecl, List<JCVariableDecl> fields, boolean explicitConstructorWritten) {
        if (classDecl.getKind() == Tree.Kind.ENUM
                || classDecl.getKind() == Tree.Kind.RECORD
                || (classDecl.mods.flags & Flags.ABSTRACT) != 0) {
            LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=unsupported-class-kind");
            return 0;
        }
        if (hasAnnotation(classDecl.getModifiers(), "SuperBuilder")) {
            LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=super-builder");
            return 0;
        }
        for (var field : fields) {
            if (hasAnnotation(field.getModifiers(), "Singular")) {
                LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                        + " reason=singular-field");
                return 0;
            }
        }
        for (var annotation : classDecl.getModifiers().getAnnotations()) {
            if (LombokAnnotations.isLombokAnnotation(root, annotation)
                    && LombokAnnotations.simpleName(annotation.getAnnotationType().toString()).equals("Builder")
                    && !annotation.getArguments().isEmpty()) {
                LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                        + " reason=builder-options");
                return 0;
            }
        }
        if (explicitConstructorWritten) {
            LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=explicit-constructor");
            return 0;
        }
        var constructorFields = fields.stream()
                .filter(f -> !((f.mods.flags & Flags.FINAL) != 0 && f.init != null))
                .filter(f -> !(hasAnnotation(classDecl.getModifiers(), "Value")
                        && f.init != null
                        && !hasAnnotation(f.getModifiers(), "NonFinal")))
                .collect(List.collector());
        if (!hasExplicitConstructor(classDecl, constructorFields.size())) {
            LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=no-all-args-constructor");
            return 0;
        }
        if (!classDecl.typarams.isEmpty()) {
            LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=generic-type");
            return 0;
        }
        var builderName = classDecl.getSimpleName().toString() + "Builder";
        if (hasNestedClass(classDecl, builderName)) {
            LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=explicit-builder-type");
            return 0;
        }
        if (hasExplicitMethod(classDecl, "builder", 0)) {
            LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=explicit-builder-method");
            return 0;
        }

        var builderType = names.fromString(builderName);
        var builderFields = fields.stream()
                .map(field -> make.VarDef(
                        make.Modifiers(Flags.PRIVATE | Flags.GENERATED_MEMBER),
                        field.name,
                        cloneType(field.vartype),
                        null))
                .<JCTree>map(field -> field)
                .collect(List.collector());
        var builderMethods = fields.stream()
                .map(field -> createBuilderSetter(builderType, field))
                .<JCTree>map(method -> method)
                .collect(List.collector());
        builderMethods = builderMethods.append(createBuilderBuildMethod(classDecl, constructorFields));
        var builderClass = make.ClassDef(
                make.Modifiers(Flags.PUBLIC | Flags.STATIC | Flags.GENERATED_MEMBER),
                builderType,
                List.nil(),
                null,
                List.nil(),
                builderFields.appendList(builderMethods));
        classDecl.defs = classDecl.defs.append(builderClass);
        classDecl.defs = classDecl.defs.append(createBuilderFactory(builderType));
        return fields.size() + 2;
    }

    private boolean supportsSimpleBuilder(JCClassDecl classDecl, List<JCVariableDecl> fields) {
        if (classDecl.getKind() == Tree.Kind.ENUM
                || classDecl.getKind() == Tree.Kind.RECORD
                || (classDecl.mods.flags & Flags.ABSTRACT) != 0
                || hasAnnotation(classDecl.getModifiers(), "SuperBuilder")) {
            return false;
        }
        for (var field : fields) {
            if (hasAnnotation(field.getModifiers(), "Singular")) return false;
        }
        for (var annotation : classDecl.getModifiers().getAnnotations()) {
            if (LombokAnnotations.isLombokAnnotation(root, annotation)
                    && LombokAnnotations.simpleName(annotation.getAnnotationType().toString()).equals("Builder")
                    && !annotation.getArguments().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private JCMethodDecl createBuilderFactory(Name builderType) {
        var body = make.Block(0L, List.of(
                make.Return(make.NewClass(
                        null, List.nil(), make.Ident(builderType), List.nil(), null))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.STATIC | Flags.GENERATED_MEMBER),
                names.fromString("builder"),
                make.Ident(builderType),
                List.nil(),
                List.nil(),
                List.nil(),
                body,
                null);
    }

    private JCMethodDecl createBuilderSetter(Name builderType, JCVariableDecl field) {
        var param = make.VarDef(
                make.Modifiers(Flags.PARAMETER),
                field.name,
                cloneType(field.vartype),
                null);
        var body = make.Block(0L, List.of(
                make.Exec(make.Assign(
                        make.Select(make.Ident(names.fromString("this")), field.name),
                        make.Ident(field.name))),
                make.Return(make.Ident(names.fromString("this")))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.GENERATED_MEMBER),
                field.name,
                make.Ident(builderType),
                List.nil(),
                List.of(param),
                List.nil(),
                body,
                null);
    }

    private JCMethodDecl createBuilderBuildMethod(JCClassDecl owner, List<JCVariableDecl> fields) {
        var arguments = fields.stream()
                .<JCExpression>map(field -> make.Select(
                        make.Ident(names.fromString("this")), field.name))
                .collect(List.collector());
        var body = make.Block(0L, List.of(
                make.Return(make.NewClass(
                        null,
                        List.nil(),
                        make.Ident(owner.name),
                        arguments,
                        null))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.GENERATED_MEMBER),
                names.fromString("build"),
                make.Ident(owner.name),
                List.nil(),
                List.nil(),
                List.nil(),
                body,
                null);
    }

    private void recurseNested(JCClassDecl classDecl) {
        for (var member : classDecl.defs) {
            if (member instanceof JCClassDecl nested) {
                injectClass(nested);
            }
        }
    }

    private boolean hasField(JCClassDecl classDecl, String name) {
        for (var member : classDecl.defs) {
            if (member instanceof JCVariableDecl field && field.name.contentEquals(name)) return true;
        }
        return false;
    }

    private boolean hasNestedClass(JCClassDecl classDecl, String name) {
        for (var member : classDecl.defs) {
            if (member instanceof JCClassDecl nested && nested.name.contentEquals(name)) return true;
        }
        return false;
    }

    private JCMethodDecl createGetter(
            String methodName, JCExpression fieldType, String fieldName, long accessFlags) {
        var body = make.Block(0L, List.of(
                make.Return(make.Select(make.Ident(names.fromString("this")), names.fromString(fieldName)))));
        return make.MethodDef(
                make.Modifiers(accessFlags | Flags.GENERATED_MEMBER),
                names.fromString(methodName),
                cloneType(fieldType),
                List.nil(),  // type params
                List.nil(),  // params
                List.nil(),  // throws
                body,
                null);
    }

    private JCMethodDecl createSetter(
            String methodName, JCExpression fieldType, String fieldName, long accessFlags) {
        var param = make.VarDef(
                make.Modifiers(Flags.PARAMETER),
                names.fromString(fieldName),
                cloneType(fieldType),
                null);
        var body = make.Block(0L, List.of(
                make.Exec(make.Assign(
                        make.Select(make.Ident(names.fromString("this")), names.fromString(fieldName)),
                        make.Ident(names.fromString(fieldName))))));
        return make.MethodDef(
                make.Modifiers(accessFlags | Flags.GENERATED_MEMBER),
                names.fromString(methodName),
                make.TypeIdent(TypeTag.VOID),
                List.nil(),
                List.of(param),
                List.nil(),
                body,
                null);
    }

    private JCMethodDecl createConstructor(
            List<JCVariableDecl> fields, boolean isEnum, long accessFlags) {
        var params = fields.stream()
                .map(f -> make.VarDef(
                        make.Modifiers(Flags.PARAMETER),
                        f.name,
                        cloneType(f.vartype),
                        null))
                .collect(List.collector());
        var assignments = fields.stream()
                .map(f -> make.Exec(make.Assign(
                        make.Select(make.Ident(names.fromString("this")), f.name),
                        make.Ident(f.name))))
                .<JCStatement>map(s -> s)
                .collect(List.collector());
        var body = make.Block(0L, assignments);
        long accessFlag = isEnum ? Flags.PRIVATE : accessFlags;
        accessFlag |= Flags.GENERATED_MEMBER;
        return make.MethodDef(
                make.Modifiers(accessFlag),
                names.init,
                null,  // constructor has no return type
                List.nil(),
                params,
                List.nil(),
                body,
                null);
    }

    private JCVariableDecl createLogField(String typeName) {
        return make.VarDef(
                make.Modifiers(Flags.PRIVATE | Flags.STATIC | Flags.FINAL | Flags.GENERATED_MEMBER),
                names.fromString(LombokAnnotations.DEFAULT_LOG_FIELD_NAME),
                qualifiedType(typeName),
                make.Literal(TypeTag.BOT, null));
    }

    private JCExpression qualifiedType(String typeName) {
        var parts = typeName.split("\\.");
        JCExpression result = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) result = make.Select(result, names.fromString(parts[i]));
        return result;
    }

    private String loggingType(ModifiersTree mods) {
        for (var annotation : mods.getAnnotations()) {
            if (!LombokAnnotations.isLombokAnnotation(root, annotation)) continue;
            var name = LombokAnnotations.simpleName(annotation.getAnnotationType().toString());
            switch (name) {
                case "XSlf4j": return "org.slf4j.ext.XLogger";
                case "Slf4j": return "org.slf4j.Logger";
                case "Log4j2": return "org.apache.logging.log4j.Logger";
                case "Log4j": return "org.apache.log4j.Logger";
                case "CommonsLog": return "org.apache.commons.logging.Log";
                case "Flogger": return "com.google.common.flogger.FluentLogger";
                case "JBossLog": return "org.jboss.logging.Logger";
                case "Log": return "java.util.logging.Logger";
                case "CustomLog":
                    LOG.info("[lombok-source-compile] logger=unsupported CustomLog");
                    return null;
                default: continue;
            }
        }
        return null;
    }

    /**
     * Clone a type expression tree. TreeMaker nodes can't be shared across declarations,
     * so we recreate simple type references.
     */
    private JCExpression cloneType(JCExpression type) {
        if (type == null) return null;
        if (type instanceof JCPrimitiveTypeTree prim) {
            return make.TypeIdent(prim.typetag);
        }
        if (type instanceof JCIdent ident) {
            return make.Ident(ident.name);
        }
        if (type instanceof JCFieldAccess select) {
            return make.Select(cloneType(select.selected), select.name);
        }
        if (type instanceof JCArrayTypeTree arr) {
            return make.TypeArray(cloneType(arr.elemtype));
        }
        if (type instanceof JCTypeApply apply) {
            var clonedArgs = apply.arguments.stream()
                    .map(this::cloneType)
                    .collect(List.collector());
            return make.TypeApply(cloneType(apply.clazz), clonedArgs);
        }
        return new TreeCopier<Void>(make).copy(type);
    }

    private List<JCVariableDecl> collectInstanceFields(JCClassDecl classDecl) {
        return classDecl.defs.stream()
                .filter(d -> d instanceof JCVariableDecl)
                .map(d -> (JCVariableDecl) d)
                .filter(f -> (f.mods.flags & Flags.STATIC) == 0)
                .filter(f -> (f.mods.flags & Flags.ENUM) == 0)
                .filter(f -> f.vartype != null)
                .collect(List.collector());
    }

    private boolean hasExplicitMethod(JCClassDecl classDecl, String name, int paramCount) {
        for (var member : classDecl.defs) {
            if (member instanceof JCMethodDecl method
                    && method.name.contentEquals(name)
                    && method.params.size() == paramCount) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExplicitConstructor(JCClassDecl classDecl, int paramCount) {
        for (var member : classDecl.defs) {
            if (member instanceof JCMethodDecl method
                    && method.name == names.init
                    && method.params.size() == paramCount) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExplicitConstructorAny(JCClassDecl classDecl) {
        for (var member : classDecl.defs) {
            if (member instanceof JCMethodDecl method && method.name == names.init) {
                return true;
            }
        }
        return false;
    }
}
