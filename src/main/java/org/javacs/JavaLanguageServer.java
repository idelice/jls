package org.javacs;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.tools.JavaFileObject;
import org.javacs.action.CodeActionProvider;
import org.javacs.action.FindMethodDeclarationAt;
import org.javacs.provider.CompletionProvider;
import org.javacs.provider.SignatureProvider;
import org.javacs.index.WorkspaceTypeIndex;
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
import org.javacs.provider.TypeHierarchyProvider;
import org.javacs.provider.CallHierarchyProvider;
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
            long version) {
        private static final CompletionSnapshot EMPTY =
                create(WorkspaceTypeIndex.EMPTY, 0);

        private static CompletionSnapshot create(
                WorkspaceTypeIndex workspaceIndex,
                long version) {
            var safeWorkspace = workspaceIndex == null ? WorkspaceTypeIndex.EMPTY : workspaceIndex;
            return new CompletionSnapshot(
                    safeWorkspace,
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

    final ServerSettings settings = new ServerSettings();
    ProgressReporter progress;

    final ScheduledExecutorService completionIndexExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("javacs-completion-index").factory());

    final AtomicLong completionIndexVersion = new AtomicLong();
    final AtomicReference<CompletionSnapshot> completionSnapshotRef =
            new AtomicReference<>(CompletionSnapshot.EMPTY);


    private final Set<String> shownWorkspaceWarnings = ConcurrentHashMap.newKeySet();

    /** Pairs a pending Rewrite with the compiler it was computed against. */
    private record PendingRewrite(Rewrite rewrite, CompilerProvider compiler) {}

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

    /** Initialize once on first use if the client did not send startup settings. */
    synchronized JavaCompilerService getOrCreateCompiler() {
        if (compiler == null) initializeCompilers(new JsonObject());
        return compiler;
    }

    synchronized JavaCompilerService compilerFor(Path file) {
        getOrCreateCompiler();
        return moduleRegistry.compilerFor(file);
    }

    boolean isClasspathTypeForIndexing(Path file, JavaCompilerService parsingCompiler, String name) {
        return moduleRegistry.isClasspathTypeForIndexing(file, parsingCompiler, name);
    }

    boolean canReferenceModule(Path declaration, Path candidate) {
        return moduleRegistry.canReferenceModule(declaration, candidate);
    }


    /** Publish immutable workspace declarations. */
    void publishCompletionSnapshot(WorkspaceTypeIndex workspaceIndex, long version) {
        completionIndexVersion.set(Math.max(0L, version));
        completionSnapshotRef.set(CompletionSnapshot.create(workspaceIndex, version));
    }

    private synchronized void initializeCompilers(JsonObject clientSettings) {
        if (compiler != null) return;
        settings.initialize(workspaceRoot, clientSettings);
        try {
            createCompilers();
        } finally {
            notifyWorkspaceInfo();
        }
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
        if (compiler == null) {
            getOrCreateCompiler();
            return;
        }
        var started = Instant.now();
        MavenTooling.invalidateCacheInputsSnapshot();
        completionIndexScheduler.reset(trigger);
        try {
            createCompilers();
            refreshStateForCompilerRecreated();
            for (var f : filterJavaFiles(FileStore.activeDocuments())) lint(f, false);
        } finally {
            notifyWorkspaceInfo();
            LOG.info(String.format(
                    "[perf] compiler_recreate trigger=%s took=%dms",
                    trigger, Duration.between(started, Instant.now()).toMillis()));
        }
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
        moduleRegistry.clear();
        if (compiler != null) compiler.close();
        pendingRewrites.clear();
        if (workspaceRoot == null) {
            compiler = new JavaCompilerService(
                    settings.classPath(), settings.docPath(), settings.addExports(), settings.extraCompilerArgs());
            return;
        }
        var started = System.nanoTime();
        var token = progress.begin("Configure javac", "Reading build metadata");
        try {
            var infer = new InferConfig(workspaceRoot, settings.externalDependencies());
            moduleGraph = infer.moduleGraph();
            moduleRegistry.inferredConfig = infer;
            var explicit = !settings.classPath().isEmpty();
            moduleRegistry.moduleScopedMaven = !explicit && !moduleGraph.modules().isEmpty()
                    && infer.buildSystem() == InferConfig.BuildSystem.MAVEN;
            moduleRegistry.moduleScopedGradle = !explicit && !moduleGraph.modules().isEmpty()
                    && infer.buildSystem() == InferConfig.BuildSystem.GRADLE;
            var scoped = moduleRegistry.moduleScopedMaven || moduleRegistry.moduleScopedGradle;
            var roots = new LinkedHashSet<Path>();
            for (var module : moduleGraph.modules().values()) roots.addAll(module.sourceDirs());
            if (!roots.isEmpty()) FileStore.setWorkspaceRoots(roots);
            var classpath = explicit ? settings.classPath() : scoped ? Set.<Path>of() : infer.classPath();
            var docs = explicit ? settings.docPath() : scoped ? Set.<Path>of() : infer.buildDocPath();
            var args = scoped ? settings.extraCompilerArgs() : selectCompilerArgs(settings.extraCompilerArgs(), infer).args();
            compiler = new JavaCompilerService(moduleGraph.externalClasspath(classpath), docs, settings.addExports(), args);
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
            notifyUser(
                    "maven_mixed_release_fallback",
                    "JLS detected mixed Maven module Java levels and fell back to the runtime/default compiler behavior for this workspace.",
                    MessageType.Warning);
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

    private void notifyUser(String key, String message, int type) {
        if (!shownWorkspaceWarnings.add(key)) {
            return;
        }
        var params = new ShowMessageParams();
        params.type = type;
        params.message = message;
        client.showMessage(params);
        LOG.warning(message);
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = params.rootUri == null ? null : Paths.get(params.rootUri);
        this.progress = new ProgressReporter(client, ProgressReporter.supportsWorkDoneProgress(params.capabilities));
        FileStore.setWorkspaceRoots(workspaceRoot == null ? Set.of() : Set.of(workspaceRoot));

        var c = new JsonObject();
        c.addProperty("textDocumentSync", 2); // Incremental
        c.addProperty("hoverProvider", true);
        var completionOptions = new JsonObject();
        completionOptions.addProperty("resolveProvider", true);
        var triggerCharacters = new JsonArray();
        triggerCharacters.add(".");
        triggerCharacters.add(":");
        triggerCharacters.add("@");
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
        c.addProperty("typeHierarchyProvider", true);
        c.addProperty("callHierarchyProvider", true);
        c.addProperty("workspaceSymbolProvider", true);
        c.addProperty("documentSymbolProvider", true);
        c.addProperty("documentFormattingProvider", true);
        var codeLensOptions = new JsonObject();
        c.add("codeLensProvider", codeLensOptions);
        c.addProperty("foldingRangeProvider", true);
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
        if (workspaceRoot == null) return;
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
        // Neovim sends its startup settings after this notification. Compiler setup
        // starts with those settings, or on first use for clients without settings.
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
        var file = Paths.get(params.textDocument.uri);
        var started = Instant.now();
        var result = new SymbolProvider(getOrCreateCompiler()).documentSymbols(file);
        LOG.fine(String.format("[perf] document_symbol file=%s symbols=%d took=%dms",
                file.getFileName(), result.size(), Duration.between(started, Instant.now()).toMillis()));
        return result;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        if (compiler != null) return; // Startup settings only; later changes require a restart.
        var java = change.settings.getAsJsonObject().get("java");
        LOG.info(String.format("Received java settings %s", java));
        var nextSettings = new JsonObject();
        if (java != null && !java.isJsonNull()) {
            nextSettings = java.getAsJsonObject();
        }
        initializeCompilers(nextSettings);
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        if (workspaceRoot == null) return;
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
            for (var f : filterJavaFiles(FileStore.activeDocuments())) lint(f, false);
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
        completionIndexScheduler.ensureIndexed(file);
        if (!completionIndexScheduler.awaitReady(INDEX_WAIT_MS)) {
            LOG.info("[completion] index_not_ready file=" + file.getFileName() + " — returning incomplete");
            return Optional.of(new CompletionList(true, List.of()));
        }
        var snapshot = completionSnapshotRef.get();
        var requestCompiler = compilerFor(file);
        var index = moduleRegistry.typeIndexFor(file);
        var scope = moduleRegistry.moduleCompilerKey(file);
        var provider = new CompletionProvider(requestCompiler, index, snapshot.version(), scope);
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
        var index = context == null ? snapshot.workspaceIndex() : context.typeIndex(snapshot);
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
        var started = Instant.now();
        var content = new HoverProvider(compilerFor(file)).hover(file, line, column);
        LOG.fine(String.format("[perf] hover file=%s took=%dms",
                file.getFileName(), Duration.between(started, Instant.now()).toMillis()));
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
        var started = Instant.now();
        var signatureProvider = new SignatureProvider(compilerFor(file), moduleRegistry.typeIndexFor(file)).signatureHelp(file, line, column);
        LOG.fine(String.format("[perf] signature_help file=%s took=%dms",
                file.getFileName(), Duration.between(started, Instant.now()).toMillis()));
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
        var started = Instant.now();
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
        LOG.fine(String.format("[perf] definition file=%s took=%dms",
                file.getFileName(), Duration.between(started, Instant.now()).toMillis()));
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
            var started = Instant.now();
            var found = new ImplementationProvider(
                            compilerFor(file),
                            file,
                            line,
                            column,
                            this::compilerFor,
                            this::canReferenceModule,
                            moduleRegistry::batchResolveModulesForFiles)
                    .find();
            LOG.fine(String.format("[perf] implementation file=%s took=%dms",
                    file.getFileName(), Duration.between(started, Instant.now()).toMillis()));
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
            var started = Instant.now();
            var found = new TypeDefinitionProvider(
                            compilerFor(file), moduleRegistry.typeIndexFor(file), file, line, column)
                    .find();
            LOG.fine(String.format("[perf] type_definition file=%s took=%dms",
                    file.getFileName(), Duration.between(started, Instant.now()).toMillis()));
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
        var token = progress.begin("Finding references", file.getFileName().toString());
        List<Location> found;
        try {
            var started = Instant.now();
            found =
                    new ReferenceProvider(
                                    compilerFor(file), file, line, column, this::compilerFor,
                                    this::canReferenceModule, moduleRegistry::batchResolveModulesForFiles,
                                    completionSnapshotRef.get().workspaceIndex()::filesContainingToken)
                            .find();
            LOG.fine(String.format("[perf] references file=%s results=%d took=%dms",
                    file.getFileName(), found == ReferenceProvider.NOT_SUPPORTED ? 0 : found.size(),
                    Duration.between(started, Instant.now()).toMillis()));
        } finally {
            progress.end(token, null);
        }
        if (found == ReferenceProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public Optional<List<TypeHierarchyItem>> prepareTypeHierarchy(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        return new TypeHierarchyProvider(compilerFor(file), moduleRegistry::typeIndexFor, this::compilerFor)
                .prepare(file, position.position.line + 1, position.position.character + 1);
    }

    @Override
    public List<TypeHierarchyItem> typeHierarchySupertypes(TypeHierarchyParams params) {
        if (params == null || params.item == null || params.item.uri == null) return List.of();
        var file = Paths.get(params.item.uri);
        return new TypeHierarchyProvider(compilerFor(file), moduleRegistry::typeIndexFor, this::compilerFor)
                .supertypes(params.item);
    }

    @Override
    public List<TypeHierarchyItem> typeHierarchySubtypes(TypeHierarchyParams params) {
        if (params == null || params.item == null || params.item.uri == null) return List.of();
        var file = Paths.get(params.item.uri);
        return new TypeHierarchyProvider(compilerFor(file), moduleRegistry::typeIndexFor, this::compilerFor)
                .subtypes(params.item);
    }

    @Override
    public Optional<List<CallHierarchyItem>> prepareCallHierarchy(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        return new CallHierarchyProvider(
                        compilerFor(file),
                        this::compilerFor,
                        moduleRegistry::batchResolveModulesForFiles,
                        moduleRegistry::includeReferenceSources,
                        completionSnapshotRef.get().workspaceIndex()::filesContainingToken)
                .prepare(file, position.position.line + 1, position.position.character + 1);
    }

    @Override
    public List<CallHierarchyIncomingCall> callHierarchyIncomingCalls(CallHierarchyParams params) {
        if (params == null || params.item == null || params.item.uri == null) return List.of();
        var file = Paths.get(params.item.uri);
        return new CallHierarchyProvider(
                        compilerFor(file),
                        this::compilerFor,
                        moduleRegistry::batchResolveModulesForFiles,
                        moduleRegistry::includeReferenceSources,
                        completionSnapshotRef.get().workspaceIndex()::filesContainingToken)
                .incomingCalls(params.item);
    }

    @Override
    public List<CallHierarchyOutgoingCall> callHierarchyOutgoingCalls(CallHierarchyParams params) {
        if (params == null || params.item == null || params.item.uri == null) return List.of();
        var file = Paths.get(params.item.uri);
        return new CallHierarchyProvider(
                        compilerFor(file),
                        this::compilerFor,
                        moduleRegistry::batchResolveModulesForFiles,
                        moduleRegistry::includeReferenceSources,
                        completionSnapshotRef.get().workspaceIndex()::filesContainingToken)
                .outgoingCalls(params.item);
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
        var started = Instant.now();
        var result = new FoldProvider(compilerFor(file)).foldingRanges(file);
        LOG.fine(String.format("[perf] folding_range file=%s ranges=%d took=%dms",
                file.getFileName(), result.size(), Duration.between(started, Instant.now()).toMillis()));
        return result;
    }

    private final RenameHandler renameHandler = new RenameHandler(this);

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        return renameHandler.prepareRename(params);
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        var started = Instant.now();
        var result = renameHandler.rename(params, client);
        LOG.fine(String.format("[perf] rename file=%s took=%dms",
                Paths.get(params.textDocument.uri).getFileName(),
                Duration.between(started, Instant.now()).toMillis()));
        return result;
    }

    @Override
    public void renameApplied(DidChangeWatchedFilesParams params) {
        if (params != null && params.changes != null) didChangeWatchedFiles(params);
    }

    /**
     * After compiling {@code changed}, re-lint the active documents that reference its declared
     * types so cross-file errors surface without the client issuing one request per open buffer.
     * Dependents are linted with propagate=false to prevent recursion.
     */
    private void propagateDiagnostics(Path changed) {
        try {
            var active = new HashSet<>(filterJavaFiles(FileStore.activeDocuments()));
            active.remove(changed);
            if (active.isEmpty()) return;
            LOG.info(String.format(
                    "[diagnostics] propagate from=%s dependents=%d",
                    changed.getFileName(), active.size()));
            for (var f : active) lint(f, false);
        } catch (Exception e) {
            LOG.warning("[diagnostics] propagate_failed file=" + changed.getFileName()
                    + " reason=" + e.getMessage());
        }
    }

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        if (workspaceRoot == null && !FileStore.isJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        var newStandaloneFile = workspaceRoot == null && !FileStore.isWorkspaceFile(file);
        if (newStandaloneFile) {
            // File roots admit these sources without walking their containing directories.
            var files = new HashSet<>(FileStore.workspaceRoots());
            files.add(file);
            FileStore.setWorkspaceRoots(files);
        }
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            activeModuleFile = file;
            var module = moduleGraph.moduleForFile(file).orElse(null);
            if (module != null) moduleRegistry.includeSources(module.sourceDirs());
        }
        FileStore.open(params);
        if (newStandaloneFile && compiler != null) compiler.setSourceRoots(FileStore.sourceRoots());
        if (FileStore.isWorkspaceJavaFile(params.textDocument.uri)) compilerFor(file);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        if (newStandaloneFile) completionIndexScheduler.filesChanged(List.of(file));
        // Index the active module and its dependency sources. Sibling modules are only visible
        // through their source, so a workspace-size shortcut here would leave completion empty.
        completionIndexScheduler.ensureIndexed(file);
        if (compiler != null) {
            try {
                var requestCompiler = compilerFor(file);
                try (var task = requestCompiler.compile(List.<JavaFileObject>of(new SourceFileObject(file)))) {
                    publishFileDiagnostics(task, file);
                    pushHints(file, task, requestCompiler, -1);
                }
                if (!serverReadyAnnounced) {
                    serverReadyAnnounced = true;
                    notifyUser("JLS", "Server ready", MessageType.Info);
                }
            } catch (Exception e) {
                LOG.fine("[didOpen] lint failed file=" + file.getFileName() + " reason=" + e.getMessage());
            }
            pendingPropagation = file;
        }
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        if (!FileStore.isWorkspaceJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        completionIndexScheduler.filesChanged(List.of(file));
        int editLine = -1;
        if (!params.contentChanges.isEmpty()) {
            var range = params.contentChanges.getFirst().range;
            if (range != null) editLine = range.start.line;
        }
        if (editLine >= 0 && compiler != null) {
            try {
                var requestCompiler = compilerFor(file);
                try (var task = requestCompiler.compile(List.<JavaFileObject>of(new SourceFileObject(file)))) {
                    publishFileDiagnostics(task, file);
                    pushHints(file, task, requestCompiler, editLine);
                }
            } catch (Exception e) {
                LOG.fine("[didChange] lint failed file=" + file.getFileName() + " reason=" + e.getMessage());
            }
            pendingPropagation = file;
        } else {
            lint(file, true);
        }
    }

    private volatile Path pendingPropagation;
    private long lastPropagatedIndexVersion;
    private boolean serverReadyAnnounced;

    private void lint(Path file, boolean propagate) {
        if (compiler == null) return;
        try {
            var requestCompiler = compilerFor(file);
            try (var task = requestCompiler.compile(List.<JavaFileObject>of(new SourceFileObject(file)))) {
                publishFileDiagnostics(task, file);
            }
            if (!serverReadyAnnounced) {
                serverReadyAnnounced = true;
                notifyUser("JLS", "Server ready", MessageType.Info);
            }
        } catch (Exception e) {
            LOG.fine("[lint] failed file=" + file.getFileName() + " reason=" + e.getMessage());
        }
        if (propagate) pendingPropagation = file;
    }

    @Override
    public void doAsyncWork() {
        var file = pendingPropagation;
        if (file == null) return;
        var version = completionIndexVersion.get();
        if (version == lastPropagatedIndexVersion) return;
        pendingPropagation = null;
        lastPropagatedIndexVersion = version;
        propagateDiagnostics(file);
    }

    private void publishFileDiagnostics(CompileTask task, Path file) {
        var diagnostics = new ErrorProvider(task, settings.richFormatter()).errors(Set.of(file.toUri()));
        for (var params : diagnostics) {
            if (file.toUri().equals(params.uri)) {
                client.publishDiagnostics(params);
                return;
            }
        }
        client.publishDiagnostics(new PublishDiagnosticsParams(file.toUri(), List.of()));
    }

    /** Reuse the diagnostic compilation for full-file hints on open, or the edited method on change. */
    private void pushHints(Path file, CompileTask existingTask, JavaCompilerService existingCompiler, int editLine) {
        if (!settings.inlayHints()) return;
        var started = System.currentTimeMillis();
        try {
            Range range = null;
            if (editLine >= 0) {
                var root = existingTask.root(file);
                if (root == null) {
                    return;
                } else {
                    var cursor = LspPosition.offset(root, new Position(editLine, 0));
                    if (cursor < 0) {
                        return;
                    } else {
                        var method = new FindMethodDeclarationAt(existingTask.trees).scan(root, cursor);
                        if (method == null) {
                            return;
                        } else {
                            var pos = existingTask.trees.getSourcePositions();
                            var startPos = pos.getStartPosition(root, method);
                            var endPos = pos.getEndPosition(root, method);
                            if (startPos >= 0 && endPos >= 0) {
                                range = new Range(LspPosition.position(root, startPos), LspPosition.position(root, endPos));
                            }
                        }
                    }
                }
            }
            var provider = new InlayHintProvider(existingCompiler, moduleRegistry.typeIndexFor(file));
            var payload = provider.inlayHints(existingTask, file, range);
            client.customNotification("java/inlayHints", payload);
            LOG.fine("[hints] file=" + file.getFileName()
                    + " range=" + (range == null ? "full" : range.start.line + ".." + range.end.line)
                    + " hints=" + payload.getAsJsonArray("hints").size()
                    + " ms=" + (System.currentTimeMillis() - started));
        } catch (Exception e) {
            LOG.fine("[hints] push failed file=" + file.getFileName() + " reason=" + e.getMessage());
        }
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
        var started = Instant.now();
        var file = Paths.get(params.textDocument.uri);
        var requestCompiler = compilerFor(file);
        // Forwarding map: provider stores Rewrite by UUID; we pair it with the compiler atomically.
        var rewriteSink = new java.util.AbstractMap<String, Rewrite>() {
            @Override public Rewrite put(String id, Rewrite rw) {
                pendingRewrites.put(id, new PendingRewrite(rw, requestCompiler));
                return null;
            }
            @Override public Set<Map.Entry<String, Rewrite>> entrySet() { return Set.of(); }
        };
        var provider = new CodeActionProvider(requestCompiler, rewriteSink);
        var result = params.context.diagnostics.isEmpty()
                ? provider.codeActionsForCursor(params)
                : provider.codeActionForDiagnostics(params);
        LOG.fine(String.format("[perf] code_action file=%s actions=%d took=%dms",
                file.getFileName(), result.size(), Duration.between(started, Instant.now()).toMillis()));
        return result;
    }

    @Override
    public CodeAction resolveCodeAction(CodeAction action) {
        if (action.data == null || !action.data.isJsonPrimitive()) return action;
        var id = action.data.getAsString();
        var pending = pendingRewrites.remove(id);
        if (pending == null) return action;
        var edits = pending.rewrite().rewrite(pending.compiler() != null ? pending.compiler() : getOrCreateCompiler());
        if (edits == null || edits == Rewrite.CANCELLED) return action;
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
        lint(file, false);
        propagateDiagnostics(file);
    }
}
