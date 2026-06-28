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
import java.util.Collections;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.LombokAnnotations;
import org.javacs.lsp.*;

public class ErrorProvider {
    final CompileTask task;
    private final CompilerProvider compiler;
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
        this(task, null);
    }

    public ErrorProvider(CompileTask task, CompilerProvider compiler) {
        this.task = task;
        this.compiler = compiler;
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
            var filteredDiagnostics = filtered.compilerDiagnostics();
            filteredDiagnostics = suppressLogErrors(filteredDiagnostics, root);
            filteredDiagnostics = suppressLombokBytecodeErrors(filteredDiagnostics, root);
            params.diagnostics.addAll(filteredDiagnostics);
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
        var warnUnused = new WarnUnused(task.trees);
        warnUnused.scan(root, null);
        var allUnused = warnUnused.notUsed();

        // Phase 1: Identify Lombok private fields and suppress false positives
        var suppressed = suppressLombokFalsePositives(root, allUnused);

        for (var unusedEl : allUnused) {
            if (suppressed.contains(unusedEl)) continue;
            result.add(warnUnused(unusedEl));
        }

        result.addAll(unusedImportWarnings(root));
        return result;
    }

    /** Cross-file reference check: for private fields in @Data/@Getter/@Setter/@Value classes,
     *  suppress warning if their generated getter/setter is referenced from files that
     *  import the owning class. */
    private Set<Element> suppressLombokFalsePositives(CompilationUnitTree root, Set<Element> allUnused) {
        // Guard: skip if no compiler access or Lombok not on classpath
        if (compiler == null || !compiler.lombokPresentOnClasspath()) {
            return Set.of();
        }

        var trees = task.trees;
        var suppressed = new HashSet<Element>();

        // Collect Lombok field info
        record LombokFieldInfo(Element element, Set<String> accessorNames, String ownerType) {}
        List<LombokFieldInfo> lombokFields = new ArrayList<>();

        for (var unusedEl : allUnused) {
            // Must be a field (not method/class/param)
            if (!(unusedEl instanceof VariableElement)) continue;
            // Enclosing element must be a class (not method — guard against class cast exception)
            if (!(unusedEl.getEnclosingElement() instanceof TypeElement typeEl)) continue;

            // Get the ClassTree to check annotations
            var classPath = trees.getPath(typeEl);
            if (classPath == null || !(classPath.getLeaf() instanceof ClassTree classTree)) continue;

            // Only suppress for @Data, @Getter, @Setter, @Value
            if (!LombokAnnotations.hasAnnotation(classTree.getModifiers(), "Data", "Getter", "Setter", "Value"))
                continue;

            // Derive getter/setter names
            var names = new HashSet<String>();
            var fieldName = unusedEl.getSimpleName().toString();
            var cap = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            names.add("get" + cap);
            names.add("set" + cap);
            // Check if boolean-like
            var fieldType = unusedEl.asType();
            if (fieldType.getKind() == TypeKind.BOOLEAN || "boolean".equals(fieldType.toString())) {
                names.add("is" + cap);
            }
            lombokFields.add(new LombokFieldInfo(unusedEl, names, typeEl.getQualifiedName().toString()));
        }

        // Phase 2: Scoped reference check
        if (!lombokFields.isEmpty()) {
            // Collect unique owner types
            var ownerTypes =
                    lombokFields.stream().map(LombokFieldInfo::ownerType).collect(Collectors.toSet());

            // Find files that reference ANY of the owner types
            Set<Path> referencingFiles = new HashSet<>();
            for (var ownerType : ownerTypes) {
                Collections.addAll(referencingFiles, compiler.findTypeReferences(ownerType));
            }

            // In those files only, check for getter/setter names
            var currentUri = root.getSourceFile().toUri();
            var allAccessorNames =
                    lombokFields.stream()
                            .flatMap(f -> f.accessorNames().stream())
                            .collect(Collectors.toSet());

            for (var file : referencingFiles) {
                if (file.toUri().equals(currentUri)) continue;
                var content = FileStore.contents(file);
                for (var name : allAccessorNames) {
                    // Word-boundary match: "getName" should NOT match "getNameFromDb"
                    if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(content).find()) {
                        // Find which fields have this accessor name
                        for (var field : lombokFields) {
                            if (field.accessorNames().contains(name)) {
                                suppressed.add(field.element());
                            }
                        }
                    }
                }
            }

        }

        return suppressed;
    }

    private List<Diagnostic> unusedImportWarnings(CompilationUnitTree root) {
        var result = new ArrayList<Diagnostic>();
        var trees = task.trees;
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
        new WarnNotThrown(task.trees).scan(root, notThrown);
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
        var trees = task.trees;
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
        var trees = task.trees;
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

    // Lombok log field is generated bytecode, not source. Suppress "cannot resolve 'log'" errors.
    private List<Diagnostic> suppressLogErrors(List<Diagnostic> diagnostics, CompilationUnitTree root) {
        if (!LombokAnnotations.hasLogAnnotation(root)) return diagnostics;
        // null workspaceRoot → default "log" field name. Full impl would read lombok.config.
        var fieldName = LombokAnnotations.logFieldName(null);
        var result = new ArrayList<Diagnostic>();
        for (var d : diagnostics) {
            if (d.code != null && d.code.contains("cant.resolve") && d.message != null && d.message.contains("'" + fieldName + "'")) {
                continue;
            }
            result.add(d);
        }
        return result;
    }

    // For Lombok-annotated files, check .class bytecode before reporting cant.resolve / cant.apply.symbol.
    // If the symbol exists in bytecode, Lombok generated it — suppress the false positive.
    private List<Diagnostic> suppressLombokBytecodeErrors(List<Diagnostic> diagnostics, CompilationUnitTree root) {
        if (compiler == null || !compiler.lombokPresentOnClasspath()) return diagnostics;
        var file = Paths.get(root.getSourceFile().toUri());
        if (!LombokAnnotations.sourceMayRequireLombokExpansion(file, 50)) return diagnostics;
        var className = qualifiedClassName(root);
        var classFile = compiler.findClassFile(className);
        if (classFile.isEmpty()) return diagnostics;
        java.lang.classfile.ClassModel model;
        try {
            model = java.lang.classfile.ClassFile.of().parse(java.nio.file.Files.readAllBytes(classFile.get()));
        } catch (java.io.IOException e) {
            return diagnostics;
        }
        var result = new ArrayList<Diagnostic>();
        for (var d : diagnostics) {
            if (d.code == null) { result.add(d); continue; }
            if (d.code.contains("cant.resolve")) {
                var name = extractSymbolName(d.message);
                if (name != null && hasMember(model, name)) {
                    LOG.info("[lombok-filter] suppressed cant.resolve '" + name + "' in " + className);
                    continue;
                }
            }
            if (d.code.contains("cant.apply.symbol")) {
                if (hasConstructor(model)) {
                    LOG.info("[lombok-filter] suppressed cant.apply.symbol in " + className);
                    continue;
                }
            }
            result.add(d);
        }
        return result;
    }

    private static String qualifiedClassName(CompilationUnitTree root) {
        var pkg = root.getPackageName();
        var decls = root.getTypeDecls();
        if (decls.isEmpty()) return null;
        var name = ((ClassTree) decls.getFirst()).getSimpleName().toString();
        return pkg != null ? pkg + "." + name : name;
    }

    private static String extractSymbolName(String message) {
        if (message == null) return null;
        var m = Pattern.compile("'([^']+)'").matcher(message);
        if (!m.find()) return null;
        var name = m.group(1);
        var paren = name.indexOf('(');
        return paren >= 0 ? name.substring(0, paren) : name;
    }

    private static boolean hasMember(java.lang.classfile.ClassModel model, String name) {
        return model.methods().stream().anyMatch(m -> m.methodName().equalsString(name))
                || model.fields().stream().anyMatch(f -> f.fieldName().equalsString(name));
    }

    private static boolean hasConstructor(java.lang.classfile.ClassModel model) {
        return model.methods().stream().anyMatch(m -> m.methodName().equalsString("<init>"));
    }
}
