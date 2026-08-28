package org.javacs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.tools.JavaFileObject;
import org.javacs.action.CodeActionProvider;
import org.javacs.provider.CompletionProvider;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.provider.SignatureProvider;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.index.TypeIndexRouter;
import org.javacs.fold.FoldProvider;
import org.javacs.provider.HoverProvider;
import org.javacs.provider.SymbolProvider;
import org.javacs.lens.CodeLensProvider;
import org.javacs.lsp.*;
import org.javacs.markup.ErrorProvider;
import org.javacs.provider.DefinitionProvider;
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

    private static final long COMPLETION_INDEX_DEBOUNCE_MS = 400;
    private static final long COMPLETION_BOOTSTRAP_WAIT_MS = 700;
    private static final long COMPLETION_BOOTSTRAP_POLL_MS = 25;
    private static final long NAVIGATION_BOOTSTRAP_WAIT_MS = 1500;
    /** Above this file count, defer full workspace indexing to avoid OOM on large modules. */
    static final int LARGE_WORKSPACE_THRESHOLD = 1000;

    private record TypeIndexAvailability(
            long versionBefore, long versionAfter, CompletionIndexScope scopeBefore, CompletionIndexScope scopeAfter, long waitMs) {}
    record CompletionSnapshot(
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
    Path workspaceRoot;
    private final LanguageClient client;
    final CompletionIndexScheduler completionIndexScheduler = new CompletionIndexScheduler(this);

    // Single compiler — all requests (interactive + diagnostics) run on the main LSP thread.
    // parse() is thread-safe (standalone javac tasks), so background index builds share the instance.
    volatile JavaCompilerService compiler;
    final ModuleCompilerRegistry moduleRegistry = new ModuleCompilerRegistry(this);
    volatile Path activeModuleFile;

    // Gradle module graph — populated during createCompilers() for Gradle projects.
    // Null until first compiler initialization; EMPTY for non-Gradle projects.
    ModuleGraph moduleGraph = ModuleGraph.EMPTY;

    private JsonObject appliedCompilerSettings = new JsonObject();
    private JsonObject settings = new JsonObject();
    ProgressReporter progress;

    final ScheduledExecutorService completionIndexExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("javacs-completion-index").factory());

    /** Monotonic revision for queued completion-index refreshes; newer schedules cancel older runs. */
    final AtomicLong completionIndexRevision = new AtomicLong();
    private final AtomicLong diagnosticRevision = new AtomicLong();
    final AtomicLong completionIndexVersion = new AtomicLong();
    final AtomicReference<CompletionSnapshot> completionSnapshotRef =
            new AtomicReference<>(CompletionSnapshot.EMPTY);
    /** Serialize completion-index refreshes so one compile/index install owns the refresh lane. */
    final Object completionIndexCompileMutex = new Object();


    private final Set<String> shownWorkspaceWarnings = ConcurrentHashMap.newKeySet();

    /** Pairs a pending Rewrite with the compiler it was computed against. */
    private record PendingRewrite(org.javacs.rewrite.Rewrite rewrite, CompilerProvider compiler) {}

    /** LRU cache of pending code actions keyed by UUID, used for codeAction/resolve. */
    private static final int REWRITE_REGISTRY_MAX = 200;
    private final Map<String, PendingRewrite> pendingRewrites =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(REWRITE_REGISTRY_MAX, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, PendingRewrite> eldest) {
                            return size() > REWRITE_REGISTRY_MAX;
                        }
                    });

    enum CompletionIndexRefreshMode {
        ACTIVE_DOCUMENT_BOOTSTRAP,
        FULL_REBUILD,
        WORKSPACE_DECLARATION_MERGE
    }

    enum CompletionIndexScope {
        EMPTY,
        ACTIVE,
        WORKSPACE
    }

    /** Return the current compiler. Compiler recreation happens at explicit event boundaries. */
    synchronized JavaCompilerService getOrCreateCompiler() {
        if (compiler == null) {
            LOG.warning("Compiler has not been initialized");
            return null;
        }
        return compiler;
    }

    synchronized JavaCompilerService compilerFor(Path file) {
        return moduleRegistry.compilerFor(file);
    }

    ExternalBinaryTypeIndex externalIndexForIndexing(
            Path file, JavaCompilerService parsingCompiler) {
        return moduleRegistry.externalIndexForIndexing(file, parsingCompiler);
    }

    boolean canReferenceModule(Path declaration, Path candidate) {
        return moduleRegistry.canReferenceModule(declaration, candidate);
    }


    void publishCompletionSnapshot(
            WorkspaceTypeIndex workspaceIndex,
            ExternalBinaryTypeIndex externalIndex,
            long version,
            CompletionIndexScope scope) {
        var snapshot = CompletionSnapshot.create(workspaceIndex, externalIndex, version, scope);
        completionIndexVersion.set(snapshot.version());
        completionSnapshotRef.set(snapshot);
    }



    private void publishExternalBinaryIndexSnapshot() {
        var currentSnapshot = completionSnapshotRef.get();
        // Use the module-scoped compiler when available — scanning 107k classes from
        // the union classpath is too slow. The active module's ~15k classes is sufficient.
        var externalIndex = new ExternalBinaryTypeIndex(getOrCreateCompiler());
        publishCompletionSnapshot(
                currentSnapshot.workspaceIndex(),
                externalIndex,
                currentSnapshot.version(),
                currentSnapshot.scope());
    }

    private synchronized void initializeCompilers() {
        createCompilers();
        appliedCompilerSettings = ServerSettings.compilerSettingsSnapshot(settings);
        publishExternalBinaryIndexSnapshot();
    }

    private void notifyWorkspaceInfo() {
        var started = Instant.now();
        var info = new JsonObject();
        if (moduleRegistry.inferredConfig != null && moduleGraph != ModuleGraph.EMPTY) {
            info.addProperty("buildSystem", "maven");
            info.addProperty("buildRoot", moduleRegistry.inferredConfig.buildRoot().toString());
            var sourceRoots = new JsonArray();
            moduleGraph.modules().values().stream()
                    .flatMap(module -> module.sourceDirs().stream())
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .distinct()
                    .sorted()
                    .forEach(sourceRoots::add);
            info.add("sourceRoots", sourceRoots);
            LOG.info(String.format(
                    "[maven] workspace_info modules=%d source_roots=%d took=%dms",
                    moduleGraph.modules().size(), sourceRoots.size(),
                    Duration.between(started, Instant.now()).toMillis()));
        }
        client.customNotification("java/workspaceInfo", info);
    }

    private synchronized void recreateCompilersAndRefreshState(String trigger) {
        var started = Instant.now();
        MavenTooling.invalidateCacheInputsSnapshot();
        completionIndexScheduler.cancel(trigger);
        try {
            createCompilers();
            appliedCompilerSettings = ServerSettings.compilerSettingsSnapshot(settings);
            publishExternalBinaryIndexSnapshot();
            refreshStateForCompilerRecreated();
            refreshDiagnostics();
        } finally {
            notifyWorkspaceInfo();
            LOG.info(String.format(
                    "[perf] compiler_recreate trigger=%s took=%dms",
                    trigger, Duration.between(started, Instant.now()).toMillis()));
        }
    }

    void refreshDiagnostics() {
        diagnosticRevision.incrementAndGet();
        client.customNotification("workspace/diagnostic/refresh", null);
    }

    private String diagnosticResultId() {
        return FileStore.contentRevision() + ":" + diagnosticRevision.get();
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

    static List<Path> filterJavaFiles(Collection<Path> files) {
        var javaFiles = new ArrayList<Path>();
        for (var file : files) {
            if (FileStore.isJavaFile(file)) {
                javaFiles.add(file);
            }
        }
        return javaFiles;
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
        var hasRequiredScope = !requireWorkspaceScope
                || currentScope == CompletionIndexScope.WORKSPACE
                || (FileStore.all().size() > LARGE_WORKSPACE_THRESHOLD
                        && currentScope == CompletionIndexScope.ACTIVE);
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
        moduleRegistry.clear();
        pendingRewrites.clear();
        var started = Instant.now();
        var progressToken = progress.begin("Configure javac", "Finding source roots");

        var serverSettings = new ServerSettings(workspaceRoot, settings);
        var externalDependencies = serverSettings.externalDependencies();
        var classPath = serverSettings.classPath();
        var userExtraArgs = serverSettings.extraCompilerArgs();
        var addExports = serverSettings.addExports();
        moduleRegistry.configuredUserCompilerArgs = List.copyOf(userExtraArgs);
        moduleRegistry.configuredAddExports = Set.copyOf(addExports);
        List<String> extraArgs = userExtraArgs;
        var settingsLoaded = Instant.now();
        Set<Path> resolvedDocPath;
        InferConfig infer = null;
        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            resolvedDocPath = serverSettings.docPath();
            LOG.info(String.format(
                    "[perf] compiler_config_inference mode=explicit classpath=%d docpath=%d took=%dms",
                    classPath.size(),
                    resolvedDocPath.size(),
                    Duration.between(settingsLoaded, Instant.now()).toMillis()));
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            infer = new InferConfig(workspaceRoot, externalDependencies);
            var isGradle = infer.buildSystem() == InferConfig.BuildSystem.GRADLE;
            var isMaven = infer.buildSystem() == InferConfig.BuildSystem.MAVEN;
            if (isMaven) {
                moduleGraph = infer.moduleGraph();
                moduleRegistry.moduleScopedMaven = moduleGraph.modules().size() > 1;
                moduleRegistry.inferredConfig = infer;
            }

            progress.report(progressToken,
                    isGradle ? "Resolving Gradle dependencies (may take a minute on first run)"
                    : isMaven ? "Resolving Maven dependencies"
                    : "Resolving dependencies");
            var inferClassPathStarted = Instant.now();
            classPath = moduleRegistry.moduleScopedMaven ? Set.of() : infer.classPath();
            var inferredClassPath = Instant.now();

            progress.report(progressToken, "Inferring doc path");
            var inferDocPathStarted = Instant.now();
            resolvedDocPath = moduleRegistry.moduleScopedMaven ? Set.of() : infer.buildDocPath();
            LOG.info(String.format(
                    "[perf] compiler_config_inference mode=inferred build_system=%s external=%d classpath=%d docpath=%d classpath_infer=%dms docpath_infer=%dms total=%dms",
                    isGradle ? "gradle" : isMaven ? "maven" : "unknown",
                    externalDependencies.size(),
                    classPath.size(),
                    resolvedDocPath.size(),
                    Duration.between(inferClassPathStarted, inferredClassPath).toMillis(),
                    Duration.between(inferDocPathStarted, Instant.now()).toMillis(),
                    Duration.between(settingsLoaded, Instant.now()).toMillis()));
        }
        var inferenceFinished = Instant.now();
        var inferForGraph = infer != null ? infer : new InferConfig(workspaceRoot, externalDependencies);
        if (!moduleRegistry.moduleScopedMaven) {
            extraArgs = selectCompilerArgs(userExtraArgs, inferForGraph).args();
        }

        // Populate module graph and compile inter-module deps before creating compilers.
        if (moduleRegistry.inferredConfig == null
                && inferForGraph.buildSystem() == InferConfig.BuildSystem.MAVEN) {
            moduleRegistry.inferredConfig = inferForGraph;
        }
        if (!moduleRegistry.moduleScopedMaven) moduleGraph = inferForGraph.moduleGraph();
        if (moduleGraph != ModuleGraph.EMPTY) {
            var isGradleProject = inferForGraph.buildSystem() == InferConfig.BuildSystem.GRADLE;
            LOG.info(String.format("[module-graph] loaded modules=%d build_system=%s",
                    moduleGraph.modules().size(), isGradleProject ? "gradle" : "maven"));
            // Warn once if Maven sibling modules lack build output
            if (!isGradleProject) {
                // Scope workspace to active module + transitive dependency source dirs.
                var moduleFile = activeModuleFile == null ? workspaceRoot : activeModuleFile;
                var activeModule = moduleGraph.moduleForFile(moduleFile);
                var openedReactorRoot = activeModule.isPresent()
                        && moduleGraph.modules().size() > 1
                        && activeModule.get().projectPath().equals(":")
                        && workspaceRoot.toAbsolutePath().normalize().equals(activeModule.get().projectDir());
                if (activeModule.isPresent() && !openedReactorRoot) {
                    var scopedRoots = new LinkedHashSet<Path>();
                    for (var dir : moduleGraph.transitiveSourceDirs(activeModule.get().projectPath(), false)) {
                        if (Files.exists(dir)) scopedRoots.add(dir);
                    }
                    if (!scopedRoots.isEmpty()) {
                        FileStore.setWorkspaceRoots(scopedRoots);
                        LOG.info(String.format("[maven] workspace scoped to %d source dirs for %s (files=%d)",
                                scopedRoots.size(), activeModule.get().projectPath(), FileStore.all().size()));
                    }
                }
            }
            // Gradle keeps using existing build outputs. Maven module outputs are refreshed lazily
            // when the first file from that module is opened.
            if (isGradleProject && moduleGraph.modules().size() > 1) {
                moduleRegistry.moduleScopedGradle = true;
                var activeModule = moduleGraph.moduleForFile(workspaceRoot);
                if (activeModule.isPresent()) {
                    // Scope workspace to active module's own source dirs only.
                    // Type resolution for deps uses the classpath (compiled .class files).
                    // Other modules' sources are added lazily in didOpenTextDocument.
                    var scopedRoots = new LinkedHashSet<Path>();
                    for (var dir : activeModule.get().sourceDirs()) {
                        if (Files.exists(dir)) scopedRoots.add(dir);
                    }
                    if (!scopedRoots.isEmpty()) {
                        FileStore.setWorkspaceRoots(scopedRoots);
                        LOG.info(String.format("[gradle] scoped workspace to %d source dirs for %s (files=%d)",
                                scopedRoots.size(), activeModule.get().projectPath(), FileStore.all().size()));
                    }
                    // Phase 2: Resolve classpath for active module + transitive deps
                    var targets = moduleGraph.transitiveModulePathsIncludingSelf(activeModule.get().projectPath());
                    var resolved = GradleTooling.resolveClasspath(workspaceRoot, targets);
                    if (resolved != GradleTooling.ModuleClasspath.EMPTY && !resolved.modules().isEmpty()) {
                        // Create compiler for active module with its classpath
                        var activeModuleClasspath = resolved.modules().get(activeModule.get().projectPath());
                        if (activeModuleClasspath != null) {
                            var updatedClasspath = new LinkedHashSet<>(activeModuleClasspath.externalClasspath());
                            updatedClasspath.addAll(moduleGraph.transitiveClassOutputDirs(activeModule.get().projectPath()));
                            classPath = updatedClasspath;
                        }
                        // Register all resolved modules
                        for (var entry : resolved.modules().entrySet()) {
                            var modulePath = entry.getKey();
                            moduleRegistry.resolvedGradleModules.add(modulePath);
                        }
                    } else {
                        // Fallback: use existing build outputs like before
                        var depDirs = moduleGraph.transitiveClassOutputDirs(activeModule.get().projectPath());
                        int missingDeps = 0;
                        for (var dir : depDirs) {
                            if (!Files.isDirectory(dir)) missingDeps++;
                        }
                        if (missingDeps > 0) {
                            warnUserOnce("gradle_missing_build_output",
                                    String.format("%d module dependencies lack build/classes/java/main. Run './gradlew compileJava' for full cross-module resolution.", missingDeps));
                        }
                        var updatedClasspath = new LinkedHashSet<>(classPath);
                        updatedClasspath.addAll(moduleGraph.transitiveClassOutputDirs(activeModule.get().projectPath()));
                        classPath = updatedClasspath;
                    }
                }
            }
        }

        progress.end(progressToken, "Configured javac");

        compiler = new JavaCompilerService(classPath, resolvedDocPath, addExports, extraArgs);
        compiler.setModuleGraph(moduleGraph);

        // Register active module compiler in moduleRegistry.gradleModuleCompilers so compilerFor() finds it
        if (moduleRegistry.moduleScopedGradle && moduleGraph != ModuleGraph.EMPTY) {
            var activeModule = moduleGraph.moduleForFile(workspaceRoot);
            if (activeModule.isPresent()) {
                var sourceRoots = new LinkedHashSet<>(activeModule.get().sourceDirs());
                compiler.setSourceRoots(sourceRoots);
                var mainKey = "gradle:" + activeModule.get().projectPath() + "#main";
                moduleRegistry.gradleModuleCompilers.put(
                        mainKey, new ModuleCompilerRegistry.ModuleCompiler(compiler, new ExternalBinaryTypeIndex(compiler), sourceRoots));
            }
        }

        // Flush any warnings from Maven resolution (e.g. broken wrapper) to the user
        for (var warning : MavenTooling.flushWarnings()) {
            var params = new org.javacs.lsp.ShowMessageParams();
            params.type = org.javacs.lsp.MessageType.Warning;
            params.message = warning;
            client.showMessage(params);
        }

        LOG.info(String.format(
                "[perf] create_compilers classpath=%d docpath=%d extra_args=%d add_exports=%d settings=%dms inference=%dms total=%dms",
                classPath.size(),
                resolvedDocPath.size(),
                extraArgs.size(),
                addExports.size(),
                Duration.between(started, settingsLoaded).toMillis(),
                Duration.between(settingsLoaded, inferenceFinished).toMillis(),
                Duration.between(started, Instant.now()).toMillis()));
    }


    private InferConfig.CompilerArgs selectCompilerArgs(List<String> userExtraArgs, InferConfig infer) {
        if (hasExplicitJavaLevelOverride(userExtraArgs)) {
            return new InferConfig.CompilerArgs(userExtraArgs, "user", false);
        }
        // Gradle: use the active module's source compatibility
        if (moduleGraph != ModuleGraph.EMPTY) {
            var activeModule = moduleGraph.moduleForFile(workspaceRoot);
            if (activeModule.isPresent() && activeModule.get().sourceCompatibility() != null
                    && !activeModule.get().sourceCompatibility().isBlank()) {
                var merged = new ArrayList<String>(userExtraArgs);
                merged.add("--release");
                merged.add(activeModule.get().sourceCompatibility());
                return new InferConfig.CompilerArgs(merged, "gradle:" + activeModule.get().sourceCompatibility(), false);
            }
        }
        if (infer.buildSystem() != InferConfig.BuildSystem.MAVEN) {
            return new InferConfig.CompilerArgs(userExtraArgs, "none", false);
        }
        var inferred = infer.compilerArgs();
        if (inferred.mixedModules()) {
            warnUserOnce(
                    "maven_mixed_release_fallback",
                    "JLS detected mixed Maven module Java levels and fell back to the runtime/default compiler behavior for this workspace.");
            return new InferConfig.CompilerArgs(userExtraArgs, "fallback_mixed_modules", true);
        }
        if (inferred.args().isEmpty()) {
            return new InferConfig.CompilerArgs(userExtraArgs, "none", false);
        }
        var merged = new ArrayList<String>(userExtraArgs);
        merged.addAll(inferred.args());
        return new InferConfig.CompilerArgs(merged, inferred.source(), false);
    }

    static boolean hasExplicitJavaLevelOverride(List<String> extraArgs) {
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



    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = Paths.get(params.rootUri);
        this.progress = new ProgressReporter(client, ProgressReporter.supportsWorkDoneProgress(params.capabilities));
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
        diagnosticOptions.addProperty("interFileDependencies", true);
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
        try {
            initializeCompilers();
        } finally {
            notifyWorkspaceInfo();
        }
        if (!moduleRegistry.moduleScopedMaven && !moduleRegistry.moduleScopedGradle) getOrCreateCompiler().fullCompileWithAP();
    }

    @Override
    public void shutdown() {
        completionIndexScheduler.shutdown();
        completionIndexExecutor.shutdownNow();
        MavenTooling.destroyAllProcesses();
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
    public List<SymbolInformation> documentSymbol(DocumentSymbolParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        if (compiler == null) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new SymbolProvider(getOrCreateCompiler()).documentSymbols(file);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var java = change.settings.getAsJsonObject().get("java");
        LOG.info(String.format("Received java settings %s", java));
        var nextSettings = new JsonObject();
        if (java != null && !java.isJsonNull()) {
            nextSettings = java.getAsJsonObject();
        }
        var nextCompilerSettings = ServerSettings.compilerSettingsSnapshot(nextSettings);
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
                if ("pom.xml".equals(name)) {
                    moduleRegistry.failedMavenModules.clear();
                }
                compilerInputsChanged = true;
            }
        }
        if (refreshActiveDiagnostics) {
            refreshDiagnostics();
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
        var provider = new CompletionProvider(
                compilerFor(file), moduleRegistry.typeIndexFor(file), snapshot.version(), moduleRegistry.moduleCompilerKey(file));
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
        var data = unresolved.data == null
                ? null
                : JsonHelper.GSON.fromJson(unresolved.data, CompletionData.class);
        var context = data == null ? null : moduleRegistry.mavenModuleCompilers.get(data.compilerId);
        if (context == null && data != null) context = moduleRegistry.gradleModuleCompilers.get(data.compilerId);
        var requestCompiler = context == null ? getOrCreateCompiler() : context.compiler;
        var index = context == null ? snapshot.typeIndex() : context.typeIndex(snapshot);
        new CompletionProvider(requestCompiler, index, snapshot.version(), data == null ? null : data.compilerId)
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
        var content = new HoverProvider(compilerFor(file)).hover(file, line, column);
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
        var signatureProvider = new SignatureProvider(compilerFor(file), moduleRegistry.typeIndexFor(file)).signatureHelp(file, line, column);
        if (signatureProvider == SignatureProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(signatureProvider);
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        List<Location> found;
        try {
            found = new DefinitionProvider(compilerFor(file),file, line, column).find();
        } catch (RuntimeException e) {
            // javac internal error (NPE in Types.sideCast on complex generics).
            // Don't crash the server — return empty.
            LOG.warning(String.format(
                    "[definition] compiler error file=%s: %s",
                    file.getFileName(), e.getClass().getSimpleName()));
            return Optional.empty();
        }
        if (found == DefinitionProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public Optional<List<Location>> findReferences(ReferenceParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        moduleRegistry.includeMavenReferenceSources();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        ensureTypeIndexReady("referencesBootstrap", NAVIGATION_BOOTSTRAP_WAIT_MS, true);
        var found =
                new ReferenceProvider(
                                compilerFor(file), file, line, column, this::compilerFor,
                                this::canReferenceModule, moduleRegistry::batchResolveModulesForFiles)
                        .find();
        if (found == ReferenceProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        var task = compilerFor(file).parse(file);
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
        var requestCompiler = compilerFor(file);
        var fixImports = new AutoFixImports(file).rewrite(requestCompiler).get(file);
        Collections.addAll(edits, fixImports);
        var addOverrides = new AutoAddOverrides(file).rewrite(requestCompiler).get(file);
        Collections.addAll(edits, addOverrides);
        return edits;
    }

    @Override
    public List<FoldingRange> foldingRange(FoldingRangeParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new FoldProvider(compilerFor(file)).foldingRanges(file);
    }

    @Override
    public Optional<List<InlayHint>> inlayHint(InlayHintParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.of(List.of());
        if (compiler == null) return Optional.of(List.of());
        var file = Paths.get(params.textDocument.uri);
        return Optional.of(new InlayHintProvider(compilerFor(file), moduleRegistry.typeIndexFor(file)).inlayHints(file, params.range));
    }

    private final RenameHandler renameHandler = new RenameHandler(this);

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        return renameHandler.prepareRename(params);
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        return renameHandler.rename(params, client);
    }

    @Override
    public void renameApplied(DidChangeWatchedFilesParams params) {
        renameHandler.renameApplied(params);
    }

    @Override
    public DocumentDiagnosticReport textDocumentDiagnostic(DocumentDiagnosticParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) {
            return new DocumentDiagnosticReport(List.of());
        }
        var file = Paths.get(params.textDocument.uri);
        try {
            if (compiler == null) {
                return new DocumentDiagnosticReport(List.of());
            }
            ensureTypeIndexReady("diagnosticBootstrap", COMPLETION_BOOTSTRAP_WAIT_MS, false);
            var resultId = diagnosticResultId();
            if (resultId.equals(params.previousResultId)) {
                return new DocumentDiagnosticReport("unchanged", resultId, null);
            }
            LOG.info("[diagnostics] pull_compile_start file=" + file.getFileName());
            var started = System.nanoTime();
            var sources = List.<JavaFileObject>of(new SourceFileObject(file));
            var requestCompiler = compilerFor(file);
            String compileProgressToken = null;
            if (moduleRegistry.moduleScopedMaven || moduleRegistry.moduleScopedGradle) {
                compileProgressToken = progress.begin(
                        "Compiling", "Compiling " + file.getFileName());
            }
            try (var task = requestCompiler.compile(sources)) {
                var durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (compileProgressToken != null) {
                    progress.end(compileProgressToken, "Compiled");
                    compileProgressToken = null;
                }
                var errorProvider = new ErrorProvider(task, requestCompiler, moduleRegistry.typeIndexFor(file));
                var errorReport = errorProvider.errors(Set.of(file.toUri()));
                LOG.info(String.format(
                        "[diagnostics] pull_compile_done file=%s duration=%dms errors=%d",
                        file.getFileName(), durationMs, errorReport.compilerDiagnosticsCount()));
                for (var diagParams : errorReport.diagnostics()) {
                    if (file.toUri().equals(diagParams.uri)) {
                        return new DocumentDiagnosticReport("full", resultId, diagParams.diagnostics);
                    }
                }
                return new DocumentDiagnosticReport("full", resultId, List.of());
            } finally {
                if (compileProgressToken != null) {
                    progress.end(compileProgressToken, "Compiled");
                }
            }
        } catch (Exception e) {
            LOG.warning("[diagnostics] pull_compile_failed file=" + file.getFileName()
                    + " reason=" + e.getMessage());
            return new DocumentDiagnosticReport(List.of());
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
        var file = Paths.get(params.textDocument.uri);
        try {
            var firstModule = activeModuleFile == null;
            if (FileStore.isJavaFile(params.textDocument.uri) && moduleGraph != ModuleGraph.EMPTY) {
                var moduleOpt = moduleGraph.moduleForFile(file);
                if (moduleOpt.isPresent()) {
                    var module = moduleOpt.get();
                    var fileInModule = module.sourceDirs().stream().anyMatch(file::startsWith);
                    if (fileInModule) {
                        // Lazy Gradle module resolution: resolve unresolved modules on first open
                        if (moduleRegistry.moduleScopedGradle && !moduleRegistry.resolvedGradleModules.contains(module.projectPath())) {
                            moduleRegistry.resolveGradleModule(module);
                        }
                        Set<Path> sourceDirs;
                        if (moduleRegistry.moduleScopedMaven) {
                            compilerFor(file);
                            sourceDirs = moduleRegistry.mavenModuleCompilers.get(moduleRegistry.moduleCompilerKey(file)).sourceRoots;
                        } else if (moduleRegistry.moduleScopedGradle) {
                            var gradleContext = moduleRegistry.gradleModuleCompilers.get(moduleRegistry.moduleCompilerKey(file));
                            sourceDirs = gradleContext != null
                                    ? gradleContext.sourceRoots
                                    : new LinkedHashSet<>(module.sourceDirs());
                        } else {
                            sourceDirs = new LinkedHashSet<>(module.sourceDirs());
                        }
                        var expanded = firstModule && (moduleRegistry.moduleScopedMaven || moduleRegistry.moduleScopedGradle)
                                ? new LinkedHashSet<Path>()
                                : new LinkedHashSet<>(FileStore.workspaceRoots());
                        var added = new ArrayList<Path>();
                        for (var srcDir : sourceDirs) {
                            if ((firstModule && (moduleRegistry.moduleScopedMaven || moduleRegistry.moduleScopedGradle)
                                            || !FileStore.isWorkspaceFile(srcDir))
                                    && Files.exists(srcDir)) {
                                expanded.add(srcDir);
                                added.add(srcDir);
                            }
                        }
                        if (!expanded.isEmpty() && (!added.isEmpty() || firstModule)) {
                            FileStore.setWorkspaceRoots(expanded);
                            LOG.info("[multi-module] expanded workspace to include " + added.size()
                                    + " source dirs from " + module.projectPath()
                                    + " (files=" + FileStore.all().size() + ")");
                            var newFiles = new ArrayList<Path>();
                            for (var srcDir : added) {
                                for (var f : FileStore.all()) {
                                    if (f.startsWith(srcDir)) newFiles.add(f);
                                }
                            }
                            if (!newFiles.isEmpty() && newFiles.size() <= LARGE_WORKSPACE_THRESHOLD
                                    && completionSnapshotRef.get().scope() != CompletionIndexScope.WORKSPACE) {
                                completionIndexScheduler.scheduleRefresh(
                                        newFiles, "moduleExpand", 0,
                                        CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
                            }
                        }
                    }
                }
            }
            if (moduleRegistry.moduleScopedMaven || moduleRegistry.moduleScopedGradle) activeModuleFile = file;
        } finally {
            FileStore.open(params);
        }
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        // For large workspaces, defer full index — only index the open file initially.
        if (FileStore.all().size() > LARGE_WORKSPACE_THRESHOLD) {
            completionIndexScheduler.scheduleRefresh(
                    List.of(Paths.get(params.textDocument.uri)),
                    "didOpen",
                    0,
                    CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
        } else {
            completionIndexScheduler.scheduleProjectBootstrapIfNeeded("didOpen");
        }
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        if (completionSnapshotRef.get().scope() == CompletionIndexScope.EMPTY) {
            completionIndexScheduler.scheduleActiveBootstrapIfNeeded("didChangeActiveBootstrap");
        } else {
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
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)
                || completionSnapshotRef.get().scope() == CompletionIndexScope.EMPTY) return;
        completionIndexScheduler.scheduleRefresh(
                List.of(Paths.get(params.textDocument.uri)),
                "didClose",
                0,
                CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
    }

    @Override
    public List<CodeAction> codeAction(CodeActionParams params) {
        var requestCompiler = compilerFor(Paths.get(params.textDocument.uri));
        // Forwarding map: provider stores Rewrite by UUID; we pair it with the compiler atomically.
        var rewriteSink = new java.util.AbstractMap<String, org.javacs.rewrite.Rewrite>() {
            @Override public org.javacs.rewrite.Rewrite put(String id, org.javacs.rewrite.Rewrite rw) {
                pendingRewrites.put(id, new PendingRewrite(rw, requestCompiler));
                return null;
            }
            @Override public Set<Map.Entry<String, org.javacs.rewrite.Rewrite>> entrySet() { return Set.of(); }
        };
        var provider = new CodeActionProvider(requestCompiler, rewriteSink);
        return params.context.diagnostics.isEmpty()
                ? provider.codeActionsForCursor(params)
                : provider.codeActionForDiagnostics(params);
    }

    @Override
    public CodeAction resolveCodeAction(CodeAction action) {
        if (action.data == null || !action.data.isJsonPrimitive()) return action;
        var id = action.data.getAsString();
        var pending = pendingRewrites.remove(id);
        if (pending == null) return action;
        var edits = pending.rewrite().rewrite(pending.compiler() != null ? pending.compiler() : getOrCreateCompiler());
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
            if (selectedFields.isEmpty() && !"constructor".equals(methodKind)) return null;
            var rewrite = new GenerateMethods(className, methodKind, 0, selectedFields);
            var edits = rewrite.rewrite(moduleRegistry.compilerForClass(className));
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
        compilerFor(file).refreshBuildOutput(file);
    }

}
