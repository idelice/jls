package org.javacs;

import com.sun.source.tree.*;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import java.util.logging.Logger;
import javax.lang.model.element.Modifier;

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
class LombokStubInjector {
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
    void inject(CompilationUnitTree root) {
        if (!LombokAnnotations.hasStructuralLombokAnnotation(root)
                && !LombokAnnotations.hasLogAnnotation(root)) {
            return;
        }
        var unit = (JCCompilationUnit) root;
        for (var def : unit.defs) {
            if (def instanceof JCClassDecl classDecl) {
                injectClass(classDecl);
            }
        }
    }

    private void injectClass(JCClassDecl classDecl) {
        var mods = classDecl.getModifiers();
        make.at(classDecl.pos);

        // @Slf4j / @Log — inject log field
        if (LombokAnnotations.hasLoggingOnlyLombokAnnotation(mods)) {
            classDecl.defs = classDecl.defs.append(createLogField(classDecl));
        }

        // Skip non-structural annotations
        if (!LombokAnnotations.hasStructuralLombokAnnotation(mods)) {
            return;
        }

        var hasData = LombokAnnotations.hasAnnotation(mods, "Data");
        var hasValue = LombokAnnotations.hasAnnotation(mods, "Value");
        var hasGetter = LombokAnnotations.hasAnnotation(mods, "Getter") || hasData || hasValue;
        var hasSetter = LombokAnnotations.hasAnnotation(mods, "Setter") || hasData;
        var hasNoArgs = LombokAnnotations.hasAnnotation(mods, "NoArgsConstructor");
        var hasAllArgs = LombokAnnotations.hasAnnotation(mods, "AllArgsConstructor") || hasValue;
        var hasRequiredArgs = LombokAnnotations.hasAnnotation(mods, "RequiredArgsConstructor");
        var hasBuilder = LombokAnnotations.hasAnnotation(mods, "Builder");

        // Don't inject into interfaces/annotations
        if (classDecl.getKind() == Tree.Kind.INTERFACE
                || classDecl.getKind() == Tree.Kind.ANNOTATION_TYPE) {
            return;
        }

        var injected = 0;

        // Inject getters/setters per field
        for (var member : classDecl.defs) {
            if (!(member instanceof JCVariableDecl field)) continue;
            if ((field.mods.flags & Flags.STATIC) != 0) continue;
            if ((field.mods.flags & Flags.ENUM) != 0) continue;

            var fieldName = field.getName().toString();
            var fieldType = field.vartype;
            if (fieldType == null || fieldName.isBlank()) continue;

            var fieldMods = field.getModifiers();
            var accessorInfo = LombokAnnotations.accessorInfo(mods, fieldMods, fieldName, fieldType.toString());
            if (accessorInfo.isEmpty()) continue;

            var info = accessorInfo.get();
            if (info.hasGetter() && !hasExplicitMethod(classDecl, info.getterName(), 0)) {
                classDecl.defs = classDecl.defs.append(createGetter(info.getterName(), fieldType, fieldName));
                injected++;
            }
            if (info.hasSetter() && !hasExplicitMethod(classDecl, info.setterName(), 1)) {
                classDecl.defs = classDecl.defs.append(createSetter(info.setterName(), fieldType, fieldName));
                injected++;
            }
        }

        // Inject constructors
        var instanceFields = collectInstanceFields(classDecl);
        var isEnum = classDecl.getKind() == Tree.Kind.ENUM;

        if (hasNoArgs && !hasExplicitConstructor(classDecl, 0)) {
            classDecl.defs = classDecl.defs.append(createConstructor(List.nil(), isEnum));
            injected++;
        }
        if (hasAllArgs && !hasExplicitConstructor(classDecl, instanceFields.size())) {
            classDecl.defs = classDecl.defs.append(createConstructor(instanceFields, isEnum));
            injected++;
        }
        if (hasRequiredArgs) {
            var requiredFields = instanceFields.stream()
                    .filter(f -> (f.mods.flags & Flags.FINAL) != 0 && f.init == null)
                    .collect(List.collector());
            if (!hasExplicitConstructor(classDecl, requiredFields.size())) {
                classDecl.defs = classDecl.defs.append(createConstructor(requiredFields, isEnum));
                injected++;
            }
        }
        if (hasData && !hasNoArgs && !hasAllArgs && !hasRequiredArgs) {
            // @Data = @RequiredArgsConstructor (if no explicit constructor)
            if (!hasExplicitConstructorAny(classDecl)) {
                var requiredFields = instanceFields.stream()
                        .filter(f -> (f.mods.flags & Flags.FINAL) != 0 && f.init == null)
                        .collect(List.collector());
                classDecl.defs = classDecl.defs.append(createConstructor(requiredFields, isEnum));
                injected++;
            }
        }

        if (injected > 0) {
            LOG.fine(String.format("[lombok-plugin] injected %d stubs into %s",
                    injected, classDecl.getSimpleName()));
        }

        // Recurse into nested classes
        for (var member : classDecl.defs) {
            if (member instanceof JCClassDecl nested) {
                injectClass(nested);
            }
        }
    }

    private JCMethodDecl createGetter(String methodName, JCExpression fieldType, String fieldName) {
        var body = make.Block(0L, List.of(
                make.Return(make.Select(make.Ident(names.fromString("this")), names.fromString(fieldName)))));
        return make.MethodDef(
                make.Modifiers(Flags.PUBLIC | Flags.SYNTHETIC),
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
                make.Modifiers(Flags.PUBLIC | Flags.SYNTHETIC),
                names.fromString(methodName),
                make.TypeIdent(TypeTag.VOID),
                List.nil(),
                List.of(param),
                List.nil(),
                body,
                null);
    }

    private JCMethodDecl createConstructor(List<JCVariableDecl> fields, boolean isEnum) {
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
        // Enum constructors must be private; regular classes get public
        long accessFlag = isEnum ? Flags.PRIVATE : Flags.PUBLIC;
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

    private JCVariableDecl createLogField(JCClassDecl classDecl) {
        // private static final org.slf4j.Logger log
        var loggerType = make.Select(
                make.Select(
                        make.Select(make.Ident(names.fromString("org")), names.fromString("slf4j")),
                        names.fromString("Logger")),
                names.empty);
        // Simpler: just use the qualified name as a single select chain
        var org = make.Ident(names.fromString("org"));
        var slf4j = make.Select(org, names.fromString("slf4j"));
        var logger = make.Select(slf4j, names.fromString("Logger"));
        return make.VarDef(
                make.Modifiers(Flags.PRIVATE | Flags.STATIC | Flags.FINAL),
                names.fromString(LombokAnnotations.logFieldName(null)),
                logger,
                make.Literal(TypeTag.BOT, null));
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
