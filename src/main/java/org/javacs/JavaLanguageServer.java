package org.javacs;

import static org.javacs.JsonHelper.GSON;

import com.google.gson.*;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.action.CodeActionConfig;
import org.javacs.action.CodeActionProvider;
import org.javacs.completion.CompletionProvider;
import org.javacs.completion.SignatureProvider;
import org.javacs.fold.FoldProvider;
import org.javacs.hover.HoverProvider;
import org.javacs.imports.AutoImportProvider;
import org.javacs.imports.AutoImportProviderFactory;
import org.javacs.imports.SimpleAutoImportProvider;
import org.javacs.inlay.InlayHintProvider;
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
    private JsonObject cacheSettings;
    private JsonObject settings = new JsonObject();
    private boolean modifiedBuild = true;
    private AutoImportProvider autoImportProvider = SimpleAutoImportProvider.INSTANCE;
    private DiagnosticsConfig diagnosticsConfig = DiagnosticsConfig.defaults();
    private FeaturesConfig featuresConfig = FeaturesConfig.defaults();
    private CodeActionConfig codeActionConfig = CodeActionConfig.defaults();
    private final Object progressToken = "jls-startup";
    private String progressTitle = "";

    synchronized JavaCompilerService compiler() {
        if (needsCompiler()) {
            cacheCompiler = createCompiler();
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
        if (files.isEmpty()) return;
        LOG.info("Lint " + files.size() + " files...");
        var started = Instant.now();
        try (var task = compiler().compile(files.toArray(Path[]::new))) {
            var compiled = Instant.now();
            LOG.info("...compiled in " + Duration.between(started, compiled).toMillis() + " ms");
            if (diagnosticsConfig.enabled) {
                for (var errs : new ErrorProvider(task, diagnosticsConfig.unusedImportsSeverity).errors()) {
                    client.publishDiagnostics(errs);
                }
            } else {
                for (var root : task.roots) {
                    client.publishDiagnostics(
                            new PublishDiagnosticsParams(root.getSourceFile().toUri(), List.of()));
                }
            }
            for (var colors : new ColorProvider(task).colors()) {
                client.customNotification("java/colors", GSON.toJsonTree(colors));
            }
            var published = Instant.now();
            LOG.info("...published in " + Duration.between(started, published).toMillis() + " ms");
        } catch (AssertionError e) {
            LOG.log(Level.SEVERE, "Lint failed with compiler assertion", e);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Lint failed", e);
        }
    }

    private void javaStartProgress(JavaStartProgressParams params) {
        // legacy custom notification
        client.customNotification("java/startProgress", GSON.toJsonTree(params));
        progressTitle = params.getMessage();
        // standard workDoneProgress
        client.workDoneProgressCreate(progressToken);
        var p = new ProgressParams();
        p.token = progressToken;
        p.value = new ProgressParams.ProgressValue();
        p.value.kind = "begin";
        p.value.title = progressTitle;
        p.value.percentage = 0;
        client.workDoneProgressNotify(p);
    }

    private void javaReportProgress(JavaReportProgressParams params, Integer percentage) {
        client.customNotification("java/reportProgress", GSON.toJsonTree(params));
        var p = new ProgressParams();
        p.token = progressToken;
        p.value = new ProgressParams.ProgressValue();
        p.value.kind = "report";
        p.value.title = progressTitle;
        p.value.message = params.getMessage();
        p.value.percentage = percentage;
        client.workDoneProgressNotify(p);
    }

    private void javaEndProgress() {
        client.customNotification("java/endProgress", JsonNull.INSTANCE);
        var p = new ProgressParams();
        p.token = progressToken;
        p.value = new ProgressParams.ProgressValue();
        p.value.kind = "end";
        p.value.title = "Ready";
        p.value.message = "Ready";
        p.value.percentage = 100;
        client.workDoneProgressNotify(p);
    }

    private JavaCompilerService createCompiler() {
        Objects.requireNonNull(workspaceRoot, "Can't create compiler because workspaceRoot has not been initialized");

        var started = Instant.now();
        javaStartProgress(new JavaStartProgressParams("Configure javac"));
        javaReportProgress(new JavaReportProgressParams("Finding source roots"), 10);

        var externalDependencies = externalDependencies();
        var classPath = classPath();
        var addExports = addExports();
        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            javaReportProgress(new JavaReportProgressParams("Using explicit classpath"), 90);
            javaEndProgress();
            var service = new JavaCompilerService(classPath, docPath(), addExports);
            LOG.fine(
                    String.format(
                            "Compiler configured with explicit classpath in %,d ms",
                            Duration.between(started, Instant.now()).toMillis()));
            return service;
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var fingerprint = inferenceFingerprint(externalDependencies, mavenSettings());
            var cached = loadInferenceCache(fingerprint);
            if (cached != null) {
                javaReportProgress(new JavaReportProgressParams("Using cached classpath"), 80);
                javaEndProgress();
                var cachedClassPath = new HashSet<Path>();
                for (var p : cached.classPath) {
                    cachedClassPath.add(Paths.get(p));
                }
                var cachedDocPath = new HashSet<Path>();
                for (var p : cached.docPath) {
                    cachedDocPath.add(Paths.get(p));
                }
                var service = new JavaCompilerService(cachedClassPath, cachedDocPath, addExports);
                LOG.fine(
                        String.format(
                                "Compiler configured from cache in %,d ms",
                                Duration.between(started, Instant.now()).toMillis()));
                return service;
            }
            var infer = new InferConfig(workspaceRoot, externalDependencies, mavenSettings().orElse(null));

            javaReportProgress(new JavaReportProgressParams("Inferring class path"), 40);
            var cpStarted = Instant.now();
            classPath = infer.classPath();
            LOG.fine(
                    String.format(
                            "Inferred classpath in %,d ms",
                            Duration.between(cpStarted, Instant.now()).toMillis()));

            javaReportProgress(new JavaReportProgressParams("Inferring doc path"), 70);
            var docStarted = Instant.now();
            var docPath = infer.buildDocPath();
            lombokDocSources().ifPresent(source -> {
                if (docPath.add(source)) {
                    LOG.info("Added Lombok doc path " + source);
                }
            });
            LOG.fine(
                    String.format(
                            "Inferred doc path in %,d ms",
                            Duration.between(docStarted, Instant.now()).toMillis()));

            javaEndProgress();
            saveInferenceCache(fingerprint, classPath, docPath);
            var service = new JavaCompilerService(classPath, docPath, addExports);
            LOG.fine(
                    String.format(
                            "Compiler configured in %,d ms",
                            Duration.between(started, Instant.now()).toMillis()));
            return service;
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

    private Optional<Path> mavenSettings() {
        if (!settings.has("mavenSettings")) return Optional.empty();
        var value = settings.get("mavenSettings");
        if (value == null || value.isJsonNull()) return Optional.empty();
        return Optional.of(Paths.get(value.getAsString()).toAbsolutePath());
    }

    private static class InferenceCache {
        String fingerprint;
        Set<String> classPath = Set.of();
        Set<String> docPath = Set.of();
    }

    private String inferenceFingerprint(Set<String> externalDependencies, Optional<Path> mavenSettings) {
        var sb = new StringBuilder();
        sb.append("root=").append(workspaceRoot.toAbsolutePath().normalize());
        var deps = new ArrayList<>(externalDependencies);
        Collections.sort(deps);
        sb.append("|deps=").append(String.join(";", deps));
        if (mavenSettings.isPresent()) {
            sb.append("|maven=").append(fileFingerprint(mavenSettings.get()));
        } else {
            sb.append("|maven=none");
        }
        for (var name : buildFileNames()) {
            var path = workspaceRoot.resolve(name);
            if (Files.exists(path)) {
                sb.append("|").append(fileFingerprint(path));
            }
        }
        return md5(sb.toString());
    }

    private static List<String> buildFileNames() {
        return List.of(
                "pom.xml",
                "build.gradle",
                "build.gradle.kts",
                "settings.gradle",
                "settings.gradle.kts",
                "gradle.properties",
                "BUILD",
                "WORKSPACE",
                "javaconfig.json");
    }

    private static String fileFingerprint(Path path) {
        try {
            var stat = Files.readAttributes(path, BasicFileAttributes.class);
            return path.toAbsolutePath().normalize()
                    + ":"
                    + stat.size()
                    + ":"
                    + stat.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize() + ":missing";
        }
    }

    private static String md5(String input) {
        try {
            var md = MessageDigest.getInstance("MD5");
            var bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (var b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private InferenceCache loadInferenceCache(String fingerprint) {
        var cacheFile = CacheConfig.cacheDir().resolve("inferred-classpath.json");
        if (!Files.exists(cacheFile)) return null;
        try (var reader = Files.newBufferedReader(cacheFile)) {
            var cache = GSON.fromJson(reader, InferenceCache.class);
            if (cache == null || cache.fingerprint == null) return null;
            if (!fingerprint.equals(cache.fingerprint)) return null;
            return cache;
        } catch (IOException e) {
            LOG.warning("Failed to read inference cache: " + e.getMessage());
            return null;
        }
    }

    private void saveInferenceCache(String fingerprint, Set<Path> classPath, Set<Path> docPath) {
        var cacheFile = CacheConfig.cacheDir().resolve("inferred-classpath.json");
        var cache = new InferenceCache();
        cache.fingerprint = fingerprint;
        var classPathStrings = new HashSet<String>();
        for (var p : classPath) {
            classPathStrings.add(p.toAbsolutePath().normalize().toString());
        }
        cache.classPath = classPathStrings;
        var docPathStrings = new HashSet<String>();
        for (var p : docPath) {
            docPathStrings.add(p.toAbsolutePath().normalize().toString());
        }
        cache.docPath = docPathStrings;
        try (var writer = Files.newBufferedWriter(cacheFile)) {
            GSON.toJson(cache, writer);
        } catch (IOException e) {
            LOG.warning("Failed to write inference cache: " + e.getMessage());
        }
    }

    private Optional<Path> lombokDocSources() {
        var lombokPath = System.getProperty("org.javacs.lombokPath");
        if (lombokPath == null || lombokPath.isBlank()) return Optional.empty();
        var jar = Paths.get(lombokPath);
        var parent = jar.getParent();
        if (parent == null) {
            parent = jar.toAbsolutePath().getParent();
            if (parent == null) return Optional.empty();
        }
        if (!Files.exists(jar)) {
            return Optional.empty();
        }
        var baseName = jar.getFileName().toString();
        if (baseName.endsWith(".jar")) {
            baseName = baseName.substring(0, baseName.length() - ".jar".length());
        }
        var candidates =
                List.of(
                        baseName + "-sources.jar",
                        baseName + "-source.jar",
                        baseName + "-src.jar",
                        "lombok-sources.jar",
                        "lombok-source.jar");
        for (var candidate : candidates) {
            var source = parent.resolve(candidate);
            if (Files.isRegularFile(source)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        applyInitOptions(params.initializationOptions);
        if (params.rootUri != null) {
            this.workspaceRoot = Paths.get(params.rootUri);
        } else if (params.rootPath != null) {
            this.workspaceRoot = Paths.get(params.rootPath);
        } else if (params.workspaceFolders != null && !params.workspaceFolders.isEmpty()) {
            this.workspaceRoot = Paths.get(params.workspaceFolders.get(0).uri);
        } else {
            this.workspaceRoot = Paths.get(".").toAbsolutePath().normalize();
        }
        FileStore.setWorkspaceRoots(Set.of(this.workspaceRoot));

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
        var codeActionOptions = new JsonObject();
        codeActionOptions.addProperty("resolveProvider", true);
        c.add("codeActionProvider", codeActionOptions);
        var renameOptions = new JsonObject();
        renameOptions.addProperty("prepareProvider", true);
        c.add("renameProvider", renameOptions);
        var window = new JsonObject();
        window.addProperty("workDoneProgress", true);
        c.add("window", window);
        if (featuresConfig.inlayHints) {
            c.addProperty("inlayHintProvider", true);
        }
        if (featuresConfig.semanticTokens) {
            c.add("semanticTokensProvider", semanticTokensProvider());
        }

        return new InitializeResult(c);
    }

    private static final String[] watchFiles = {
        "**/*.java", "**/pom.xml", "**/BUILD", "**/javaconfig.json", "**/WORKSPACE"
    };

    @Override
    public void initialized() {
        client.registerCapability("workspace/didChangeWatchedFiles", watchFiles(watchFiles));
        CompletableFuture.runAsync(this::compiler);
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
        updateAutoImportProvider();
    }

    @Override
    public List<SymbolInformation> workspaceSymbols(WorkspaceSymbolParams params) {
        return new SymbolProvider(compiler()).findSymbols(params.query, 50);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var next = normalizeSettings(change.settings);
        LOG.info("Received settings " + next);
        settings = next;
        diagnosticsConfig = DiagnosticsConfig.from(settings);
        featuresConfig = FeaturesConfig.from(settings);
        codeActionConfig = CodeActionConfig.from(settings);
        updateAutoImportProvider();
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
                        break;
                    case FileChangeType.Deleted:
                        removeClass(file);
                        break;
                }
                continue;
            }
            var name = file.getFileName().toString();
            switch (name) {
                case "BUILD":
                case "pom.xml":
                    LOG.info("Compiler needs to be re-created because " + file + " has changed");
                    modifiedBuild = true;
            }
        }
    }

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        try {
            var file = Paths.get(params.textDocument.uri);
            var provider = new CompletionProvider(compiler(), autoImportProvider);
            var list = provider.complete(file, params.position.line + 1, params.position.character + 1);
            if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
            return Optional.of(list);
        } catch (AssertionError e) {
            LOG.log(Level.SEVERE, "Completion failed with compiler assertion", e);
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Completion failed", e);
            return Optional.empty();
        }
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        new HoverProvider(compiler()).resolveCompletionItem(unresolved);
        return unresolved;
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var list = new HoverProvider(compiler()).hover(file, line, column);
        if (list == HoverProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        // TODO add range
        return Optional.of(new Hover(list));
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
    public Optional<List<InlayHint>> inlayHint(InlayHintParams params) {
        if (!featuresConfig.inlayHints) return Optional.of(List.of());
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.of(List.of());
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            var range = params != null ? params.range : null;
            var root = task.root(file);
            return Optional.of(new InlayHintProvider(task, root, range).hints());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Inlay hints failed", e);
            return Optional.of(List.of());
        }
    }

    @Override
    public Optional<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        if (!featuresConfig.semanticTokens) return Optional.of(new SemanticTokens());
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.of(new SemanticTokens());
        return Optional.of(new SemanticTokens());
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
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
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new ReferenceProvider(compiler(), file, line, column).find();
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
        var resolved = new ArrayList<CodeLens>(lenses.size());
        for (var lens : lenses) {
            resolved.add(resolveCodeLens(lens));
        }
        return resolved;
    }

    @Override
    public CodeLens resolveCodeLens(CodeLens unresolved) {
        if (unresolved == null || unresolved.data == null || !unresolved.data.isJsonObject()) {
            return unresolved;
        }
        var obj = unresolved.data.getAsJsonObject();
        if (!obj.has("uri") || !obj.has("line") || !obj.has("column") || !obj.has("name")) {
            return unresolved;
        }
        try {
            var name = obj.get("name").getAsString();
            if (name == null || name.isBlank()) return unresolved;
            var count = fastReferenceCount(name);
            var title = count == 1 ? "1 reference" : count + " references";
            if (count > 20) {
                title = "20+ references";
            }
            unresolved.command = new Command(title, "jls.showReferences", null);
            return unresolved;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "CodeLens resolve failed", e);
            return unresolved;
        }
    }

    private int fastReferenceCount(String name) {
        var files = org.javacs.index.WorkspaceIndex.filesContaining(name);
        if (files.isEmpty()) return 0;
        int count = 0;
        final int limit = 20;
        var search = new StringSearch(name);
        for (var file : files) {
            var text = FileStore.contents(file);
            int remaining = limit - count;
            if (remaining <= 0) return limit + 1;
            var found = search.countWords(text, remaining);
            if (found > remaining) return limit + 1;
            count += found;
        }
        return count;
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
        LOG.info("Try to rename...");
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            CompilationUnitTree root;
            try {
                root = task.root(file);
            } catch (RuntimeException e) {
                LOG.info("...couldn't resolve compilation unit");
                return Optional.empty();
            }
            var lines = root.getLineMap();
            var cursor = lines.getPosition(params.position.line + 1, params.position.character + 1);
            var path = new FindNameAt(task).scan(root, cursor);
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
            CompilationUnitTree root;
            try {
                root = task.root(file);
            } catch (RuntimeException e) {
                return Rewrite.NOT_SUPPORTED;
            }
            var lines = root.getLineMap();
            var position = lines.getPosition(params.position.line + 1, params.position.character + 1);
            var path = new FindNameAt(task).scan(root, position);
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

    private void removeClass(Path file) {
        var className = cacheCompiler.fileManager.getClassName(file);
        FileStore.externalDelete(file);
        var compiler = compiler();
        var referencePaths =
                Arrays.stream(compiler.findTypeReferences(className)).filter(ref -> !ref.equals(file)).toList();
        if (referencePaths.isEmpty()) {
            return;
        }
        for (var referencePath : referencePaths) {
            try (var task = compiler.compile(referencePath)) {
                compiler.compiler.removeClass((JCTree.JCCompilationUnit) task.root(), className);
            }
        }
        compiler.clearCachedModified();
        lint(referencePaths);
    }

    private boolean uncheckedChanges = false;
    private Path lastEdited = Paths.get("");

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        FileStore.open(params);
        if (!FileStore.isJavaFile(params.textDocument.uri)) return;
        lastEdited = Paths.get(params.textDocument.uri);
        uncheckedChanges = true;
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        lastEdited = Paths.get(params.textDocument.uri);
        uncheckedChanges = true;
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);

        if (FileStore.isJavaFile(params.textDocument.uri)) {
            // Clear diagnostics
            client.publishDiagnostics(new PublishDiagnosticsParams(params.textDocument.uri, List.of()));
        }
    }

    @Override
    public List<CodeAction> codeAction(CodeActionParams params) {
        var provider = new CodeActionProvider(compiler(), autoImportProvider, codeActionConfig);
        var actions = new ArrayList<CodeAction>();
        actions.addAll(provider.codeActionsForCursor(params));
        if (!params.context.diagnostics.isEmpty()) {
            actions.addAll(provider.codeActionForDiagnostics(params));
        }
        return actions;
    }

    @Override
    public CodeAction codeActionResolve(CodeAction action) {
        return new CodeActionProvider(compiler(), autoImportProvider, codeActionConfig)
                .resolve(action);
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            var file = Paths.get(params.textDocument.uri);
            var targets = new HashSet<>(FileStore.activeDocuments());
            targets.add(file);
            try {
                var className = compiler().fileManager.getClassName(file);
                for (var ref : compiler().findTypeReferences(className)) {
                    targets.add(ref);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to compute dependent files for " + file, e);
            }
            // Re-lint active + dependent documents
            lint(targets);
        }
    }

    @Override
    public void doAsyncWork() {
        if (uncheckedChanges && FileStore.activeDocuments().contains(lastEdited)) {
            lint(List.of(lastEdited));
            uncheckedChanges = false;
        }
    }

    private static JsonObject normalizeSettings(JsonElement settings) {
        if (settings == null || settings.isJsonNull()) {
            return new JsonObject();
        }
        if (!settings.isJsonObject()) {
            return new JsonObject();
        }
        var obj = settings.getAsJsonObject();
        if (obj.has("jls") && obj.get("jls").isJsonObject()) {
            return obj.getAsJsonObject("jls");
        }
        if (obj.has("java") && obj.get("java").isJsonObject()) {
            return obj.getAsJsonObject("java");
        }
        return obj;
    }

    private void applyInitOptions(JsonElement initOptions) {
        if (initOptions == null || initOptions.isJsonNull() || !initOptions.isJsonObject()) {
            return;
        }
        var obj = normalizeSettings(initOptions);
        if (obj.has("cache") && obj.get("cache").isJsonObject()) {
            var cache = obj.getAsJsonObject("cache");
            if (cache.has("dir")) {
                var dir = cache.getAsJsonPrimitive("dir").getAsString();
                if (dir != null && !dir.isBlank()) {
                    CacheConfig.setCacheDir(Paths.get(dir));
                }
            }
        }
        if (obj.has("features") && obj.get("features").isJsonObject()) {
            featuresConfig = FeaturesConfig.from(obj);
        }
    }

    private JsonObject semanticTokensProvider() {
        var provider = new JsonObject();
        var legend = new JsonObject();
        var tokenTypes = new JsonArray();
        tokenTypes.add("class");
        tokenTypes.add("method");
        tokenTypes.add("variable");
        var tokenModifiers = new JsonArray();
        tokenModifiers.add("declaration");
        tokenModifiers.add("static");
        legend.add("tokenTypes", tokenTypes);
        legend.add("tokenModifiers", tokenModifiers);
        provider.add("legend", legend);
        provider.addProperty("full", true);
        return provider;
    }

    private static class DiagnosticsConfig {
        final boolean enabled;
        final Integer unusedImportsSeverity;

        private DiagnosticsConfig(boolean enabled, Integer unusedImportsSeverity) {
            this.enabled = enabled;
            this.unusedImportsSeverity = unusedImportsSeverity;
        }

        static DiagnosticsConfig defaults() {
            return new DiagnosticsConfig(true, DiagnosticSeverity.Warning);
        }

        static DiagnosticsConfig from(JsonObject settings) {
            var diagnostics =
                    settings.has("diagnostics") && settings.get("diagnostics").isJsonObject()
                            ? settings.getAsJsonObject("diagnostics")
                            : new JsonObject();
            var enabled = getBoolean(diagnostics, "enable", true);
            var unusedImports = getString(diagnostics, "unusedImports", "warning");
            var severity = parseSeverity(unusedImports);
            return new DiagnosticsConfig(enabled, severity);
        }
    }

    private static class FeaturesConfig {
        final boolean inlayHints;
        final boolean semanticTokens;

        private FeaturesConfig(boolean inlayHints, boolean semanticTokens) {
            this.inlayHints = inlayHints;
            this.semanticTokens = semanticTokens;
        }

        static FeaturesConfig defaults() {
            return new FeaturesConfig(true, true);
        }

        static FeaturesConfig from(JsonObject settings) {
            var features =
                    settings.has("features") && settings.get("features").isJsonObject()
                            ? settings.getAsJsonObject("features")
                            : new JsonObject();
            var inlayHints = getBoolean(features, "inlayHints", false);
            var semanticTokens = getBoolean(features, "semanticTokens", false);
            return new FeaturesConfig(inlayHints, semanticTokens);
        }
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.getAsJsonPrimitive(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.getAsJsonPrimitive(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer parseSeverity(String value) {
        if (value == null) return DiagnosticSeverity.Warning;
        switch (value.toLowerCase(Locale.ROOT)) {
            case "off":
            case "none":
            case "false":
                return null;
            case "error":
                return DiagnosticSeverity.Error;
            case "warning":
            default:
                return DiagnosticSeverity.Warning;
        }
    }

    private void updateAutoImportProvider() {
        if (!settings.has("importOrder")) {
            return;
        }
        var name = settings.getAsJsonPrimitive("importOrder").getAsString();
        try {
            autoImportProvider = AutoImportProviderFactory.getByName(name);
        } catch (IllegalArgumentException e) {
            LOG.warning("Unknown import order: " + name);
            LOG.warning("Falling back to the default import order");
            autoImportProvider = SimpleAutoImportProvider.INSTANCE;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
