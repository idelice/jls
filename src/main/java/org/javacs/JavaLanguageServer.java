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
import org.javacs.provider.ImplementationProvider;
import org.javacs.provider.ReferenceProvider;
import org.javacs.provider.TypeDefinitionProvider;
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

    /** Brief wait so a small project can answer its first completion from a fresh index. */
    private static final long INDEX_WAIT_MS = 200;

    record CompletionSnapshot(
            WorkspaceTypeIndex workspaceIndex,
            ExternalBinaryTypeIndex externalIndex,
            TypeIndexRouter typeIndex,
            long version) {
        private static final CompletionSnapshot EMPTY =
                create(WorkspaceTypeIndex.EMPTY, ExternalBinaryTypeIndex.EMPTY, 0);

        private static CompletionSnapshot create(
                WorkspaceTypeIndex workspaceIndex,
                ExternalBinaryTypeIndex externalIndex,
                long version) {
            var safeWorkspace = workspaceIndex == null ? WorkspaceTypeIndex.EMPTY : workspaceIndex;
            var safeExternal = externalIndex == null  ? ExternalBinaryTypeIndex.EMPTY : externalIndex;
            return new CompletionSnapshot(
                    safeWorkspace,
                    safeExternal,
                    new TypeIndexRouter(safeWorkspace, safeExternal),
                    Math.max(0L, version));
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

    private final AtomicLong diagnosticRevision = new AtomicLong();
    final AtomicLong completionIndexVersion = new AtomicLong();
    final AtomicReference<CompletionSnapshot> completionSnapshotRef =
            new AtomicReference<>(CompletionSnapshot.EMPTY);


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


    /** Replace the workspace declarations, keeping the external (dependency) index. */
    void publishCompletionSnapshot(WorkspaceTypeIndex workspaceIndex, long version) {
        var current = completionSnapshotRef.get();
        publishCompletionSnapshot(workspaceIndex, current.externalIndex(), version);
    }

    void publishCompletionSnapshot(
            WorkspaceTypeIndex workspaceIndex, ExternalBinaryTypeIndex externalIndex, long version) {
        var snapshot = CompletionSnapshot.create(workspaceIndex, externalIndex, version);
        completionIndexVersion.set(snapshot.version());
        completionSnapshotRef.set(snapshot);
    }



    private void publishExternalBinaryIndexSnapshot() {
        var currentSnapshot = completionSnapshotRef.get();
        // Use the module-scoped compiler when available — scanning 107k classes from
        // the union classpath is too slow. The active module's ~15k classes is sufficient.
        var externalIndex = new ExternalBinaryTypeIndex(getOrCreateCompiler());
        publishCompletionSnapshot(
                currentSnapshot.workspaceIndex(), externalIndex, currentSnapshot.version());
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
        completionIndexScheduler.reset(trigger);
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

    /** The index and its external index came from the replaced compiler, so rebuild from scratch. */
    private void refreshStateForCompilerRecreated() {
        completionIndexScheduler.reset("compilerRecreated");
        var active = filterJavaFiles(FileStore.activeDocuments());
        if (!active.isEmpty()) completionIndexScheduler.ensureIndexed(active.get(0));
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

    /** Configure source ownership once; module dependencies resolve when a file needs them. */
    private void createCompilers() {
        Objects.requireNonNull(workspaceRoot, "Workspace has not been initialized");
        moduleRegistry.clear();
        if (compiler != null) compiler.close();
        pendingRewrites.clear();
        var started = System.nanoTime();
        var token = progress.begin("Configure javac", "Reading build metadata");
        try {
            var configured = new ServerSettings(workspaceRoot, settings);
            var infer = new InferConfig(workspaceRoot, configured.externalDependencies());
            moduleGraph = infer.moduleGraph();
            moduleRegistry.inferredConfig = infer;
            moduleRegistry.configuredUserCompilerArgs = List.copyOf(configured.extraCompilerArgs());
            moduleRegistry.configuredAddExports = Set.copyOf(configured.addExports());
            var explicit = !configured.classPath().isEmpty();
            moduleRegistry.moduleScopedMaven = !explicit && !moduleGraph.modules().isEmpty()
                    && infer.buildSystem() == InferConfig.BuildSystem.MAVEN;
            moduleRegistry.moduleScopedGradle = !explicit && !moduleGraph.modules().isEmpty()
                    && infer.buildSystem() == InferConfig.BuildSystem.GRADLE;
            var scoped = moduleRegistry.moduleScopedMaven || moduleRegistry.moduleScopedGradle;
            var roots = new LinkedHashSet<Path>();
            for (var module : moduleGraph.modules().values()) roots.addAll(module.sourceDirs());
            if (!roots.isEmpty()) FileStore.setWorkspaceRoots(roots);
            var classpath = explicit ? configured.classPath() : scoped ? Set.<Path>of() : infer.classPath();
            var docs = explicit ? configured.docPath() : scoped ? Set.<Path>of() : infer.buildDocPath();
            var args = scoped ? configured.extraCompilerArgs() : selectCompilerArgs(configured.extraCompilerArgs(), infer).args();
            compiler = new JavaCompilerService(moduleGraph.externalClasspath(classpath), docs, configured.addExports(), args);
            if (!roots.isEmpty()) compiler.setSourceRoots(roots);
            LOG.info("[analysis] configured proc=none workspace_binaries=0 modules=" + moduleGraph.modules().size()
                    + " sources=" + roots.size() + " ms=" + (System.nanoTime() - started) / 1_000_000);
        } finally {
            progress.end(token, "Configured javac");
        }
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
            if ("--release".equals(arg) || arg.startsWith("--release=") || "-source".equals(arg) || "-target".equals(arg)) {
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
        c.addProperty("implementationProvider", true);
        c.addProperty("typeDefinitionProvider", true);
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

    }

    @Override
    public void shutdown() {
        completionIndexScheduler.shutdown();
        completionIndexExecutor.shutdownNow();
        moduleRegistry.close();
        if (compiler != null) compiler.close();
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
                                completionIndexScheduler.filesChanged(List.of(file));
                            }
                        } else {
                            FileStore.externalCreate(file);
                            completionIndexScheduler.filesChanged(List.of(file));
                        }
                        break;
                    case FileChangeType.Changed:
                        FileStore.externalChange(file);
                        if (suppressActiveDocumentWork) {
                            LOG.fine(
                                    "[perf] watched_java_change_skip reason=active_document event=changed file="
                                            + file);
                        } else {
                            completionIndexScheduler.filesChanged(List.of(file));
                        }
                        break;
                    case FileChangeType.Deleted:
                        FileStore.externalDelete(file);
                        // Replacing a deleted file's declarations with nothing removes its types.
                        completionIndexScheduler.filesChanged(List.of(file));
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
                    moduleRegistry.clear();
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
        // Completion is answered from the index. Until it covers this module, report an incomplete
        // list so the client asks again, instead of doing the work on the LSP thread.
        completionIndexScheduler.ensureIndexed(file);
        if (!completionIndexScheduler.awaitReady(INDEX_WAIT_MS)) {
            LOG.info("[completion] index_not_ready file=" + file.getFileName() + " — returning incomplete");
            return Optional.of(new CompletionList(true, List.of()));
        }
        var snapshot = completionSnapshotRef.get();
        var provider = new CompletionProvider(
                compilerFor(file), moduleRegistry.typeIndexFor(file), snapshot.version(), moduleRegistry.moduleCompilerKey(file));
        var list = provider.complete(file, params.position.line + 1, params.position.character + 1);
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        LOG.fine(String.format("[perf] completion_request file=%s took=%dms",
                file.getFileName(), Duration.between(started, Instant.now()).toMillis()));
        return Optional.of(list);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        var snapshot = completionSnapshotRef.get();
        var data = unresolved.data == null
                ? null
                : JsonHelper.GSON.fromJson(unresolved.data, CompletionData.class);
        var context = data == null ? null : moduleRegistry.moduleCompilers.get(data.compilerId);
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
    public Optional<List<Location>> implementation(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        moduleRegistry.includeReferenceSources();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        try {
            var found = new ImplementationProvider(
                            compilerFor(file),
                            file,
                            line,
                            column,
                            this::compilerFor,
                            this::canReferenceModule,
                            moduleRegistry::batchResolveModulesForFiles)
                    .find();
            return found == ImplementationProvider.NOT_SUPPORTED
                    ? Optional.empty()
                    : Optional.of(found);
        } catch (RuntimeException e) {
            LOG.warning(String.format(
                    "[implementation] compiler_error file=%s type=%s message=%s",
                    file.getFileName(), e.getClass().getSimpleName(), e.getMessage()));
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<Location>> typeDefinition(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        try {
            var found = new TypeDefinitionProvider(
                            compilerFor(file), moduleRegistry.typeIndexFor(file), file, line, column)
                    .find();
            return found == TypeDefinitionProvider.NOT_SUPPORTED
                    ? Optional.empty()
                    : Optional.of(found);
        } catch (RuntimeException e) {
            LOG.warning(String.format(
                    "[type-definition] compiler_error file=%s type=%s message=%s",
                    file.getFileName(), e.getClass().getSimpleName(), e.getMessage()));
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<Location>> findReferences(ReferenceParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        moduleRegistry.includeReferenceSources();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
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
        if (params != null && params.changes != null) didChangeWatchedFiles(params);
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
            var resultId = diagnosticResultId();
            if (resultId.equals(params.previousResultId)) {
                return new DocumentDiagnosticReport("unchanged", resultId, null);
            }
            LOG.info("[diagnostics] pull_compile_start file=" + file.getFileName());
            var started = System.nanoTime();
            var sources = List.<JavaFileObject>of(new SourceFileObject(file));
            var requestCompiler = compilerFor(file);
            String compileProgressToken = null;
            // A warm context answers in milliseconds; only announce a cold multi-module compile.
            if (moduleRegistry.multiModule() && !requestCompiler.hasWarmContext()) {
                compileProgressToken = progress.begin(
                        "Compiling", "Compiling " + file.getFileName());
            }
            try (var task = requestCompiler.compile(sources)) {
                var durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (compileProgressToken != null) {
                    progress.end(compileProgressToken, "Compiled");
                    compileProgressToken = null;
                }
                var errorProvider = new ErrorProvider(task);
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
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            activeModuleFile = file;
            var module = moduleGraph.moduleForFile(file).orElse(null);
            if (module != null) moduleRegistry.includeSources(module.sourceDirs());
        }
        FileStore.open(params);
        if (FileStore.isWorkspaceJavaFile(params.textDocument.uri)) compilerFor(file);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        // Index the active module and its dependency sources. Sibling modules are only visible
        // through their source, so a workspace-size shortcut here would leave completion empty.
        completionIndexScheduler.ensureIndexed(file);
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        completionIndexScheduler.filesChanged(List.of(file));
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        // Closing discards the buffer, so the index must go back to the file on disk.
        completionIndexScheduler.filesChanged(List.of(Paths.get(params.textDocument.uri)));
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
        completionIndexScheduler.filesChanged(List.of(file));
    }

}
