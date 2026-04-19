package org.javacs.markup;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.FileStore;
import org.javacs.lsp.*;

public class ErrorProvider {
    final CompileTask task;
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");
    private static final Set<String> SYNTAX_BLOCKING_CODES =
            Set.of(
                    "compiler.err.expected",
                    "compiler.err.expected2",
                    "compiler.err.not.stmt",
                    "compiler.err.illegal.start.of.expr",
                    "compiler.err.illegal.start.of.stmt");

    private record DiagnosticFilterResult(
            List<org.javacs.lsp.Diagnostic> compilerDiagnostics, boolean syntaxSuppressed, int droppedCount) {}

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

    public ErrorReport errors(Set<java.net.URI> requestedUris) {
        var requested = requestedUris == null ? Set.<java.net.URI>of() : new LinkedHashSet<>(requestedUris);
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

    private boolean isJarOrCachedSource(java.net.URI uri) {
        // Check if it's a jar: URI
        if ("jar".equals(uri.getScheme())) {
            return true;
        }
        // Check if it's in the jls-jar-sources cache directory
        String path = uri.getPath();
        return path != null && path.contains("jls-jar-sources");
    }

    private List<org.javacs.lsp.Diagnostic> compilerErrors(CompilationUnitTree root) {
        var result = new ArrayList<org.javacs.lsp.Diagnostic>();

        // Create a copy to avoid ConcurrentModificationException during cache compilation
        var diagnosticsCopy = new ArrayList<>(task.diagnostics);

        for (var d : diagnosticsCopy) {
            if (d.getSource() == null || !d.getSource().toUri().equals(root.getSourceFile().toUri())) continue;
            if (d.getStartPosition() == -1 || d.getEndPosition() == -1) continue;

            result.add(lspDiagnostic(d, root.getLineMap()));
        }
        return result;
    }

    private DiagnosticFilterResult filterCompilerDiagnostics(
            List<org.javacs.lsp.Diagnostic> compilerDiagnostics, CompilationUnitTree root) {
        var deduped = dedupeDiagnostics(compilerDiagnostics);
        var firstSyntaxLine = firstSyntaxBlockingLine(deduped);
        if (firstSyntaxLine == -1) {
            return new DiagnosticFilterResult(deduped, false, compilerDiagnostics.size() - deduped.size());
        }

        org.javacs.lsp.Diagnostic primarySyntaxDiagnostic = null;
        for (var diagnostic : deduped) {
            if (diagnostic.severity == null || diagnostic.severity != DiagnosticSeverity.Error) {
                continue;
            }
            if (diagnostic.range.start.line == firstSyntaxLine && isSyntaxBlockingDiagnostic(diagnostic)) {
                primarySyntaxDiagnostic = diagnostic;
                break;
            }
        }
        var filtered = new ArrayList<org.javacs.lsp.Diagnostic>();
        if (primarySyntaxDiagnostic != null) {
            filtered.add(primarySyntaxDiagnostic);
        }
        return new DiagnosticFilterResult(filtered, true, compilerDiagnostics.size() - filtered.size());
    }

    private List<org.javacs.lsp.Diagnostic> dedupeDiagnostics(List<org.javacs.lsp.Diagnostic> diagnostics) {
        var unique = new LinkedHashMap<String, org.javacs.lsp.Diagnostic>();
        for (var diagnostic : diagnostics) {
            unique.putIfAbsent(diagnosticKey(diagnostic), diagnostic);
        }
        return new ArrayList<>(unique.values());
    }

    private int firstSyntaxBlockingLine(List<org.javacs.lsp.Diagnostic> diagnostics) {
        var firstLine = Integer.MAX_VALUE;
        for (var diagnostic : diagnostics) {
            if (!isSyntaxBlockingDiagnostic(diagnostic)) {
                continue;
            }
            firstLine = Math.min(firstLine, diagnostic.range.start.line);
        }
        return firstLine == Integer.MAX_VALUE ? -1 : firstLine;
    }

    private boolean isSyntaxBlockingDiagnostic(org.javacs.lsp.Diagnostic diagnostic) {
        return diagnostic.code != null && SYNTAX_BLOCKING_CODES.contains(diagnostic.code);
    }

    private String diagnosticKey(org.javacs.lsp.Diagnostic diagnostic) {
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

    private List<org.javacs.lsp.Diagnostic> unusedWarnings(CompilationUnitTree root) {
        var result = new ArrayList<org.javacs.lsp.Diagnostic>();
        var warnUnused = new WarnUnused(task.task);
        warnUnused.scan(root, null);
        for (var unusedEl : warnUnused.notUsed()) {
            result.add(warnUnused(unusedEl));
        }
        return result;
    }

    private List<org.javacs.lsp.Diagnostic> notThrownWarnings(CompilationUnitTree root) {
        var result = new ArrayList<org.javacs.lsp.Diagnostic>();
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
    private org.javacs.lsp.Diagnostic lspDiagnostic(javax.tools.Diagnostic<? extends JavaFileObject> d, LineMap lines) {
        var start = d.getStartPosition();
        var end = d.getEndPosition();
        var severity = severity(d.getKind());
        var code = d.getCode();
        var message = d.getMessage(null);
        var result = new org.javacs.lsp.Diagnostic();
        result.severity = severity;
        result.code = code;
        result.message = message;
        try {
            result.range = org.javacs.FileStore.range(d.getSource().getCharContent(true).toString(), start, end);
        } catch (java.io.IOException e) {
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

    private org.javacs.lsp.Diagnostic warnNotThrown(String name, TreePath path) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = path.getCompilationUnit();
        var start = pos.getStartPosition(root, path.getLeaf());
        var end = pos.getEndPosition(root, path.getLeaf());
        var d = new org.javacs.lsp.Diagnostic();
        d.message = String.format("'%s' is not thrown in the body of the method", name);
        d.range = RangeHelper.range(root, start, end);
        d.code = "unused_throws";
        d.severity = DiagnosticSeverity.Information;
        d.tags = List.of(DiagnosticTag.Unnecessary);
        return d;
    }

    private org.javacs.lsp.Diagnostic warnUnused(Element unusedEl) {
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
        var region = contents.subSequence(start, end == Diagnostic.NOPOS ? contents.length() : end);
        var matcher = Pattern.compile("\\b" + name + "\\b").matcher(region);
        if (matcher.find()) {
            start += matcher.start();
            end = start + name.length();
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

    private static org.javacs.lsp.Diagnostic lspWarnUnused(
            int severity, String code, String message, int start, int end, CompilationUnitTree root) {
        var result = new org.javacs.lsp.Diagnostic();
        result.severity = severity;
        result.code = code;
        result.message = message;
        result.tags = List.of(DiagnosticTag.Unnecessary);
        result.range = RangeHelper.range(root, start, end);
        return result;
    }
}
