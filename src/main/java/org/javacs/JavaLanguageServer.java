package org.javacs;

import static org.javacs.JsonHelper.GSON;

import com.google.gson.*;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.action.CodeActionProvider;
import org.javacs.completion.CompletionProvider;
import org.javacs.completion.SignatureProvider;
import org.javacs.completion.TypeMemberIndex;
import org.javacs.fold.FoldProvider;
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
    private JavaCompilerService cacheDiagnosticsCompilerPrimary;
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
    private ScheduledFuture<?> pendingDiagnostics;
    private ScheduledFuture<?> pendingCompletionIndex;
    private static final long DIAGNOSTIC_DEBOUNCE_MS = 400;
    private static final long COMPLETION_INDEX_DEBOUNCE_MS = 100;
    private boolean lombokVerifiedForCurrentCompiler;
    private boolean lombokEnabledForCurrentCompiler = true;
    private final AtomicReference<TypeMemberIndex> completionIndexRef =
            new AtomicReference<>(TypeMemberIndex.EMPTY);
    private final AtomicLong completionIndexVersion = new AtomicLong();

    synchronized JavaCompilerService compiler() {
        if (needsCompiler()) {
            var compilers = createCompilers();
            cacheCompiler = compilers.interactive;
            cacheDiagnosticsCompilerPrimary = compilers.diagnosticsPrimary;
            lombokEnabledForCurrentCompiler = compilers.lombokEnabled;
            lombokVerifiedForCurrentCompiler = false;
            completionIndexRevision.incrementAndGet();
            cancelPendingCompletionIndex("compilerRecreated");
            cacheSettings = compilerSettingsSnapshot(settings);
            modifiedBuild = false;
            refreshCompletionIndexForCompilerRecreated();
        }
        return cacheCompiler;
    }

    private DiagnosticsCompilerSelection selectDiagnosticsCompiler() {
        compiler();
        return new DiagnosticsCompilerSelection(cacheDiagnosticsCompilerPrimary);
    }

    private boolean needsCompiler() {
        if (modifiedBuild) {
            return true;
        }
        var currentCompilerSettings = compilerSettingsSnapshot(settings);
        if (!currentCompilerSettings.equals(cacheSettings)) {
            LOG.info("Settings\n\t" + settings + "\nis different than\n\t" + cacheSettings);
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
        var active = FileStore.activeDocuments();
        if (!active.isEmpty()) {
            refreshCompletionIndexNow(active, "compilerRecreated");
            return;
        }
        scheduleProjectCompletionIndexRefresh("compilerRecreated", COMPLETION_INDEX_DEBOUNCE_MS);
    }

    void lint(Collection<Path> files) {
        cancelPendingDiagnostics("foreground");
        cancelPendingCompletionIndex("foreground");
        var selection = selectDiagnosticsCompiler();
        compileAndPublish(files, selection.compiler, "foreground", -1, false);
    }

    private void compileAndPublish(
            Collection<Path> files,
            JavaCompilerService diagnosticsCompiler,
            String trigger,
            long expectedRevision,
            boolean updateCompletionIndex) {
        var waitStarted = Instant.now();
        synchronized (diagnosticsCompileMutex) {
            var waited = Duration.between(waitStarted, Instant.now()).toMillis();
            if (waited > 0) {
                LOG.info(
                        String.format(
                                "[perf] diagnostics_compile_wait trigger=%s waited=%dms",
                                trigger, waited));
            }
            var javaFiles = normalizeJavaFiles(files);
            if (javaFiles.isEmpty()) return;
            LOG.info("Lint " + javaFiles.size() + " files...");
            var started = Instant.now();
            CompileTask task = null;
            try {
                task = diagnosticsCompiler.compile(javaFiles.toArray(Path[]::new));
                var compiled = Instant.now();
                LOG.info(
                        String.format(
                                "[perf] diagnostics_compile trigger=%s files=%d took=%dms",
                                trigger, javaFiles.size(), Duration.between(started, compiled).toMillis()));
                if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "post_compile")) {
                    return;
                }
                verifyLombokSymbols(task, "diagnostics");
                if (updateCompletionIndex) {
                    var indexStarted = Instant.now();
                    var nextIndex = TypeMemberIndex.from(task);
                    if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "post_index_build")) {
                        return;
                    }
                    var indexVersion = nextIndexVersion();
                    installTypeMemberIndex(
                            nextIndex,
                            indexVersion,
                            "index:" + trigger,
                            Duration.between(indexStarted, Instant.now()));
                    LOG.info(
                            String.format(
                                    "[perf] completion_index_refresh_shared trigger=%s files=%d version=%d took=%dms",
                                    trigger,
                                    javaFiles.size(),
                                    indexVersion,
                                    Duration.between(indexStarted, Instant.now()).toMillis()));
                    if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "post_index_install")) {
                        return;
                    }
                }
                var publishStarted = Instant.now();
                if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "pre_publish")) {
                    return;
                }
                var diagnosticsCount = 0;
                var publishedUris = new HashSet<java.net.URI>();
                for (var errs : new ErrorProvider(task).errors()) {
                    if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "publish_loop")) {
                        return;
                    }
                    client.publishDiagnostics(errs);
                    diagnosticsCount += errs.diagnostics.size();
                    publishedUris.add(errs.uri);
                }
                // Always clear diagnostics for requested files that did not produce any root in this compile.
                for (var file : javaFiles) {
                    if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "publish_clear_loop")) {
                        return;
                    }
                    if (!FileStore.isJavaFile(file)) continue;
                    var uri = file.toUri();
                    if (publishedUris.contains(uri)) continue;
                    client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
                }
                for (var colors : new ColorProvider(task).colors()) {
                    client.customNotification("java/colors", GSON.toJsonTree(colors));
                }
                LOG.info(
                        String.format(
                                "[perf] diagnostics_publish trigger=%s files=%d diagnostics=%d took=%dms",
                                trigger,
                                javaFiles.size(),
                                diagnosticsCount,
                                Duration.between(publishStarted, Instant.now()).toMillis()));
                if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "post_publish")) {
                    return;
                }
            } finally {
                if (task != null) {
                    task.close();
                }
            }
        }
    }

    private List<Path> normalizeJavaFiles(Collection<Path> files) {
        var javaFiles = new ArrayList<Path>();
        for (var file : files) {
            if (FileStore.isJavaFile(file)) {
                javaFiles.add(file);
            }
        }
        return javaFiles;
    }

    private void installTypeMemberIndex(
            TypeMemberIndex nextIndex, long indexVersion, String trigger, Duration took) {
        var rebuilt = nextIndex == null ? TypeMemberIndex.EMPTY : nextIndex;
        completionIndexRef.set(rebuilt);
        completionIndexVersion.set(indexVersion);
        LOG.info(
                String.format(
                        "[perf] completion_type_index trigger=%s version=%d types=%d took=%dms",
                        trigger, indexVersion, rebuilt.size(), took.toMillis()));
    }

    private long nextIndexVersion() {
        return completionIndexVersion.incrementAndGet();
    }

    private void refreshProjectCompletionIndexNow(String trigger) {
        refreshCompletionIndexNow(FileStore.all(), trigger);
    }

    private void refreshCompletionIndexNow(Collection<Path> files, String trigger) {
        var javaFiles = normalizeJavaFiles(files);
        if (javaFiles.isEmpty()) {
            return;
        }
        if (cacheCompiler == null) {
            return;
        }
        var started = Instant.now();
        CompileTask task = null;
        try {
            task = cacheCompiler.compile(javaFiles.toArray(Path[]::new));
            var indexStarted = Instant.now();
            var nextIndex = TypeMemberIndex.from(task);
            var indexVersion = nextIndexVersion();
            installTypeMemberIndex(
                    nextIndex,
                    indexVersion,
                    "index:" + trigger,
                    Duration.between(indexStarted, Instant.now()));
            LOG.info(
                    String.format(
                            "[perf] completion_index_refresh_sync trigger=%s files=%d version=%d compile=%dms total=%dms",
                            trigger,
                            javaFiles.size(),
                            indexVersion,
                            Duration.between(started, indexStarted).toMillis(),
                            Duration.between(started, Instant.now()).toMillis()));
        } catch (Exception e) {
            LOG.warning(
                    String.format(
                            "[completion] sync index refresh failed trigger=%s files=%d reason=%s",
                            trigger, javaFiles.size(), e.getMessage()));
            LOG.log(java.util.logging.Level.FINE, "", e);
        } finally {
            if (task != null) {
                task.close();
            }
        }
    }

    private void scheduleProjectCompletionIndexRefresh(String trigger, long delayMs) {
        scheduleCompletionIndexRefresh(FileStore.all(), trigger, delayMs);
    }

    private void scheduleCompletionIndexRefresh(Collection<Path> files, String trigger) {
        scheduleCompletionIndexRefresh(files, trigger, COMPLETION_INDEX_DEBOUNCE_MS);
    }

    private void scheduleCompletionIndexRefresh(Collection<Path> files, String trigger, long delayMs) {
        var javaFiles = normalizeJavaFiles(files);
        if (javaFiles.isEmpty()) {
            return;
        }
        var revision = completionIndexRevision.incrementAndGet();
        var filesBatch = List.copyOf(javaFiles);
        synchronized (this) {
            if (pendingCompletionIndex != null) {
                pendingCompletionIndex.cancel(false);
            }
            pendingCompletionIndex =
                    completionIndexExecutor.schedule(
                            () -> runCompletionIndexRefresh(filesBatch, revision, trigger),
                            delayMs,
                            TimeUnit.MILLISECONDS);
        }
        LOG.info(
                String.format(
                        "[perf] completion_index_debounce trigger=%s files=%d delay=%dms revision=%d",
                        trigger, filesBatch.size(), delayMs, revision));
    }

    private void runCompletionIndexRefresh(List<Path> files, long revision, String trigger) {
        if (revision != completionIndexRevision.get()) {
            return;
        }
        compiler();
        var started = Instant.now();
        CompileTask task = null;
        try {
            task = cacheCompiler.compile(files.toArray(Path[]::new));
            if (revision != completionIndexRevision.get()) {
                LOG.info(
                        String.format(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_compile expected=%d current=%d",
                                trigger, revision, completionIndexRevision.get()));
                return;
            }
            var indexStarted = Instant.now();
            var nextIndex = TypeMemberIndex.from(task);
            if (revision != completionIndexRevision.get()) {
                LOG.info(
                        String.format(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_index expected=%d current=%d",
                                trigger, revision, completionIndexRevision.get()));
                return;
            }
            var indexVersion = nextIndexVersion();
            installTypeMemberIndex(
                    nextIndex,
                    indexVersion,
                    "index:" + trigger,
                    Duration.between(indexStarted, Instant.now()));
            LOG.info(
                    String.format(
                            "[perf] completion_index_refresh trigger=%s files=%d version=%d compile=%dms total=%dms",
                            trigger,
                            files.size(),
                            indexVersion,
                            Duration.between(started, indexStarted).toMillis(),
                            Duration.between(started, Instant.now()).toMillis()));
        } catch (Exception e) {
            LOG.warning(
                    String.format(
                            "[completion] index refresh failed trigger=%s files=%d reason=%s",
                            trigger, files.size(), e.getMessage()));
            LOG.log(java.util.logging.Level.FINE, "", e);
        } finally {
            if (task != null) {
                task.close();
            }
        }
    }

    private void scheduleDiagnostics(Collection<Path> files, String trigger) {
        scheduleDiagnostics(files, trigger, DIAGNOSTIC_DEBOUNCE_MS);
    }

    private void scheduleDiagnostics(Collection<Path> files, String trigger, long delayMs) {
        var javaFiles = normalizeJavaFiles(files);
        if (javaFiles.isEmpty()) return;
        var revision = diagnosticsRevision.incrementAndGet();
        var filesBatch = List.copyOf(javaFiles);
        synchronized (this) {
            if (pendingDiagnostics != null) {
                pendingDiagnostics.cancel(false);
            }
            pendingDiagnostics =
                    diagnosticsExecutor.schedule(
                            () -> runDiagnostics(filesBatch, revision, trigger),
                            delayMs,
                            TimeUnit.MILLISECONDS);
        }
        LOG.info(
                String.format(
                        "[perf] diagnostics_debounce trigger=%s files=%d delay=%dms revision=%d",
                        trigger, filesBatch.size(), delayMs, revision));
    }

    private void cancelPendingDiagnostics(String reason) {
        synchronized (this) {
            if (pendingDiagnostics == null) {
                return;
            }
            diagnosticsRevision.incrementAndGet();
            pendingDiagnostics.cancel(false);
            pendingDiagnostics = null;
        }
        LOG.info("[perf] diagnostics_cancel reason=" + reason);
    }

    private void runDiagnostics(
            List<Path> files, long revision, String trigger) {
        if (revision != diagnosticsRevision.get()) {
            return;
        }
        var selection = selectDiagnosticsCompiler();
        try {
            compileAndPublish(files, selection.compiler, "async:" + trigger, revision, false);
        } catch (Exception e) {
            LOG.warning("Async lint failed for " + files + ": " + e.getMessage());
            LOG.log(java.util.logging.Level.FINE, "", e);
        }
    }

    private void preparseActiveDocument(Path file, String trigger) {
        try {
            var interactive = compiler();
            interactive.parse(file);
            var parsedCompilers = 1;
            if (cacheDiagnosticsCompilerPrimary != null && cacheDiagnosticsCompilerPrimary != interactive) {
                cacheDiagnosticsCompilerPrimary.parse(file);
                parsedCompilers++;
            }
            LOG.fine(
                    String.format(
                            "[perf] parse_lifecycle trigger=%s file=%s compilers=%d",
                            trigger, file.getFileName(), parsedCompilers));
        } catch (RuntimeException e) {
            LOG.fine(
                    String.format(
                            "[perf] parse_lifecycle trigger=%s file=%s status=failed reason=%s",
                            trigger, file.getFileName(), e.getMessage()));
        }
    }

    private void cancelPendingCompletionIndex(String reason) {
        synchronized (this) {
            if (pendingCompletionIndex == null) {
                return;
            }
            completionIndexRevision.incrementAndGet();
            pendingCompletionIndex.cancel(false);
            pendingCompletionIndex = null;
        }
        LOG.info("[perf] completion_index_cancel reason=" + reason);
    }

    private boolean shouldSkipStaleDiagnostics(long expectedRevision, String trigger, String phase) {
        if (expectedRevision < 0) {
            return false;
        }
        var current = diagnosticsRevision.get();
        if (expectedRevision == current) {
            return false;
        }
        LOG.info(
                String.format(
                        "[perf] diagnostics_skip_stale trigger=%s phase=%s expected=%d current=%d",
                        trigger, phase, expectedRevision, current));
        return true;
    }

    private void verifyLombokSymbols(CompileTask task, String phase) {
        if (lombokVerifiedForCurrentCompiler) return;
        if (!lombokEnabledForCurrentCompiler) {
            LOG.info("[perf] lombok_verify phase=" + phase + " skipped=disabled_by_setting");
            lombokVerifiedForCurrentCompiler = true;
            return;
        }
        var trees = Trees.instance(task.task);
        for (var root : task.roots) {
            for (var typeDecl : root.getTypeDecls()) {
                if (!(typeDecl instanceof ClassTree)) continue;
                var cls = (ClassTree) typeDecl;
                if (!hasLombokAnnotation(cls)) continue;
                var path = trees.getPath(root, cls);
                if (path == null) continue;
                var element = trees.getElement(path);
                if (!(element instanceof TypeElement)) continue;
                var typeElement = (TypeElement) element;
                var visibleMethods =
                        typeElement.getEnclosedElements().stream()
                                .filter(e -> e.getKind() == ElementKind.METHOD)
                                .map(e -> e.getSimpleName().toString())
                                .collect(java.util.stream.Collectors.toSet());
                var expectedGenerated = expectedLombokMethodNames(cls);
                var generatedVisible = visibleMethods.stream().anyMatch(expectedGenerated::contains);
                LOG.info(
                        String.format(
                                "[perf] lombok_verify phase=%s class=%s generated_members_visible=%s enclosed_count=%d",
                                phase,
                                typeElement.getQualifiedName(),
                                generatedVisible,
                                typeElement.getEnclosedElements().size()));
                if (generatedVisible) {
                    lombokVerifiedForCurrentCompiler = true;
                }
                return;
            }
        }
        LOG.info("[perf] lombok_verify phase=" + phase + " skipped=no_lombok_annotated_class");
        lombokVerifiedForCurrentCompiler = true;
    }

    private boolean hasLombokAnnotation(ClassTree cls) {
        for (var annotation : cls.getModifiers().getAnnotations()) {
            var annotationType = annotation.getAnnotationType().toString();
            if (isLombokAnnotationType(annotationType)) {
                LOG.fine("[perf] lombok_annotation_match type=" + annotationType);
                return true;
            }
        }
        return false;
    }

    static boolean isLombokAnnotationType(String annotationType) {
        if (annotationType == null || annotationType.isBlank()) {
            return false;
        }
        if (annotationType.startsWith("lombok.")) {
            return true;
        }
        var simpleName = annotationType;
        var lastDot = annotationType.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < annotationType.length()) {
            simpleName = annotationType.substring(lastDot + 1);
        }
        return KNOWN_LOMBOK_ANNOTATIONS.contains(simpleName);
    }

    private Set<String> expectedLombokMethodNames(ClassTree cls) {
        var names = new HashSet<String>();
        names.add("equals");
        names.add("hashCode");
        names.add("toString");
        names.add("canEqual");
        for (Tree member : cls.getMembers()) {
            if (member instanceof VariableTree variable) {
                var raw = variable.getName().toString();
                if (raw == null || raw.isBlank()) continue;
                var suffix = Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
                names.add("get" + suffix);
                names.add("is" + suffix);
                names.add("set" + suffix);
            }
        }
        return names;
    }

    private static final Set<String> KNOWN_LOMBOK_ANNOTATIONS =
            Set.of(
                    "Data",
                    "Getter",
                    "Setter",
                    "Builder",
                    "Value",
                    "SuperBuilder",
                    "AllArgsConstructor",
                    "NoArgsConstructor",
                    "RequiredArgsConstructor",
                    "ToString",
                    "EqualsAndHashCode",
                    "With",
                    "Slf4j");

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

        javaStartProgress(new JavaStartProgressParams("Configure javac"));
        javaReportProgress(new JavaReportProgressParams("Finding source roots"));

        var externalDependencies = externalDependencies();
        var classPath = classPath();
        var extraArgs = extraCompilerArgs();
        var addExports = addExports();
        var lombokEnabled = lombokEnabled();
        Set<Path> resolvedDocPath;
        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            resolvedDocPath = docPath();
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var infer = new InferConfig(workspaceRoot, externalDependencies);

            javaReportProgress(new JavaReportProgressParams("Inferring class path"));
            classPath = infer.classPath();

            javaReportProgress(new JavaReportProgressParams("Inferring doc path"));
            resolvedDocPath = infer.buildDocPath();
        }
        javaEndProgress();
        LOG.info("[perf] lombok_setting enabled=" + lombokEnabled);
        return new CompilerSet(
                new JavaCompilerService(classPath, resolvedDocPath, addExports, extraArgs, lombokEnabled),
                new JavaCompilerService(classPath, resolvedDocPath, addExports, extraArgs, lombokEnabled),
                lombokEnabled);
    }

    private static class CompilerSet {
        final JavaCompilerService interactive;
        final JavaCompilerService diagnosticsPrimary;
        final boolean lombokEnabled;

        CompilerSet(
                JavaCompilerService interactive,
                JavaCompilerService diagnosticsPrimary,
                boolean lombokEnabled) {
            this.interactive = interactive;
            this.diagnosticsPrimary = diagnosticsPrimary;
            this.lombokEnabled = lombokEnabled;
        }
    }

    private static class DiagnosticsCompilerSelection {
        final JavaCompilerService compiler;

        DiagnosticsCompilerSelection(JavaCompilerService compiler) {
            this.compiler = compiler;
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
                                LOG.info(
                                        "[perf] watched_java_change_skip reason=active_document event=created file="
                                                + file);
                            } else {
                                scheduleCompletionIndexRefresh(
                                        List.of(file), "didChangeWatchedFiles:javaCreatedExisting");
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
                            LOG.info(
                                    "[perf] watched_java_change_skip reason=active_document event=changed file="
                                            + file);
                        } else {
                            scheduleCompletionIndexRefresh(
                                    List.of(file), "didChangeWatchedFiles:javaChanged");
                        }
                        break;
                    case FileChangeType.Deleted:
                        FileStore.externalDelete(file);
                        scheduleProjectCompletionIndexRefresh("didChangeWatchedFiles:javaDeleted", 0);
                        break;
                }
                if (!activeDocuments.isEmpty()) {
                    if (suppressActiveDocumentWork) {
                        LOG.info(
                                "[perf] diagnostics_watched_skip reason=active_document file="
                                        + file);
                    } else {
                        scheduleDiagnostics(activeDocuments, "didChangeWatchedFiles");
                    }
                }
                return;
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
        var file = Paths.get(params.textDocument.uri);
        compiler();
        var completionIndex = completionIndexRef.get();
        var indexVersion = completionIndexVersion.get();
        var provider = new CompletionProvider(cacheCompiler, completionIndex, indexVersion);
        var list = provider.complete(file, params.position.line + 1, params.position.character + 1, -1);
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(list);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        new HoverProvider(compiler(), completionIndexRef.get()).resolveCompletionItem(unresolved);
        return unresolved;
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var content = new HoverProvider(compiler(), completionIndexRef.get()).hover(file, line, column);
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
        var found = new DefinitionProvider(compiler(), completionIndexRef.get(), file, line, column).find();
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

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        FileStore.open(params);
        if (!FileStore.isJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        preparseActiveDocument(file, "didOpen");
        scheduleDiagnostics(List.of(file), "didOpen");
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        if (!FileStore.isJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        preparseActiveDocument(file, "didChange");
        scheduleDiagnostics(List.of(file), "didChange");
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
            diagnosticsRevision.incrementAndGet();
            cancelPendingDiagnostics("didSave");
            cancelPendingCompletionIndex("didSave");
            var files = new LinkedHashSet<Path>();
            files.addAll(FileStore.activeDocuments());
            files.add(Paths.get(params.textDocument.uri));
            var selection = selectDiagnosticsCompiler();
            compileAndPublish(files, selection.compiler, "didSave", -1, true);
        }
    }

    @Override
    public void doAsyncWork() {}

    private static final Logger LOG = Logger.getLogger("main");
}
