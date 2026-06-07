package org.javacs.provider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonNull;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.logging.Logger;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;

import org.javacs.CacheAudit;
import org.javacs.CompilerProvider;
import org.javacs.CompletionData;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.JsonHelper;
import org.javacs.LombokAnnotations;
import org.javacs.MarkdownHelper;
import org.javacs.ParseTask;
import org.javacs.StringSearch;
import org.javacs.completion.FindCompletionsAt;
import org.javacs.completion.PruneMethodBodies;
import org.javacs.index.IndexedMember;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.Command;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.InsertTextFormat;
import org.javacs.lsp.TextEdit;
import org.javacs.resolve.ParseTypeResolver;
import org.javacs.resolve.TypeNames;
import org.javacs.rewrite.AddImport;

/** Completion entrypoint that stays on the parse/index path without compiling on each request. */
public class CompletionProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private record MemberAccessContext(String receiver, String partial, boolean dotTrigger, boolean methodReference, int separatorEnd) { }
    private record IndexedCompletionResult(CompletionList list, long resolveMs, String cacheState) {}
    private record AnnotationContext(String qualifiedAnnotationType, String partial) {}
    private record CompletionRequestContext(
            ParseTask task, String contents, long cursor, TreePath parsePath, MemberAccessContext memberAccess, AnnotationContext annotationContext, long parseMs) {}
    private record MemberCompletionCacheKey(
            long indexVersion,
            String targetType,
            boolean staticContext,
            boolean enclosingInstanceAccess,
            boolean methodReference,
            String partial,
            boolean endsWithParen,
            String currentType) {}

    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndexRouter;
    private final long completionIndexVersion;

    public static final CompletionList NOT_SUPPORTED = new CompletionList(false, List.of());
    public static final int MAX_COMPLETION_ITEMS = 50;
    private static final Set<String> OBJECT_MEMBER_LABELS =
            Set.of("equals", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");
    private static final Cache<MemberCompletionCacheKey, CompletionList> MEMBER_COMPLETION_CACHE =
            Caffeine.newBuilder().maximumSize(20_000).build();

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

    public CompletionProvider(CompilerProvider compiler, TypeIndexRouter typeIndexRouter, long completionIndexVersion) {
        this.compiler = compiler;
        this.typeIndexRouter = typeIndexRouter == null ? TypeIndexRouter.EMPTY : typeIndexRouter;
        this.completionIndexVersion = Math.max(0L, completionIndexVersion);
    }

    public void resolveCompletionItem(CompletionItem item) {
        if (item.data == null || item.data == JsonNull.INSTANCE) return;
        var data = JsonHelper.GSON.fromJson(item.data, CompletionData.class);
        var source = compiler.findAnywhere(data.className);
        if (source.isEmpty()) return;
        var task = compiler.parse(source.get());
        Tree tree;
        try {
            tree = findItem(task, data);
        } catch (RuntimeException missingGeneratedItem) {
            LOG.fine(
                    String.format(
                            "Skip completion item resolve for unresolved member %s#%s",
                            data.className, data.memberName));
            return;
        }
        resolveDetail(item, data, tree);
        var path = Trees.instance(task.task()).getPath(task.root(), tree);
        if (path == null) return;
        var docTree = DocTrees.instance(task.task()).getDocCommentTree(path);
        if (docTree == null) return;
        item.documentation = MarkdownHelper.asMarkupContent(docTree);
    }

    public CompletionList complete(Path file, int line, int column) {
        var started = Instant.now();
        var request = prepareRequestContext(file, line, column);
        if (request == null) {
            return NOT_SUPPORTED;
        }
        CompletionList list;
        String mode;
        long resolveMs = 0;
        String memberCacheState = "n/a";
        if (request.memberAccess() != null && isImportOrPackageContext(request.parsePath())) {
            list = completeParseOnly(request.task(), request.contents(), request.cursor(), request.memberAccess().partial);
            mode = "import_parse";
        } else if (request.memberAccess() != null) {
            var indexed =

                    completeMembersUsingIndex(
                            request.task(),
                            request.parsePath(),
                            request.cursor(),
                            request.memberAccess(),
                            endsWithParen(request.contents(), (int) request.cursor()));
            list = indexed.list();
            resolveMs = indexed.resolveMs();
            memberCacheState = indexed.cacheState();
            mode = "member_index";
        } else if (request.annotationContext() != null) {
            var partial = partialIdentifier(request.contents(), (int) request.cursor());
            list = completeParseOnly(request.task(), request.contents(), request.cursor(), partial);
            var attrs = completeAnnotationAttributes(request.annotationContext());
            list.items.addAll(0, attrs.items);
            mode = "annotation";
        } else {
            list = completeParseOnly(
                    request.task(), request.contents(), request.cursor(), partialIdentifier(request.contents(), (int) request.cursor()));
            mode = "identifier_parse";
        }
        if (list == NOT_SUPPORTED) {
            return NOT_SUPPORTED;
        }
        list = finalizeCompletionList(request.task(), list);
        logCompletionFlow(file, mode, request.parseMs(), resolveMs, memberCacheState, started);
        return list;
    }

    private CompletionRequestContext prepareRequestContext(Path file, int line, int column) {
        var parseStarted = Instant.now();
        var task = compiler.parse(file);
        var parseMs = Duration.between(parseStarted, Instant.now()).toMillis();
        long cursor;
        try {
            var source = task.root().getSourceFile().getCharContent(true).toString();
            cursor = FileStore.offset(source, line, column);
            if (isInComment(source, cursor)) {
                return null;
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        var contents = new PruneMethodBodies(task.task()).scan(task.root(), cursor);
        var endOfLine = endOfLine(contents, (int) cursor);
        contents.insert(endOfLine, ';');
        var pruned = contents.toString();
        var parsePath = new FindCompletionsAt(task.task()).scan(task.root(), cursor);
        var memberAccess = memberAccessContext(pruned, (int) cursor);
        var annotationContext = findAnnotationContext(task, cursor);
        return new CompletionRequestContext(task, pruned, cursor, parsePath, memberAccess, annotationContext, parseMs);
    }

    /**
     * Scans source text up to cursor offset to determine if the cursor is inside a comment.
     * Tracks string and char literals to avoid false positives where // or /* appear inside strings.
     */
    private static boolean isInComment(String source, long cursorOffset) {
        var inLineComment = false;
        var inBlockComment = false;
        var inString = false;
        var inChar = false;
        var escaping = false;
        var limit = (int) Math.min(cursorOffset, source.length());
        for (int i = 0; i < limit; i++) {
            var c = source.charAt(i);
            var next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (escaping) {
                    escaping = false;
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
        }
        return inLineComment || inBlockComment;
    }

    private CompletionList finalizeCompletionList(ParseTask task, CompletionList list) {
        // Some sentinel paths use immutable lists (List.of()).
        // Completion post-processing mutates/sorts the list, so ensure mutability.
        if (!(list.items instanceof ArrayList)) {
            list.items = new ArrayList<>(list.items);
        }
        addTopLevelSnippets(task, list);
        sortCompletionItems(list.items);
        return list;
    }

    private void logCompletionFlow(
            Path file, String mode, long parseMs, long resolveMs, String memberCacheState, Instant started) {
        LOG.info(
                String.format(
                        "[perf] completion_flow file=%s mode=%s sources=1 enter=%d analyze=%d ap=%d index_version=%d parse=%dms resolve=%dms member_cache=%s took=%dms",
                        file.getFileName(),
                        mode,
                        0,
                        0,
                        0,
                        completionIndexVersion,
                        parseMs,
                        resolveMs,
                        memberCacheState,
                        Duration.between(started, Instant.now()).toMillis()));
    }

    private int endOfLine(CharSequence contents, int cursor) {
        while (cursor < contents.length()) {
            var c = contents.charAt(cursor);
            if (c == '\r' || c == '\n') break;
            cursor++;
        }
        return cursor;
    }

    private IndexedCompletionResult completeMembersUsingIndex(
            ParseTask parseTask,
            TreePath parsePath,
            long cursor,
            MemberAccessContext memberAccess,
            boolean endsWithParen) {
        if (typeIndexRouter == null) {
            return new IndexedCompletionResult(EMPTY, 0, "index_empty");
        }
        var index = typeIndexRouter;
        var started = Instant.now();
        var resolver = new ParseTypeResolver(parseTask, compiler, index, cursor);
        var expression = memberReceiverExpression(parsePath);
        if (expression == null && memberAccess.receiver().isEmpty()) {
            // ParsePath didn't find a MemberSelectTree at the cursor (e.g. double-dot error recovery).
            // Fall back to finding the expression that ends exactly at the separator position.
            expression = findExpressionEndingAt(parseTask, memberAccess.separatorEnd());
        }
        var resolveStarted = Instant.now();
        var resolved = resolver.resolve(expression, memberAccess.receiver());
        var resolveMs = Duration.between(resolveStarted, Instant.now()).toMillis();
        if (resolved.isEmpty()) {
            return new IndexedCompletionResult(EMPTY, resolveMs, "unresolved_type");
        }
        var target = resolved.get();
        if (!target.arrayType() && !isQualifiedTypeName(target.qualifiedType())) {
            return new IndexedCompletionResult(EMPTY, resolveMs, "non_fqn_type");
        }
        var currentType = resolver.currentEnclosingTypeName();
        if (target.arrayType()) {
            return new IndexedCompletionResult(
                    completeArrayMemberSelect(target.staticContext()), resolveMs, "array_type");
        }
        var membersStarted = Instant.now();
        var members =
                memberAccess.methodReference() && target.staticContext()
                        ? mergeMemberContexts(index, target.qualifiedType())
                        : index.members(target.qualifiedType(), target.staticContext());
        var membersMs = Duration.between(membersStarted, Instant.now()).toMillis();
        if (members.isEmpty() && !target.staticContext()) {
            return new IndexedCompletionResult(EMPTY, resolveMs + membersMs, "no_members");
        }
        var enclosingInstanceAccess =
                target.staticContext()
                        && isAccessibleEnclosingInstanceType(
                                parseTask, parsePath, cursor, target.qualifiedType(), memberAccess.receiver);
        var cacheKey =
                new MemberCompletionCacheKey(
                        completionIndexVersion,
                        target.qualifiedType(),
                        target.staticContext(),
                        enclosingInstanceAccess,
                        memberAccess.methodReference(),
                        memberAccess.partial,
                        endsWithParen,
                        currentType.orElse(""));
        var cached = MEMBER_COMPLETION_CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            CacheAudit.hit("completion.member");
            return new IndexedCompletionResult(
                    copyCompletionList(cached),
                    Duration.between(started, Instant.now()).toMillis(),
                    "hit");
        }
        CacheAudit.miss("completion.member");

        var buildStarted = Instant.now();
        var list = new ArrayList<CompletionItem>();
        var methods = new TreeMap<String, List<IndexedMember>>();
        var methodPriority = new HashMap<String, Integer>();
        for (var member : members) {
            if (!isIndexMemberVisible(member, target.qualifiedType(), currentType)) continue;
            if (!matchesCompletionPrefix(member.name, memberAccess.partial)) continue;
            if (memberAccess.methodReference() && member.kind != CompletionItemKind.Method) continue;
            if (member.kind == CompletionItemKind.Method) {
                methods.computeIfAbsent(member.name, __ -> new ArrayList<>()).add(member);
                methodPriority.merge(member.name, indexMemberPriority(member), Math::min);
            } else {
                var item = indexedMember(member);
                item.sortText = sortKey(indexMemberPriority(member), item.label);
                list.add(item);
            }
        }
        for (var entry : methods.entrySet()) {
            var method = indexedMethod(entry.getValue(), memberAccess.methodReference() ? false : !endsWithParen);
            method.sortText = sortKey(methodPriority.getOrDefault(entry.getKey(), Priority.INHERITED_METHOD), method.label);
            list.add(method);
        }
        // Nested types (e.g. IndexedMember.Origin) are stored in IndexedType.nestedTypes, not as
        // IndexedMember entries. Add them in static-access context (e.g. ClassName.).
        if (target.staticContext() && !memberAccess.methodReference()) {
            typeIndexRouter.typeInfo(target.qualifiedType()).ifPresent(typeInfo -> {
                for (var nested : typeInfo.nestedTypes) {
                    var simpleName = TypeNames.simpleName(nested);
                    if (simpleName == null || simpleName.isBlank()) continue;
                    if (!matchesCompletionPrefix(simpleName, memberAccess.partial)) continue;
                    var nestedKind = typeIndexRouter.typeInfo(nested)
                            .map(t -> t.kind)
                            .orElse(CompletionItemKind.Class);
                    var item = new CompletionItem();
                    item.label = simpleName;
                    item.kind = nestedKind;
                    item.detail = target.qualifiedType();
                    // Priority consistent with indexMemberPriority() for class/enum/interface kinds.
                    item.sortText = sortKey(45, simpleName);
                    list.add(item);
                }
            });
        }
        if (memberAccess.methodReference() && target.staticContext()) {
            list.add(keyword("new"));
        } else if (target.staticContext()) {
            list.add(keyword("class"));
            if (enclosingInstanceAccess) {
                list.add(keyword("this"));
                list.add(keyword("super"));
            }
        }
        var result = new CompletionList(false, list);
        var buildMs = Duration.between(buildStarted, Instant.now()).toMillis();
        if (memberAccess.partial.isEmpty()
                && !"java.lang.Object".equals(target.qualifiedType())
                && isWeakObjectOnlyResult(result)) {
            return new IndexedCompletionResult(
                    EMPTY, resolveMs + membersMs + buildMs, "miss_drop_weak_object_only");
        }
        sortCompletionItems(list);
        result = new CompletionList(false, list);
        MEMBER_COMPLETION_CACHE.put(cacheKey, freezeCompletionList(result));
        CacheAudit.load("completion.member");
        CacheAudit.store("completion.member");
        return new IndexedCompletionResult(
                result, Duration.between(started, Instant.now()).toMillis(), "miss");
    }

    private boolean isIndexMemberVisible(
            IndexedMember member, String targetType, Optional<String> currentEnclosingType) {
        if (!member.isPrivate) {
            return true;
        }
        if (!Objects.equals(member.ownerType, targetType)) {
            return false;
        }
        return currentEnclosingType.isPresent() && Objects.equals(currentEnclosingType.get(), targetType);
    }

    private Tree memberReceiverExpression(TreePath parsePath) {
        for (var cursor = parsePath; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof MemberSelectTree select) {
                return select.getExpression();
            }
            if (cursor.getLeaf() instanceof MemberReferenceTree reference) {
                return reference.getQualifierExpression();
            }
        }
        return null;
    }

    private boolean isQualifiedTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        return typeName.contains(".") || TypeNames.isPrimitive(typeName);
    }

    private int indexMemberPriority(IndexedMember member) {
        if (member.kind == CompletionItemKind.Method) {
            if (isUtilityMethodName(member.name)) return 90; // always sink utility methods
            if (member.priority == 2) return 80; // java.lang.Object methods near end
            if (member.isStatic) return 30;
            if (member.priority == 0 && isAccessorMethodName(member.name)) return 10;
            if (member.priority == 0) return 20;
            return 60;
        }
        if (member.kind == CompletionItemKind.Field) {
            if (member.isStatic) return 40;
            if (member.priority == 0) return 35;
            return 50;
        }
        if (member.kind == CompletionItemKind.Class
                || member.kind == CompletionItemKind.Interface
                || member.kind == CompletionItemKind.Enum) {
            return 45;
        }
        return Priority.PACKAGE_MEMBER;
    }

    private CompletionItem indexedMember(IndexedMember member) {
        var item = new CompletionItem();
        item.label = member.name;
        item.kind = member.kind;
        item.detail = member.detail;
        item.insertText = member.name;
        item.insertTextFormat = InsertTextFormat.PlainText;
        var data = new CompletionData();
        data.className = member.ownerType;
        data.memberName = member.name;
        item.data = JsonHelper.GSON.toJsonTree(data);
        return item;
    }

    private CompletionItem indexedMethod(List<IndexedMember> overloads, boolean addParens) {
        var first = overloads.get(0);
        var item = new CompletionItem();
        item.label = first.name;
        item.kind = CompletionItemKind.Method;
        item.detail = first.detail;
        if (addParens) {
            var noArgs =
                    overloads.size() == 1
                            && (first.erasedParameterTypes == null || first.erasedParameterTypes.length == 0);
            if (noArgs) {
                item.insertText = first.name + "()";
                item.insertTextFormat = InsertTextFormat.PlainText;
            } else {
                item.insertText = first.name + "($0)";
                item.insertTextFormat = InsertTextFormat.Snippet;
                item.command = new Command();
                item.command.command = "editor.action.triggerParameterHints";
                item.command.title = "Trigger Parameter Hints";
            }
        }
        var data = new CompletionData();
        data.className = first.ownerType;
        data.memberName = first.name;
        data.erasedParameterTypes = first.erasedParameterTypes == null ? new String[0] : first.erasedParameterTypes;
        data.plusOverloads = Math.max(0, overloads.size() - 1);
        item.data = JsonHelper.GSON.toJsonTree(data);
        return item;
    }

    private boolean isImportOrPackageContext(TreePath path) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            var kind = cursor.getLeaf().getKind();
            if (kind == Tree.Kind.IMPORT || kind == Tree.Kind.PACKAGE) {
                return true;
            }
        }
        return false;
    }

    private boolean isWeakObjectOnlyResult(CompletionList list) {
        if (list == null || list == NOT_SUPPORTED || list.items.isEmpty()) {
            return false;
        }
        var sawMember = false;
        for (var item : list.items) {
            if (item == null || item.label == null) {
                continue;
            }
            if (Objects.equals(item.kind, CompletionItemKind.Keyword)) {
                continue;
            }
            sawMember = true;
            if (!OBJECT_MEMBER_LABELS.contains(item.label)) {
                return false;
            }
        }
        return sawMember;
    }

    private CompletionList freezeCompletionList(CompletionList list) {
        return new CompletionList(list.isIncomplete, List.copyOf(list.items));
    }

    private CompletionList copyCompletionList(CompletionList list) {
        return new CompletionList(list.isIncomplete, new ArrayList<>(list.items));
    }

    private CompletionList completeParseOnly(ParseTask parseTask, String contents, long cursor, String partial) {
        var path = new FindCompletionsAt(parseTask.task()).scan(parseTask.root(), cursor);
        if (path == null) {
            return new CompletionList();
        }
        var memberAccess = memberAccessContext(contents, (int) cursor);
        if (memberAccess != null && !isImportOrPackageContext(path)) {
            var list = new CompletionList();
            addSyntacticMemberUsages(parseTask, cursor, memberAccess.receiver, partial, list);
            sortCompletionItems(list.items);
            return list;
        }
        switch (path.getLeaf().getKind()) {
            case IMPORT:
                return completeImport(qualifiedPartialIdentifier(contents, (int) cursor));
            default:
                var enumCase = completeEnumCase(parseTask, path, cursor, partial);
                if (enumCase != null) {
                    return enumCase;
                }
                var list = new CompletionList();
                var endsWithParen = endsWithParen(contents, (int) cursor);
                addKeywords(path, partial, list);
                addSyntacticLocalVariables(parseTask, cursor, partial, list);
                addSyntacticEnclosingTypeMembers(parseTask, path, cursor, partial, list);
                addIndexedEnclosingTypeMembers(parseTask, path, partial, list, endsWithParen);
                addEnclosingInstanceKeywords(path, list);
                addSlf4jLoggerIfAnnotated(parseTask, path, cursor, partial, list);
                addStaticImportsFromIndex(parseTask.root(), partial, false, list);
                addImportedTypeNames(parseTask.root(), partial, list);
                if (!list.isIncomplete && !partial.isEmpty() && Character.isUpperCase(partial.charAt(0))) {
                    addClassNames(parseTask.root(), Trees.instance(parseTask.task()).getSourcePositions(), partial, list);
                }
                boostExactMatches(parseTask.root(), list.items, partial);
                sortCompletionItems(list.items);
                return list;
        }
    }

    private void addTopLevelSnippets(ParseTask task, CompletionList list) {
        var file = Paths.get(task.root().getSourceFile().toUri());
        if (!hasTypeDeclaration(task.root())) {
            list.items.add(classSnippet(file));
            if (task.root().getPackage() == null) {
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

    private void addImportedTypeNames(CompilationUnitTree root, String partial, CompletionList list) {
        var seen = new HashSet<String>();
        for (var item : list.items) {
            if (item.label != null) {
                seen.add(item.label);
            }
        }
        for (var importTree : root.getImports()) {
            if (importTree.isStatic()) continue;
            var qualified = importTree.getQualifiedIdentifier().toString();
            if (qualified.endsWith(".*")) continue;
            var simple = TypeNames.simpleName(qualified);
            if (!matchesCompletionPrefix(simple, partial)) continue;
            if (!seen.add(simple)) continue;
            list.items.add(classItem(qualified));
        }
    }

    private void boostExactMatches(CompilationUnitTree root, List<CompletionItem> items, String partial) {
        if (partial == null || partial.isBlank()) {
            return;
        }
        for (var item : items) {
            if (item.label == null) continue;
            if (!item.label.equalsIgnoreCase(partial)) continue;
            var existingPriority = parseSortPriority(item.sortText);
            var exactPriority = isDirectImportedType(root, item) ? Priority.EXACT_IMPORTED_MATCH : Priority.EXACT_MATCH;
            item.sortText =
                    String.format(
                            "%03d:%03d:%s",
                            exactPriority, existingPriority, item.label.toLowerCase());
        }
    }

    private boolean isDirectImportedType(CompilationUnitTree root, CompletionItem item) {
        if (root == null || item == null || item.label == null) {
            return false;
        }
        if (!Objects.equals(item.kind, CompletionItemKind.Class)
                && !Objects.equals(item.kind, CompletionItemKind.Interface)) {
            return false;
        }
        for (var i : root.getImports()) {
            if (i.isStatic()) continue;
            var imported = i.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) continue;
            if (TypeNames.simpleName(imported).equals(item.label)) {
                return true;
            }
        }
        return false;
    }

    private void resolveDetail(CompletionItem item, CompletionData data, Tree tree) {
        if (tree instanceof MethodTree method) {
            var parameters = new StringJoiner(", ");
            for (var p : method.getParameters()) {
                parameters.add(p.getType() + " " + p.getName());
            }
            item.detail = method.getReturnType() + " " + method.getName() + "(" + parameters + ")";
            if (!method.getThrows().isEmpty()) {
                var exceptions = new StringJoiner(", ");
                for (var e : method.getThrows()) {
                    exceptions.add(e.toString());
                }
                item.detail += " throws " + exceptions;
            }
            if (data.plusOverloads != 0) {
                item.detail += " (+" + data.plusOverloads + " overloads)";
            }
        }
    }

    private Tree findItem(ParseTask task, CompletionData data) {
        if (data.erasedParameterTypes != null) {
            return FindHelper.findMethod(task, data.className, data.memberName, data.erasedParameterTypes);
        }
        if (data.memberName != null) {
            return FindHelper.findField(task, data.className, data.memberName);
        }
        if (data.className != null) {
            var type = FindHelper.findType(task, data.className);
            if (type != null) {
                return type;
            }
            throw new RuntimeException("no type");
        }
        throw new RuntimeException("no className");
    }

    private int parseSortPriority(String sortText) {
        if (sortText == null) {
            return Priority.PACKAGE_MEMBER;
        }
        var colon = sortText.indexOf(':');
        if (colon <= 0) {
            return Priority.PACKAGE_MEMBER;
        }
        try {
            return Integer.parseInt(sortText.substring(0, colon));
        } catch (NumberFormatException e) {
            return Priority.PACKAGE_MEMBER;
        }
    }

    private boolean matchesCompletionPrefix(CharSequence candidate, String partial) {
        if (partial == null) return true;
        if (StringSearch.matchesPartialName(candidate, partial)) return true;
        if (candidate.length() < partial.length()) return false;
        for (int i = 0; i < partial.length(); i++) {
            if (Character.toLowerCase(candidate.charAt(i)) != Character.toLowerCase(partial.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void addKeywords(TreePath path, String partial, CompletionList list) {
        String[] keywords = {};
        var level = findKeywordLevel(path);
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

    private void addSyntacticLocalVariables(ParseTask task, long cursor, String partial, CompletionList list) {
        var trees = Trees.instance(task.task());
        var positions = trees.getSourcePositions();
        var seen = new HashSet<String>();
        for (var item : list.items) {
            seen.add(item.label);
        }
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree t, Void __) {
                var start = positions.getStartPosition(task.root(), t);
                var end = positions.getEndPosition(task.root(), t);
                if (cursor < start || cursor > end) return null;
                return super.visitMethod(t, null);
            }

            @Override
            public Void visitVariable(VariableTree t, Void __) {
                var parent = getCurrentPath().getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree) {
                    return super.visitVariable(t, null);
                }
                var start = positions.getStartPosition(task.root(), t);
                if (start < 0 || start >= cursor) return super.visitVariable(t, null);
                var name = t.getName().toString();
                if (name.isEmpty()) return super.visitVariable(t, null);
                if (!StringSearch.matchesPartialName(name, partial)) return super.visitVariable(t, null);
                if (isTypeLikeIdentifier(name)) {
                    return super.visitVariable(t, null);
                }
                if (seen.add(name)) {
                     list.items.add(variable(name, syntacticType(t)));
                }
                return super.visitVariable(t, null);
            }
        }.scan(task.root(), null);
    }

    private String syntacticType(VariableTree t) {
        var type = t.getType();
        if (type != null) return type.toString();
        var init = t.getInitializer();
        if (init instanceof NewClassTree nct && nct.getIdentifier() != null) {
            return nct.getIdentifier().toString();
        }
        if (init instanceof TypeCastTree cast) {
            return cast.getType().toString();
        }
        return null;
    }

    private void addSyntacticEnclosingTypeMembers(
            ParseTask parseTask, TreePath path, long cursor, String partial, CompletionList list) {
        var classPath = enclosingClassPath(path);
        if (classPath == null) {
            classPath = enclosingClassPath(parseTask, cursor);
        }
        if (classPath == null) {
            return;
        }
        var classTree = (ClassTree) classPath.getLeaf();
        var staticContext = !hasAccessibleEnclosingInstance(path);
        var enclosingOwnerType = qualifiedEnclosingClassName(parseTask.root(), classPath);
        for (var member : classTree.getMembers()) {
            switch (member.getKind()) {
                case VARIABLE:
                    var field = (VariableTree) member;
                    if (!isSyntacticStaticField(classTree, field, staticContext)) {
                        continue;
                    }
                    addSyntacticField(field, partial, list);
                    break;
                case METHOD:
                    var method = (MethodTree) member;
                    if (!isSyntacticStaticMethod(method, staticContext)) {
                        continue;
                    }
                    addSyntacticMethod(method, partial, list);
                    break;
                case CLASS:
                case INTERFACE:
                case ENUM:
                case ANNOTATION_TYPE:
                    addSyntacticNestedType(member, partial, enclosingOwnerType, list);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Enrich parse-only identifier completions with members that come from enclosing source types
     * and their supertypes. This keeps the fast parse/index path while restoring inherited and
     * outer-scope members that are not present in the local syntax tree alone.
     */
    private void addIndexedEnclosingTypeMembers(
            ParseTask parseTask, TreePath path, String partial, CompletionList list, boolean endsWithParen) {
        if (typeIndexRouter == null || path == null) {
            return;
        }
        var seen = new HashSet<String>();
        for (var item : list.items) {
            if (item != null && item.label != null) {
                seen.add(item.label);
            }
        }
        var methods = new TreeMap<String, List<IndexedMember>>();
        var methodPriority = new HashMap<String, Integer>();
        var blockedByStaticContext = false;
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            var leaf = cursor.getLeaf();
            if (leaf instanceof MethodTree methodTree
                    && methodTree.getModifiers() != null
                    && methodTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                blockedByStaticContext = true;
                continue;
            }
            if (leaf instanceof BlockTree blockTree) {
                var parent = cursor.getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree && blockTree.isStatic()) {
                    blockedByStaticContext = true;
                }
                continue;
            }
            if (!(leaf instanceof ClassTree classTree)) {
                continue;
            }
            var qualifiedType = qualifiedEnclosingClassName(parseTask.root(), cursor);
            if (qualifiedType != null) {
                addIndexedEnclosingMembers(
                        qualifiedType,
                        partial,
                        blockedByStaticContext,
                        seen,
                        methods,
                        methodPriority,
                        list);
            }
            if (classTree.getModifiers() != null && classTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                blockedByStaticContext = true;
            }
        }
        for (var entry : methods.entrySet()) {
            var method = indexedMethod(entry.getValue(), !endsWithParen);
            method.sortText =
                    sortKey(
                            methodPriority.getOrDefault(entry.getKey(), Priority.INHERITED_METHOD), method.label);
            replaceSyntacticMethodPlaceholder(list, method);
        }
    }

    private CompletionList completeEnumCase(ParseTask parseTask, TreePath path, long cursor, String partial) {
        if (typeIndexRouter == null || path == null) {
            return null;
        }
        var switchExpression = switchExpression(path);
        if (switchExpression == null) {
            return null;
        }
        var resolver = new ParseTypeResolver(parseTask, compiler, typeIndexRouter, cursor);
        var resolved = resolver.resolve(switchExpression, null);
        if (resolved.isEmpty()) {
            return null;
        }
        var enumType = resolved.get().qualifiedType();
        if (enumType == null || enumType.isBlank()) {
            return null;
        }
        var list = new CompletionList();
        var seen = new HashSet<String>();
        for (var member : typeIndexRouter.members(enumType, true)) {
            if (!matchesCompletionPrefix(member.name, partial)) continue;
            if (!isEnumCaseConstant(member, enumType)) continue;
            if (!seen.add(member.name)) continue;
            var item = indexedMember(member);
            item.sortText = sortKey(Priority.FIELD, item.label);
            list.items.add(item);
        }
        if (list.items.isEmpty()) {
            return null;
        }
        sortCompletionItems(list.items);
        return list;
    }

    private Tree switchExpression(TreePath path) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof SwitchTree switchTree) {
                return switchTree.getExpression();
            }
            if (cursor.getLeaf() instanceof SwitchExpressionTree switchExpressionTree) {
                return switchExpressionTree.getExpression();
            }
        }
        return null;
    }

    private boolean isEnumCaseConstant(IndexedMember member, String enumType) {
        if (member.kind == CompletionItemKind.EnumMember) {
            return true;
        }
        return member.kind == CompletionItemKind.Field
                && member.isStatic
                && sameQualifiedType(enumType, member.returnType);
    }

    private boolean isSyntacticStaticField(ClassTree owner, VariableTree field, boolean staticContext) {
        if (!staticContext) {
            return true;
        }
        return owner.getKind() == Tree.Kind.INTERFACE || field.getModifiers().getFlags().contains(Modifier.STATIC);
    }

    private boolean isSyntacticStaticMethod(MethodTree method, boolean staticContext) {
        if (method.getName().contentEquals("<init>")) {
            return false;
        }
        if (!staticContext) {
            return true;
        }
        return method.getModifiers().getFlags().contains(Modifier.STATIC);
    }

    private void addSyntacticField(VariableTree field, String partial, CompletionList list) {
        var name = field.getName().toString();
        if (!matchesCompletionPrefix(name, partial)) {
            return;
        }
        if (containsCompletionLabel(list, name)) {
            return;
        }
        list.items.add(field(name, syntacticType(field)));
    }

    private void addSyntacticMethod(MethodTree method, String partial, CompletionList list) {
        var name = method.getName().toString();
        if (!matchesCompletionPrefix(name, partial)) {
            return;
        }
        if (containsCompletionLabel(list, name)) {
            return;
        }
        list.items.add(syntacticMethod(name, method));
    }

    private void addSyntacticNestedType(Tree member, String partial, String ownerType, CompletionList list) {
        var nested = (ClassTree) member;
        var name = nested.getSimpleName().toString();
        if (name.isEmpty() || !matchesCompletionPrefix(name, partial)) {
            return;
        }
        if (containsCompletionLabel(list, name)) {
            return;
        }
        list.items.add(syntacticType(name, nested.getKind(), ownerType));
    }

    private boolean isTypeLikeIdentifier(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return Character.isUpperCase(name.charAt(0));
    }

    private void addSlf4jLoggerIfAnnotated(
            ParseTask parseTask, TreePath path, long cursor, String partial, CompletionList list) {
        if (!matchesCompletionPrefix("log", partial)) {
            return;
        }
        if (containsCompletionLabel(list, "log")) {
            return;
        }
        var classPath = enclosingClassPath(path);
        if (classPath == null) {
            classPath = enclosingClassPath(parseTask, cursor);
        }
        if (classPath == null) {
            return;
        }
        var classTree = (ClassTree) classPath.getLeaf();
        if (!LombokAnnotations.hasLoggingOnlyLombokAnnotation(classTree.getModifiers())) {
            return;
        }
        var logger = new CompletionItem();
        logger.label = "log";
        logger.kind = CompletionItemKind.Field;
        logger.detail = "org.slf4j.Logger log";
        logger.insertText = "log";
        logger.insertTextFormat = InsertTextFormat.PlainText;
        logger.sortText = sortKey(Priority.FIELD, logger.label);
        list.items.add(logger);
    }

    private TreePath enclosingClassPath(ParseTask parseTask, long cursor) {
        var positions = Trees.instance(parseTask.task()).getSourcePositions();
        final TreePath[] best = new TreePath[1];
        final int[] bestDepth = {-1};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                var start = positions.getStartPosition(parseTask.root(), classTree);
                var end = positions.getEndPosition(parseTask.root(), classTree);
                if (start < 0 || end < 0 || cursor < start || cursor > end) {
                    return null;
                }
                var depth = pathDepth(getCurrentPath());
                if (depth > bestDepth[0]) {
                    bestDepth[0] = depth;
                    best[0] = getCurrentPath();
                }
                return super.visitClass(classTree, unused);
            }
        }.scan(parseTask.root(), null);
        return best[0];
    }

    private int pathDepth(TreePath path) {
        var depth = 0;
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            depth++;
        }
        return depth;
    }

    private TreePath enclosingClassPath(TreePath path) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree) {
                return cursor;
            }
        }
        return null;
    }

    private boolean containsCompletionLabel(CompletionList list, String label) {
        for (var item : list.items) {
            if (item != null && Objects.equals(label, item.label)) {
                return true;
            }
        }
        return false;
    }

    private void replaceSyntacticMethodPlaceholder(CompletionList list, CompletionItem replacement) {
        for (int i = 0; i < list.items.size(); i++) {
            var item = list.items.get(i);
            if (item == null || !Objects.equals(item.label, replacement.label)) {
                continue;
            }
            if (isSyntacticMethodPlaceholder(item)) {
                list.items.set(i, replacement);
            }
            return;
        }
        list.items.add(replacement);
    }

    private boolean isSyntacticMethodPlaceholder(CompletionItem item) {
        // Syntactic placeholders have no completion data (data is set on indexed items) and no
        // insertText (indexed method items set insertText to "name()" or "name($0)").
        return item != null
                && item.kind == CompletionItemKind.Method
                && item.data == null;
    }

    private void addSyntacticMemberUsages(
            ParseTask task, long cursor, String receiver, String partial, CompletionList list) {
        var trees = Trees.instance(task.task());
        var positions = trees.getSourcePositions();
        var seen = new HashSet<String>();
        for (var item : list.items) {
            seen.add(item.label);
        }
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree invocation, Void __) {
                var select = invocation.getMethodSelect();
                if (select instanceof MemberSelectTree member
                        && receiverMatches(member.getExpression(), receiver)) {
                    var start = positions.getStartPosition(task.root(), invocation);
                    if (start >= 0 && start < cursor) {
                        addMethod(member.getIdentifier().toString(), receiver, partial, list, seen);
                    }
                }
                return super.visitMethodInvocation(invocation, __);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree member, Void __) {
                if (receiverMatches(member.getExpression(), receiver)) {
                    var start = positions.getStartPosition(task.root(), member);
                    if (start >= 0 && start < cursor) {
                        addField(member.getIdentifier().toString(), receiver, partial, list, seen);
                    }
                }
                return super.visitMemberSelect(member, __);
            }

            private boolean receiverMatches(ExpressionTree expression, String expected) {
                if (!(expression instanceof IdentifierTree identifier)) return false;
                return expected.equals(identifier.getName().toString());
            }
        }.scan(task.root(), null);
    }

    private void addMethod(String name, String receiver, String partial, CompletionList list, Set<String> seen) {
        if (name.equals(receiver)) return;
        if (!StringSearch.matchesPartialName(name, partial)) return;
        if (!seen.add(name)) return;
        var method = new CompletionItem();
        method.label = name;
        method.kind = CompletionItemKind.Method;
        method.insertText = name + "($0)";
        method.insertTextFormat = InsertTextFormat.Snippet;
        method.sortText = sortKey(Priority.METHOD, method.label);
        list.items.add(method);
    }

    private void addField(String name, String receiver, String partial, CompletionList list, Set<String> seen) {
        if (name.equals(receiver)) return;
        if (!StringSearch.matchesPartialName(name, partial)) return;
        if (!seen.add(name)) return;
        var field = new CompletionItem();
        field.label = name;
        field.kind = CompletionItemKind.Field;
        field.insertText = name;
        field.insertTextFormat = InsertTextFormat.PlainText;
        field.sortText = sortKey(Priority.FIELD, field.label);
        list.items.add(field);
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


    private void addStaticImportsFromIndex(
            CompilationUnitTree root, String partial, boolean endsWithParen, CompletionList list) {
        if (typeIndexRouter == null || root == null) {
            return;
        }
        var methods = new TreeMap<String, List<IndexedMember>>();
        var methodPriority = new HashMap<String, Integer>();
        var seen = new HashSet<String>();
        for (var item : list.items) {
            if (item != null && item.label != null) {
                seen.add(item.label);
            }
        }
        outer:
        for (var importTree : root.getImports()) {
            if (!importTree.isStatic()) continue;
            if (!(importTree.getQualifiedIdentifier() instanceof MemberSelectTree id)) continue;
            var ownerType = id.getExpression().toString();
            if (!importMatchesPartial(id.getIdentifier(), partial)) continue;
            for (var member : typeIndexRouter.members(ownerType, true)) {
                if (!memberMatchesImport(id.getIdentifier(), member.name)) continue;
                if (!matchesCompletionPrefix(member.name, partial)) continue;
                if (member.kind == CompletionItemKind.Method) {
                    methods.computeIfAbsent(member.name, __ -> new ArrayList<>()).add(member);
                    methodPriority.merge(member.name, indexMemberPriority(member), Math::min);
                } else if (seen.add(member.name)) {
                    var item = indexedMember(member);
                    item.sortText = sortKey(indexMemberPriority(member), item.label);
                    list.items.add(item);
                }
                if (list.items.size() + methods.size() > MAX_COMPLETION_ITEMS) {
                    list.isIncomplete = true;
                    break outer;
                }
            }
        }
        for (var entry : methods.entrySet()) {
            if (!seen.add(entry.getKey())) continue;
            var method = indexedMethod(entry.getValue(), !endsWithParen);
            method.sortText = sortKey(methodPriority.getOrDefault(entry.getKey(), Priority.INHERITED_METHOD), method.label);
            list.items.add(method);
        }
    }

    private boolean importMatchesPartial(Name staticImport, String partial) {
        return staticImport.contentEquals("*") || StringSearch.matchesPartialName(staticImport, partial);
    }

    private boolean memberMatchesImport(Name staticImport, String memberName) {
        return staticImport.contentEquals("*") || staticImport.contentEquals(memberName);
    }

    private void addClassNames(
            CompilationUnitTree root, SourcePositions sourcePositions, String partial, CompletionList list) {
        var packageName = Objects.toString(root.getPackageName(), "");
        var uniques = new HashSet<String>();
        for (var item : list.items) {
            if (item == null) {
                continue;
            }
            if (!Objects.equals(item.kind, CompletionItemKind.Class)
                    && !Objects.equals(item.kind, CompletionItemKind.Interface)
                    && !Objects.equals(item.kind, CompletionItemKind.Enum)) {
                continue;
            }
            if (item.detail != null && !item.detail.isBlank()) {
                uniques.add(item.detail);
            }
        }
        for (var className : compiler.packagePrivateTopLevelTypes(packageName)) {
            if (!StringSearch.matchesPartialName(className, partial)) continue;
            if (!uniques.add(className)) {
                continue;
            }
            var item = classItem(className);
            applyClassSortPriority(root, className, item);
            if (hasImportConflict(root, className)) {
                item.insertText = className;
                item.insertTextFormat = InsertTextFormat.PlainText;
            } else {
                var edits = autoImportEdits(className, root, sourcePositions);
                if (!edits.isEmpty()) {
                    item.additionalTextEdits = edits;
                }
            }
            list.items.add(item);
        }
        for (var className : compiler.publicTopLevelTypes()) {
            if (!StringSearch.matchesPartialName(TypeNames.simpleName(className), partial)) continue;
            if (!uniques.add(className)) {
                continue;
            }
            if (list.items.size() > MAX_COMPLETION_ITEMS) {
                list.isIncomplete = true;
                break;
            }
            var item = classItem(className);
            applyClassSortPriority(root, className, item);
            if (hasImportConflict(root, className)) {
                item.insertText = className;
                item.insertTextFormat = InsertTextFormat.PlainText;
            } else {
                var edits = autoImportEdits(className, root, sourcePositions);
                if (!edits.isEmpty()) {
                    item.additionalTextEdits = edits;
                }
            }
            list.items.add(item);
        }
    }

    private void applyClassSortPriority(CompilationUnitTree root, String className, CompletionItem item) {
        var imported = isDirectlyImported(root, className);
        var samePackage = isSamePackageTopLevel(root, className);
        var javaLang = className.startsWith("java.lang.");
        var priority = (imported || samePackage || javaLang) ? Priority.IMPORTED_CLASS : Priority.NOT_IMPORTED_CLASS;
        item.sortText = sortKey(priority, item.label);
    }

    private boolean isDirectlyImported(CompilationUnitTree root, String className) {
        for (var i : root.getImports()) {
            if (i.isStatic()) continue;
            var imported = i.getQualifiedIdentifier().toString();
            if (imported.equals(className)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSamePackageTopLevel(CompilationUnitTree root, String className) {
        if (className.indexOf('.') < 0) {
            return true;
        }
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (packageName.isEmpty()) {
            return className.indexOf('.') < 0;
        }
        if (!className.startsWith(packageName + ".")) {
            return false;
        }
        return className.substring(packageName.length() + 1).indexOf('.') < 0;
    }

    private List<TextEdit> autoImportEdits(
            String className, CompilationUnitTree root, SourcePositions sourcePositions) {
        if (className.indexOf('.') < 0) {
            return List.of();
        }
        if (className.startsWith("java.lang.")) {
            return List.of();
        }
        var packageTree = root.getPackage();
        if (packageTree != null) {
            var packageName = packageTree.getPackageName().toString();
            if (className.startsWith(packageName + ".")
                    && !className.substring(packageName.length() + 1).contains(".")) {
                return List.of();
            }
        }
        for (var i : root.getImports()) {
            var imported = i.getQualifiedIdentifier().toString();
            if (imported.equals(className)) {
                return List.of();
            }
            if (imported.endsWith(".*")) {
                var importedPackage = imported.substring(0, imported.length() - 1);
                if (className.startsWith(importedPackage)) {
                    return List.of();
                }
            }
        }

        var edits = AddImport.createTextEdits(className, root, sourcePositions);
        return List.of(edits[0]);
    }

    private boolean hasImportConflict(CompilationUnitTree root, String className) {
        if (className.indexOf('.') < 0) {
            return false;
        }
        var targetSimple = TypeNames.simpleName(className);
        for (var i : root.getImports()) {
            if (i.isStatic()) continue;
            var imported = i.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) continue;
            if (imported.equals(className)) continue;
            var importedSimple = TypeNames.simpleName(imported);
            if (importedSimple.equals(targetSimple)) {
                return true;
            }
        }
        return false;
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

    private void addEnclosingInstanceKeywords(TreePath path, CompletionList list) {
        if (!hasAccessibleEnclosingInstance(path)) {
            return;
        }
        if (!containsCompletionLabel(list, "this")) {
            list.items.add(keyword("this"));
        }
        if (!containsCompletionLabel(list, "super")) {
            list.items.add(keyword("super"));
        }
    }

    private boolean hasAccessibleEnclosingInstance(TreePath path) {
        if (path == null) {
            return false;
        }
        var blockedByStaticContext = false;
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            var leaf = cursor.getLeaf();
            if (leaf instanceof MethodTree methodTree
                    && methodTree.getModifiers() != null
                    && methodTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                blockedByStaticContext = true;
                continue;
            }
            if (leaf instanceof BlockTree blockTree) {
                var parent = cursor.getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree && blockTree.isStatic()) {
                    blockedByStaticContext = true;
                }
                continue;
            }
            if (leaf instanceof ClassTree) {
                return !blockedByStaticContext;
            }
        }
        return false;
    }

    private boolean isAccessibleEnclosingInstanceType(
            ParseTask parseTask, TreePath path, long cursor, String qualifiedType, String receiverName) {
        if (path == null
                || ((qualifiedType == null || qualifiedType.isBlank())
                        && (receiverName == null || receiverName.isBlank()))) {
            return false;
        }
        for (var classPath = enclosingClassPath(parseTask, cursor);
                classPath != null;
                classPath = parentEnclosingClassPath(classPath)) {
            var classTree = (ClassTree) classPath.getLeaf();
            var enclosingType = qualifiedEnclosingClassName(parseTask.root(), classPath);
            if (!sameQualifiedType(qualifiedType, enclosingType)
                    && !sameSimpleTypeName(receiverName, classTree.getSimpleName().toString())
                    && !sameSimpleTypeName(qualifiedType, classTree.getSimpleName().toString())) {
                continue;
            }
            return !hasStaticBoundaryBetween(path, classPath);
        }
        var nearestNamedClass = nearestNamedEnclosingClassPath(parseTask, cursor);
        if (nearestNamedClass != null) {
            var classTree = (ClassTree) nearestNamedClass.getLeaf();
            var enclosingType = qualifiedEnclosingClassName(parseTask.root(), nearestNamedClass);
            if ((sameQualifiedType(qualifiedType, enclosingType)
                            || sameSimpleTypeName(receiverName, classTree.getSimpleName().toString())
                            || sameSimpleTypeName(qualifiedType, classTree.getSimpleName().toString()))
                    && !hasStaticBoundaryBetween(path, nearestNamedClass)) {
                return true;
            }
        }
        return false;
    }

    private TreePath parentEnclosingClassPath(TreePath classPath) {
        if (classPath == null) {
            return null;
        }
        for (var cursor = classPath.getParentPath(); cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree) {
                return cursor;
            }
        }
        return null;
    }

    private TreePath nearestNamedEnclosingClassPath(ParseTask parseTask, long cursor) {
        for (var classPath = enclosingClassPath(parseTask, cursor);
                classPath != null;
                classPath = parentEnclosingClassPath(classPath)) {
            var classTree = (ClassTree) classPath.getLeaf();
            if (!classTree.getSimpleName().contentEquals("")) {
                return classPath;
            }
        }
        return null;
    }

    private boolean hasStaticBoundaryBetween(TreePath path, TreePath targetClassPath) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() == targetClassPath.getLeaf()) {
                return false;
            }
            var leaf = cursor.getLeaf();
            if (leaf instanceof MethodTree methodTree
                    && methodTree.getModifiers() != null
                    && methodTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                return true;
            }
            if (leaf instanceof BlockTree blockTree) {
                var parent = cursor.getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree && blockTree.isStatic()) {
                    return true;
                }
            }
            if (leaf instanceof ClassTree classTree
                    && classTree.getModifiers() != null
                    && classTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                return true;
            }
        }
        return true;
    }

    private String qualifiedEnclosingClassName(CompilationUnitTree root, TreePath classPath) {
        var classes = new ArrayList<String>();
        for (var cursor = classPath; cursor != null; cursor = cursor.getParentPath()) {
            if (!(cursor.getLeaf() instanceof ClassTree classTree)) {
                continue;
            }
            var simpleName = classTree.getSimpleName().toString();
            if (simpleName.isBlank()) {
                return null;
            }
            classes.add(simpleName);
        }
        if (classes.isEmpty()) {
            return null;
        }
        Collections.reverse(classes);
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        var qualified = String.join(".", classes);
        return packageName.isEmpty() ? qualified : packageName + "." + qualified;
    }

    private void addIndexedEnclosingMembers(
            String qualifiedType,
            String partial,
            boolean blockedByStaticContext,
            Set<String> seen,
            Map<String, List<IndexedMember>> methods,
            Map<String, Integer> methodPriority,
            CompletionList list) {
        addIndexedEnclosingMembersForStaticContext(
                qualifiedType, partial, true, seen, methods, methodPriority, list);
        if (!blockedByStaticContext) {
            addIndexedEnclosingMembersForStaticContext(
                    qualifiedType, partial, false, seen, methods, methodPriority, list);
        }
    }

    private void addIndexedEnclosingMembersForStaticContext(
            String qualifiedType,
            String partial,
            boolean staticContext,
            Set<String> seen,
            Map<String, List<IndexedMember>> methods,
            Map<String, Integer> methodPriority,
            CompletionList list) {
        for (var member : typeIndexRouter.members(qualifiedType, staticContext)) {
            if (!matchesCompletionPrefix(member.name, partial)) continue;
            if (member.kind == CompletionItemKind.Method) {
                methods.computeIfAbsent(member.name, __ -> new ArrayList<>()).add(member);
                methodPriority.merge(member.name, indexMemberPriority(member), Math::min);
            } else if (seen.add(member.name)) {
                var item = indexedMember(member);
                item.sortText = sortKey(indexMemberPriority(member), item.label);
                list.items.add(item);
            }
        }
    }

    private boolean sameQualifiedType(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right) || left.replace('$', '.').equals(right.replace('$', '.'));
    }

    private boolean sameSimpleTypeName(String maybeQualified, String simpleName) {
        if (maybeQualified == null || maybeQualified.isBlank() || simpleName == null || simpleName.isBlank()) {
            return false;
        }
        return TypeNames.simpleName(maybeQualified.replace('$', '.')).equals(simpleName);
    }

    private static final CompletionList EMPTY = new CompletionList(false, List.of());

    private CompletionList completeImport(String path) {
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
        var i = new CompletionItem();
        i.label = TypeNames.simpleName(className);
        i.kind = CompletionItemKind.Class;
        i.detail = className;
        i.sortText = sortKey(Priority.IMPORTED_CLASS, i.label);
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

    private CompletionItem keyword(String keyword) {
        var i = new CompletionItem();
        i.label = keyword;
        i.kind = CompletionItemKind.Keyword;
        i.detail = "keyword";
        i.sortText = sortKey(Priority.KEYWORD, i.label);
        return i;
    }

    private CompletionItem variable(String name, String type) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Variable;
        i.detail = type != null ? type : "local";
        i.sortText = sortKey(Priority.LOCAL, i.label);
        return i;
    }

    private CompletionItem field(String name, String type) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Field;
        i.detail = type != null ? type : "field";
        i.sortText = sortKey(Priority.FIELD, i.label);
        return i;
    }

    private CompletionItem syntacticMethod(String name, MethodTree method) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Method;
        i.detail = method.getReturnType() != null ? method.getReturnType().toString() : "void";
        i.sortText = sortKey(Priority.METHOD, i.label);
        return i;
    }

    private CompletionItem syntacticType(String name, Tree.Kind kind, String ownerType) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = kind == Tree.Kind.INTERFACE || kind == Tree.Kind.ANNOTATION_TYPE
                ? CompletionItemKind.Interface
                : kind == Tree.Kind.ENUM ? CompletionItemKind.Enum : CompletionItemKind.Class;
        i.detail = ownerType != null ? ownerType : "member type";
        i.sortText = sortKey(Priority.IMPORTED_CLASS, i.label);
        return i;
    }

    private static class Priority {
        static final int EXACT_IMPORTED_MATCH = -2;
        static final int EXACT_MATCH = -1;
        static int iota = 0;
        static final int SNIPPET = iota++;
        static final int LOCAL = iota++;
        static final int FIELD = iota++;
        static final int METHOD = iota++;
        static final int INHERITED_METHOD = iota++;
        static final int IMPORTED_CLASS = iota++;
        static final int NOT_IMPORTED_CLASS = iota++;
        static final int KEYWORD = iota++;
        static final int PACKAGE_MEMBER = iota++;
    }

    private boolean isAccessorMethodName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return Character.isUpperCase(name.charAt(3));
        }
        if (name.startsWith("set") && name.length() > 3) {
            return Character.isUpperCase(name.charAt(3));
        }
        if (name.startsWith("is") && name.length() > 2) {
            return Character.isUpperCase(name.charAt(2));
        }
        return false;
    }

    private boolean isUtilityMethodName(String name) {
        if (name == null) {
            return false;
        }
        return "toString".equals(name)
                || "hashCode".equals(name)
                || "equals".equals(name)
                || "canEqual".equals(name);
    }

    private String sortKey(int priority, String label) {
        var safe = label == null ? "" : label;
        return String.format("%03d:%s", priority, safe.toLowerCase());
    }

    private void sortCompletionItems(List<CompletionItem> items) {
        items.sort(
                Comparator.comparing((CompletionItem item) -> item.sortText == null ? sortKey(Priority.PACKAGE_MEMBER, item.label) : item.sortText)
                        .thenComparing(item -> item.label == null ? "" : item.label, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(item -> item.kind));
    }

    private List<IndexedMember> mergeMemberContexts(TypeIndexRouter index, String qualifiedType) {
        var merged = new ArrayList<IndexedMember>();
        merged.addAll(index.members(qualifiedType, true));
        merged.addAll(index.members(qualifiedType, false));
        return merged;
    }

    private AnnotationContext findAnnotationContext(ParseTask task, long cursor) {
        if (typeIndexRouter == null) return null;
        var positions = Trees.instance(task.task()).getSourcePositions();
        var root = task.root();
        var result = new com.sun.source.tree.AnnotationTree[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitAnnotation(com.sun.source.tree.AnnotationTree node, Void p) {
                var start = positions.getStartPosition(root, node);
                var end = positions.getEndPosition(root, node);
                if (start <= cursor && cursor <= end) {
                    // Check cursor is inside the parentheses, not on the annotation name
                    var typeEnd = positions.getEndPosition(root, node.getAnnotationType());
                    if (cursor > typeEnd) {
                        result[0] = node;
                    }
                }
                return super.visitAnnotation(node, p);
            }
        }.scan(root, null);
        if (result[0] == null) return null;
        String source;
        try {
            source = root.getSourceFile().getCharContent(true).toString();
        } catch (java.io.IOException e) {
            return null;
        }
        var partial = partialIdentifier(source, (int) cursor);
        // After '=' means value position — let normal completion handle it
        var beforePartial = (int) cursor - partial.length();
        for (int i = beforePartial - 1; i >= 0; i--) {
            var c = source.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == '=') return null;
            break;
        }
        var typeName = result[0].getAnnotationType().toString();
        var qualified = typeIndexRouter.resolveTypeName(typeName, root);
        if (qualified.isEmpty()) return null;
        return new AnnotationContext(qualified.get(), partial);
    }

    private static final Set<String> ANNOTATION_INHERITED_METHODS =
            Set.of("annotationType", "toString", "equals", "hashCode");

    private CompletionList completeAnnotationAttributes(AnnotationContext ctx) {
        var members = typeIndexRouter.members(ctx.qualifiedAnnotationType(), false);
        if (members.isEmpty()) return new CompletionList(false, List.of());
        var list = new ArrayList<CompletionItem>();
        for (var member : members) {
            if (member.kind != CompletionItemKind.Method) continue;
            if (ANNOTATION_INHERITED_METHODS.contains(member.name)) continue;
            if (!matchesCompletionPrefix(member.name, ctx.partial())) continue;
            var item = new CompletionItem();
            item.label = member.name;
            item.kind = CompletionItemKind.Property;
            item.detail = member.declaredReturnType != null ? member.declaredReturnType : "";
            item.insertText = member.name + " = ";
            item.insertTextFormat = InsertTextFormat.PlainText;
            item.sortText = sortKey(Priority.FIELD, member.name);
            list.add(item);
        }
        sortCompletionItems(list);
        return new CompletionList(false, list);
    }

    private MemberAccessContext memberAccessContext(String contents, int cursor) {
        if (cursor < 0 || cursor > contents.length()) {
            return null;
        }
        var effectiveCursor = cursor;
        if (effectiveCursor < contents.length() && contents.charAt(effectiveCursor) == '.') {
            effectiveCursor++;
        }
        var partialStart = effectiveCursor;
        while (partialStart > 0 && Character.isJavaIdentifierPart(contents.charAt(partialStart - 1))) {
            partialStart--;
        }
        var separatorEnd = partialStart - 1;
        var methodReference = false;
        if (separatorEnd < 0) {
            return null;
        }
        var receiverEnd = separatorEnd;
        if (contents.charAt(separatorEnd) == '.') {
            methodReference = false;
        } else if (separatorEnd > 0
                && contents.charAt(separatorEnd) == ':'
                && contents.charAt(separatorEnd - 1) == ':') {
            methodReference = true;
            receiverEnd = separatorEnd - 1;
        } else {
            return null;
        }
        var receiverStart = receiverEnd;
        while (receiverStart > 0 && Character.isJavaIdentifierPart(contents.charAt(receiverStart - 1))) {
            receiverStart--;
        }
        var receiver = receiverStart < receiverEnd ? contents.substring(receiverStart, receiverEnd) : "";
        var partial = contents.substring(partialStart, effectiveCursor);
        return new MemberAccessContext(receiver, partial, partial.isEmpty(), methodReference, separatorEnd);
    }

    /**
     * Find the expression tree in the parse task whose end position is closest to (but not past)
     * {@code separatorEnd}. Used as a fallback when FindCompletionsAt cannot match the cursor to a
     * MemberSelectTree, which happens during error recovery for double-dot edits (e.g. {@code
     * expr()..member}).
     */
    private Tree findExpressionEndingAt(ParseTask task, int separatorEnd) {
        var pos = Trees.instance(task.task()).getSourcePositions();
        var root = task.root();
        var bestEnd = new long[]{-1};
        var result = new Tree[]{null};
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void p) {
                if (tree == null) return null;
                var end = pos.getEndPosition(root, tree);
                // Use >= so that when multiple expressions share the same end position
                // (e.g. a lambda and its body under javac error recovery), the innermost
                // (deepest/last-scanned) one wins rather than the outermost wrapper.
                if (end >= bestEnd[0] && end <= separatorEnd && isExpressionTree(tree)) {
                    bestEnd[0] = end;
                    result[0] = tree;
                }
                return super.scan(tree, p);
            }

            @Override
            public Void visitErroneous(com.sun.source.tree.ErroneousTree node, Void p) {
                // TreeScanner.visitErroneous returns null without recursing;
                // we need to descend into error trees to find valid sub-expressions
                // that javac wrapped (e.g. CompleteExpression inside "CompleteExpression..").
                if (node.getErrorTrees() != null) {
                    for (var e : node.getErrorTrees()) {
                        scan(e, p);
                    }
                }
                return null;
            }
        }.scan(root, null);
        return result[0];
    }

    private boolean isExpressionTree(Tree tree) {
        return switch (tree.getKind()) {
            case METHOD_INVOCATION, MEMBER_SELECT, IDENTIFIER, NEW_CLASS, NEW_ARRAY,
                    INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL,
                    BOOLEAN_LITERAL, CHAR_LITERAL, STRING_LITERAL, NULL_LITERAL,
                    TYPE_CAST, ARRAY_ACCESS, PARENTHESIZED,
                    POSTFIX_INCREMENT, POSTFIX_DECREMENT, PREFIX_INCREMENT, PREFIX_DECREMENT,
                    UNARY_PLUS, UNARY_MINUS, BITWISE_COMPLEMENT, LOGICAL_COMPLEMENT,
                    MULTIPLY, DIVIDE, REMAINDER, PLUS, MINUS, LEFT_SHIFT, RIGHT_SHIFT,
                    UNSIGNED_RIGHT_SHIFT, AND, XOR, OR, CONDITIONAL_AND, CONDITIONAL_OR,
                    EQUAL_TO, NOT_EQUAL_TO, LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN,
                    GREATER_THAN_EQUAL, CONDITIONAL_EXPRESSION, ASSIGNMENT,
                    MEMBER_REFERENCE, LAMBDA_EXPRESSION -> true;
            default -> false;
        };
    }

}
