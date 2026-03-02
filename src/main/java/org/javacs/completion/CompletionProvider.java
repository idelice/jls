package org.javacs.completion;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
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
import org.javacs.CompileBatch;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.CompletionData;
import org.javacs.FileStore;
import org.javacs.JsonHelper;
import org.javacs.ParseTask;
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
    private final TypeMemberIndex completionIndex;
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

    public CompletionProvider(CompilerProvider compiler, TypeMemberIndex completionIndex, long completionIndexVersion) {
        this.compiler = compiler;
        this.completionIndex = completionIndex == null ? TypeMemberIndex.EMPTY : completionIndex;
        this.completionIndexVersion = Math.max(0L, completionIndexVersion);
    }

    public CompletionList complete(Path file, int line, int column) {
        return complete(file, line, column, -1);
    }

    public CompletionList complete(Path file, int line, int column, int fileVersionSeen) {
        LOG.info("Complete at " + file.getFileName() + "(" + line + "," + column + ")...");
        var started = Instant.now();
        var countersBefore = CompileBatch.perfCounters();
        var parseStarted = Instant.now();
        var task = compiler.parse(file);
        LOG.info("[perf] completion_parse file=" + file.getFileName() + " took=" + Duration.between(parseStarted, Instant.now()).toMillis() + "ms");
        var cursor = task.root.getLineMap().getPosition(line, column);
        var contents = new PruneMethodBodies(task.task).scan(task.root, cursor);
        var endOfLine = endOfLine(contents, (int) cursor);
        contents.insert(endOfLine, ';');
        var pruned = contents.toString();
        var memberAccess = memberAccessContext(pruned, (int) cursor);
        var parsePath = new FindCompletionsAt(task.task).scan(task.root, cursor);
        CompletionList list;
        String mode;
        if (memberAccess != null && isImportOrPackageContext(parsePath)) {
            // `import foo.bar.` should stay on the import completion path.
            list = completeParseOnly(task, pruned, cursor, memberAccess.partial);
            mode = "import_parse";
        } else if (memberAccess != null) {
            list =
                    completeParseAndIndex(
                            task,
                            parsePath,
                            cursor,
                            memberAccess,
                            endsWithParen(pruned, (int) cursor));
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
        logCompletionTiming(started, list.items, list.isIncomplete);
        var countersAfter = CompileBatch.perfCounters();
        var enterDelta = countersAfter.enterOnlyBatches - countersBefore.enterOnlyBatches;
        var analyzeDelta = countersAfter.analyzeInvocations - countersBefore.analyzeInvocations;
        var apDelta = countersAfter.apEnabledBatches - countersBefore.apEnabledBatches;
        LOG.info(
                String.format(
                        "[perf] completion_flow file=%s mode=%s sources=1 enter=%d analyze=%d ap=%d index_version=%d took=%dms",
                        file.getFileName(),
                        mode,
                        enterDelta,
                        analyzeDelta,
                        apDelta,
                        completionIndexVersion,
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

    private CompletionList completeParseAndIndex(
            ParseTask parseTask,
            TreePath parsePath,
            long cursor,
            MemberAccessContext memberAccess,
            boolean endsWithParen) {
        return completeMembersUsingIndex(parseTask, parsePath, cursor, memberAccess, endsWithParen);
    }

    private CompletionList completeMembersUsingIndex(
            ParseTask parseTask,
            TreePath parsePath,
            long cursor,
            MemberAccessContext memberAccess,
            boolean endsWithParen) {
        if (completionIndex == null || completionIndex.size() == 0) {
            LOG.info("[perf] completion_member_index state=miss reason=index_empty receiver=" + memberAccess.receiver);
            return EMPTY;
        }
        var index = completionIndex;
        var started = Instant.now();
        var resolver = new ParseTypeResolver(parseTask, index, cursor);
        var expression = memberReceiverExpression(parsePath);
        var resolved = resolver.resolve(expression, memberAccess.receiver);
        if (resolved.isEmpty()) {
            LOG.info("[completion] unresolved receiver at position " + cursor + ", no fallback used");
            LOG.info(
                    "[perf] completion_member_index state=miss receiver="
                            + memberAccess.receiver
                            + " reason=unresolved_type");
            return EMPTY;
        }
        var target = resolved.get();
        if (!target.arrayType && !isQualifiedTypeName(target.qualifiedType)) {
            LOG.info("[completion] unresolved receiver at position " + cursor + ", no fallback used");
            LOG.info(
                    "[perf] completion_member_index state=miss receiver="
                            + memberAccess.receiver
                            + " reason=non_fqn_type type="
                            + target.qualifiedType);
            return EMPTY;
        }
        var currentType = resolver.currentEnclosingTypeName();
        if (target.arrayType) {
            return completeArrayMemberSelect(target.staticContext);
        }
        var members = index.members(target.qualifiedType, target.staticContext);
        if (members.isEmpty()) {
            LOG.info(
                    "[perf] completion_member_index state=miss receiver="
                            + memberAccess.receiver
                            + " type="
                            + target.qualifiedType
                            + " reason=no_members");
            return EMPTY;
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
            LOG.info(
                    String.format(
                            "[perf] completion_member_index state=cache_hit receiver=%s type=%s items=%d",
                            memberAccess.receiver, target.qualifiedType, cached.items.size()));
            return copyCompletionList(cached);
        }

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
        if (memberAccess.partial.isEmpty()
                && !"java.lang.Object".equals(target.qualifiedType)
                && isWeakObjectOnlyResult(result)) {
            LOG.info(
                    "[perf] completion_member_index state=drop_weak_object_only receiver="
                            + memberAccess.receiver
                            + " type="
                            + target.qualifiedType
                            + " items="
                            + list.size());
            return EMPTY;
        }
        sortCompletionItems(list);
        result = new CompletionList(false, list);
        MEMBER_COMPLETION_CACHE.put(cacheKey, freezeCompletionList(result));
        LOG.info(
                String.format(
                        "[perf] completion_member_index state=hit receiver=%s type=%s items=%d took=%dms",
                        memberAccess.receiver,
                        target.qualifiedType,
                        list.size(),
                        Duration.between(started, Instant.now()).toMillis()));
        return result;
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

    private static class TypeResolution {
        final String qualifiedType;
        final boolean staticContext;
        final boolean arrayType;

        TypeResolution(String qualifiedType, boolean staticContext, boolean arrayType) {
            this.qualifiedType = qualifiedType;
            this.staticContext = staticContext;
            this.arrayType = arrayType;
        }
    }

    private static class CandidateType {
        final TypeResolution resolution;
        final int depth;
        final long start;

        CandidateType(TypeResolution resolution, int depth, long start) {
            this.resolution = resolution;
            this.depth = depth;
            this.start = start;
        }
    }

    private static class ParseTypeResolver {
        private static final int MAX_RESOLVE_DEPTH = 24;
        private final ParseTask parseTask;
        private final CompilationUnitTree root;
        private final SourcePositions positions;
        private final TypeMemberIndex index;
        private final long cursor;
        private final TreePath cursorPath;
        private TypeResolution thisType;
        private TypeResolution superType;

        ParseTypeResolver(ParseTask parseTask, TypeMemberIndex index, long cursor) {
            this.parseTask = parseTask;
            this.root = parseTask.root;
            this.positions = Trees.instance(parseTask.task).getSourcePositions();
            this.index = index;
            this.cursor = cursor;
            this.cursorPath = new FindCompletionsAt(parseTask.task).scan(parseTask.root, cursor);
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
                return resolveTypeTree(newClassTree.getIdentifier(), false);
            }
            if (expression instanceof NewArrayTree newArrayTree) {
                if (newArrayTree.getType() == null) {
                    return Optional.empty();
                }
                var component = resolveTypeTree(newArrayTree.getType(), false);
                if (component.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new TypeResolution(component.get().qualifiedType, false, true));
            }
            if (expression instanceof MethodInvocationTree invocationTree) {
                return resolveMethodInvocation(invocationTree, depth + 1);
            }
            if (expression instanceof MemberSelectTree memberSelectTree) {
                return resolveMemberSelect(memberSelectTree, depth + 1);
            }
            if (expression instanceof TypeCastTree castTree) {
                return resolveTypeTree(castTree.getType(), false);
            }
            if (expression instanceof ArrayAccessTree arrayAccessTree) {
                var array = resolveExpression(arrayAccessTree.getExpression(), depth + 1);
                if (array.isEmpty()) return Optional.empty();
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
                if (current.isEmpty()) return Optional.empty();
                var method = index.member(current.get().qualifiedType, identifier.getName().toString(), false);
                if (method.isEmpty()) return Optional.empty();
                return returnTypeOf(method.get());
            }
            if (select instanceof MemberSelectTree memberSelectTree) {
                var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
                if (receiver.isEmpty()) return Optional.empty();
                var method =
                        index.member(
                                receiver.get().qualifiedType,
                                memberSelectTree.getIdentifier().toString(),
                                receiver.get().staticContext);
                if (method.isEmpty()) return Optional.empty();
                return returnTypeOf(method.get());
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> resolveMemberSelect(MemberSelectTree memberSelectTree, int depth) {
            var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
            if (receiver.isEmpty()) {
                return resolveTypeTree(memberSelectTree, true);
            }
            if (receiver.get().arrayType && "length".equals(memberSelectTree.getIdentifier().toString())) {
                return Optional.of(new TypeResolution("int", false, false));
            }
            if (receiver.get().staticContext
                    && "class".equals(memberSelectTree.getIdentifier().toString())) {
                return Optional.of(new TypeResolution("java.lang.Class", false, false));
            }
            var member =
                    index.member(
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
            var nested = resolveNestedTypeInEnclosingScopes(identifier);
            if (nested.isPresent()) {
                return Optional.of(new TypeResolution(nested.get(), true, false));
            }
            var type = index.resolveTypeName(identifier, root);
            if (type.isPresent()) {
                return Optional.of(new TypeResolution(type.get(), true, false));
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
            if (!CompletionProvider.hasSlf4jAnnotation(classTree.getModifiers())) {
                return Optional.empty();
            }
            var loggerType = "org.slf4j.Logger";
            if (!index.types().containsKey(loggerType)) {
                return Optional.empty();
            }
            return Optional.of(new TypeResolution(loggerType, false, false));
        }

        private Optional<String> resolveNestedTypeInEnclosingScopes(String simpleName) {
            for (var classPath = enclosingClassPath(); classPath != null; classPath = parentClassPath(classPath.getParentPath())) {
                var owner = qualifiedClassName(classPath);
                var candidate = owner + "." + simpleName;
                if (index.types().containsKey(candidate)) {
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
                    if (!containsCursor(classTree)) return null;
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
                    if (!containsCursor(methodTree)) return null;
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
                    if (!containsCursor(blockTree)) return null;
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
                    if (!variableTree.getName().contentEquals(targetName)) return;
                    var start = startOf(variableTree);
                    if (start < 0 || start >= cursor) return;
                    var resolved = resolveVariableType(variableTree, path, nextDepth);
                    if (resolved.isEmpty()) return;
                    var candidate = new CandidateType(resolved.get(), depth(path), start);
                    if (best[0] == null
                            || candidate.depth > best[0].depth
                            || (candidate.depth == best[0].depth && candidate.start > best[0].start)) {
                        best[0] = candidate;
                    }
                }
            }.scan(root, null);

            if (best[0] == null) return Optional.empty();
            return Optional.of(best[0].resolution);
        }

        private Optional<TypeResolution> resolveVariableType(VariableTree variableTree, TreePath path, int depth) {
            if (variableTree.getType() != null && !"var".equals(variableTree.getType().toString())) {
                return resolveTypeTree(variableTree.getType(), false);
            }
            if (variableTree.getInitializer() != null) {
                return resolveExpression(variableTree.getInitializer(), depth + 1);
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> resolveTypeTree(Tree tree, boolean staticContext) {
            if (tree == null) return Optional.empty();
            if (tree instanceof AnnotatedTypeTree annotatedTypeTree) {
                return resolveTypeTree(annotatedTypeTree.getUnderlyingType(), staticContext);
            }
            if (tree instanceof ParameterizedTypeTree parameterizedTypeTree) {
                return resolveTypeTree(parameterizedTypeTree.getType(), staticContext);
            }
            if (tree instanceof ArrayTypeTree arrayTypeTree) {
                var component = resolveTypeTree(arrayTypeTree.getType(), staticContext);
                if (component.isEmpty()) return Optional.empty();
                return Optional.of(new TypeResolution(component.get().qualifiedType, staticContext, true));
            }
            var typeName = tree.toString();
            var resolved = index.resolveTypeName(typeName, root);
            return resolved.map(type -> new TypeResolution(type, staticContext, false));
        }

        private Optional<TypeResolution> resolveThisType() {
            if (thisType != null) {
                return Optional.of(thisType);
            }
            var classPath = enclosingClassPath();
            if (classPath == null) return Optional.empty();
            var qualified = qualifiedClassName(classPath);
            if (!index.types().containsKey(qualified)) {
                return Optional.empty();
            }
            thisType = new TypeResolution(qualified, false, false);
            return Optional.of(thisType);
        }

        Optional<String> currentEnclosingTypeName() {
            return resolveThisType().map(type -> type.qualifiedType);
        }

        boolean isEnclosingInstanceType(String qualifiedType) {
            if (qualifiedType == null || qualifiedType.isBlank()) {
                return false;
            }
            if (cursorPath == null) {
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
                    if (parent != null
                            && parent.getLeaf() instanceof ClassTree
                            && blockTree.isStatic()) {
                        blockedByStaticContext = true;
                    }
                    continue;
                }
                if (!(leaf instanceof ClassTree classTree)) {
                    continue;
                }
                var className = qualifiedClassName(path);
                if (qualifiedType.equals(className)) {
                    return !blockedByStaticContext;
                }
                if (classTree.getModifiers() != null
                        && classTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
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
            if (classPath == null) return Optional.empty();
            var classTree = (ClassTree) classPath.getLeaf();
            if (classTree.getExtendsClause() == null) {
                superType = new TypeResolution("java.lang.Object", false, false);
                return Optional.of(superType);
            }
            var resolved = resolveTypeTree(classTree.getExtendsClause(), false);
            if (resolved.isPresent()) {
                superType = resolved.get();
                return resolved;
            }
            return Optional.empty();
        }

        private Optional<TypeResolution> returnTypeOf(TypeMemberIndex.Member member) {
            if (member.returnType == null || member.returnType.isBlank()) {
                return Optional.empty();
            }
            var type = member.returnType;
            var isArray = type.endsWith("[]");
            if (isArray) {
                type = type.substring(0, type.length() - 2);
            }
            var resolved = index.resolveTypeName(type, root);
            return resolved.map(next -> new TypeResolution(next, false, isArray));
        }

        private Optional<TypeResolution> literalType(LiteralTree literal) {
            var value = literal.getValue();
            if (value == null) return Optional.empty();
            if (value instanceof String) return Optional.of(new TypeResolution("java.lang.String", false, false));
            if (value instanceof Integer) return Optional.of(new TypeResolution("java.lang.Integer", false, false));
            if (value instanceof Long) return Optional.of(new TypeResolution("java.lang.Long", false, false));
            if (value instanceof Float) return Optional.of(new TypeResolution("java.lang.Float", false, false));
            if (value instanceof Double) return Optional.of(new TypeResolution("java.lang.Double", false, false));
            if (value instanceof Boolean) return Optional.of(new TypeResolution("java.lang.Boolean", false, false));
            if (value instanceof Character) return Optional.of(new TypeResolution("java.lang.Character", false, false));
            return Optional.empty();
        }

        private TreePath enclosingClassPath() {
            final TreePath[] best = new TreePath[1];
            final int[] bestDepth = {-1};
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree classTree, Void p) {
                    if (!containsCursor(classTree)) return null;
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

        private String qualifiedClassName(TreePath classPath) {
            var classes = new ArrayList<String>();
            for (var cursorPath = classPath; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
                if (cursorPath.getLeaf() instanceof ClassTree classTree) {
                    classes.add(classTree.getSimpleName().toString());
                }
            }
            Collections.reverse(classes);
            var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (packageName.isEmpty()) {
                return String.join(".", classes);
            }
            return packageName + "." + String.join(".", classes);
        }

        private boolean containsCursor(Tree tree) {
            var start = startOf(tree);
            var end = endOf(tree);
            if (start < 0 || end < 0) return false;
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
        var path = new FindCompletionsAt(parseTask.task).scan(parseTask.root, cursor);
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
                addSlf4jLoggerIfAnnotated(parseTask, path, cursor, partial, list);
                addImportedTypeNames(parseTask.root, partial, list);
                if (!list.isIncomplete && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
                    addClassNames(parseTask.root, Trees.instance(parseTask.task).getSourcePositions(), partial, list);
                }
                boostExactMatches(parseTask.root, list.items, partial);
                sortCompletionItems(list.items);
                return list;
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
        addStaticImports(task, path.getCompilationUnit(), partial, endsWithParen, list);
        addImportedTypeNames(path.getCompilationUnit(), partial, list);
        if (!list.isIncomplete && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), Trees.instance(task.task).getSourcePositions(), partial, list);
        }
        addKeywords(path, partial, list);
        boostExactMatches(path.getCompilationUnit(), list.items, partial);
        sortCompletionItems(list.items);
        return list;
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
        var trees = Trees.instance(task.task);
        var positions = trees.getSourcePositions();
        var seen = new HashSet<String>();
        for (var item : list.items) {
            seen.add(item.label);
        }
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree t, Void __) {
                var start = positions.getStartPosition(task.root, t);
                if (start < 0 || start >= cursor) return super.visitVariable(t, null);
                var name = t.getName().toString();
                if (name.isEmpty()) return super.visitVariable(t, null);
                if (!StringSearch.matchesPartialName(name, partial)) return super.visitVariable(t, null);
                if (seen.add(name)) {
                    list.items.add(variable(name));
                }
                return super.visitVariable(t, null);
            }
        }.scan(task.root, null);
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
        if (!hasSlf4jAnnotation(classTree.getModifiers())) {
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
        var positions = Trees.instance(parseTask.task).getSourcePositions();
        final TreePath[] best = new TreePath[1];
        final int[] bestDepth = {-1};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                var start = positions.getStartPosition(parseTask.root, classTree);
                var end = positions.getEndPosition(parseTask.root, classTree);
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
        }.scan(parseTask.root, null);
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

    private static boolean hasSlf4jAnnotation(ModifiersTree modifiers) {
        if (modifiers == null) {
            return false;
        }
        for (var annotation : modifiers.getAnnotations()) {
            if (isSlf4jAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSlf4jAnnotation(AnnotationTree annotation) {
        if (annotation == null) {
            return false;
        }
        var type = annotation.getAnnotationType();
        if (type instanceof IdentifierTree identifier) {
            return "Slf4j".equals(identifier.getName().toString());
        }
        if (type instanceof MemberSelectTree select) {
            return "lombok.extern.slf4j.Slf4j".equals(select.toString()) || "Slf4j".equals(select.getIdentifier().toString());
        }
        return false;
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
        var trees = Trees.instance(task.task);
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
                    var start = positions.getStartPosition(task.root, invocation);
                    if (start >= 0 && start < cursor) {
                        addMethod(member.getIdentifier().toString(), receiver, partial, list, seen);
                    }
                }
                return super.visitMethodInvocation(invocation, __);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree member, Void __) {
                if (receiverMatches(member.getExpression(), receiver)) {
                    var start = positions.getStartPosition(task.root, member);
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
        }.scan(task.root, null);
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

    private List<CompletionItem> completeUsingScope(
            CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        var trees = Trees.instance(task.task);
        var list = new ArrayList<CompletionItem>();
        var methods = new TreeMap<String, List<ExecutableElement>>();
        var scope = trees.getScope(path);
        Predicate<CharSequence> filter = name -> matchesCompletionPrefix(name, partial);
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
        var methods = new TreeMap<String, List<ExecutableElement>>();
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

    private void addClassNames(
            CompilationUnitTree root, SourcePositions sourcePositions, String partial, CompletionList list) {
        var packageName = Objects.toString(root.getPackageName(), "");
        var uniques = new HashSet<String>();
        var previousSize = list.items.size();
        for (var className : compiler.packagePrivateTopLevelTypes(packageName)) {
            if (!StringSearch.matchesPartialName(className, partial)) continue;
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
            uniques.add(className);
        }
        for (var className : compiler.publicTopLevelTypes()) {
            if (!StringSearch.matchesPartialName(simpleName(className), partial)) continue;
            if (uniques.contains(className)) continue;
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
            uniques.add(className);
        }
        LOG.info("...found " + (list.items.size() - previousSize) + " class names");
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

    private CompletionList completeMemberSelect(
            CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        var select = (MemberSelectTree) path.getLeaf();
        LOG.info("...complete members of " + select.getExpression());
        var expressionPath = new TreePath(path, select.getExpression());
        return completeMembersForExpression(task, expressionPath, select.getExpression(), partial, endsWithParen);
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
        if (type instanceof ArrayType) {
            return completeArrayMemberSelect(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(
                    task, scope, (TypeVariable) type, isStatic, partial, endsWithParen, includePrivate);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(
                    task, scope, (DeclaredType) type, isStatic, partial, endsWithParen, includePrivate);
        } else {
            return NOT_SUPPORTED;
        }
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
        var methods = new TreeMap<String, List<ExecutableElement>>();
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
        sortCompletionItems(list);
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
        var expressionPath = new TreePath(path, switchTree.getExpression());
        var type = resolveSwitchExpressionType(task, expressionPath);
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

    private TypeMirror resolveSwitchExpressionType(CompileTask task, TreePath expressionPath) {
        var trees = Trees.instance(task.task);
        if (expressionPath.getLeaf() instanceof ParenthesizedTree) {
            var paren = (ParenthesizedTree) expressionPath.getLeaf();
            return resolveSwitchExpressionType(task, new TreePath(expressionPath, paren.getExpression()));
        }
        var direct = trees.getTypeMirror(expressionPath);
        if (direct instanceof DeclaredType) {
            return direct;
        }
        var expression = expressionPath.getLeaf();
        if (expression instanceof MethodInvocationTree) {
            return resolveInvocationReturnType(task, expressionPath, (MethodInvocationTree) expression);
        }
        if (expression instanceof IdentifierTree) {
            var element = trees.getElement(expressionPath);
            if (element instanceof VariableElement) {
                return ((VariableElement) element).asType();
            }
        }
        return direct;
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
        if (invocation.getMethodSelect() instanceof MemberSelectTree) {
            var select = (MemberSelectTree) invocation.getMethodSelect();
            var qualifierPath = new TreePath(selectPath, select.getExpression());
            var qualifierType = trees.getTypeMirror(qualifierPath);
            if (!(qualifierType instanceof DeclaredType)) return qualifierType;
            var qualifierDeclared = (DeclaredType) qualifierType;
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
        var first = overloads.get(0);
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

    private CompletionItem variable(String name) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Variable;
        i.detail = "local";
        i.sortText = sortKey(Priority.LOCAL, i.label);
        return i;
    }

    private static class Priority {
        static final int EXACT_IMPORTED_MATCH = -2;
        static final int EXACT_MATCH = -1;
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

    private static class MemberAccessContext {
        final String receiver;
        final String partial;
        final boolean dotTrigger;

        MemberAccessContext(String receiver, String partial, boolean dotTrigger) {
            this.receiver = receiver;
            this.partial = partial;
            this.dotTrigger = dotTrigger;
        }
    }

    private void logCompletionTiming(Instant started, List<?> list, boolean isIncomplete) {
        var elapsedMs = Duration.between(started, Instant.now()).toMillis();
        if (isIncomplete)
            LOG.info(String.format("[perf] completion_total items=%d incomplete=true took=%dms", list.size(), elapsedMs));
        else LOG.info(String.format("[perf] completion_total items=%d incomplete=false took=%dms", list.size(), elapsedMs));
    }

    private CharSequence simpleName(String className) {
        var dot = className.lastIndexOf('.');
        if (dot == -1) return className;
        return className.subSequence(dot + 1, className.length());
    }

    private static final Logger LOG = Logger.getLogger("main");
}
