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
import java.util.Optional;
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
        var hasBuilder = hasAnnotation(mods, "Builder") || hasAnnotation(mods, "SuperBuilder");

        if (classDecl.getKind() == Tree.Kind.INTERFACE
                || classDecl.getKind() == Tree.Kind.ANNOTATION_TYPE) {
            recurseNested(classDecl);
            return;
        }

        if (hasValue) applyValueModifiers(classDecl);

        var accessorCount = injectAccessors(classDecl);
        var constructorCount = 0;
        var builderCount = 0;
        if (structural) {
            var instanceFields = collectInstanceFields(classDecl);
            var explicitConstructorWritten = hasExplicitConstructorAny(classDecl);
            var simpleBuilder = hasBuilder && supportsSimpleBuilder(classDecl);
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
        recurseNested(classDecl);
    }

    private int injectLogger(JCClassDecl classDecl) {
        var config = LombokAnnotations.configFor(root);
        var fieldName = config.logFieldName();
        if ((classDecl.getKind() == Tree.Kind.INTERFACE || classDecl.getKind() == Tree.Kind.ANNOTATION_TYPE)
                || !LombokAnnotations.hasLoggingOnlyLombokAnnotation(root, classDecl.getModifiers())
                || hasField(classDecl, fieldName)) {
            return 0;
        }
        var loggerType = loggingType(classDecl.getModifiers(), config);
        if (loggerType == null) return 0;
        classDecl.defs = classDecl.defs.append(createLogField(loggerType, fieldName));
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
                                accessorFlags(classModifiers, field.getModifiers(), "Setter"),
                                info.chain() ? classDecl : null));
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
            LOG.fine("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=unsupported-class-kind");
            return 0;
        }
        if (hasAnnotation(classDecl.getModifiers(), "SuperBuilder")) {
            return injectSuperBuilder(classDecl, fields);
        }
        if (explicitConstructorWritten) {
            LOG.fine("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
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
            LOG.fine("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=no-all-args-constructor");
            return 0;
        }
        if (!classDecl.typarams.isEmpty()) {
            LOG.fine("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=generic-type");
            return 0;
        }
        var options = LombokAnnotations.builderOptions(
                root, classDecl.getModifiers(), classDecl.getSimpleName().toString());
        var builderName = options.builderClassName();
        if (hasNestedClass(classDecl, builderName)) {
            LOG.fine("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=explicit-builder-type");
            return 0;
        }
        if (hasExplicitMethod(classDecl, options.builderMethodName(), 0)) {
            LOG.fine("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=explicit-builder-method");
            return 0;
        }

        var builderType = names.fromString(builderName);
        var members = new java.util.ArrayList<JCTree>();
        var injected = 0;
        for (var field : fields) {
            var singular = singularValue(field);
            if (singular.isPresent()) {
                // @Singular: add per-element adder(s), a bulk setter and a clear method.
                var typeArgs = typeArguments(field.vartype);
                var adder = LombokAnnotations.prefixedBuilderName(
                        options.setterPrefix(),
                        LombokAnnotations.singularName(singular.get(), field.name.toString()));
                if (typeArgs.size() == 2) {
                    members.add(createMapAdder(builderType, adder, typeArgs.get(0), typeArgs.get(1)));
                    members.add(createBulkSetter(builderType, prefixed(options, field.name.toString()),
                            field.name, wildcardMap(typeArgs.get(0), typeArgs.get(1))));
                } else {
                    var element = typeArgs.isEmpty() ? objectType() : typeArgs.get(0);
                    members.add(createElementAdder(builderType, adder, element));
                    members.add(createBulkSetter(builderType, prefixed(options, field.name.toString()),
                            field.name, wildcardCollection(element)));
                }
                members.add(createClearMethod(builderType, "clear" + LombokAnnotations.capitalize(field.name.toString())));
                injected += 3;
            } else {
                members.add(make.VarDef(
                        make.Modifiers(Flags.PRIVATE | Flags.GENERATED_MEMBER),
                        field.name, cloneType(field.vartype), null));
                members.add(createBuilderSetter(builderType, prefixed(options, field.name.toString()), field));
                injected += 2;
            }
        }
        members.add(createBuilderBuildMethod(classDecl, options.buildMethodName()));
        var builderClass = make.ClassDef(
                make.Modifiers(Flags.PUBLIC | Flags.STATIC | Flags.GENERATED_MEMBER),
                builderType,
                List.nil(),
                null,
                List.nil(),
                List.from(members));
        classDecl.defs = classDecl.defs.append(builderClass);
        classDecl.defs = classDecl.defs.append(
                createBuilderFactory(options.builderMethodName(), builderType));
        injected += 2;
        if (options.toBuilder()) {
            classDecl.defs = classDecl.defs.append(createToBuilder(builderType));
            injected++;
        }
        return injected;
    }

    /**
     * Models {@code @SuperBuilder}. Reproduces Lombok's self-referencing builder shape so the
     * consumer resolves: an abstract nested {@code <Owner>Builder<C extends Owner, B extends
     * <Owner>Builder<C,B>>} whose per-field setters return {@code B}, a {@code builder()} factory
     * returning {@code <Owner>Builder<?,?>}, and abstract {@code self()}/{@code build()}. When the
     * class extends another type the builder extends {@code <Super>.<Super>Builder<C,B>}, so the
     * parent's setters are inherited without any cross-file lookup (Lombok requires the parent to
     * also be {@code @SuperBuilder}, which supplies that nested type on the source path).
     *
     * <p>Only naming options that also apply to {@code @SuperBuilder} would matter here; the fixture
     * needs none, so this uses Lombok's defaults ({@code builder}/{@code build}, {@code <Type>Builder},
     * no setter prefix). If custom options are ever needed, extend via {@link
     * LombokAnnotations#builderOptions}.
     */
    private int injectSuperBuilder(JCClassDecl classDecl, List<JCVariableDecl> fields) {
        var options = LombokAnnotations.builderOptions(
                root, classDecl.getModifiers(), classDecl.getSimpleName().toString());
        var builderName = options.builderClassName();
        if (hasNestedClass(classDecl, builderName)
                || hasExplicitMethod(classDecl, options.builderMethodName(), 0)) {
            LOG.fine("[lombok-source-compile] builder=skipped class=" + classDecl.getSimpleName()
                    + " reason=explicit-super-builder");
            return 0;
        }
        var owner = classDecl.getSimpleName().toString();
        var builderType = names.fromString(builderName);
        var cName = names.fromString("C");
        var bName = names.fromString("B");

        // <C extends Owner, B extends OwnerBuilder<C,B>>
        var cParam = make.TypeParameter(cName, List.of(make.Ident(classDecl.name)));
        var bParam = make.TypeParameter(
                bName,
                List.of(make.TypeApply(
                        make.Ident(builderType), List.of(make.Ident(cName), make.Ident(bName)))));

        // extends Super.SuperBuilder<C,B> when there is a (non-Object) superclass.
        JCExpression extendsClause = null;
        var superName = superclassSimpleName(classDecl);
        if (superName != null) {
            var superBuilder = make.Select(
                    make.Ident(names.fromString(superName)),
                    names.fromString(superName + "Builder"));
            extendsClause = make.TypeApply(
                    superBuilder, List.of(make.Ident(cName), make.Ident(bName)));
        }

        var members = new java.util.ArrayList<JCTree>();
        var injected = 0;
        // Per-field setters returning B (the concrete builder subtype).
        for (var field : fields) {
            var param = make.VarDef(
                    make.Modifiers(Flags.PARAMETER), field.name, cloneType(field.vartype), null);
            var body = make.Block(0L, List.of(
                    make.Return(make.Apply(
                            List.nil(), make.Ident(names.fromString("self")), List.nil()))));
            members.add(make.MethodDef(
                    make.Modifiers(Flags.PUBLIC | Flags.GENERATED_MEMBER),
                    names.fromString(prefixed(options, field.name.toString())),
                    make.Ident(bName),
                    List.nil(), List.of(param), List.nil(),
                    body, null));
            injected++;
        }
        // protected abstract B self();
        members.add(make.MethodDef(
                make.Modifiers(Flags.PROTECTED | Flags.ABSTRACT | Flags.GENERATED_MEMBER),
                names.fromString("self"),
                make.Ident(bName),
                List.nil(), List.nil(), List.nil(),
                null, null));
        // public abstract C build();
        members.add(make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.ABSTRACT | Flags.GENERATED_MEMBER),
                names.fromString(options.buildMethodName()),
                make.Ident(cName),
                List.nil(), List.nil(), List.nil(),
                null, null));

        var builderClass = make.ClassDef(
                make.Modifiers(Flags.PUBLIC | Flags.STATIC | Flags.ABSTRACT | Flags.GENERATED_MEMBER),
                builderType,
                List.of(cParam, bParam),
                extendsClause,
                List.nil(),
                List.from(members));
        classDecl.defs = classDecl.defs.append(builderClass);
        injected++;

        // public static OwnerBuilder<?,?> builder() { return null; }
        var wildcardBuilder = make.TypeApply(
                make.Ident(builderType),
                List.of(make.Wildcard(make.TypeBoundKind(com.sun.tools.javac.code.BoundKind.UNBOUND), null),
                        make.Wildcard(make.TypeBoundKind(com.sun.tools.javac.code.BoundKind.UNBOUND), null)));
        classDecl.defs = classDecl.defs.append(make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.STATIC | Flags.GENERATED_MEMBER),
                names.fromString(options.builderMethodName()),
                wildcardBuilder,
                List.nil(), List.nil(), List.nil(),
                make.Block(0L, List.of(make.Return(make.Literal(TypeTag.BOT, null)))),
                null));
        injected++;

        LOG.fine("[lombok-source-compile] super-builder class=" + owner
                + " super=" + (superName == null ? "-" : superName)
                + " setters=" + fields.size());
        return injected;
    }

    /** Simple name of the extends clause, or null when the class extends Object/nothing. */
    private String superclassSimpleName(JCClassDecl classDecl) {
        var extending = classDecl.extending;
        if (extending == null) return null;
        var name = simpleTypeName(extending);
        return name == null || name.equals("Object") ? null : name;
    }

    private String simpleTypeName(JCExpression type) {
        if (type instanceof JCTypeApply apply) return simpleTypeName(apply.clazz);
        if (type instanceof JCFieldAccess select) return select.name.toString();
        if (type instanceof JCIdent ident) return ident.name.toString();
        return null;
    }

    private String prefixed(LombokAnnotations.BuilderOptions options, String fieldName) {
        return LombokAnnotations.prefixedBuilderName(options.setterPrefix(), fieldName);
    }

    /** Returns the {@code @Singular} explicit value ("" if none), or empty if not @Singular. */
    private java.util.Optional<String> singularValue(JCVariableDecl field) {
        for (var annotation : field.getModifiers().getAnnotations()) {
            if (!LombokAnnotations.isLombokAnnotation(root, annotation)
                    || !LombokAnnotations.simpleName(annotation.getAnnotationType().toString())
                            .equals("Singular")) {
                continue;
            }
            for (var argument : annotation.getArguments()) {
                if (argument instanceof LiteralTree literal
                        && literal.getValue() instanceof String value) {
                    return java.util.Optional.of(value);
                }
                if (argument instanceof AssignmentTree assignment
                        && assignment.getVariable().toString().equals("value")
                        && assignment.getExpression() instanceof LiteralTree literal
                        && literal.getValue() instanceof String value) {
                    return java.util.Optional.of(value);
                }
            }
            return Optional.of("");
        }
        return Optional.empty();
    }

    private List<JCExpression> typeArguments(JCExpression type) {
        if (type instanceof JCTypeApply apply) return apply.arguments;
        return List.nil();
    }

    private JCExpression objectType() {
        return make.Ident(names.fromString("Object"));
    }

    /** {@code java.util.Collection<? extends E>}. */
    private JCExpression wildcardCollection(JCExpression element) {
        return make.TypeApply(qualifiedType("java.util.Collection"), List.of(extendsWildcard(element)));
    }

    /** {@code java.util.Map<? extends K, ? extends V>}. */
    private JCExpression wildcardMap(JCExpression key, JCExpression value) {
        return make.TypeApply(
                qualifiedType("java.util.Map"),
                List.of(extendsWildcard(key), extendsWildcard(value)));
    }

    private JCExpression extendsWildcard(JCExpression bound) {
        return make.Wildcard(
                make.TypeBoundKind(com.sun.tools.javac.code.BoundKind.EXTENDS), cloneType(bound));
    }

    /** Lombok itself rejects a plain @Builder on these; @SuperBuilder is generated separately. */
    private boolean supportsSimpleBuilder(JCClassDecl classDecl) {
        return classDecl.getKind() != Tree.Kind.ENUM
                && classDecl.getKind() != Tree.Kind.RECORD
                && (classDecl.mods.flags & Flags.ABSTRACT) == 0
                && !hasAnnotation(classDecl.getModifiers(), "SuperBuilder");
    }

    private JCMethodDecl createBuilderFactory(String factoryName, Name builderType) {
        var body = make.Block(0L, List.of(
                make.Return(make.NewClass(null, List.nil(), make.Ident(builderType), List.nil(), null))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.STATIC | Flags.GENERATED_MEMBER),
                names.fromString(factoryName),
                make.Ident(builderType),
                List.nil(), List.nil(), List.nil(),
                body, null);
    }

    /** Instance {@code toBuilder()} returning the builder type. */
    private JCMethodDecl createToBuilder(Name builderType) {
        var body = make.Block(0L, List.of(
                make.Return(make.NewClass(null, List.nil(), make.Ident(builderType), List.nil(), null))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.GENERATED_MEMBER),
                names.fromString("toBuilder"),
                make.Ident(builderType),
                List.nil(), List.nil(), List.nil(),
                body, null);
    }

    private JCMethodDecl createBuilderSetter(Name builderType, String methodName, JCVariableDecl field) {
        var param = make.VarDef(
                make.Modifiers(Flags.PARAMETER), field.name, cloneType(field.vartype), null);
        return builderReturningMethod(builderType, methodName, List.of(param));
    }

    /** Singular collection adder: {@code Builder withElement(E element)}. */
    private JCMethodDecl createElementAdder(Name builderType, String methodName, JCExpression element) {
        var param = make.VarDef(
                make.Modifiers(Flags.PARAMETER), names.fromString("item"), cloneType(element), null);
        return builderReturningMethod(builderType, methodName, List.of(param));
    }

    /** Singular map adder: {@code Builder withEntry(K key, V value)}. */
    private JCMethodDecl createMapAdder(
            Name builderType, String methodName, JCExpression key, JCExpression value) {
        var keyParam = make.VarDef(
                make.Modifiers(Flags.PARAMETER), names.fromString("key"), cloneType(key), null);
        var valueParam = make.VarDef(
                make.Modifiers(Flags.PARAMETER), names.fromString("value"), cloneType(value), null);
        return builderReturningMethod(builderType, methodName, List.of(keyParam, valueParam));
    }

    /** Bulk setter accepting a wildcard Collection/Map. */
    private JCMethodDecl createBulkSetter(
            Name builderType, String methodName, Name paramName, JCExpression paramType) {
        var param = make.VarDef(make.Modifiers(Flags.PARAMETER), paramName, paramType, null);
        return builderReturningMethod(builderType, methodName, List.of(param));
    }

    private JCMethodDecl createClearMethod(Name builderType, String methodName) {
        return builderReturningMethod(builderType, methodName, List.nil());
    }

    /** A builder method returning {@code this} (the builder), with the given params. */
    private JCMethodDecl builderReturningMethod(
            Name builderType, String methodName, List<JCVariableDecl> params) {
        var body = make.Block(0L, List.of(make.Return(make.Ident(names.fromString("this")))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.GENERATED_MEMBER),
                names.fromString(methodName),
                make.Ident(builderType),
                List.nil(), params, List.nil(),
                body, null);
    }

    private JCMethodDecl createBuilderBuildMethod(JCClassDecl owner, String buildMethodName) {
        var body = make.Block(0L, List.of(make.Return(make.Literal(TypeTag.BOT, null))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.GENERATED_MEMBER),
                names.fromString(buildMethodName),
                make.Ident(owner.name),
                List.nil(), List.nil(), List.nil(),
                body, null);
    }

    /**
     * Applies the implicit modifiers Lombok's {@code @Value} adds: the class becomes {@code final}
     * and every non-static instance field becomes {@code private final} (unless {@code @NonFinal}).
     * This only affects diagnostics (e.g. reassigning a value field is an error).
     */
    private void applyValueModifiers(JCClassDecl classDecl) {
        if (!hasAnnotation(classDecl.getModifiers(), "NonFinal")) {
            classDecl.mods.flags |= Flags.FINAL;
        }
        for (var member : classDecl.defs) {
            if (!(member instanceof JCVariableDecl field)
                    || (field.mods.flags & Flags.STATIC) != 0
                    || (field.mods.flags & Flags.ENUM) != 0
                    || hasAnnotation(field.getModifiers(), "NonFinal")) {
                continue;
            }
            field.mods.flags |= Flags.FINAL;
            if ((field.mods.flags & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE)) == 0) {
                field.mods.flags |= Flags.PRIVATE;
            }
        }
    }

    private void recurseNested(JCClassDecl classDecl) {        for (var member : classDecl.defs) {
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
            String methodName, JCExpression fieldType, String fieldName, long accessFlags,
            JCClassDecl chainOwner) {
        var param = make.VarDef(
                make.Modifiers(Flags.PARAMETER),
                names.fromString(fieldName),
                cloneType(fieldType),
                null);
        var assign = make.Exec(make.Assign(
                make.Select(make.Ident(names.fromString("this")), names.fromString(fieldName)),
                make.Ident(names.fromString(fieldName))));
        var statements = chainOwner == null
                ? List.<JCStatement>of(assign)
                : List.<JCStatement>of(assign, make.Return(make.Ident(names.fromString("this"))));
        var returnType = chainOwner == null
                ? make.TypeIdent(TypeTag.VOID)
                : ownerType(chainOwner);
        return make.MethodDef(
                make.Modifiers(accessFlags | Flags.GENERATED_MEMBER),
                names.fromString(methodName),
                returnType,
                List.nil(),
                List.of(param),
                List.nil(),
                make.Block(0L, statements),
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

    private JCVariableDecl createLogField(String typeName, String fieldName) {
        return make.VarDef(
                make.Modifiers(Flags.PRIVATE | Flags.STATIC | Flags.FINAL | Flags.GENERATED_MEMBER),
                names.fromString(fieldName),
                qualifiedType(typeName),
                make.Literal(TypeTag.BOT, null));
    }

    private JCExpression qualifiedType(String typeName) {
        var parts = typeName.split("\\.");
        JCExpression result = make.Ident(names.fromString(parts[0]));
        for (int i = 1; i < parts.length; i++) result = make.Select(result, names.fromString(parts[i]));
        return result;
    }

    private String loggingType(ModifiersTree mods, LombokAnnotations.LombokConfig config) {
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
                    if (config.customLogType() != null) return config.customLogType();
                    LOG.fine("[lombok-source-compile] logger=unsupported CustomLog (no lombok.log.custom.declaration)");
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
