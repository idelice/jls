package org.javacs;

import static org.javacs.JsonHelper.GSON;

import com.google.gson.*;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.action.CodeActionProvider;
import org.javacs.completion.CompletionProvider;
import org.javacs.completion.SignatureProvider;
import org.javacs.fold.FoldProvider;
import org.javacs.hint.InlayHintProvider;
import org.javacs.hover.HoverProvider;
import org.javacs.index.SymbolProvider;
import org.javacs.lens.CodeLensProvider;
import org.javacs.lsp.*;
import org.javacs.markup.ColorProvider;
import org.javacs.markup.ErrorProvider;
import org.javacs.navigation.DefinitionProvider;
import org.javacs.navigation.ReferenceProvider;
import org.javacs.rewrite.*;

class JavaLanguageServer extends LanguageServer {
    // TODO allow multiple workspace roots
    private Path workspaceRoot;
    private final LanguageClient client;
    private JavaCompilerService cacheCompiler;
    private LombokMetadataCache lombokCache;
    private JsonObject cacheSettings;
    private JsonObject settings = new JsonObject();
    private boolean modifiedBuild = true;
    private CacheManager cacheManager;
    private String progressToken;
    private final LinkedHashSet<Path> pendingFastLintFiles = new LinkedHashSet<>();
    private final LinkedHashSet<Path> pendingFullLintFiles = new LinkedHashSet<>();
    private final LinkedHashSet<Path> pendingWarningLintFiles = new LinkedHashSet<>();
    private long pendingFastLintSeq = 0;
    private long pendingFullLintSeq = 0;
    private long pendingWarningLintSeq = 0;
    private long nextFastLintAtNanos = 0;
    private long nextFullLintAtNanos = 0;
    private long nextWarningLintAtNanos = 0;
    private long changeSequence = 0;
    private long suppressFullLintUntilNanos = 0;
    private final Map<java.net.URI, Integer> diagnosticsFingerprintByUri = new HashMap<>();
    private final InlayHintProvider inlayHintProvider = new InlayHintProvider();
    private static final long FAST_EDIT_LINT_DEBOUNCE_MS = 220;
    private static final long FULL_LINT_IDLE_MS = 900;
    private static final long DEFERRED_WARNING_LINT_MS = 160;
    private static final long FULL_LINT_SUPPRESS_AFTER_INTERACTIVE_MS = 1000;

    JavaCompilerService compiler() {
        if (needsCompiler()) {
            cacheCompiler = createCompiler();
            if (lombokCache != null) {
                lombokCache.clear(); // Clear cache when compiler changes
            }
            cacheSettings = settings;
            modifiedBuild = false;
        }
        return cacheCompiler;
    }

    private boolean needsCompiler() {
        if (modifiedBuild) {
            return true;
        }
        if (!settings.equals(cacheSettings)) {
            LOG.info("Settings\n\t" + settings + "\nis different than\n\t" + cacheSettings);
            return true;
        }
        return false;
    }

    void lint(Collection<Path> files) {
        lint(files, true, "full");
    }

    void lint(Collection<Path> files, boolean includeWarnings, String phase) {
        if (files.isEmpty()) return;
        if (includeWarnings && shouldUseTwoPhaseDiagnostics(phase)) {
            lint(files, false, phase + "-errors");
            scheduleDeferredWarningLint(files, phase);
            return;
        }
        runLint(files, includeWarnings, phase);
    }

    private void runLint(Collection<Path> files, boolean includeWarnings, String phase) {
        if (files.isEmpty()) return;
        LOG.info("Lint " + files.size() + " files...");
        var startedNanos = System.nanoTime();
        JavaCompilerService.runInDiagnosticsCriticalPath(
                () -> {
                    try (var task = compiler().compile(files.toArray(Path[]::new))) {
                        var compileElapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
                        LOG.info(
                                "[perf][diagnostics] phase="
                                        + phase
                                        + " includeWarnings="
                                        + includeWarnings
                                        + " compile_ms="
                                        + compileElapsedMs
                                        + " files="
                                        + files.size());
                        publishDiagnosticsAndColors(task, startedNanos, includeWarnings, phase);
                    }
                });
    }

    /**
     * Publish diagnostics (errors/warnings) and color information for a compiled task.
     * This is separated from compilation so navigation can compile without this overhead.
     */
    void publishDiagnosticsAndColors(CompileTask task, Instant started) {
        publishDiagnosticsAndColors(task, started, true, "full");
    }

    void publishDiagnosticsAndColors(CompileTask task, Instant started, boolean includeWarnings, String phase) {
        publishDiagnosticsAndColors(task, System.nanoTime(), includeWarnings, phase);
    }

    void publishDiagnosticsAndColors(CompileTask task, long startedNanos, boolean includeWarnings, String phase) {
        var collectStarted = System.nanoTime();
        var payloads = new ErrorProvider(task, lombokCache, includeWarnings).errors();
        var collectElapsedMs = (System.nanoTime() - collectStarted) / 1_000_000;
        var publishStarted = System.nanoTime();
        var publishedCount = 0;
        var skippedUnchanged = 0;
        for (var errs : payloads) {
            var fingerprint = diagnosticsFingerprint(errs);
            var previous = diagnosticsFingerprintByUri.get(errs.uri);
            if (previous != null && previous == fingerprint) {
                skippedUnchanged++;
                continue;
            }
            diagnosticsFingerprintByUri.put(errs.uri, fingerprint);
            client.publishDiagnostics(errs);
            publishedCount++;
        }
        // for (var colors : new ColorProvider(task).colors()) {
        //     client.customNotification("java/colors", GSON.toJsonTree(colors));
        // }
        var publishElapsedMs = (System.nanoTime() - publishStarted) / 1_000_000;
        var totalElapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        LOG.info(
                "[perf][diagnostics] phase="
                        + phase
                        + " includeWarnings="
                        + includeWarnings
                        + " collect_ms="
                        + collectElapsedMs
                        + " publish_ms="
                        + publishElapsedMs
                        + " total_ms="
                        + totalElapsedMs
                        + " published_files="
                        + publishedCount
                        + " skipped_unchanged="
                        + skippedUnchanged);
    }

    private int diagnosticsFingerprint(PublishDiagnosticsParams params) {
        var hash = 1;
        for (var d : params.diagnostics) {
            hash = 31 * hash + Objects.hashCode(d.code);
            hash = 31 * hash + Objects.hashCode(d.message);
            hash = 31 * hash + Objects.hashCode(d.severity);
            hash = 31 * hash + d.range.start.line;
            hash = 31 * hash + d.range.start.character;
            hash = 31 * hash + d.range.end.line;
            hash = 31 * hash + d.range.end.character;
        }
        return hash;
    }

    /**
     * Start a new progress report using standard LSP WorkDoneProgress. Token is generated
     * as "jls-progress-{timestamp}" to uniquely identify this progress session.
     */
    private void startProgress(String title) {
        progressToken = "jls-progress-" + System.currentTimeMillis();
        var begin = new WorkDoneProgressBegin(title);
        var params = new WorkDoneProgressParams(progressToken, begin);
        client.customNotification("$/progress", GSON.toJsonTree(params));
    }

    /**
     * Report progress update using standard LSP WorkDoneProgress.
     */
    private void reportProgress(String message) {
        if (progressToken != null) {
            var report = new WorkDoneProgressReport(message);
            var params = new WorkDoneProgressParams(progressToken, report);
            client.customNotification("$/progress", GSON.toJsonTree(params));
        }
    }

    /**
     * End the current progress report using standard LSP WorkDoneProgress.
     */
    private void endProgress() {
        if (progressToken != null) {
            var end = new WorkDoneProgressEnd();
            var params = new WorkDoneProgressParams(progressToken, end);
            client.customNotification("$/progress", GSON.toJsonTree(params));
            progressToken = null;
        }
    }

    // Deprecated: kept for backward compatibility but now uses standard LSP
    private void javaStartProgress(JavaStartProgressParams params) {
        startProgress(params.getMessage());
    }

    // Deprecated: kept for backward compatibility but now uses standard LSP
    private void javaReportProgress(JavaReportProgressParams params) {
        reportProgress(params.getMessage());
    }

    // Deprecated: kept for backward compatibility but now uses standard LSP
    private void javaEndProgress() {
        endProgress();
    }

    private JavaCompilerService createCompiler() {
        Objects.requireNonNull(workspaceRoot, "Can't create compiler because workspaceRoot has not been initialized");

        startProgress("Configure javac");
        reportProgress("Finding source roots");

        var externalDependencies = externalDependencies();
        var classPath = classPath();
        var extraArgs = extraCompilerArgs();
        var addExports = addExports();

        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            endProgress();
            var compiler = new JavaCompilerService(classPath, docPath(), addExports, extraArgs);
            lombokCache = new LombokMetadataCache(compiler);
            return compiler;
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var infer = new InferConfig(workspaceRoot, externalDependencies, cacheManager);

            if (cacheManager != null) {
                reportProgress("Checking dependency cache");
            }
            reportProgress("Resolving dependencies (parallel)");
            classPath = infer.classPath();

            reportProgress("Building documentation index");
            var docPath = infer.buildDocPath();

            endProgress();
            var compiler = new JavaCompilerService(classPath, docPath, addExports, extraArgs);
            lombokCache = new LombokMetadataCache(compiler);
            return compiler;
        }
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
        c.addProperty("inlayHintProvider", true);
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
    public void shutdown() {}

    public JavaLanguageServer(LanguageClient client) {
        this.client = client;
        this.cacheManager = new CacheManager();
    }

    @Override
    public List<SymbolInformation> workspaceSymbols(WorkspaceSymbolParams params) {
        return new SymbolProvider(compiler()).findSymbols(params.query, 50);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var java = change.settings.getAsJsonObject().get("java");
        LOG.info("Received java settings " + java);
        if (java != null && !java.isJsonNull()) {
            settings = java.getAsJsonObject();
        }
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        for (var c : params.changes) {
            var file = Paths.get(c.uri);
            if (FileStore.isJavaFile(file)) {
                switch (c.type) {
                    case FileChangeType.Created:
                        FileStore.externalCreate(file);
                        break;
                    case FileChangeType.Changed:
                        FileStore.externalChange(file);
                        invalidateLombokCacheForFile(file);
                        break;
                    case FileChangeType.Deleted:
                        FileStore.externalDelete(file);
                        invalidateLombokCacheForFile(file);
                        break;
                }
                return;
            }
            var name = file.getFileName().toString();
            switch (name) {
                case "BUILD":
                case "pom.xml":
                case "build.gradle":
                case "WORKSPACE":
                    LOG.info("Compiler needs to be re-created because " + file + " has changed");
                    modifiedBuild = true;
                    if (cacheManager != null) {
                        cacheManager.clearCache(workspaceRoot);
                        LOG.info("Cleared dependency cache for workspace " + workspaceRoot);
                    }
            }
        }
    }

    /**
     * Invalidate Lombok cache entries for all classes in a file.
     *
     * <p>Called when a file is modified or deleted to ensure cache consistency.
     */
    private void invalidateLombokCacheForFile(Path file) {
        if (lombokCache == null) return;

        try {
            var packageName = FileStore.packageName(file);

            // Invalidate cache for public class (filename usually matches)
            var fileName = file.getFileName().toString();
            if (fileName.endsWith(".java")) {
                var simpleClassName = fileName.substring(0, fileName.length() - 5);
                var qualifiedName = packageName.isEmpty()
                    ? simpleClassName
                    : packageName + "." + simpleClassName;
                lombokCache.invalidate(qualifiedName);
            }

            // Note: We could also parse the file to find all classes, but invalidating
            // just the main class is usually sufficient since Lombok classes are typically
            // one class per file.
        } catch (Exception e) {
            LOG.fine("Failed to invalidate Lombok cache for " + file + ": " + e.getMessage());
        }
    }

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        markInteractiveRequest("completion");
        var file = Paths.get(params.textDocument.uri);
        var startedNanos = System.nanoTime();
        var provider = new CompletionProvider(compiler(), lombokCache);
        var list = provider.complete(file, params.position.line + 1, params.position.character + 1);
        LOG.info(
                "[perf][completion-request] file="
                        + file.getFileName()
                        + " elapsed_ms="
                        + ((System.nanoTime() - startedNanos) / 1_000_000));
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(list);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        var startedNanos = System.nanoTime();
        new HoverProvider(compiler(), lombokCache).resolveCompletionItem(unresolved);
        LOG.info("[perf][completion-resolve-request] elapsed_ms=" + ((System.nanoTime() - startedNanos) / 1_000_000));
        return unresolved;
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        markInteractiveRequest("hover");
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var startedNanos = System.nanoTime();
        var content = new HoverProvider(compiler(), lombokCache).hover(file, line, column);
        LOG.info(
                "[perf][hover-request] file="
                        + file.getFileName()
                        + " elapsed_ms="
                        + ((System.nanoTime() - startedNanos) / 1_000_000));
        if (content == null) {
            return Optional.empty();
        }
        // TODO add range
        return Optional.of(new Hover(content));
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        markInteractiveRequest("signature");
        var file = Paths.get(params.textDocument.uri);
        var line = params.position.line + 1;
        var column = params.position.character + 1;
        var help = new SignatureProvider(compiler(), lombokCache).signatureHelp(file, line, column);
        if (help == SignatureProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(help);
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        markInteractiveRequest("definition");
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new DefinitionProvider(compiler(), file, line, column).find();
        if (found == DefinitionProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public Optional<List<Location>> findReferences(ReferenceParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        markInteractiveRequest("references");
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new ReferenceProvider(compiler(), lombokCache, file, line, column).find();
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
        var lenses = CodeLensProvider.find(task);
        LOG.fine("CodeLens: " + lenses.size() + " items for " + file.getFileName());
        return lenses;
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
    public List<InlayHint> inlayHint(InlayHintParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        markInteractiveRequest("inlayHint");
        var file = Paths.get(params.textDocument.uri);
        var hints = inlayHintProvider.inlayHints(compiler(), file, params.range);
        LOG.fine("[inlay-hints] computed file=" + file.getFileName() + " hints=" + hints.size());
        return hints;
    }

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        LOG.info("Try to rename...");
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            var lines = task.root().getLineMap();
            var cursor = lines.getPosition(params.position.line + 1, params.position.character + 1);
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
                LOG.info("...can't rename " + el);
                return Optional.empty();
            }
            if (!canFindSource(el)) {
                LOG.info("...can't find source for " + el);
                return Optional.empty();
            }
            var response = new RenameResponse();
            response.range = FindHelper.location(task, path).range;
            response.placeholder = el.getSimpleName().toString();
            return Optional.of(response);
        }
    }

    private boolean canRename(Element rename) {
        switch (rename.getKind()) {
            case METHOD:
            case FIELD:
            case LOCAL_VARIABLE:
            case PARAMETER:
            case EXCEPTION_PARAMETER:
                return true;
            default:
                // TODO rename other types
                return false;
        }
    }

    private boolean canFindSource(Element rename) {
        if (rename == null) return false;
        if (rename instanceof TypeElement) {
            var type = (TypeElement) rename;
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
            var lines = task.root().getLineMap();
            var position = lines.getPosition(params.position.line + 1, params.position.character + 1);
            var path = new FindNameAt(task).scan(task.root(), position);
            if (path == null) return Rewrite.NOT_SUPPORTED;
            var el = Trees.instance(task.task).getElement(path);
            switch (el.getKind()) {
                case METHOD:
                    return renameMethod(task, (ExecutableElement) el, params.newName);
                case FIELD:
                    return renameField(task, (VariableElement) el, params.newName);
                case LOCAL_VARIABLE:
                case PARAMETER:
                case EXCEPTION_PARAMETER:
                    return renameVariable(task, (VariableElement) el, params.newName);
                default:
                    return Rewrite.NOT_SUPPORTED;
            }
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

    private boolean uncheckedChanges = false;
    private Path lastEdited = Paths.get("");

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        FileStore.open(params);
        if (!FileStore.isJavaFile(params.textDocument.uri)) return;
        lastEdited = Paths.get(params.textDocument.uri);
        uncheckedChanges = true;
        scheduleDebouncedLint(lastEdited, "open");
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        lastEdited = Paths.get(params.textDocument.uri);
        uncheckedChanges = true;
        scheduleDebouncedLint(lastEdited, "change");
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);

        if (FileStore.isJavaFile(params.textDocument.uri)) {
            var closed = Paths.get(params.textDocument.uri);
            pendingFastLintFiles.remove(closed);
            pendingFullLintFiles.remove(closed);
            pendingWarningLintFiles.remove(closed);
            diagnosticsFingerprintByUri.remove(params.textDocument.uri);
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
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            pendingFastLintFiles.clear();
            pendingFullLintFiles.clear();
            pendingWarningLintFiles.clear();
            // Re-lint all active documents
            lint(FileStore.activeDocuments(), true, "full-save");
        }
    }

    @Override
    public void doAsyncWork() {
        var now = System.nanoTime();
        if (!uncheckedChanges) {
            return;
        }

        if (!pendingFastLintFiles.isEmpty()
                && now >= nextFastLintAtNanos) {
            try {
                var files = activeOnly(pendingFastLintFiles);
                var seq = pendingFastLintSeq;
                pendingFastLintFiles.clear();
                if (!files.isEmpty()) {
                    LOG.fine(
                            "[lint-scheduler] run fast lint files="
                                    + files.size()
                                    + " seq="
                                    + seq
                                    + " debounce_ms="
                                    + fastEditLintDebounceMs());
                    lint(files, false, "fast");
                }
            } catch (Exception e) {
                // Log but don't crash server. Lint failures should not terminate the language server.
                LOG.warning("Async lint failed: " + e.getMessage());
                LOG.log(java.util.logging.Level.FINE, "", e);
            }
        }

        if (!warningsOnSaveOnly()
                && !pendingFullLintFiles.isEmpty()
                && now >= nextFullLintAtNanos
                && now >= suppressFullLintUntilNanos) {
            try {
                var files = activeOnly(pendingFullLintFiles);
                var seq = pendingFullLintSeq;
                pendingFullLintFiles.clear();
                if (!files.isEmpty()) {
                    LOG.fine(
                            "[lint-scheduler] run full lint files="
                                    + files.size()
                                    + " seq="
                                    + seq
                                    + " idle_ms="
                                    + fullLintIdleMs());
                    lint(files, true, "full-idle");
                }
            } catch (Exception e) {
                LOG.warning("Async full lint failed: " + e.getMessage());
                LOG.log(java.util.logging.Level.FINE, "", e);
            }
        }

        if (!pendingWarningLintFiles.isEmpty()
                && now >= nextWarningLintAtNanos
                && now >= suppressFullLintUntilNanos) {
            try {
                var files = activeOnly(pendingWarningLintFiles);
                var seq = pendingWarningLintSeq;
                pendingWarningLintFiles.clear();
                if (!files.isEmpty()) {
                    LOG.fine(
                            "[lint-scheduler] run deferred warning lint files="
                                    + files.size()
                                    + " seq="
                                    + seq
                                    + " defer_ms="
                                    + deferredWarningLintMs());
                    runLint(files, true, "warnings-deferred");
                }
            } catch (Exception e) {
                LOG.warning("Async deferred warning lint failed: " + e.getMessage());
                LOG.log(java.util.logging.Level.FINE, "", e);
            }
        }

        if (pendingFastLintFiles.isEmpty()
                && (warningsOnSaveOnly() || pendingFullLintFiles.isEmpty())
                && pendingWarningLintFiles.isEmpty()) {
            uncheckedChanges = false;
        }
    }

    private List<Path> activeOnly(LinkedHashSet<Path> files) {
        var active = FileStore.activeDocuments();
        var result = new ArrayList<Path>();
        for (var file : files) {
            if (active.contains(file)) {
                result.add(file);
            }
        }
        return result;
    }

    private void scheduleDebouncedLint(Path file, String reason) {
        if (!FileStore.activeDocuments().contains(file)) {
            return;
        }
        if (isCachedJarSource(file)) {
            LOG.fine("[lint-scheduler] skip cached jar source file=" + file + " reason=" + reason);
            return;
        }
        pendingFastLintFiles.add(file);
        pendingWarningLintFiles.remove(file);
        changeSequence++;
        pendingFastLintSeq = changeSequence;
        var now = System.nanoTime();
        nextFastLintAtNanos = now + TimeUnit.MILLISECONDS.toNanos(fastEditLintDebounceMs());
        if (!warningsOnSaveOnly()) {
            pendingFullLintFiles.add(file);
            pendingFullLintSeq = changeSequence;
            nextFullLintAtNanos = now + TimeUnit.MILLISECONDS.toNanos(fullLintIdleMs());
        }
        LOG.fine(
                "[lint-scheduler] queued "
                        + reason
                        + " file="
                        + file
                        + " pending_fast="
                        + pendingFastLintFiles.size()
                        + " pending_full="
                        + pendingFullLintFiles.size()
                        + " seq="
                        + pendingFastLintSeq
                        + " fast_due_ms="
                        + fastEditLintDebounceMs()
                        + " full_due_ms="
                        + (warningsOnSaveOnly() ? 0 : fullLintIdleMs())
                        + " warnings_on_save_only="
                        + warningsOnSaveOnly());
    }

    private boolean shouldUseTwoPhaseDiagnostics(String phase) {
        if (phase == null) return false;
        if (phase.contains("warnings-deferred")) return false;
        return readDiagnosticsBooleanSetting("twoPhasePublish", true);
    }

    private void scheduleDeferredWarningLint(Collection<Path> files, String phase) {
        if (files.isEmpty()) return;
        var now = System.nanoTime();
        changeSequence++;
        pendingWarningLintSeq = changeSequence;
        for (var file : files) {
            if (!FileStore.activeDocuments().contains(file)) continue;
            if (isCachedJarSource(file)) continue;
            pendingWarningLintFiles.add(file);
        }
        nextWarningLintAtNanos = now + TimeUnit.MILLISECONDS.toNanos(deferredWarningLintMs());
        uncheckedChanges = true;
        LOG.fine(
                "[lint-scheduler] queued deferred warning lint phase="
                        + phase
                        + " files="
                        + pendingWarningLintFiles.size()
                        + " seq="
                        + pendingWarningLintSeq
                        + " due_ms="
                        + deferredWarningLintMs());
    }

    private boolean isCachedJarSource(Path file) {
        var path = file.toString();
        return path.contains("jls-jar-sources");
    }

    private void markInteractiveRequest(String source) {
        suppressFullLintUntilNanos =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(fullLintSuppressAfterInteractiveMs());
        LOG.fine(
                "[lint-scheduler] suppress full lint source="
                        + source
                        + " for_ms="
                        + fullLintSuppressAfterInteractiveMs());
    }

    private boolean warningsOnSaveOnly() {
        return readDiagnosticsBooleanSetting("warningsOnSaveOnly", true);
    }

    private int fastEditLintDebounceMs() {
        return readDiagnosticsIntSetting("fastDebounceMs", (int) FAST_EDIT_LINT_DEBOUNCE_MS, 50, 1000);
    }

    private int fullLintIdleMs() {
        return readDiagnosticsIntSetting("fullIdleMs", (int) FULL_LINT_IDLE_MS, 200, 5000);
    }

    private int fullLintSuppressAfterInteractiveMs() {
        return readDiagnosticsIntSetting(
                "interactiveSuppressMs", (int) FULL_LINT_SUPPRESS_AFTER_INTERACTIVE_MS, 100, 5000);
    }

    private int deferredWarningLintMs() {
        return readDiagnosticsIntSetting("deferredWarningMs", (int) DEFERRED_WARNING_LINT_MS, 20, 2000);
    }

    private boolean readDiagnosticsBooleanSetting(String key, boolean fallback) {
        if (!settings.has("diagnostics")) return fallback;
        var diagnostics = settings.get("diagnostics");
        if (!diagnostics.isJsonObject()) return fallback;
        var obj = diagnostics.getAsJsonObject();
        if (!obj.has(key)) return fallback;
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int readDiagnosticsIntSetting(String key, int fallback, int min, int max) {
        if (!settings.has("diagnostics")) return fallback;
        var diagnostics = settings.get("diagnostics");
        if (!diagnostics.isJsonObject()) return fallback;
        var obj = diagnostics.getAsJsonObject();
        if (!obj.has(key)) return fallback;
        try {
            var value = obj.get(key).getAsInt();
            if (value < min) return min;
            if (value > max) return max;
            return value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
