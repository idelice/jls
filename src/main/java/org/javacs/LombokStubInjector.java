package org.javacs;

import com.sun.source.tree.*;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
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
        var structural = LombokAnnotations.hasStructuralLombokAnnotation(mods);
        var hasData = LombokAnnotations.hasAnnotation(mods, "Data");
        var hasValue = LombokAnnotations.hasAnnotation(mods, "Value");
        var hasNoArgs = LombokAnnotations.hasAnnotation(mods, "NoArgsConstructor");
        var hasAllArgs = LombokAnnotations.hasAnnotation(mods, "AllArgsConstructor");
        var hasRequiredArgs = LombokAnnotations.hasAnnotation(mods, "RequiredArgsConstructor");
        var hasBuilder = LombokAnnotations.hasAnnotation(mods, "Builder");

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
                || !LombokAnnotations.hasLoggingOnlyLombokAnnotation(classDecl.getModifiers())
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
                    classModifiers, field.getModifiers(), fieldName, field.vartype.toString());
            if (accessorInfo.isEmpty()) continue;
            var info = accessorInfo.get();
            if (info.hasGetter() && !hasExplicitMethod(classDecl, info.getterName(), 0)) {
                classDecl.defs = classDecl.defs.append(
                        createGetter(info.getterName(), field.vartype, fieldName));
                injected++;
            }
            if (info.hasSetter() && !hasExplicitMethod(classDecl, info.setterName(), 1)) {
                classDecl.defs = classDecl.defs.append(
                        createSetter(info.setterName(), field.vartype, fieldName));
                injected++;
            }
        }
        return injected;
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
        if (hasNoArgs && !hasExplicitConstructor(classDecl, 0)) {
            classDecl.defs = classDecl.defs.append(createConstructor(List.nil(), isEnum));
            injected++;
        }
        var writtenConstructor = hasExplicitConstructorAny(classDecl);
        var explicitConstructorAnnotation = hasNoArgs || hasRequiredArgs || hasAllArgsAnnotation;
        var valueAllArgs = hasValue && !writtenConstructor && !explicitConstructorAnnotation;
        if (hasAllArgsAnnotation || valueAllArgs || builderAllArgs) {
            var allArgsFields = instanceFields.stream()
                    .filter(f -> !((f.mods.flags & Flags.FINAL) != 0 && f.init != null))
                    .filter(f -> !(hasValue && f.init != null
                            && !LombokAnnotations.hasAnnotation(f.getModifiers(), "NonFinal")))
                    .collect(List.collector());
            if (!hasExplicitConstructor(classDecl, allArgsFields.size())) {
                classDecl.defs = classDecl.defs.append(
                        createConstructor(allArgsFields, isEnum, builderAllArgs));
                injected++;
            }
        }
        if (hasRequiredArgs) {
            var requiredFields = instanceFields.stream()
                    .filter(f -> f.init == null
                            && ((f.mods.flags & Flags.FINAL) != 0
                            || LombokAnnotations.hasAnnotation(f.getModifiers(), "NonNull")))
                    .collect(List.collector());
            if (!hasExplicitConstructor(classDecl, requiredFields.size())) {
                classDecl.defs = classDecl.defs.append(createConstructor(requiredFields, isEnum));
                injected++;
            }
        }
        if (hasData && !hasNoArgs && !hasAllArgsAnnotation && !hasRequiredArgs
                && !hasExplicitConstructorAny(classDecl)) {
            var requiredFields = instanceFields.stream()
                    .filter(f -> f.init == null
                            && ((f.mods.flags & Flags.FINAL) != 0
                            || LombokAnnotations.hasAnnotation(f.getModifiers(), "NonNull")))
                    .collect(List.collector());
            classDecl.defs = classDecl.defs.append(createConstructor(requiredFields, isEnum));
            injected++;
        }
        return injected;
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
        if (LombokAnnotations.hasAnnotation(classDecl.getModifiers(), "SuperBuilder")) {
            LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=super-builder");
            return 0;
        }
        for (var field : fields) {
            if (LombokAnnotations.hasAnnotation(field.getModifiers(), "Singular")) {
                LOG.info("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                        + " reason=singular-field");
                return 0;
            }
        }
        for (var annotation : classDecl.getModifiers().getAnnotations()) {
            if (LombokAnnotations.simpleName(annotation.getAnnotationType().toString()).equals("Builder")
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
                .filter(f -> !(LombokAnnotations.hasAnnotation(classDecl.getModifiers(), "Value")
                        && f.init != null
                        && !LombokAnnotations.hasAnnotation(f.getModifiers(), "NonFinal")))
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
                || LombokAnnotations.hasAnnotation(classDecl.getModifiers(), "SuperBuilder")) {
            return false;
        }
        for (var field : fields) {
            if (LombokAnnotations.hasAnnotation(field.getModifiers(), "Singular")) return false;
        }
        for (var annotation : classDecl.getModifiers().getAnnotations()) {
            if (LombokAnnotations.simpleName(annotation.getAnnotationType().toString()).equals("Builder")
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

    private JCMethodDecl createGetter(String methodName, JCExpression fieldType, String fieldName) {
        var body = make.Block(0L, List.of(
                make.Return(make.Select(make.Ident(names.fromString("this")), names.fromString(fieldName)))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.GENERATED_MEMBER),
                names.fromString(methodName),
                cloneType(fieldType),
                List.nil(),  // type params
                List.nil(),  // params
                List.nil(),  // throws
                body,
                null);
    }

    private JCMethodDecl createSetter(String methodName, JCExpression fieldType, String fieldName) {
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
                make.Modifiers(Flags.PUBLIC | Flags.GENERATED_MEMBER),
                names.fromString(methodName),
                make.TypeIdent(TypeTag.VOID),
                List.nil(),
                List.of(param),
                List.nil(),
                body,
                null);
    }

    private JCMethodDecl createConstructor(List<JCVariableDecl> fields, boolean isEnum) {
        return createConstructor(fields, isEnum, false);
    }

    private JCMethodDecl createConstructor(
            List<JCVariableDecl> fields, boolean isEnum, boolean builderGenerated) {
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
        // Enum constructors must be private; Lombok's implicit builder constructor is package
        // private, while explicit constructor annotations retain the existing public behavior.
        long accessFlag = isEnum ? Flags.PRIVATE : (builderGenerated ? 0L : Flags.PUBLIC);
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
        // Fallback: use the type's string form as an identifier
        return make.Ident(names.fromString(type.toString()));
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
