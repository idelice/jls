package org.javacs;

import static org.javacs.JsonHelper.GSON;

import com.google.gson.*;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.action.CodeActionProvider;
import org.javacs.completion.CompletionProvider;
import org.javacs.completion.ExternalBinaryTypeIndex;
import org.javacs.completion.SignatureProvider;
import org.javacs.completion.WorkspaceTypeIndex;
import org.javacs.completion.TypeIndexRouter;
import org.javacs.fold.FoldProvider;
import org.javacs.hover.HoverProvider;
import org.javacs.index.SymbolProvider;
import org.javacs.lens.CodeLensProvider;
import org.javacs.lsp.*;
import org.javacs.markup.ErrorProvider;
import org.javacs.navigation.DefinitionProvider;
import org.javacs.navigation.ReferenceProvider;
import org.javacs.rewrite.*;

/**
 * Main language-server orchestration for workspace state, diagnostics, and navigation/completion
 * readiness.
 *
 * <p>Diagnostics and completion indexing intentionally remain separate flows. Diagnostics must be
 * able to publish quickly for open files, while the completion index is allowed to lag slightly and
 * rebuild independently as declarations drift or the compiler is recreated.
 */
class JavaLanguageServer extends LanguageServer {
    private static final Logger LOG = Logger.getLogger("main");

    // TODO allow multiple workspace roots
    private Path workspaceRoot;
    private final LanguageClient client;
    private final ServerPerformanceLog perf = new ServerPerformanceLog(LOG);
    private final DiagnosticsFlow diagnosticsFlow = new DiagnosticsFlow(this, perf);
    private final CompletionIndexFlow completionIndexFlow = new CompletionIndexFlow(this, perf);
    private JavaCompilerService cacheCompiler;
    private JavaCompilerService diagnosticsCompiler;
    private JsonObject cacheSettings;
    private JsonObject settings = new JsonObject();
    private boolean modifiedBuild = true;
    private final ScheduledExecutorService diagnosticsExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "javacs-diagnostics");
                        t.setDaemon(true);
                        return t;
                    });
    private final ScheduledExecutorService completionIndexExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "javacs-completion-index");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicLong diagnosticsRevision = new AtomicLong();
    private final AtomicLong completionIndexRevision = new AtomicLong();
    private final Object diagnosticsCompileMutex = new Object();
    private final Object completionIndexCompileMutex = new Object();
    private ScheduledFuture<?> pendingDiagnostics;
    private ScheduledFuture<?> pendingCompletionIndex;
    private static final long DIAGNOSTIC_DEBOUNCE_MS = 250;
    private static final long COMPLETION_INDEX_DEBOUNCE_MS = 100;
    private static final long COMPLETION_BOOTSTRAP_WAIT_MS = 700;
    private static final long COMPLETION_BOOTSTRAP_POLL_MS = 25;
    private static final long NAVIGATION_BOOTSTRAP_WAIT_MS = 1500;
    private final AtomicReference<WorkspaceTypeIndex> completionIndexRef =
            new AtomicReference<>(WorkspaceTypeIndex.EMPTY);
    private final AtomicReference<ExternalBinaryTypeIndex> externalBinaryIndexRef =
            new AtomicReference<>(ExternalBinaryTypeIndex.EMPTY);
    private final AtomicLong completionIndexVersion = new AtomicLong();
    private final AtomicReference<CompletionIndexScope> completionIndexScope =
            new AtomicReference<>(CompletionIndexScope.EMPTY);
    private final AtomicReference<CompletionSnapshot> completionSnapshotRef =
            new AtomicReference<>(CompletionSnapshot.EMPTY);
    private final Set<Path> dirtyDiagnosticsFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicInteger diagnosticsCompilesInFlight = new AtomicInteger();
    private final AtomicInteger didSaveDiagnosticsInFlight = new AtomicInteger();

    private enum CompletionIndexRefreshMode {
        ACTIVE_DOCUMENT_BOOTSTRAP,
        FULL_REBUILD,
        WORKSPACE_DECLARATION_MERGE
    }

    private enum CompletionIndexScope {
        EMPTY,
        ACTIVE,
        WORKSPACE
    }

    private record CompletionSnapshot(
            WorkspaceTypeIndex workspaceIndex,
            ExternalBinaryTypeIndex externalIndex,
            TypeIndexRouter typeIndex,
            long version,
            CompletionIndexScope scope) {
        private static final CompletionSnapshot EMPTY =
                create(WorkspaceTypeIndex.EMPTY, ExternalBinaryTypeIndex.EMPTY, 0, CompletionIndexScope.EMPTY);

        private static CompletionSnapshot create(
                WorkspaceTypeIndex workspaceIndex,
                ExternalBinaryTypeIndex externalIndex,
                long version,
                CompletionIndexScope scope) {
            var safeWorkspace = workspaceIndex == null ? WorkspaceTypeIndex.EMPTY : workspaceIndex;
            var safeExternal = externalIndex == null  ? ExternalBinaryTypeIndex.EMPTY : externalIndex;
            var safeScope = scope == null ? CompletionIndexScope.EMPTY : scope;
            return new CompletionSnapshot(
                    safeWorkspace,
                    safeExternal,
                    new TypeIndexRouter(safeWorkspace, safeExternal),
                    Math.max(0L, version),
                    safeScope);
        }
    }

    private record ActiveDocumentChangeImpact(boolean refreshCompletionIndex) {}

    private record ScheduledDiagnosticsRequest(
            long scheduleRevision, long contentRevision, int requestedCount, int dirtyOpenCount) {}

    private record DiagnosticsBatch(List<Path> files, int requestedCount, int dirtyOpenCount) {}

    private record TypeIndexAvailability(
            long versionBefore, long versionAfter, CompletionIndexScope scopeBefore, CompletionIndexScope scopeAfter, long waitMs) {}

    private record DeclaredTypeShape(
            String qualifiedName,
            List<String> directMemberSignatures,
            String superclass,
            List<String> interfaces,
            boolean structuralLombok) {}

    synchronized JavaCompilerService compiler() {
        if (needsCompiler()) {
            var compilers = createCompilers();
            cacheCompiler = compilers.interactive;
            diagnosticsCompiler = compilers.diagnosticsPrimary;
            refreshExternalBinaryIndex(cacheCompiler);
            cancelPendingCompletionIndex("compilerRecreated");
            cacheSettings = compilerSettingsSnapshot(settings);
            modifiedBuild = false;
            refreshCompletionIndexForCompilerRecreated();
        }
        return cacheCompiler;
    }

    private CompletionSnapshot completionSnapshot() {
        return completionSnapshotRef.get();
    }

    private void publishCompletionSnapshot(
            WorkspaceTypeIndex workspaceIndex,
            ExternalBinaryTypeIndex externalIndex,
            long version,
            CompletionIndexScope scope) {
        var snapshot = CompletionSnapshot.create(workspaceIndex, externalIndex, version, scope);
        completionIndexRef.set(snapshot.workspaceIndex());
        externalBinaryIndexRef.set(snapshot.externalIndex());
        completionIndexVersion.set(snapshot.version());
        completionIndexScope.set(snapshot.scope());
        completionSnapshotRef.set(snapshot);
    }

    private void refreshExternalBinaryIndex(JavaCompilerService compiler) {
        var currentSnapshot = completionSnapshot();
        publishCompletionSnapshot(
                currentSnapshot.workspaceIndex(),
                new ExternalBinaryTypeIndex(compiler),
                currentSnapshot.version(),
                currentSnapshot.scope());
    }

    private DiagnosticsCompilerSelection selectDiagnosticsCompiler() {
        compiler();
        return new DiagnosticsCompilerSelection(diagnosticsCompiler);
    }

    private boolean needsCompiler() {
        if (modifiedBuild) {
            return true;
        }
        var currentCompilerSettings = compilerSettingsSnapshot(settings);
        if (!currentCompilerSettings.equals(cacheSettings)) {
            perf.info("Settings\n\t%s\nis different than\n\t%s", settings, cacheSettings);
            return true;
        }
        return false;
    }

    private JsonObject compilerSettingsSnapshot(JsonObject source) {
        var snapshot = new JsonObject();
        if (source == null) {
            return snapshot;
        }
        copySettingIfPresent(source, snapshot, "externalDependencies");
        copySettingIfPresent(source, snapshot, "classPath");
        copySettingIfPresent(source, snapshot, "extraCompilerArgs");
        copySettingIfPresent(source, snapshot, "docPath");
        copySettingIfPresent(source, snapshot, "addExports");
        copySettingIfPresent(source, snapshot, "lombokEnabled");
        copySettingIfPresent(source, snapshot, "lombok.enabled");
        copySettingIfPresent(source, snapshot, "lombok");
        return snapshot;
    }

    private void copySettingIfPresent(JsonObject source, JsonObject target, String key) {
        if (!source.has(key)) {
            return;
        }
        target.add(key, source.get(key).deepCopy());
    }

    private void refreshCompletionIndexForCompilerRecreated() {
        var active = normalizeJavaFiles(FileStore.activeDocuments());
        if (!active.isEmpty()) {
            diagnosticsFlow.cancel("compilerRecreated");
            scheduleDiagnostics(active, "compilerRecreated", 0);
            scheduleProjectCompletionIndexRefresh("compilerRecreated", 0);
            return;
        }
        perf.fine("[perf] completion_index_refresh_deferred trigger=compilerRecreated reason=no_active_docs");
    }

    void lint(Collection<Path> files) {
        diagnosticsFlow.cancel("foreground");
        completionIndexFlow.cancel("foreground");
        var selection = selectDiagnosticsCompiler();
        var javaFiles = normalizeJavaFiles(files);
        compileAndPublish(files, selection.compiler, "foreground", -1, javaFiles.size(), 0);
        if (completionSnapshot().version() == 0 && !FileStore.activeDocuments().isEmpty()) {
            scheduleActiveCompletionBootstrapIfNeeded("lintBootstrap", 0);
        }
    }

    private void compileAndPublish(
            Collection<Path> files,
            JavaCompilerService diagnosticsCompiler,
            String trigger,
            long expectedContentRevision,
            int requestedCount,
            int dirtyOpenCount) {
        diagnosticsFlow.compileAndPublish(
                files,
                diagnosticsCompiler,
                trigger,
                expectedContentRevision,
                requestedCount,
                dirtyOpenCount);
    }

    private void publishDiagnosticsFromTask(
            CompileTask task,
            Collection<Path> requestedFiles,
            String trigger,
            long expectedContentRevision) {
        diagnosticsFlow.publishFromTask(task, requestedFiles, trigger, expectedContentRevision);
    }

    private List<Path> normalizeJavaFiles(Collection<Path> files) {
        return DiagnosticsFlow.normalizeJavaFiles(files);
    }

    private void installTypeMemberIndex(
            WorkspaceTypeIndex nextIndex,
            long indexVersion,
            String trigger,
            Duration took,
            CompletionIndexScope scope) {
        completionIndexFlow.installTypeMemberIndex(nextIndex, indexVersion, trigger, took, scope);
    }

    private void installMergedTypeMemberIndex(
            WorkspaceTypeIndex deltaIndex,
            Collection<Path> replacedFiles,
            long indexVersion,
            String trigger,
            Duration took) {
        completionIndexFlow.installMergedTypeMemberIndex(deltaIndex, replacedFiles, indexVersion, trigger, took);
    }

    private long nextIndexVersion() {
        return completionIndexVersion.incrementAndGet();
    }

    private void scheduleProjectCompletionIndexRefresh(String trigger, long delayMs) {
        completionIndexFlow.scheduleProjectRefresh(trigger, delayMs);
    }

    private void scheduleActiveCompletionBootstrapIfNeeded(String trigger, long delayMs) {
        completionIndexFlow.scheduleActiveBootstrapIfNeeded(trigger, delayMs);
    }

    private void scheduleProjectCompletionBootstrapIfNeeded(String trigger, long delayMs) {
        completionIndexFlow.scheduleProjectBootstrapIfNeeded(trigger, delayMs);
    }

    private void scheduleCompletionIndexRefresh(
            Collection<Path> files, String trigger, long delayMs, CompletionIndexRefreshMode mode) {
        completionIndexFlow.scheduleRefresh(files, trigger, delayMs, mode);
    }

    private void runCompletionIndexRefresh(
            List<Path> files, long revision, String trigger, CompletionIndexRefreshMode mode) {
        completionIndexFlow.runRefresh(files, revision, trigger, mode);
    }

    private void installCompletionIndex(
            WorkspaceTypeIndex nextIndex,
            Collection<Path> files,
            CompletionIndexRefreshMode mode,
            long indexVersion,
            String trigger,
            Duration took) {
        completionIndexFlow.install(nextIndex, files, mode, indexVersion, trigger, took);
    }

    private WorkspaceTypeIndex buildCompletionIndex(CompileTask task, CompletionIndexRefreshMode mode) {
        return completionIndexFlow.build(task, mode);
    }

    private ActiveDocumentChangeImpact analyzeActiveDocumentChange(Path file) {
        try {
            var parse = compiler().parse(file);
            var shapes = declaredTypeShapes(parse);
            var hasStructuralLombokType = shapes.stream().anyMatch(DeclaredTypeShape::structuralLombok);
            if (hasStructuralLombokType) {
                perf.fine(
                        "[perf] completion_index_didChange_refresh file="
                                + file.getFileName()
                                + " reason=structural_lombok");
                return new ActiveDocumentChangeImpact(true);
            }
            if (hasLikelyIncompleteSource(FileStore.contents(file))) {
                perf.fine(
                        "[perf] completion_index_didChange_skip file="
                                + file.getFileName()
                                + " reason=incomplete_source");
                return new ActiveDocumentChangeImpact(false);
            }
            var declarationDrift = hasDeclarationDrift(file, shapes);
            if (declarationDrift) {
                perf.fine(
                        "[perf] completion_index_didChange_refresh file="
                                + file.getFileName()
                                + " reason=declaration_drift");
            }
            return new ActiveDocumentChangeImpact(declarationDrift);
        } catch (RuntimeException e) {
            perf.fine(
                    String.format(
                            "[perf] completion_index_didChange_skip file=%s reason=parse_failed detail=%s",
                            file.getFileName(), e.getMessage()));
        }
        return new ActiveDocumentChangeImpact(false);
    }

    static boolean hasLikelyIncompleteSource(String source) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        var braces = 0;
        var parens = 0;
        var inString = false;
        var inChar = false;
        var inLineComment = false;
        var inBlockComment = false;
        var escaping = false;
        for (int i = 0; i < source.length(); i++) {
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
            if (c == '{') {
                braces++;
            } else if (c == '}') {
                braces--;
            } else if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
            }
            if (braces < 0 || parens < 0) {
                return true;
            }
        }
        return braces != 0 || parens != 0 || inString || inChar || inBlockComment;
    }

    private Collection<Path> otherActiveDocuments(Path file) {
        var activeJavaFiles = normalizeJavaFiles(FileStore.activeDocuments());
        if (activeJavaFiles.isEmpty() || !activeJavaFiles.contains(file)) {
            return List.of();
        }
        var others = new LinkedHashSet<Path>();
        for (var active : activeJavaFiles) {
            if (active.equals(file)) {
                continue;
            }
            others.add(active);
        }
        return List.copyOf(others);
    }

    private List<DeclaredTypeShape> declaredTypeShapes(ParseTask parse) {
        var packageName = parse.root().getPackageName() == null ? "" : parse.root().getPackageName().toString();
        var result = new ArrayList<DeclaredTypeShape>();
        for (var decl : parse.root().getTypeDecls()) {
            if (!(decl instanceof ClassTree cls)) {
                continue;
            }
            var qualifiedName =
                    packageName.isEmpty()
                            ? cls.getSimpleName().toString()
                            : packageName + "." + cls.getSimpleName();
            var directMembers = new ArrayList<String>();
            for (var member : cls.getMembers()) {
                if (member instanceof VariableTree field) {
                    directMembers.add(fieldSignature(field));
                } else if (member instanceof MethodTree method) {
                    directMembers.add(methodSignature(method));
                }
            }
            var interfaces = new ArrayList<String>();
            if (cls.getImplementsClause() != null) {
                for (var iface : cls.getImplementsClause()) {
                    interfaces.add(iface.toString());
                }
            }
            var superclass = cls.getExtendsClause() == null ? null : cls.getExtendsClause().toString();
            result.add(
                    new DeclaredTypeShape(
                            qualifiedName,
                            List.copyOf(directMembers),
                            superclass,
                            List.copyOf(interfaces),
                            LombokAnnotations.hasStructuralLombokAnnotation(cls.getModifiers())));
        }
        return List.copyOf(result);
    }

    private boolean hasDeclarationDrift(Path file, List<DeclaredTypeShape> shapes) {
        var indexedTypes = new LinkedHashMap<String, WorkspaceTypeIndex.TypeInfo>();
        for (var type : completionSnapshot().workspaceIndex().types().values()) {
            if (file.equals(type.sourcePath)) {
                indexedTypes.put(type.qualifiedName, type);
            }
        }
        if (indexedTypes.size() != shapes.size()) {
            return true;
        }
        for (var shape : shapes) {
            var indexed = indexedTypes.get(shape.qualifiedName());
            if (indexed == null) {
                return true;
            }
            if (!Objects.equals(normalizeSuperclass(shape.superclass()), normalizeSuperclass(indexed.superclass))) {
                return true;
            }
            if (!Objects.equals(shape.interfaces(), indexed.interfaces)) {
                return true;
            }
            if (!Objects.equals(
                    sortedSignatures(shape.directMemberSignatures()),
                    sortedSignatures(indexedDirectMemberSignatures(indexed)))) {
                return true;
            }
            var indexedStructuralLombok =
                    indexed.members.stream().anyMatch(member -> member.synthetic && member.backingFieldName != null);
            if (shape.structuralLombok() != indexedStructuralLombok) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSuperclass(String superclass) {
        return superclass == null || "java.lang.Object".equals(superclass) ? null : superclass;
    }

    private List<String> indexedDirectMemberSignatures(WorkspaceTypeIndex.TypeInfo type) {
        var signatures = new ArrayList<String>();
        for (var member : type.members) {
            if (member.synthetic || member.priority != 0) {
                continue;
            }
            if (member.kind == CompletionItemKind.Field) {
                signatures.add("F:" + member.name + ":" + member.isStatic);
            } else if (member.kind == CompletionItemKind.Method) {
                signatures.add(
                        "M:"
                                + member.name
                                + ":"
                                + (member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length)
                                + ":"
                                + member.isStatic);
            }
        }
        return List.copyOf(signatures);
    }

    private List<String> sortedSignatures(List<String> signatures) {
        var sorted = new ArrayList<>(signatures);
        Collections.sort(sorted);
        return sorted;
    }

    private String fieldSignature(VariableTree field) {
        var isStatic =
                field.getModifiers() != null && field.getModifiers().getFlags().contains(Modifier.STATIC);
        return "F:" + field.getName() + ":" + isStatic;
    }

    private String methodSignature(MethodTree method) {
        var isStatic =
                method.getModifiers() != null && method.getModifiers().getFlags().contains(Modifier.STATIC);
        return "M:"
                + method.getName()
                + ":"
                + method.getParameters().size()
                + ":"
                + isStatic;
    }

    private void scheduleDiagnostics(Collection<Path> files, String trigger, long delayMs) {
        diagnosticsFlow.schedule(files, trigger, delayMs);
    }

    private void markDirtyDiagnostics(Path file, String trigger) {
        diagnosticsFlow.markDirty(file, trigger);
    }

    private void markDirtyDiagnostics(Collection<Path> files, String trigger) {
        diagnosticsFlow.markDirty(files, trigger);
    }

    private void markOtherActiveDiagnosticsDirty(Path file, String trigger) {
        markDirtyDiagnostics(file, trigger);
        var others = otherActiveDocuments(file);
        if (!others.isEmpty()) {
            markDirtyDiagnostics(others, trigger + ":openFiles");
            perf.fine(
                    String.format(
                            "[perf] diagnostics_dirty_open_files trigger=%s file=%s files=%d",
                            trigger, file.getFileName(), others.size()));
        }
    }

    private void clearDirtyDiagnostics(Collection<Path> files) {
        diagnosticsFlow.clearDirty(files);
    }

    private void cancelPendingDiagnostics(String reason) {
        diagnosticsFlow.cancel(reason);
    }

    private boolean shouldYieldToDidSaveDiagnostics(String trigger, String phase) {
        if (!trigger.startsWith("async:")) {
            return false;
        }
        if (didSaveDiagnosticsInFlight.get() <= 0) {
            return false;
        }
        perf.fine(
                String.format(
                        "[perf] diagnostics_yield trigger=%s phase=%s reason=didSave_pending",
                        trigger, phase));
        return true;
    }

    private DiagnosticsBatch resolveDiagnosticsBatch(Collection<Path> files, String trigger) {
        return diagnosticsFlow.resolveBatch(files, trigger);
    }

    private void runDiagnostics(List<Path> files, ScheduledDiagnosticsRequest request, String trigger) {
        diagnosticsFlow.run(files, request, trigger);
    }

    private void bootstrapWorkspaceOnDidOpen(Path file) {
        var selection = selectDiagnosticsCompiler();
        var workspaceFiles = normalizeJavaFiles(FileStore.all());
        if (workspaceFiles.isEmpty()) {
            return;
        }
        diagnosticsFlow.cancel("didOpenBootstrap");
        completionIndexFlow.cancel("didOpenBootstrap");
        perf.info(
                String.format(
                        "[perf] workspace bootstrap started trigger=didOpenBootstrap files=%d",
                        workspaceFiles.size()));
        CompileTask task = null;
        synchronized (diagnosticsCompileMutex) {
            try {
                perf.fine(
                        "[perf] diagnostics_compile trigger=%s requested=%d dirty_open=%d batch=%d",
                        "didOpenBootstrap",
                        workspaceFiles.size(),
                        0,
                        workspaceFiles.size());
                task = selection.compiler.compile(workspaceFiles.toArray(Path[]::new));
                var compileTelemetry = selection.compiler.lastCompileTelemetry();
                perf.info(
                        String.format(
                                "[perf] diagnostics_summary trigger=didOpenBootstrap requested=%d dirty_open=%d batch=%d compiled_roots=%d ap=%s expanded=%d compiler_path=%s cache=%s parse=%dms enter=%dms analyze=%dms",
                                workspaceFiles.size(),
                                0,
                                workspaceFiles.size(),
                                task.roots.size(),
                                compileTelemetry.annotationProcessingEnabled(),
                                compileTelemetry.expandedSources(),
                                compileTelemetry.path(),
                                compileTelemetry.cacheName(),
                                compileTelemetry.parseMs(),
                                compileTelemetry.enterMs(),
                                compileTelemetry.analyzeMs()));
                publishDiagnosticsFromTask(task, List.of(file), "didOpenBootstrap", -1);
                var indexStarted = Instant.now();
                var nextIndex = WorkspaceTypeIndex.workspaceDeclarations(task);
                var indexVersion = nextIndexVersion();
                installCompletionIndex(
                        nextIndex,
                        workspaceFiles,
                        CompletionIndexRefreshMode.FULL_REBUILD,
                        indexVersion,
                        "didOpenBootstrap",
                        Duration.between(indexStarted, Instant.now()));
                perf.info(
                        String.format(
                                "[perf] workspace index installed trigger=didOpenBootstrap version=%d types=%d",
                                indexVersion, nextIndex == null ? 0 : nextIndex.size()));
            } finally {
                if (task != null) {
                    task.close();
                }
            }
        }
    }

    private void cancelPendingCompletionIndex(String reason) {
        completionIndexFlow.cancel(reason);
    }

    private long awaitCompletionBootstrap(long initialIndexVersion, long timeoutMs) {
        var started = System.nanoTime();
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (completionSnapshot().version() == initialIndexVersion && System.nanoTime() < deadline) {
            try {
                Thread.sleep(COMPLETION_BOOTSTRAP_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            }
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private TypeIndexAvailability ensureTypeIndexReady(String trigger, long waitMs, boolean requireWorkspaceScope) {
        compiler();
        var snapshot = completionSnapshot();
        var initialIndexVersion = snapshot.version();
        var currentScope = snapshot.scope();
        if (initialIndexVersion != 0
                && (!requireWorkspaceScope || currentScope == CompletionIndexScope.WORKSPACE)) {
            return new TypeIndexAvailability(initialIndexVersion, initialIndexVersion, currentScope, currentScope, 0);
        }
        perf.fine(
                String.format(
                        "[perf] completion_index_bootstrap trigger=%s",
                        trigger));
        if (requireWorkspaceScope || FileStore.activeDocuments().isEmpty()) {
            scheduleProjectCompletionBootstrapIfNeeded(trigger, 0);
        } else {
            scheduleActiveCompletionBootstrapIfNeeded(trigger, 0);
        }
        var waited = awaitCompletionBootstrap(initialIndexVersion, waitMs);
        var updated = completionSnapshot();
        return new TypeIndexAvailability(
                initialIndexVersion,
                updated.version(),
                currentScope,
                updated.scope(),
                waited);
    }

    private boolean shouldSkipStaleDiagnostics(long expectedContentRevision, String trigger, String phase) {
        var current = FileStore.contentRevision();
        if (!isStaleDiagnosticsContent(expectedContentRevision, current)) {
            return false;
        }
        perf.fine(
                String.format(
                        "[perf] diagnostics_skip_stale trigger=%s phase=%s expected_content=%d current_content=%d",
                        trigger, phase, expectedContentRevision, current));
        return true;
    }

    static boolean isStaleDiagnosticsContent(long expectedContentRevision, long currentContentRevision) {
        return expectedContentRevision >= 0 && expectedContentRevision != currentContentRevision;
    }

    private boolean shouldRefreshCompletionIndexForActiveDocumentChange(ActiveDocumentChangeImpact impact) {
        return impact.refreshCompletionIndex();
    }

    private void javaStartProgress(JavaStartProgressParams params) {
        client.customNotification("java/startProgress", GSON.toJsonTree(params));
    }

    private void javaReportProgress(JavaReportProgressParams params) {
        client.customNotification("java/reportProgress", GSON.toJsonTree(params));
    }

    private void javaEndProgress() {
        client.customNotification("java/endProgress", JsonNull.INSTANCE);
    }

    private CompilerSet createCompilers() {
        Objects.requireNonNull(workspaceRoot, "Can't create compiler because workspaceRoot has not been initialized");
        var started = Instant.now();

        javaStartProgress(new JavaStartProgressParams("Configure javac"));
        javaReportProgress(new JavaReportProgressParams("Finding source roots"));

        var externalDependencies = externalDependencies();
        var classPath = classPath();
        var extraArgs = extraCompilerArgs();
        var addExports = addExports();
        var lombokEnabled = lombokEnabled();
        var settingsLoaded = Instant.now();
        Set<Path> resolvedDocPath;
        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            resolvedDocPath = docPath();
            perf.info(
                    String.format(
                            "[perf] compiler_config_inference mode=explicit classpath=%d docpath=%d took=%dms",
                            classPath.size(),
                            resolvedDocPath.size(),
                            Duration.between(settingsLoaded, Instant.now()).toMillis()));
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var infer = new InferConfig(workspaceRoot, externalDependencies);

            javaReportProgress(new JavaReportProgressParams("Inferring class path"));
            var inferClassPathStarted = Instant.now();
            classPath = infer.classPath();
            var inferredClassPath = Instant.now();

            javaReportProgress(new JavaReportProgressParams("Inferring doc path"));
            var inferDocPathStarted = Instant.now();
            resolvedDocPath = infer.buildDocPath();
            perf.info(
                    String.format(
                            "[perf] compiler_config_inference mode=inferred external=%d classpath=%d docpath=%d classpath_infer=%dms docpath_infer=%dms total=%dms",
                            externalDependencies.size(),
                            classPath.size(),
                            resolvedDocPath.size(),
                            Duration.between(inferClassPathStarted, inferredClassPath).toMillis(),
                            Duration.between(inferDocPathStarted, Instant.now()).toMillis(),
                            Duration.between(settingsLoaded, Instant.now()).toMillis()));
        }
        var inferenceFinished = Instant.now();
        javaEndProgress();
        perf.info("[perf] lombok_setting enabled=%s", lombokEnabled);
        var interactive =
                new JavaCompilerService(
                        classPath,
                        resolvedDocPath,
                        addExports,
                        extraArgs,
                        lombokEnabled,
                        "interactive");
        var diagnosticsStarted = Instant.now();
        var diagnostics =
                new JavaCompilerService(
                        classPath,
                        resolvedDocPath,
                        addExports,
                        extraArgs,
                        lombokEnabled,
                        "diagnostics");
        perf.info(
                String.format(
                        "[perf] create_compilers classpath=%d docpath=%d extra_args=%d add_exports=%d settings=%dms inference=%dms interactive=%dms diagnostics=%dms total=%dms",
                        classPath.size(),
                        resolvedDocPath.size(),
                        extraArgs.size(),
                        addExports.size(),
                        Duration.between(started, settingsLoaded).toMillis(),
                        Duration.between(settingsLoaded, inferenceFinished).toMillis(),
                        Duration.between(inferenceFinished, diagnosticsStarted).toMillis(),
                        Duration.between(diagnosticsStarted, Instant.now()).toMillis(),
                        Duration.between(started, Instant.now()).toMillis()));
        return new CompilerSet(interactive, diagnostics, lombokEnabled);
    }

    private record CompilerSet(JavaCompilerService interactive, JavaCompilerService diagnosticsPrimary,
                               boolean lombokEnabled) {
    }

    private record DiagnosticsCompilerSelection(JavaCompilerService compiler) {
    }

    private Set<String> externalDependencies() {
        if (!settings.has("externalDependencies")) return Set.of();
        var array = settings.getAsJsonArray("externalDependencies");
        var strings = new HashSet<String>();
        for (var each : array) {
            strings.add(each.getAsString());
        }
        return strings;
    }

    private Set<Path> classPath() {
        if (!settings.has("classPath")) return Set.of();
        var array = settings.getAsJsonArray("classPath");
        var paths = new HashSet<Path>();
        for (var each : array) {
            paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        }
        return paths;
    }

    private Set<String> extraCompilerArgs() {
        if (!settings.has("extraCompilerArgs")) return Set.of();
        var array = settings.getAsJsonArray("extraCompilerArgs");
        var args = new HashSet<String>();
        for (var each : array) {
            // split "a b  c" to ["a","b","c"]
            args.addAll(Arrays.asList(each.getAsString().split("\\s+")));
        }
        return args;
    }

    private Set<Path> docPath() {
        if (!settings.has("docPath")) return Set.of();
        var array = settings.getAsJsonArray("docPath");
        var paths = new HashSet<Path>();
        for (var each : array) {
            paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        }
        return paths;
    }

    private Set<String> addExports() {
        if (!settings.has("addExports")) return Set.of();
        var array = settings.getAsJsonArray("addExports");
        var strings = new HashSet<String>();
        for (var each : array) {
            strings.add(each.getAsString());
        }
        return strings;
    }

    private boolean lombokEnabled() {
        if (settings.has("lombokEnabled")) {
            return settings.get("lombokEnabled").getAsBoolean();
        }
        if (settings.has("lombok.enabled")) {
            return settings.get("lombok.enabled").getAsBoolean();
        }
        if (settings.has("lombok") && settings.get("lombok").isJsonObject()) {
            var lombok = settings.getAsJsonObject("lombok");
            if (lombok.has("enabled")) {
                return lombok.get("enabled").getAsBoolean();
            }
        }
        return true;
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = Paths.get(params.rootUri);
        FileStore.setWorkspaceRoots(Set.of(Paths.get(params.rootUri)));

        var c = new JsonObject();
        c.addProperty("textDocumentSync", 2); // Incremental
        c.addProperty("hoverProvider", true);
        var completionOptions = new JsonObject();
        completionOptions.addProperty("resolveProvider", true);
        var triggerCharacters = new JsonArray();
        triggerCharacters.add(".");
        completionOptions.add("triggerCharacters", triggerCharacters);
        c.add("completionProvider", completionOptions);
        var signatureHelpOptions = new JsonObject();
        var signatureTrigger = new JsonArray();
        signatureTrigger.add("(");
        signatureTrigger.add(",");
        signatureHelpOptions.add("triggerCharacters", signatureTrigger);
        c.add("signatureHelpProvider", signatureHelpOptions);
        c.addProperty("referencesProvider", true);
        c.addProperty("definitionProvider", true);
        c.addProperty("workspaceSymbolProvider", true);
        c.addProperty("documentSymbolProvider", true);
        c.addProperty("documentFormattingProvider", true);
        var codeLensOptions = new JsonObject();
        c.add("codeLensProvider", codeLensOptions);
        c.addProperty("foldingRangeProvider", true);
        // c.addProperty("inlayHintProvider", true);
        c.addProperty("codeActionProvider", true);
        var renameOptions = new JsonObject();
        renameOptions.addProperty("prepareProvider", true);
        c.add("renameProvider", renameOptions);

        return new InitializeResult(c);
    }

    private static final String[] watchFiles = {
        "**/*.java", "**/pom.xml", "**/BUILD", "**/javaconfig.json", "**/WORKSPACE"
    };

    @Override
    public void initialized() {
        client.registerCapability("workspace/didChangeWatchedFiles", watchFiles(watchFiles));
        perf.info("[perf] client_attached workspace=%s watchers=%d", workspaceRoot, watchFiles.length);
        compiler();
    }

    private JsonObject watchFiles(String... globPatterns) {
        var options = new JsonObject();
        var watchers = new JsonArray();
        for (var p : globPatterns) {
            var config = new JsonObject();
            config.addProperty("globPattern", p);
            watchers.add(config);
        }
        options.add("watchers", watchers);
        return options;
    }

    @Override
    public void shutdown() {
        synchronized (this) {
            if (pendingDiagnostics != null) {
                pendingDiagnostics.cancel(false);
                pendingDiagnostics = null;
            }
            if (pendingCompletionIndex != null) {
                pendingCompletionIndex.cancel(false);
                pendingCompletionIndex = null;
            }
        }
        diagnosticsExecutor.shutdownNow();
        completionIndexExecutor.shutdownNow();
        CacheAudit.logSummary(LOG);
    }

    public JavaLanguageServer(LanguageClient client) {
        this.client = client;
    }

    @Override
    public List<SymbolInformation> workspaceSymbols(WorkspaceSymbolParams params) {
        return new SymbolProvider(compiler()).findSymbols(params.query, 50);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var java = change.settings.getAsJsonObject().get("java");
        perf.info("Received java settings %s", java);
        if (java != null && !java.isJsonNull()) {
            settings = java.getAsJsonObject();
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        for (var c : params.changes) {
            var file = Paths.get(c.uri);
            if (FileStore.isJavaFile(file)) {
                var activeDocuments = FileStore.activeDocuments();
                var activeJavaDocument = activeDocuments.contains(file);
                var suppressActiveDocumentWork = activeJavaDocument && c.type != FileChangeType.Deleted;
                switch (c.type) {
                    case FileChangeType.Created:
                        // Some clients report save-on-open-file as "Created" for an existing path.
                        // Treat that as a normal change to avoid full project refresh churn.
                        if (activeJavaDocument || Files.exists(file)) {
                            FileStore.externalChange(file);
                            if (suppressActiveDocumentWork) {
                                perf.fine(
                                        "[perf] watched_java_change_skip reason=active_document event=created file="
                                                + file);
                            } else {
                                scheduleCompletionIndexRefresh(
                                        List.of(file),
                                        "didChangeWatchedFiles:javaCreatedExisting",
                                        COMPLETION_INDEX_DEBOUNCE_MS,
                                        CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
                            }
                        } else {
                            FileStore.externalCreate(file);
                            scheduleProjectCompletionIndexRefresh(
                                    "didChangeWatchedFiles:javaCreated", 0);
                        }
                        break;
                    case FileChangeType.Changed:
                        FileStore.externalChange(file);
                        if (suppressActiveDocumentWork) {
                            perf.fine(
                                    "[perf] watched_java_change_skip reason=active_document event=changed file="
                                            + file);
                        } else {
                            scheduleCompletionIndexRefresh(
                                    List.of(file),
                                    "didChangeWatchedFiles:javaChanged",
                                    COMPLETION_INDEX_DEBOUNCE_MS,
                                    CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
                        }
                        break;
                    case FileChangeType.Deleted:
                        FileStore.externalDelete(file);
                        scheduleProjectCompletionIndexRefresh("didChangeWatchedFiles:javaDeleted", 0);
                        break;
                }
                if (!activeDocuments.isEmpty()) {
                    if (suppressActiveDocumentWork) {
                        perf.fine(
                                "[perf] diagnostics_watched_skip reason=active_document file="
                                        + file);
                    } else {
                        markDirtyDiagnostics(activeDocuments, "didChangeWatchedFiles");
                        scheduleDiagnostics(activeDocuments, "didChangeWatchedFiles", DIAGNOSTIC_DEBOUNCE_MS);
                    }
                }
                return;
            }
            var name = file.getFileName().toString();
            switch (name) {
                case "BUILD":
                case "pom.xml":
                    perf.info("Compiler needs to be re-created because %s has changed", file);
                    modifiedBuild = true;
            }
        }
    }

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var started = Instant.now();
        var readiness = ensureTypeIndexReady("completionBootstrap", COMPLETION_BOOTSTRAP_WAIT_MS, false);
        var snapshot = completionSnapshot();
        var provider = new CompletionProvider(cacheCompiler, snapshot.typeIndex(), snapshot.version());
        var list = provider.complete(file, params.position.line + 1, params.position.character + 1);
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        perf.fineIfSlow(
                Duration.between(started, Instant.now()).toMillis(),
                "[perf] completion_request file=%s wait=%dms index_before=%d index_after=%d scope_before=%s scope_after=%s diagnostics_active=%s took=%dms",
                file.getFileName(),
                readiness.waitMs(),
                readiness.versionBefore(),
                readiness.versionAfter(),
                readiness.scopeBefore().name().toLowerCase(),
                readiness.scopeAfter().name().toLowerCase(),
                diagnosticsCompilesInFlight.get() > 0,
                Duration.between(started, Instant.now()).toMillis());
        return Optional.of(list);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        var snapshot = completionSnapshot();
        new HoverProvider(compiler(), snapshot.typeIndex()).resolveCompletionItem(unresolved);
        return unresolved;
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var snapshot = completionSnapshot();
        var content = new HoverProvider(compiler(), snapshot.typeIndex()).hover(file, line, column);
        if (content == null) {
            return Optional.empty();
        }
        // TODO add range
        return Optional.of(new Hover(content));
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var line = params.position.line + 1;
        var column = params.position.character + 1;
        var help = new SignatureProvider(compiler()).signatureHelp(file, line, column);
        if (help == SignatureProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(help);
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        ensureTypeIndexReady("definitionBootstrap", NAVIGATION_BOOTSTRAP_WAIT_MS, true);
        var snapshot = completionSnapshot();
        var found = new DefinitionProvider(compiler(), snapshot.typeIndex(), file, line, column).find();
        if (found == DefinitionProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public Optional<List<Location>> findReferences(ReferenceParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        ensureTypeIndexReady("referencesBootstrap", NAVIGATION_BOOTSTRAP_WAIT_MS, true);
        var snapshot = completionSnapshot();
        var includeDeclaration = position.context != null && position.context.includeDeclaration;
        var found =
                new ReferenceProvider(
                                compiler(), snapshot.typeIndex(), file, line, column, includeDeclaration)
                        .find();
        if (found == ReferenceProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public List<SymbolInformation> documentSymbol(DocumentSymbolParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new SymbolProvider(compiler()).documentSymbols(file);
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        var task = compiler().parse(file);
        return CodeLensProvider.find(task);
    }

    @Override
    public CodeLens resolveCodeLens(CodeLens unresolved) {
        return null;
    }

    @Override
    public List<TextEdit> formatting(DocumentFormattingParams params) {
        var edits = new ArrayList<TextEdit>();
        var file = Paths.get(params.textDocument.uri);
        var fixImports = new AutoFixImports(file).rewrite(compiler()).get(file);
        Collections.addAll(edits, fixImports);
        var addOverrides = new AutoAddOverrides(file).rewrite(compiler()).get(file);
        Collections.addAll(edits, addOverrides);
        return edits;
    }

    @Override
    public List<FoldingRange> foldingRange(FoldingRangeParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new FoldProvider(compiler()).foldingRanges(file);
    }

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        perf.info("Try to rename...");
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            long cursor;
            try {
                cursor =
                        FileStore.offset(
                                task.root().getSourceFile().getCharContent(true).toString(),
                                params.position.line + 1,
                                params.position.character + 1);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(task).scan(task.root(), cursor);
            if (path == null) {
                perf.info("...no element under cursor");
                return Optional.empty();
            }
            var el = Trees.instance(task.task).getElement(path);
            if (el == null) {
                perf.info("...couldn't resolve element");
                return Optional.empty();
            }
            if (!canRename(el)) {
                perf.info("...can't rename %s", el);
                return Optional.empty();
            }
            if (!canFindSource(el)) {
                perf.info("...can't find source for %s", el);
                return Optional.empty();
            }
            var response = new RenameResponse();
            response.range = FindHelper.location(task, path).range;
            response.placeholder = el.getSimpleName().toString();
            return Optional.of(response);
        }
    }

    private boolean canRename(Element rename) {
        return switch (rename.getKind()) {
            case METHOD, FIELD, LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER -> true;
            default ->
                // TODO rename other types
                    false;
        };
    }

    private boolean canFindSource(Element rename) {
        if (rename == null) return false;
        if (rename instanceof TypeElement type) {
            var name = type.getQualifiedName().toString();
            return compiler().findTypeDeclaration(name) != CompilerProvider.NOT_FOUND;
        }
        return canFindSource(rename.getEnclosingElement());
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        var rw = createRewrite(params);
        var response = new WorkspaceEdit();
        var map = rw.rewrite(compiler());
        for (var editedFile : map.keySet()) {
            response.changes.put(editedFile.toUri(), List.of(map.get(editedFile)));
        }
        return response;
    }

    private Rewrite createRewrite(RenameParams params) {
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            long position;
            try {
                position =
                        FileStore.offset(
                                task.root().getSourceFile().getCharContent(true).toString(),
                                params.position.line + 1,
                                params.position.character + 1);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(task).scan(task.root(), position);
            if (path == null) return Rewrite.NOT_SUPPORTED;
            var el = Trees.instance(task.task).getElement(path);
            return switch (el.getKind()) {
                case METHOD -> renameMethod(task, (ExecutableElement) el, params.newName);
                case FIELD -> renameField(task, (VariableElement) el, params.newName);
                case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER ->
                        renameVariable(task, (VariableElement) el, params.newName);
                default -> Rewrite.NOT_SUPPORTED;
            };
        }
    }

    private RenameMethod renameMethod(CompileTask task, ExecutableElement method, String newName) {
        var parent = (TypeElement) method.getEnclosingElement();
        var className = parent.getQualifiedName().toString();
        var methodName = method.getSimpleName().toString();
        var erasedParameterTypes = new String[method.getParameters().size()];
        for (var i = 0; i < erasedParameterTypes.length; i++) {
            var type = method.getParameters().get(i).asType();
            erasedParameterTypes[i] = task.task.getTypes().erasure(type).toString();
        }
        return new RenameMethod(className, methodName, erasedParameterTypes, newName);
    }

    private RenameField renameField(CompileTask task, VariableElement field, String newName) {
        var parent = (TypeElement) field.getEnclosingElement();
        var className = parent.getQualifiedName().toString();
        var fieldName = field.getSimpleName().toString();
        return new RenameField(className, fieldName, newName);
    }

    private RenameVariable renameVariable(CompileTask task, VariableElement variable, String newName) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(variable);
        var file = Paths.get(path.getCompilationUnit().getSourceFile().toUri());
        var position = trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf());
        return new RenameVariable(file, (int) position, newName);
    }

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        FileStore.open(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        if (completionSnapshot().version() == 0) {
            bootstrapWorkspaceOnDidOpen(file);
            return;
        }
        scheduleDiagnostics(List.of(file), "didOpen", DIAGNOSTIC_DEBOUNCE_MS);
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        var impact = analyzeActiveDocumentChange(file);
        markOtherActiveDiagnosticsDirty(file, "didChange");
        if (completionSnapshot().version() == 0) {
            scheduleActiveCompletionBootstrapIfNeeded("didChangeActiveBootstrap", 0);
        } else if (shouldRefreshCompletionIndexForActiveDocumentChange(impact)) {
            scheduleCompletionIndexRefresh(
                    List.of(file),
                    "didChange",
                    COMPLETION_INDEX_DEBOUNCE_MS,
                    CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
        }
        scheduleDiagnostics(List.of(file), "didChange", DIAGNOSTIC_DEBOUNCE_MS);
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);

        if (FileStore.isWorkspaceJavaFile(params.textDocument.uri)) {
            dirtyDiagnosticsFiles.remove(Paths.get(params.textDocument.uri));
            // Clear diagnostics
            client.publishDiagnostics(new PublishDiagnosticsParams(params.textDocument.uri, List.of()));
        }
    }

    @Override
    public List<CodeAction> codeAction(CodeActionParams params) {
        var provider = new CodeActionProvider(compiler());
        if (params.context.diagnostics.isEmpty()) {
            return provider.codeActionsForCursor(params);
        } else {
            return provider.codeActionForDiagnostics(params);
        }
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        if (FileStore.isWorkspaceJavaFile(params.textDocument.uri)) {
            var file = Paths.get(params.textDocument.uri);
            markOtherActiveDiagnosticsDirty(file, "didSave");
            cancelPendingDiagnostics("didSave");
            cancelPendingCompletionIndex("didSave");
            var selection = selectDiagnosticsCompiler();
            var batch = resolveDiagnosticsBatch(List.of(file), "didSave");
            didSaveDiagnosticsInFlight.incrementAndGet();
            try {
                compileAndPublish(
                        batch.files(),
                        selection.compiler,
                        "didSave",
                        -1,
                        batch.requestedCount(),
                        batch.dirtyOpenCount());
            } finally {
                didSaveDiagnosticsInFlight.updateAndGet(current -> Math.max(0, current - 1));
            }
            if (completionSnapshot().version() == 0 && !FileStore.activeDocuments().isEmpty()) {
                scheduleActiveCompletionBootstrapIfNeeded("didSaveBootstrap", 0);
            } else {
                scheduleCompletionIndexRefresh(
                        List.of(file),
                        "didSave",
                        0,
                        CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
            }
        }
    }

    /**
     * Shared timing/log helper for server orchestration logs.
     *
     * <p>Startup/configuration summaries stay at INFO. Short-lived internal timings can stay at
     * FINE, while especially noisy latency-only details can be gated through {@link #fineIfSlow}.
     */
    static final class ServerPerformanceLog {
        private static final long SLOW_ACTION_THRESHOLD_MS = 100;
        private final Logger log;

        ServerPerformanceLog(Logger log) {
            this.log = log;
        }

        void info(String format, Object... args) {
            log.info(args.length == 0 ? format : String.format(format, args));
        }

        void fine(String format, Object... args) {
            log.fine(args.length == 0 ? format : String.format(format, args));
        }

        void fineIfSlow(long elapsedMs, String format, Object... args) {
            if (elapsedMs < SLOW_ACTION_THRESHOLD_MS) {
                return;
            }
            fine(format, args);
        }
    }

    /**
     * Diagnostics scheduling and publication stay together so debounce, dirty-file tracking, and
     * publish behavior evolve as one unit.
     */
    final class DiagnosticsFlow {
        private final JavaLanguageServer server;
        private final ServerPerformanceLog perf;

        DiagnosticsFlow(JavaLanguageServer server, ServerPerformanceLog perf) {
            this.server = server;
            this.perf = perf;
        }

        static List<Path> normalizeJavaFiles(Collection<Path> files) {
            var javaFiles = new ArrayList<Path>();
            for (var file : files) {
                if (FileStore.isJavaFile(file)) {
                    javaFiles.add(file);
                }
            }
            return javaFiles;
        }

        void compileAndPublish(
                Collection<Path> files,
                JavaCompilerService diagnosticsCompiler,
                String trigger,
                long expectedContentRevision,
                int requestedCount,
                int dirtyOpenCount) {
            if (server.shouldYieldToDidSaveDiagnostics(trigger, "pre_lock")) {
                return;
            }
            var waitStarted = Instant.now();
            synchronized (server.diagnosticsCompileMutex) {
                if (server.shouldYieldToDidSaveDiagnostics(trigger, "post_lock")) {
                    return;
                }
                var waited = Duration.between(waitStarted, Instant.now()).toMillis();
                perf.fineIfSlow(
                        waited,
                        "[perf] diagnostics_compile_wait trigger=%s waited=%dms",
                        trigger,
                        waited);
                var javaFiles = normalizeJavaFiles(files);
                if (javaFiles.isEmpty()) return;
                perf.info("Lint %d files...", javaFiles.size());
                CompileTask task = null;
                try {
                    server.diagnosticsCompilesInFlight.incrementAndGet();
                    perf.fine(
                            "[perf] diagnostics_compile trigger=%s requested=%d dirty_open=%d batch=%d",
                            trigger,
                            requestedCount,
                            dirtyOpenCount,
                            javaFiles.size());
                    task =
                            diagnosticsCompiler.compileDiagnostics(
                                    javaFiles.stream().map(SourceFileObject::new).toList());
                    var compileTelemetry = diagnosticsCompiler.lastCompileTelemetry();
                    perf.fine(
                            "[perf] diagnostics_summary trigger=%s requested=%d dirty_open=%d batch=%d compiled_roots=%d ap=%s expanded=%d compiler_path=%s cache=%s parse=%dms enter=%dms analyze=%dms",
                            trigger,
                            requestedCount,
                            dirtyOpenCount,
                            javaFiles.size(),
                            task.roots.size(),
                            compileTelemetry.annotationProcessingEnabled(),
                            compileTelemetry.expandedSources(),
                            compileTelemetry.path(),
                            compileTelemetry.cacheName(),
                            compileTelemetry.parseMs(),
                            compileTelemetry.enterMs(),
                            compileTelemetry.analyzeMs());
                    if (server.shouldYieldToDidSaveDiagnostics(trigger, "post_compile")) {
                        return;
                    }
                    if (server.shouldSkipStaleDiagnostics(expectedContentRevision, trigger, "post_compile")) {
                        return;
                    }
                    publishFromTask(task, javaFiles, trigger, expectedContentRevision);
                    if (server.shouldSkipStaleDiagnostics(expectedContentRevision, trigger, "post_publish")) {
                        return;
                    }
                } finally {
                    server.diagnosticsCompilesInFlight.updateAndGet(current -> Math.max(0, current - 1));
                    if (task != null) {
                        task.close();
                    }
                }
            }
        }

        void publishFromTask(
                CompileTask task, Collection<Path> requestedFiles, String trigger, long expectedContentRevision) {
            var requestedJavaFiles = normalizeJavaFiles(requestedFiles);
            if (requestedJavaFiles.isEmpty()) {
                return;
            }
            var publishStarted = Instant.now();
            if (server.shouldSkipStaleDiagnostics(expectedContentRevision, trigger, "pre_publish")) {
                return;
            }
            if (server.shouldYieldToDidSaveDiagnostics(trigger, "pre_publish")) {
                return;
            }
            var diagnosticsCount = 0;
            var publishedUris = new HashSet<java.net.URI>();
            var requestedUris = new HashSet<java.net.URI>();
            for (var file : requestedJavaFiles) {
                requestedUris.add(file.toUri());
            }
            var materializeStarted = Instant.now();
            var report = new ErrorProvider(task).errors(requestedUris);
            var materializeMs = Duration.between(materializeStarted, Instant.now()).toMillis();
            var clientStarted = Instant.now();
            for (var errs : report.diagnostics()) {
                if (server.shouldSkipStaleDiagnostics(expectedContentRevision, trigger, "publish_loop")) {
                    return;
                }
                server.client.publishDiagnostics(errs);
                diagnosticsCount += errs.diagnostics.size();
                publishedUris.add(errs.uri);
            }
            for (var file : requestedJavaFiles) {
                if (server.shouldSkipStaleDiagnostics(expectedContentRevision, trigger, "publish_clear_loop")) {
                    return;
                }
                if (!FileStore.isJavaFile(file)) continue;
                var uri = file.toUri();
                if (publishedUris.contains(uri)) continue;
                server.client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
                publishedUris.add(uri);
            }
            perf.fine(
                    "[perf] diagnostics_publish trigger=%s requested_roots=%d compiled_roots=%d processed_roots=%d published_roots=%d diagnostics=%d convert=%dms warnings=%dms materialize=%dms client=%dms took=%dms",
                    trigger,
                    requestedJavaFiles.size(),
                    report.compiledRoots(),
                    report.processedRoots(),
                    publishedUris.size(),
                    diagnosticsCount,
                    report.convertMs(),
                    report.warningMs(),
                    materializeMs,
                    Duration.between(clientStarted, Instant.now()).toMillis(),
                    Duration.between(publishStarted, Instant.now()).toMillis());
            clearDirty(requestedJavaFiles);
        }

        void schedule(Collection<Path> files, String trigger, long delayMs) {
            var batch = resolveBatch(files, trigger);
            if (batch.files().isEmpty()) return;
            var request =
                    new ScheduledDiagnosticsRequest(
                            server.diagnosticsRevision.incrementAndGet(),
                            FileStore.contentRevision(),
                            batch.requestedCount(),
                            batch.dirtyOpenCount());
            var filesBatch = batch.files();
            synchronized (server) {
                if (server.pendingDiagnostics != null) {
                    server.pendingDiagnostics.cancel(false);
                }
                server.pendingDiagnostics =
                        server.diagnosticsExecutor.schedule(
                                () -> run(filesBatch, request, trigger),
                                delayMs,
                                TimeUnit.MILLISECONDS);
            }
            perf.fine(
                    "[perf] diagnostics_debounce trigger=%s files=%d delay=%dms revision=%d content_revision=%d",
                    trigger,
                    filesBatch.size(),
                    delayMs,
                    request.scheduleRevision(),
                    request.contentRevision());
        }

        void markDirty(Path file, String trigger) {
            if (server.dirtyDiagnosticsFiles.add(file)) {
                perf.fine(
                        "[perf] diagnostics_dirty_mark trigger=%s file=%s",
                        trigger,
                        file.getFileName());
            }
        }

        void markDirty(Collection<Path> files, String trigger) {
            var javaFiles = normalizeJavaFiles(files);
            if (javaFiles.isEmpty()) {
                return;
            }
            var marked = 0;
            for (var file : javaFiles) {
                if (server.dirtyDiagnosticsFiles.add(file)) {
                    marked++;
                }
            }
            if (marked > 0) {
                perf.fine("[perf] diagnostics_dirty_batch trigger=%s files=%d", trigger, marked);
            }
        }

        void clearDirty(Collection<Path> files) {
            for (var file : files) {
                server.dirtyDiagnosticsFiles.remove(file);
            }
        }

        void cancel(String reason) {
            synchronized (server) {
                if (server.pendingDiagnostics == null) {
                    return;
                }
                server.diagnosticsRevision.incrementAndGet();
                server.pendingDiagnostics.cancel(false);
                server.pendingDiagnostics = null;
            }
            perf.fine("[perf] diagnostics_cancel reason=%s", reason);
        }

        DiagnosticsBatch resolveBatch(Collection<Path> files, String trigger) {
            var requested = normalizeJavaFiles(files);
            if (requested.isEmpty()) {
                return new DiagnosticsBatch(List.of(), 0, 0);
            }
            var batch = new LinkedHashSet<>(requested);
            var activeJavaFiles = new LinkedHashSet<>(normalizeJavaFiles(FileStore.activeDocuments()));
            var dirtyOpenCount = 0;
            if (!activeJavaFiles.isEmpty()) {
                for (var dirty : server.dirtyDiagnosticsFiles) {
                    if (!activeJavaFiles.contains(dirty) || batch.contains(dirty)) {
                        continue;
                    }
                    batch.add(dirty);
                    dirtyOpenCount++;
                }
            }
            var filesBatch = List.copyOf(batch);
            perf.fine(
                    "[perf] diagnostics_batch trigger=%s requested=%d dirty_open=%d files=%d",
                    trigger,
                    requested.size(),
                    dirtyOpenCount,
                    filesBatch.size());
            return new DiagnosticsBatch(filesBatch, requested.size(), dirtyOpenCount);
        }

        void run(List<Path> files, ScheduledDiagnosticsRequest request, String trigger) {
            if (request.scheduleRevision() != server.diagnosticsRevision.get()) {
                return;
            }
            var selection = server.selectDiagnosticsCompiler();
            try {
                compileAndPublish(
                        files,
                        selection.compiler,
                        "async:" + trigger,
                        request.contentRevision(),
                        request.requestedCount(),
                        request.dirtyOpenCount());
            } catch (Exception e) {
                LOG.warning("Async lint failed for " + files + ": " + e.getMessage());
                LOG.log(java.util.logging.Level.FINE, "", e);
            }
        }
    }

    /**
     * Completion-index refresh/install logic is isolated so compiler recreation, active-document
     * merges, and full workspace bootstraps share one publication path.
     */
    final class CompletionIndexFlow {
        private final JavaLanguageServer server;
        private final ServerPerformanceLog perf;

        CompletionIndexFlow(JavaLanguageServer server, ServerPerformanceLog perf) {
            this.server = server;
            this.perf = perf;
        }

        void installTypeMemberIndex(
                WorkspaceTypeIndex nextIndex,
                long indexVersion,
                String trigger,
                Duration took,
                CompletionIndexScope scope) {
            var rebuilt = (nextIndex == null || nextIndex.size() == 0) ? WorkspaceTypeIndex.EMPTY : nextIndex;
            var currentSnapshot = server.completionSnapshot();
            server.publishCompletionSnapshot(
                    rebuilt,
                    currentSnapshot.externalIndex(),
                    indexVersion,
                    rebuilt == WorkspaceTypeIndex.EMPTY ? CompletionIndexScope.EMPTY : scope);
            perf.fineIfSlow(
                    took.toMillis(),
                    "[perf] completion_type_index trigger=%s version=%d types=%d took=%dms",
                    trigger,
                    indexVersion,
                    rebuilt.size(),
                    took.toMillis());
        }

        void installMergedTypeMemberIndex(
                WorkspaceTypeIndex deltaIndex,
                Collection<Path> replacedFiles,
                long indexVersion,
                String trigger,
                Duration took) {
            var baseSnapshot = server.completionSnapshot();
            var merged =
                    baseSnapshot
                            .workspaceIndex()
                            .replaceWorkspaceDeclarations(
                                    deltaIndex == null ? WorkspaceTypeIndex.EMPTY : deltaIndex,
                                    new LinkedHashSet<>(replacedFiles));
            var nextScope =
                    merged == WorkspaceTypeIndex.EMPTY
                            ? CompletionIndexScope.EMPTY
                            : baseSnapshot.scope() == CompletionIndexScope.EMPTY
                                    ? CompletionIndexScope.ACTIVE
                                    : baseSnapshot.scope();
            server.publishCompletionSnapshot(merged, baseSnapshot.externalIndex(), indexVersion, nextScope);
            perf.fineIfSlow(
                    took.toMillis(),
                    "[perf] completion_type_index_merge trigger=%s base_version=%d version=%d types=%d files=%d took=%dms",
                    trigger,
                    baseSnapshot.version(),
                    indexVersion,
                    merged.size(),
                    replacedFiles.size(),
                    took.toMillis());
        }

        void scheduleProjectRefresh(String trigger, long delayMs) {
            scheduleRefresh(FileStore.all(), trigger, delayMs, CompletionIndexRefreshMode.FULL_REBUILD);
        }

        void scheduleActiveBootstrapIfNeeded(String trigger, long delayMs) {
            if (server.completionSnapshot().scope() != CompletionIndexScope.EMPTY) {
                return;
            }
            synchronized (server) {
                if (server.pendingCompletionIndex != null && !server.pendingCompletionIndex.isDone()) {
                    return;
                }
            }
            scheduleRefresh(FileStore.all(), trigger, delayMs, CompletionIndexRefreshMode.FULL_REBUILD);
        }

        void scheduleProjectBootstrapIfNeeded(String trigger, long delayMs) {
            if (server.completionSnapshot().scope() == CompletionIndexScope.WORKSPACE) {
                return;
            }
            scheduleProjectRefresh(trigger, delayMs);
        }

        void scheduleRefresh(
                Collection<Path> files, String trigger, long delayMs, CompletionIndexRefreshMode mode) {
            var javaFiles = DiagnosticsFlow.normalizeJavaFiles(files);
            if (javaFiles.isEmpty()) {
                return;
            }
            var revision = server.completionIndexRevision.incrementAndGet();
            var filesBatch = List.copyOf(javaFiles);
            synchronized (server) {
                if (server.pendingCompletionIndex != null) {
                    server.pendingCompletionIndex.cancel(false);
                }
                server.pendingCompletionIndex =
                        server.completionIndexExecutor.schedule(
                                () -> runRefresh(filesBatch, revision, trigger, mode),
                                delayMs,
                                TimeUnit.MILLISECONDS);
            }
            if (delayMs == 0
                    || "didSave".equals(trigger)
                    || mode == CompletionIndexRefreshMode.FULL_REBUILD
                    || filesBatch.size() > 1) {
                perf.fine(
                        "[perf] completion_index_debounce trigger=%s files=%d mode=%s delay=%dms revision=%d",
                        trigger,
                        filesBatch.size(),
                        mode.name().toLowerCase(),
                        delayMs,
                        revision);
            }
        }

        void runRefresh(List<Path> files, long revision, String trigger, CompletionIndexRefreshMode mode) {
            if (revision != server.completionIndexRevision.get()) {
                return;
            }
            var workspaceBootstrap =
                    mode != CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE
                            && server.completionSnapshot().scope() == CompletionIndexScope.EMPTY;
            synchronized (server.completionIndexCompileMutex) {
                var started = Instant.now();
                CompileTask task = null;
                try {
                    if (workspaceBootstrap) {
                        perf.info("[perf] workspace bootstrap started trigger=%s files=%d", trigger, files.size());
                    }
                    task = server.compiler().compileFast(files.toArray(Path[]::new));
                    if (revision != server.completionIndexRevision.get()) {
                        perf.fine(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_compile expected=%d current=%d",
                                trigger,
                                revision,
                                server.completionIndexRevision.get());
                        return;
                    }
                    var indexStarted = Instant.now();
                    var nextIndex = build(task, mode);
                    if (revision != server.completionIndexRevision.get()) {
                        perf.fine(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_index expected=%d current=%d",
                                trigger,
                                revision,
                                server.completionIndexRevision.get());
                        return;
                    }
                    var indexVersion = server.nextIndexVersion();
                    install(
                            nextIndex,
                            files,
                            mode,
                            indexVersion,
                            "index:" + trigger,
                            Duration.between(indexStarted, Instant.now()));
                    if (workspaceBootstrap) {
                        perf.info(
                                "[perf] workspace index installed trigger=%s version=%d types=%d",
                                trigger,
                                indexVersion,
                                nextIndex == null ? 0 : nextIndex.size());
                    }
                    var totalMs = Duration.between(started, Instant.now()).toMillis();
                    perf.fineIfSlow(
                            totalMs,
                            "[perf] completion_index_refresh trigger=%s files=%d version=%d mode=%s compile=%dms total=%dms",
                            trigger,
                            files.size(),
                            indexVersion,
                            mode.name().toLowerCase(),
                            Duration.between(started, indexStarted).toMillis(),
                            totalMs);
                } catch (Exception e) {
                    LOG.warning(
                            String.format(
                                    "[completion] index refresh failed trigger=%s files=%d reason=%s",
                                    trigger,
                                    files.size(),
                                    e.getMessage()));
                    LOG.log(java.util.logging.Level.FINE, "", e);
                } finally {
                    if (task != null) {
                        task.close();
                    }
                }
            }
        }

        void install(
                WorkspaceTypeIndex nextIndex,
                Collection<Path> files,
                CompletionIndexRefreshMode mode,
                long indexVersion,
                String trigger,
                Duration took) {
            if (mode == CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                installMergedTypeMemberIndex(nextIndex, files, indexVersion, trigger, took);
                return;
            }
            var scope =
                    mode == CompletionIndexRefreshMode.FULL_REBUILD
                            ? CompletionIndexScope.WORKSPACE
                            : CompletionIndexScope.ACTIVE;
            installTypeMemberIndex(nextIndex, indexVersion, trigger, took, scope);
        }

        WorkspaceTypeIndex build(CompileTask task, CompletionIndexRefreshMode mode) {
            if (mode == CompletionIndexRefreshMode.ACTIVE_DOCUMENT_BOOTSTRAP) {
                return WorkspaceTypeIndex.from(task)
                        .filterTypes(
                                info ->
                                        info.sourcePath != null
                                                || server.compiler().findTypeDeclaration(info.qualifiedName)
                                                        != CompilerProvider.NOT_FOUND);
            }
            return WorkspaceTypeIndex.workspaceDeclarations(task);
        }

        void cancel(String reason) {
            synchronized (server) {
                if (server.pendingCompletionIndex == null) {
                    return;
                }
                server.completionIndexRevision.incrementAndGet();
                server.pendingCompletionIndex.cancel(false);
                server.pendingCompletionIndex = null;
            }
            perf.fine("[perf] completion_index_cancel reason=%s", reason);
        }
    }
}
