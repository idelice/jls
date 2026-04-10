package org.javacs;

import com.google.gson.*;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
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
import org.javacs.index.IndexedType;
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

    private static final long DIAGNOSTIC_DEBOUNCE_MS = 250;
    private static final long COMPLETION_INDEX_DEBOUNCE_MS = 100;
    private static final long COMPLETION_BOOTSTRAP_WAIT_MS = 700;
    private static final long COMPLETION_BOOTSTRAP_POLL_MS = 25;
    private static final long NAVIGATION_BOOTSTRAP_WAIT_MS = 1500;

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

    // TODO allow multiple workspace roots
    private Path workspaceRoot;
    private final LanguageClient client;
    private final DiagnosticsScheduler diagnosticsScheduler = new DiagnosticsScheduler();
    private final CompletionIndexScheduler completionIndexScheduler = new CompletionIndexScheduler();

    // Interactive requests reuse the cache compiler, while diagnostics batches stay on a separate
    // compiler service so foreground lookup and background publication do not fight over one task.
    private JavaCompilerService cacheCompiler;
    private JavaCompilerService diagnosticsCompiler;

    private JsonObject appliedCompilerSettings = new JsonObject();
    private JsonObject settings = new JsonObject();
    private boolean workDoneProgressSupported;

    private final ScheduledExecutorService diagnosticsExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("javacs-diagnostics").factory());
    private final ScheduledExecutorService completionIndexExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("javacs-completion-index").factory());

    /** Monotonic revision for queued async diagnostics work; newer schedules cancel older runs. */
    private final AtomicLong diagnosticsRevision = new AtomicLong();
    /** Monotonic revision for queued completion-index refreshes; newer schedules cancel older runs. */
    private final AtomicLong completionIndexRevision = new AtomicLong();
    private final AtomicLong completionIndexVersion = new AtomicLong();
    private final AtomicReference<CompletionSnapshot> completionSnapshotRef =
            new AtomicReference<>(CompletionSnapshot.EMPTY);
    private final AtomicInteger diagnosticsCompilesInFlight = new AtomicInteger();

    /** Serialize diagnostics compilation/publish so one batch owns the diagnostics compiler at a time. */
    private final Object diagnosticsCompileMutex = new Object();
    /** Serialize completion-index refreshes so one compile/index install owns the refresh lane. */
    private final Object completionIndexCompileMutex = new Object();

    private ScheduledFuture<?> pendingDiagnostics;
    private ScheduledFuture<?> pendingCompletionIndex;

    private final Set<Path> dirtyDiagnosticsFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Foreground didSave diagnostics take priority over queued async publishes; async work checks
     * this counter and yields instead of racing the explicit save request.
     */
    private final AtomicInteger didSaveDiagnosticsInFlight = new AtomicInteger();
    private final Set<String> shownWorkspaceWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    /** Return the current interactive compiler. Compiler recreation happens at explicit event boundaries. */
    synchronized JavaCompilerService getOrCreateCompiler() {
        Objects.requireNonNull(cacheCompiler, "Compiler has not been initialized");
        return cacheCompiler;
    }

    private void publishCompletionSnapshot(
            WorkspaceTypeIndex workspaceIndex,
            ExternalBinaryTypeIndex externalIndex,
            long version,
            CompletionIndexScope scope) {
        var snapshot = CompletionSnapshot.create(workspaceIndex, externalIndex, version, scope);
        completionIndexVersion.set(snapshot.version());
        completionSnapshotRef.set(snapshot);
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

    private void publishExternalBinaryIndexSnapshot() {
        var currentSnapshot = completionSnapshotRef.get();
        publishCompletionSnapshot(
                currentSnapshot.workspaceIndex(),
                new ExternalBinaryTypeIndex(getOrCreateCompiler()),
                currentSnapshot.version(),
                currentSnapshot.scope());
    }

    private synchronized void initializeCompilers() {
        createCompilers();
        appliedCompilerSettings = compilerSettingsSnapshot(settings);
        publishExternalBinaryIndexSnapshot();
    }

    private synchronized void recreateCompilersAndRefreshState(String trigger) {
        LOG.info(String.format("[perf] compiler_recreate trigger=%s", trigger));
        diagnosticsScheduler.cancel(trigger);
        completionIndexScheduler.cancel(trigger);
        createCompilers();
        appliedCompilerSettings = compilerSettingsSnapshot(settings);
        publishExternalBinaryIndexSnapshot();
        refreshStateForCompilerRecreated();
    }

    private void refreshStateForCompilerRecreated() {
        var currentSnapshot = completionSnapshotRef.get();
        var active = filterJavaFiles(FileStore.activeDocuments());
        if (active.isEmpty()) {
            if (currentSnapshot.scope() != CompletionIndexScope.EMPTY) {
                publishCompletionSnapshot(
                        WorkspaceTypeIndex.EMPTY,
                        currentSnapshot.externalIndex(),
                        currentSnapshot.version(),
                        CompletionIndexScope.EMPTY);
            }
            LOG.fine("[perf] completion_index_refresh_deferred trigger=compilerRecreated reason=no_active_docs");
            return;
        }
        if (currentSnapshot.scope() == CompletionIndexScope.EMPTY) {
            LOG.fine("[perf] completion_index_refresh_deferred trigger=compilerRecreated reason=empty_scope");
            return;
        }
        diagnosticsScheduler.schedule(active, "compilerRecreated", 0);
        completionIndexScheduler.scheduleRefresh(
                FileStore.all(), "compilerRecreated", 0, CompletionIndexRefreshMode.FULL_REBUILD);
    }

    void lint(Collection<Path> files) {
        var javaFiles = filterJavaFiles(files);
        if (javaFiles.isEmpty()) {
            return;
        }
        diagnosticsScheduler.cancel("foreground");
        completionIndexScheduler.cancel("foreground");
        getOrCreateCompiler();
        diagnosticsScheduler.compileAndPublish(javaFiles, diagnosticsCompiler, "foreground", -1, javaFiles.size(), 0);
        if (completionSnapshotRef.get().scope() == CompletionIndexScope.EMPTY
                && !FileStore.activeDocuments().isEmpty()) {
            completionIndexScheduler.scheduleActiveBootstrapIfNeeded("lintBootstrap");
        }
    }

    private boolean analyzeActiveDocumentChange(
            Path file, List<TextDocumentContentChangeEvent> contentChanges) {
        try {
            var parse = getOrCreateCompiler().parse(file);
            var contents = FileStore.contents(file);
            if (hasLikelyIncompleteSource(FileStore.contents(file))) {
                LOG.fine(
                        "[perf] completion_index_didChange_skip file="
                                + file.getFileName()
                                + " reason=incomplete_source");
                return false;
            }
            if (contentChanges != null && !contentChanges.isEmpty()) {
                var positions = Trees.instance(parse.task()).getSourcePositions();
                var spans = new ArrayList<long[]>();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        if (method.getBody() != null) {
                            addSpan(method.getBody());
                        }
                        return super.visitMethod(method, unused);
                    }

                    @Override
                    public Void visitBlock(BlockTree block, Void unused) {
                        var parent = getCurrentPath().getParentPath();
                        if (parent != null && parent.getLeaf() instanceof ClassTree) {
                            addSpan(block);
                        }
                        return super.visitBlock(block, unused);
                    }

                    @Override
                    public Void visitVariable(VariableTree variable, Void unused) {
                        var parent = getCurrentPath().getParentPath();
                        if (parent != null
                                && parent.getLeaf() instanceof ClassTree
                                && variable.getInitializer() != null) {
                            addSpan(variable.getInitializer());
                        }
                        return super.visitVariable(variable, unused);
                    }

                    private void addSpan(com.sun.source.tree.Tree tree) {
                        var start = positions.getStartPosition(parse.root(), tree);
                        var end = positions.getEndPosition(parse.root(), tree);
                        if (start >= 0 && end >= start) {
                            spans.add(new long[] {start, end});
                        }
                    }
                }.scan(parse.root(), null);
                if (!spans.isEmpty()) {
                    var executableOnlyChange = true;
                    for (var change : contentChanges) {
                        if (change.range == null) {
                            executableOnlyChange = false;
                            break;
                        }
                        var start =
                                FileStore.offset(
                                        contents,
                                        change.range.start.line + 1,
                                        change.range.start.character + 1);
                        var end =
                                FileStore.offset(
                                        contents,
                                        change.range.end.line + 1,
                                        change.range.end.character + 1);
                        if (end < start) {
                            end = start;
                        }
                        var covered = false;
                        for (var span : spans) {
                            if (start >= span[0] && end <= span[1]) {
                                covered = true;
                                break;
                            }
                        }
                        if (!covered) {
                            executableOnlyChange = false;
                            break;
                        }
                    }
                    if (executableOnlyChange) {
                        LOG.fine(
                                "[perf] completion_index_didChange_skip file="
                                        + file.getFileName()
                                        + " reason=executable_only_change");
                        return false;
                    }
                }
            }
            var shapes = declaredTypeShapes(parse);
            var hasStructuralLombokType = shapes.stream().anyMatch(DeclaredTypeShape::structuralLombok);
            if (hasStructuralLombokType) {
                LOG.fine(
                        "[perf] completion_index_didChange_refresh file="
                                + file.getFileName()
                                + " reason=structural_lombok");
                return true;
            }
            var declarationDrift = hasDeclarationDrift(file, shapes);
            if (declarationDrift) {
                LOG.fine(
                        "[perf] completion_index_didChange_refresh file="
                                + file.getFileName()
                                + " reason=declaration_drift");
            }
            return declarationDrift;
        } catch (RuntimeException e) {
            LOG.fine(String.format(
                    "[perf] completion_index_didChange_skip file=%s reason=parse_failed detail=%s",
                    file.getFileName(), e.getMessage()));
        }
        return false;
    }

    private static List<Path> filterJavaFiles(Collection<Path> files) {
        var javaFiles = new ArrayList<Path>();
        for (var file : files) {
            if (FileStore.isJavaFile(file)) {
                javaFiles.add(file);
            }
        }
        return javaFiles;
    }

    /**
     * Heuristic used during active edits to avoid refreshing declaration indexes from obviously
     * half-typed source.
     */
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
        var activeJavaFiles = filterJavaFiles(FileStore.activeDocuments());
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
        var indexedTypes = new LinkedHashMap<String, IndexedType>();
        for (var type : completionSnapshotRef.get().workspaceIndex().types().values()) {
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

    private List<String> indexedDirectMemberSignatures(IndexedType type) {
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

    /**
     * Mark the changed file and any other open Java files as needing a fresh diagnostics publish.
     *
     * <p>The next scheduled diagnostics batch keeps the explicit requested file first and then pulls
     * in any other open files that were marked dirty by earlier edits or saves.
     */
    private void markOtherActiveDiagnosticsDirty(Path file, String trigger) {
        diagnosticsScheduler.markDirty(List.of(file), trigger);
        var others = otherActiveDocuments(file);
        if (!others.isEmpty()) {
            diagnosticsScheduler.markDirty(others, trigger + ":openFiles");
            LOG.fine(String.format(
                    "[perf] diagnostics_dirty_open_files trigger=%s file=%s files=%d",
                    trigger, file.getFileName(), others.size()));
        }
    }

    /**
     * Let queued async diagnostics yield to an active didSave compile/publish cycle so the explicit
     * save request wins.
     */
    private boolean shouldYieldToDidSaveDiagnostics(String trigger, String phase) {
        if (!trigger.startsWith("async:")) {
            return false;
        }
        if (didSaveDiagnosticsInFlight.get() <= 0) {
            return false;
        }
        LOG.fine(String.format(
                "[perf] diagnostics_yield trigger=%s phase=%s reason=didSave_pending",
                trigger, phase));
        return true;
    }

    private void bootstrapWorkspaceOnDidOpen(Path file) {
        var workspaceFiles = filterJavaFiles(FileStore.all());
        if (workspaceFiles.isEmpty()) {
            return;
        }
        var bootstrapStarted = Instant.now();
        var progressToken = beginWorkDoneProgress("Bootstrap workspace", "Compiling workspace");
        var progressEnded = false;
        getOrCreateCompiler();
        diagnosticsScheduler.cancel("didOpenBootstrap");
        completionIndexScheduler.cancel("didOpenBootstrap");
        LOG.info(String.format(
                "[perf] workspace bootstrap started trigger=didOpenBootstrap files=%d",
                workspaceFiles.size()));
        CompileTask task = null;
        synchronized (diagnosticsCompileMutex) {
            try {
                var compileStarted = Instant.now();
                task =
                        diagnosticsCompiler.compileDiagnostics(
                                workspaceFiles.stream().map(SourceFileObject::new).toList());
                var compileFinished = Instant.now();
                var indexStarted = Instant.now();
                reportWorkDoneProgress(progressToken, "Building workspace index");
                var nextIndex = WorkspaceTypeIndex.workspaceDeclarations(task);
                var indexFinished = Instant.now();
                var indexVersion = completionIndexVersion.incrementAndGet();
                completionIndexScheduler.installTypeMemberIndex(
                        nextIndex,
                        indexVersion,
                        "didOpenBootstrap",
                        Duration.between(indexStarted, indexFinished),
                        CompletionIndexScope.WORKSPACE);
                var publishStarted = Instant.now();
                reportWorkDoneProgress(progressToken, "Publishing diagnostics");
                diagnosticsScheduler.publishFromTask(task, List.of(file), "didOpenBootstrap", -1);
                var finished = Instant.now();
                endWorkDoneProgress(progressToken, "Workspace ready");
                progressEnded = true;
                LOG.info(String.format(
                        "[perf] workspace index installed trigger=didOpenBootstrap version=%d types=%d compile=%dms index=%dms publish=%dms total=%dms",
                        indexVersion,
                        nextIndex == null ? 0 : nextIndex.size(),
                        Duration.between(compileStarted, compileFinished).toMillis(),
                        Duration.between(indexStarted, indexFinished).toMillis(),
                        Duration.between(publishStarted, finished).toMillis(),
                        Duration.between(bootstrapStarted, finished).toMillis()));
            } finally {
                if (!progressEnded) {
                    endWorkDoneProgress(progressToken, "Workspace bootstrap finished");
                }
                if (task != null) {
                    task.close();
                }
            }
        }
    }

    /**
     * Wait briefly for an asynchronously scheduled completion-index bootstrap to publish a newer
     * snapshot version.
     */
    private long awaitCompletionBootstrap(long initialIndexVersion, long timeoutMs) {
        var started = System.nanoTime();
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (completionSnapshotRef.get().version() == initialIndexVersion && System.nanoTime() < deadline) {
            try {
                Thread.sleep(COMPLETION_BOOTSTRAP_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            }
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /**
     * Ensure the type index is available for a request, scheduling bootstrap and waiting briefly
     * when the current snapshot is still empty or below the required scope.
     */
    private TypeIndexAvailability ensureTypeIndexReady(String trigger, long waitMs, boolean requireWorkspaceScope) {
        getOrCreateCompiler();
        var snapshot = completionSnapshotRef.get();
        var initialIndexVersion = snapshot.version();
        var currentScope = snapshot.scope();
        var hasIndex = initialIndexVersion != 0;
        var hasRequiredScope = !requireWorkspaceScope || currentScope == CompletionIndexScope.WORKSPACE;
        if (hasIndex && hasRequiredScope) {
            return new TypeIndexAvailability(initialIndexVersion, initialIndexVersion, currentScope, currentScope, 0);
        }
        LOG.fine(String.format("[perf] completion_index_bootstrap trigger=%s", trigger));
        var needsWorkspaceBootstrap = requireWorkspaceScope || FileStore.activeDocuments().isEmpty();
        if (needsWorkspaceBootstrap) {
            completionIndexScheduler.scheduleProjectBootstrapIfNeeded(trigger);
        } else {
            completionIndexScheduler.scheduleActiveBootstrapIfNeeded(trigger);
        }
        var waited = awaitCompletionBootstrap(initialIndexVersion, waitMs);
        var updated = completionSnapshotRef.get();
        return new TypeIndexAvailability(
                initialIndexVersion,
                updated.version(),
                currentScope,
                updated.scope(),
                waited);
    }

    /**
     * Ignore diagnostics work produced for an older document revision after the file changed again.
     *
     * <p>The same check is intentionally repeated before publish and during the publish loops
     * because file content can change while diagnostics are being materialized or sent to the
     * client.
     */
    private boolean shouldSkipStaleDiagnostics(long expectedContentRevision, String trigger, String phase) {
        var current = FileStore.contentRevision();
        if (!isStaleDiagnosticsContent(expectedContentRevision, current)) {
            return false;
        }
        LOG.fine(String.format(
                "[perf] diagnostics_skip_stale trigger=%s phase=%s expected_content=%d current_content=%d",
                trigger, phase, expectedContentRevision, current));
        return true;
    }

    static boolean isStaleDiagnosticsContent(long expectedContentRevision, long currentContentRevision) {
        return expectedContentRevision >= 0 && expectedContentRevision != currentContentRevision;
    }

    /**
     * Recreate the paired compiler services used for interactive requests and diagnostics
     * publication.
     */
    private void createCompilers() {
        Objects.requireNonNull(workspaceRoot, "Can't create compiler because workspaceRoot has not been initialized");
        var started = Instant.now();
        var progressToken = beginWorkDoneProgress("Configure javac", "Finding source roots");

        var externalDependencies = externalDependencies();
        var classPath = classPath();
        var userExtraArgs = extraCompilerArgs();
        var addExports = addExports();
        var lombokEnabled = lombokEnabled();
        var compilerArgs = selectCompilerArgs(userExtraArgs, externalDependencies);
        var extraArgs = compilerArgs.args();
        var settingsLoaded = Instant.now();
        Set<Path> resolvedDocPath;
        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            resolvedDocPath = docPath();
            LOG.info(String.format(
                    "[perf] compiler_config_inference mode=explicit classpath=%d docpath=%d took=%dms",
                    classPath.size(),
                    resolvedDocPath.size(),
                    Duration.between(settingsLoaded, Instant.now()).toMillis()));
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var infer = new InferConfig(workspaceRoot, externalDependencies);

            reportWorkDoneProgress(progressToken, "Inferring class path");
            var inferClassPathStarted = Instant.now();
            classPath = infer.classPath();
            var inferredClassPath = Instant.now();

            reportWorkDoneProgress(progressToken, "Inferring doc path");
            var inferDocPathStarted = Instant.now();
            resolvedDocPath = infer.buildDocPath();
            LOG.info(String.format(
                    "[perf] compiler_config_inference mode=inferred external=%d classpath=%d docpath=%d classpath_infer=%dms docpath_infer=%dms total=%dms",
                    externalDependencies.size(),
                    classPath.size(),
                    resolvedDocPath.size(),
                    Duration.between(inferClassPathStarted, inferredClassPath).toMillis(),
                    Duration.between(inferDocPathStarted, Instant.now()).toMillis(),
                    Duration.between(settingsLoaded, Instant.now()).toMillis()));
        }
        var inferenceFinished = Instant.now();
        endWorkDoneProgress(progressToken, "Configured javac");
        LOG.info(String.format("[perf] lombok_setting enabled=%s", lombokEnabled));
        LOG.info(
                String.format(
                        "[perf] compiler_args source=%s count=%d mixed_modules=%s",
                        compilerArgs.source(),
                        extraArgs.size(),
                        compilerArgs.mixedModules()));
        LOG.info(String.format("[perf] compiler_args_values args=%s", extraArgs));
        cacheCompiler =
                new JavaCompilerService(
                        classPath,
                        resolvedDocPath,
                        addExports,
                        extraArgs,
                        lombokEnabled,
                        "interactive");
        var diagnosticsStarted = Instant.now();
        diagnosticsCompiler =
                new JavaCompilerService(
                        classPath,
                        resolvedDocPath,
                        addExports,
                        extraArgs,
                        lombokEnabled,
                        "diagnostics");
        LOG.info(String.format(
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

    private List<String> extraCompilerArgs() {
        if (!settings.has("extraCompilerArgs")) return List.of();
        var array = settings.getAsJsonArray("extraCompilerArgs");
        var args = new ArrayList<String>();
        for (var each : array) {
            // split "a b  c" to ["a","b","c"]
            args.addAll(Arrays.asList(each.getAsString().trim().split("\\s+")));
        }
        return List.copyOf(args);
    }

    private InferConfig.MavenCompilerArgs selectCompilerArgs(List<String> userExtraArgs, Set<String> externalDependencies) {
        if (hasExplicitJavaLevelOverride(userExtraArgs)) {
            return new InferConfig.MavenCompilerArgs(List.copyOf(userExtraArgs), "user", false);
        }
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(pomXml)) {
            return new InferConfig.MavenCompilerArgs(List.copyOf(userExtraArgs), "none", false);
        }
        var inferred = new InferConfig(workspaceRoot, externalDependencies).compilerArgs();
        if (inferred.mixedModules()) {
            warnUserOnce(
                    "maven_mixed_release_fallback",
                    "JLS detected mixed Maven module Java levels and fell back to the runtime/default compiler behavior for this workspace.");
            return new InferConfig.MavenCompilerArgs(List.copyOf(userExtraArgs), "fallback_mixed_modules", true);
        }
        if (inferred.args().isEmpty()) {
            return new InferConfig.MavenCompilerArgs(List.copyOf(userExtraArgs), "none", false);
        }
        var merged = new ArrayList<String>(userExtraArgs);
        merged.addAll(inferred.args());
        return new InferConfig.MavenCompilerArgs(List.copyOf(merged), inferred.source(), false);
    }

    private static boolean hasExplicitJavaLevelOverride(List<String> extraArgs) {
        for (var i = 0; i < extraArgs.size(); i++) {
            var arg = extraArgs.get(i);
            if ("--release".equals(arg) || "-source".equals(arg) || "-target".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private void warnUserOnce(String key, String message) {
        if (!shownWorkspaceWarnings.add(key)) {
            return;
        }
        var params = new ShowMessageParams();
        params.type = MessageType.Warning;
        params.message = message;
        client.showMessage(params);
        LOG.warning(message);
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

    private static boolean supportsWorkDoneProgress(JsonElement capabilities) {
        if (capabilities == null || !capabilities.isJsonObject()) {
            return false;
        }
        var root = capabilities.getAsJsonObject();
        if (!root.has("window") || !root.get("window").isJsonObject()) {
            return false;
        }
        var window = root.getAsJsonObject("window");
        return window.has("workDoneProgress") && window.get("workDoneProgress").getAsBoolean();
    }

    private String beginWorkDoneProgress(String title, String message) {
        if (!workDoneProgressSupported) {
            return null;
        }
        var token = UUID.randomUUID().toString();
        var create = new JsonObject();
        create.addProperty("token", token);
        client.sendRequest("window/workDoneProgress/create", create);

        var value = new JsonObject();
        value.addProperty("kind", "begin");
        value.addProperty("title", title);
        value.addProperty("message", message);
        value.addProperty("cancellable", false);
        var progress = new JsonObject();
        progress.addProperty("token", token);
        progress.add("value", value);
        client.customNotification("$/progress", progress);
        return token;
    }

    private void reportWorkDoneProgress(String token, String message) {
        if (token == null) {
            return;
        }
        var value = new JsonObject();
        value.addProperty("kind", "report");
        value.addProperty("message", message);
        value.addProperty("cancellable", false);
        var progress = new JsonObject();
        progress.addProperty("token", token);
        progress.add("value", value);
        client.customNotification("$/progress", progress);
    }

    private void endWorkDoneProgress(String token, String message) {
        if (token == null) {
            return;
        }
        var value = new JsonObject();
        value.addProperty("kind", "end");
        if (message != null && !message.isBlank()) {
            value.addProperty("message", message);
        }
        var progress = new JsonObject();
        progress.addProperty("token", token);
        progress.add("value", value);
        client.customNotification("$/progress", progress);
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = Paths.get(params.rootUri);
        this.workDoneProgressSupported = supportsWorkDoneProgress(params.capabilities);
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
        "**/*.java",
        "**/pom.xml",
        "**/BUILD",
        "**/WORKSPACE",
        "**/javaconfig.json",
        "**/build.gradle",
        "**/build.gradle.kts",
        "**/settings.gradle",
        "**/settings.gradle.kts"
    };

    @Override
    public void initialized() {
        var options = new JsonObject();
        var watchers = new JsonArray();
        for (var pattern : watchFiles) {
            var config = new JsonObject();
            config.addProperty("globPattern", pattern);
            watchers.add(config);
        }
        options.add("watchers", watchers);
        client.registerCapability("workspace/didChangeWatchedFiles", options);
        LOG.info(String.format("[perf] client_attached workspace=%s watchers=%d", workspaceRoot, watchFiles.length));
        initializeCompilers();
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
        return new SymbolProvider(getOrCreateCompiler()).findSymbols(params.query, 50);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var java = change.settings.getAsJsonObject().get("java");
        LOG.info(String.format("Received java settings %s", java));
        var nextSettings = new JsonObject();
        if (java != null && !java.isJsonNull()) {
            nextSettings = java.getAsJsonObject();
        }
        var nextCompilerSettings = compilerSettingsSnapshot(nextSettings);
        settings = nextSettings;
        if (!nextCompilerSettings.equals(appliedCompilerSettings)) {
            recreateCompilersAndRefreshState("didChangeConfiguration");
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        var activeDocuments = FileStore.activeDocuments();
        var refreshActiveDiagnostics = false;
        var compilerInputsChanged = false;
        for (var c : params.changes) {
            var file = Paths.get(c.uri);
            if (FileStore.isJavaFile(file)) {
                var activeJavaDocument = activeDocuments.contains(file);
                var suppressActiveDocumentWork = activeJavaDocument && c.type != FileChangeType.Deleted;
                switch (c.type) {
                    case FileChangeType.Created:
                        // Some clients report save-on-open-file as "Created" for an existing path.
                        // Treat that as a normal change to avoid full project refresh churn.
                        if (activeJavaDocument || Files.exists(file)) {
                            FileStore.externalChange(file);
                            if (suppressActiveDocumentWork) {
                                LOG.fine(
                                        "[perf] watched_java_change_skip reason=active_document event=created file="
                                                + file);
                            } else {
                                completionIndexScheduler.scheduleRefresh(
                                        List.of(file),
                                        "didChangeWatchedFiles:javaCreatedExisting",
                                        COMPLETION_INDEX_DEBOUNCE_MS,
                                        CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
                            }
                        } else {
                            FileStore.externalCreate(file);
                            completionIndexScheduler.scheduleRefresh(
                                    FileStore.all(),
                                    "didChangeWatchedFiles:javaCreated",
                                    0,
                                    CompletionIndexRefreshMode.FULL_REBUILD);
                        }
                        break;
                    case FileChangeType.Changed:
                        FileStore.externalChange(file);
                        if (suppressActiveDocumentWork) {
                            LOG.fine(
                                    "[perf] watched_java_change_skip reason=active_document event=changed file="
                                            + file);
                        } else {
                            completionIndexScheduler.scheduleRefresh(
                                    List.of(file),
                                    "didChangeWatchedFiles:javaChanged",
                                    COMPLETION_INDEX_DEBOUNCE_MS,
                                    CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
                        }
                        break;
                    case FileChangeType.Deleted:
                        FileStore.externalDelete(file);
                        completionIndexScheduler.scheduleRefresh(
                                FileStore.all(),
                                "didChangeWatchedFiles:javaDeleted",
                                0,
                                CompletionIndexRefreshMode.FULL_REBUILD);
                        break;
                }
                if (!activeDocuments.isEmpty()) {
                    if (suppressActiveDocumentWork) {
                        LOG.fine(
                                "[perf] diagnostics_watched_skip reason=active_document file="
                                        + file);
                    } else {
                        refreshActiveDiagnostics = true;
                    }
                }
                continue;
            }
            var name = file.getFileName().toString();
            if (isCompilerConfigFile(name)) {
                LOG.info(String.format("Compiler needs to be re-created because %s has changed", file));
                compilerInputsChanged = true;
            }
        }
        if (refreshActiveDiagnostics) {
            diagnosticsScheduler.markDirty(activeDocuments, "didChangeWatchedFiles");
            diagnosticsScheduler.schedule(activeDocuments, "didChangeWatchedFiles", DIAGNOSTIC_DEBOUNCE_MS);
        }
        if (compilerInputsChanged) {
            recreateCompilersAndRefreshState("didChangeWatchedFiles");
        }
    }

    private boolean isCompilerConfigFile(String name) {
        return switch (name) {
            case "BUILD",
                    "WORKSPACE",
                    "pom.xml",
                    "javaconfig.json",
                    "build.gradle",
                    "build.gradle.kts",
                    "settings.gradle",
                    "settings.gradle.kts" -> true;
            default -> false;
        };
    }

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var started = Instant.now();
        var readiness = ensureTypeIndexReady("completionBootstrap", COMPLETION_BOOTSTRAP_WAIT_MS, false);
        var snapshot = completionSnapshotRef.get();
        var provider = new CompletionProvider(cacheCompiler, snapshot.typeIndex(), snapshot.version());
        var list = provider.complete(file, params.position.line + 1, params.position.character + 1);
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        LOG.fine(String.format(
                "[perf] completion_request file=%s wait=%dms index_before=%d index_after=%d scope_before=%s scope_after=%s diagnostics_active=%s took=%dms",
                file.getFileName(),
                readiness.waitMs(),
                readiness.versionBefore(),
                readiness.versionAfter(),
                readiness.scopeBefore().name().toLowerCase(),
                readiness.scopeAfter().name().toLowerCase(),
                diagnosticsCompilesInFlight.get() > 0,
                Duration.between(started, Instant.now()).toMillis()));
        return Optional.of(list);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        var snapshot = completionSnapshotRef.get();
        new HoverProvider(getOrCreateCompiler(), snapshot.typeIndex()).resolveCompletionItem(unresolved);
        return unresolved;
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var snapshot = completionSnapshotRef.get();
        var content = new HoverProvider(getOrCreateCompiler(), snapshot.typeIndex()).hover(file, line, column);
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
        var help = new SignatureProvider(getOrCreateCompiler()).signatureHelp(file, line, column);
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
        var snapshot = completionSnapshotRef.get();
        var found = new DefinitionProvider(getOrCreateCompiler(), snapshot.typeIndex(), file, line, column).find();
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
        var snapshot = completionSnapshotRef.get();
        var includeDeclaration = position.context != null && position.context.includeDeclaration;
        var found =
                new ReferenceProvider(
                                getOrCreateCompiler(), snapshot.typeIndex(), file, line, column, includeDeclaration)
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
        return new SymbolProvider(getOrCreateCompiler()).documentSymbols(file);
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        var task = getOrCreateCompiler().parse(file);
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
        var fixImports = new AutoFixImports(file).rewrite(getOrCreateCompiler()).get(file);
        Collections.addAll(edits, fixImports);
        var addOverrides = new AutoAddOverrides(file).rewrite(getOrCreateCompiler()).get(file);
        Collections.addAll(edits, addOverrides);
        return edits;
    }

    @Override
    public List<FoldingRange> foldingRange(FoldingRangeParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new FoldProvider(getOrCreateCompiler()).foldingRanges(file);
    }

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        LOG.info("Try to rename...");
        var file = Paths.get(params.textDocument.uri);
        try (var task = getOrCreateCompiler().compile(file)) {
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
                LOG.info("...no element under cursor");
                return Optional.empty();
            }
            var el = Trees.instance(task.task).getElement(path);
            if (el == null) {
                LOG.info("...couldn't resolve element");
                return Optional.empty();
            }
            if (!canRename(el)) {
                LOG.info(String.format("...can't rename %s", el));
                return Optional.empty();
            }
            if (!canFindSource(el)) {
                LOG.info(String.format("...can't find source for %s", el));
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
            return getOrCreateCompiler().findTypeDeclaration(name) != CompilerProvider.NOT_FOUND;
        }
        return canFindSource(rename.getEnclosingElement());
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        var rw = createRewrite(params);
        var response = new WorkspaceEdit();
        var map = rw.rewrite(getOrCreateCompiler());
        for (var editedFile : map.keySet()) {
            response.changes.put(editedFile.toUri(), List.of(map.get(editedFile)));
        }
        return response;
    }

    private Rewrite createRewrite(RenameParams params) {
        var file = Paths.get(params.textDocument.uri);
        try (var task = getOrCreateCompiler().compile(file)) {
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
        if (completionSnapshotRef.get().scope() == CompletionIndexScope.EMPTY) {
            bootstrapWorkspaceOnDidOpen(file);
            return;
        }
        diagnosticsScheduler.schedule(List.of(file), "didOpen", DIAGNOSTIC_DEBOUNCE_MS);
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        var refreshCompletionIndex = analyzeActiveDocumentChange(file, params.contentChanges);
        markOtherActiveDiagnosticsDirty(file, "didChange");
        if (completionSnapshotRef.get().scope() == CompletionIndexScope.EMPTY) {
            completionIndexScheduler.scheduleActiveBootstrapIfNeeded("didChangeActiveBootstrap");
        } else if (refreshCompletionIndex) {
            completionIndexScheduler.scheduleRefresh(
                    List.of(file),
                    "didChange",
                    COMPLETION_INDEX_DEBOUNCE_MS,
                    CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
        }
        diagnosticsScheduler.schedule(List.of(file), "didChange", DIAGNOSTIC_DEBOUNCE_MS);
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        dirtyDiagnosticsFiles.remove(Paths.get(params.textDocument.uri));
        client.publishDiagnostics(new PublishDiagnosticsParams(params.textDocument.uri, List.of()));
    }

    @Override
    public List<CodeAction> codeAction(CodeActionParams params) {
        var provider = new CodeActionProvider(getOrCreateCompiler());
        if (params.context.diagnostics.isEmpty()) {
            return provider.codeActionsForCursor(params);
        } else {
            return provider.codeActionForDiagnostics(params);
        }
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        // Save-triggered diagnostics should not reuse a stale didChange compile for the same buffer text.
        FileStore.save(file);
        markOtherActiveDiagnosticsDirty(file, "didSave");
        diagnosticsScheduler.cancel("didSave");
        completionIndexScheduler.cancel("didSave");
        getOrCreateCompiler();
        var batch = diagnosticsScheduler.expandRequestedBatch(List.of(file), "didSave");
        didSaveDiagnosticsInFlight.incrementAndGet();
        try {
            diagnosticsScheduler.compileAndPublish(
                    batch.files(),
                    diagnosticsCompiler,
                    "didSave",
                    -1,
                    batch.requestedCount(),
                    batch.dirtyOpenCount());
        } finally {
            didSaveDiagnosticsInFlight.updateAndGet(current -> Math.max(0, current - 1));
        }
        if (completionSnapshotRef.get().scope() == CompletionIndexScope.EMPTY
                && !FileStore.activeDocuments().isEmpty()) {
            completionIndexScheduler.scheduleActiveBootstrapIfNeeded("didSaveBootstrap");
        } else {
            completionIndexScheduler.scheduleRefresh(
                    List.of(file),
                    "didSave",
                    0,
                    CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
        }
    }

    /**
     * Diagnostics scheduling and publication stay together so debounce, dirty-file tracking,
     * didSave priority, and stale-publish guards evolve as one unit.
     *
     * <p>The flow is:
     * normalize requested files -> expand with dirty open files -> debounce or run immediately ->
     * compile on the diagnostics compiler -> publish only the requested/dirty batch ->
     * clear successfully refreshed dirty entries.
     */
    final class DiagnosticsScheduler {
        DiagnosticsScheduler() {}

        /**
         * Compile the requested diagnostics batch and publish only the files that were explicitly
         * requested or pulled in as dirty open files.
         *
         * <p>This method owns compile serialization only. Stale-content and didSave-priority checks
         * are re-checked inside {@link #publishFromTask(CompileTask, Collection, String, long)}
         * immediately before publishing so diagnostics do not leak after the buffer changes or a
         * save-triggered compile takes over.
         */
        void compileAndPublish(
                Collection<Path> files,
                JavaCompilerService diagnosticsCompiler,
                String trigger,
                long expectedContentRevision,
                int requestedCount,
                int dirtyOpenCount) {
            if (shouldYieldToDidSaveDiagnostics(trigger, "pre_lock")) {
                return;
            }
            var waitStarted = Instant.now();
            synchronized (diagnosticsCompileMutex) {
                if (shouldYieldToDidSaveDiagnostics(trigger, "post_lock")) {
                    return;
                }
                var waited = Duration.between(waitStarted, Instant.now()).toMillis();
                LOG.fine(String.format(
                        "[perf] diagnostics_compile_wait trigger=%s waited=%dms",
                        trigger,
                        waited));
                var javaFiles = JavaLanguageServer.filterJavaFiles(files);
                if (javaFiles.isEmpty()) return;
                LOG.info(String.format("Lint %d files...", javaFiles.size()));
                CompileTask task = null;
                try {
                    diagnosticsCompilesInFlight.incrementAndGet();
                    LOG.fine(String.format(
                            "[perf] diagnostics_compile trigger=%s requested=%d dirty_open=%d batch=%d",
                            trigger,
                            requestedCount,
                            dirtyOpenCount,
                            javaFiles.size()));
                    try {
                        task =
                                diagnosticsCompiler.compileDiagnostics(
                                        javaFiles.stream().map(SourceFileObject::new).toList());
                    } catch (RuntimeException | AssertionError e) {
                        LOG.fine(
                                String.format(
                                        "[perf] diagnostics_compile_skip trigger=%s files=%d reason=%s message=%s",
                                        trigger,
                                        javaFiles.size(),
                                        e.getClass().getSimpleName(),
                                        e.getMessage()));
                        return;
                    }
                    var compileTelemetry = diagnosticsCompiler.lastCompileTelemetry();
                    LOG.fine(String.format(
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
                            compileTelemetry.analyzeMs()));
                    publishFromTask(task, javaFiles, trigger, expectedContentRevision);
                } finally {
                    diagnosticsCompilesInFlight.updateAndGet(current -> Math.max(0, current - 1));
                    if (task != null) {
                        task.close();
                    }
                }
            }
        }

        /**
         * Publish diagnostics for the requested batch only and clear files that no longer report any
         * diagnostics.
         */
        void publishFromTask(
                CompileTask task, Collection<Path> requestedFiles, String trigger, long expectedContentRevision) {
            var requestedJavaFiles = JavaLanguageServer.filterJavaFiles(requestedFiles);
            if (requestedJavaFiles.isEmpty()) {
                return;
            }
            var publishStarted = Instant.now();
            if (shouldSkipStaleDiagnostics(expectedContentRevision, trigger, "pre_publish")) {
                return;
            }
            if (shouldYieldToDidSaveDiagnostics(trigger, "pre_publish")) {
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
                if (shouldSkipStaleDiagnostics(expectedContentRevision, trigger, "publish_loop")) {
                    return;
                }
                client.publishDiagnostics(errs);
                diagnosticsCount += errs.diagnostics.size();
                publishedUris.add(errs.uri);
            }
            for (var file : requestedJavaFiles) {
                if (shouldSkipStaleDiagnostics(expectedContentRevision, trigger, "publish_clear_loop")) {
                    return;
                }
                if (!FileStore.isJavaFile(file)) continue;
                var uri = file.toUri();
                if (publishedUris.contains(uri)) continue;
                client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
                publishedUris.add(uri);
            }
            LOG.fine(String.format(
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
                    Duration.between(publishStarted, Instant.now()).toMillis()));
            clearDirty(requestedJavaFiles);
        }

        /** Debounce diagnostics work so typing collapses into one queued async publish. */
        void schedule(Collection<Path> files, String trigger, long delayMs) {
            var batch = expandRequestedBatch(files, trigger);
            if (batch.files().isEmpty()) return;
            var request =
                    new ScheduledDiagnosticsRequest(
                            diagnosticsRevision.incrementAndGet(),
                            FileStore.contentRevision(),
                            batch.requestedCount(),
                            batch.dirtyOpenCount());
            var filesBatch = batch.files();
            synchronized (JavaLanguageServer.this) {
                if (pendingDiagnostics != null) {
                    pendingDiagnostics.cancel(false);
                }
                pendingDiagnostics =
                        diagnosticsExecutor.schedule(
                                () -> run(filesBatch, request, trigger),
                                delayMs,
                                TimeUnit.MILLISECONDS);
            }
            LOG.fine(String.format(
                    "[perf] diagnostics_debounce trigger=%s files=%d delay=%dms revision=%d content_revision=%d",
                    trigger,
                    filesBatch.size(),
                    delayMs,
                    request.scheduleRevision(),
                    request.contentRevision()));
        }

        /** Remember which open Java files need to be refreshed in the next diagnostics batch. */
        void markDirty(Collection<Path> files, String trigger) {
            var javaFiles = JavaLanguageServer.filterJavaFiles(files);
            if (javaFiles.isEmpty()) {
                return;
            }
            if (javaFiles.size() == 1) {
                var file = javaFiles.getFirst();
                if (dirtyDiagnosticsFiles.add(file)) {
                    LOG.fine(String.format(
                            "[perf] diagnostics_dirty_mark trigger=%s file=%s",
                            trigger,
                            file.getFileName()));
                }
                return;
            }
            var marked = 0;
            for (var file : javaFiles) {
                if (dirtyDiagnosticsFiles.add(file)) {
                    marked++;
                }
            }
            if (marked > 0) {
                LOG.fine(String.format("[perf] diagnostics_dirty_batch trigger=%s files=%d", trigger, marked));
            }
        }

        void clearDirty(Collection<Path> files) {
            for (var file : files) {
                dirtyDiagnosticsFiles.remove(file);
            }
        }

        /** Cancel any queued async diagnostics run and bump the revision so stale work is dropped. */
        void cancel(String reason) {
            synchronized (JavaLanguageServer.this) {
                if (pendingDiagnostics == null) {
                    return;
                }
                diagnosticsRevision.incrementAndGet();
                pendingDiagnostics.cancel(false);
                pendingDiagnostics = null;
            }
            LOG.fine(String.format("[perf] diagnostics_cancel reason=%s", reason));
        }

        /**
         * Expand the explicitly requested files with any other open Java files already marked dirty.
         *
         * <p>This keeps diagnostics responsive for the current file while letting the next publish
         * refresh other open files whose diagnostics may have become stale due to cross-file edits.
         */
        DiagnosticsBatch expandRequestedBatch(Collection<Path> files, String trigger) {
            var requested = JavaLanguageServer.filterJavaFiles(files);
            if (requested.isEmpty()) {
                return new DiagnosticsBatch(List.of(), 0, 0);
            }
            var batch = new LinkedHashSet<>(requested);
            var activeJavaFiles = new LinkedHashSet<>(JavaLanguageServer.filterJavaFiles(FileStore.activeDocuments()));
            var dirtyOpenCount = 0;
            if (!activeJavaFiles.isEmpty()) {
                for (var dirty : dirtyDiagnosticsFiles) {
                    if (!activeJavaFiles.contains(dirty) || batch.contains(dirty)) {
                        continue;
                    }
                    batch.add(dirty);
                    dirtyOpenCount++;
                }
            }
            var filesBatch = List.copyOf(batch);
            LOG.fine(String.format(
                    "[perf] diagnostics_batch trigger=%s requested=%d dirty_open=%d files=%d",
                    trigger,
                    requested.size(),
                    dirtyOpenCount,
                    filesBatch.size()));
            return new DiagnosticsBatch(filesBatch, requested.size(), dirtyOpenCount);
        }

        /** Execute one queued async diagnostics run if its scheduled revision is still current. */
        void run(List<Path> files, ScheduledDiagnosticsRequest request, String trigger) {
            if (request.scheduleRevision() != diagnosticsRevision.get()) {
                return;
            }
            getOrCreateCompiler();
            try {
                compileAndPublish(
                        files,
                        diagnosticsCompiler,
                        "async:" + trigger,
                        request.contentRevision(),
                        request.requestedCount(),
                        request.dirtyOpenCount());
            } catch (RuntimeException e) {
                LOG.fine("Async lint failed for " + files + ": " + e.getMessage());
            }
        }
    }

    /**
     * Completion-index refresh/install logic is isolated so compiler recreation, active-document
     * merges, and full workspace bootstraps share one publication path.
     */
    final class CompletionIndexScheduler {
        CompletionIndexScheduler() {}

        /** Publish a rebuilt workspace index snapshot, replacing the previous workspace view. */
        void installTypeMemberIndex(
                WorkspaceTypeIndex nextIndex,
                long indexVersion,
                String trigger,
                Duration took,
                CompletionIndexScope scope) {
            var rebuilt = (nextIndex == null || nextIndex.size() == 0) ? WorkspaceTypeIndex.EMPTY : nextIndex;
            var currentSnapshot = completionSnapshotRef.get();
            publishCompletionSnapshot(
                    rebuilt,
                    currentSnapshot.externalIndex(),
                    indexVersion,
                    rebuilt == WorkspaceTypeIndex.EMPTY ? CompletionIndexScope.EMPTY : scope);
            LOG.fine(String.format(
                    "[perf] completion_type_index trigger=%s version=%d types=%d took=%dms",
                    trigger,
                    indexVersion,
                    rebuilt.size(),
                    took.toMillis()));
        }

        /**
         * Publish a file-scoped workspace declaration merge, replacing only the declarations from
         * the supplied files while preserving the rest of the existing workspace snapshot.
         */
        void installMergedTypeMemberIndex(
                WorkspaceTypeIndex deltaIndex,
                Collection<Path> replacedFiles,
                long indexVersion,
                String trigger,
                Duration took) {
            var baseSnapshot = completionSnapshotRef.get();
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
            publishCompletionSnapshot(merged, baseSnapshot.externalIndex(), indexVersion, nextScope);
            LOG.fine(String.format(
                    "[perf] completion_type_index_merge trigger=%s base_version=%d version=%d types=%d files=%d took=%dms",
                    trigger,
                    baseSnapshot.version(),
                    indexVersion,
                    merged.size(),
                    replacedFiles.size(),
                    took.toMillis()));
        }

        /** Queue an active-document bootstrap only while the published scope is still empty. */
        void scheduleActiveBootstrapIfNeeded(String trigger) {
            if (completionSnapshotRef.get().scope() != CompletionIndexScope.EMPTY) {
                return;
            }
            synchronized (JavaLanguageServer.this) {
                if (pendingCompletionIndex != null && !pendingCompletionIndex.isDone()) {
                    return;
                }
            }
            scheduleRefresh(FileStore.all(), trigger, 0, CompletionIndexRefreshMode.FULL_REBUILD);
        }

        /** Queue a full workspace bootstrap unless the published scope is already workspace-wide. */
        void scheduleProjectBootstrapIfNeeded(String trigger) {
            if (completionSnapshotRef.get().scope() == CompletionIndexScope.WORKSPACE) {
                return;
            }
            scheduleRefresh(FileStore.all(), trigger, 0, CompletionIndexRefreshMode.FULL_REBUILD);
        }

        /** Debounce completion-index refreshes and collapse newer schedules onto one pending task. */
        void scheduleRefresh(
                Collection<Path> files, String trigger, long delayMs, CompletionIndexRefreshMode mode) {
            var javaFiles = filterJavaFiles(files);
            if (javaFiles.isEmpty()) {
                return;
            }
            var revision = completionIndexRevision.incrementAndGet();
            var filesBatch = List.copyOf(javaFiles);
            synchronized (JavaLanguageServer.this) {
                if (pendingCompletionIndex != null) {
                    pendingCompletionIndex.cancel(false);
                }
                pendingCompletionIndex =
                        completionIndexExecutor.schedule(
                                () -> runRefresh(filesBatch, revision, trigger, mode),
                                delayMs,
                                TimeUnit.MILLISECONDS);
            }
            if (delayMs == 0
                    || "didSave".equals(trigger)
                    || mode == CompletionIndexRefreshMode.FULL_REBUILD
                    || filesBatch.size() > 1) {
                LOG.fine(String.format(
                        "[perf] completion_index_debounce trigger=%s files=%d mode=%s delay=%dms revision=%d",
                        trigger,
                        filesBatch.size(),
                        mode.name().toLowerCase(),
                        delayMs,
                        revision));
            }
        }

        /**
         * Run one queued completion-index refresh if its revision is still current, then compile,
         * index, and publish the resulting workspace snapshot.
         */
        void runRefresh(List<Path> files, long revision, String trigger, CompletionIndexRefreshMode mode) {
            if (revision != completionIndexRevision.get()) {
                return;
            }
            var workspaceBootstrap =
                    mode != CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE
                            && completionSnapshotRef.get().scope() == CompletionIndexScope.EMPTY;
            synchronized (completionIndexCompileMutex) {
                var started = Instant.now();
                CompileTask task = null;
                try {
                    if (workspaceBootstrap) {
                        LOG.info(String.format("[perf] workspace bootstrap started trigger=%s files=%d", trigger, files.size()));
                    }
                    var compiler = getOrCreateCompiler();
                    task = compiler.compileFast(files.toArray(Path[]::new));
                    if (revision != completionIndexRevision.get()) {
                        LOG.fine(String.format(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_compile expected=%d current=%d",
                                trigger,
                                revision,
                                completionIndexRevision.get()));
                        return;
                    }
                    var indexStarted = Instant.now();
                    var nextIndex = buildIndex(task, mode, compiler);
                    if (revision != completionIndexRevision.get()) {
                        LOG.fine(String.format(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_index expected=%d current=%d",
                                trigger,
                                revision,
                                completionIndexRevision.get()));
                        return;
                    }
                    var indexVersion = completionIndexVersion.incrementAndGet();
                    var installTook = Duration.between(indexStarted, Instant.now());
                    if (mode == CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                        installMergedTypeMemberIndex(
                                nextIndex, files, indexVersion, "index:" + trigger, installTook);
                    } else {
                        var scope =
                                mode == CompletionIndexRefreshMode.FULL_REBUILD
                                        ? CompletionIndexScope.WORKSPACE
                                        : CompletionIndexScope.ACTIVE;
                        installTypeMemberIndex(
                                nextIndex, indexVersion, "index:" + trigger, installTook, scope);
                    }
                    if (workspaceBootstrap) {
                        LOG.info(String.format(
                                "[perf] workspace index installed trigger=%s version=%d types=%d",
                                trigger,
                                indexVersion,
                                nextIndex == null ? 0 : nextIndex.size()));
                    }
                    var totalMs = Duration.between(started, Instant.now()).toMillis();
                    LOG.fine(String.format(
                            "[perf] completion_index_refresh trigger=%s files=%d version=%d mode=%s compile=%dms total=%dms",
                            trigger,
                            files.size(),
                            indexVersion,
                            mode.name().toLowerCase(),
                            Duration.between(started, indexStarted).toMillis(),
                            totalMs));
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

        /**
         * Build the next workspace type snapshot for the requested refresh mode.
         *
         * <p>Active bootstrap keeps only types that are declared in the compiled sources or can be
         * mapped back to a source declaration. Full rebuild and declaration-merge modes index all
         * workspace declarations from the compile task.
         */
        WorkspaceTypeIndex buildIndex(
                CompileTask task, CompletionIndexRefreshMode mode, JavaCompilerService compiler) {
            if (mode == CompletionIndexRefreshMode.ACTIVE_DOCUMENT_BOOTSTRAP) {
                return WorkspaceTypeIndex.from(task)
                        .filterTypes(
                                info ->
                                        info.sourcePath != null
                                                || compiler.findTypeDeclaration(info.qualifiedName)
                                                        != CompilerProvider.NOT_FOUND);
            }
            return WorkspaceTypeIndex.workspaceDeclarations(task);
        }

        /** Cancel any queued completion-index refresh and bump the revision so stale work is dropped. */
        void cancel(String reason) {
            synchronized (JavaLanguageServer.this) {
                if (pendingCompletionIndex == null) {
                    return;
                }
                completionIndexRevision.incrementAndGet();
                pendingCompletionIndex.cancel(false);
                pendingCompletionIndex = null;
            }
            LOG.fine(String.format("[perf] completion_index_cancel reason=%s", reason));
        }
    }
}
