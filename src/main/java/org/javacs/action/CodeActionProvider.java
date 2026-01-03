package org.javacs.action;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import org.javacs.*;
import org.javacs.FindTypeDeclarationAt;
import org.javacs.imports.AutoImportProvider;
import org.javacs.lsp.*;
import org.javacs.rewrite.*;

public class CodeActionProvider {
    private final CompilerProvider compiler;
    private final AutoImportProvider autoImportProvider;
    private final CodeActionConfig config;

    public CodeActionProvider(
            CompilerProvider compiler, AutoImportProvider autoImportProvider, CodeActionConfig config) {
        this.compiler = compiler;
        this.autoImportProvider = autoImportProvider;
        this.config = config == null ? CodeActionConfig.defaults() : config;
    }

    public List<CodeAction> codeActionsForCursor(CodeActionParams params) {
        LOG.info(
                String.format(
                        "Find code actions at %s(%d)...",
                        params.textDocument.uri.getPath(), params.range.start.line + 1));
        var started = Instant.now();
        var file = Paths.get(params.textDocument.uri);
        var rewrites = new TreeMap<String, Rewrite>();
        var deferred = new ArrayList<CodeAction>();
        try (var task = compiler.compile(file)) {
            var elapsed = Duration.between(started, Instant.now()).toMillis();
            LOG.info(String.format("...compiled in %d ms", elapsed));
            var root = task.root(file);
            var lines = root.getLineMap();
            var cursor = lines.getPosition(params.range.start.line + 1, params.range.start.character + 1);
            rewrites.putAll(overrideInheritedMethods(task, root, file, cursor));
            deferred.addAll(generateDeferredActions(task, root, cursor));
        }
        var actions = new ArrayList<CodeAction>();
        for (var title : rewrites.keySet()) {
            // TODO are these all quick fixes?
            actions.addAll(createQuickFix(title, rewrites.get(title)));
        }
        actions.addAll(deferred);
        var elapsed = Duration.between(started, Instant.now()).toMillis();
        LOG.info(String.format("...created %d actions in %d ms", actions.size(), elapsed));
        return actions;
    }

    private Map<String, Rewrite> overrideInheritedMethods(
            CompileTask task, CompilationUnitTree root, Path file, long cursor) {
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

    private List<CodeAction> generateDeferredActions(CompileTask task, CompilationUnitTree root, long cursor) {
        var trees = Trees.instance(task.task);
        var classTree = new FindTypeDeclarationAt(task.task).scan(root, cursor);
        if (classTree == null) {
            classTree = firstClass(root);
        }
        if (classTree == null) return List.of();
        var classPath = trees.getPath(root, classTree);
        var classElement = (TypeElement) trees.getElement(classPath);
        var className = classElement == null
                ? qualifiedName(root, classTree)
                : classElement.getQualifiedName().toString();
        if (className == null || className.isBlank()) return List.of();
        var actions = new ArrayList<CodeAction>();
        var includePatterns = new ArrayList<String>();
        for (var p : config.constructor.include) {
            includePatterns.add(p.pattern());
        }
        actions.add(lazyAction("Generate constructor", "constructor", className, includePatterns));
        if (!hasLombok(classTree, LOMBOK_DATA_LIKE, LOMBOK_DATA_PATTERN)) {
            actions.add(lazyAction("Generate toString", "toString", className, null));
            actions.add(lazyAction("Generate equals/hashCode", "equalsHashCode", className, null));
        }
        if (!hasLombok(classTree, LOMBOK_ACCESSORS, LOMBOK_ACCESSOR_PATTERN)) {
            actions.add(lazyAction("Generate getters/setters", "gettersSetters", className, null));
        }
        return actions;
    }

    private boolean hasLombok(ClassTree classTree, Set<String> suffixes, Pattern suffixPattern) {
        if (classTree == null || classTree.getModifiers() == null) return false;
        var anns = classTree.getModifiers().getAnnotations();
        if (anns == null || anns.isEmpty()) return false;
        for (var ann : anns) {
            var name = ann.getAnnotationType().toString();
            var shortName = name.substring(name.lastIndexOf('.') + 1);
            if (suffixPattern.matcher(shortName).find() || suffixPattern.matcher(name).find()) return true;
        }
        return false;
    }

    private static final Set<String> LOMBOK_ACCESSORS =
            Set.of("Data", "Getter", "Setter", "Value");
    private static final Set<String> LOMBOK_DATA_LIKE =
            Set.of("Data", "Value", "ToString", "EqualsAndHashCode");
    private static final Pattern LOMBOK_ACCESSOR_PATTERN =
            Pattern.compile("(Data|Getter|Setter|Value)$");
    private static final Pattern LOMBOK_DATA_PATTERN =
            Pattern.compile("(Data|Value|ToString|EqualsAndHashCode)$");

    private CodeAction lazyAction(String title, String type, String className, List<String> includePatterns) {
        var action = new CodeAction();
        action.title = title;
        action.kind = CodeActionKind.Refactor;
        var data = new JsonObject();
        data.addProperty("type", type);
        if (className != null) data.addProperty("class", className);
        if (includePatterns != null && !includePatterns.isEmpty()) {
            var arr = new JsonArray();
            includePatterns.forEach(arr::add);
            data.add("include", arr);
        }
        action.data = data;
        return action;
    }

    private ClassTree firstClass(CompilationUnitTree root) {
        return new TreeScanner<ClassTree, Void>() {
            @Override
            public ClassTree visitClass(ClassTree t, Void v) {
                return t;
            }

            @Override
            public ClassTree reduce(ClassTree a, ClassTree b) {
                if (a != null) return a;
                return b;
            }
        }.scan(root, null);
    }

    private String qualifiedName(CompilationUnitTree root, ClassTree classTree) {
        var pkg = Objects.toString(root.getPackageName(), "");
        var name = classTree.getSimpleName().toString();
        if (pkg == null || pkg.isBlank()) return name;
        return pkg + "." + name;
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
            var root = task.root(file);
            var actions = new ArrayList<CodeAction>();
            for (var d : params.context.diagnostics) {
                if (d == null || d.range == null || d.range.start == null) {
                    continue;
                }
                var newActions = codeActionForDiagnostic(task, root, file, d);
                actions.addAll(newActions);
            }
            var elapsed = Duration.between(started, Instant.now()).toMillis();
            LOG.info(String.format("...created %d quick fixes in %d ms", actions.size(), elapsed));
            return actions;
        }
    }

    private List<CodeAction> codeActionForDiagnostic(
            CompileTask task, CompilationUnitTree root, Path file, Diagnostic d) {
        // TODO this should be done asynchronously using executeCommand
        if (d == null || d.range == null || d.range.start == null) {
            return List.of();
        }
        switch (d.code) {
            case "unused_local":
                var toStatement = new ConvertVariableToStatement(file, findPosition(root, d.range.start));
                return createQuickFix("Convert to statement", toStatement);
            case "unused_field":
                var toBlock = new ConvertFieldToBlock(file, findPosition(root, d.range.start));
                return createQuickFix("Convert to block", toBlock);
            case "unused_class":
                var removeClass = new RemoveClass(file, findPosition(root, d.range.start));
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
                        var addImport = new AddImport(file, qualifiedName, autoImportProvider);
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
                var missingMethod = new CreateMissingMethod(file, findPosition(root, d.range.start));
                return createQuickFix("Create missing method", missingMethod);
            default:
                return List.of();
        }
    }

    private int findPosition(CompilationUnitTree root, Position position) {
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

    public CodeAction resolve(CodeAction action) {
        if (action == null) return null;
        if (action.edit != null) return action; // already resolved
        var data = GSON.toJsonTree(action.data);
        if (data == null || !data.isJsonObject()) return action;
        var obj = data.getAsJsonObject();
        var type = getString(obj, "type");
        var className = getString(obj, "class");
        List<Pattern> include = config.constructor.include;
        if (obj.has("include") && obj.get("include").isJsonArray()) {
            include = new ArrayList<>();
            for (var el : obj.getAsJsonArray("include")) {
                try {
                    include.add(Pattern.compile(el.getAsString()));
                } catch (Exception e) {
                    // ignore bad pattern
                }
            }
        }
        Rewrite rewrite = null;
        switch (type) {
            case "constructor":
                rewrite = new GenerateConstructor(className, include);
                break;
            case "toString":
                rewrite = new GenerateToString(className);
                break;
            case "equalsHashCode":
                rewrite = new GenerateEqualsHashCode(className);
                break;
            case "gettersSetters":
                rewrite = new GenerateGettersSetters(className);
                break;
            default:
                return action;
        }
        var edits = rewrite.rewrite(compiler);
        if (edits == Rewrite.CANCELLED) return action;
        var ws = new WorkspaceEdit();
        for (var file : edits.keySet()) {
            ws.changes.put(file.toUri(), List.of(edits.get(file)));
        }
        action.edit = ws;
        if (action.kind == null) action.kind = CodeActionKind.Refactor;
        return action;
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static final Gson GSON = new Gson();
    private static final Logger LOG = Logger.getLogger("main");
}
