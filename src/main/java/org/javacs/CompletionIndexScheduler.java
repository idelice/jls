package org.javacs;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.logging.Logger;
import org.javacs.index.WorkspaceTypeIndex;

/**
 * Completion-index refresh/install logic is isolated so compiler recreation, active-document
 * merges, and full workspace bootstraps share one publication path.
 */
final class CompletionIndexScheduler {
    private static final Logger LOG = Logger.getLogger("main");

    private final JavaLanguageServer server;

    // Scheduler-internal mutable state — guarded by synchronized(server)
    private ScheduledFuture<?> pendingCompletionIndex;
    private JavaLanguageServer.CompletionIndexRefreshMode pendingCompletionIndexMode;
    private final Set<Path> pendingCompletionIndexFiles = new LinkedHashSet<>();
    private String pendingCompletionIndexTrigger;

    CompletionIndexScheduler(JavaLanguageServer server) {
        this.server = server;
    }

    /** Publish a rebuilt workspace index snapshot, replacing the previous workspace view. */
    void installTypeMemberIndex(
            WorkspaceTypeIndex nextIndex,
            long indexVersion,
            String trigger,
            Duration took,
            JavaLanguageServer.CompletionIndexScope scope) {
        var rebuilt = (nextIndex == null || nextIndex.size() == 0) ? WorkspaceTypeIndex.EMPTY : nextIndex;
        var currentSnapshot = server.completionSnapshotRef.get();
        server.publishCompletionSnapshot(
                rebuilt,
                currentSnapshot.externalIndex(),
                indexVersion,
                rebuilt == WorkspaceTypeIndex.EMPTY ? JavaLanguageServer.CompletionIndexScope.EMPTY : scope);
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
        var baseSnapshot = server.completionSnapshotRef.get();
        var merged =
                baseSnapshot
                        .workspaceIndex()
                        .replaceWorkspaceDeclarations(
                                deltaIndex == null ? WorkspaceTypeIndex.EMPTY : deltaIndex,
                                new LinkedHashSet<>(replacedFiles));
        var nextScope =
                merged == WorkspaceTypeIndex.EMPTY
                        ? JavaLanguageServer.CompletionIndexScope.EMPTY
                        : baseSnapshot.scope() == JavaLanguageServer.CompletionIndexScope.EMPTY
                                ? JavaLanguageServer.CompletionIndexScope.ACTIVE
                                : baseSnapshot.scope();
        server.publishCompletionSnapshot(merged, baseSnapshot.externalIndex(), indexVersion, nextScope);
        server.refreshDiagnostics();
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
        if (server.completionSnapshotRef.get().scope() != JavaLanguageServer.CompletionIndexScope.EMPTY) {
            return;
        }
        synchronized (server) {
            if (pendingCompletionIndex != null && !pendingCompletionIndex.isDone()) {
                return;
            }
        }
        var active = JavaLanguageServer.filterJavaFiles(FileStore.activeDocuments());
        if (active.isEmpty()) {
            return;
        }
        scheduleRefresh(active, trigger, 0, JavaLanguageServer.CompletionIndexRefreshMode.ACTIVE_DOCUMENT_BOOTSTRAP);
    }

    /** Queue a full workspace bootstrap unless the published scope is already workspace-wide. */
    void scheduleProjectBootstrapIfNeeded(String trigger) {
        if (server.completionSnapshotRef.get().scope() == JavaLanguageServer.CompletionIndexScope.WORKSPACE) {
            return;
        }
        synchronized (server) {
            // Only skip if a full rebuild is already pending — merges should be superseded
            if (pendingCompletionIndex != null && !pendingCompletionIndex.isDone()
                    && pendingCompletionIndexMode == JavaLanguageServer.CompletionIndexRefreshMode.FULL_REBUILD) {
                return;
            }
        }
        // For Gradle multi-module projects, scope the index to the active module's transitive
        // source files rather than all workspace files. This avoids parsing 8000+ files when
        // only the active module and its deps are needed for completion.
        var files = scopedSourceFiles();
        scheduleRefresh(files, trigger, 0, JavaLanguageServer.CompletionIndexRefreshMode.FULL_REBUILD);
    }

    /**
     * Return the set of source files to index for the current context.
     * FileStore is already scoped to the active module's source dirs for Gradle projects.
     * Cap at 1000 files to avoid OOM on very large modules.
     */
    private Collection<Path> scopedSourceFiles() {
        var all = FileStore.all();
        // For multi-module projects, workspace roots include transitive deps for resolution.
        // But indexing should only cover the active module to stay fast.
        if (server.moduleGraph != ModuleGraph.EMPTY) {
            var moduleFile = server.activeModuleFile == null ? server.workspaceRoot : server.activeModuleFile;
            var activeModule = server.moduleGraph.moduleForFile(moduleFile);
            if (activeModule.isPresent()) {
                var info = activeModule.get();
                var testSources = info.testSourceDir() != null && moduleFile.startsWith(info.testSourceDir());
                var activeSourceDirs = info.sourceDirs().stream()
                        .filter(dir -> testSources || !dir.equals(info.testSourceDir()))
                        .toList();
                var scoped = all.stream()
                        .filter(f -> activeSourceDirs.stream().anyMatch(f::startsWith))
                        .toList();
                LOG.info(String.format("[perf] index_scope active_module=%s active_files=%d workspace_files=%d",
                        activeModule.get().projectPath(), scoped.size(), all.size()));
                if (scoped.size() > JavaLanguageServer.LARGE_WORKSPACE_THRESHOLD) {
                    return List.of();
                }
                return scoped;
            }
        }
        if (all.size() > JavaLanguageServer.LARGE_WORKSPACE_THRESHOLD) {
            LOG.info(String.format("[perf] index_scope workspace_files=%d exceeds_threshold=%d — indexing active docs only",
                    all.size(), JavaLanguageServer.LARGE_WORKSPACE_THRESHOLD));
            return List.of();
        }
        LOG.info(String.format("[perf] index_scope workspace_files=%d", all.size()));
        return all;
    }

    /** Debounce completion-index refreshes and collapse newer schedules onto one pending task. */
    void scheduleRefresh(
            Collection<Path> files, String trigger, long delayMs, JavaLanguageServer.CompletionIndexRefreshMode mode) {
        var javaFiles = JavaLanguageServer.filterJavaFiles(files);
        if (javaFiles.isEmpty()) {
            return;
        }
        synchronized (server) {
            var pending = pendingCompletionIndex != null && !pendingCompletionIndex.isDone();
            if (mode == JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                pendingCompletionIndexFiles.addAll(javaFiles);
                if (pendingCompletionIndexTrigger == null
                        || "didChange".equals(trigger)) {
                    pendingCompletionIndexTrigger = trigger;
                }
                if (pending && pendingCompletionIndexMode != JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                    return;
                }
                javaFiles = List.copyOf(pendingCompletionIndexFiles);
                trigger = pendingCompletionIndexTrigger;
            } else if (pending
                    && mode == JavaLanguageServer.CompletionIndexRefreshMode.FULL_REBUILD
                    && pendingCompletionIndexMode != JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                return;
            }
            if (pending) {
                pendingCompletionIndex.cancel(false);
            }
            var revision = server.completionIndexRevision.incrementAndGet();
            var filesBatch = List.copyOf(javaFiles);
            var refreshTrigger = trigger;
            pendingCompletionIndexMode = mode;
            pendingCompletionIndex =
                    server.completionIndexExecutor.schedule(
                            () -> runRefresh(filesBatch, revision, refreshTrigger, mode),
                            delayMs,
                            TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Run one queued completion-index refresh if its revision is still current, then compile,
     * index, and publish the resulting workspace snapshot.
     */
    void runRefresh(List<Path> files, long revision, String trigger, JavaLanguageServer.CompletionIndexRefreshMode mode) {
        if (revision != server.completionIndexRevision.get()) {
            return;
        }
        synchronized (server.completionIndexCompileMutex) {
            var started = Instant.now();
            String bootstrapProgressToken = null;
            var completed = false;
            var indexedFiles = files;
            try {
                var progressLabel = switch (mode) {
                    case ACTIVE_DOCUMENT_BOOTSTRAP -> "Indexing open files";
                    case FULL_REBUILD -> "Indexing workspace";
                    case WORKSPACE_DECLARATION_MERGE -> "Updating index";
                };
                if (mode != JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                    bootstrapProgressToken = server.progress.begin("Index", progressLabel);
                }
                var compiler = server.activeModuleFile == null
                        ? server.compiler
                        : server.compilerFor(server.activeModuleFile);
                if (compiler == null) {
                    LOG.warning("[completion] index refresh skipped — compiler not yet initialized (trigger=" + trigger + ")");
                    return;
                }
                WorkspaceTypeIndex nextIndex;
                Instant indexStarted;
                if (mode == JavaLanguageServer.CompletionIndexRefreshMode.FULL_REBUILD) {
                    // Parse-only path: ~15x faster than compilation for large workspaces.
                    server.progress.report(bootstrapProgressToken,
                            "Parsing " + files.size() + " files");
                    var parseStarted = Instant.now();
                    var parseTasks = compiler.parseAll(files);
                    var parseTook = Duration.between(parseStarted, Instant.now()).toMillis();
                    LOG.info(String.format("[perf] index_parse files=%d took=%dms", files.size(), parseTook));
                    if (revision != server.completionIndexRevision.get()) {
                        LOG.fine(String.format(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_parse expected=%d current=%d",
                                trigger, revision, server.completionIndexRevision.get()));
                        server.progress.end(bootstrapProgressToken, null);
                        return;
                    }
                    indexStarted = Instant.now();
                    nextIndex = WorkspaceTypeIndex.fromParseTrees(
                            parseTasks,
                            (source, name) -> server.externalIndexForIndexing(source, compiler).containsType(name),
                            (source, declaration) -> server.canReferenceModule(declaration, source));
                    LOG.info(String.format("[perf] index_build files=%d types=%d took=%dms",
                            files.size(), nextIndex.size(),
                            Duration.between(indexStarted, Instant.now()).toMillis()));
                } else {
                    // WORKSPACE_DECLARATION_MERGE / ACTIVE_DOCUMENT_BOOTSTRAP:
                    // Use parse-only for index updates — compile (ATTR) hangs on large
                    // multi-module projects due to javac internal errors.
                    // Parse captures type declarations and member signatures accurately.
                    var parseTasks = compiler.parseAll(files);
                    if (mode == JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                        var validTasks = new ArrayList<ParseTask>();
                        var validFiles = new ArrayList<Path>();
                        for (int i = 0; i < parseTasks.size(); i++) {
                            if (parseTasks.get(i).hasSyntaxErrors()) {
                                LOG.fine(String.format(
                                        "[perf] completion_index_refresh_skip trigger=%s file=%s reason=syntax_errors",
                                        trigger, files.get(i).getFileName()));
                                continue;
                            }
                            validTasks.add(parseTasks.get(i));
                            validFiles.add(files.get(i));
                        }
                        if (validTasks.isEmpty()) {
                            completed = true;
                            return;
                        }
                        parseTasks = validTasks;
                        indexedFiles = List.copyOf(validFiles);
                    }
                    if (revision != server.completionIndexRevision.get()) {
                        LOG.fine(String.format(
                                "[perf] completion_index_refresh_skip trigger=%s phase=post_compile expected=%d current=%d",
                                trigger, revision, server.completionIndexRevision.get()));
                        server.progress.end(bootstrapProgressToken, null);
                        return;
                    }
                    indexStarted = Instant.now();
                    if (mode == JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                        var baseSnapshot = server.completionSnapshotRef.get();
                        var replacedFiles = Set.copyOf(indexedFiles);
                        BiPredicate<Path, String> knownType = (source, name) ->
                                baseSnapshot
                                                .workspaceIndex()
                                                .typeInfo(name)
                                                .filter(type -> !replacedFiles.contains(type.sourcePath))
                                                .filter(type -> server.canReferenceModule(type.sourcePath, source))
                                                .isPresent()
                                        || server.externalIndexForIndexing(source, compiler).containsType(name);
                        nextIndex = WorkspaceTypeIndex.fromParseTrees(
                                parseTasks,
                                knownType,
                                (source, declaration) -> server.canReferenceModule(declaration, source));
                    } else {
                        nextIndex = WorkspaceTypeIndex.fromParseTrees(
                                parseTasks,
                                (source, name) -> server.externalIndexForIndexing(source, compiler).containsType(name),
                                (source, declaration) -> server.canReferenceModule(declaration, source));
                    }
                }
                if (revision != server.completionIndexRevision.get()) {
                    LOG.fine(String.format(
                            "[perf] completion_index_refresh_skip trigger=%s phase=post_index expected=%d current=%d",
                            trigger,
                            revision,
                            server.completionIndexRevision.get()));
                    server.progress.end(bootstrapProgressToken, null);
                    return;
                }
                if (mode == JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE
                        && server.completionSnapshotRef
                                .get()
                                .workspaceIndex()
                                .hasSameDeclarations(nextIndex, indexedFiles)) {
                    LOG.fine(String.format(
                            "[perf] completion_index_refresh_skip trigger=%s reason=unchanged_declarations",
                            trigger));
                    completed = true;
                    return;
                }
                if (bootstrapProgressToken == null) {
                    bootstrapProgressToken = server.progress.begin("Index", progressLabel);
                }
                server.progress.report(
                        bootstrapProgressToken, "Indexed " + files.size() + " files");
                var indexVersion = server.completionIndexVersion.incrementAndGet();
                var installTook = Duration.between(indexStarted, Instant.now());
                if (mode == JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE) {
                    installMergedTypeMemberIndex(
                            nextIndex, indexedFiles, indexVersion, "index:" + trigger, installTook);
                } else {
                    var scope =
                            mode == JavaLanguageServer.CompletionIndexRefreshMode.FULL_REBUILD
                                    ? JavaLanguageServer.CompletionIndexScope.WORKSPACE
                                    : JavaLanguageServer.CompletionIndexScope.ACTIVE;
                    installTypeMemberIndex(
                            nextIndex, indexVersion, "index:" + trigger, installTook, scope);
                    if (scope == JavaLanguageServer.CompletionIndexScope.ACTIVE) {
                        server.completionIndexExecutor.schedule(
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
                server.progress.end(bootstrapProgressToken, "Index ready");
                var totalMs = Duration.between(started, Instant.now()).toMillis();
                LOG.fine(String.format(
                        "[perf] completion_index_refresh trigger=%s files=%d version=%d mode=%s compile=%dms total=%dms",
                        trigger,
                        files.size(),
                        indexVersion,
                        mode.name().toLowerCase(),
                        Duration.between(started, indexStarted).toMillis(),
                        totalMs));
                completed = true;
            } catch (RuntimeException e) {
                server.progress.end(bootstrapProgressToken, "Index failed");
                LOG.warning(
                        String.format(
                                "[completion] index refresh failed trigger=%s files=%d reason=%s",
                                trigger,
                                files.size(),
                                e.getMessage()));
                LOG.fine(e.toString());
            } finally {
                var schedulePendingMerge = false;
                synchronized (server) {
                    if (revision == server.completionIndexRevision.get()) {
                        if (mode == JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE
                                && completed) {
                            pendingCompletionIndexFiles.removeAll(files);
                            if (pendingCompletionIndexFiles.isEmpty()) {
                                pendingCompletionIndexTrigger = null;
                            }
                        }
                        pendingCompletionIndex = null;
                        pendingCompletionIndexMode = null;
                        schedulePendingMerge = mode != JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE
                                && !pendingCompletionIndexFiles.isEmpty();
                        if (schedulePendingMerge) {
                            scheduleRefresh(
                                    List.copyOf(pendingCompletionIndexFiles),
                                    pendingCompletionIndexTrigger,
                                    0,
                                    JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
                        }
                    }
                }
            }
        }
    }

    /** Cancel any queued completion-index refresh and bump the revision so stale work is dropped. */
    void cancel(String reason) {
        synchronized (server) {
            if (pendingCompletionIndex == null && pendingCompletionIndexFiles.isEmpty()) {
                return;
            }
            server.completionIndexRevision.incrementAndGet();
            if (pendingCompletionIndex != null) {
                pendingCompletionIndex.cancel(false);
            }
            pendingCompletionIndex = null;
            pendingCompletionIndexMode = null;
            pendingCompletionIndexFiles.clear();
            pendingCompletionIndexTrigger = null;
        }
        LOG.fine(String.format("[perf] completion_index_cancel reason=%s", reason));
    }

    /** Shut down the scheduler, cancelling any pending work. */
    void shutdown() {
        synchronized (server) {
            if (pendingCompletionIndex != null) {
                pendingCompletionIndex.cancel(false);
                pendingCompletionIndex = null;
            }
            pendingCompletionIndexMode = null;
            pendingCompletionIndexFiles.clear();
            pendingCompletionIndexTrigger = null;
        }
    }
}
