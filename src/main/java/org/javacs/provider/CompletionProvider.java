package org.javacs.provider;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import static org.javacs.provider.CompletionItems.*;

import com.google.gson.JsonNull;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens.TokenKind;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;

import org.javacs.ClassFileParameterNames;
import org.javacs.CompileTask;
import org.javacs.ElementSignatureHelper;
import org.javacs.CompilerProvider;
import org.javacs.CompletionData;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.JsonHelper;
import org.javacs.MarkdownHelper;
import org.javacs.ParseTask;
import org.javacs.SourceFileObject;
import org.javacs.StringSearch;
import org.javacs.completion.FindCompletionsAt;
import org.javacs.completion.PruneMethodBodies;
import org.javacs.index.IndexedMember;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.CompletionList;
import org.javacs.lsp.Position;
import org.javacs.resolve.TypeNames;
import org.javacs.rewrite.AddImport;

public class CompletionProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private record MemberAccessContext(String partial, boolean methodReference) { }
    private record AnnotationContext(String qualifiedAnnotationType, String partial, boolean attributePosition, String attributeName) {}
    private record CompletionPrep(ParseTask task, String contents, long cursor, long parseMs) {}

    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndexRouter;
    private final long completionIndexVersion;
    private final String compilerId;

    public static final CompletionList NOT_SUPPORTED = new CompletionList(false, List.of());
    private static final CompletionList EMPTY = new CompletionList(false, List.of());

    public static final int MAX_COMPLETION_ITEMS = 50;

    public CompletionProvider(
            CompilerProvider compiler,
            TypeIndexRouter typeIndexRouter,
            long completionIndexVersion,
            String compilerId) {
        this.compiler = compiler;
        this.typeIndexRouter = typeIndexRouter == null ? TypeIndexRouter.EMPTY : typeIndexRouter;
        this.completionIndexVersion = Math.max(0L, completionIndexVersion);
        this.compilerId = compilerId;
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
        var prep = prepare(file, line, column);
        if (prep == null) {
            return NOT_SUPPORTED;
        }
        var path = new FindCompletionsAt(prep.task().task()).scan(prep.task().root(), prep.cursor());
        if (path != null && (path.getLeaf().getKind() == Tree.Kind.STRING_LITERAL
                || path.getLeaf().getKind() == Tree.Kind.CHAR_LITERAL)) {
            logCompletionFlow(file, "LITERAL", prep.parseMs(), started);
            return EMPTY;
        }
        var partial = partialIdentifier(prep.task(), path, prep.contents(), (int) prep.cursor());
        var member = memberAccessContext(path, partial, prep.contents(), (int) prep.cursor());
        var annotation = findAnnotationContext(prep, path, partial);
        CompletionList list;
        String route;
        if (isImportOrPackageContext(path) && path.getLeaf().getKind() == Tree.Kind.IMPORT) {
            route = "IMPORT";
            list = completePackage(qualifiedPartialIdentifier(prep.task(), path, prep.contents(), (int) prep.cursor()), false, false);
        } else if (isAnnotationName(prep, path, partial)) {
            route = "ANNOTATION_NAME";
            list = completeAnnotationName(prep, partial);
        } else if (member != null && !isImportOrPackageContext(path)) {
            route = "MEMBER";
            list = completeMember(prep.task(), prep.contents(), prep.cursor(), member,
                    endsWithParen(prep.contents(), (int) prep.cursor()), annotation != null);
        } else if (annotation != null) {
            route = "ANNOTATION";
            list = completeAnnotation(prep, path, partial, annotation);
        } else if (switchExpression(path) != null) {
            route = "ENUM_CASE";
            list = completeEnumCase(prep, path, partial);
        } else {
            route = "IDENTIFIER";
            list = completeIdentifier(prep.task(), path, prep.contents(), prep.cursor(), partial);
        }
        if (list == NOT_SUPPORTED) {
            return NOT_SUPPORTED;
        }
        if (!route.equals("ANNOTATION_NAME")) list = finalizeCompletionList(prep.task(), list);
        logCompletionFlow(file, route, prep.parseMs(), started);
        return list;
    }

    private CompletionList completeAnnotation(
            CompletionPrep prep, TreePath path, String partial, AnnotationContext annotation) {
        var list = new CompletionList();
        if (annotation.attributePosition()) {
          list.items.addAll(completeAnnotationAttributes(annotation).items);
        }
        if (!annotation.attributePosition()) {
            var attribute = typeIndexRouter.member(annotation.qualifiedAnnotationType(), annotation.attributeName(), false);
            var expected = attribute.flatMap(m -> typeIndexRouter.resolveTypeName(m.returnType, prep.task().root()));
            if (expected.isPresent()) {
                var enumType = expected.get();
                var simpleName = TypeNames.simpleName(enumType);
                if (matchesCompletionPrefix(simpleName, partial)) {
                    var item = CompletionItems.classItem(enumType, compilerId, false);
                    item.sortText = sortKey(Priority.LOCAL, item.label);
                    var imported = typeIndexRouter.resolveTypeName(simpleName, prep.task().root());
                    if (imported.isPresent() && !imported.get().equals(enumType)) {
                        qualify(item, enumType, false);
                    } else if (imported.isEmpty()) {
                        item.additionalTextEdits = List.of(AddImport.createTextEdits(enumType, prep.task().root(),
                                Trees.instance(prep.task().task()).getSourcePositions())[0]);
                    }
                    list.items.add(item);
                }
                for (var member : typeIndexRouter.members(enumType, true)) {
                    if (!isEnumCaseConstant(member, enumType)) continue;
                    if (!matchesCompletionPrefix(member.name, partial)) continue;
                    var item = indexedMember(member);
                    qualify(item, enumType + "." + member.name, false);
                    list.items.add(item);
                }
            }
        }
        addExpressionCandidates(prep, path, partial, list, true);
        return list;
    }

    private boolean isAnnotationName(CompletionPrep prep, TreePath path, String partial) {
        for (var current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof LiteralTree) return false;
            if (current.getLeaf() instanceof AnnotationTree annotation) {
                var end = Trees.instance(prep.task().task()).getSourcePositions()
                        .getEndPosition(prep.task().root(), annotation.getAnnotationType());
                return prep.cursor() <= end;
            }
        }
        // A bare @ may not have an annotation tree yet.
        var start = (int) prep.cursor() - partial.length();
        if (start <= 0 || prep.contents().charAt(start - 1) != '@') return false;
        var scanner = ScannerFactory.instance(((BasicJavacTask) prep.task().task()).getContext())
                .newScanner(prep.contents(), false);
        do {
            scanner.nextToken();
        } while (scanner.token().pos < start - 1 && scanner.token().kind != TokenKind.EOF);
        return scanner.token().pos == start - 1 && scanner.token().kind == TokenKind.MONKEYS_AT;
    }

    private CompletionList completeAnnotationName(CompletionPrep prep, String partial) {
        var qualified = qualifiedPartialIdentifier(prep.task(), null, prep.contents(), (int) prep.cursor());
        if (qualified.indexOf('.') >= 0) return completePackage(qualified, false, true);
        var list = new CompletionList();
        var imports = new LinkedHashMap<String, String>();
        var insertionLines = new TreeMap<String, Integer>();
        collectImports(prep.task(), imports, new ArrayList<>(), insertionLines);
        addTypesAndPackages(prep.task().root(), imports, insertionLines, partial, false, true, list);
        return list;
    }

    private static boolean isConstructorType(TreePath path) {
        for (; path != null && path.getParentPath() != null; path = path.getParentPath()) {
            if (path.getParentPath().getLeaf() instanceof NewClassTree constructor) {
                return constructor.getIdentifier() == path.getLeaf();
            }
        }
        return false;
    }

    private CompletionPrep prepare(Path file, int line, int column) {
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var contents = new PruneMethodBodies(task.task()).scan(task.root(), cursor);
        var pruned = contents.toString();
        return new CompletionPrep(task, pruned, cursor, parseMs);
    }

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
        if (!(list.items instanceof ArrayList)) {
            list.items = new ArrayList<>(list.items);
        }
        addTopLevelSnippets(task, list);
        return list;
    }

    private void logCompletionFlow(
            Path file, String route, long parseMs, Instant started) {
        LOG.info(
                String.format(
                        "[perf] completion_flow file=%s route=%s index_version=%d parse=%dms took=%dms",
                        file.getFileName(),
                        route,
                        completionIndexVersion,
                        parseMs,
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

    private String terminateCompletionLine(String contents, long cursor) {
        return new StringBuilder(contents).insert(endOfLine(contents, (int) cursor), ';').toString();
    }

    private CompletionList completeMember(
            ParseTask parseTask, String contents, long cursor,
            MemberAccessContext memberAccess, boolean endsWithParen, boolean inAnnotation) {
        // A statement terminator after an annotation detaches it from its declaration.
        if (!inAnnotation) contents = terminateCompletionLine(contents, cursor);
        try {
            var file = Paths.get(parseTask.root().getSourceFile().toUri());
            var source = new SourceFileObject(file, contents, Instant.now());
            try (var task = compiler.compile(List.of(source), true)) {
                var trees = Trees.instance(task.task);
                var path = new FindCompletionsAt(task.task).scan(task.root(file), cursor);
                if (path == null) return EMPTY;
                Tree receiverExpr;
                if (path.getLeaf() instanceof MemberSelectTree select) {
                    receiverExpr = select.getExpression();
                } else if (path.getLeaf() instanceof MemberReferenceTree ref) {
                    receiverExpr = ref.getQualifierExpression();
                } else {
                    return EMPTY;
                }
                var exprPath = new TreePath(path, receiverExpr);
                var receiver = trees.getElement(exprPath);
                if (receiver instanceof javax.lang.model.element.PackageElement packageElement) {
                    return completePackage(packageElement.getQualifiedName() + "." + memberAccess.partial(),
                            isConstructorType(path) && !endsWithParen, false);
                }
                var type = trees.getTypeMirror(exprPath);
                if (type == null || type.getKind() == TypeKind.ERROR) {
                    return EMPTY;
                }
                var isStatic = receiver instanceof TypeElement;
                var scope = trees.getScope(path);
                if (type instanceof ArrayType) {
                    return completeArrayMemberSelect(isStatic);
                }
                if (type instanceof TypeVariable tv) {
                    type = tv.getUpperBound();
                }
                if (!(type instanceof DeclaredType declared)) {
                    return EMPTY;
                }
                return memberItems(task, declared, scope, isStatic, memberAccess, endsWithParen, inAnnotation);
            }
        } catch (Exception e) {
            LOG.warning("[completion] compile_member_failed: " + e);
            return EMPTY;
        }
    }

    private CompletionList memberItems(
            CompileTask task,
            DeclaredType declared,
            Scope scope,
            boolean isStatic,
            MemberAccessContext memberAccess,
            boolean endsWithParen, boolean inAnnotation) {
        var trees = task.trees;
        var typeElement = (TypeElement) declared.asElement();
        var methodReference = memberAccess.methodReference();
        var list = new ArrayList<CompletionItem>();
        var methods = new TreeMap<String, List<ExecutableElement>>();
        for (var member : task.task.getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR
                    || inAnnotation && member.getKind() == ElementKind.METHOD) continue;
            if (!matchesCompletionPrefix(member.getSimpleName(), memberAccess.partial)) continue;
            if (!trees.isAccessible(scope, member, declared)) continue;
            if (methodReference) {
                if (member.getKind() != ElementKind.METHOD) continue;
            } else if (isStatic != member.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            if (member.getKind() == ElementKind.METHOD) {
                methods.computeIfAbsent(member.getSimpleName().toString(), __ -> new ArrayList<>())
                        .add((ExecutableElement) member);
            } else {
                list.add(CompletionItems.compiledMember(member, task.task.getElements().isDeprecated(member)));
            }
        }
        byte[] ownerClassBytes = methods.isEmpty() ? null
                : compiler.findClassFile(typeElement.getQualifiedName().toString()).orElse(null);
        var sourceByType = new HashMap<String, ParseTask>();
        for (var entry : methods.entrySet()) {
            var overloads = entry.getValue();
            list.add(compiledMethod(task, typeElement.getQualifiedName().toString(), overloads,
                    ownerClassBytes, sourceByType, !endsWithParen && !methodReference));
        }
        if (methodReference && isStatic && !inAnnotation) {
            list.add(keyword("new"));
        } else if (isStatic) {
            list.add(keyword("class"));
        }
        return new CompletionList(false, list);
    }

    private CompletionItem compiledMethod(CompileTask task, String ownerType,
            List<ExecutableElement> overloads, byte[] ownerClassBytes,
            Map<String, ParseTask> sourceByType, boolean addParentheses) {
        var first = overloads.getFirst();
        var sourceMethod = ElementSignatureHelper.hasSyntheticNames(first.getParameters())
                ? sourceMethod(first, task, sourceByType) : null;
        var data = new CompletionData();
        data.className = ownerType;
        data.memberName = first.getSimpleName().toString();
        data.erasedParameterTypes = FindHelper.erasedParameterTypes(task, first);
        data.plusOverloads = overloads.size() - 1;
        data.compilerId = compilerId;
        return CompletionItems.compiledMethod(first, ownerClassBytes, sourceMethod, data, addParentheses);
    }

    private String[] indexParameterNames(IndexedMember member, byte[] classBytes) {
        if (member.parameterNames != null) return member.parameterNames;
        var types = member.erasedParameterTypes;
        if (types == null || types.length == 0 || member.kind == CompletionItemKind.Field) return null;
        if (classBytes == null) classBytes = compiler.findClassFile(member.ownerType).orElse(null);
        return classBytes == null ? null
                : ClassFileParameterNames.read(classBytes, member.name, types, member.isStatic);
    }

    private ParseTask ownerSource(String ownerType, Map<String, ParseTask> memo) {
        if (memo.containsKey(ownerType)) return memo.get(ownerType);
        var parsed = compiler.findAnywhere(ownerType).map(compiler::parse).orElse(null);
        memo.put(ownerType, parsed);
        return parsed;
    }

    private MethodTree sourceMethod(ExecutableElement method, CompileTask task, Map<String, ParseTask> memo) {
        if (!(method.getEnclosingElement() instanceof TypeElement owner)) return null;
        var ownerType = owner.getQualifiedName().toString();
        var ownerParse = ownerSource(ownerType, memo);
        if (ownerParse == null) return null;
        try {
            return FindHelper.findMethod(ownerParse, ownerType, method.getSimpleName().toString(),
                    FindHelper.erasedParameterTypes(task, method));
        } catch (RuntimeException notFound) {
            return null;
        }
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
        return indexedMember(member, false);
    }

    private CompletionItem indexedMember(IndexedMember member, boolean addParentheses) {
        return CompletionItems.indexedMember(member, indexParameterNames(member, null), compilerId, addParentheses);
    }

    private CompletionItem indexedMethod(List<IndexedMember> overloads, boolean addParens, byte[] classBytes) {
        return CompletionItems.indexedMethod(overloads, indexParameterNames(overloads.getFirst(), classBytes),
                compilerId, addParens);
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

    private CompletionList completeIdentifier(
            ParseTask parseTask, TreePath path, String contents, long cursor, String partial) {
        var list = new CompletionList();
        if (path == null) return list;
        var endsWithParen = endsWithParen(contents, (int) cursor);
        var constructor = isConstructorType(path);
        var addParentheses = constructor && !endsWithParen;
        var importedTypes = new LinkedHashMap<String, String>();
        var staticImports = new ArrayList<MemberSelectTree>();
        var insertionLines = new TreeMap<String, Integer>();
        collectImports(parseTask, importedTypes, staticImports, insertionLines);
        var positions = new HashMap<String, Integer>();
        var scope = enclosingScopes(path);
        if (!constructor) {
            addKeywords(path, partial, list);
            for (int i = 0; i < list.items.size(); i++) positions.put(list.items.get(i).label, i);
            addSyntacticLocalVariables(parseTask, cursor, partial, positions, list);
        }
        addSyntacticEnclosingTypeMembers(parseTask, scope, partial, endsWithParen, addParentheses, constructor, true, positions, list);
        addIndexedEnclosingTypeMembers(parseTask, scope, partial, list, endsWithParen, addParentheses, constructor, true, positions);
        if (!constructor && !scope.isEmpty() && !scope.values().iterator().next()) {
            if (positions.putIfAbsent("this", list.items.size()) == null) list.items.add(keyword("this"));
            if (positions.putIfAbsent("super", list.items.size()) == null) list.items.add(keyword("super"));
        }
        addStaticImportsFromIndex(staticImports, partial, endsWithParen, addParentheses, constructor, true, positions, list);
        addImportedTypeNames(importedTypes, partial, addParentheses, positions, list);
        if (!list.isIncomplete && !partial.isEmpty()) {
            addTypesAndPackages(parseTask.root(), importedTypes, insertionLines, partial, addParentheses, false, list);
        }
        return list;
    }

    private void addExpressionCandidates(
            CompletionPrep prep, TreePath path, String partial, CompletionList list, boolean annotationValues) {
        var positions = new HashMap<String, Integer>();
        for (int i = 0; i < list.items.size(); i++) {
          positions.put(list.items.get(i).label, i);
        }
        var scope = enclosingScopes(path);
        var paren = endsWithParen(prep.contents(), (int) prep.cursor());
        addSyntacticLocalVariables(prep.task(), prep.cursor(), partial, positions, list);
        addSyntacticEnclosingTypeMembers(prep.task(), scope, partial, paren, false, false, !annotationValues, positions, list);
        addIndexedEnclosingTypeMembers(prep.task(), scope, partial, list, paren, false, false, !annotationValues, positions);
        var importedTypes = new LinkedHashMap<String, String>();
        var staticImports = new ArrayList<MemberSelectTree>();
        var insertionLines = new TreeMap<String, Integer>();
        collectImports(prep.task(), importedTypes, staticImports, insertionLines);
        addStaticImportsFromIndex(staticImports, partial, paren, false, false, !annotationValues, positions, list);
        addImportedTypeNames(importedTypes, partial, false, positions, list);
        if (!partial.isEmpty()) {
            addTypesAndPackages(prep.task().root(), importedTypes, insertionLines, partial, false, false, list);
        }
        for (var literal : List.of("true", "false")) {
            if (matchesCompletionPrefix(literal, partial) && positions.putIfAbsent(literal, list.items.size()) == null) {
                list.items.add(keyword(literal));
            }
        }
    }

    private static Map<TreePath, Boolean> enclosingScopes(TreePath path) {
        var scopes = new LinkedHashMap<TreePath, Boolean>();
        var staticContext = false;
        for (; path != null; path = path.getParentPath()) {
            var leaf = path.getLeaf();
            if (leaf instanceof MethodTree method && method.getModifiers().getFlags().contains(Modifier.STATIC)
                    || leaf instanceof VariableTree variable && variable.getModifiers().getFlags().contains(Modifier.STATIC)
                    || leaf instanceof BlockTree block && block.isStatic()) staticContext = true;
            if (leaf instanceof ClassTree type) {
                scopes.put(path, staticContext);
                if (type.getModifiers().getFlags().contains(Modifier.STATIC)) staticContext = true;
            }
        }
        return scopes;
    }

    private void addTopLevelSnippets(ParseTask task, CompletionList list) {
        var file = Paths.get(task.root().getSourceFile().toUri());
        if (!hasTypeDeclaration(task.root())) {
            var name = typeName(file);
            list.items.add(classSnippet(name));
            list.items.add(interfaceSnippet(name));
            list.items.add(recordSnippet(name));
            list.items.add(enumSnippet(name));
            if (task.root().getPackage() == null) {
                list.items.add(packageSnippet(FileStore.suggestedPackageName(file)));
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

    private static String typeName(Path file) {
        var name = file.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private String partialIdentifier(ParseTask task, TreePath path, String contents, int end) {
        if (path != null && path.getLeaf() instanceof IdentifierTree identifier) {
            var positions = Trees.instance(task.task()).getSourcePositions();
            var start = positions.getStartPosition(task.root(), identifier);
            var finish = positions.getEndPosition(task.root(), identifier);
            if (start >= 0 && start <= end && end <= finish) return contents.substring((int) start, end);
        }
        return partialIdentifier(contents, end);
    }

    /** Cursor prefix recovery when javac has no identifier node at the insertion point. */
    private String partialIdentifier(String contents, int end) {
        var start = end;
        while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private boolean endsWithParen(String contents, int cursor) {
        var next = cursor;
        while (next < contents.length() && Character.isJavaIdentifierPart(contents.charAt(next))) next++;
        while (next < contents.length() && Character.isWhitespace(contents.charAt(next))) next++;
        return next < contents.length() && contents.charAt(next) == '(';
    }

    private String qualifiedPartialIdentifier(ParseTask task, TreePath path, String contents, int end) {
        if (path != null && path.getLeaf() instanceof ImportTree imported) {
            var start = Trees.instance(task.task()).getSourcePositions()
                    .getStartPosition(task.root(), imported.getQualifiedIdentifier());
            if (start >= 0 && start <= end) return contents.substring((int) start, end).replaceAll("\\s+", "");
        }
        var start = end;
        while (start > 0 && isQualifiedIdentifierChar(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private boolean isQualifiedIdentifierChar(char c) {
        return c == '.' || Character.isJavaIdentifierPart(c);
    }

    private void addImportedTypeNames(
            Map<String, String> importedTypes, String partial, boolean addParentheses, Map<String, Integer> positions, CompletionList list) {
        for (var qualified : importedTypes.values()) {
            if (qualified.endsWith(".*")) continue;
            var simple = TypeNames.simpleName(qualified);
            if (!matchesCompletionPrefix(simple, partial)) continue;
            if (positions.putIfAbsent(simple, list.items.size()) != null) continue;
            list.items.add(CompletionItems.classItem(qualified, compilerId, addParentheses));
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

    /**
     * Enrich parse-only identifier completions with members that come from enclosing source types
     * and their supertypes. This keeps the fast parse/index path while restoring inherited and
     * outer-scope members that are not present in the local syntax tree alone.
     */
    private void addIndexedEnclosingTypeMembers(
            ParseTask parseTask, Map<TreePath, Boolean> scopes, String partial, CompletionList list,
            boolean endsWithParen, boolean addParentheses, boolean typesOnly, boolean includeMethods, Map<String, Integer> positions) {
        var methods = new TreeMap<String, List<IndexedMember>>();
        var methodPriority = new HashMap<String, Integer>();
        for (var entry : scopes.entrySet()) {
            var cursor = entry.getKey();
            var qualifiedType = qualifiedEnclosingClassName(parseTask.root(), cursor);
            if (qualifiedType != null) {
                addIndexedMembers(qualifiedType, partial, true, positions, methods, methodPriority, addParentheses, typesOnly, includeMethods, list);
                if (!entry.getValue()) {
                    addIndexedMembers(qualifiedType, partial, false, positions, methods, methodPriority, addParentheses, typesOnly, includeMethods, list);
                }
            }
        }
        for (var entry : methods.entrySet()) {
            var method = indexedMethod(entry.getValue(), !endsWithParen, null);
            method.sortText =
                    sortKey(
                            methodPriority.getOrDefault(entry.getKey(), Priority.INHERITED_METHOD), method.label);
            var previous = positions.putIfAbsent(method.label, list.items.size());
            if (previous == null) list.items.add(method);
            else if (list.items.get(previous).kind == CompletionItemKind.Method) list.items.set(previous, method);
        }
    }

    private CompletionList completeEnumCase(CompletionPrep prep, TreePath parsePath, String partial) {
        var file = Paths.get(prep.task().root().getSourceFile().toUri());
        var source = new SourceFileObject(file, terminateCompletionLine(prep.contents(), prep.cursor()), Instant.now());
        var list = new CompletionList();
        try (CompileTask task = compiler.compile(List.of(source), true)) {
            var root = task.root(source);
            var path = root == null ? null : new FindCompletionsAt(task.task).scan(root, prep.cursor());
            var selector = switchExpression(path);
            var selectorPath = selector == null ? null : task.trees.getPath(root, selector);
            var type = selectorPath == null ? null : task.trees.getTypeMirror(selectorPath);
            if (type instanceof DeclaredType declared && type.getKind() != TypeKind.ERROR
                    && declared.asElement() instanceof TypeElement owner && owner.getKind() == ElementKind.ENUM) {
                var enumType = owner.getQualifiedName().toString();
                var seen = new HashSet<String>();
                for (var member : typeIndexRouter.members(enumType, true)) {
                    if (!matchesCompletionPrefix(member.name, partial)) continue;
                    if (!isEnumCaseConstant(member, enumType)) continue;
                    if (!seen.add(member.name)) continue;
                    var item = indexedMember(member);
                    item.sortText = sortKey(Priority.FIELD, item.label);
                    list.items.add(item);
                }
                return list;
            }
        }
        addExpressionCandidates(prep, parsePath, partial, list, false);
        return list;
    }

    private Tree switchExpression(TreePath path) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            var leaf = cursor.getLeaf();
            if (leaf instanceof SwitchTree switchTree) {
                return switchTree.getExpression();
            }
            if (leaf instanceof SwitchExpressionTree switchExpressionTree) {
                return switchExpressionTree.getExpression();
            }
            if (leaf instanceof BlockTree) {
                return null;
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

    private void addStaticImportsFromIndex(
            List<MemberSelectTree> staticImports, String partial, boolean endsWithParen, boolean addParentheses, boolean typesOnly, boolean includeMethods, Map<String, Integer> positions, CompletionList list) {
        var methods = new TreeMap<String, List<IndexedMember>>();
        var methodPriority = new HashMap<String, Integer>();
        outer:
        for (var id : staticImports) {
            var ownerType = id.getExpression().toString();
            if (!importMatchesPartial(id.getIdentifier(), partial)) continue;
            for (var member : typeIndexRouter.members(ownerType, true)) {
                if (!includeMethods && (member.kind == CompletionItemKind.Method
                        || member.kind == CompletionItemKind.Constructor)) continue;
                if (typesOnly && member.kind != CompletionItemKind.Class
                        && member.kind != CompletionItemKind.Interface && member.kind != CompletionItemKind.Enum) continue;
                if (!memberMatchesImport(id.getIdentifier(), member.name)) continue;
                if (!matchesCompletionPrefix(member.name, partial)) continue;
                if (member.kind == CompletionItemKind.Method) {
                    methods.computeIfAbsent(member.name, __ -> new ArrayList<>()).add(member);
                    methodPriority.merge(member.name, indexMemberPriority(member), Math::min);
                } else if (positions.putIfAbsent(member.name, list.items.size()) == null) {
                    var item = indexedMember(member, addParentheses);
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
            if (positions.putIfAbsent(entry.getKey(), list.items.size()) != null) continue;
            var method = indexedMethod(entry.getValue(), !endsWithParen, null);
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

    private void addTypesAndPackages(
            CompilationUnitTree root, Map<String, String> importedTypes, TreeMap<String, Integer> insertionLines,
            String partial, boolean addParentheses, boolean annotationOnly, CompletionList list) {
        var uniques = new HashSet<String>();
        var names = new HashSet<String>();
        for (var item : list.items) {
            if (item == null) {
                continue;
            }
            names.add(item.label);
            if (!Objects.equals(item.kind, CompletionItemKind.Class)
                    && !Objects.equals(item.kind, CompletionItemKind.Interface)
                    && !Objects.equals(item.kind, CompletionItemKind.Enum)) {
                continue;
            }
            if (item.detail != null && !item.detail.isBlank()) {
                uniques.add(item.detail);
            }
        }
        for (var className : compiler.publicTopLevelTypes()) {
            var separator = className.indexOf('.');
            if (separator > 0 && (!annotationOnly || !partial.isEmpty())) {
                var packageName = className.substring(0, separator);
                if (packageName.startsWith(partial) && names.add(packageName)) {
                    list.items.add(packageItem(packageName));
                }
            }
            if (list.items.size() > MAX_COMPLETION_ITEMS) {
                list.isIncomplete = true;
                break;
            }
            if (!annotationOnly && (partial.isEmpty() || !Character.isUpperCase(partial.charAt(0)))) continue;
            if (!partial.isEmpty() && !StringSearch.matchesPartialName(TypeNames.simpleName(className), partial)) continue;
            if (annotationOnly && !typeIndexRouter.isAnnotationType(className)) continue;
            if (!uniques.add(className)) {
                continue;
            }
            if (list.items.size() > MAX_COMPLETION_ITEMS) {
                list.isIncomplete = true;
                break;
            }
            var item = CompletionItems.classItem(className, compilerId, addParentheses);
            var imported = importedTypes.get(TypeNames.simpleName(className));
            var priority = className.equals(imported) || samePackage(root, className) || className.startsWith("java.lang.")
                    ? Priority.IMPORTED_CLASS : Priority.NOT_IMPORTED_CLASS;
            item.sortText = sortKey(priority, item.label);
            if (imported != null && !imported.equals(className)) {
                qualify(item, className, addParentheses);
            } else if (!importCovered(root, className, insertionLines)) {
                var next = insertionLines.higherEntry(className);
                var point = new Position(next == null ? insertionLines.get("") : next.getValue(), 0);
                item.additionalTextEdits = List.of(AddImport.createTextEdits(className, point)[0]);
            }
            list.items.add(item);
        }
    }

    private static void collectImports(
            ParseTask task, Map<String, String> importedTypes, List<MemberSelectTree> staticImports,
            TreeMap<String, Integer> insertionLines) {
        var root = task.root();
        var sourcePositions = Trees.instance(task.task()).getSourcePositions();
        var lastLine = root.getPackage() == null ? -1
                : (int) root.getLineMap().getLineNumber(sourcePositions.getStartPosition(root, root.getPackage())) - 1;
        for (var tree : root.getImports()) {
            var name = tree.getQualifiedIdentifier().toString();
            var line = (int) root.getLineMap().getLineNumber(sourcePositions.getStartPosition(root, tree)) - 1;
            insertionLines.merge(name, line, Math::min);
            lastLine = line;
            if (tree.isStatic()) {
                if (tree.getQualifiedIdentifier() instanceof MemberSelectTree member) staticImports.add(member);
            } else {
                importedTypes.putIfAbsent(name.endsWith(".*") ? name : TypeNames.simpleName(name), name);
            }
        }
        // The earliest greater import preserves AddImport's behavior even for unsorted imports.
        var earliest = Integer.MAX_VALUE;
        for (var entry : insertionLines.descendingMap().entrySet()) {
            earliest = Math.min(earliest, entry.getValue());
            entry.setValue(earliest);
        }
        insertionLines.put("", lastLine + 1);
    }

    private static boolean samePackage(CompilationUnitTree root, String name) {
        var prefix = root.getPackageName() == null ? "" : root.getPackageName() + ".";
        return name.startsWith(prefix) && name.indexOf('.', prefix.length()) < 0;
    }

    private static boolean importCovered(
            CompilationUnitTree root, String name, TreeMap<String, Integer> insertionLines) {
        if (samePackage(root, name) || name.startsWith("java.lang.") || insertionLines.containsKey(name)) return true;
        var separator = name.lastIndexOf('.');
        return separator >= 0 && insertionLines.containsKey(name.substring(0, separator) + ".*");
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

    private void addIndexedMembers(
            String qualifiedType,
            String partial,
            boolean staticContext,
            Map<String, Integer> positions,
            Map<String, List<IndexedMember>> methods,
            Map<String, Integer> methodPriority,
            boolean addParentheses, boolean typesOnly, boolean includeMethods, CompletionList list) {
        for (var member : typeIndexRouter.members(qualifiedType, staticContext)) {
            if (!includeMethods && (member.kind == CompletionItemKind.Method
                    || member.kind == CompletionItemKind.Constructor)) continue;
            if (!matchesCompletionPrefix(member.name, partial)) continue;
            if (typesOnly && !isTypeKind(member.kind)) continue;
            if (member.kind == CompletionItemKind.Method) {
                methods.computeIfAbsent(member.name, __ -> new ArrayList<>()).add(member);
                methodPriority.merge(member.name, indexMemberPriority(member), Math::min);
            } else if (positions.putIfAbsent(member.name, list.items.size()) == null) {
                var item = indexedMember(member, addParentheses);
                item.sortText = sortKey(indexMemberPriority(member), item.label);
                list.items.add(item);
            }
        }
    }

    private static boolean isTypeKind(int kind) {
        return kind == CompletionItemKind.Class || kind == CompletionItemKind.Interface
                || kind == CompletionItemKind.Enum || kind == CompletionItemKind.Struct;
    }

    private boolean sameQualifiedType(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right) || left.replace('$', '.').equals(right.replace('$', '.'));
    }

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

    private static boolean matchesCompletionPrefix(CharSequence candidate, String partial) {
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

    private static void addKeywords(TreePath path, String partial, CompletionList list) {
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

    private static void addSyntacticLocalVariables(ParseTask task, long cursor, String partial,
            Map<String, Integer> positionsByName, CompletionList list) {
        var trees = Trees.instance(task.task());
        var positions = trees.getSourcePositions();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) return null;
                if (tree instanceof BlockTree || tree instanceof ClassTree || tree instanceof MethodTree
                        || tree.getKind() == Tree.Kind.LAMBDA_EXPRESSION
                        || tree.getKind() == Tree.Kind.FOR_LOOP || tree.getKind() == Tree.Kind.ENHANCED_FOR_LOOP
                        || tree.getKind() == Tree.Kind.CATCH) {
                    var start = positions.getStartPosition(task.root(), tree);
                    var end = positions.getEndPosition(task.root(), tree);
                    if (start > cursor || end >= 0 && end < cursor) return null;
                }
                return super.scan(tree, unused);
            }

            @Override
            public Void visitVariable(VariableTree t, Void __) {
                var parent = getCurrentPath().getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree) {
                    return super.visitVariable(t, null);
                }
                var start = positions.getStartPosition(task.root(), t);
                if (start < 0 || start >= cursor) return super.visitVariable(t, null);
                var end = positions.getEndPosition(task.root(), t);
                if (end >= cursor) return super.visitVariable(t, null);
                var name = t.getName().toString();
                if (name.isEmpty()) return super.visitVariable(t, null);
                if (!StringSearch.matchesPartialName(name, partial)) return super.visitVariable(t, null);
                if (positionsByName.putIfAbsent(name, list.items.size()) == null) {
                     list.items.add(variable(name, syntacticType(t)));
                }
                return super.visitVariable(t, null);
            }
        }.scan(task.root(), null);
    }

    private static String syntacticType(VariableTree t) {
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

    private static void addSyntacticEnclosingTypeMembers(
            ParseTask parseTask, Map<TreePath, Boolean> scopes, String partial, boolean endsWithParen,
            boolean addParentheses, boolean typesOnly, boolean includeMethods, Map<String, Integer> positions, CompletionList list) {
        if (scopes.isEmpty()) return;
        var classPath = scopes.keySet().iterator().next();
        var classTree = (ClassTree) classPath.getLeaf();
        var staticContext = scopes.get(classPath);
        var enclosingOwnerType = qualifiedEnclosingClassName(parseTask.root(), classPath);
        for (var member : classTree.getMembers()) {
            switch (member.getKind()) {
                case VARIABLE:
                    if (typesOnly) continue;
                    var field = (VariableTree) member;
                    if (!isSyntacticStaticField(classTree, field, staticContext)) {
                        continue;
                    }
                    addSyntacticField(field, partial, positions, list);
                    break;
                case METHOD:
                    if (typesOnly || !includeMethods) continue;
                    var method = (MethodTree) member;
                    if (!isSyntacticStaticMethod(method, staticContext)) {
                        continue;
                    }
                    addSyntacticMethod(method, partial, endsWithParen, positions, list);
                    break;
                case CLASS:
                case INTERFACE:
                case ENUM:
                case ANNOTATION_TYPE:
                    addSyntacticNestedType(member, partial, enclosingOwnerType, addParentheses, positions, list);
                    break;
                default:
                    break;
            }
        }
    }

    private static boolean isSyntacticStaticField(ClassTree owner, VariableTree field, boolean staticContext) {
        if (!staticContext) {
            return true;
        }
        return owner.getKind() == Tree.Kind.INTERFACE || field.getModifiers().getFlags().contains(Modifier.STATIC);
    }

    private static boolean isSyntacticStaticMethod(MethodTree method, boolean staticContext) {
        if (method.getName().contentEquals("<init>")) {
            return false;
        }
        if (!staticContext) {
            return true;
        }
        return method.getModifiers().getFlags().contains(Modifier.STATIC);
    }

    private static void addSyntacticField(VariableTree field, String partial, Map<String, Integer> positions, CompletionList list) {
        var name = field.getName().toString();
        if (!matchesCompletionPrefix(name, partial)) {
            return;
        }
        if (positions.putIfAbsent(name, list.items.size()) != null) {
            return;
        }
        list.items.add(field(name, syntacticType(field)));
    }

    private static void addSyntacticMethod(MethodTree method, String partial, boolean endsWithParen,
            Map<String, Integer> positions, CompletionList list) {
        var name = method.getName().toString();
        if (!matchesCompletionPrefix(name, partial)) {
            return;
        }
        if (positions.putIfAbsent(name, list.items.size()) != null) {
            return;
        }
        list.items.add(syntacticMethod(name, method, endsWithParen));
    }

    private static void addSyntacticNestedType(Tree member, String partial, String ownerType, boolean addParentheses,
            Map<String, Integer> positions, CompletionList list) {
        var nested = (ClassTree) member;
        var name = nested.getSimpleName().toString();
        if (name.isEmpty() || !matchesCompletionPrefix(name, partial)) {
            return;
        }
        if (positions.putIfAbsent(name, list.items.size()) != null) {
            return;
        }
        list.items.add(CompletionItems.syntacticType(name, nested.getKind(), ownerType, addParentheses));
    }

    private static Tree findKeywordLevel(TreePath path) {
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

    private static String qualifiedEnclosingClassName(CompilationUnitTree root, TreePath classPath) {
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

    private CompletionList completePackage(String path, boolean addParentheses, boolean annotationOnly) {
        var names = new HashSet<String>();
        var list = new CompletionList();
        for (var className : compiler.publicTopLevelTypes()) {
            if (className.startsWith(path)) {
                var start = path.lastIndexOf('.');
                var end = className.indexOf('.', path.length());
                if (end == -1) end = className.length();
                var segment = className.substring(start + 1, end);
                if (names.contains(segment)) continue;
                var isClass = end == className.length();
                if (isClass && annotationOnly && !typeIndexRouter.isAnnotationType(className)) continue;
                names.add(segment);
                if (isClass) {
                    var item = CompletionItems.classItem(className, compilerId, addParentheses);
                    // Only the segment after the last dot is replaced by completion.
                    qualify(item, segment, addParentheses);
                    list.items.add(item);
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

    private AnnotationContext findAnnotationContext(CompletionPrep prep, TreePath path, String partial) {
        if (path != null && path.getLeaf() instanceof com.sun.source.tree.LiteralTree) return null;
        var task = prep.task();
        var cursor = prep.cursor();
        var source = prep.contents();
        var positions = Trees.instance(task.task()).getSourcePositions();
        var root = task.root();
        AnnotationTree annotation = null;
        for (var enclosing = path; enclosing != null; enclosing = enclosing.getParentPath()) {
            if (enclosing.getLeaf() instanceof AnnotationTree node
                    && positions.getEndPosition(root, node.getAnnotationType()) < cursor
                    && cursor <= positions.getEndPosition(root, node)
                    && !(cursor == positions.getEndPosition(root, node) && source.charAt((int) cursor - 1) == ')')) {
                annotation = node;
                break;
            }
        }
        if (annotation == null) return null;
        // A named attribute's right-hand side accepts values, not more attribute names.
        var attributePosition = true;
        var attributeName = "value";
        var beforePartial = (int) cursor - partial.length();
        for (int i = beforePartial - 1; i >= 0; i--) {
            var c = source.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == '=') attributePosition = false;
            break;
        }
        for (var argument : annotation.getArguments()) {
            if (!(argument instanceof AssignmentTree assignment)) continue;
            var valueStart = positions.getStartPosition(root, assignment.getExpression());
            var argumentEnd = positions.getEndPosition(root, argument);
            if (valueStart >= 0 && valueStart <= cursor && (argumentEnd < 0 || cursor <= argumentEnd)) {
                attributePosition = false;
                attributeName = assignment.getVariable().toString();
                break;
            }
            if (!attributePosition) attributeName = assignment.getVariable().toString();
        }
        var typeName = annotation.getAnnotationType().toString();
        var qualified = typeIndexRouter.resolveTypeName(typeName, root);
        return new AnnotationContext(qualified.orElse(""), partial, attributePosition, attributeName);
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
            list.add(annotationAttribute(member));
        }
        return new CompletionList(false, list);
    }

    private MemberAccessContext memberAccessContext(TreePath path, String partial, String contents, int cursor) {
        if (path != null) {
            if (path.getLeaf() instanceof MemberSelectTree) return new MemberAccessContext(partial, false);
            if (path.getLeaf() instanceof MemberReferenceTree) return new MemberAccessContext(partial, true);
        }
        return recoverMemberAccess(contents, cursor);
    }

    /** Incomplete selections can be represented only as erroneous trees. */
    private MemberAccessContext recoverMemberAccess(String contents, int cursor) {
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
        if (contents.charAt(separatorEnd) == '.') {
            methodReference = false;
        } else if (separatorEnd > 0
                && contents.charAt(separatorEnd) == ':'
                && contents.charAt(separatorEnd - 1) == ':') {
            methodReference = true;
        } else {
            return null;
        }
        var partial = contents.substring(partialStart, effectiveCursor);
        return new MemberAccessContext(partial, methodReference);
    }
}
