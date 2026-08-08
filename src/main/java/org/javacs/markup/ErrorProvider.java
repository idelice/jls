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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindNameAt;
import org.javacs.LombokAnnotations;
import org.javacs.index.IndexedMember;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.*;
import org.javacs.resolve.TypeNames;

public class ErrorProvider {
    final CompileTask task;
    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndex;
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
        this(task, null, null);
    }

    public ErrorProvider(CompileTask task, CompilerProvider compiler) {
        this(task, compiler, null);
    }

    public ErrorProvider(CompileTask task, CompilerProvider compiler, TypeIndexRouter typeIndex) {
        this.task = task;
        this.compiler = compiler;
        this.typeIndex = typeIndex;
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
                params.diagnostics.addAll(staleWorkspaceAccessorErrors(root));
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

    private List<Diagnostic> staleWorkspaceAccessorErrors(CompilationUnitTree root) {
        if (typeIndex == null) return List.of();

        var result = new ArrayList<Diagnostic>();
        var positions = task.trees.getSourcePositions();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                var methodPath = new TreePath(getCurrentPath(), invocation.getMethodSelect());
                var element = task.trees.getElement(methodPath);
                if (!(element instanceof ExecutableElement method) || task.trees.getPath(method) != null) {
                    return super.visitMethodInvocation(invocation, unused);
                }
                if (!(method.getEnclosingElement() instanceof TypeElement owner)) {
                    return super.visitMethodInvocation(invocation, unused);
                }

                var ownerName = owner.getQualifiedName().toString();
                var liveOwner = typeIndex.workspace().typeInfo(ownerName).orElse(null);
                if (liveOwner == null) {
                    return super.visitMethodInvocation(invocation, unused);
                }

                var methodName = method.getSimpleName().toString();
                var fieldName = LombokAnnotations.accessorFieldName(methodName).orElse(null);
                if (fieldName == null) {
                    return super.visitMethodInvocation(invocation, unused);
                }

                var parameterTypes = method.getParameters().stream()
                        .map(parameter -> task.types.erasure(parameter.asType()).toString())
                        .toArray(String[]::new);
                var methodStillExists = false;
                for (var member : liveOwner.members) {
                    if (member.kind != CompletionItemKind.Method
                            || !member.name.equals(methodName)
                            || member.erasedParameterTypes.length != parameterTypes.length) continue;
                    methodStillExists = true;
                    for (var i = 0; i < parameterTypes.length; i++) {
                        if (!TypeNames.simpleName(member.erasedParameterTypes[i])
                                .equals(TypeNames.simpleName(parameterTypes[i]))) {
                            methodStillExists = false;
                            break;
                        }
                    }
                    if (methodStillExists) break;
                }
                if (methodStillExists) {
                    return super.visitMethodInvocation(invocation, unused);
                }

                var end = positions.getEndPosition(root, invocation.getMethodSelect());
                var start = end - methodName.length();
                if (start < 0 || end < start) {
                    return super.visitMethodInvocation(invocation, unused);
                }

                var diagnostic = new Diagnostic();
                diagnostic.range = RangeHelper.range(root, start, end);
                diagnostic.severity = DiagnosticSeverity.Error;
                diagnostic.code = "compiler.err.cant.resolve.location.args";
                diagnostic.message = "cannot resolve symbol '" + methodName + "()'";
                result.add(diagnostic);
                return super.visitMethodInvocation(invocation, unused);
            }
        }.scan(root, null);
        return result;
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

    // Structural Lombok annotations generate methods and constructors that proc:none cannot see.
    // Logging annotations are handled separately and must not make ordinary bytecode fields look generated.
    private List<Diagnostic> suppressLombokBytecodeErrors(List<Diagnostic> diagnostics, CompilationUnitTree root) {
        if (compiler == null || typeIndex == null || !compiler.lombokPresentOnClasspath()) return diagnostics;
        if (!LombokAnnotations.hasStructuralLombokAnnotation(root)) return diagnostics;
        var className = qualifiedClassName(root);
        var classFile = compiler.findClassFile(className);
        if (classFile.isEmpty()) return diagnostics;
        java.lang.classfile.ClassModel model;
        try {
            model = java.lang.classfile.ClassFile.of().parse(classFile.get());
        } catch (Exception e) {
            return diagnostics;
        }
        var result = new ArrayList<Diagnostic>();
        for (var d : diagnostics) {
            if (d.code == null) { result.add(d); continue; }
            if (d.code.contains("cant.resolve") && d.message != null) {
                var signatureMatcher = Pattern.compile("'([^']+)'", Pattern.DOTALL).matcher(d.message);
                if (signatureMatcher.find()) {
                    var signature = signatureMatcher.group(1);
                    var open = signature.indexOf('(');
                    var close = signature.lastIndexOf(')');
                    if (open > 0 && close > open) {
                        var name = signature.substring(0, open);
                        var parameters = signature.substring(open + 1, close);
                        var requestedTypes = parameters.isBlank() ? new String[0] : parameters.split(",");
                        var liveType = typeIndex.workspace().typeInfo(className).orElse(null);
                        var liveMethod = false;
                        if (liveType != null) {
                            for (var member : liveType.members) {
                                if (!member.name.equals(name)
                                        || member.kind != CompletionItemKind.Method
                                        || member.erasedParameterTypes.length != requestedTypes.length) continue;
                                liveMethod = true;
                                for (var i = 0; i < requestedTypes.length; i++) {
                                    if (!TypeNames.simpleName(member.erasedParameterTypes[i])
                                            .equals(TypeNames.simpleName(requestedTypes[i]))) {
                                        liveMethod = false;
                                        break;
                                    }
                                }
                                if (liveMethod) break;
                            }
                        }
                        if (liveMethod && hasMethod(model, name)) {
                            continue;
                        }
                    }
                }
            }
            if (d.code.contains("cant.apply.symbol")) {
                if (isGeneratedConstructorError(d, root, className, model)) {
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

    private static boolean hasMethod(java.lang.classfile.ClassModel model, String name) {
        return model.methods().stream().anyMatch(m -> m.methodName().equalsString(name));
    }

    private boolean isGeneratedConstructorError(
            Diagnostic diagnostic,
            CompilationUnitTree root,
            String className,
            java.lang.classfile.ClassModel model) {
        if (diagnostic.message == null
                || !diagnostic.message.startsWith("constructor " + TypeNames.simpleName(className) + " ")
                || diagnostic.range == null) {
            return false;
        }

        var offset = root.getLineMap().getPosition(
                diagnostic.range.start.line + 1,
                diagnostic.range.start.character + 1);
        var invocationPath = new FindNameAt(task).scan(root, offset);
        while (invocationPath != null && !(invocationPath.getLeaf() instanceof NewClassTree)) {
            invocationPath = invocationPath.getParentPath();
        }
        if (invocationPath == null || !(invocationPath.getLeaf() instanceof NewClassTree invocation)) {
            return false;
        }

        var owner = task.elements.getTypeElement(className);
        if (owner == null) {
            return false;
        }
        var fields = new HashMap<String, VariableElement>();
        for (var element : owner.getEnclosedElements()) {
            if (element instanceof VariableElement field) {
                fields.put(field.getSimpleName().toString(), field);
            }
        }

        for (var constructor : typeIndex.workspace().constructors(className)) {
            if (constructor.origin != IndexedMember.Origin.LOMBOK_CONSTRUCTOR
                    || constructor.parameterNames.length != invocation.getArguments().size()) {
                continue;
            }

            var parameterTypes = new ArrayList<javax.lang.model.type.TypeMirror>();
            var acceptsArguments = true;
            for (var i = 0; i < constructor.parameterNames.length; i++) {
                var field = fields.get(constructor.parameterNames[i]);
                if (field == null) {
                    acceptsArguments = false;
                    break;
                }
                var expected = field.asType();
                var actual = task.trees.getTypeMirror(
                        new TreePath(invocationPath, invocation.getArguments().get(i)));
                if (actual == null
                        || !(task.types.isAssignable(actual, expected)
                                || expected.getKind() == TypeKind.TYPEVAR
                                        && task.types.isAssignable(actual, task.types.erasure(expected)))) {
                    acceptsArguments = false;
                    break;
                }
                parameterTypes.add(task.types.erasure(expected));
            }
            if (!acceptsArguments) {
                continue;
            }

            for (var method : model.methods()) {
                if (!method.methodName().equalsString("<init>")
                        || method.methodTypeSymbol().parameterCount() != parameterTypes.size()) {
                    continue;
                }
                var sameSignature = true;
                for (var i = 0; i < parameterTypes.size(); i++) {
                    var binaryType = method.methodTypeSymbol().parameterType(i);
                    var binaryName = binaryType.packageName().isBlank()
                            ? binaryType.displayName()
                            : binaryType.packageName() + "." + binaryType.displayName();
                    if (!binaryName.replace('$', '.')
                            .equals(parameterTypes.get(i).toString().replace('$', '.'))) {
                        sameSignature = false;
                        break;
                    }
                }
                if (sameSignature) {
                    return true;
                }
            }
        }
        return false;
    }
}
