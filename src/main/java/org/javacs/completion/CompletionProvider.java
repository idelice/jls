package org.javacs.completion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
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
import java.util.TreeMap;
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
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import org.javacs.CacheAudit;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.CompletionData;
import org.javacs.FileStore;
import org.javacs.JsonHelper;
import org.javacs.LombokAnnotations;
import org.javacs.ParseTask;
import org.javacs.StringSearch;
import org.javacs.TypeLookupBoundary;
import org.javacs.lsp.Command;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.InsertTextFormat;
import org.javacs.lsp.TextEdit;
import org.javacs.rewrite.AddImport;

public class CompletionProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final CompilerProvider compiler;
    private final CompositeTypeIndex completionIndex;
    private final long completionIndexVersion;

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
    private static final Set<String> OBJECT_MEMBER_LABELS =
            Set.of("equals", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");
    private static final Cache<MemberCompletionCacheKey, CompletionList> MEMBER_COMPLETION_CACHE =
            Caffeine.newBuilder().maximumSize(20_000).build();

    private record MemberCompletionCacheKey(
            long indexVersion,
            String targetType,
            boolean staticContext,
            String partial,
            boolean endsWithParen,
            String currentType) {}

    private record IndexedCompletionResult(CompletionList list, long resolveMs, String cacheState) {}

    public CompletionProvider(CompilerProvider compiler, CompositeTypeIndex completionIndex, long completionIndexVersion) {
        this.compiler = compiler;
        this.completionIndex = completionIndex == null ? CompositeTypeIndex.EMPTY : completionIndex;
        this.completionIndexVersion = Math.max(0L, completionIndexVersion);
    }

    public CompletionList complete(Path file, int line, int column) {
        var started = Instant.now();
        var parseStarted = Instant.now();
        var task = compiler.parse(file);
        var parseMs = Duration.between(parseStarted, Instant.now()).toMillis();
        long cursor;
        try {
            cursor = FileStore.offset(task.root().getSourceFile().getCharContent(true).toString(), line, column);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        var contents = new PruneMethodBodies(task.task()).scan(task.root(), cursor);
        var endOfLine = endOfLine(contents, (int) cursor);
        contents.insert(endOfLine, ';');
        var pruned = contents.toString();
        var memberAccess = memberAccessContext(pruned, (int) cursor);
        var parsePath = new FindCompletionsAt(task.task()).scan(task.root(), cursor);
        CompletionList list;
        String mode;
        long resolveMs = 0;
        String memberCacheState = "n/a";
        if (memberAccess != null && isImportOrPackageContext(parsePath)) {
            // `import foo.bar.` should stay on the import completion path.
            list = completeParseOnly(task, pruned, cursor, memberAccess.partial);
            mode = "import_parse";
        } else if (memberAccess != null) {
            var indexed =
                    completeParseAndIndex(
                            task,
                            parsePath,
                            cursor,
                            memberAccess,
                            endsWithParen(pruned, (int) cursor));
            list = indexed.list();
            resolveMs = indexed.resolveMs();
            memberCacheState = indexed.cacheState();
            mode = "member_index";
        } else {
            list = completeParseOnly(task, pruned, cursor, partialIdentifier(pruned, (int) cursor));
            mode = "identifier_parse";
        }
        if (list == NOT_SUPPORTED) {
            return NOT_SUPPORTED;
        }
        // Some sentinel paths use immutable lists (List.of()).
        // Completion post-processing mutates/sorts the list, so ensure mutability.
        if (!(list.items instanceof ArrayList)) {
            list.items = new ArrayList<>(list.items);
        }
        addTopLevelSnippets(task, list);
        sortCompletionItems(list.items);
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

    private IndexedCompletionResult completeParseAndIndex(
            ParseTask parseTask,
            TreePath parsePath,
            long cursor,
            MemberAccessContext memberAccess,
            boolean endsWithParen) {
        return completeMembersUsingIndex(parseTask, parsePath, cursor, memberAccess, endsWithParen);
    }

    private IndexedCompletionResult completeMembersUsingIndex(
            ParseTask parseTask,
            TreePath parsePath,
            long cursor,
            MemberAccessContext memberAccess,
            boolean endsWithParen) {
        if (completionIndex == null) {
            return new IndexedCompletionResult(EMPTY, 0, "index_empty");
        }
        var index = completionIndex;
        var started = Instant.now();
        var resolver = new ParseTypeResolver(parseTask, compiler, index, cursor);
        var expression = memberReceiverExpression(parsePath);
        var resolveStarted = Instant.now();
        var resolved = resolver.resolve(expression, memberAccess.receiver);
        var resolveMs = Duration.between(resolveStarted, Instant.now()).toMillis();
        if (resolved.isEmpty()) {
            return new IndexedCompletionResult(EMPTY, resolveMs, "unresolved_type");
        }
        var target = resolved.get();
        if (!target.arrayType && !isQualifiedTypeName(target.qualifiedType)) {
            return new IndexedCompletionResult(EMPTY, resolveMs, "non_fqn_type");
        }
        var currentType = resolver.currentEnclosingTypeName();
        if (target.arrayType) {
            return new IndexedCompletionResult(
                    completeArrayMemberSelect(target.staticContext), resolveMs, "array_type");
        }
        var membersStarted = Instant.now();
        var members = index.members(target.qualifiedType, target.staticContext);
        var membersMs = Duration.between(membersStarted, Instant.now()).toMillis();
        if (members.isEmpty()) {
            return new IndexedCompletionResult(EMPTY, resolveMs + membersMs, "no_members");
        }
        var cacheKey =
                new MemberCompletionCacheKey(
                        completionIndexVersion,
                        target.qualifiedType,
                        target.staticContext,
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
        var methods = new TreeMap<String, List<TypeMemberIndex.Member>>();
        var methodPriority = new HashMap<String, Integer>();
        for (var member : members) {
            if (!isIndexMemberVisible(member, target.qualifiedType, currentType)) continue;
            if (!matchesCompletionPrefix(member.name, memberAccess.partial)) continue;
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
            var method = indexedMethod(entry.getValue(), !endsWithParen);
            method.sortText = sortKey(methodPriority.getOrDefault(entry.getKey(), Priority.INHERITED_METHOD), method.label);
            list.add(method);
        }
        if (target.staticContext) {
            list.add(keyword("class"));
            if (resolver.isEnclosingInstanceType(target.qualifiedType)) {
                list.add(keyword("this"));
                list.add(keyword("super"));
            }
        }
        var result = new CompletionList(false, list);
        var buildMs = Duration.between(buildStarted, Instant.now()).toMillis();
        if (memberAccess.partial.isEmpty()
                && !"java.lang.Object".equals(target.qualifiedType)
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
            TypeMemberIndex.Member member, String targetType, Optional<String> currentEnclosingType) {
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
        }
        return null;
    }

    private boolean isQualifiedTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        return typeName.contains(".") || TypeMemberIndex.isPrimitiveTypeName(typeName);
    }

    private int indexMemberPriority(TypeMemberIndex.Member member) {
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

    private CompletionItem indexedMember(TypeMemberIndex.Member member) {
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

    private CompletionItem indexedMethod(List<TypeMemberIndex.Member> overloads, boolean addParens) {
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

    private record TypeResolution(String qualifiedType, boolean staticContext, boolean arrayType,
                                  String firstTypeArgument) {

        TypeResolution(String qualifiedType, boolean staticContext, boolean arrayType) {
                this(qualifiedType, staticContext, arrayType, null);
            }

            TypeResolution withFirstTypeArgument(String nextFirstTypeArgument) {
                return new TypeResolution(qualifiedType, staticContext, arrayType, nextFirstTypeArgument);
            }
        }

    private record CandidateType(TypeResolution resolution, int depth, long start) {
    }

    private static class ParseTypeResolver {
        private static final int MAX_RESOLVE_DEPTH = 24;

        private static final class SourceClassInfo {
            final CompilationUnitTree sourceRoot;
            final TreePath classPath;
            final ClassTree classTree;

            SourceClassInfo(CompilationUnitTree sourceRoot, TreePath classPath) {
                this.sourceRoot = sourceRoot;
                this.classPath = classPath;
                this.classTree = (ClassTree) classPath.getLeaf();
            }
        }

        private final CompilationUnitTree root;
        private final SourcePositions positions;
        private final CompilerProvider compiler;
        private final TypeLookupBoundary typeLookup;
        private final CompositeTypeIndex index;
        private final long cursor;
        private final TreePath cursorPath;
        private ClassLoader externalClassLoader;
        private TypeResolution thisType;
        private TypeResolution superType;

        ParseTypeResolver(ParseTask parseTask, CompilerProvider compiler, CompositeTypeIndex index, long cursor) {
            this.root = parseTask.root();
            this.positions = Trees.instance(parseTask.task()).getSourcePositions();
            this.compiler = compiler;
            this.index = index;
            this.typeLookup = new TypeLookupBoundary(compiler, index);
            this.cursor = cursor;
            this.cursorPath = new FindCompletionsAt(parseTask.task()).scan(parseTask.root(), cursor);
        }

        Optional<TypeResolution> resolve(Tree expression, String fallbackIdentifier) {
            if (expression != null) {
                var direct = resolveExpression(expression, 0);
                if (direct.isPresent()) {
                    return direct;
                }
            }
            if (fallbackIdentifier == null || fallbackIdentifier.isBlank()) {
                return Optional.empty();
            }
            return resolveIdentifier(fallbackIdentifier, 0);
        }

        private Optional<TypeResolution> resolveExpression(Tree expression, int depth) {
            if (expression == null || depth > MAX_RESOLVE_DEPTH) {
                return Optional.empty();
            }
            if (expression instanceof ParenthesizedTree parenthesized) {
                return resolveExpression(parenthesized.getExpression(), depth + 1);
            }
            if (expression instanceof IdentifierTree identifier) {
                return resolveIdentifier(identifier.getName().toString(), depth + 1);
            }
            if (expression instanceof NewClassTree newClassTree) {
                return resolveTypeTree(newClassTree.getIdentifier(), root, false);
            }
            if (expression instanceof NewArrayTree newArrayTree) {
                if (newArrayTree.getType() == null) {
                    return Optional.empty();
                }
                var component = resolveTypeTree(newArrayTree.getType(), root, false);
                return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType, false, true));
            }
            if (expression instanceof MethodInvocationTree invocationTree) {
                return resolveMethodInvocation(invocationTree, depth + 1);
            }
            if (expression instanceof MemberSelectTree memberSelectTree) {
                return resolveMemberSelect(memberSelectTree, depth + 1);
            }
            if (expression instanceof TypeCastTree castTree) {
                return resolveTypeTree(castTree.getType(), root, false);
            }
            if (expression instanceof ArrayAccessTree arrayAccessTree) {
                var array = resolveExpression(arrayAccessTree.getExpression(), depth + 1);
                if (array.isEmpty()) {
                    return Optional.empty();
                }
                var type = array.get();
                if (!type.arrayType || type.qualifiedType == null || type.qualifiedType.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new TypeResolution(type.qualifiedType, false, false));
            }
            if (expression instanceof LiteralTree literal) {
                return literalType(literal);
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> resolveMethodInvocation(MethodInvocationTree invocationTree, int depth) {
            var select = invocationTree.getMethodSelect();
            if (select instanceof IdentifierTree identifier) {
                var current = resolveThisType();
                if (current.isEmpty()) {
                    return Optional.empty();
                }
                var method =
                        resolveMemberFromIndexOrSource(
                                current.get().qualifiedType, identifier.getName().toString(), false);
                if (method.isEmpty()) {
                    return Optional.empty();
                }
                return returnTypeOf(method.get());
            }
            if (select instanceof MemberSelectTree memberSelectTree) {
                var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
                if (receiver.isEmpty()) {
                    return Optional.empty();
                }
                var method =
                        resolveMemberFromIndexOrSource(
                                receiver.get().qualifiedType,
                                memberSelectTree.getIdentifier().toString(),
                                receiver.get().staticContext);
                if (method.isEmpty()) {
                    return Optional.empty();
                }
                var external = resolveExternalMethodReturnType(receiver.get(), method.get());
                if (external.isPresent()) {
                    return external;
                }
                return returnTypeOf(method.get());
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> resolveMemberSelect(MemberSelectTree memberSelectTree, int depth) {
            var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
            if (receiver.isEmpty()) {
                return resolveTypeTree(memberSelectTree, root, true);
            }
            if (receiver.get().arrayType && "length".equals(memberSelectTree.getIdentifier().toString())) {
                return Optional.of(new TypeResolution("int", false, false));
            }
            if (receiver.get().staticContext && "class".equals(memberSelectTree.getIdentifier().toString())) {
                return Optional.of(new TypeResolution("java.lang.Class", false, false));
            }
            var member =
                    resolveMemberFromIndexOrSource(
                            receiver.get().qualifiedType,
                            memberSelectTree.getIdentifier().toString(),
                            receiver.get().staticContext);
            if (member.isEmpty()) {
                return Optional.empty();
            }
            return returnTypeOf(member.get());
        }

        private Optional<TypeResolution> resolveIdentifier(String identifier, int depth) {
            if ("this".equals(identifier)) {
                return resolveThisType();
            }
            if ("super".equals(identifier)) {
                return resolveSuperType();
            }
            var variable = resolveVisibleVariable(identifier, depth + 1);
            if (variable.isPresent()) {
                return variable;
            }
            var implicitLogger = resolveImplicitSlf4jLogger(identifier);
            if (implicitLogger.isPresent()) {
                return implicitLogger;
            }
            var enclosingField = resolveEnclosingField(identifier);
            if (enclosingField.isPresent()) {
                return enclosingField;
            }
            var nested = resolveNestedTypeInEnclosingScopes(identifier);
            if (nested.isPresent()) {
                return Optional.of(new TypeResolution(nested.get(), true, false));
            }
            var type = typeLookup.resolveTypeName(identifier, root);
            return type.map(s -> new TypeResolution(s, true, false));
        }

        private Optional<TypeResolution> resolveEnclosingField(String identifier) {
            if (identifier == null || identifier.isBlank()) {
                return Optional.empty();
            }
            var current = resolveThisType();
            if (current.isEmpty()) {
                return Optional.empty();
            }
            var field = resolveMemberFromIndexOrSource(current.get().qualifiedType, identifier, false);
            if (field.isEmpty() || field.get().kind != CompletionItemKind.Field) {
                return Optional.empty();
            }
            return returnTypeOf(field.get());
        }

        private Optional<TypeMemberIndex.Member> resolveMemberFromIndexOrSource(
                String ownerType, String memberName, boolean staticContext) {
            var indexed = resolveIndexedMember(ownerType, memberName, staticContext, null);
            if (indexed.isPresent()) {
                return indexed;
            }
            return resolveSourceMember(ownerType, memberName, staticContext);
        }

        private Optional<TypeMemberIndex.Member> resolveIndexedMember(
                String ownerType, String memberName, boolean staticContext, String[] erasedParameterTypes) {
            if (index.isWorkspaceOwnedType(ownerType, compiler)) {
                return erasedParameterTypes == null
                        ? index.workspace().member(ownerType, memberName, staticContext)
                        : index.workspace().member(ownerType, memberName, staticContext, erasedParameterTypes);
            }
            return erasedParameterTypes == null
                    ? index.external().rawMember(ownerType, memberName, staticContext)
                    : index.external().rawMember(ownerType, memberName, staticContext, erasedParameterTypes);
        }

        private Optional<TypeMemberIndex.Member> resolveSourceMember(
                String ownerType, String memberName, boolean staticContext) {
            var info = sourceClassInfo(ownerType);
            if (info.isEmpty()) {
                return Optional.empty();
            }
            for (var member : info.get().classTree.getMembers()) {
                if (member instanceof VariableTree field) {
                    if (!field.getName().contentEquals(memberName)) {
                        continue;
                    }
                    var isStatic =
                            field.getModifiers() != null
                                    && field.getModifiers().getFlags().contains(Modifier.STATIC);
                    if (isStatic != staticContext) {
                        continue;
                    }
                    var fieldType = resolveTypeTree(field.getType(), info.get().sourceRoot, false);
                    if (fieldType.isEmpty()) {
                        continue;
                    }
                    return Optional.of(
                            new TypeMemberIndex.Member(
                                    ownerType,
                                    memberName,
                                    CompletionItemKind.Field,
                                    isStatic,
                                    field.getModifiers() != null
                                            && field.getModifiers().getFlags().contains(Modifier.PRIVATE),
                                    0,
                                    field.getType() + " " + memberName,
                                    typeName(fieldType.get()),
                                    null,
                                    null,
                                    TypeMemberIndex.canonicalMemberKey(ownerType, CompletionItemKind.Field, memberName, null),
                                    TypeMemberIndex.canonicalMemberKey(ownerType, CompletionItemKind.Field, memberName, null),
                                    null,
                                    false));
                }
                if (member instanceof MethodTree method) {
                    if (!method.getName().contentEquals(memberName)) {
                        continue;
                    }
                    var isStatic =
                            method.getModifiers() != null
                                    && method.getModifiers().getFlags().contains(Modifier.STATIC);
                    if (isStatic != staticContext) {
                        continue;
                    }
                    var returnType =
                            method.getReturnType() == null
                                    ? Optional.<TypeResolution>empty()
                                    : resolveTypeTree(method.getReturnType(), info.get().sourceRoot, false);
                    var parameterNames = new String[method.getParameters().size()];
                    var erasedParameterTypes = new String[method.getParameters().size()];
                    for (int i = 0; i < method.getParameters().size(); i++) {
                        var parameter = method.getParameters().get(i);
                        parameterNames[i] = parameter.getName().toString();
                        erasedParameterTypes[i] = parameter.getType().toString();
                    }
                    return Optional.of(
                            new TypeMemberIndex.Member(
                                    ownerType,
                                    memberName,
                                    CompletionItemKind.Method,
                                    isStatic,
                                    method.getModifiers() != null
                                            && method.getModifiers().getFlags().contains(Modifier.PRIVATE),
                                    0,
                                    method.toString(),
                                    returnType.map(ParseTypeResolver::typeName).orElse(null),
                                    parameterNames,
                                    erasedParameterTypes,
                                    TypeMemberIndex.canonicalMemberKey(
                                            ownerType, CompletionItemKind.Method, memberName, erasedParameterTypes),
                                    TypeMemberIndex.canonicalMemberKey(
                                            ownerType, CompletionItemKind.Method, memberName, erasedParameterTypes),
                                    null,
                                    false));
                }
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> resolveImplicitSlf4jLogger(String identifier) {
            if (!"log".equals(identifier)) {
                return Optional.empty();
            }
            var classPath = enclosingClassPath();
            if (classPath == null) {
                return Optional.empty();
            }
            var classTree = (ClassTree) classPath.getLeaf();
            if (!LombokAnnotations.hasLoggingOnlyLombokAnnotation(classTree.getModifiers())) {
                return Optional.empty();
            }
            var ownerType = qualifiedClassName(root, classPath);
            var loggerMember =
                    index.isWorkspaceOwnedType(ownerType, compiler)
                            ? index.workspace().member(ownerType, "log", false)
                            : index.member(ownerType, "log", false);
            if (loggerMember.isEmpty()) {
                loggerMember =
                        index.isWorkspaceOwnedType(ownerType, compiler)
                                ? index.workspace().member(ownerType, "log", true)
                                : index.member(ownerType, "log", true);
            }
            if (loggerMember.isPresent() && loggerMember.get().returnType != null) {
                return Optional.of(new TypeResolution(loggerMember.get().returnType, false, false));
            }
            var loggerType = "org.slf4j.Logger";
            if (!index.containsType(loggerType)) {
                return Optional.empty();
            }
            return Optional.of(new TypeResolution(loggerType, false, false));
        }

        private Optional<String> resolveNestedTypeInEnclosingScopes(String simpleName) {
            for (var classPath = enclosingClassPath(); classPath != null; classPath = parentClassPath(classPath.getParentPath())) {
                var owner = qualifiedClassName(root, classPath);
                var candidate = owner + "." + simpleName;
                if (index.workspace().containsType(candidate) || sourceClassInfo(candidate).isPresent()) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }

        private TreePath parentClassPath(TreePath from) {
            for (var cursor = from; cursor != null; cursor = cursor.getParentPath()) {
                if (cursor.getLeaf() instanceof ClassTree) {
                    return cursor;
                }
            }
            return null;
        }

        private Optional<TypeResolution> resolveVisibleVariable(String targetName, int depth) {
            final CandidateType[] best = new CandidateType[1];
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree classTree, Void p) {
                    if (!containsCursor(classTree)) {
                        return null;
                    }
                    for (var member : classTree.getMembers()) {
                        if (member instanceof VariableTree) {
                            scan(member, p);
                        } else if (containsCursor(member)) {
                            scan(member, p);
                        }
                    }
                    return null;
                }

                @Override
                public Void visitMethod(MethodTree methodTree, Void p) {
                    if (!containsCursor(methodTree)) {
                        return null;
                    }
                    for (var parameter : methodTree.getParameters()) {
                        consider(parameter, getCurrentPath(), depth + 1);
                    }
                    if (methodTree.getBody() != null) {
                        scan(methodTree.getBody(), p);
                    }
                    return null;
                }

                @Override
                public Void visitBlock(BlockTree blockTree, Void p) {
                    if (!containsCursor(blockTree)) {
                        return null;
                    }
                    for (var statement : blockTree.getStatements()) {
                        var start = startOf(statement);
                        if (start >= 0 && start >= cursor) {
                            break;
                        }
                        scan(statement, p);
                    }
                    return null;
                }

                @Override
                public Void visitVariable(VariableTree variableTree, Void p) {
                    consider(variableTree, getCurrentPath(), depth + 1);
                    return null;
                }

                private void consider(VariableTree variableTree, TreePath path, int nextDepth) {
                    if (!variableTree.getName().contentEquals(targetName)) {
                        return;
                    }
                    var start = startOf(variableTree);
                    if (start < 0 || start >= cursor) {
                        return;
                    }
                    var resolved = resolveVariableType(variableTree, path, nextDepth);
                    if (resolved.isEmpty()) {
                        return;
                    }
                    var candidate = new CandidateType(resolved.get(), depth(path), start);
                    if (best[0] == null
                            || candidate.depth > best[0].depth
                            || (candidate.depth == best[0].depth && candidate.start > best[0].start)) {
                        best[0] = candidate;
                    }
                }
            }.scan(root, null);

            if (best[0] == null) {
                return Optional.empty();
            }
            return Optional.of(best[0].resolution);
        }

        private Optional<TypeResolution> resolveVariableType(VariableTree variableTree, TreePath path, int depth) {
            if (variableTree.getType() != null && !"var".equals(variableTree.getType().toString())) {
                return resolveTypeTree(variableTree.getType(), root, false);
            }
            var enhancedFor = resolveEnhancedForVariableType(variableTree, path, depth + 1);
            if (enhancedFor.isPresent()) {
                return enhancedFor;
            }
            var lambda = resolveLambdaParameterType(variableTree, path, depth + 1);
            if (lambda.isPresent()) {
                return lambda;
            }
            if (variableTree.getInitializer() != null) {
                return resolveExpression(variableTree.getInitializer(), depth + 1);
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> resolveEnhancedForVariableType(
                VariableTree variableTree, TreePath path, int depth) {
            var parent = path == null ? null : path.getParentPath();
            if (parent == null || !(parent.getLeaf() instanceof EnhancedForLoopTree loop) || loop.getVariable() != variableTree) {
                return Optional.empty();
            }
            var iterableType = resolveExpression(loop.getExpression(), depth + 1);
            if (iterableType.isEmpty()) {
                return Optional.empty();
            }
            if (iterableType.get().arrayType) {
                return Optional.of(new TypeResolution(iterableType.get().qualifiedType, false, false));
            }
            if (iterableType.get().firstTypeArgument == null || iterableType.get().firstTypeArgument.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TypeResolution(iterableType.get().firstTypeArgument, false, false));
        }

        private Optional<TypeResolution> resolveLambdaParameterType(
                VariableTree variableTree, TreePath path, int depth) {
            var parent = path == null ? null : path.getParentPath();
            if (parent == null || !(parent.getLeaf() instanceof LambdaExpressionTree lambda)) {
                return Optional.empty();
            }
            if (lambda.getParameters().size() != 1 || lambda.getParameters().get(0) != variableTree) {
                return Optional.empty();
            }
            var invocationPath = parent.getParentPath();
            if (invocationPath == null || !(invocationPath.getLeaf() instanceof MethodInvocationTree invocation)) {
                return Optional.empty();
            }
            var argumentIndex = -1;
            for (int i = 0; i < invocation.getArguments().size(); i++) {
                if (invocation.getArguments().get(i) == lambda) {
                    argumentIndex = i;
                    break;
                }
            }
            if (argumentIndex < 0) {
                return Optional.empty();
            }
            var functionalType = resolveInvocationArgumentType(invocation, argumentIndex, depth + 1);
            if (functionalType.isEmpty()) {
                return Optional.empty();
            }
            return resolveSamParameterType(functionalType.get());
        }

        private Optional<TypeResolution> resolveTypeTree(Tree tree, CompilationUnitTree sourceRoot, boolean staticContext) {
            if (tree == null) {
                return Optional.empty();
            }
            if (tree instanceof AnnotatedTypeTree annotatedTypeTree) {
                return resolveTypeTree(annotatedTypeTree.getUnderlyingType(), sourceRoot, staticContext);
            }
            if (tree instanceof ParameterizedTypeTree parameterizedTypeTree) {
                var raw = resolveTypeTree(parameterizedTypeTree.getType(), sourceRoot, staticContext);
                if (raw.isEmpty()) {
                    return Optional.empty();
                }
                var firstTypeArgument = firstTypeArgument(parameterizedTypeTree, sourceRoot);
                return Optional.of(raw.get().withFirstTypeArgument(firstTypeArgument.orElse(null)));
            }
            if (tree instanceof ArrayTypeTree arrayTypeTree) {
                var component = resolveTypeTree(arrayTypeTree.getType(), sourceRoot, staticContext);
                return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType, staticContext, true));
            }
            var typeName = tree.toString();
            var resolved = typeLookup.resolveTypeName(typeName, sourceRoot);
            if (resolved.isEmpty()) {
                resolved = resolveTypeNameInSource(typeName, sourceRoot);
            }
            return resolved.map(type -> new TypeResolution(type, staticContext, false));
        }

        private Optional<String> firstTypeArgument(ParameterizedTypeTree parameterizedTypeTree, CompilationUnitTree sourceRoot) {
            if (parameterizedTypeTree.getTypeArguments().isEmpty()) {
                return Optional.empty();
            }
            var resolved = resolveTypeTree(parameterizedTypeTree.getTypeArguments().get(0), sourceRoot, false);
            return resolved.map(ParseTypeResolver::typeName);
        }

        private Optional<TypeResolution> resolveThisType() {
            if (thisType != null) {
                return Optional.of(thisType);
            }
            var classPath = enclosingClassPath();
            if (classPath == null) {
                return Optional.empty();
            }
            var qualified = qualifiedClassName(root, classPath);
            thisType = new TypeResolution(qualified, false, false);
            return Optional.of(thisType);
        }

        Optional<String> currentEnclosingTypeName() {
            return resolveThisType().map(type -> type.qualifiedType);
        }

        boolean isEnclosingInstanceType(String qualifiedType) {
            if (qualifiedType == null || qualifiedType.isBlank() || cursorPath == null) {
                return false;
            }
            var blockedByStaticContext = false;
            for (var path = cursorPath; path != null; path = path.getParentPath()) {
                var leaf = path.getLeaf();
                if (leaf instanceof MethodTree methodTree
                        && methodTree.getModifiers() != null
                        && methodTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                    blockedByStaticContext = true;
                    continue;
                }
                if (leaf instanceof BlockTree blockTree) {
                    var parent = path.getParentPath();
                    if (parent != null && parent.getLeaf() instanceof ClassTree && blockTree.isStatic()) {
                        blockedByStaticContext = true;
                    }
                    continue;
                }
                if (!(leaf instanceof ClassTree classTree)) {
                    continue;
                }
                var className = qualifiedClassName(root, path);
                if (qualifiedType.equals(className)) {
                    return !blockedByStaticContext;
                }
                if (classTree.getModifiers() != null && classTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                    blockedByStaticContext = true;
                }
            }
            return false;
        }

        private Optional<TypeResolution> resolveSuperType() {
            if (superType != null) {
                return Optional.of(superType);
            }
            var classPath = enclosingClassPath();
            if (classPath == null) {
                return Optional.empty();
            }
            var classTree = (ClassTree) classPath.getLeaf();
            if (classTree.getExtendsClause() == null) {
                superType = new TypeResolution("java.lang.Object", false, false);
                return Optional.of(superType);
            }
            var resolved = resolveTypeTree(classTree.getExtendsClause(), root, false);
            resolved.ifPresent(typeResolution -> superType = typeResolution);
            return resolved;
        }

        private Optional<TypeResolution> returnTypeOf(TypeMemberIndex.Member member) {
            var workspaceSource = resolveWorkspaceSourceMemberType(member);
            if (workspaceSource.isPresent()) {
                return workspaceSource;
            }
            if (member.returnType == null || member.returnType.isBlank()) {
                return Optional.empty();
            }
            var type = member.returnType;
            var isArray = type.endsWith("[]");
            if (isArray) {
                type = type.substring(0, type.length() - 2);
            }
            var resolved = typeLookup.resolveTypeName(type, root);
            if (resolved.isEmpty()) {
                resolved = resolveTypeNameInCurrentSource(type);
            }
            return resolved.map(next -> new TypeResolution(next, false, isArray, firstTypeArgumentFromTypeName(member.returnType).orElse(null)));
        }

        private Optional<TypeResolution> resolveWorkspaceSourceMemberType(TypeMemberIndex.Member member) {
            if (member == null || !index.isWorkspaceOwnedType(member.ownerType, compiler)) {
                return Optional.empty();
            }
            var info = sourceClassInfo(member.ownerType);
            if (info.isEmpty()) {
                return Optional.empty();
            }
            if (member.backingFieldName != null && !member.backingFieldName.isBlank()) {
                for (var candidate : info.get().classTree.getMembers()) {
                    if (candidate instanceof VariableTree field && field.getName().contentEquals(member.backingFieldName)) {
                        return resolveTypeTree(field.getType(), info.get().sourceRoot, false);
                    }
                }
            }
            for (var candidate : info.get().classTree.getMembers()) {
                if (member.kind == CompletionItemKind.Field && candidate instanceof VariableTree field && field.getName().contentEquals(member.name)) {
                    return resolveTypeTree(field.getType(), info.get().sourceRoot, false);
                }
                if (member.kind == CompletionItemKind.Method
                        && candidate instanceof MethodTree method
                        && method.getName().contentEquals(member.name)
                        && method.getReturnType() != null
                        && method.getParameters().size() == (member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length)) {
                    return resolveTypeTree(method.getReturnType(), info.get().sourceRoot, false);
                }
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> resolveInvocationArgumentType(
                MethodInvocationTree invocation, int argumentIndex, int depth) {
            var select = invocation.getMethodSelect();
            if (select instanceof IdentifierTree identifier) {
                var current = resolveThisType();
                if (current.isEmpty()) {
                    return Optional.empty();
                }
                return resolveMethodArgumentType(
                        current.get(), current.get().qualifiedType, identifier.getName().toString(), false, invocation, argumentIndex);
            }
            if (select instanceof MemberSelectTree memberSelectTree) {
                var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
                if (receiver.isEmpty()) {
                    return Optional.empty();
                }
                return resolveMethodArgumentType(
                        receiver.get(),
                        receiver.get().qualifiedType,
                        memberSelectTree.getIdentifier().toString(),
                        receiver.get().staticContext,
                        invocation,
                        argumentIndex);
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> resolveMethodArgumentType(
                TypeResolution receiverType,
                String ownerType,
                String methodName,
                boolean staticContext,
                MethodInvocationTree invocation,
                int argumentIndex) {
            var source = resolveSourceMethodArgumentType(ownerType, methodName, staticContext, invocation, argumentIndex);
            if (source.isPresent()) {
                return source;
            }
            if (index.isWorkspaceOwnedType(ownerType, compiler)) {
                return Optional.empty();
            }
            return resolveExternalMethodArgumentType(receiverType, ownerType, methodName, staticContext, invocation, argumentIndex);
        }

        private Optional<TypeResolution> resolveSourceMethodArgumentType(
                String ownerType,
                String methodName,
                boolean staticContext,
                MethodInvocationTree invocation,
                int argumentIndex) {
            var info = sourceClassInfo(ownerType);
            if (info.isEmpty()) {
                return Optional.empty();
            }
            var matches = new ArrayList<MethodTree>();
            for (var member : info.get().classTree.getMembers()) {
                if (!(member instanceof MethodTree method)) {
                    continue;
                }
                if (!method.getName().contentEquals(methodName)) {
                    continue;
                }
                var isStatic =
                        method.getModifiers() != null
                                && method.getModifiers().getFlags().contains(Modifier.STATIC);
                if (isStatic != staticContext || method.getParameters().size() != invocation.getArguments().size()) {
                    continue;
                }
                matches.add(method);
            }
            if (matches.size() != 1) {
                return Optional.empty();
            }
            return resolveTypeTree(matches.getFirst().getParameters().get(argumentIndex).getType(), info.get().sourceRoot, false);
        }

        private Optional<TypeResolution> resolveExternalMethodArgumentType(
                TypeResolution receiverType,
                String ownerType,
                String methodName,
                boolean staticContext,
                MethodInvocationTree invocation,
                int argumentIndex) {
            var method = resolveReflectiveMethod(ownerType, methodName, staticContext, invocation.getArguments().size());
            if (method.isEmpty()) {
                return Optional.empty();
            }
            var bindings = bindingsForMethodOwner(receiverType, method.get().getDeclaringClass());
            var parameterTypes = method.get().getGenericParameterTypes();
            if (argumentIndex < 0 || argumentIndex >= parameterTypes.length) {
                return Optional.empty();
            }
            return resolveReflectiveType(parameterTypes[argumentIndex], bindings);
        }

        private Optional<TypeResolution> resolveSamParameterType(TypeResolution functionalType) {
            if (functionalType == null || functionalType.qualifiedType == null || functionalType.qualifiedType.isBlank()) {
                return Optional.empty();
            }
            if (index.isWorkspaceOwnedType(functionalType.qualifiedType, compiler)) {
                return resolveSourceSamParameterType(functionalType);
            }
            var rawClass = loadExternalClass(functionalType.qualifiedType);
            if (rawClass.isEmpty()) {
                return Optional.empty();
            }
            var sam = singleAbstractMethod(rawClass.get());
            if (sam.isEmpty() || sam.get().getParameterCount() != 1) {
                return Optional.empty();
            }
            var bindings = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
            var vars = rawClass.get().getTypeParameters();
            if (vars.length > 0 && functionalType.firstTypeArgument != null && !functionalType.firstTypeArgument.isBlank()) {
                bindings.put(vars[0], simpleResolution(functionalType.firstTypeArgument));
            }
            return resolveReflectiveType(sam.get().getGenericParameterTypes()[0], bindings);
        }

        private Optional<TypeResolution> resolveSourceSamParameterType(TypeResolution functionalType) {
            var info = sourceClassInfo(functionalType.qualifiedType);
            if (info.isEmpty()) {
                return Optional.empty();
            }
            MethodTree sam = null;
            for (var member : info.get().classTree.getMembers()) {
                if (!(member instanceof MethodTree method)) {
                    continue;
                }
                var modifiers = method.getModifiers() == null ? Set.<Modifier>of() : method.getModifiers().getFlags();
                if (modifiers.contains(Modifier.DEFAULT) || modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE)) {
                    continue;
                }
                if (method.getBody() != null) {
                    continue;
                }
                if (sam != null) {
                    return Optional.empty();
                }
                sam = method;
            }
            if (sam == null || sam.getParameters().size() != 1) {
                return Optional.empty();
            }
            return resolveTypeTree(sam.getParameters().get(0).getType(), info.get().sourceRoot, false);
        }

        private Optional<TypeResolution> resolveExternalMethodReturnType(TypeResolution receiverType, TypeMemberIndex.Member member) {
            if (receiverType == null
                    || member == null
                    || member.ownerType == null
                    || member.name == null
                    || index.isWorkspaceOwnedType(member.ownerType, compiler)) {
                return Optional.empty();
            }
            var method =
                    resolveReflectiveMethod(
                            member.ownerType,
                            member.name,
                            member.isStatic,
                            member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length);
            if (method.isEmpty()) {
                return Optional.empty();
            }
            var bindings = bindingsForMethodOwner(receiverType, method.get().getDeclaringClass());
            return resolveReflectiveType(method.get().getGenericReturnType(), bindings);
        }

        private Optional<Method> resolveReflectiveMethod(
                String ownerType, String methodName, boolean staticContext, int parameterCount) {
            var rawClass = loadExternalClass(ownerType);
            if (rawClass.isEmpty()) {
                return Optional.empty();
            }
            Method match = null;
            for (var method : rawClass.get().getMethods()) {
                if (!method.getName().equals(methodName)
                        || method.getParameterCount() != parameterCount
                        || staticContext != java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (match != null && !sameReflectiveSignature(match, method)) {
                    return Optional.empty();
                }
                match = method;
            }
            return Optional.ofNullable(match);
        }

        private boolean sameReflectiveSignature(Method left, Method right) {
            return left.getDeclaringClass().equals(right.getDeclaringClass())
                    && Objects.deepEquals(left.getParameterTypes(), right.getParameterTypes());
        }

        private Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindingsForMethodOwner(
                TypeResolution receiverType, Class<?> declaringClass) {
            if (receiverType == null || receiverType.qualifiedType == null || receiverType.qualifiedType.isBlank()) {
                return Map.of();
            }
            var receiverClass = loadExternalClass(receiverType.qualifiedType);
            if (receiverClass.isEmpty()) {
                return Map.of();
            }
            var initial = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
            var receiverVars = receiverClass.get().getTypeParameters();
            if (receiverVars.length > 0 && receiverType.firstTypeArgument != null && !receiverType.firstTypeArgument.isBlank()) {
                initial.put(receiverVars[0], simpleResolution(receiverType.firstTypeArgument));
            }
            return resolveBindingsForClass(receiverClass.get(), initial, declaringClass).orElse(Map.of());
        }

        private Optional<Map<java.lang.reflect.TypeVariable<?>, TypeResolution>> resolveBindingsForClass(
                Class<?> current,
                Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindings,
                Class<?> target) {
            if (current.equals(target)) {
                return Optional.of(bindings);
            }
            var candidates = new ArrayList<Type>();
            Collections.addAll(candidates, current.getGenericInterfaces());
            if (current.getGenericSuperclass() != null) {
                candidates.add(current.getGenericSuperclass());
            }
            for (var candidate : candidates) {
                var raw = rawClass(candidate);
                if (raw.isEmpty()) {
                    continue;
                }
                var nextBindings = bindingsForSuperType(candidate, bindings);
                if (raw.get().equals(target)) {
                    return Optional.of(nextBindings);
                }
                var nested = resolveBindingsForClass(raw.get(), nextBindings, target);
                if (nested.isPresent()) {
                    return nested;
                }
            }
            return Optional.empty();
        }

        private Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindingsForSuperType(
                Type superType, Map<java.lang.reflect.TypeVariable<?>, TypeResolution> currentBindings) {
            if (!(superType instanceof ParameterizedType parameterized) || !(parameterized.getRawType() instanceof Class<?> rawClass)) {
                return Map.of();
            }
            var next = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
            var vars = rawClass.getTypeParameters();
            var args = parameterized.getActualTypeArguments();
            for (int i = 0; i < vars.length && i < args.length; i++) {
                var resolved = resolveReflectiveType(args[i], currentBindings);
                if (resolved.isPresent()) {
                    next.put(vars[i], resolved.get());
                }
            }
            return next;
        }

        private Optional<TypeResolution> resolveReflectiveType(
                Type type, Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindings) {
            if (type instanceof Class<?> rawClass) {
                if (rawClass.isArray()) {
                    return Optional.of(new TypeResolution(rawClass.getComponentType().getName(), false, true));
                }
                return Optional.of(new TypeResolution(rawClass.getName(), false, false));
            }
            if (type instanceof ParameterizedType parameterized) {
                var raw = resolveReflectiveType(parameterized.getRawType(), bindings);
                if (raw.isEmpty()) {
                    return Optional.empty();
                }
                var firstArg =
                        parameterized.getActualTypeArguments().length == 0
                                ? Optional.<TypeResolution>empty()
                                : resolveReflectiveType(parameterized.getActualTypeArguments()[0], bindings);
                return Optional.of(raw.get().withFirstTypeArgument(firstArg.map(ParseTypeResolver::typeName).orElse(null)));
            }
            if (type instanceof java.lang.reflect.TypeVariable<?> typeVariable) {
                var bound = bindings.get(typeVariable);
                if (bound != null) {
                    return Optional.of(bound);
                }
                var bounds = typeVariable.getBounds();
                return bounds.length == 0 ? Optional.empty() : resolveReflectiveType(bounds[0], bindings);
            }
            if (type instanceof java.lang.reflect.WildcardType wildcard) {
                if (wildcard.getLowerBounds().length > 0) {
                    return resolveReflectiveType(wildcard.getLowerBounds()[0], bindings);
                }
                for (var upper : wildcard.getUpperBounds()) {
                    var resolved = resolveReflectiveType(upper, bindings);
                    if (resolved.isPresent() && !"java.lang.Object".equals(resolved.get().qualifiedType)) {
                        return resolved;
                    }
                }
                return Optional.empty();
            }
            if (type instanceof GenericArrayType arrayType) {
                var component = resolveReflectiveType(arrayType.getGenericComponentType(), bindings);
                return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType, false, true));
            }
            return Optional.empty();
        }

        private Optional<Class<?>> rawClass(Type type) {
            if (type instanceof Class<?> rawClass) {
                return Optional.of(rawClass);
            }
            if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawClass) {
                return Optional.of(rawClass);
            }
            return Optional.empty();
        }

        private Optional<Class<?>> loadExternalClass(String qualifiedType) {
            if (qualifiedType == null || qualifiedType.isBlank()) {
                return Optional.empty();
            }
            var loader = externalClassLoader();
            if (loader == null) {
                return Optional.empty();
            }
            var binaryName = qualifiedType;
            while (true) {
                try {
                    return Optional.of(Class.forName(binaryName, false, loader));
                } catch (ClassNotFoundException ignored) {
                    var split = binaryName.lastIndexOf('.');
                    if (split < 0) {
                        return Optional.empty();
                    }
                    binaryName = binaryName.substring(0, split) + "$" + binaryName.substring(split + 1);
                }
            }
        }

        private ClassLoader externalClassLoader() {
            if (externalClassLoader != null) {
                return externalClassLoader;
            }
            var urls =
                    compiler.classPathRoots().stream()
                            .map(Path::toUri)
                            .map(
                                    uri -> {
                                        try {
                                            return uri.toURL();
                                        } catch (java.io.IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    })
                            .toArray(URL[]::new);
            externalClassLoader = new URLClassLoader(urls, CompletionProvider.class.getClassLoader());
            return externalClassLoader;
        }

        private Optional<Method> singleAbstractMethod(Class<?> rawClass) {
            Method found = null;
            for (var method : rawClass.getMethods()) {
                if (!java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                        || method.getDeclaringClass().equals(Object.class)) {
                    continue;
                }
                if (found != null && !sameReflectiveSignature(found, method)) {
                    return Optional.empty();
                }
                found = method;
            }
            return Optional.ofNullable(found);
        }

        private Optional<String> firstTypeArgumentFromTypeName(String typeName) {
            if (typeName == null || typeName.isBlank()) {
                return Optional.empty();
            }
            var start = typeName.indexOf('<');
            var end = typeName.lastIndexOf('>');
            if (start < 0 || end <= start) {
                return Optional.empty();
            }
            var first = typeName.substring(start + 1, end).trim();
            var comma = first.indexOf(',');
            if (comma >= 0) {
                first = first.substring(0, comma).trim();
            }
            while (first.startsWith("? extends ")) {
                first = first.substring("? extends ".length()).trim();
            }
            while (first.startsWith("? super ")) {
                first = first.substring("? super ".length()).trim();
            }
            if (first.isBlank() || "?".equals(first)) {
                return Optional.empty();
            }
            var resolved = typeLookup.resolveTypeName(first, root);
            if (resolved.isEmpty()) {
                resolved = resolveTypeNameInCurrentSource(first);
            }
            return resolved;
        }

        private Optional<String> resolveTypeNameInCurrentSource(String typeName) {
            return resolveTypeNameInSource(typeName, root);
        }

        private Optional<String> resolveTypeNameInSource(String typeName, CompilationUnitTree sourceRoot) {
            if (typeName == null || typeName.isBlank()) {
                return Optional.empty();
            }
            var raw = typeName.replace('$', '.');
            if (raw.contains(".") && declaredClassPathInRoot(sourceRoot, raw) != null) {
                return Optional.of(raw);
            }
            final String[] match = new String[1];
            final boolean[] ambiguous = {false};
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree classTree, Void unused) {
                    var qualified = qualifiedClassName(sourceRoot, getCurrentPath());
                    var simple = classTree.getSimpleName().toString();
                    if (!simple.equals(raw)) {
                        return super.visitClass(classTree, unused);
                    }
                    if (match[0] == null) {
                        match[0] = qualified;
                    } else if (!match[0].equals(qualified)) {
                        ambiguous[0] = true;
                    }
                    return super.visitClass(classTree, unused);
                }
            }.scan(sourceRoot, null);
            if (ambiguous[0]) {
                return Optional.empty();
            }
            return Optional.ofNullable(match[0]);
        }

        private Optional<SourceClassInfo> sourceClassInfo(String qualifiedType) {
            if (qualifiedType == null || qualifiedType.isBlank()) {
                return Optional.empty();
            }
            var current = declaredClassPathInRoot(root, qualifiedType);
            if (current != null) {
                return Optional.of(new SourceClassInfo(root, current));
            }
            var declaration = compiler.findTypeDeclaration(qualifiedType);
            if (declaration == null || declaration.equals(CompilerProvider.NOT_FOUND)) {
                return Optional.empty();
            }
            var parsed = compiler.parse(declaration);
            var classPath = declaredClassPathInRoot(parsed.root(), qualifiedType);
            return classPath == null ? Optional.empty() : Optional.of(new SourceClassInfo(parsed.root(), classPath));
        }

        private TreePath declaredClassPathInRoot(CompilationUnitTree sourceRoot, String qualifiedType) {
            if (qualifiedType == null || qualifiedType.isBlank()) {
                return null;
            }
            final TreePath[] match = new TreePath[1];
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree classTree, Void unused) {
                    if (qualifiedType.equals(qualifiedClassName(sourceRoot, getCurrentPath()))) {
                        match[0] = getCurrentPath();
                        return null;
                    }
                    return super.visitClass(classTree, unused);
                }
            }.scan(sourceRoot, null);
            return match[0];
        }

        private Optional<TypeResolution> literalType(LiteralTree literal) {
            var value = literal.getValue();
            if (value == null) {
                return Optional.empty();
            }
            if (value instanceof String) {
                return Optional.of(new TypeResolution("java.lang.String", false, false));
            }
            if (value instanceof Integer) {
                return Optional.of(new TypeResolution("java.lang.Integer", false, false));
            }
            if (value instanceof Long) {
                return Optional.of(new TypeResolution("java.lang.Long", false, false));
            }
            if (value instanceof Float) {
                return Optional.of(new TypeResolution("java.lang.Float", false, false));
            }
            if (value instanceof Double) {
                return Optional.of(new TypeResolution("java.lang.Double", false, false));
            }
            if (value instanceof Boolean) {
                return Optional.of(new TypeResolution("java.lang.Boolean", false, false));
            }
            if (value instanceof Character) {
                return Optional.of(new TypeResolution("java.lang.Character", false, false));
            }
            return Optional.empty();
        }

        private TreePath enclosingClassPath() {
            final TreePath[] best = new TreePath[1];
            final int[] bestDepth = {-1};
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree classTree, Void p) {
                    if (!containsCursor(classTree)) {
                        return null;
                    }
                    var depth = depth(getCurrentPath());
                    if (depth > bestDepth[0]) {
                        bestDepth[0] = depth;
                        best[0] = getCurrentPath();
                    }
                    return super.visitClass(classTree, p);
                }
            }.scan(root, null);
            return best[0];
        }

        private String qualifiedClassName(CompilationUnitTree sourceRoot, TreePath classPath) {
            var classes = new ArrayList<String>();
            for (var cursorPath = classPath; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
                if (cursorPath.getLeaf() instanceof ClassTree classTree) {
                    classes.add(classTree.getSimpleName().toString());
                }
            }
            Collections.reverse(classes);
            var packageName = sourceRoot.getPackageName() == null ? "" : sourceRoot.getPackageName().toString();
            return packageName.isEmpty() ? String.join(".", classes) : packageName + "." + String.join(".", classes);
        }

        private boolean containsCursor(Tree tree) {
            var start = startOf(tree);
            var end = endOf(tree);
            if (start < 0 || end < 0) {
                return false;
            }
            return start <= cursor && cursor <= end;
        }

        private long startOf(Tree tree) {
            return positions.getStartPosition(root, tree);
        }

        private long endOf(Tree tree) {
            return positions.getEndPosition(root, tree);
        }

        private int depth(TreePath path) {
            var depth = 0;
            for (var cursorPath = path; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
                depth++;
            }
            return depth;
        }

        private static TypeResolution simpleResolution(String qualifiedType) {
            if (qualifiedType == null || qualifiedType.isBlank()) {
                return new TypeResolution("", false, false);
            }
            var array = qualifiedType.endsWith("[]");
            return new TypeResolution(array ? qualifiedType.substring(0, qualifiedType.length() - 2) : qualifiedType, false, array);
        }

        private static String typeName(TypeResolution resolution) {
            if (resolution == null || resolution.qualifiedType == null) {
                return null;
            }
            return resolution.arrayType ? resolution.qualifiedType + "[]" : resolution.qualifiedType;
        }
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
                var list = new CompletionList();
                addKeywords(path, partial, list);
                addSyntacticLocalVariables(parseTask, cursor, partial, list);
                addSyntacticEnclosingTypeMembers(parseTask, path, cursor, partial, list);
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
            var simple = simpleName(qualified).toString();
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
            if (simpleName(imported).toString().equals(item.label)) {
                return true;
            }
        }
        return false;
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

    private void addSyntacticLocalVariables(ParseTask task, long cursor, String partial, CompletionList list) {
        var trees = Trees.instance(task.task());
        var positions = trees.getSourcePositions();
        var seen = new HashSet<String>();
        for (var item : list.items) {
            seen.add(item.label);
        }
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree t, Void __) {
                var start = positions.getStartPosition(task.root(), t);
                if (start < 0 || start >= cursor) return super.visitVariable(t, null);
                var name = t.getName().toString();
                if (name.isEmpty()) return super.visitVariable(t, null);
                if (!StringSearch.matchesPartialName(name, partial)) return super.visitVariable(t, null);
                if (isTypeLikeIdentifier(name)) {
                    return super.visitVariable(t, null);
                }
                if (seen.add(name)) {
                    list.items.add(variable(name));
                }
                return super.visitVariable(t, null);
            }
        }.scan(task.root(), null);
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
        var staticContext = isSyntacticStaticContext(path);
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
                    addSyntacticNestedType(member, partial, list);
                    break;
                default:
                    break;
            }
        }
    }

    private boolean isSyntacticStaticContext(TreePath path) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof MethodTree method) {
                return method.getModifiers().getFlags().contains(Modifier.STATIC);
            }
        }
        return false;
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
        list.items.add(field(name));
    }

    private void addSyntacticMethod(MethodTree method, String partial, CompletionList list) {
        var name = method.getName().toString();
        if (!matchesCompletionPrefix(name, partial)) {
            return;
        }
        if (containsCompletionLabel(list, name)) {
            return;
        }
        list.items.add(syntacticMethod(name));
    }

    private void addSyntacticNestedType(Tree member, String partial, CompletionList list) {
        var nested = (ClassTree) member;
        var name = nested.getSimpleName().toString();
        if (name.isEmpty() || !matchesCompletionPrefix(name, partial)) {
            return;
        }
        if (containsCompletionLabel(list, name)) {
            return;
        }
        list.items.add(syntacticType(name, nested.getKind()));
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
        if (completionIndex == null || root == null) {
            return;
        }
        var methods = new TreeMap<String, List<TypeMemberIndex.Member>>();
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
            for (var member : completionIndex.members(ownerType, true)) {
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
            if (!StringSearch.matchesPartialName(simpleName(className), partial)) continue;
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
        var targetSimple = simpleName(className).toString();
        for (var i : root.getImports()) {
            if (i.isStatic()) continue;
            var imported = i.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) continue;
            if (imported.equals(className)) continue;
            var importedSimple = simpleName(imported).toString();
            if (importedSimple.equals(targetSimple)) {
                return true;
            }
        }
        return false;
    }

    private CompletionList completeMembersForExpression(
            CompileTask task,
            TreePath expressionPath,
            Tree expression,
            String partial,
            boolean endsWithParen) {
        var trees = Trees.instance(task.task);
        var includePrivate = expression instanceof ExpressionTree && isThisOrSuper((ExpressionTree) expression);
        var isStatic = trees.getElement(expressionPath) instanceof TypeElement;
        var scope = trees.getScope(expressionPath);
        var type = trees.getTypeMirror(expressionPath);
        if (expression instanceof MethodInvocationTree) {
            var resolved = resolveInvocationReturnType(task, expressionPath, (MethodInvocationTree) expression);
            if (resolved != null) {
                type = resolved;
            }
            isStatic = false;
        }
        return switch (type) {
            case ArrayType _ -> completeArrayMemberSelect(isStatic);
            case TypeVariable typeVariable -> completeTypeVariableMemberSelect(
                    task, scope, typeVariable, isStatic, partial, endsWithParen, includePrivate);
            case DeclaredType declaredType -> completeDeclaredTypeMemberSelect(
                    task, scope, declaredType, isStatic, partial, endsWithParen, includePrivate);
            case null, default -> NOT_SUPPORTED;
        };
    }

    private boolean isThisOrSuper(ExpressionTree expression) {
        if (!(expression instanceof IdentifierTree identifier)) {
            return false;
        }
        var name = identifier.getName().toString();
        return "this".equals(name) || "super".equals(name);
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
            CompileTask task,
            Scope scope,
            TypeVariable type,
            boolean isStatic,
            String partial,
            boolean endsWithParen,
            boolean includePrivate) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(
                    task,
                    scope,
                    (DeclaredType) type.getUpperBound(),
                    isStatic,
                    partial,
                    endsWithParen,
                    includePrivate);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(
                    task,
                    scope,
                    (TypeVariable) type.getUpperBound(),
                    isStatic,
                    partial,
                    endsWithParen,
                    includePrivate);
        } else {
            return NOT_SUPPORTED;
        }
    }

    private CompletionList completeDeclaredTypeMemberSelect(
            CompileTask task,
            Scope scope,
            DeclaredType type,
            boolean isStatic,
            String partial,
            boolean endsWithParen,
            boolean includePrivate) {
        var trees = Trees.instance(task.task);
        var typeElement = (TypeElement) type.asElement();
        var list = new ArrayList<CompletionItem>();
        var methods = new TreeMap<String, List<ExecutableElement>>();
        var methodPriority = new HashMap<String, Integer>();
        for (var member : task.task.getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            if (!includePrivate && !trees.isAccessible(scope, member, type)) continue;
            if (isStatic != member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
                var name = member.getSimpleName().toString();
                var priority = memberPriority(typeElement, member);
                methodPriority.merge(name, priority, Math::min);
            } else {
                var next = item(task, member);
                next.sortText = sortKey(memberPriority(typeElement, member), next.label);
                list.add(next);
            }
        }
        for (var entry : methods.entrySet()) {
            var method = method(task, entry.getValue(), !endsWithParen);
            method.sortText = sortKey(methodPriority.getOrDefault(entry.getKey(), Priority.INHERITED_METHOD), method.label);
            list.add(method);
        }

        if (isStatic) {
            list.add(keyword("class"));
        }
        if (isStatic && isEnclosingClass(type, scope)) {
            list.add(keyword("this"));
            list.add(keyword("super"));
        }
        sortCompletionItems(list);
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

    private static final CompletionList EMPTY = new CompletionList(false, List.of());

    private void putMethod(ExecutableElement method, Map<String, List<ExecutableElement>> methods) {
        var name = method.getSimpleName().toString();
        if (!methods.containsKey(name)) {
            methods.put(name, new ArrayList<>());
        }
        methods.get(name).add(method);
    }

    private TypeMirror resolveInvocationReturnType(
            CompileTask task, TreePath invocationPath, MethodInvocationTree invocation) {
        var trees = Trees.instance(task.task);
        var selectPath = new TreePath(invocationPath, invocation.getMethodSelect());
        if (invocation.getMethodSelect() instanceof IdentifierTree) {
            var id = (IdentifierTree) invocation.getMethodSelect();
            var scope = trees.getScope(selectPath);
            Predicate<CharSequence> filter = name -> id.getName().contentEquals(name);
            for (var member : ScopeHelper.scopeMembers(task, scope, filter)) {
                if (member.getKind() != ElementKind.METHOD) continue;
                var method = (ExecutableElement) member;
                if (!matchesArity(method, invocation.getArguments().size())) continue;
                return method.getReturnType();
            }
            return null;
        }
        if (invocation.getMethodSelect() instanceof MemberSelectTree select) {
            var qualifierPath = new TreePath(selectPath, select.getExpression());
            var qualifierType = trees.getTypeMirror(qualifierPath);
            if (!(qualifierType instanceof DeclaredType qualifierDeclared)) return qualifierType;
            var qualifierElement = (TypeElement) qualifierDeclared.asElement();
            var scope = trees.getScope(qualifierPath);
            var staticCall = trees.getElement(qualifierPath) instanceof TypeElement;
            for (var member : task.task.getElements().getAllMembers(qualifierElement)) {
                if (member.getKind() != ElementKind.METHOD) continue;
                if (!member.getSimpleName().contentEquals(select.getIdentifier())) continue;
                if (staticCall != member.getModifiers().contains(Modifier.STATIC)) continue;
                if (!trees.isAccessible(scope, member, qualifierDeclared)) continue;
                var method = (ExecutableElement) member;
                if (!matchesArity(method, invocation.getArguments().size())) continue;
                return method.getReturnType();
            }
        }
        return null;
    }

    private boolean matchesArity(ExecutableElement method, int args) {
        if (method.isVarArgs()) {
            return args >= method.getParameters().size() - 1;
        }
        return method.getParameters().size() == args;
    }

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
        i.label = simpleName(className).toString();
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

    private CompletionItem item(CompileTask task, Element element) {
        if (element.getKind() == ElementKind.METHOD) throw new RuntimeException("method");
        var i = new CompletionItem();
        i.label = element.getSimpleName().toString();
        i.kind = kind(element);
        i.detail = element.toString();
        i.data = JsonHelper.GSON.toJsonTree(data(task, element, 1));
        i.sortText = sortKey(priorityForItemKind(i.kind), i.label);
        return i;
    }

    private CompletionItem method(CompileTask task, List<ExecutableElement> overloads, boolean addParens) {
        var first = overloads.getFirst();
        var i = new CompletionItem();
        i.label = first.getSimpleName().toString();
        i.kind = CompletionItemKind.Method;
        i.detail = first.getReturnType() + " " + first;
        var data = data(task, first, overloads.size());
        i.data = JsonHelper.GSON.toJsonTree(data);
        if (addParens) {
            var noArgs = overloads.size() == 1 && first.getParameters().isEmpty();
            if (noArgs) {
                i.insertText = first.getSimpleName() + "()";
                i.insertTextFormat = InsertTextFormat.PlainText;
            } else {
                i.insertText = first.getSimpleName() + "($0)";
                i.insertTextFormat = InsertTextFormat.Snippet;
                i.command = new Command();
                i.command.command = "editor.action.triggerParameterHints";
                i.command.title = "Trigger Parameter Hints";
            }
        }
        i.sortText = sortKey(Priority.METHOD, i.label);
        return i;
    }

    private CompletionData data(CompileTask task, Element element, int overloads) {
        var data = new CompletionData();
        if (element instanceof TypeElement type) {
            data.className = type.getQualifiedName().toString();
        } else if (element.getKind() == ElementKind.FIELD) {
            var field = (VariableElement) element;
            var type = (TypeElement) field.getEnclosingElement();
            data.className = type.getQualifiedName().toString();
            data.memberName = field.getSimpleName().toString();
        } else if (element instanceof ExecutableElement method) {
            var types = task.task.getTypes();
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
        return switch (e.getKind()) {
            case ANNOTATION_TYPE, INTERFACE -> CompletionItemKind.Interface;
            case CLASS -> CompletionItemKind.Class;
            case CONSTRUCTOR -> CompletionItemKind.Constructor;
            case ENUM -> CompletionItemKind.Enum;
            case ENUM_CONSTANT -> CompletionItemKind.EnumMember;
            case EXCEPTION_PARAMETER, PARAMETER -> CompletionItemKind.Property;
            case FIELD -> CompletionItemKind.Field;
            case STATIC_INIT, INSTANCE_INIT -> CompletionItemKind.Function;
            case LOCAL_VARIABLE, RESOURCE_VARIABLE -> CompletionItemKind.Variable;
            case METHOD -> CompletionItemKind.Method;
            case PACKAGE -> CompletionItemKind.Module;
            case TYPE_PARAMETER -> CompletionItemKind.TypeParameter;
            default -> null;
        };
    }

    private CompletionItem keyword(String keyword) {
        var i = new CompletionItem();
        i.label = keyword;
        i.kind = CompletionItemKind.Keyword;
        i.detail = "keyword";
        i.sortText = sortKey(Priority.KEYWORD, i.label);
        return i;
    }

    private CompletionItem variable(String name) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Variable;
        i.detail = "local";
        i.sortText = sortKey(Priority.LOCAL, i.label);
        return i;
    }

    private CompletionItem field(String name) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Field;
        i.detail = "field";
        i.sortText = sortKey(Priority.FIELD, i.label);
        return i;
    }

    private CompletionItem syntacticMethod(String name) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Method;
        i.detail = "method";
        i.sortText = sortKey(Priority.METHOD, i.label);
        return i;
    }

    private CompletionItem syntacticType(String name, Tree.Kind kind) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = kind == Tree.Kind.INTERFACE || kind == Tree.Kind.ANNOTATION_TYPE
                ? CompletionItemKind.Interface
                : kind == Tree.Kind.ENUM ? CompletionItemKind.Enum : CompletionItemKind.Class;
        i.detail = "member type";
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

    private int memberPriority(TypeElement ownerType, Element member) {
        var enclosing = member.getEnclosingElement();
        var declaredInOwner = enclosing != null && enclosing.equals(ownerType);
        var declaredInObject =
                enclosing instanceof TypeElement
                        && ((TypeElement) enclosing).getQualifiedName().contentEquals("java.lang.Object");
        if (member.getKind() == ElementKind.METHOD) {
            if (isUtilityMethodName(member.getSimpleName().toString())) {
                return 90; // always sink utility methods
            }
            if (declaredInObject) {
                return 80;
            }
            if (member.getModifiers().contains(Modifier.STATIC)) {
                return 30;
            }
            if (declaredInOwner && isAccessorMethodName(member.getSimpleName().toString())) {
                return 10;
            }
            if (declaredInOwner) {
                return 20;
            }
            return 60;
        }
        if (member.getKind() == ElementKind.FIELD || member.getKind() == ElementKind.ENUM_CONSTANT) {
            if (member.getModifiers().contains(Modifier.STATIC)) {
                return 40;
            }
            if (declaredInOwner) {
                return 35;
            }
            return 50;
        }
        return priorityForItemKind(kind(member));
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

    private int priorityForItemKind(Integer kind) {
        if (kind == null) {
            return Priority.PACKAGE_MEMBER;
        }
        if (Objects.equals(kind, CompletionItemKind.Variable)) {
            return Priority.LOCAL;
        }
        if (Objects.equals(kind, CompletionItemKind.Field)) {
            return Priority.FIELD;
        }
        if (Objects.equals(kind, CompletionItemKind.Method)) {
            return Priority.METHOD;
        }
        if (Objects.equals(kind, CompletionItemKind.Keyword)) {
            return Priority.KEYWORD;
        }
        if (Objects.equals(kind, CompletionItemKind.Class) || Objects.equals(kind, CompletionItemKind.Interface)) {
            return Priority.IMPORTED_CLASS;
        }
        if (Objects.equals(kind, CompletionItemKind.Module)) {
            return Priority.PACKAGE_MEMBER;
        }
        return Priority.PACKAGE_MEMBER;
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
        var dot = partialStart - 1;
        if (dot < 0 || contents.charAt(dot) != '.') {
            return null;
        }
        var receiverEnd = dot;
        var receiverStart = receiverEnd;
        while (receiverStart > 0 && Character.isJavaIdentifierPart(contents.charAt(receiverStart - 1))) {
            receiverStart--;
        }
        var receiver = receiverStart < receiverEnd ? contents.substring(receiverStart, receiverEnd) : "";
        var partial = contents.substring(partialStart, effectiveCursor);
        return new MemberAccessContext(receiver, partial, partial.isEmpty());
    }

    private record MemberAccessContext(String receiver, String partial, boolean dotTrigger) {
    }

    private CharSequence simpleName(String className) {
        var dot = className.lastIndexOf('.');
        if (dot == -1) return className;
        return className.subSequence(dot + 1, className.length());
    }

}
