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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.action.CodeActionProvider;
import org.javacs.provider.CompletionProvider;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.provider.SignatureProvider;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.index.TypeIndexRouter;
import org.javacs.fold.FoldProvider;
import org.javacs.provider.HoverProvider;
import org.javacs.index.IndexedType;
import org.javacs.provider.SymbolProvider;
import org.javacs.lens.CodeLensProvider;
import org.javacs.lsp.*;
import org.javacs.markup.ErrorProvider;
import org.javacs.provider.DefinitionProvider;
import org.javacs.provider.DocumentHighlightProvider;
import org.javacs.provider.InlayHintProvider;
import org.javacs.provider.ReferenceProvider;
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

    private static final long COMPLETION_INDEX_DEBOUNCE_MS = 100;
    private static final long COMPLETION_BOOTSTRAP_WAIT_MS = 700;
    private static final long COMPLETION_BOOTSTRAP_POLL_MS = 25;
    private static final long NAVIGATION_BOOTSTRAP_WAIT_MS = 1500;

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
    private final CompletionIndexScheduler completionIndexScheduler = new CompletionIndexScheduler();

    // Two compilers, each owned by exactly one thread/task:
    //   mainCompiler / interactiveCompiler — LSP main thread (definition, hover, completion, references …)
    //   indexCompiler                    — background completion-index builds (runRefresh / CompletionIndexScheduler)
    // No two of these are ever used concurrently. JavaCompilerService is not thread-safe.
    private JavaCompilerService interactiveCompiler;
    private JavaCompilerService indexCompiler;

    private JsonObject appliedCompilerSettings = new JsonObject();
    private JsonObject settings = new JsonObject();
    private boolean workDoneProgressSupported;

    private final ScheduledExecutorService completionIndexExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("javacs-completion-index").factory());

    /** Monotonic revision for queued completion-index refreshes; newer schedules cancel older runs. */
    private final AtomicLong completionIndexRevision = new AtomicLong();
    private final AtomicLong completionIndexVersion = new AtomicLong();
    private final AtomicReference<CompletionSnapshot> completionSnapshotRef =
            new AtomicReference<>(CompletionSnapshot.EMPTY);
    /** Serialize completion-index refreshes so one compile/index install owns the refresh lane. */
    private final Object completionIndexCompileMutex = new Object();

    private ScheduledFuture<?> pendingCompletionIndex;

    private final Set<String> shownWorkspaceWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** LRU cache of pending Rewrite objects keyed by UUID, used for codeAction/resolve. */
    private static final int REWRITE_REGISTRY_MAX = 200;
    private final Map<String, org.javacs.rewrite.Rewrite> rewriteRegistry =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(REWRITE_REGISTRY_MAX, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, org.javacs.rewrite.Rewrite> eldest) {
                            return size() > REWRITE_REGISTRY_MAX;
                        }
                    });

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
        Objects.requireNonNull(interactiveCompiler, "Compiler has not been initialized");
        return interactiveCompiler;
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
        completionIndexScheduler.cancel(trigger);
        createCompilers();
        appliedCompilerSettings = compilerSettingsSnapshot(settings);
        publishExternalBinaryIndexSnapshot();
        refreshStateForCompilerRecreated();
        client.customNotification("workspace/diagnostic/refresh", null);
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
        if (currentSnapshot.scope() == CompletionIndexScope.WORKSPACE) {
            completionIndexScheduler.scheduleRefresh(
                    FileStore.all(), "compilerRecreated", 0, CompletionIndexRefreshMode.FULL_REBUILD);
        } else {
            completionIndexScheduler.scheduleRefresh(
                    active, "compilerRecreated", 0, CompletionIndexRefreshMode.ACTIVE_DOCUMENT_BOOTSTRAP);
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
            if (contentChanges != null && isWhitespaceOnlyChange(contentChanges)) {
                LOG.fine(
                        "[perf] completion_index_didChange_skip file="
                                + file.getFileName()
                                + " reason=whitespace_only");
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

    private static boolean isWhitespaceOnlyChange(List<TextDocumentContentChangeEvent> changes) {
        for (var change : changes) {
            if (change.text == null) return false;
            for (int i = 0; i < change.text.length(); i++) {
                if (!Character.isWhitespace(change.text.charAt(i))) return false;
            }
        }
        return true;
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
     * Recreate the paired compiler services used for interactive requests and pull-diagnostics.
     */
    private void createCompilers() {
        Objects.requireNonNull(workspaceRoot, "Can't create compiler because workspaceRoot has not been initialized");
        var started = Instant.now();
        var progressToken = beginWorkDoneProgress("Configure javac", "Finding source roots");

        var externalDependencies = externalDependencies();
        var classPath = classPath();
        var userExtraArgs = extraCompilerArgs();
        var addExports = addExports();
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

        var shared = CompilerSharedResources.from(classPath, resolvedDocPath, addExports, extraArgs);
        interactiveCompiler = new JavaCompilerService(shared, "interactive");

        var indexStarted = Instant.now();
        indexCompiler = new JavaCompilerService(shared, "index");

        LOG.info(String.format(
                "[perf] create_compilers classpath=%d docpath=%d extra_args=%d add_exports=%d settings=%dms inference=%dms interactive=%dms index=%dms total=%dms",
                classPath.size(),
                resolvedDocPath.size(),
                extraArgs.size(),
                addExports.size(),
                Duration.between(started, settingsLoaded).toMillis(),
                Duration.between(settingsLoaded, inferenceFinished).toMillis(),
                Duration.between(inferenceFinished, indexStarted).toMillis(),
                Duration.between(indexStarted, Instant.now()).toMillis(),
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
        var args = new ArrayList<String>();
        var file = projectSettings();
        if (file.has("extraCompilerArgs")) {
            for (var each : file.getAsJsonArray("extraCompilerArgs"))
                args.addAll(Arrays.asList(each.getAsString().trim().split("\\s+")));
        }
        if (settings.has("extraCompilerArgs")) {
            for (var each : settings.getAsJsonArray("extraCompilerArgs"))
                args.addAll(Arrays.asList(each.getAsString().trim().split("\\s+")));
        }
        return List.copyOf(args);
    }

    private InferConfig.MavenCompilerArgs selectCompilerArgs(List<String> userExtraArgs, Set<String> externalDependencies) {
        if (hasExplicitJavaLevelOverride(userExtraArgs)) {
            return new InferConfig.MavenCompilerArgs(userExtraArgs, "user", false);
        }
        var pomXml = workspaceRoot.resolve("pom.xml");
        if (!Files.exists(pomXml)) {
            return new InferConfig.MavenCompilerArgs(userExtraArgs, "none", false);
        }
        var inferred = new InferConfig(workspaceRoot, externalDependencies).compilerArgs();
        if (inferred.mixedModules()) {
            warnUserOnce(
                    "maven_mixed_release_fallback",
                    "JLS detected mixed Maven module Java levels and fell back to the runtime/default compiler behavior for this workspace.");
            return new InferConfig.MavenCompilerArgs(userExtraArgs, "fallback_mixed_modules", true);
        }
        if (inferred.args().isEmpty()) {
            return new InferConfig.MavenCompilerArgs(userExtraArgs, "none", false);
        }
        var merged = new ArrayList<String>(userExtraArgs);
        merged.addAll(inferred.args());
        return new InferConfig.MavenCompilerArgs(merged, inferred.source(), false);
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
        var merged = new HashSet<String>();
        // Project file first, then client settings override/extend
        var file = projectSettings();
        if (file.has("addExports")) {
            for (var each : file.getAsJsonArray("addExports")) merged.add(each.getAsString());
        }
        if (settings.has("addExports")) {
            for (var each : settings.getAsJsonArray("addExports")) merged.add(each.getAsString());
        }
        return merged;
    }

    /** Read .java-language-server.json from the workspace root, or empty object if absent/invalid. */
    private JsonObject projectSettings() {
        if (workspaceRoot == null) return new JsonObject();
        var file = workspaceRoot.resolve(".java-language-server.json");
        if (!Files.exists(file)) return new JsonObject();
        try {
            var text = Files.readString(file);
            var parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            LOG.warning("Failed to read .java-language-server.json: " + e.getMessage());
            return new JsonObject();
        }
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
        c.addProperty("documentHighlightProvider", true);
        c.addProperty("workspaceSymbolProvider", true);
        c.addProperty("documentSymbolProvider", true);
        c.addProperty("documentFormattingProvider", true);
        var codeLensOptions = new JsonObject();
        c.add("codeLensProvider", codeLensOptions);
        c.addProperty("foldingRangeProvider", true);
        c.addProperty("inlayHintProvider", true);
        var codeActionOptions = new JsonObject();
        codeActionOptions.addProperty("resolveProvider", true);
        c.add("codeActionProvider", codeActionOptions);
        var renameOptions = new JsonObject();
        renameOptions.addProperty("prepareProvider", true);
        c.add("renameProvider", renameOptions);
        var executeCommandOptions = new JsonObject();
        var commands = new JsonArray();
        commands.add("java.pickAndGenerate");
        commands.add("java.generateFields");
        executeCommandOptions.add("commands", commands);
        c.add("executeCommandProvider", executeCommandOptions);
        var diagnosticOptions = new JsonObject();
        diagnosticOptions.addProperty("interFileDependencies", false);
        diagnosticOptions.addProperty("workspaceDiagnostics", false);
        c.add("diagnosticProvider", diagnosticOptions);

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
            if (pendingCompletionIndex != null) {
                pendingCompletionIndex.cancel(false);
                pendingCompletionIndex = null;
            }
        }
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
                continue;            }
            var name = file.getFileName().toString();
            if (isCompilerConfigFile(name)) {
                LOG.info(String.format("Compiler needs to be re-created because %s has changed", file));
                compilerInputsChanged = true;
            }
        }
        if (refreshActiveDiagnostics) {
            client.customNotification("workspace/diagnostic/refresh", null);
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
        var provider = new CompletionProvider(getOrCreateCompiler(), snapshot.typeIndex(), snapshot.version());
        var list = provider.complete(file, params.position.line + 1, params.position.character + 1);
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        LOG.fine(String.format(
                "[perf] completion_request file=%s wait=%dms index_before=%d index_after=%d scope_before=%s scope_after=%s took=%dms",
                file.getFileName(),
                readiness.waitMs(),
                readiness.versionBefore(),
                readiness.versionAfter(),
                readiness.scopeBefore().name().toLowerCase(),
                readiness.scopeAfter().name().toLowerCase(),
                Duration.between(started, Instant.now()).toMillis()));
        return Optional.of(list);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        var snapshot = completionSnapshotRef.get();
        new CompletionProvider(getOrCreateCompiler(), snapshot.typeIndex(), snapshot.version())
                .resolveCompletionItem(unresolved);
        return unresolved;
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        ensureTypeIndexReady("hoverBootstrap", NAVIGATION_BOOTSTRAP_WAIT_MS, true);
        var snapshot = completionSnapshotRef.get();
        var content = new HoverProvider(getOrCreateCompiler()).hover(file, line, column);
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
        ensureTypeIndexReady("signatureBootstrap", NAVIGATION_BOOTSTRAP_WAIT_MS, true);
        var snapshot = completionSnapshotRef.get();
        var help = new SignatureProvider(getOrCreateCompiler(), snapshot.typeIndex()).signatureHelp(file, line, column);
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
        List<Location> found;
        try {
            found = new DefinitionProvider(getOrCreateCompiler(), snapshot.typeIndex(), file, line, column).find();
        } catch (ReusableCompiler.TaskCreationException e) {
            LOG.warning(
                    String.format(
                            "[compiler] definition_retry_after_task_create_failure file=%s reason=%s",
                            file.getFileName(), e.getMessage()));
            try {
                found = new DefinitionProvider(getOrCreateCompiler(), snapshot.typeIndex(), file, line, column).find();
            } catch (ReusableCompiler.TaskCreationException retryFailure) {
                LOG.warning(
                        String.format(
                                "[compiler] definition_failed_after_task_create_retry file=%s reason=%s",
                                file.getFileName(), retryFailure.getMessage()));
                return Optional.empty();
            }
        }
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
    public Optional<List<DocumentHighlight>> documentHighlight(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var line = params.position.line + 1;
        var column = params.position.character + 1;
        ensureTypeIndexReady("referencesBootstrap", NAVIGATION_BOOTSTRAP_WAIT_MS, true);
        var snapshot = completionSnapshotRef.get();
        var found = new DocumentHighlightProvider(
                        getOrCreateCompiler(), snapshot.typeIndex(), file, line, column)
                .find();
        if (found.isEmpty()) return Optional.empty();
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
    public Optional<List<InlayHint>> inlayHint(InlayHintParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.of(List.of());
        var file = Paths.get(params.textDocument.uri);
        return Optional.of(new InlayHintProvider(getOrCreateCompiler()).inlayHints(file, params.range));
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
                                task.root(file).getSourceFile().getCharContent(true).toString(),
                                params.position.line + 1,
                                params.position.character + 1);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(task).scan(task.root(file), cursor);
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
            response.range = FindHelper.location(task, path, "").range;
            response.placeholder = el.getSimpleName().toString();
            return Optional.of(response);
        }
    }

    private boolean canRename(Element rename) {
        return switch (rename.getKind()) {
            case METHOD, FIELD, LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, CLASS -> true;
            default -> false;
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
            response.changes.put(editedFile.toUri(), Arrays.asList(map.get(editedFile)));
        }
        // Schedule index refresh for modified files
        if (!map.isEmpty()) {
            completionIndexScheduler.scheduleRefresh(
                    map.keySet(),
                    "codeAction",
                    0,
                    CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
        }
        // For class renames, notify client to rename the file on disk
        if (rw instanceof RenameClass rc) {
            var sourceFile = getOrCreateCompiler().findTypeDeclaration(rc.oldQualifiedName);
            if (sourceFile != null && sourceFile != CompilerProvider.NOT_FOUND) {
                var oldPath = sourceFile.toAbsolutePath().normalize().toString();
                var parent = sourceFile.getParent();
                var newFileName = rc.newSimpleName + ".java";
                var newPath =
                        parent != null
                                ? parent.resolve(newFileName).toAbsolutePath().normalize().toString()
                                : newFileName;
                var notificationParams = new HashMap<String, String>();
                notificationParams.put("oldPath", oldPath);
                notificationParams.put("newPath", newPath);
                client.customNotification("java/renameFile", new Gson().toJsonTree(notificationParams));
            }
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
                                task.root(file).getSourceFile().getCharContent(true).toString(),
                                params.position.line + 1,
                                params.position.character + 1);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(task).scan(task.root(file), position);
            if (path == null) return Rewrite.NOT_SUPPORTED;
            var el = Trees.instance(task.task).getElement(path);
            return switch (el.getKind()) {
                case METHOD -> renameMethod(task, (ExecutableElement) el, params.newName);
                case FIELD -> renameField(task, (VariableElement) el, params.newName);
                case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER ->
                        renameVariable(task, (VariableElement) el, params.newName);
                case CLASS -> {
                    var type = (TypeElement) el;
                    yield new RenameClass(type.getQualifiedName().toString(), params.newName);
                }
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
    public DocumentDiagnosticReport textDocumentDiagnostic(DocumentDiagnosticParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) {
            return new DocumentDiagnosticReport(List.of());
        }
        var file = Paths.get(params.textDocument.uri);
        var fileUri = file.toUri();
        var started = Instant.now();
        CompileTask task = null;
        try {
            task = getOrCreateCompiler().compileDiagnostics(List.of(new SourceFileObject(file)));
            var requestedUris = Set.of(fileUri);
            var report = new ErrorProvider(task).errors(requestedUris);
            var diagnostics = report.diagnostics().stream()
                    .filter(p -> fileUri.equals(p.uri))
                    .flatMap(p -> p.diagnostics.stream())
                    .collect(java.util.stream.Collectors.toList());
            LOG.fine(String.format(
                    "[perf] pull_diagnostics file=%s count=%d took=%dms",
                    file.getFileName(),
                    diagnostics.size(),
                    Duration.between(started, Instant.now()).toMillis()));
            return new DocumentDiagnosticReport(diagnostics);
        } catch (ReusableCompiler.TaskCreationException e) {
            LOG.fine(String.format(
                    "[perf] pull_diagnostics_skip file=%s reason=%s",
                    file.getFileName(), e.getMessage()));
            return new DocumentDiagnosticReport(List.of());
        } finally {
            if (task != null) task.close();
        }
    }

    /** Test helper: trigger diagnostics for a set of files synchronously. */
    void lint(Collection<Path> files) {
        for (var file : files) {
            var params = new DocumentDiagnosticParams();
            params.textDocument = new TextDocumentIdentifier(file.toUri());
            var report = textDocumentDiagnostic(params);
            client.publishDiagnostics(new PublishDiagnosticsParams(file.toUri(), report.items));
        }
    }

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        FileStore.open(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        completionIndexScheduler.scheduleProjectBootstrapIfNeeded("didOpen");
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        var refreshCompletionIndex = analyzeActiveDocumentChange(file, params.contentChanges);
        if (completionSnapshotRef.get().scope() == CompletionIndexScope.EMPTY) {
            completionIndexScheduler.scheduleActiveBootstrapIfNeeded("didChangeActiveBootstrap");
        } else if (refreshCompletionIndex) {
            completionIndexScheduler.scheduleRefresh(
                    List.of(file),
                    "didChange",
                    COMPLETION_INDEX_DEBOUNCE_MS,
                    CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
        }
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);
    }

    @Override
    public List<CodeAction> codeAction(CodeActionParams params) {
        var provider = new CodeActionProvider(getOrCreateCompiler(), rewriteRegistry);
        if (params.context.diagnostics.isEmpty()) {
            return provider.codeActionsForCursor(params);
        } else {
            return provider.codeActionForDiagnostics(params);
        }
    }

    @Override
    public CodeAction resolveCodeAction(CodeAction action) {
        if (action.data == null || !action.data.isJsonPrimitive()) return action;
        var id = action.data.getAsString();
        var rewrite = rewriteRegistry.remove(id);
        if (rewrite == null) return action;
        var edits = rewrite.rewrite(getOrCreateCompiler());
        if (edits == null || edits == org.javacs.rewrite.Rewrite.CANCELLED) return action;
        action.edit = new WorkspaceEdit();
        for (var entry : edits.entrySet()) {
            action.edit.changes.put(entry.getKey().toUri(), List.of(entry.getValue()));
        }
        return action;
    }

    @Override
    public Object executeCommand(ExecuteCommandParams params) {
        if ("java.pickAndGenerate".equals(params.command)) {
            var args = params.arguments;
            if (args == null || args.size() < 3) return null;
            var fields = args.get(2).getAsString();
            return Map.of(
                    "action", "pickFields",
                    "className", args.get(0).getAsString(),
                    "methodKind", args.get(1).getAsString(),
                    "fields", fields);
        }
        if ("java.generateFields".equals(params.command)) {
            var args = params.arguments;
            if (args == null || args.size() < 3) return null;
            var className = args.get(0).getAsString();
            var methodKind = args.get(1).getAsString();
            var selectedFields = new java.util.HashSet<String>();
            for (var part : args.get(2).getAsString().split(",")) {
                var trimmed = part.trim();
                if (!trimmed.isEmpty()) selectedFields.add(trimmed);
            }
            if (selectedFields.isEmpty()) return null;
            var rewrite = new org.javacs.rewrite.GenerateMethods(className, methodKind, 0, selectedFields);
            var edits = rewrite.rewrite(getOrCreateCompiler());
            if (edits != null && !edits.isEmpty()) {
                var workspaceEdit = new WorkspaceEdit();
                for (var entry : edits.entrySet()) {
                    workspaceEdit.changes.put(entry.getKey().toUri(), Arrays.asList(entry.getValue()));
                }
                return workspaceEdit;
            }
        }
        return null;
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        FileStore.save(file);
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
            var active = filterJavaFiles(FileStore.activeDocuments());
            if (active.isEmpty()) {
                return;
            }
            scheduleRefresh(active, trigger, 0, CompletionIndexRefreshMode.ACTIVE_DOCUMENT_BOOTSTRAP);
        }

        /** Queue a full workspace bootstrap unless the published scope is already workspace-wide. */
        void scheduleProjectBootstrapIfNeeded(String trigger) {
            if (completionSnapshotRef.get().scope() == CompletionIndexScope.WORKSPACE) {
                return;
            }
            synchronized (JavaLanguageServer.this) {
                if (pendingCompletionIndex != null && !pendingCompletionIndex.isDone()) {
                    return;
                }
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
            synchronized (completionIndexCompileMutex) {
                var started = Instant.now();
                CompileTask task = null;
                String bootstrapProgressToken = null;
                try {
                    var progressLabel = switch (mode) {
                        case ACTIVE_DOCUMENT_BOOTSTRAP -> "Indexing open files";
                        case FULL_REBUILD -> "Indexing workspace";
                        case WORKSPACE_DECLARATION_MERGE -> "Updating index";
                    };
                    bootstrapProgressToken = beginWorkDoneProgress("Index", progressLabel);
                    var compiler = indexCompiler;
                    WorkspaceTypeIndex nextIndex;
                    Instant indexStarted;
                    if (mode == CompletionIndexRefreshMode.FULL_REBUILD) {
                        // Parse-only path: ~15x faster than compilation for large workspaces.
                        // Type names in the index are raw parse-tree strings; ParseTypeResolver
                        // resolves them at query time. Inherited external members are resolved
                        // lazily by ExternalBinaryTypeIndex.
                        reportWorkDoneProgress(bootstrapProgressToken,
                                "Parsing " + files.size() + " files");
                        var parseTasks = compiler.parseAll(files);
                        if (revision != completionIndexRevision.get()) {
                            LOG.fine(String.format(
                                    "[perf] completion_index_refresh_skip trigger=%s phase=post_parse expected=%d current=%d",
                                    trigger, revision, completionIndexRevision.get()));
                            endWorkDoneProgress(bootstrapProgressToken, null);
                            return;
                        }
                        indexStarted = Instant.now();
                        nextIndex = WorkspaceTypeIndex.fromParseTrees(parseTasks);
                    } else {
                        // WORKSPACE_DECLARATION_MERGE: single-file compile with AP for accurate
                        // erased parameter types and Lombok-generated members.
                        // ACTIVE_DOCUMENT_BOOTSTRAP: compile open files with AP so Lombok
                        // classes show their generated accessors immediately.
                        task = compiler.compileFastWithProcessors(files.toArray(Path[]::new));
                        if (revision != completionIndexRevision.get()) {
                            LOG.fine(String.format(
                                    "[perf] completion_index_refresh_skip trigger=%s phase=post_compile expected=%d current=%d",
                                    trigger, revision, completionIndexRevision.get()));
                            endWorkDoneProgress(bootstrapProgressToken, null);
                            return;
                        }
                        indexStarted = Instant.now();
                        reportWorkDoneProgress(bootstrapProgressToken,
                                "Compiled " + files.size() + " files");
                        nextIndex = buildIndex(task, mode, compiler);
                    }
                    if (revision != completionIndexRevision.get()) {
                        LOG.fine(String.format(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_index expected=%d current=%d",
                                trigger,
                                revision,
                                completionIndexRevision.get()));
                        endWorkDoneProgress(bootstrapProgressToken, null);
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
                        if (scope == CompletionIndexScope.ACTIVE) {
                            completionIndexExecutor.schedule(
                                    () -> scheduleProjectBootstrapIfNeeded("active-bootstrap-upgrade"),
                                    0,
                                    TimeUnit.MILLISECONDS);
                        }
                    }
                    LOG.info(String.format(
                            "[perf] index installed trigger=%s version=%d types=%d took=%dms",
                            trigger,
                            indexVersion,
                            nextIndex == null ? 0 : nextIndex.size(),
                            Duration.between(started, Instant.now()).toMillis()));
                    endWorkDoneProgress(bootstrapProgressToken, "Index ready");
                    var totalMs = Duration.between(started, Instant.now()).toMillis();
                    LOG.fine(String.format(
                            "[perf] completion_index_refresh trigger=%s files=%d version=%d mode=%s compile=%dms total=%dms",
                            trigger,
                            files.size(),
                            indexVersion,
                            mode.name().toLowerCase(),
                            Duration.between(started, indexStarted).toMillis(),
                            totalMs));
                } catch (ReusableCompiler.TaskCreationException e) {
                    endWorkDoneProgress(bootstrapProgressToken, "Index failed");
                    LOG.warning(
                            String.format(
                                    "[completion] index refresh failed trigger=%s files=%d reason=%s",
                                    trigger,
                                    files.size(),
                                    e.getMessage()));
                    LOG.fine(e.toString());
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
