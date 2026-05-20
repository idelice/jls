package org.javacs.action;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import org.javacs.*;
import org.javacs.FindTypeDeclarationAt;
import org.javacs.lsp.*;
import org.javacs.rewrite.*;

public class CodeActionProvider {
    private final CompilerProvider compiler;

    public CodeActionProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<CodeAction> codeActionsForCursor(CodeActionParams params) {
        LOG.info(
                String.format(
                        "Find code actions at %s(%d)...",
                        params.textDocument.uri.getPath(), params.range.start.line + 1));
        var started = Instant.now();
        var file = Paths.get(params.textDocument.uri);
        // TODO this get-map / convert-to-CodeAction split is an ugly workaround of the fact that we need a new compile
        // task to generate the code actions
        // If we switch to resolving code actions asynchronously using Command, that will fix this problem.
        var rewrites = new TreeMap<String, Rewrite>();
        var variedActions = new ArrayList<VariedAction>();
        var actions = new ArrayList<CodeAction>();
        try (var task = compiler.compile(file)) {
            var elapsed = Duration.between(started, Instant.now()).toMillis();
            LOG.info(String.format("...compiled in %d ms", elapsed));
            var root = task.root(file);
            var lines = root.getLineMap();
            var cursor = lines.getPosition(params.range.start.line + 1, params.range.start.character + 1);

            rewrites.putAll(overrideInheritedMethods(task, file, cursor));

            var hasSelection = params.range.start.line != params.range.end.line
                    || params.range.start.character != params.range.end.character;
            if (hasSelection) {
                var selectionEnd = lines.getPosition(params.range.end.line + 1, params.range.end.character + 1);
                var methodAtEnd = new FindMethodDeclarationAt(task.task).scan(root, selectionEnd);
                var methodAtStart = new FindMethodDeclarationAt(task.task).scan(root, cursor);
                var insideMethod = (methodAtEnd != null || methodAtStart != null);
                if (insideMethod) {
                    var methodDetectPos = (methodAtEnd != null) ? selectionEnd : cursor;
                    var classTree = new FindTypeDeclarationAt(task.task).scan(root, methodDetectPos);
                    if (classTree != null) {
                        var className = qualifiedName(task, root, classTree);
                        var rangeStart =
                                (int)
                                        lines.getPosition(
                                                params.range.start.line + 1,
                                                params.range.start.character + 1);
                        var rangeEnd =
                                (int)
                                        lines.getPosition(
                                                params.range.end.line + 1,
                                                params.range.end.character + 1);

                        variedActions.add(
                                new VariedAction(
                                        "Surround with try-catch",
                                        CodeActionKind.RefactorRewrite,
                                        new CatchException(className, "Exception", rangeStart, rangeEnd)));

                        var typePos = rangeEnd - 1;
                        try {
                            var content = root.getSourceFile().getCharContent(true);
                            while (typePos > rangeStart && typePos < content.length()
                                    && (content.charAt(typePos) == ';'
                                            || Character.isWhitespace(content.charAt(typePos)))) {
                                typePos--;
                            }
                        } catch (IOException e) {
                            // use original position
                        }
                        var type = inferType(task, root, typePos);
                        variedActions.add(
                                new VariedAction(
                                        "Extract to local variable",
                                        CodeActionKind.RefactorExtract,
                                        new ExtractVariable(className, type, rangeStart, rangeEnd)));
                    }
                }
            }

            if (!hasSelection) {
                var classTree = new FindTypeDeclarationAt(task.task).scan(root, cursor);
                if (classTree != null) {
                    var className = qualifiedName(task, root, classTree);
                    for (var kind :
                            new String[] {
                                "constructor", "equals", "hashCode", "toString"
                            }) {
                        variedActions.add(
                                new VariedAction(
                                        "Generate " + kind,
                                        CodeActionKind.Source,
                                        new GenerateMethods(className, kind, (int) cursor)));
                    }
                    if (compiler.lombokPresentOnClasspath()) {
                        for (var ann : LombokAnnotations.KNOWN) {
                            variedActions.add(
                                    new VariedAction(
                                            "Add @" + ann,
                                            CodeActionKind.RefactorRewrite,
                                            new AddLombokAnnotations(
                                                    className, Set.of(ann), (int) cursor)));
                        }
                    }
                    // Field collection for command-based picker
                    var fields = new ArrayList<VariableTree>();
                    for (var member : classTree.getMembers()) {
                        if (member instanceof VariableTree) {
                            var field = (VariableTree) member;
                            if (!field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)) {
                                fields.add(field);
                            }
                        }
                    }
                    // Command-based multi-select getters/setters picker
                    if (!fields.isEmpty()) {
                        var allFieldNames = joinFieldNames(fields);

                        var getterCmd = new Command();
                        getterCmd.command = "java.pickAndGenerate";
                        var getterArgs = new com.google.gson.JsonArray();
                        getterArgs.add(className);
                        getterArgs.add("getters");
                        getterArgs.add(allFieldNames);
                        getterCmd.arguments = getterArgs;
                        var getterAction = new CodeAction();
                        getterAction.title = "Generate getters (pick fields)";
                        getterAction.kind = CodeActionKind.Source;
                        getterAction.command = getterCmd;
                        actions.add(getterAction);

                        // Setters: exclude final fields
                        var nonFinalFields = new ArrayList<VariableTree>();
                        for (var f : fields) {
                            if (!f.getModifiers().getFlags().contains(Modifier.FINAL)) {
                                nonFinalFields.add(f);
                            }
                        }
                        if (!nonFinalFields.isEmpty()) {
                            var setterCmd = new Command();
                            setterCmd.command = "java.pickAndGenerate";
                            var setterArgs = new com.google.gson.JsonArray();
                            setterArgs.add(className);
                            setterArgs.add("setters");
                            setterArgs.add(joinFieldNames(nonFinalFields));
                            setterCmd.arguments = setterArgs;
                            var setterAction = new CodeAction();
                            setterAction.title = "Generate setters (pick fields)";
                            setterAction.kind = CodeActionKind.Source;
                            setterAction.command = setterCmd;
                            actions.add(setterAction);
                        }
                    }
                }
            }
        }
        for (var title : rewrites.keySet()) {
            // TODO are these all quick fixes?
            actions.addAll(createQuickFix(title, rewrites.get(title)));
        }
        for (var va : variedActions) {
            actions.addAll(createAction(va.title, va.kind, va.rewrite));
        }
        var elapsed = Duration.between(started, Instant.now()).toMillis();
        LOG.info(String.format("...created %d actions in %d ms", actions.size(), elapsed));
        return actions;
    }

    private List<CodeAction> createAction(String title, String kind, Rewrite rewrite) {
        var edits = rewrite.rewrite(compiler);
        if (edits == Rewrite.CANCELLED) return List.of();
        var a = new CodeAction();
        a.kind = kind;
        a.title = title;
        a.edit = new WorkspaceEdit();
        for (var file : edits.keySet()) {
            a.edit.changes.put(file.toUri(), List.of(edits.get(file)));
        }
        return List.of(a);
    }

    private JavaType inferType(CompileTask task, CompilationUnitTree root, int pos) {
        var trees = Trees.instance(task.task);
        var sourcePos = trees.getSourcePositions();
        var finder = new TreeAtFinder(sourcePos, root);
        var treeAt = finder.scan(root, (long) pos);
        if (treeAt == null) return new JavaType("Object", new JavaType[0]);
        var path = trees.getPath(root, treeAt);
        if (path == null) return new JavaType("Object", new JavaType[0]);
        var typeMirror = trees.getTypeMirror(path);
        if (typeMirror == null) return new JavaType("Object", new JavaType[0]);
        return new JavaType(simpleTypeName(typeMirror), new JavaType[0]);
    }

    private static String simpleTypeName(TypeMirror type) {
        if (type instanceof DeclaredType declared) {
            var element = (TypeElement) declared.asElement();
            var name = element.getSimpleName().toString();
            if (!declared.getTypeArguments().isEmpty()) {
                var args = new StringJoiner(", ");
                for (var arg : declared.getTypeArguments()) {
                    args.add(simpleTypeName(arg));
                }
                return name + "<" + args + ">";
            }
            return name;
        }
        return type.toString();
    }

    private Map<String, Rewrite> overrideInheritedMethods(CompileTask task, Path file, long cursor) {
        var root = task.root(file);
        if (!isBlankLine(root, cursor)) return Map.of();
        if (isInMethod(task, root, cursor)) return Map.of();
        var methodTree = new FindMethodDeclarationAt(task.task).scan(root, cursor);
        if (methodTree != null) return Map.of();
        var actions = new TreeMap<String, Rewrite>();
        var trees = Trees.instance(task.task);
        var classTree = new FindTypeDeclarationAt(task.task).scan(root, cursor);
        if (classTree == null) return Map.of();
        var classPath = trees.getPath(root, classTree);
        var elements = task.task.getElements();
        var classElement = (TypeElement) trees.getElement(classPath);
        for (var member : elements.getAllMembers(classElement)) {
            if (member.getModifiers().contains(Modifier.FINAL)) continue;
            if (member.getKind() != ElementKind.METHOD) continue;
            var method = (ExecutableElement) member;
            var methodSource = (TypeElement) member.getEnclosingElement();
            if (methodSource.getQualifiedName().contentEquals("java.lang.Object")) continue;
            if (methodSource.equals(classElement)) continue;
            var ptr = new MethodPtr(task.task, method);
            var rewrite =
                    new OverrideInheritedMethod(
                            ptr.className, ptr.methodName, ptr.erasedParameterTypes, file, (int) cursor);
            var title = "Override '" + method.getSimpleName() + "' from " + ptr.className;
            actions.put(title, rewrite);
        }
        return actions;
    }

    private boolean isInMethod(CompileTask task, CompilationUnitTree root, long cursor) {
        var method = new FindMethodDeclarationAt(task.task).scan(root, cursor);
        return method != null;
    }

    private boolean isBlankLine(CompilationUnitTree root, long cursor) {
        var lines = root.getLineMap();
        var line = lines.getLineNumber(cursor);
        var start = lines.getStartPosition(line);
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (var i = start; i < cursor; i++) {
            if (!Character.isWhitespace(contents.charAt((int) i))) {
                return false;
            }
        }
        return true;
    }

    public List<CodeAction> codeActionForDiagnostics(CodeActionParams params) {
        LOG.info(String.format("Check %d diagnostics for quick fixes...", params.context.diagnostics.size()));
        var started = Instant.now();
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler.compile(file)) {
            var actions = new ArrayList<CodeAction>();
            for (var d : params.context.diagnostics) {
                var newActions = codeActionForDiagnostic(task, file, d);
                actions.addAll(newActions);
            }
            var elapsed = Duration.between(started, Instant.now()).toMillis();
            LOG.info(String.format("...created %d quick fixes in %d ms", actions.size(), elapsed));
            return actions;
        }
    }

    private List<CodeAction> codeActionForDiagnostic(CompileTask task, Path file, Diagnostic d) {
        var root = task.root(file);
        // TODO this should be done asynchronously using executeCommand
        switch (d.code) {
            case "unused_local":
                var toStatement = new ConvertVariableToStatement(file, findPosition(task, root, d.range.start));
                return createQuickFix("Convert to statement", toStatement);
            case "unused_field":
                var toBlock = new ConvertFieldToBlock(file, findPosition(task, root, d.range.start));
                return createQuickFix("Convert to block", toBlock);
            case "unused_class":
                var removeClass = new RemoveClass(file, findPosition(task, root, d.range.start));
                return createQuickFix("Remove class", removeClass);
            case "unused_method":
                var unusedMethod = findMethod(task, root, d.range);
                var removeMethod =
                        new RemoveMethod(
                                unusedMethod.className, unusedMethod.methodName, unusedMethod.erasedParameterTypes);
                return createQuickFix("Remove method", removeMethod);
            case "unused_throws":
                var shortExceptionName = extractRange(root, d.range);
                var notThrown = extractNotThrownExceptionName(d.message);
                var methodWithExtraThrow = findMethod(task, root, d.range);
                var removeThrow =
                        new RemoveException(
                                methodWithExtraThrow.className,
                                methodWithExtraThrow.methodName,
                                methodWithExtraThrow.erasedParameterTypes,
                                notThrown);
                return createQuickFix("Remove '" + shortExceptionName + "'", removeThrow);
            case "compiler.warn.unchecked.call.mbr.of.raw.type":
                var warnedMethod = findMethod(task, root, d.range);
                var suppressWarning =
                        new AddSuppressWarningAnnotation(
                                warnedMethod.className, warnedMethod.methodName, warnedMethod.erasedParameterTypes);
                return createQuickFix("Suppress 'unchecked' warning", suppressWarning);
            case "compiler.err.unreported.exception.need.to.catch.or.throw":
                var needsThrow = findMethod(task, root, d.range);
                var exceptionName = extractExceptionName(d.message);
                var addThrows =
                        new AddException(
                                needsThrow.className,
                                needsThrow.methodName,
                                needsThrow.erasedParameterTypes,
                                exceptionName);
                return createQuickFix("Add 'throws'", addThrows);
            case "compiler.err.cant.resolve":
            case "compiler.err.cant.resolve.location":
                var simpleName = extractRange(root, d.range);
                var allImports = new ArrayList<CodeAction>();
                for (var qualifiedName : compiler.publicTopLevelTypes()) {
                    if (qualifiedName.endsWith("." + simpleName)) {
                        var title = "Import '" + qualifiedName + "'";
                        var addImport = new AddImport(file, qualifiedName);
                        allImports.addAll(createQuickFix(title, addImport));
                    }
                }
                return allImports;
            case "compiler.err.var.not.initialized.in.default.constructor":
                var needsConstructor = findClassNeedingConstructor(task, root, d.range);
                if (needsConstructor == null) return List.of();
                var generateConstructor = new GenerateRecordConstructor(needsConstructor);
                return createQuickFix("Generate constructor", generateConstructor);
            case "compiler.err.does.not.override.abstract":
                var missingAbstracts = findClass(task, root, d.range);
                var implementAbstracts = new ImplementAbstractMethods(missingAbstracts);
                return createQuickFix("Implement abstract methods", implementAbstracts);
            case "compiler.err.cant.resolve.location.args":
                var missingMethod = new CreateMissingMethod(file, findPosition(task, root, d.range.start));
                return createQuickFix("Create missing method", missingMethod);
            default:
                return List.of();
        }
    }

    private int findPosition(CompileTask task, CompilationUnitTree root, Position position) {
        var lines = root.getLineMap();
        return (int) lines.getPosition(position.line + 1, position.character + 1);
    }

    private String findClassNeedingConstructor(CompileTask task, CompilationUnitTree root, Range range) {
        var type = findClassTree(task, root, range);
        if (type == null || hasConstructor(task, root, type)) return null;
        return qualifiedName(task, root, type);
    }

    private String findClass(CompileTask task, CompilationUnitTree root, Range range) {
        var type = findClassTree(task, root, range);
        if (type == null) return null;
        return qualifiedName(task, root, type);
    }

    private ClassTree findClassTree(CompileTask task, CompilationUnitTree root, Range range) {
        var position = root.getLineMap().getPosition(range.start.line + 1, range.start.character + 1);
        return new FindTypeDeclarationAt(task.task).scan(root, position);
    }

    private String qualifiedName(CompileTask task, CompilationUnitTree root, ClassTree tree) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(root, tree);
        var type = (TypeElement) trees.getElement(path);
        return type.getQualifiedName().toString();
    }

    private boolean hasConstructor(CompileTask task, CompilationUnitTree root, ClassTree type) {
        for (var member : type.getMembers()) {
            if (member instanceof MethodTree) {
                var method = (MethodTree) member;
                if (isConstructor(task, root, method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isConstructor(CompileTask task, CompilationUnitTree root, MethodTree method) {
        return method.getName().contentEquals("<init>") && !synthentic(task, root, method);
    }

    private boolean synthentic(CompileTask task, CompilationUnitTree root, MethodTree method) {
        return Trees.instance(task.task).getSourcePositions().getStartPosition(root, method) != -1;
    }

    private MethodPtr findMethod(CompileTask task, CompilationUnitTree root, Range range) {
        var trees = Trees.instance(task.task);
        var position = root.getLineMap().getPosition(range.start.line + 1, range.start.character + 1);
        var tree = new FindMethodDeclarationAt(task.task).scan(root, position);
        var path = trees.getPath(root, tree);
        var method = (ExecutableElement) trees.getElement(path);
        return new MethodPtr(task.task, method);
    }

    class MethodPtr {
        String className, methodName;
        String[] erasedParameterTypes;

        MethodPtr(JavacTask task, ExecutableElement method) {
            var types = task.getTypes();
            var parent = (TypeElement) method.getEnclosingElement();
            className = parent.getQualifiedName().toString();
            methodName = method.getSimpleName().toString();
            erasedParameterTypes = new String[method.getParameters().size()];
            for (var i = 0; i < erasedParameterTypes.length; i++) {
                var param = method.getParameters().get(i);
                var type = param.asType();
                var erased = types.erasure(type);
                erasedParameterTypes[i] = erased.toString();
            }
        }
    }

    private static final Pattern NOT_THROWN_EXCEPTION = Pattern.compile("^'((\\w+\\.)*\\w+)' is not thrown");

    private String extractNotThrownExceptionName(String message) {
        var matcher = NOT_THROWN_EXCEPTION.matcher(message);
        if (!matcher.find()) {
            LOG.warning(String.format("`%s` doesn't match `%s`", message, NOT_THROWN_EXCEPTION));
            return "";
        }
        return matcher.group(1);
    }

    private static final Pattern UNREPORTED_EXCEPTION = Pattern.compile("unreported exception ((\\w+\\.)*\\w+)");

    private String extractExceptionName(String message) {
        var matcher = UNREPORTED_EXCEPTION.matcher(message);
        if (!matcher.find()) {
            LOG.warning(String.format("`%s` doesn't match `%s`", message, UNREPORTED_EXCEPTION));
            return "";
        }
        return matcher.group(1);
    }

    private CharSequence extractRange(CompilationUnitTree root, Range range) {
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var start = (int) root.getLineMap().getPosition(range.start.line + 1, range.start.character + 1);
        var end = (int) root.getLineMap().getPosition(range.end.line + 1, range.end.character + 1);
        return contents.subSequence(start, end);
    }

    private List<CodeAction> createQuickFix(String title, Rewrite rewrite) {
        var edits = rewrite.rewrite(compiler);
        if (edits == Rewrite.CANCELLED) {
            return List.of();
        }
        var a = new CodeAction();
        a.kind = CodeActionKind.QuickFix;
        a.title = title;
        a.edit = new WorkspaceEdit();
        for (var file : edits.keySet()) {
            a.edit.changes.put(file.toUri(), List.of(edits.get(file)));
        }
        return List.of(a);
    }

    private static class TreeAtFinder extends TreeScanner<Tree, Long> {
        private final SourcePositions pos;
        private CompilationUnitTree root;

        TreeAtFinder(SourcePositions pos, CompilationUnitTree root) {
            this.pos = pos;
            this.root = root;
        }

        @Override
        public Tree reduce(Tree a, Tree b) {
            return a != null ? a : b;
        }

        @Override
        public Tree scan(Tree tree, Long find) {
            if (tree == null) return null;
            var start = pos.getStartPosition(root, tree);
            var end = pos.getEndPosition(root, tree);
            if (start <= find && find < end) {
                var inner = super.scan(tree, find);
                return inner != null ? inner : tree;
            }
            return null;
        }
    }

    private static String joinFieldNames(List<VariableTree> fields) {
        var sb = new StringBuilder();
        for (var f : fields) {
            if (sb.length() > 0) sb.append(",");
            sb.append(f.getName());
        }
        return sb.toString();
    }

    private record VariedAction(String title, String kind, Rewrite rewrite) {}

    private static final Logger LOG = Logger.getLogger("main");
}
