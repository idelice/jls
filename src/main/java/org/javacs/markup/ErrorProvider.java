package org.javacs.markup;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.FileStore;
import org.javacs.lsp.*;

public class ErrorProvider {
    final CompileTask task;

    public ErrorProvider(CompileTask task) {
        this.task = task;
    }

    public PublishDiagnosticsParams[] errors() {
        var result = new PublishDiagnosticsParams[task.roots.size()];
        for (var i = 0; i < task.roots.size(); i++) {
            var root = task.roots.get(i);
            result[i] = new PublishDiagnosticsParams();
            result[i].uri = root.getSourceFile().toUri();
            result[i].diagnostics.addAll(compilerErrors(root));
            result[i].diagnostics.addAll(unusedWarnings(root));
            result[i].diagnostics.addAll(notThrownWarnings(root));
            suppressSecondaryDiagnosticsOnErrorLines(result[i].diagnostics);
            result[i].diagnostics.sort((a, b) -> {
                var aSeverity = a.severity == null ? DiagnosticSeverity.Warning : a.severity;
                var bSeverity = b.severity == null ? DiagnosticSeverity.Warning : b.severity;
                if (aSeverity != bSeverity) {
                    return Integer.compare(aSeverity, bSeverity);
                }
                if (a.range == null || b.range == null) {
                    return a.range == null ? (b.range == null ? 0 : 1) : -1;
                }
                var lineCompare = Integer.compare(a.range.start.line, b.range.start.line);
                if (lineCompare != 0) {
                    return lineCompare;
                }
                return Integer.compare(a.range.start.character, b.range.start.character);
            });
        }
        // TODO hint fields that could be final

        return result;
    }

    private static void suppressSecondaryDiagnosticsOnErrorLines(List<org.javacs.lsp.Diagnostic> diagnostics) {
        var errorLines = new java.util.HashSet<Integer>();
        for (var d : diagnostics) {
            if (d.severity != null && d.severity == DiagnosticSeverity.Error && d.range != null) {
                for (int line = d.range.start.line; line <= d.range.end.line; line++) {
                    errorLines.add(line);
                }
            }
        }
        if (errorLines.isEmpty()) {
            return;
        }
        diagnostics.removeIf(d -> {
            if (d.severity == null || d.severity != DiagnosticSeverity.Warning || d.range == null) {
                return false;
            }
            for (int line = d.range.start.line; line <= d.range.end.line; line++) {
                if (errorLines.contains(line)) {
                    return true;
                }
            }
            return false;
        });
    }

    private List<org.javacs.lsp.Diagnostic> compilerErrors(CompilationUnitTree root) {
        var result = new ArrayList<org.javacs.lsp.Diagnostic>();
        for (var d : task.diagnostics) {
            if (d.getSource() == null || !d.getSource().toUri().equals(root.getSourceFile().toUri())) continue;
            if (d.getStartPosition() == -1) continue;
            result.add(lspDiagnostic(d, root.getLineMap()));
        }
        return result;
    }

    private List<org.javacs.lsp.Diagnostic> unusedWarnings(CompilationUnitTree root) {
        var result = new ArrayList<org.javacs.lsp.Diagnostic>();
        var warnUnused = new WarnUnused(task.task);
        warnUnused.scan(root, null);
        for (var unusedEl : warnUnused.notUsed()) {
            result.add(warnUnused(unusedEl));
        }
        for (var importPath : warnUnused.unusedImports()) {
            var leaf = importPath.getLeaf();
            if (!(leaf instanceof ImportTree)) continue;
            var warn = warnUnusedImport(root, (ImportTree) leaf);
            if (warn != null) {
                result.add(warn);
            }
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

    private org.javacs.lsp.Diagnostic warnUnusedImport(CompilationUnitTree root, ImportTree tree) {
        var pos = Trees.instance(task.task).getSourcePositions();
        var start = (int) pos.getStartPosition(root, tree);
        var end = (int) pos.getEndPosition(root, tree);
        if (start == Diagnostic.NOPOS) {
            return null;
        }
        if (end == Diagnostic.NOPOS || end <= start) {
            var file = Paths.get(root.getSourceFile().toUri());
            var contents = FileStore.contents(file);
            end = Math.min(start + 1, contents.length());
        }
        var qualified = tree.getQualifiedIdentifier().toString();
        var d = new org.javacs.lsp.Diagnostic();
        d.severity = DiagnosticSeverity.Warning;
        d.code = "unused_import";
        d.message = String.format("Import '%s' is not used", qualified);
        d.tags = List.of(DiagnosticTag.Unnecessary);
        d.range = RangeHelper.range(root, start, end);
        return d;
    }

    /**
     * lspDiagnostic(d, lines) converts d to LSP format, with its position shifted appropriately for the latest version
     * of the file.
     */
    private org.javacs.lsp.Diagnostic lspDiagnostic(javax.tools.Diagnostic<? extends JavaFileObject> d, LineMap lines) {
        var start = d.getStartPosition();
        var end = d.getEndPosition();
        if (end == Diagnostic.NOPOS) {
            end = start;
        }
        var severity = severity(d.getKind());
        if (severity == DiagnosticSeverity.Error && end <= start) {
            var expanded = expandToLineRange(d, start);
            start = expanded.start;
            end = expanded.end;
        }
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        var code = d.getCode();
        var message = d.getMessage(null);
        var result = new org.javacs.lsp.Diagnostic();
        result.severity = severity;
        result.code = code;
        result.message = message;
        result.range =
                new Range(new Position(startLine - 1, startColumn - 1), new Position(endLine - 1, endColumn - 1));
        return result;
    }

    private static class LineRange {
        final long start;
        final long end;

        LineRange(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private LineRange expandToLineRange(javax.tools.Diagnostic<? extends JavaFileObject> d, long start) {
        if (d.getSource() == null) {
            return new LineRange(start, start);
        }
        var file = Paths.get(d.getSource().toUri());
        var contents = FileStore.contents(file);
        var length = contents.length();
        if (start < 0 || start > length) {
            return new LineRange(start, start);
        }
        int lineStart = (int) start;
        while (lineStart > 0 && contents.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int lineEnd = (int) start;
        while (lineEnd < length && contents.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        if (lineEnd > lineStart && lineEnd <= length && lineEnd > 0 && contents.charAt(lineEnd - 1) == '\r') {
            lineEnd--;
        }
        if (lineEnd == lineStart && lineEnd < length) {
            lineEnd = lineStart + 1;
        }
        return new LineRange(lineStart, lineEnd);
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
