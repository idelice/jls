package org.javacs.markup;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.FileStore;
import org.javacs.LombokMetadataCache;
import org.javacs.LombokHandler;
import org.javacs.lsp.*;

public class ErrorProvider {
    final CompileTask task;
    private final LombokMetadataCache lombokCache;
    private final boolean includeWarnings;
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");
    private static final java.util.regex.Pattern LOCATION_CLASS_PATTERN =
            java.util.regex.Pattern.compile("location:.*(?:of type|class)\\s+([\\w.]+)");
    private static final java.util.regex.Pattern IN_CLASS_PATTERN =
            java.util.regex.Pattern.compile("in\\s+(?:class|enum)\\s+([\\w.]+)");

    public ErrorProvider(CompileTask task, LombokMetadataCache lombokCache) {
        this(task, lombokCache, true);
    }

    /**
     * Create an ErrorProvider with optional warning generation.
     *
     * @param task the compiled task
     * @param lombokCache the Lombok metadata cache
     * @param includeWarnings if false, skip expensive warning scans (unused variables, not thrown exceptions)
     */
    public ErrorProvider(CompileTask task, LombokMetadataCache lombokCache, boolean includeWarnings) {
        this.task = task;
        this.lombokCache = lombokCache;
        this.includeWarnings = includeWarnings;
    }

    public PublishDiagnosticsParams[] errors() {
        var result = new PublishDiagnosticsParams[task.roots.size()];
        for (var i = 0; i < task.roots.size(); i++) {
            var rootStart = System.nanoTime();
            var root = task.roots.get(i);
            var uri = root.getSourceFile().toUri();
            result[i] = new PublishDiagnosticsParams();
            result[i].uri = uri;
            // Skip diagnostics for JAR-based files (they are not user code)
            if (isJarOrCachedSource(uri)) {
                LOG.fine("Skipping diagnostics for JAR source: " + uri);
                continue;
            }
            var compilerStart = System.nanoTime();
            result[i].diagnostics.addAll(compilerErrors(root));
            result[i].diagnostics.addAll(LombokHandler.constructorDiagnostics(task, lombokCache, root));
            result[i].diagnostics.addAll(LombokHandler.builderConstructorDiagnostics(task, root));
            var compilerElapsedMs = (System.nanoTime() - compilerStart) / 1_000_000;

            // Skip warning scans when hard errors already exist; warning results are low-value and expensive while code is broken.
            var hasHardErrors =
                    result[i].diagnostics.stream().anyMatch(d -> d.severity == DiagnosticSeverity.Error);
            var warningStart = System.nanoTime();
            if (includeWarnings && !hasHardErrors) {
                result[i].diagnostics.addAll(unusedWarnings(root));
                result[i].diagnostics.addAll(notThrownWarnings(root));
            }
            var warningElapsedMs = (System.nanoTime() - warningStart) / 1_000_000;
            var totalElapsedMs = (System.nanoTime() - rootStart) / 1_000_000;
            var diagnosticCount = result[i].diagnostics.size();
            var warningsSkipped = includeWarnings && hasHardErrors;
            LOG.fine(
                    () ->
                            "[diag-timing] uri="
                                    + uri
                                    + " compiler_ms="
                                    + compilerElapsedMs
                                    + " warnings_ms="
                                    + warningElapsedMs
                                    + " warnings_skipped="
                                    + warningsSkipped
                                    + " total_ms="
                                    + totalElapsedMs
                                    + " diagnostics="
                                    + diagnosticCount);
        }
        // TODO hint fields that could be final

        return result;
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
        var filterContext = LombokHandler.newDiagnosticFilterContext("batch:" + root.getSourceFile().toUri());
        var prefetchClassNames = collectLikelyLombokClassNames(diagnosticsCopy, root);
        LombokHandler.prefetchDiagnosticClassMetadata(task, lombokCache, filterContext, prefetchClassNames, root);
        LOG.fine(
                () ->
                        "[lombok-cache] diagnostics batch start uri="
                                + root.getSourceFile().toUri()
                                + " diagnostics="
                                + diagnosticsCopy.size());
        int filteredByLombok = 0;
        int lombokChecks = 0;
        int lombokSkippedByPrecheck = 0;

        for (var d : diagnosticsCopy) {
            if (d.getSource() == null || !d.getSource().toUri().equals(root.getSourceFile().toUri())) continue;
            if (d.getStartPosition() == -1 || d.getEndPosition() == -1) continue;

            // Replace or filter out errors for Lombok-generated members
            var lombokAdjusted = LombokHandler.adjustDiagnostic(task, lombokCache, d, root);
            if (lombokAdjusted != null) {
                result.add(lombokAdjusted);
                continue;
            }
            if (shouldAttemptLombokFilter(d)) {
                lombokChecks++;
                if (LombokHandler.shouldFilterDiagnostic(task, lombokCache, d, filterContext)) {
                    filteredByLombok++;
                    continue; // Skip this error
                }
            } else {
                lombokSkippedByPrecheck++;
            }

            result.add(lspDiagnostic(d, root.getLineMap()));
        }
        var kept = result.size();
        var filteredCount = filteredByLombok;
        var checks = lombokChecks;
        var precheckSkipped = lombokSkippedByPrecheck;
        LOG.fine(
                () ->
                        "[lombok-cache] diagnostics batch end uri="
                                + root.getSourceFile().toUri()
                                + " filtered="
                                + filteredCount
                                + " lombok_checks="
                                + checks
                                + " precheck_skipped="
                                + precheckSkipped
                                + " kept="
                                + kept);
        return result;
    }

    private boolean shouldAttemptLombokFilter(Diagnostic<? extends JavaFileObject> d) {
        var code = d.getCode();
        if (code == null) return false;
        if ("compiler.err.cant.apply.symbol".equals(code)) {
            return true;
        }
        if (!code.startsWith("compiler.err.cant.resolve")) {
            return false;
        }
        var message = d.getMessage(null);
        if (message == null) {
            return false;
        }
        // Generated Lombok members are surfaced as unresolved methods, or as slf4j `log`.
        if (message.contains("method ")) {
            return true;
        }
        return message.contains("variable log");
    }

    private Set<String> collectLikelyLombokClassNames(
            List<Diagnostic<? extends JavaFileObject>> diagnosticsCopy, CompilationUnitTree root) {
        var classes = new HashSet<String>();
        var packageName = root.getPackage() != null ? root.getPackage().getPackageName().toString() : "";
        var sourceName = root.getSourceFile().getName();
        if (sourceName != null && sourceName.endsWith(".java")) {
            var separator = Math.max(sourceName.lastIndexOf('/'), sourceName.lastIndexOf('\\'));
            var fileName = sourceName.substring(separator + 1, sourceName.length() - 5);
            classes.add(packageName.isEmpty() ? fileName : packageName + "." + fileName);
        }
        for (var d : diagnosticsCopy) {
            if (d.getSource() == null || !d.getSource().toUri().equals(root.getSourceFile().toUri())) continue;
            var code = d.getCode();
            if (code == null || (!code.startsWith("compiler.err.cant.resolve") && !code.equals("compiler.err.cant.apply.symbol"))) {
                continue;
            }
            var message = d.getMessage(null);
            if (message == null) continue;
            var locationMatcher = LOCATION_CLASS_PATTERN.matcher(message);
            if (locationMatcher.find()) {
                classes.add(locationMatcher.group(1));
            }
            var inClassMatcher = IN_CLASS_PATTERN.matcher(message);
            if (inClassMatcher.find()) {
                classes.add(inClassMatcher.group(1));
            }
        }
        return classes;
    }

    /**
     * Check if a diagnostic is about a Lombok-generated member.
     * Examples of such errors:
     * - "cannot find symbol\n  symbol:   method getOne()\n  location: variable foo of type Foo"
     * - "cannot find symbol\n  symbol:   method setAge(int)\n  location: class Foo"
     */

    private List<org.javacs.lsp.Diagnostic> unusedWarnings(CompilationUnitTree root) {
        var result = new ArrayList<org.javacs.lsp.Diagnostic>();
        var warnUnused = new WarnUnused(task.task);
        warnUnused.scan(root, null);
        for (var unusedEl : warnUnused.notUsed()) {
            if (!LombokHandler.shouldSuppressUnusedField(unusedEl, task, lombokCache)) {
                result.add(warnUnused(unusedEl));
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

    /**
     * lspDiagnostic(d, lines) converts d to LSP format, with its position shifted appropriately for the latest version
     * of the file.
     */
    private org.javacs.lsp.Diagnostic lspDiagnostic(javax.tools.Diagnostic<? extends JavaFileObject> d, LineMap lines) {
        var start = d.getStartPosition();
        var end = d.getEndPosition();
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        var severity = severity(d.getKind());
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
