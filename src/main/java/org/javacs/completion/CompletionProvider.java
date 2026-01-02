package org.javacs.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
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
import java.util.function.Predicate;
import java.util.logging.Level;
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
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.CompletionData;
import org.javacs.CompletionDocumentation;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.JsonHelper;
import org.javacs.ParseTask;
import org.javacs.SourceFileObject;
import org.javacs.StringSearch;
import org.javacs.imports.AutoImportProvider;
import org.javacs.lsp.Command;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.InsertTextFormat;

public class CompletionProvider {
    private final CompilerProvider compiler;
    private final AutoImportProvider autoImportProvider;
    private final CompletionDocumentation documentationHelper;

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

    public CompletionProvider(CompilerProvider compiler, AutoImportProvider autoImportProvider) {
        this.compiler = compiler;
        this.autoImportProvider = autoImportProvider;
        this.documentationHelper = new CompletionDocumentation(compiler);
    }

    public CompletionList complete(Path file, int line, int column) {
        LOG.info("Complete at " + file.getFileName() + "(" + line + "," + column + ")...");
        var started = Instant.now();
        try {
            var task = compiler.parse(file);
            var cursor = task.root.getLineMap().getPosition(line, column);
            var contents = new PruneMethodBodies(task.task).scan(task.root, cursor);
            var endOfLine = endOfLine(contents, (int) cursor);
            contents.insert(endOfLine, ';');
            var list = compileAndComplete(file, contents.toString(), cursor, endOfLine);
            addTopLevelSnippets(task, list);
            logCompletionTiming(started, list.items, list.isIncomplete);
            LOG.fine(
                    String.format(
                            "Completion %s:%d:%d total=%dms items=%d incomplete=%s",
                            file.getFileName(),
                            line,
                            column,
                            Duration.between(started, Instant.now()).toMillis(),
                            list.items.size(),
                            list.isIncomplete));
            return list;
        } catch (AssertionError e) {
            LOG.log(Level.SEVERE, "Completion failed with compiler assertion", e);
            return NOT_SUPPORTED;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Completion failed", e);
            return NOT_SUPPORTED;
        }
    }

    private int endOfLine(CharSequence contents, int cursor) {
        while (cursor < contents.length()) {
            var c = contents.charAt(cursor);
            if (c == '\r' || c == '\n') break;
            cursor++;
        }
        return cursor;
    }

    private CompletionList compileAndComplete(Path file, String contents, long cursor, int endOfLine) {
        var started = Instant.now();
        var source = new SourceFileObject(file, contents, completionModified(file, endOfLine));
        var partial = partialIdentifier(contents, (int) cursor);
        var endsWithParen = endsWithParen(contents, (int) cursor);
        try (var task = compiler.compile(List.of(source))) {
            LOG.info("...compiled in " + Duration.between(started, Instant.now()).toMillis() + "ms");
            var path = new FindCompletionsAt(task.task).scan(task.root(file), cursor);
            if (path == null) return NOT_SUPPORTED;
            if (isAnnotationContext(contents, (int) cursor)) {
                return completeAnnotation(task, path, partial);
            }
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

    private Instant completionModified(Path file, int endOfLine) {
        var version = FileStore.documentVersion(file);
        if (version != null) {
            long token = ((long) version << 32) ^ (endOfLine & 0xffffffffL);
            token = token & Long.MAX_VALUE;
            return Instant.ofEpochMilli(token);
        }
        var modified = FileStore.modified(file);
        if (modified != null) {
            long token = (modified.toEpochMilli() & 0xffffffffL) ^ (endOfLine & 0xffffffffL);
            token = token & Long.MAX_VALUE;
            return Instant.ofEpochMilli(token);
        }
        return Instant.EPOCH;
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

    private boolean isAnnotationContext(CharSequence contents, int cursor) {
        var i = cursor - 1;
        while (i >= 0) {
            char c = contents.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '@';
            }
            i--;
        }
        return false;
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
        addStaticImports(task, path.getCompilationUnit(), partial, endsWithParen, list);
        if (!list.isIncomplete && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), Trees.instance(task.task).getSourcePositions(), partial, list);
        }
        addKeywords(path, partial, list);
        return list;
    }

    private CompletionList completeAnnotation(CompileTask task, TreePath path, String partial) {
        LOG.info("...complete annotations");
        var list = new CompletionList();
        addAnnotationImports(task, path.getCompilationUnit(), Trees.instance(task.task).getSourcePositions(), partial, list);
        if (!list.isIncomplete && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addAnnotationNames(task, path.getCompilationUnit(), Trees.instance(task.task).getSourcePositions(), partial, list);
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
        var scope = trees.getScope(path);
        Predicate<CharSequence> filter = name -> StringSearch.matchesPartialName(name, partial);
        for (var member : ScopeHelper.scopeMembers(task, scope, filter)) {
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(task, member));
            }
        }
        for (var overloads : methods.values()) {
            list.add(method(task, overloads, !endsWithParen));
        }
        LOG.info("...found " + list.size() + " scope members");
        return list;
    }

    private void addStaticImports(
            CompileTask task, CompilationUnitTree root, String partial, boolean endsWithParen, CompletionList list) {
        var trees = Trees.instance(task.task);
        var methods = new HashMap<String, List<ExecutableElement>>();
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
                    putMethod((ExecutableElement) member, methods);
                } else {
                    list.items.add(item(task, member));
                }
                if (list.items.size() + methods.size() > MAX_COMPLETION_ITEMS) {
                    list.isIncomplete = true;
                    break outer;
                }
            }
        }
        for (var overloads : methods.values()) {
            list.items.add(method(task, overloads, !endsWithParen));
        }
        LOG.info("...found " + (list.items.size() - previousSize) + " static imports");
    }

    private boolean importMatchesPartial(Name staticImport, String partial) {
        return staticImport.contentEquals("*") || StringSearch.matchesPartialName(staticImport, partial);
    }

    private boolean memberMatchesImport(Name staticImport, Element member) {
        return staticImport.contentEquals("*") || staticImport.contentEquals(member.getSimpleName());
    }

    private void addClassNames(CompilationUnitTree root, SourcePositions sourcePositions, String partial, CompletionList list) {
        var packageName = Objects.toString(root.getPackageName(), "");
        var previousSize = list.items.size();
        var added = new HashSet<String>();
        for (var i : root.getImports()) {
            if (i.isStatic()) continue;
            var qualified = i.getQualifiedIdentifier().toString();
            if (qualified.endsWith(".*")) continue;
            if (!StringSearch.matchesPartialName(simpleName(qualified), partial)) continue;
            var item = classItem(qualified);
            item.additionalTextEdits = autoImportProvider.addImport(qualified, root, sourcePositions);
            list.items.add(item);
            added.add(qualified);
        }
        for (var className : compiler.packagePrivateTopLevelTypes(packageName)) {
            if (added.contains(className)) continue;
            var item = classItem(className);
            item.additionalTextEdits = autoImportProvider.addImport(className, root, sourcePositions);
            list.items.add(item);
        }
        for (var className : compiler.publicTopLevelTypes()) {
            if (added.contains(className)) continue;
            if (!StringSearch.matchesPartialName(simpleName(className), partial)) continue;
            if (list.items.size() > MAX_COMPLETION_ITEMS) {
                list.isIncomplete = true;
                break;
            }
            var item = classItem(className);
            item.additionalTextEdits = autoImportProvider.addImport(className, root, sourcePositions);
            list.items.add(item);
        }
        LOG.info("...found " + (list.items.size() - previousSize) + " class names");
    }

    private void addAnnotationImports(
            CompileTask task, CompilationUnitTree root, SourcePositions sourcePositions, String partial, CompletionList list) {
        var previousSize = list.items.size();
        var added = new HashSet<String>();
        var annotationCache = new HashMap<String, Boolean>();

        for (var i : root.getImports()) {
            if (i.isStatic()) continue;
            var qualified = i.getQualifiedIdentifier().toString();
            if (qualified.endsWith(".*")) continue;
            if (!StringSearch.matchesPartialName(simpleName(qualified), partial)) continue;
            if (!isAnnotationType(task, qualified, annotationCache)) continue;
            var item = classItem(qualified);
            item.additionalTextEdits = autoImportProvider.addImport(qualified, root, sourcePositions);
            list.items.add(item);
            added.add(qualified);
        }

        LOG.info("...found " + (list.items.size() - previousSize) + " annotation imports");
    }

    private void addAnnotationNames(
            CompileTask task, CompilationUnitTree root, SourcePositions sourcePositions, String partial, CompletionList list) {
        var previousSize = list.items.size();
        var added = new HashSet<String>();
        var annotationCache = new HashMap<String, Boolean>();

        for (var className : compiler.publicTopLevelTypes()) {
            if (added.contains(className)) continue;
            if (!StringSearch.matchesPartialName(simpleName(className), partial)) continue;
            if (!isAnnotationType(task, className, annotationCache)) continue;
            if (list.items.size() > MAX_COMPLETION_ITEMS) {
                list.isIncomplete = true;
                break;
            }
            var item = classItem(className);
            item.additionalTextEdits = autoImportProvider.addImport(className, root, sourcePositions);
            list.items.add(item);
        }

        LOG.info("...found " + (list.items.size() - previousSize) + " annotation names");
    }

    private boolean isAnnotationType(CompileTask task, String className, Map<String, Boolean> cache) {
        var cached = cache.get(className);
        if (cached != null) return cached;
        try {
            var source = compiler.findAnywhere(className);
            if (source.isEmpty()) {
                cache.put(className, false);
                return false;
            }
            var parse = compiler.parse(source.get());
            var tree = FindHelper.findType(parse, className);
            if (tree instanceof ClassTree) {
                var kind = ((ClassTree) tree).getKind();
                var isAnnotation = kind == Tree.Kind.ANNOTATION_TYPE;
                cache.put(className, isAnnotation);
                return isAnnotation;
            }
        } catch (RuntimeException e) {
            // ignore parse failures
        }
        cache.put(className, false);
        return false;
    }

    private CompletionList completeMemberSelect(
            CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        var trees = Trees.instance(task.task);
        var select = (MemberSelectTree) path.getLeaf();
        LOG.info("...complete members of " + select.getExpression());
        path = new TreePath(path, select.getExpression());
        var element = trees.getElement(path);
        var isStatic = element instanceof TypeElement;
        var scope = trees.getScope(path);
        var type = trees.getTypeMirror(path);
        if (isFallbackType(type) && select.getExpression().getKind() == Tree.Kind.IDENTIFIER) {
            var name = ((IdentifierTree) select.getExpression()).getName();
            var fallback = resolveInScope(task, scope, name);
            if (fallback != null) {
                LOG.fine("...resolved member-select from scope for " + name);
                element = fallback;
                isStatic = element instanceof TypeElement;
                type = element.asType();
            }
        }
        if (!isStatic && type instanceof DeclaredType && select.getExpression().getKind() == Tree.Kind.IDENTIFIER) {
            var typeElement = ((DeclaredType) type).asElement();
            if (typeElement instanceof TypeElement) {
                var name = ((IdentifierTree) select.getExpression()).getName();
                if (name.contentEquals(typeElement.getSimpleName())) {
                    isStatic = true;
                }
            }
        }
        if (type == null) {
            LOG.info("...no type mirror for " + select.getExpression());
        } else {
            LOG.info("...type mirror kind " + type.getKind() + " for " + select.getExpression());
        }
        if (type instanceof ArrayType) {
            return completeArrayMemberSelect(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(task, scope, (TypeVariable) type, isStatic, partial, endsWithParen);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(task, scope, (DeclaredType) type, isStatic, partial, endsWithParen);
        } else {
            LOG.info("...completion not supported for type " + type);
            return NOT_SUPPORTED;
        }
    }

    private boolean isFallbackType(TypeMirror type) {
        if (type == null) return true;
        var kind = type.getKind();
        return kind == TypeKind.PACKAGE || kind == TypeKind.ERROR;
    }

    private Element resolveInScope(CompileTask task, Scope scope, Name name) {
        for (var member : ScopeHelper.scopeMembers(task, scope, candidate -> name.contentEquals(candidate))) {
            switch (member.getKind()) {
                case LOCAL_VARIABLE:
                case PARAMETER:
                case FIELD:
                case ENUM_CONSTANT:
                case CLASS:
                case INTERFACE:
                case ENUM:
                case RECORD:
                    return member;
                default:
                    break;
            }
        }
        return null;
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

    private CompletionList completeDeclaredTypeMemberSelect(
            CompileTask task, Scope scope, DeclaredType type, boolean isStatic, String partial, boolean endsWithParen) {
        var trees = Trees.instance(task.task);
        var typeElement = (TypeElement) type.asElement();
        var list = new ArrayList<CompletionItem>();
        var methods = new HashMap<String, List<ExecutableElement>>();
        for (var member : task.task.getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (isStatic != member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(task, member));
            }
        }
        for (var overloads : methods.values()) {
            list.add(method(task, overloads, !endsWithParen));
        }
        if (isStatic) {
            list.add(keyword("class"));
        }
        if (isStatic && isEnclosingClass(type, scope)) {
            list.add(keyword("this"));
            list.add(keyword("super"));
        }
        return new CompletionList(false, list);
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
        var trees = Trees.instance(task.task);
        var typeElement = (TypeElement) type.asElement();
        var list = new ArrayList<CompletionItem>();
        var methods = new HashMap<String, List<ExecutableElement>>();
        for (var member : task.task.getElements().getAllMembers(typeElement)) {
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            if (member.getKind() != ElementKind.METHOD) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (!isStatic && member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(task, member));
            }
        }
        for (var overloads : methods.values()) {
            list.add(method(task, overloads, false));
        }
        if (isStatic) {
            list.add(keyword("new"));
        }
        return new CompletionList(false, list);
    }

    private static final CompletionList EMPTY = new CompletionList(false, List.of());

    private void putMethod(ExecutableElement method, Map<String, List<ExecutableElement>> methods) {
        var name = method.getSimpleName().toString();
        if (!methods.containsKey(name)) {
            methods.put(name, new ArrayList<>());
        }
        methods.get(name).add(method);
    }

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
            list.add(item(task, member));
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
        return i;
    }

    private CompletionItem classItem(String className) {
        var i = new CompletionItem();
        i.label = simpleName(className).toString();
        i.kind = CompletionItemKind.Class;
        i.detail = className;
        var data = new CompletionData();
        data.className = className;
        i.data = JsonHelper.GSON.toJsonTree(data);
        addDocumentation(i, data);
        return i;
    }

    private CompletionItem snippetItem(String label, String snippet) {
        var i = new CompletionItem();
        i.label = label;
        i.kind = CompletionItemKind.Snippet;
        i.insertText = snippet;
        i.insertTextFormat = InsertTextFormat.Snippet;
        i.sortText = String.format("%02d%s", Priority.SNIPPET, i.label);
        return i;
    }

    private CompletionItem item(CompileTask task, Element element) {
        if (element.getKind() == ElementKind.METHOD) throw new RuntimeException("method");
        var i = new CompletionItem();
        i.label = element.getSimpleName().toString();
        i.kind = kind(element);
        i.detail = element.toString();
        var data = data(task, element, 1);
        i.data = JsonHelper.GSON.toJsonTree(data);
        addDocumentation(i, data);
        return i;
    }

    private CompletionItem method(CompileTask task, List<ExecutableElement> overloads, boolean addParens) {
        var first = overloads.get(0);
        var i = new CompletionItem();
        i.label = first.getSimpleName().toString();
        i.kind = CompletionItemKind.Method;
        i.detail = first.getReturnType() + " " + first;
        var data = data(task, first, overloads.size());
        i.data = JsonHelper.GSON.toJsonTree(data);
        addDocumentation(i, data);
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

    private void addDocumentation(CompletionItem item, CompletionData data) {
        if (data == null) return;
        try {
            var docs = documentationHelper.documentation(data);
            docs.ifPresent(markup -> item.documentation = markup);
        } catch (RuntimeException e) {
            LOG.fine("...skipping documentation: " + e.getMessage());
        }
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
        i.sortText = String.format("%02d%s", Priority.KEYWORD, i.label);
        return i;
    }

    private static class Priority {
        static int iota = 0;
        static final int SNIPPET = iota;
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
        if (isIncomplete) LOG.fine(String.format("Found %d items (incomplete) in %,d ms", list.size(), elapsedMs));
        else LOG.fine(String.format("...found %d items in %,d ms", list.size(), elapsedMs));
    }

    private CharSequence simpleName(String className) {
        var dot = className.lastIndexOf('.');
        if (dot == -1) return className;
        return className.subSequence(dot + 1, className.length());
    }

    private static final Logger LOG = Logger.getLogger("main");
}
