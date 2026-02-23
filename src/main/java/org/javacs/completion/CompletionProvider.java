package org.javacs.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.CompletionData;
import org.javacs.FileStore;
import org.javacs.JsonHelper;
import org.javacs.LombokHandler;
import org.javacs.LombokMetadataCache;
import org.javacs.ParseTask;
import org.javacs.SourceFileObject;
import org.javacs.StringSearch;
import org.javacs.lsp.Command;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.InsertTextFormat;
import org.javacs.lsp.TextEdit;
import org.javacs.rewrite.AddImport;

public class CompletionProvider {
    private final CompilerProvider compiler;
    private final LombokMetadataCache lombokCache;

    public static final CompletionList NOT_SUPPORTED = new CompletionList(false, List.of());
    public static final int MAX_COMPLETION_ITEMS = 50;

    private static final String[] TOP_LEVEL_KEYWORDS = {
        "package",
        "import",
        "public",
        "private",
        "protected",
        "abstract",
        "class",
        "interface",
        "@interface",
        "extends",
        "implements",
    };

    private static final String[] CLASS_BODY_KEYWORDS = {
        "public",
        "private",
        "protected",
        "static",
        "final",
        "native",
        "synchronized",
        "abstract",
        "default",
        "class",
        "interface",
        "void",
        "boolean",
        "int",
        "long",
        "float",
        "double",
    };

    private static final String[] METHOD_BODY_KEYWORDS = {
        "new",
        "assert",
        "try",
        "catch",
        "finally",
        "throw",
        "return",
        "break",
        "case",
        "continue",
        "default",
        "do",
        "while",
        "for",
        "switch",
        "if",
        "else",
        "instanceof",
        "var",
        "final",
        "class",
        "void",
        "boolean",
        "int",
        "long",
        "float",
        "double",
    };

    public CompletionProvider(CompilerProvider compiler, LombokMetadataCache lombokCache) {
        this.compiler = compiler;
        this.lombokCache = lombokCache;
    }

    public CompletionList complete(Path file, int line, int column) {
        LOG.info("Complete at " + file.getFileName() + "(" + line + "," + column + ")...");
        var started = Instant.now();
        var task = compiler.parse(file);
        var cursor = task.root.getLineMap().getPosition(line, column);
        var contents = new PruneMethodBodies(task.task).scan(task.root, cursor);
        var endOfLine = endOfLine(contents, (int) cursor);
        contents.insert(endOfLine, ';');
        var list = compileAndComplete(file, contents.toString(), cursor);
        addTopLevelSnippets(task, list);
        logCompletionTiming(started, list.items, list.isIncomplete);
        return list;
    }

    private int endOfLine(CharSequence contents, int cursor) {
        while (cursor < contents.length()) {
            var c = contents.charAt(cursor);
            if (c == '\r' || c == '\n') break;
            cursor++;
        }
        return cursor;
    }

    private CompletionList compileAndComplete(Path file, String contents, long cursor) {
        var started = Instant.now();
        var source = new SourceFileObject(file, contents, Instant.now());
        var partial = partialIdentifier(contents, (int) cursor);
        var endsWithParen = endsWithParen(contents, (int) cursor);
        try (var task = compiler.compile(List.of(source))) {
            LOG.info("...compiled in " + Duration.between(started, Instant.now()).toMillis() + "ms");
            var path = new FindCompletionsAt(task.task).scan(task.root(), cursor);
            switch (path.getLeaf().getKind()) {
                case IDENTIFIER:
                    return completeIdentifier(task, path, partial, endsWithParen);
                case MEMBER_SELECT:
                    return completeMemberSelect(task, path, partial, endsWithParen);
                case MEMBER_REFERENCE:
                    return completeMemberReference(task, path, partial);
                case SWITCH:
                    return completeSwitchConstant(task, path, partial);
                case IMPORT:
                    return completeImport(qualifiedPartialIdentifier(contents, (int) cursor));
                default:
                    var list = new CompletionList();
                    addKeywords(path, partial, list);
                    return list;
            }
        }
    }

    private void addTopLevelSnippets(ParseTask task, CompletionList list) {
        var file = Paths.get(task.root.getSourceFile().toUri());
        if (!hasTypeDeclaration(task.root)) {
            list.items.add(classSnippet(file));
            if (task.root.getPackage() == null) {
                list.items.add(packageSnippet(file));
            }
        }
    }

    private boolean hasTypeDeclaration(CompilationUnitTree root) {
        for (var tree : root.getTypeDecls()) {
            if (tree.getKind() != Tree.Kind.ERRONEOUS) {
                return true;
            }
        }
        return false;
    }

    private CompletionItem packageSnippet(Path file) {
        var name = FileStore.suggestedPackageName(file);
        return snippetItem("package " + name, "package " + name + ";\n\n");
    }

    private CompletionItem classSnippet(Path file) {
        var name = file.getFileName().toString();
        name = name.substring(0, name.length() - ".java".length());
        return snippetItem("class " + name, "class " + name + " {\n    $0\n}");
    }

    private String partialIdentifier(String contents, int end) {
        var start = end;
        while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private boolean endsWithParen(String contents, int cursor) {
        for (var i = cursor; i < contents.length(); i++) {
            if (!Character.isJavaIdentifierPart(contents.charAt(i))) {
                return contents.charAt(i) == '(';
            }
        }
        return false;
    }

    private String qualifiedPartialIdentifier(String contents, int end) {
        var start = end;
        while (start > 0 && isQualifiedIdentifierChar(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private boolean isQualifiedIdentifierChar(char c) {
        return c == '.' || Character.isJavaIdentifierPart(c);
    }

    private CompletionList completeIdentifier(CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        LOG.info("...complete identifiers");
        var list = new CompletionList();
        list.items = completeUsingScope(task, path, partial, endsWithParen);
        LombokHandler.addScopeCompletions(task, path, partial, list.items, lombokCache);
        addStaticImports(task, path.getCompilationUnit(), partial, endsWithParen, list);
        if (!list.isIncomplete && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), Trees.instance(task.task).getSourcePositions(), partial, list);
        }
        addKeywords(path, partial, list);
        return list;
    }

    private void addKeywords(TreePath path, String partial, CompletionList list) {
        var level = findKeywordLevel(path);
        String[] keywords = {};
        if (level instanceof CompilationUnitTree) {
            keywords = TOP_LEVEL_KEYWORDS;
        } else if (level instanceof ClassTree) {
            keywords = CLASS_BODY_KEYWORDS;
        } else if (level instanceof MethodTree) {
            keywords = METHOD_BODY_KEYWORDS;
        }
        for (var k : keywords) {
            if (StringSearch.matchesPartialName(k, partial)) {
                list.items.add(keyword(k));
            }
        }
    }

    private Tree findKeywordLevel(TreePath path) {
        while (path != null) {
            if (path.getLeaf() instanceof CompilationUnitTree
                    || path.getLeaf() instanceof ClassTree
                    || path.getLeaf() instanceof MethodTree) {
                return path.getLeaf();
            }
            path = path.getParentPath();
        }
        throw new RuntimeException("empty path");
    }

    private List<CompletionItem> completeUsingScope(
            CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        var trees = Trees.instance(task.task);
        var list = new ArrayList<CompletionItem>();
        var methods = new HashMap<String, List<ExecutableElement>>();
        var methodPriority = new HashMap<String, Integer>();
        var scope = trees.getScope(path);
        Predicate<CharSequence> filter = name -> StringSearch.matchesPartialName(name, partial);
        for (var scoped : ScopeHelper.scopedMembers(task, scope, filter)) {
            var member = scoped.element;
            var priority = priorityForScopedMember(scoped);
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods, methodPriority, priority);
            } else {
                list.add(item(task, member, priority));
            }
        }
        for (var entry : methods.entrySet()) {
            var overloads = entry.getValue();
            var priority = methodPriority.getOrDefault(entry.getKey(), Priority.METHOD);
            list.add(method(task, overloads, !endsWithParen, priority));
        }
        LOG.info("...found " + list.size() + " scope members");
        return list;
    }

    private void addStaticImports(
            CompileTask task, CompilationUnitTree root, String partial, boolean endsWithParen, CompletionList list) {
        var trees = Trees.instance(task.task);
        var methods = new HashMap<String, List<ExecutableElement>>();
        var methodPriority = new HashMap<String, Integer>();
        var previousSize = list.items.size();
        outer:
        for (var i : root.getImports()) {
            if (!i.isStatic()) continue;
            var id = (MemberSelectTree) i.getQualifiedIdentifier();
            if (!importMatchesPartial(id.getIdentifier(), partial)) continue;
            var path = trees.getPath(root, id.getExpression());
            var type = (TypeElement) trees.getElement(path);
            for (var member : type.getEnclosedElements()) {
                if (!member.getModifiers().contains(Modifier.STATIC)) continue;
                if (!memberMatchesImport(id.getIdentifier(), member)) continue;
                if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
                if (member.getKind() == ElementKind.METHOD) {
                    putMethod((ExecutableElement) member, methods, methodPriority, Priority.METHOD);
                } else {
                    list.items.add(item(task, member, Priority.FIELD));
                }
                if (list.items.size() + methods.size() > MAX_COMPLETION_ITEMS) {
                    list.isIncomplete = true;
                    break outer;
                }
            }
        }
        for (var entry : methods.entrySet()) {
            var overloads = entry.getValue();
            var priority = methodPriority.getOrDefault(entry.getKey(), Priority.METHOD);
            list.items.add(method(task, overloads, !endsWithParen, priority));
        }
        LOG.info("...found " + (list.items.size() - previousSize) + " static imports");
    }

    private boolean importMatchesPartial(Name staticImport, String partial) {
        return staticImport.contentEquals("*") || StringSearch.matchesPartialName(staticImport, partial);
    }

    private boolean memberMatchesImport(Name staticImport, Element member) {
        return staticImport.contentEquals("*") || staticImport.contentEquals(member.getSimpleName());
    }

    private void addClassNames(
            CompilationUnitTree root, SourcePositions sourcePositions, String partial, CompletionList list) {
        var importFacts = ImportFacts.create(root);
        var packageName = Objects.toString(root.getPackageName(), "");
        var uniques = new HashSet<String>();
        var previousSize = list.items.size();
        for (var className : compiler.packagePrivateTopLevelTypes(packageName)) {
            if (!StringSearch.matchesPartialName(className, partial)) continue;
            var item = classItem(className, Priority.IMPORTED_CLASS);
            if (hasImportConflict(importFacts, className)) {
                item.insertText = className;
                item.insertTextFormat = InsertTextFormat.PlainText;
            } else {
                var edits = autoImportEdits(className, importFacts, sourcePositions);
                if (!edits.isEmpty()) {
                    item.additionalTextEdits = edits;
                }
            }
            item.sortText = sortKey(item.additionalTextEdits == null ? Priority.IMPORTED_CLASS : Priority.NOT_IMPORTED_CLASS, item.label);
            list.items.add(item);
            uniques.add(className);
        }
        for (var className : compiler.publicTopLevelTypes()) {
            if (!StringSearch.matchesPartialName(simpleName(className), partial)) continue;
            if (uniques.contains(className)) continue;
            if (list.items.size() > MAX_COMPLETION_ITEMS) {
                list.isIncomplete = true;
                break;
            }
            var item = classItem(className, Priority.NOT_IMPORTED_CLASS);
            if (hasImportConflict(importFacts, className)) {
                item.insertText = className;
                item.insertTextFormat = InsertTextFormat.PlainText;
            } else {
                var edits = autoImportEdits(className, importFacts, sourcePositions);
                if (!edits.isEmpty()) {
                    item.additionalTextEdits = edits;
                }
            }
            item.sortText = sortKey(item.additionalTextEdits == null ? Priority.IMPORTED_CLASS : Priority.NOT_IMPORTED_CLASS, item.label);
            list.items.add(item);
            uniques.add(className);
        }
        LOG.info("...found " + (list.items.size() - previousSize) + " class names");
    }

    private List<TextEdit> autoImportEdits(
            String className, ImportFacts importFacts, SourcePositions sourcePositions) {
        if (className.indexOf('.') < 0) {
            return List.of();
        }
        if (className.startsWith("java.lang.")) {
            return List.of();
        }
        if (!importFacts.packageName.isEmpty()
                && className.startsWith(importFacts.packageName + ".")
                && !className.substring(importFacts.packageName.length() + 1).contains(".")) {
            return List.of();
        }
        if (importFacts.explicitImports.contains(className)) {
            return List.of();
        }
        if (importFacts.starImports.contains(packageName(className))) {
            return List.of();
        }

        var edits = AddImport.createTextEdits(className, importFacts.root, sourcePositions);
        return List.of(edits[0]);
    }

    private boolean hasImportConflict(ImportFacts importFacts, String className) {
        if (className.indexOf('.') < 0) {
            return false;
        }
        var targetSimple = simpleName(className).toString();
        var existingImport = importFacts.simpleToExplicitImport.get(targetSimple);
        return existingImport != null && !existingImport.equals(className);
    }

    private static final class ImportFacts {
        private final CompilationUnitTree root;
        private final String packageName;
        private final Set<String> explicitImports;
        private final Set<String> starImports;
        private final Map<String, String> simpleToExplicitImport;

        private ImportFacts(
                CompilationUnitTree root,
                String packageName,
                Set<String> explicitImports,
                Set<String> starImports,
                Map<String, String> simpleToExplicitImport) {
            this.root = root;
            this.packageName = packageName;
            this.explicitImports = explicitImports;
            this.starImports = starImports;
            this.simpleToExplicitImport = simpleToExplicitImport;
        }

        private static ImportFacts create(CompilationUnitTree root) {
            var packageName = Objects.toString(root.getPackageName(), "");
            var explicitImports = new HashSet<String>();
            var starImports = new HashSet<String>();
            var simpleToExplicitImport = new HashMap<String, String>();
            for (var i : root.getImports()) {
                if (i.isStatic()) continue;
                var imported = i.getQualifiedIdentifier().toString();
                if (imported.endsWith(".*")) {
                    starImports.add(imported.substring(0, imported.length() - 2));
                    continue;
                }
                explicitImports.add(imported);
                var dot = imported.lastIndexOf('.');
                var simpleImportedName = dot == -1 ? imported : imported.substring(dot + 1);
                simpleToExplicitImport.putIfAbsent(simpleImportedName, imported);
            }
            return new ImportFacts(root, packageName, explicitImports, starImports, simpleToExplicitImport);
        }
    }

    private CompletionList completeMemberSelect(
            CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        var trees = Trees.instance(task.task);
        var select = (MemberSelectTree) path.getLeaf();
        LombokHandler.CompletionContext lombokContext = null;
        LOG.info("...complete members of " + select.getExpression());

        // Fast-path for Lombok @Slf4j generated `log` receiver.
        if (select.getExpression() instanceof IdentifierTree
                && "log".contentEquals(((IdentifierTree) select.getExpression()).getName())) {
            lombokContext = LombokHandler.newCompletionContext("memberSelect:" + select.getExpression());
            var slf4jCompletions =
                    LombokHandler.completeSlf4jLogMemberSelect(task, path, partial, lombokCache, lombokContext);
            if (slf4jCompletions != null) {
                return slf4jCompletions;
            }
        }

        if (select.getExpression() instanceof MethodInvocationTree) {
            LOG.info("...expression is MethodInvocationTree, attempting Lombok resolution");
            var builderList =
                    LombokHandler.builderCompletionsForInvocation(
                            task, (MethodInvocationTree) select.getExpression(), partial, lombokCache, compiler);
            if (builderList != null) {
                LOG.info("...builder pattern detected, returning builder completions");
                return builderList;
            }

            // Resolve Lombok-generated getter chains like obj.getA().getB().
            var invocationPath = new TreePath(path, select.getExpression());
            var lombokType = LombokHandler.resolveMethodInvocationReturnType(
                    task, (MethodInvocationTree) select.getExpression(), invocationPath, lombokCache);
            LOG.info("...Lombok invocation type resolved: " + lombokType);
            if (lombokType instanceof DeclaredType) {
                if (lombokContext == null) {
                    lombokContext = LombokHandler.newCompletionContext("memberSelect:" + select.getExpression());
                }
                var scope = trees.getScope(invocationPath);
                return completeDeclaredTypeMemberSelect(
                        task, scope, (DeclaredType) lombokType, false, partial, endsWithParen, lombokContext);
            }
        }
        path = new TreePath(path, select.getExpression());
        var isStatic = trees.getElement(path) instanceof TypeElement;
        var scope = trees.getScope(path);
        var type = trees.getTypeMirror(path);
        if (type == null) {
            return NOT_SUPPORTED;
        }

        // Handle ERROR types from unresolved Lombok-generated methods (recursive support)
        if (type.getKind() == TypeKind.ERROR) {
            var errorTypeName = type.toString();
            var resolvedType = LombokHandler.resolveLombokGeneratedMethodType(task, errorTypeName, lombokCache);

            if (resolvedType != null && resolvedType instanceof DeclaredType) {
                var resolvedDeclaredType = (DeclaredType) resolvedType;
                // ERROR types from method calls are always instance access, not static
                if (lombokContext == null) {
                    lombokContext = LombokHandler.newCompletionContext("memberSelect:" + select.getExpression());
                }
                return completeDeclaredTypeMemberSelect(
                        task, scope, resolvedDeclaredType, false, partial, endsWithParen, lombokContext);
            }
            var varInferred = inferVarDeclaredType(task, path);
            if (varInferred != null) {
                LOG.fine("...resolved var type for completion: " + varInferred);
                if (lombokContext == null) {
                    lombokContext = LombokHandler.newCompletionContext("memberSelect:" + select.getExpression());
                }
                return completeDeclaredTypeMemberSelect(
                        task, scope, varInferred, false, partial, endsWithParen, lombokContext);
            }
            return NOT_SUPPORTED;
        }

        if (type instanceof ArrayType) {
            return completeArrayMemberSelect(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(task, scope, (TypeVariable) type, isStatic, partial, endsWithParen);
        } else if (type instanceof DeclaredType) {
            var declaredType = (DeclaredType) type;
            if (lombokContext == null) {
                lombokContext = LombokHandler.newCompletionContext("memberSelect:" + select.getExpression());
            }
            return completeDeclaredTypeMemberSelect(
                    task, scope, declaredType, isStatic, partial, endsWithParen, lombokContext);
        } else {
            return NOT_SUPPORTED;
        }
    }

    private CompletionList completeArrayMemberSelect(boolean isStatic) {
        if (isStatic) {
            return EMPTY;
        } else {
            var list = new CompletionList();
            list.items.add(keyword("length"));
            return list;
        }
    }

    private CompletionList completeTypeVariableMemberSelect(
            CompileTask task, Scope scope, TypeVariable type, boolean isStatic, String partial, boolean endsWithParen) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(
                    task, scope, (DeclaredType) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(
                    task, scope, (TypeVariable) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else {
            return NOT_SUPPORTED;
        }
    }

    private DeclaredType inferVarDeclaredType(CompileTask task, TreePath expressionPath) {
        if (!(expressionPath.getLeaf() instanceof IdentifierTree)) {
            return null;
        }
        var trees = Trees.instance(task.task);
        var element = trees.getElement(expressionPath);
        if (!(element instanceof VariableElement)) {
            return null;
        }
        var variable = (VariableElement) element;
        if (variable.asType() instanceof DeclaredType) {
            return (DeclaredType) variable.asType();
        }
        var declarationPath = trees.getPath(element);
        if (declarationPath == null || !(declarationPath.getLeaf() instanceof VariableTree)) {
            declarationPath = findVariableDeclarationPath(task, expressionPath, variable.getSimpleName().toString());
        }
        if (declarationPath == null || !(declarationPath.getLeaf() instanceof VariableTree)) {
            return null;
        }
        var declaration = (VariableTree) declarationPath.getLeaf();
        if (!(declaration.getType() instanceof IdentifierTree)) {
            return null;
        }
        var typeIdent = (IdentifierTree) declaration.getType();
        if (!typeIdent.getName().contentEquals("var")) {
            return null;
        }
        if (declaration.getInitializer() == null) {
            return null;
        }
        var initializerPath = new TreePath(declarationPath, declaration.getInitializer());
        var initType = trees.getTypeMirror(initializerPath);
        if (initType instanceof DeclaredType) {
            return (DeclaredType) initType;
        }
        if (initType != null && initType.getKind() == TypeKind.ERROR) {
            if (declaration.getInitializer() instanceof MethodInvocationTree) {
                var resolved = LombokHandler.resolveMethodInvocationReturnType(
                        task, (MethodInvocationTree) declaration.getInitializer(), initializerPath, lombokCache);
                if (resolved instanceof DeclaredType) {
                    return (DeclaredType) resolved;
                }
            }
            var resolved = LombokHandler.resolveLombokGeneratedMethodType(task, initType.toString(), lombokCache);
            if (resolved instanceof DeclaredType) {
                return (DeclaredType) resolved;
            }
        }
        if (declaration.getType() != null) {
            var declaredTypePath = new TreePath(declarationPath, declaration.getType());
            var declaredType = trees.getTypeMirror(declaredTypePath);
            if (declaredType instanceof DeclaredType) {
                return (DeclaredType) declaredType;
            }
        }
        return null;
    }

    private TreePath findVariableDeclarationPath(CompileTask task, TreePath usagePath, String variableName) {
        if (usagePath == null || variableName == null || variableName.isEmpty()) {
            return null;
        }
        var root = usagePath.getCompilationUnit();
        if (root == null) {
            return null;
        }
        var trees = Trees.instance(task.task);
        var sourcePositions = trees.getSourcePositions();
        var usageStart = sourcePositions.getStartPosition(root, usagePath.getLeaf());
        if (usageStart == javax.tools.Diagnostic.NOPOS) {
            return null;
        }
        class Finder extends TreePathScanner<Void, Void> {
            private TreePath best;
            private long bestEnd = Long.MIN_VALUE;

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (node.getName().contentEquals(variableName)) {
                    var end = sourcePositions.getEndPosition(root, node);
                    if (end != javax.tools.Diagnostic.NOPOS && end <= usageStart && end > bestEnd) {
                        bestEnd = end;
                        best = getCurrentPath();
                    }
                }
                return super.visitVariable(node, unused);
            }
        }
        var finder = new Finder();
        finder.scan(root, null);
        return finder.best;
    }

    private CompletionList completeDeclaredTypeMemberSelect(
            CompileTask task, Scope scope, DeclaredType type, boolean isStatic, String partial, boolean endsWithParen) {
        return completeDeclaredTypeMemberSelect(task, scope, type, isStatic, partial, endsWithParen, null);
    }

    private CompletionList completeDeclaredTypeMemberSelect(
            CompileTask task,
            Scope scope,
            DeclaredType type,
            boolean isStatic,
            String partial,
            boolean endsWithParen,
            LombokHandler.CompletionContext lombokContext) {
        var typeElement = (TypeElement) type.asElement();
        var collected =
                collectTypeMembers(
                        task,
                        scope,
                        type,
                        partial,
                        true,
                        member -> isStatic == member.getModifiers().contains(Modifier.STATIC));
        var list = collected.items;
        addMethodCompletions(task, collected, !endsWithParen, list);

        // Add Lombok-generated members
        if (!isStatic) {
            addLombokCompletions(task, typeElement, partial, list, lombokContext);
        } else {
            LombokHandler.addStaticCompletions(task, typeElement, partial, list, lombokCache, lombokContext);
        }

        if (isStatic) {
            list.add(keyword("class"));
        }
        if (isStatic && isEnclosingClass(type, scope)) {
            list.add(keyword("this"));
            list.add(keyword("super"));
        }
        applySortTextIfMissing(list);
        return new CompletionList(false, list);
    }

    /**
     * Add completion items for Lombok-generated members.
     * Searches the compilation task for the ClassTree of the given TypeElement and synthesizes
     * completions for generated getters, setters, toString, equals, hashCode, and constructors.
     */
    private void addLombokCompletions(
            CompileTask task,
            TypeElement typeElement,
            String partial,
            List<CompletionItem> list,
            LombokHandler.CompletionContext lombokContext) {
        LombokHandler.addCompletions(task, typeElement, partial, list, lombokCache, lombokContext);
    }

    private boolean isEnclosingClass(DeclaredType type, Scope start) {
        for (var s : ScopeHelper.fastScopes(start)) {
            // If we reach a static method, stop looking
            var method = s.getEnclosingMethod();
            if (method != null && method.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
            // If we find the enclosing class
            var thisElement = s.getEnclosingClass();
            if (thisElement != null && thisElement.asType().equals(type)) {
                return true;
            }
            // If the enclosing class is static, stop looking
            if (thisElement != null && thisElement.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
        }
        return false;
    }

    private CompletionList completeMemberReference(CompileTask task, TreePath path, String partial) {
        var trees = Trees.instance(task.task);
        var select = (MemberReferenceTree) path.getLeaf();
        LOG.info("...complete methods of " + select.getQualifierExpression());
        path = new TreePath(path, select.getQualifierExpression());
        var element = trees.getElement(path);
        var isStatic = element instanceof TypeElement;
        var scope = trees.getScope(path);
        var type = trees.getTypeMirror(path);
        if (type instanceof ArrayType) {
            return completeArrayMemberReference(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberReference(task, scope, (TypeVariable) type, isStatic, partial);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(task, scope, (DeclaredType) type, isStatic, partial);
        } else {
            return NOT_SUPPORTED;
        }
    }

    private CompletionList completeArrayMemberReference(boolean isStatic) {
        if (isStatic) {
            var list = new CompletionList();
            list.items.add(keyword("new"));
            return list;
        } else {
            return EMPTY;
        }
    }

    private CompletionList completeTypeVariableMemberReference(
            CompileTask task, Scope scope, TypeVariable type, boolean isStatic, String partial) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(
                    task, scope, (DeclaredType) type.getUpperBound(), isStatic, partial);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberReference(
                    task, scope, (TypeVariable) type.getUpperBound(), isStatic, partial);
        } else {
            return NOT_SUPPORTED;
        }
    }

    private CompletionList completeDeclaredTypeMemberReference(
            CompileTask task, Scope scope, DeclaredType type, boolean isStatic, String partial) {
        var collected =
                collectTypeMembers(
                        task,
                        scope,
                        type,
                        partial,
                        false,
                        member -> isStatic || !member.getModifiers().contains(Modifier.STATIC));
        var list = collected.items;
        addMethodCompletions(task, collected, false, list);
        if (isStatic) {
            list.add(keyword("new"));
        }
        applySortTextIfMissing(list);
        return new CompletionList(false, list);
    }

    private static final CompletionList EMPTY = new CompletionList(false, List.of());

    private CompletionList completeSwitchConstant(CompileTask task, TreePath path, String partial) {
        var switchTree = (SwitchTree) path.getLeaf();
        path = new TreePath(path, switchTree.getExpression());
        var type = Trees.instance(task.task).getTypeMirror(path);
        LOG.info("...complete constants of type " + type);
        if (!(type instanceof DeclaredType)) {
            return NOT_SUPPORTED;
        }
        var declared = (DeclaredType) type;
        var element = (TypeElement) declared.asElement();
        var list = new ArrayList<CompletionItem>();
        for (var member : task.task.getElements().getAllMembers(element)) {
            if (member.getKind() != ElementKind.ENUM_CONSTANT) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            list.add(item(task, member, Priority.CASE_LABEL));
        }
        return new CompletionList(false, list);
    }

    private CompletionList completeImport(String path) {
        LOG.info("...complete import");
        var names = new HashSet<String>();
        var list = new CompletionList();
        for (var className : compiler.publicTopLevelTypes()) {
            if (className.startsWith(path)) {
                var start = path.lastIndexOf('.');
                var end = className.indexOf('.', path.length());
                if (end == -1) end = className.length();
                var segment = className.substring(start + 1, end);
                if (names.contains(segment)) continue;
                names.add(segment);
                var isClass = end == path.length();
                if (isClass) {
                    list.items.add(classItem(className));
                } else {
                    list.items.add(packageItem(segment));
                }
                if (list.items.size() > MAX_COMPLETION_ITEMS) {
                    list.isIncomplete = true;
                    return list;
                }
            }
        }
        return list;
    }

    private CompletionItem packageItem(String name) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Module;
        i.sortText = sortKey(Priority.PACKAGE_MEMBER, i.label);
        return i;
    }

    private CompletionItem classItem(String className) {
        return classItem(className, Priority.IMPORTED_CLASS);
    }

    private CompletionItem classItem(String className, int priority) {
        var i = new CompletionItem();
        i.label = simpleName(className).toString();
        i.kind = CompletionItemKind.Class;
        i.detail = className;
        i.sortText = sortKey(priority, i.label);
        var data = new CompletionData();
        data.className = className;
        i.data = JsonHelper.GSON.toJsonTree(data);
        return i;
    }

    private CompletionItem snippetItem(String label, String snippet) {
        var i = new CompletionItem();
        i.label = label;
        i.kind = CompletionItemKind.Snippet;
        i.insertText = snippet;
        i.insertTextFormat = InsertTextFormat.Snippet;
        i.sortText = sortKey(Priority.SNIPPET, i.label);
        return i;
    }

    private CompletionItem item(CompileTask task, Element element, int priority) {
        if (element.getKind() == ElementKind.METHOD) throw new RuntimeException("method");
        var i = new CompletionItem();
        i.label = element.getSimpleName().toString();
        i.kind = kind(element);
        i.detail = element.toString();
        i.sortText = sortKey(priority, i.label);
        i.data = JsonHelper.GSON.toJsonTree(data(task, element, 1));
        return i;
    }

    private CompletionItem method(
            CompileTask task, List<ExecutableElement> overloads, boolean addParens, int priority) {
        var first = overloads.get(0);
        var i = new CompletionItem();
        i.label = first.getSimpleName().toString();
        i.kind = CompletionItemKind.Method;
        i.detail = first.getReturnType() + " " + first;
        i.sortText = sortKey(priority, i.label);
        var data = data(task, first, overloads.size());
        i.data = JsonHelper.GSON.toJsonTree(data);
        if (addParens) {
            if (overloads.size() == 1 && first.getParameters().isEmpty()) {
                i.insertText = first.getSimpleName() + "()$0";
            } else {
                i.insertText = first.getSimpleName() + "($0)";
                // Activate signatureHelp
                // Remove this if VSCode ever fixes https://github.com/microsoft/vscode/issues/78806
                i.command = new Command();
                i.command.command = "editor.action.triggerParameterHints";
                i.command.title = "Trigger Parameter Hints";
            }
            i.insertTextFormat = 2; // Snippet
        }
        return i;
    }

    private CompletionData data(CompileTask task, Element element, int overloads) {
        var data = new CompletionData();
        if (element instanceof TypeElement) {
            var type = (TypeElement) element;
            data.className = type.getQualifiedName().toString();
        } else if (element.getKind() == ElementKind.FIELD) {
            var field = (VariableElement) element;
            var type = (TypeElement) field.getEnclosingElement();
            data.className = type.getQualifiedName().toString();
            data.memberName = field.getSimpleName().toString();
        } else if (element instanceof ExecutableElement) {
            var types = task.task.getTypes();
            var method = (ExecutableElement) element;
            var type = (TypeElement) method.getEnclosingElement();
            data.className = type.getQualifiedName().toString();
            data.memberName = method.getSimpleName().toString();
            data.erasedParameterTypes = new String[method.getParameters().size()];
            for (var i = 0; i < data.erasedParameterTypes.length; i++) {
                var p = method.getParameters().get(i).asType();
                data.erasedParameterTypes[i] = types.erasure(p).toString();
            }
            data.plusOverloads = overloads - 1;
        } else {
            return null;
        }
        return data;
    }

    private Integer kind(Element e) {
        switch (e.getKind()) {
            case ANNOTATION_TYPE:
                return CompletionItemKind.Interface;
            case CLASS:
                return CompletionItemKind.Class;
            case CONSTRUCTOR:
                return CompletionItemKind.Constructor;
            case ENUM:
                return CompletionItemKind.Enum;
            case ENUM_CONSTANT:
                return CompletionItemKind.EnumMember;
            case EXCEPTION_PARAMETER:
                return CompletionItemKind.Property;
            case FIELD:
                return CompletionItemKind.Field;
            case STATIC_INIT:
            case INSTANCE_INIT:
                return CompletionItemKind.Function;
            case INTERFACE:
                return CompletionItemKind.Interface;
            case LOCAL_VARIABLE:
            case BINDING_VARIABLE:
                return CompletionItemKind.Variable;
            case METHOD:
                return CompletionItemKind.Method;
            case PACKAGE:
                return CompletionItemKind.Module;
            case PARAMETER:
                return CompletionItemKind.Property;
            case RESOURCE_VARIABLE:
                return CompletionItemKind.Variable;
            case TYPE_PARAMETER:
                return CompletionItemKind.TypeParameter;
            case OTHER:
            default:
                return null;
        }
    }

    private CompletionItem keyword(String keyword) {
        var i = new CompletionItem();
        i.label = keyword;
        i.kind = CompletionItemKind.Keyword;
        i.detail = "keyword";
        i.sortText = sortKey(Priority.KEYWORD, i.label);
        return i;
    }

    private static class Priority {
        static int iota = 0;
        static final int SNIPPET = iota++;
        static final int LOCAL = iota++;
        static final int FIELD = iota++;
        static final int INHERITED_FIELD = iota++;
        static final int METHOD = iota++;
        static final int INHERITED_METHOD = iota++;
        static final int OBJECT_METHOD = iota++;
        static final int INNER_CLASS = iota++;
        static final int INHERITED_INNER_CLASS = iota++;
        static final int IMPORTED_CLASS = iota++;
        static final int NOT_IMPORTED_CLASS = iota++;
        static final int KEYWORD = iota++;
        static final int PACKAGE_MEMBER = iota++;
        static final int CASE_LABEL = iota++;
    }

    private void logCompletionTiming(Instant started, List<?> list, boolean isIncomplete) {
        var elapsedMs = Duration.between(started, Instant.now()).toMillis();
        if (isIncomplete) LOG.info(String.format("Found %d items (incomplete) in %,d ms", list.size(), elapsedMs));
        else LOG.info(String.format("...found %d items in %,d ms", list.size(), elapsedMs));
    }

    private CharSequence simpleName(String className) {
        var dot = className.lastIndexOf('.');
        if (dot == -1) return className;
        return className.subSequence(dot + 1, className.length());
    }

    private String packageName(String className) {
        var dot = className.lastIndexOf('.');
        if (dot <= 0) return "";
        return className.substring(0, dot);
    }

    private int priorityForScopedMember(ScopeHelper.ScopedMember scoped) {
        var member = scoped.element;
        if (scoped.local) {
            return Priority.LOCAL;
        }
        switch (member.getKind()) {
            case FIELD:
                return declaredInOwner(scoped) ? Priority.FIELD : Priority.INHERITED_FIELD;
            case METHOD:
                if (isObjectMethod(member)) return Priority.OBJECT_METHOD;
                return declaredInOwner(scoped) ? Priority.METHOD : Priority.INHERITED_METHOD;
            case CLASS:
            case INTERFACE:
            case ENUM:
            case ANNOTATION_TYPE:
                return declaredInOwner(scoped) ? Priority.INNER_CLASS : Priority.INHERITED_INNER_CLASS;
            case ENUM_CONSTANT:
                return Priority.FIELD;
            default:
                return Priority.LOCAL;
        }
    }

    private boolean declaredInOwner(ScopeHelper.ScopedMember scoped) {
        return scoped.owner != null && scoped.element.getEnclosingElement().equals(scoped.owner);
    }

    private int priorityForMemberOfType(Element member, TypeElement owner) {
        switch (member.getKind()) {
            case FIELD:
                return member.getEnclosingElement().equals(owner) ? Priority.FIELD : Priority.INHERITED_FIELD;
            case METHOD:
                if (isObjectMethod(member)) return Priority.OBJECT_METHOD;
                return member.getEnclosingElement().equals(owner) ? Priority.METHOD : Priority.INHERITED_METHOD;
            case CLASS:
            case INTERFACE:
            case ENUM:
            case ANNOTATION_TYPE:
                return member.getEnclosingElement().equals(owner) ? Priority.INNER_CLASS : Priority.INHERITED_INNER_CLASS;
            case ENUM_CONSTANT:
                return Priority.FIELD;
            default:
                return Priority.LOCAL;
        }
    }

    private boolean isObjectMethod(Element member) {
        if (member.getKind() != ElementKind.METHOD) return false;
        var enclosing = member.getEnclosingElement();
        if (!(enclosing instanceof TypeElement)) return false;
        var type = (TypeElement) enclosing;
        return "java.lang.Object".contentEquals(type.getQualifiedName());
    }

    private void applySortTextIfMissing(List<CompletionItem> items) {
        for (var item : items) {
            if (item.sortText != null || item.label == null) continue;
            switch (item.kind) {
                case CompletionItemKind.Method:
                    item.sortText = sortKey(Priority.METHOD, item.label);
                    break;
                case CompletionItemKind.Field:
                case CompletionItemKind.Variable:
                case CompletionItemKind.Property:
                    item.sortText = sortKey(Priority.FIELD, item.label);
                    break;
                case CompletionItemKind.Class:
                case CompletionItemKind.Interface:
                case CompletionItemKind.Enum:
                case CompletionItemKind.EnumMember:
                    item.sortText = sortKey(Priority.INNER_CLASS, item.label);
                    break;
                default:
                    item.sortText = sortKey(Priority.LOCAL, item.label);
                    break;
            }
        }
    }

    private void putMethod(
            ExecutableElement method,
            Map<String, List<ExecutableElement>> methods,
            Map<String, Integer> priorities,
            int priority) {
        var name = method.getSimpleName().toString();
        methods.computeIfAbsent(name, __ -> new ArrayList<>()).add(method);
        priorities.merge(name, priority, Math::min);
    }

    private String sortKey(int priority, String label) {
        return String.format("%02d%s", priority, label);
    }

    private MemberCompletions collectTypeMembers(
            CompileTask task,
            Scope scope,
            DeclaredType type,
            String partial,
            boolean includeNonMethods,
            Predicate<Element> memberFilter) {
        var trees = Trees.instance(task.task);
        var typeElement = (TypeElement) type.asElement();
        var collected = new MemberCompletions();
        for (var member : task.task.getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (memberFilter != null && !memberFilter.test(member)) continue;
            if (!includeNonMethods && member.getKind() != ElementKind.METHOD) continue;

            var priority = priorityForMemberOfType(member, typeElement);
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, collected.methods, collected.methodPriority, priority);
            } else if (includeNonMethods) {
                collected.items.add(item(task, member, priority));
            }
        }
        return collected;
    }

    private void addMethodCompletions(
            CompileTask task, MemberCompletions collected, boolean addParens, List<CompletionItem> list) {
        for (var entry : collected.methods.entrySet()) {
            var overloads = entry.getValue();
            var priority = collected.methodPriority.getOrDefault(entry.getKey(), Priority.METHOD);
            list.add(method(task, overloads, addParens, priority));
        }
    }

    private static class MemberCompletions {
        final List<CompletionItem> items = new ArrayList<>();
        final Map<String, List<ExecutableElement>> methods = new HashMap<>();
        final Map<String, Integer> methodPriority = new HashMap<>();
    }

    private static final Logger LOG = Logger.getLogger("main");
}
