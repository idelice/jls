package org.javacs.markup;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.FileStore;
import org.javacs.lsp.*;

public class ErrorProvider {
    final CompileTask task;
    private static final Logger LOG = Logger.getLogger("main");
    private static final Set<String> SYNTAX_BLOCKING_CODES =
            Set.of(
                    "compiler.err.expected",
                    "compiler.err.expected2",
                    "compiler.err.not.stmt",
                    "compiler.err.illegal.start.of.expr",
                    "compiler.err.illegal.start.of.stmt");

    private record DiagnosticFilterResult(
            List<Diagnostic> compilerDiagnostics, boolean syntaxSuppressed, int droppedCount) {}

    public record ErrorReport(
            List<PublishDiagnosticsParams> diagnostics,
            int compiledRoots,
            int requestedRoots,
            int processedRoots,
            int compilerDiagnosticsCount,
            int warningDiagnosticsCount,
            long convertMs,
            long warningMs) {}

    public ErrorProvider(CompileTask task) {
        this.task = task;
    }

    public ErrorReport errors(Set<URI> requestedUris) {
        var requested = requestedUris == null ? Set.<URI>of() : new LinkedHashSet<>(requestedUris);
        var result = new ArrayList<PublishDiagnosticsParams>();
        long convertNanos = 0;
        long warningNanos = 0;
        var processedRoots = 0;
        var compilerDiagnosticsCount = 0;
        var warningDiagnosticsCount = 0;
        for (var root : task.roots) {
            var uri = root.getSourceFile().toUri();
            if (!requested.isEmpty() && !requested.contains(uri)) {
                continue;
            }
            var params = new PublishDiagnosticsParams();
            params.uri = uri;
            result.add(params);
            // Skip diagnostics for JAR-based files (they are not user code)
            if (isJarOrCachedSource(uri)) {
                LOG.fine("Skipping diagnostics for JAR source: " + uri);
                continue;
            }
            processedRoots++;
            var convertStarted = System.nanoTime();
            var filtered = filterCompilerDiagnostics(compilerErrors(root), root);
            convertNanos += System.nanoTime() - convertStarted;
            params.diagnostics.addAll(filtered.compilerDiagnostics());
            compilerDiagnosticsCount += filtered.compilerDiagnostics().size();
            if (!filtered.syntaxSuppressed()) {
                var warningStarted = System.nanoTime();
                var unused = unusedWarnings(root);
                var notThrown = notThrownWarnings(root);
                warningNanos += System.nanoTime() - warningStarted;
                params.diagnostics.addAll(unused);
                params.diagnostics.addAll(notThrown);
                warningDiagnosticsCount += unused.size() + notThrown.size();
            }
        }
        return new ErrorReport(
                List.copyOf(result),
                task.roots.size(),
                requested.size(),
                processedRoots,
                compilerDiagnosticsCount,
                warningDiagnosticsCount,
                convertNanos / 1_000_000,
                warningNanos / 1_000_000);
    }

    private boolean isJarOrCachedSource(URI uri) {
        // Check if it's a jar: URI
        if ("jar".equals(uri.getScheme())) {
            return true;
        }
        // Check if it's in the jls-jar-sources cache directory
        String path = uri.getPath();
        return path != null && path.contains("jls-jar-sources");
    }

    private List<Diagnostic> compilerErrors(CompilationUnitTree root) {
        var result = new ArrayList<Diagnostic>();

        // Create a copy to avoid ConcurrentModificationException during cache compilation
        var diagnosticsCopy = new ArrayList<>(task.diagnostics);

        for (var d : diagnosticsCopy) {
            if (d.getSource() == null || !d.getSource().toUri().equals(root.getSourceFile().toUri())) continue;
            if (d.getStartPosition() == -1 || d.getEndPosition() == -1) continue;
            if ("compiler.warn.proc.messager".equals(d.getCode())) continue;

            result.add(lspDiagnostic(d, root.getLineMap()));
        }
        return result;
    }

    private DiagnosticFilterResult filterCompilerDiagnostics(
            List<Diagnostic> compilerDiagnostics, CompilationUnitTree root) {
        var deduped = dedupeDiagnostics(compilerDiagnostics);
        var firstSyntaxLine = firstSyntaxBlockingLine(deduped);
        if (firstSyntaxLine == -1) {
            return new DiagnosticFilterResult(deduped, false, compilerDiagnostics.size() - deduped.size());
        }

        Diagnostic primarySyntaxDiagnostic = null;
        for (var diagnostic : deduped) {
            if (diagnostic.severity == null || diagnostic.severity != DiagnosticSeverity.Error) {
                continue;
            }
            if (diagnostic.range.start.line == firstSyntaxLine && isSyntaxBlockingDiagnostic(diagnostic)) {
                primarySyntaxDiagnostic = diagnostic;
                break;
            }
        }
        var filtered = new ArrayList<Diagnostic>();
        if (primarySyntaxDiagnostic != null) {
            filtered.add(primarySyntaxDiagnostic);
        }
        return new DiagnosticFilterResult(filtered, true, compilerDiagnostics.size() - filtered.size());
    }

    private List<Diagnostic> dedupeDiagnostics(List<Diagnostic> diagnostics) {
        var unique = new LinkedHashMap<String, Diagnostic>();
        for (var diagnostic : diagnostics) {
            unique.putIfAbsent(diagnosticKey(diagnostic), diagnostic);
        }
        return new ArrayList<>(unique.values());
    }

    private int firstSyntaxBlockingLine(List<Diagnostic> diagnostics) {
        var firstLine = Integer.MAX_VALUE;
        for (var diagnostic : diagnostics) {
            if (!isSyntaxBlockingDiagnostic(diagnostic)) {
                continue;
            }
            firstLine = Math.min(firstLine, diagnostic.range.start.line);
        }
        return firstLine == Integer.MAX_VALUE ? -1 : firstLine;
    }

    private boolean isSyntaxBlockingDiagnostic(Diagnostic diagnostic) {
        return diagnostic.code != null && SYNTAX_BLOCKING_CODES.contains(diagnostic.code);
    }

    private String diagnosticKey(Diagnostic diagnostic) {
        return diagnostic.code
                + "|"
                + diagnostic.message
                + "|"
                + diagnostic.range.start.line
                + ":"
                + diagnostic.range.start.character
                + "|"
                + diagnostic.range.end.line
                + ":"
                + diagnostic.range.end.character;
    }

    private List<Diagnostic> unusedWarnings(CompilationUnitTree root) {
        var result = new ArrayList<Diagnostic>();
        var warnUnused = new WarnUnused(task.task);
        warnUnused.scan(root, null);
        for (var unusedEl : warnUnused.notUsed()) {
            result.add(warnUnused(unusedEl));
        }

        result.addAll(unusedImportWarnings(root));
        return result;
    }

    private List<Diagnostic> unusedImportWarnings(CompilationUnitTree root) {
        var result = new ArrayList<Diagnostic>();
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var importTrees = new ArrayList<ImportTree>();
        var importNames = new ArrayList<String>();
        for (var imp : root.getImports()) {
            if (imp.isStatic()) continue;
            var qualName = imp.getQualifiedIdentifier().toString();
            if (qualName.endsWith(".*")) continue;
            importTrees.add(imp);
            importNames.add(qualName);
        }
        if (importTrees.isEmpty()) return result;
        var usedImports = new HashSet<String>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree t, Void __) {
                var el = trees.getElement(getCurrentPath());
                if (el instanceof TypeElement te) {
                    usedImports.add(te.getQualifiedName().toString());
                }
                return super.visitIdentifier(t, null);
            }
        }.scan(root, null);
        for (int i = 0; i < importTrees.size(); i++) {
            if (!usedImports.contains(importNames.get(i))) {
                var imp = importTrees.get(i);
                var start = (int) pos.getStartPosition(root, imp);
                var end = (int) pos.getEndPosition(root, imp);
                var simpleName = importNames.get(i);
                var dot = simpleName.lastIndexOf('.');
                if (dot != -1) simpleName = simpleName.substring(dot + 1);
                var d = new Diagnostic();
                d.message = String.format("'%s' is not used", simpleName);
                d.range = RangeHelper.range(root, start, end);
                d.code = "unused_import";
                d.severity = DiagnosticSeverity.Information;
                d.tags = List.of(DiagnosticTag.Unnecessary);
                result.add(d);
            }
        }
        return result;
    }

    private List<Diagnostic> notThrownWarnings(CompilationUnitTree root) {
        var result = new ArrayList<Diagnostic>();
        var notThrown = new HashMap<TreePath, String>();
        new WarnNotThrown(task.task).scan(root, notThrown);
        for (var location : notThrown.keySet()) {
            result.add(warnNotThrown(notThrown.get(location), location));
        }
        return result;
    }

    /**
     * lspDiagnostic(d, lines) converts d to LSP format, with its position shifted appropriately for the latest version
     * of the file.
     */
    private Diagnostic lspDiagnostic(javax.tools.Diagnostic<? extends JavaFileObject> d, LineMap lines) {
        var start = d.getStartPosition();
        var end = d.getEndPosition();
        var severity = severity(d.getKind());
        var code = d.getCode();
        var message = simplifyMessage(d.getMessage(null));
        var result = new Diagnostic();
        result.severity = severity;
        result.code = code;
        result.message = message;
        try {
            result.range = FileStore.range(d.getSource().getCharContent(true).toString(), start, end);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private int severity(javax.tools.Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return DiagnosticSeverity.Error;
            case WARNING:
            case MANDATORY_WARNING:
                return DiagnosticSeverity.Warning;
            case NOTE:
                return DiagnosticSeverity.Information;
            case OTHER:
            default:
                return DiagnosticSeverity.Hint;
        }
    }

    private static final Pattern QUALIFIED_NAME = Pattern.compile("\\b([a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+)\\.([A-Z][\\w]*)");
    private static final Pattern CANT_RESOLVE = Pattern.compile("(?s)cannot find symbol\\s+symbol:\\s+(?:variable|method|class)\\s+(\\S+).*");

    private static String simplifyMessage(String message) {
        var m = CANT_RESOLVE.matcher(message);
        if (m.matches()) {
            return "cannot resolve symbol '" + m.group(1) + "'";
        }
        return QUALIFIED_NAME.matcher(message).replaceAll("$3");
    }

    private Diagnostic warnNotThrown(String name, TreePath path) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = path.getCompilationUnit();
        var start = pos.getStartPosition(root, path.getLeaf());
        var end = pos.getEndPosition(root, path.getLeaf());
        var d = new Diagnostic();
        d.message = String.format("'%s' is not thrown in the body of the method", name);
        d.range = RangeHelper.range(root, start, end);
        d.code = "unused_throws";
        d.severity = DiagnosticSeverity.Information;
        d.tags = List.of(DiagnosticTag.Unnecessary);
        return d;
    }

    private Diagnostic warnUnused(Element unusedEl) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(unusedEl);
        if (path == null) {
            throw new RuntimeException(unusedEl + " has no path");
        }
        var root = path.getCompilationUnit();
        var leaf = path.getLeaf();
        var pos = trees.getSourcePositions();
        var start = (int) pos.getStartPosition(root, leaf);
        var end = (int) pos.getEndPosition(root, leaf);
        if (leaf instanceof VariableTree) {
            var v = (VariableTree) leaf;
            var offset = (int) pos.getEndPosition(root, v.getType());
            if (offset != -1) {
                start = offset;
            }
        }
        var file = Paths.get(root.getSourceFile().toUri());
        var contents = FileStore.contents(file);
        var name = unusedEl.getSimpleName();
        if (name.contentEquals("<init>")) {
            name = unusedEl.getEnclosingElement().getSimpleName();
        }
        var leafStart = (int) pos.getStartPosition(root, leaf);
        var leafEnd = (int) pos.getEndPosition(root, leaf);
        var region = contents.subSequence(start, end == javax.tools.Diagnostic.NOPOS ? contents.length() : end);
        var matcher = Pattern.compile("\\b" + name + "\\b").matcher(region);
        if (matcher.find()) {
            start += matcher.start();
            end = start + name.length();
        } else if (start >= end) {
            start = leafStart;
            end = leafEnd;
        }
        var message = String.format("'%s' is not used", name);
        String code;
        int severity;
        if (leaf instanceof VariableTree) {
            var parent = path.getParentPath().getLeaf();
            if (parent instanceof MethodTree) {
                code = "unused_param";
                severity = DiagnosticSeverity.Hint;
            } else if (parent instanceof BlockTree) {
                code = "unused_local";
                severity = DiagnosticSeverity.Information;
            } else if (parent instanceof ClassTree) {
                code = "unused_field";
                severity = DiagnosticSeverity.Information;
            } else {
                code = "unused_other";
                severity = DiagnosticSeverity.Hint;
            }
        } else if (leaf instanceof MethodTree) {
            code = "unused_method";
            severity = DiagnosticSeverity.Information;
        } else if (leaf instanceof ClassTree) {
            code = "unused_class";
            severity = DiagnosticSeverity.Information;
        } else {
            code = "unused_other";
            severity = DiagnosticSeverity.Information;
        }
        return lspWarnUnused(severity, code, message, start, end, root);
    }

    private static Diagnostic lspWarnUnused(
            int severity, String code, String message, int start, int end, CompilationUnitTree root) {
        var result = new Diagnostic();
        result.severity = severity;
        result.code = code;
        result.message = message;
        result.tags = List.of(DiagnosticTag.Unnecessary);
        result.range = RangeHelper.range(root, start, end);
        return result;
    }
}
