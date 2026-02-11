package org.javacs.inlay;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.ParseTask;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

public class InlayHintProvider {
    private final CompilerProvider compiler;
    private final Duration debounceWindow;
    private final Duration cacheIdleTtl;
    private final int cacheMaxEntries;

    public InlayHintProvider(CompilerProvider compiler) {
        this(compiler, DEFAULT_DEBOUNCE_WINDOW, DEFAULT_CACHE_IDLE_TTL, DEFAULT_CACHE_MAX_ENTRIES);
    }

    public InlayHintProvider(
            CompilerProvider compiler, Duration debounceWindow, Duration cacheIdleTtl, int cacheMaxEntries) {
        this.compiler = compiler;
        this.debounceWindow = debounceWindow == null ? DEFAULT_DEBOUNCE_WINDOW : debounceWindow;
        this.cacheIdleTtl = cacheIdleTtl == null ? DEFAULT_CACHE_IDLE_TTL : cacheIdleTtl;
        this.cacheMaxEntries = Math.max(1, cacheMaxEntries);
    }

    public List<InlayHint> inlayHints(Path file, Range range, boolean parameterNames) {
        var now = Instant.now();
        pruneCache(now);
        var documentVersion = FileStore.activeVersion(file).orElse(-1);
        var cached = hintCache.get(file);
        if (cached != null) {
            if (!cached.matches(range, parameterNames)) {
                LOG.fine(
                        () ->
                                "Inlay cache bypass (scope/settings changed), file="
                                        + file.getFileName()
                                        + ", cachedVersion="
                                        + cached.version
                                        + ", currentVersion="
                                        + documentVersion);
            } else if (documentVersion == cached.version) {
                LOG.fine(
                        () ->
                                "Inlay cache hit file="
                                        + file.getFileName()
                                        + " version="
                                        + documentVersion
                                        + " hints="
                                        + cached.hints.size());
                return cached.hints;
            } else if (documentVersion > cached.version
                    && Duration.between(cached.createdAt, now).compareTo(debounceWindow) <= 0) {
                LOG.fine(
                        () ->
                                "Inlay debounce hit file="
                                        + file.getFileName()
                                        + " currentVersion="
                                        + documentVersion
                                        + " cachedVersion="
                                        + cached.version
                                        + " ageMs="
                                        + Duration.between(cached.createdAt, now).toMillis()
                                        + " hints="
                                        + cached.hints.size());
                return cached.hints;
            } else if (documentVersion >= 0 && documentVersion < cached.version) {
                hintCache.remove(file);
                LOG.fine(
                        () ->
                                "Inlay cache invalidated (version rollback), file="
                                        + file.getFileName()
                                        + ", currentVersion="
                                        + documentVersion
                                        + ", cachedVersion="
                                        + cached.version);
            }
        }
        try (var task = compiler.compile(file)) {
            var root = task.root();
            var lineMap = root.getLineMap();
            var trees = Trees.instance(task.task);
            var positions = trees.getSourcePositions();
            var scannedHints = new ArrayList<InlayHint>();
            var parameterNameCache = new java.util.HashMap<String, String>();

            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
                    if (parameterNames) {
                        addInvocationHints(tree.getArguments(), trees.getElement(getCurrentPath()));
                    }
                    return super.visitMethodInvocation(tree, p);
                }

                @Override
                public Void visitNewClass(NewClassTree tree, Void p) {
                    if (parameterNames) {
                        addInvocationHints(tree.getArguments(), trees.getElement(getCurrentPath()));
                    }
                    return super.visitNewClass(tree, p);
                }

                @Override
                public Void visitVariable(VariableTree tree, Void p) {
                    if (isVarDeclaration(tree, positions, root)) {
                        addVarTypeHint(tree);
                    }
                    return super.visitVariable(tree, p);
                }

                private void addInvocationHints(List<? extends ExpressionTree> args, Element element) {
                    if (!(element instanceof ExecutableElement)) return;
                    var method = (ExecutableElement) element;
                    var params = method.getParameters();
                    var count = Math.min(params.size(), args.size());
                    for (int i = 0; i < count; i++) {
                        var arg = args.get(i);
                        var pos = positions.getStartPosition(root, arg);
                        if (pos < 0) continue;
                        var line = (int) lineMap.getLineNumber(pos) - 1;
                        var col = (int) lineMap.getColumnNumber(pos) - 1;
                        var hintPos = new Position(line, col);
                        if (!withinRange(range, hintPos)) continue;
                        var name = parameterName(task, method, i, parameterNameCache);
                        if (name.isEmpty()) continue;
                        var hint = new InlayHint(hintPos, name + ": ", 2);
                        scannedHints.add(hint);
                    }
                }

                private void addVarTypeHint(VariableTree tree) {
                    var type = trees.getTypeMirror(getCurrentPath());
                    if (type == null && tree.getInitializer() != null) {
                        type = trees.getTypeMirror(new com.sun.source.util.TreePath(getCurrentPath(), tree.getInitializer()));
                    }
                    if (type == null) return;
                    var typeName = simplifyTypeName(type.toString());
                    var namePos = nameEndPosition(
                            tree, positions, lineMap, getCurrentPath().getCompilationUnit());
                    Position hintPos = namePos;
                    if (hintPos == null) {
                        var init = tree.getInitializer();
                        long initPos = init != null ? positions.getStartPosition(root, init) : -1;
                        if (initPos < 0) {
                            initPos = positions.getStartPosition(root, tree);
                        }
                        if (initPos >= 0) {
                            var line = (int) lineMap.getLineNumber(initPos) - 1;
                            var col = (int) lineMap.getColumnNumber(initPos) - 1;
                            hintPos = new Position(line, col);
                        }
                    }
                    if (hintPos == null) return;
                    if (!withinRange(range, hintPos)) return;
                    var hint = new InlayHint(hintPos, ": " + typeName, 1);
                    scannedHints.add(hint);
                    LOG.fine("Inlay var hint: " + tree.getName() + " -> " + typeName);
                }
            }.scan(root, null);

            var hints = List.copyOf(scannedHints);
            if (documentVersion >= 0) {
                hintCache.put(
                        file,
                        new HintSnapshot(
                                hints,
                                Instant.now(),
                                documentVersion,
                                range,
                                parameterNames));
                LOG.fine(
                        () ->
                                "Inlay cache store file="
                                        + file.getFileName()
                                        + " version="
                                        + documentVersion
                                        + " hints="
                                        + hints.size());
            }
            LOG.fine("Inlay hints: " + hints.size() + " items for " + file.getFileName());
            return hints;
        }
    }

    private boolean isVarDeclaration(
            VariableTree tree, SourcePositions positions, com.sun.source.tree.CompilationUnitTree root) {
        var type = tree.getType();
        if (type != null) {
            if ("var".equals(type.toString())) return true;
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                return "var".contentEquals(id.getName());
            }
        }
        // Fallback: inspect source between declaration start and variable name
        long start = positions.getStartPosition(root, tree);
        long end = positions.getEndPosition(root, tree);
        if (start < 0 || end < 0) return false;
        String name = tree.getName().toString();
        if (name.isEmpty()) return false;
        int namePos = FindHelper.findNameIn(root, name, (int) start, (int) end);
        if (namePos < 0) return false;
        try {
            var contents = root.getSourceFile().getCharContent(true);
            int scanStart = (int) start;
            int scanEnd = Math.min(namePos, contents.length());
            var prefix = contents.subSequence(scanStart, scanEnd).toString();
            return prefix.contains("var");
        } catch (Exception e) {
            return false;
        }
    }

    private String parameterName(org.javacs.CompileTask task, ExecutableElement method, int index) {
        return parameterName(task, method, index, null);
    }

    private String parameterName(
            org.javacs.CompileTask task,
            ExecutableElement method,
            int index,
            java.util.Map<String, String> parameterNameCache) {
        var params = method.getParameters();
        if (index >= params.size()) return "";
        if (parameterNameCache != null) {
            var key = parameterNameCacheKey(task, method, index);
            if (parameterNameCache.containsKey(key)) {
                return parameterNameCache.get(key);
            }
            var resolved = resolveParameterName(task, method, index);
            parameterNameCache.put(key, resolved);
            return resolved;
        }
        return resolveParameterName(task, method, index);
    }

    private String resolveParameterName(org.javacs.CompileTask task, ExecutableElement method, int index) {
        var params = method.getParameters();
        if (index >= params.size()) return "";
        var name = params.get(index).getSimpleName().toString();
        if (!looksSynthetic(name)) return name;

        var resolved = resolveParameterNameFromSource(task, method, index);
        if (resolved != null && !resolved.equals(name)) {
            LOG.fine("Resolved parameter name " + name + " -> " + resolved + " for " + method.getSimpleName());
        }
        return resolved != null ? resolved : "";
    }

    private String parameterNameCacheKey(org.javacs.CompileTask task, ExecutableElement method, int index) {
        var owner = method.getEnclosingElement().toString();
        var methodName = method.getSimpleName().toString();
        var erased = FindHelper.erasedParameterTypes(task, method);
        return owner + "#" + methodName + "(" + String.join(",", erased) + ")@" + index;
    }

    private boolean looksSynthetic(String name) {
        if (name == null) return false;
        if (name.startsWith("arg") && name.length() > 3) {
            for (int i = 3; i < name.length(); i++) {
                if (!Character.isDigit(name.charAt(i))) return false;
            }
            return true;
        }
        return false;
    }

    private String resolveParameterNameFromSource(org.javacs.CompileTask task, ExecutableElement method, int index) {
        var enclosing = method.getEnclosingElement();
        if (!(enclosing instanceof TypeElement)) return null;
        var type = (TypeElement) enclosing;
        var className = type.getQualifiedName().toString();
        var source = compiler.findAnywhere(className);
        if (source.isEmpty()) return null;
        ParseTask parse;
        try {
            parse = compiler.parse(source.get());
        } catch (Exception e) {
            return null;
        }
        var erased = FindHelper.erasedParameterTypes(task, method);
        com.sun.source.tree.MethodTree tree;
        try {
            tree = FindHelper.findMethod(parse, className, method.getSimpleName().toString(), erased);
        } catch (Exception e) {
            return null;
        }
        if (tree == null) return null;
        if (index >= tree.getParameters().size()) return null;
        return tree.getParameters().get(index).getName().toString();
    }

    private String simplifyTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) return typeName;
        StringBuilder out = new StringBuilder(typeName.length());
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < typeName.length(); i++) {
            char c = typeName.charAt(i);
            if (isTypeChar(c)) {
                token.append(c);
            } else {
                appendSimplifiedToken(out, token);
                out.append(c);
            }
        }
        appendSimplifiedToken(out, token);
        return out.toString();
    }

    private boolean isTypeChar(char c) {
        return Character.isJavaIdentifierPart(c) || c == '.';
    }

    private void appendSimplifiedToken(StringBuilder out, StringBuilder token) {
        if (token.length() == 0) return;
        String t = token.toString();
        int lastDot = t.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < t.length() - 1) {
            out.append(t.substring(lastDot + 1));
        } else {
            out.append(t);
        }
        token.setLength(0);
    }

    private Position nameEndPosition(
            VariableTree tree,
            SourcePositions positions,
            com.sun.source.tree.LineMap lineMap,
            com.sun.source.tree.CompilationUnitTree root) {
        var start = positions.getStartPosition(root, tree);
        var end = positions.getEndPosition(root, tree);
        if (start < 0 || end < 0) return null;
        var name = tree.getName().toString();
        if (name.isEmpty()) return null;
        var pos = FindHelper.findNameIn(root, name, (int) start, (int) end);
        if (pos < 0) return null;
        var nameEnd = pos + name.length();
        var line = (int) lineMap.getLineNumber(nameEnd) - 1;
        var col = (int) lineMap.getColumnNumber(nameEnd) - 1;
        return new Position(line, col);
    }

    private boolean withinRange(Range range, Position pos) {
        if (range == null) return true;
        if (range == Range.NONE) return true;
        var start = range.start;
        var end = range.end;
        if (start == null || end == null) return true;
        if (pos.line < start.line || pos.line > end.line) return false;
        if (pos.line == start.line && pos.character < start.character) return false;
        if (pos.line == end.line && pos.character > end.character) return false;
        return true;
    }

    private static final Logger LOG = Logger.getLogger("main");
    private static final Duration DEFAULT_DEBOUNCE_WINDOW = Duration.ofMillis(250);
    private static final Duration DEFAULT_CACHE_IDLE_TTL = Duration.ofMinutes(2);
    private static final int DEFAULT_CACHE_MAX_ENTRIES = 256;
    private static final Map<Path, HintSnapshot> hintCache = new ConcurrentHashMap<>();

    public static void invalidate(Path file) {
        if (file == null) return;
        var removed = hintCache.remove(file);
        if (removed != null) {
            LOG.fine(() -> "Inlay cache invalidated file=" + file.getFileName());
        }
    }

    private void pruneCache(Instant now) {
        int removedByIdle = 0;
        for (var it = hintCache.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (Duration.between(entry.getValue().createdAt, now).compareTo(cacheIdleTtl) > 0) {
                it.remove();
                removedByIdle++;
            }
        }
        if (removedByIdle > 0) {
            final int removed = removedByIdle;
            LOG.fine(() -> "Inlay cache prune idle removed=" + removed + " size=" + hintCache.size());
        }

        if (hintCache.size() <= cacheMaxEntries) return;
        var entries = new ArrayList<>(hintCache.entrySet());
        entries.sort(java.util.Comparator.comparing(e -> e.getValue().createdAt));
        int toRemove = hintCache.size() - cacheMaxEntries;
        int removed = 0;
        for (var entry : entries) {
            if (removed >= toRemove) break;
            if (hintCache.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            final int removedCount = removed;
            LOG.fine(
                    () ->
                            "Inlay cache prune size removed="
                                    + removedCount
                                    + " max="
                                    + cacheMaxEntries
                                    + " size="
                                    + hintCache.size());
        }
    }

    private static final class HintSnapshot {
        private final List<InlayHint> hints;
        private final Instant createdAt;
        private final int version;
        private final int startLine;
        private final int startCharacter;
        private final int endLine;
        private final int endCharacter;
        private final boolean parameterNames;

        private HintSnapshot(
                List<InlayHint> hints,
                Instant createdAt,
                int version,
                Range range,
                boolean parameterNames) {
            this.hints = List.copyOf(hints);
            this.createdAt = createdAt;
            this.version = version;
            this.parameterNames = parameterNames;
            if (range == null || range.start == null || range.end == null) {
                this.startLine = Integer.MIN_VALUE;
                this.startCharacter = Integer.MIN_VALUE;
                this.endLine = Integer.MAX_VALUE;
                this.endCharacter = Integer.MAX_VALUE;
            } else {
                this.startLine = range.start.line;
                this.startCharacter = range.start.character;
                this.endLine = range.end.line;
                this.endCharacter = range.end.character;
            }
        }

        private boolean matches(Range range, boolean parameterNames) {
            if (this.parameterNames != parameterNames) return false;
            if (range == null || range.start == null || range.end == null) {
                return this.startLine == Integer.MIN_VALUE
                        && this.startCharacter == Integer.MIN_VALUE
                        && this.endLine == Integer.MAX_VALUE
                        && this.endCharacter == Integer.MAX_VALUE;
            }
            return this.startLine == range.start.line
                    && this.startCharacter == range.start.character
                    && this.endLine == range.end.line
                    && this.endCharacter == range.end.character;
        }
    }
}
