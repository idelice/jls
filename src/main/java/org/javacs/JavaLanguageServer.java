package org.javacs;

import static org.javacs.JsonHelper.GSON;

import com.google.gson.*;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.lang.model.element.*;
import org.javacs.action.CodeActionProvider;
import org.javacs.completion.CompletionProvider;
import org.javacs.completion.CompletionSnapshotProvider;
import org.javacs.completion.IndexSnapshot;
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
    private JavaCompilerService cacheDiagnosticsCompilerSecondary;
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
    private final AtomicLong diagnosticsRevision = new AtomicLong();
    private ScheduledFuture<?> pendingDiagnostics;
    private static final long DIAGNOSTIC_DEBOUNCE_MS = 200;
    private static final long STALE_SNAPSHOT_REFRESH_MS = 75;
    private boolean lombokVerifiedForCurrentCompiler;
    private boolean lombokEnabledForCurrentCompiler = true;
    private final ReentrantReadWriteLock completionSnapshotLock = new ReentrantReadWriteLock();
    private CompileTask completionSnapshot;
    private volatile int completionSnapshotCompilerSlot = -1;
    private final Map<Path, Instant> completionSnapshotFileModified = new HashMap<>();
    private final Map<Path, Integer> completionSnapshotFileVersion = new HashMap<>();
    private final Map<Path, Long> staleSnapshotRefreshNanos = new ConcurrentHashMap<>();
    private final AtomicReference<IndexSnapshot> indexRef =
            new AtomicReference<>(IndexSnapshot.EMPTY);
    private final AtomicLong indexSnapshotVersion = new AtomicLong();
    private static final Pattern TRAILING_DOT_BEFORE_EOL = Pattern.compile("\\.(\\s*)(\\R)");
    private static final Pattern TRAILING_DOT_AT_EOF = Pattern.compile("\\.(\\s*)\\z");

    JavaCompilerService compiler() {
        if (needsCompiler()) {
            clearCompletionSnapshot("compiler_recreate");
            var compilers = createCompilers();
            cacheCompiler = compilers.interactive;
            cacheDiagnosticsCompilerPrimary = compilers.diagnosticsPrimary;
            cacheDiagnosticsCompilerSecondary = compilers.diagnosticsSecondary;
            lombokEnabledForCurrentCompiler = compilers.lombokEnabled;
            lombokVerifiedForCurrentCompiler = false;
            indexRef.set(IndexSnapshot.EMPTY);
            indexSnapshotVersion.set(0);
            cacheSettings = settings;
            modifiedBuild = false;
        }
        return cacheCompiler;
    }

    private DiagnosticsCompilerSelection selectDiagnosticsCompiler() {
        compiler();
        var primary = cacheDiagnosticsCompilerPrimary;
        var secondary = cacheDiagnosticsCompilerSecondary;
        if (secondary == null) {
            return new DiagnosticsCompilerSelection(primary, 0);
        }
        var snapshotSlot = activeCompletionSnapshotSlot();
        if (snapshotSlot == 0) {
            return new DiagnosticsCompilerSelection(secondary, 1);
        }
        if (snapshotSlot == 1) {
            return new DiagnosticsCompilerSelection(primary, 0);
        }
        return new DiagnosticsCompilerSelection(primary, 0);
    }

    private int activeCompletionSnapshotSlot() {
        var read = completionSnapshotLock.readLock();
        read.lock();
        try {
            if (completionSnapshot == null) {
                return -1;
            }
            return completionSnapshotCompilerSlot;
        } finally {
            read.unlock();
        }
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
        cancelPendingDiagnostics("foreground");
        var selection = selectDiagnosticsCompiler();
        lint(files, selection.compiler, selection.slot, "foreground", -1);
    }

    private void lint(
            Collection<Path> files,
            JavaCompilerService diagnosticsCompiler,
            int diagnosticsCompilerSlot,
            String trigger,
            long expectedRevision) {
        if (files.isEmpty()) return;
        LOG.info("Lint " + files.size() + " files...");
        var started = Instant.now();
        CompileTask task = null;
        CompileTask indexTask = null;
        var retainTask = false;
        var retainIndexTask = false;
        try {
            task = diagnosticsCompiler.compile(files.toArray(Path[]::new));
            var compiled = Instant.now();
            LOG.info(
                    String.format(
                            "[perf] diagnostics_compile trigger=%s files=%d took=%dms",
                            trigger, files.size(), Duration.between(started, compiled).toMillis()));
            if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "post_compile")) {
                return;
            }
            verifyLombokSymbols(task, "diagnostics");
            var indexStarted = Instant.now();
            var indexMode = "diagnostics";
            var snapshotSelection = new DiagnosticsCompilerSelection(diagnosticsCompiler, diagnosticsCompilerSlot);
            if (requiresSanitizedSnapshot(files)) {
                snapshotSelection = sanitizedIndexCompilerSelection(diagnosticsCompilerSlot);
                if (activeCompletionSnapshotSlot() == snapshotSelection.slot) {
                    retireCompletionSnapshot("snapshot_slot_reuse:" + trigger);
                }
                indexTask = buildCompletionSnapshot(snapshotSelection.compiler, files);
                if (indexTask != null) {
                    indexMode = "sanitized_fast_ap";
                }
            }
            var snapshotTask = indexTask != null ? indexTask : task;
            if (snapshotTask != null) {
                installCompletionSnapshot(snapshotTask, trigger + ":" + indexMode, snapshotSelection.slot);
                if (snapshotTask == task) {
                    retainTask = true;
                } else {
                    retainIndexTask = true;
                }
            }
            var indexSource = snapshotTask != null ? snapshotTask : task;
            try {
                var nextIndex = TypeMemberIndex.from(indexSource);
                var snapshotVersion = nextIndexVersion(indexSource);
                installTypeMemberIndex(
                        nextIndex,
                        snapshotVersion,
                        trigger + ":" + indexMode,
                        Duration.between(indexStarted, Instant.now()));
            } catch (RuntimeException e) {
                LOG.warning(
                        String.format(
                                "[completion] type index rebuild failed trigger=%s reason=%s",
                                trigger, e.getMessage()));
                LOG.log(java.util.logging.Level.FINE, "", e);
            }
            var publishStarted = Instant.now();
            var diagnosticsCount = 0;
            var publishedUris = new HashSet<java.net.URI>();
            for (var errs : new ErrorProvider(task).errors()) {
                client.publishDiagnostics(errs);
                diagnosticsCount += errs.diagnostics.size();
                publishedUris.add(errs.uri);
            }
            // Always clear diagnostics for requested files that did not produce any root in this compile.
            for (var file : files) {
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
                            files.size(),
                            diagnosticsCount,
                            Duration.between(publishStarted, Instant.now()).toMillis()));
            if (shouldSkipStaleDiagnostics(expectedRevision, trigger, "post_publish")) {
                return;
            }
        } finally {
            if (indexTask != null && !retainIndexTask) {
                indexTask.close();
            }
            if (task != null && !retainTask) {
                task.close();
            }
        }
    }

    private DiagnosticsCompilerSelection sanitizedIndexCompilerSelection(int diagnosticsCompilerSlot) {
        compiler();
        var primary = cacheDiagnosticsCompilerPrimary;
        var secondary = cacheDiagnosticsCompilerSecondary;
        if (secondary == null) {
            return new DiagnosticsCompilerSelection(primary, 0);
        }
        if (diagnosticsCompilerSlot == 0) {
            return new DiagnosticsCompilerSelection(secondary, 1);
        }
        return new DiagnosticsCompilerSelection(primary, 0);
    }

    private void installTypeMemberIndex(
            TypeMemberIndex nextIndex, long snapshotVersion, String trigger, Duration took) {
        var rebuilt = nextIndex == null ? TypeMemberIndex.EMPTY : nextIndex;
        var snapshot = new IndexSnapshot(snapshotVersion, rebuilt);
        indexRef.set(snapshot);
        LOG.info(
                String.format(
                        "[perf] completion_type_index trigger=%s version=%d types=%d took=%dms",
                        trigger, snapshot.version, snapshot.size(), took.toMillis()));
    }

    private long nextIndexVersion(CompileTask task) {
        if (task == null) {
            return indexSnapshotVersion.incrementAndGet();
        }
        long highestObserved = -1;
        for (var stamp : task.sourceStamps.values()) {
            if (stamp == null || stamp.version < 0) continue;
            highestObserved = Math.max(highestObserved, stamp.version);
        }
        if (highestObserved >= 0) {
            return highestObserved;
        }
        return indexSnapshotVersion.incrementAndGet();
    }

    private void scheduleDiagnostics(Collection<Path> files, String trigger) {
        scheduleDiagnostics(files, trigger, DIAGNOSTIC_DEBOUNCE_MS);
    }

    private void scheduleDiagnostics(Collection<Path> files, String trigger, long delayMs) {
        if (files.isEmpty()) return;
        var revision = diagnosticsRevision.incrementAndGet();
        var snapshot = List.copyOf(files);
        synchronized (this) {
            if (pendingDiagnostics != null) {
                pendingDiagnostics.cancel(false);
            }
            pendingDiagnostics =
                    diagnosticsExecutor.schedule(
                            () -> runDiagnostics(snapshot, revision, trigger),
                            delayMs,
                            TimeUnit.MILLISECONDS);
        }
        LOG.info(
                String.format(
                        "[perf] diagnostics_debounce trigger=%s files=%d delay=%dms",
                        trigger, snapshot.size(), delayMs));
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
            lint(files, selection.compiler, selection.slot, "async:" + trigger, revision);
        } catch (Exception e) {
            LOG.warning("Async lint failed for " + files + ": " + e.getMessage());
            LOG.log(java.util.logging.Level.FINE, "", e);
        }
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

    private void retireCompletionSnapshot(String reason) {
        var write = completionSnapshotLock.writeLock();
        write.lock();
        try {
            if (completionSnapshot == null) return;
            completionSnapshot.close();
            completionSnapshot = null;
            completionSnapshotCompilerSlot = -1;
            completionSnapshotFileModified.clear();
            completionSnapshotFileVersion.clear();
        } finally {
            write.unlock();
        }
        LOG.info("[perf] completion_snapshot state=retired reason=" + reason);
    }

    private void installCompletionSnapshot(CompileTask task, String trigger, int compilerSlot) {
        var write = completionSnapshotLock.writeLock();
        write.lock();
        try {
            if (completionSnapshot != null) {
                completionSnapshot.close();
            }
            completionSnapshot = task;
            completionSnapshotCompilerSlot = compilerSlot;
            completionSnapshotFileModified.clear();
            completionSnapshotFileVersion.clear();
            for (var root : task.roots) {
                var uri = root.getSourceFile().toUri();
                if (!"file".equals(uri.getScheme())) continue;
                var path = Paths.get(uri);
                var sourceStamp = task.sourceStamps.get(path);
                if (sourceStamp != null) {
                    if (sourceStamp.modifiedMillis > 0) {
                        completionSnapshotFileModified.put(
                                path, Instant.ofEpochMilli(sourceStamp.modifiedMillis));
                    }
                    if (sourceStamp.version >= 0) {
                        completionSnapshotFileVersion.put(path, sourceStamp.version);
                    }
                }
                var source = root.getSourceFile();
                if (source instanceof SourceFileObject sourceFileObject) {
                    var snapshotModified = sourceFileObject.snapshotModified();
                    if (snapshotModified != null && !completionSnapshotFileModified.containsKey(path)) {
                        completionSnapshotFileModified.put(path, snapshotModified);
                    }
                    var snapshotVersion = sourceFileObject.snapshotVersion();
                    if (snapshotVersion >= 0 && !completionSnapshotFileVersion.containsKey(path)) {
                        completionSnapshotFileVersion.put(path, snapshotVersion);
                    }
                }
                var lastModified = source.getLastModified();
                if (!completionSnapshotFileModified.containsKey(path) && lastModified > 0) {
                    completionSnapshotFileModified.put(path, Instant.ofEpochMilli(lastModified));
                } else if (!completionSnapshotFileModified.containsKey(path)) {
                    var fileStoreModified = FileStore.modified(path);
                    if (fileStoreModified != null) {
                        completionSnapshotFileModified.put(path, fileStoreModified);
                    }
                }
            }
        } finally {
            write.unlock();
        }
        LOG.info(
                String.format(
                        "[perf] completion_snapshot state=updated trigger=%s roots=%d slot=%d",
                        trigger, task.roots.size(), compilerSlot));
    }

    private void clearCompletionSnapshot(String reason) {
        var write = completionSnapshotLock.writeLock();
        write.lock();
        try {
            if (completionSnapshot != null) {
                completionSnapshot.close();
                completionSnapshot = null;
            }
            completionSnapshotCompilerSlot = -1;
            completionSnapshotFileModified.clear();
            completionSnapshotFileVersion.clear();
        } finally {
            write.unlock();
        }
        LOG.info("[perf] completion_snapshot state=cleared reason=" + reason);
    }

    private boolean requiresSanitizedSnapshot(Collection<Path> files) {
        for (var file : files) {
            if (!FileStore.isJavaFile(file)) continue;
            var original = FileStore.contents(file);
            if (!sanitizeForCompletionSnapshot(original).equals(original)) {
                return true;
            }
        }
        return false;
    }

    private CompileTask buildCompletionSnapshot(JavaCompilerService diagnosticsCompiler, Collection<Path> files) {
        var sources = new ArrayList<javax.tools.JavaFileObject>();
        for (var file : files) {
            if (!FileStore.isJavaFile(file)) continue;
            var original = FileStore.contents(file);
            var sanitized = sanitizeForCompletionSnapshot(original);
            if (sanitized.equals(original)) {
                sources.add(new SourceFileObject(file));
            } else {
                var modified = FileStore.modified(file);
                if (modified == null) {
                    modified = Instant.now();
                }
                var version = FileStore.version(file);
                sources.add(new SourceFileObject(file, sanitized, modified, version));
            }
        }
        if (sources.isEmpty()) {
            return null;
        }
        var started = Instant.now();
        var snapshot = diagnosticsCompiler.compileFastWithProcessors(sources);
        LOG.info(
                String.format(
                        "[perf] completion_snapshot_compile mode=fast_ap files=%d took=%dms",
                        sources.size(), Duration.between(started, Instant.now()).toMillis()));
        return snapshot;
    }

    private String sanitizeForCompletionSnapshot(String contents) {
        var sanitized = TRAILING_DOT_BEFORE_EOL.matcher(contents).replaceAll(".hashCode()$1;$2");
        sanitized = TRAILING_DOT_AT_EOF.matcher(sanitized).replaceAll(".hashCode()$1;");
        return sanitized;
    }

    private Optional<CompletionSnapshotProvider.Snapshot> acquireCompletionSnapshot(Path file) {
        var read = completionSnapshotLock.readLock();
        read.lock();
        if (completionSnapshot == null) {
            read.unlock();
            return Optional.empty();
        }
        var staleVersion = false;
        var staleModified = false;
        var snapshotVersion = completionSnapshotFileVersion.get(file);
        var currentVersion = FileStore.version(file);
        if (snapshotVersion != null && currentVersion >= 0 && !snapshotVersion.equals(currentVersion)) {
            staleVersion = true;
            maybeScheduleStaleSnapshotRefresh(file);
            LOG.info(
                    String.format(
                            "[perf] completion_snapshot file=%s state=stale_version_served snapshot=%d current=%d drift=%d",
                            file.getFileName(),
                            snapshotVersion,
                            currentVersion,
                            Math.abs(currentVersion - snapshotVersion)));
        }
        var snapshotModified = completionSnapshotFileModified.get(file);
        var currentModified = FileStore.modified(file);
        if (snapshotModified != null
                && currentModified != null
                && !snapshotModified.equals(currentModified)
                && snapshotModified.toEpochMilli() != currentModified.toEpochMilli()) {
            staleModified = true;
            maybeScheduleStaleSnapshotRefresh(file);
            LOG.info(
                    String.format(
                            "[perf] completion_snapshot file=%s state=stale_modified_served snapshot_ms=%d current_ms=%d snapshot=%s current=%s",
                            file.getFileName(),
                            snapshotModified.toEpochMilli(),
                            currentModified.toEpochMilli(),
                            snapshotModified,
                            currentModified));
        }
        try {
            completionSnapshot.root(file);
        } catch (RuntimeException missingRoot) {
            read.unlock();
            return Optional.empty();
        }
        if (staleVersion || staleModified) {
            LOG.info(
                    String.format(
                            "[perf] completion_snapshot file=%s state=served_stale",
                            file.getFileName()));
        }
        var stale = staleVersion || staleModified;
        return Optional.of(
                new CompletionSnapshotProvider.Snapshot() {
                    private boolean closed;

                    @Override
                    public CompileTask task() {
                        return completionSnapshot;
                    }

                    @Override
                    public boolean stale() {
                        return stale;
                    }

                    @Override
                    public void close() {
                        if (closed) return;
                        closed = true;
                        read.unlock();
                    }
                });
    }

    private void maybeScheduleStaleSnapshotRefresh(Path file) {
        var now = System.nanoTime();
        var last = staleSnapshotRefreshNanos.getOrDefault(file, 0L);
        if (now - last < TimeUnit.MILLISECONDS.toNanos(STALE_SNAPSHOT_REFRESH_MS)) {
            return;
        }
        staleSnapshotRefreshNanos.put(file, now);
        scheduleDiagnostics(List.of(file), "staleSnapshot", STALE_SNAPSHOT_REFRESH_MS);
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
            if (annotationType.startsWith("lombok.")
                    || annotationType.startsWith("lombok")
                    || KNOWN_LOMBOK_ANNOTATIONS.contains(annotationType)) {
                return true;
            }
        }
        return false;
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
                    "AllArgsConstructor",
                    "NoArgsConstructor",
                    "RequiredArgsConstructor",
                    "ToString",
                    "EqualsAndHashCode",
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
                new JavaCompilerService(classPath, resolvedDocPath, addExports, extraArgs, lombokEnabled),
                lombokEnabled);
    }

    private static class CompilerSet {
        final JavaCompilerService interactive;
        final JavaCompilerService diagnosticsPrimary;
        final JavaCompilerService diagnosticsSecondary;
        final boolean lombokEnabled;

        CompilerSet(
                JavaCompilerService interactive,
                JavaCompilerService diagnosticsPrimary,
                JavaCompilerService diagnosticsSecondary,
                boolean lombokEnabled) {
            this.interactive = interactive;
            this.diagnosticsPrimary = diagnosticsPrimary;
            this.diagnosticsSecondary = diagnosticsSecondary;
            this.lombokEnabled = lombokEnabled;
        }
    }

    private static class DiagnosticsCompilerSelection {
        final JavaCompilerService compiler;
        final int slot;

        DiagnosticsCompilerSelection(JavaCompilerService compiler, int slot) {
            this.compiler = compiler;
            this.slot = slot;
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
        }
        diagnosticsExecutor.shutdownNow();
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
                switch (c.type) {
                    case FileChangeType.Created:
                        FileStore.externalCreate(file);
                        break;
                    case FileChangeType.Changed:
                        FileStore.externalChange(file);
                        break;
                    case FileChangeType.Deleted:
                        FileStore.externalDelete(file);
                        break;
                }
                if (!FileStore.activeDocuments().isEmpty()) {
                    scheduleDiagnostics(FileStore.activeDocuments(), "didChangeWatchedFiles");
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
        var snapshot = indexRef.get();
        if (snapshot.isEmpty()) {
            LOG.info(
                    String.format(
                            "[perf] completion_type_index state=bootstrap request=%s", file.getFileName()));
            scheduleDiagnostics(List.of(file), "completionBootstrap", 0);
        }
        var provider = new CompletionProvider(cacheCompiler, this::acquireCompletionSnapshot, snapshot);
        var list =
                provider.complete(
                        file,
                        params.position.line + 1,
                        params.position.character + 1,
                        FileStore.version(file));
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(list);
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
        var content = new HoverProvider(compiler()).hover(file, line, column);
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
        scheduleDiagnostics(List.of(Paths.get(params.textDocument.uri)), "didOpen");
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            scheduleDiagnostics(List.of(Paths.get(params.textDocument.uri)), "didChange");
        }
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);

        if (FileStore.isJavaFile(params.textDocument.uri)) {
            var file = Paths.get(params.textDocument.uri);
            staleSnapshotRefreshNanos.remove(file);
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
            // Re-lint all active documents
            scheduleDiagnostics(FileStore.activeDocuments(), "didSave", 0);
        }
    }

    @Override
    public void doAsyncWork() {}

    private static final Logger LOG = Logger.getLogger("main");
}
