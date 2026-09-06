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
 * Owns the workspace declaration index: one background lane, one state.
 *
 * <p>The index covers the active module's source scope — its own sources plus the sources of its
 * dependency modules — because that is exactly what the module's compiler can resolve, and sibling
 * modules are only visible through their source. Index-based features ask {@link #ready()} and
 * report "not ready" rather than doing the work themselves; nothing else may cancel a build.
 */
final class CompletionIndexScheduler {
    private static final Logger LOG = Logger.getLogger("main");
    /** Coalesce keystrokes into one merge. */
    private static final long MERGE_DEBOUNCE_MS = 100;

    private enum State {
        EMPTY,
        BUILDING,
        READY
    }

    private final JavaLanguageServer server;

    // All fields below are guarded by synchronized (server).
    private State state = State.EMPTY;
    /** The module/source-set scope the index covers, or is being built for. */
    private String scope = "";
    /** Files edited since the last index update, waiting for a build to finish or a merge to run. */
    private final Set<Path> queued = new LinkedHashSet<>();
    private ScheduledFuture<?> pending;
    /**
     * Bumped whenever the index is dropped. The scope key alone cannot detect a reset that
     * re-establishes the same scope, which would let a build started against the previous compiler
     * publish an index resolved against a closed one.
     */
    private long epoch;

    CompletionIndexScheduler(JavaLanguageServer server) {
        this.server = server;
    }

    /** True once an index covering the active scope is installed. */
    boolean ready() {
        synchronized (server) {
            return state == State.READY;
        }
    }

    /**
     * Build the index for {@code file}'s scope unless it is already covered or being built. A build
     * for a different scope is superseded, because its result would answer for the wrong module.
     */
    void ensureIndexed(Path file) {
        var wanted = scopeKey(file);
        synchronized (server) {
            if (state == State.READY && wanted.equals(scope)) return;
            if (state == State.BUILDING) {
                if (wanted.equals(scope)) return;
                cancelPending();
            }
            state = State.BUILDING;
            scope = wanted;
            var startedEpoch = epoch;
            pending = server.completionIndexExecutor.schedule(
                    () -> build(wanted, startedEpoch), 0, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Record source changes. A deleted file is passed through as well: replacing its declarations
     * with nothing is what removes its types from the index.
     */
    void filesChanged(Collection<Path> files) {
        var javaFiles = JavaLanguageServer.filterJavaFiles(files);
        if (javaFiles.isEmpty()) return;
        synchronized (server) {
            // Without an index there is nothing to update; the next build covers these files.
            if (state == State.EMPTY) return;
            queued.addAll(javaFiles);
            if (state == State.BUILDING) return;
            if (pending != null && !pending.isDone()) return;
            pending = server.completionIndexExecutor.schedule(
                    this::merge, MERGE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Drop the index. The compiler that produced it, and its external index, are gone. */
    void reset(String reason) {
        synchronized (server) {
            LOG.info("[index] reset reason=" + reason + " previous_scope=" + scope);
            cancelPending();
            state = State.EMPTY;
            scope = "";
            queued.clear();
            epoch++;
        }
        server.publishCompletionSnapshot(WorkspaceTypeIndex.EMPTY, 0);
    }

    /** Wait briefly for an in-flight build, so a small project answers on the first request. */
    boolean awaitReady(long waitMs) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (!ready() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ready();
    }

    private void cancelPending() {
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    private String scopeKey(Path file) {
        var moduleFile = file != null ? file : server.activeModuleFile;
        if (moduleFile == null) moduleFile = server.workspaceRoot;
        if (moduleFile == null) return "";
        var target = moduleFile;
        return server.moduleGraph.moduleForFile(target)
                .map(module -> module.projectPath() + (module.isTestSource(target) ? "#test" : "#main"))
                .orElse("");
    }

    /** Sources the active module's compiler can resolve: its own plus its dependency modules'. */
    private List<Path> scopeSources(Path file) {
        var all = FileStore.all();
        var moduleFile = file != null ? file : server.activeModuleFile;
        if (moduleFile == null) moduleFile = server.workspaceRoot;
        var module = moduleFile == null
                ? Optional.<ModuleGraph.ModuleInfo>empty()
                : server.moduleGraph.moduleForFile(moduleFile);
        if (module.isEmpty()) return List.copyOf(all);
        var roots = server.moduleGraph.transitiveSourceDirs(
                module.get().projectPath(), module.get().isTestSource(moduleFile));
        return all.stream().filter(f -> roots.stream().anyMatch(f::startsWith)).toList();
    }

    private void build(String wanted, long startedEpoch) {
        List<Path> files;
        Path moduleFile;
        synchronized (server) {
            if (state != State.BUILDING || !wanted.equals(scope) || epoch != startedEpoch) return;
            moduleFile = server.activeModuleFile;
            files = scopeSources(moduleFile);
        }
        var started = Instant.now();
        var token = server.progress.begin("Index", "Indexing " + files.size() + " files");
        try {
            var compiler = moduleFile == null ? server.compiler : server.compilerFor(moduleFile);
            if (compiler == null) {
                LOG.warning("[index] build skipped scope=" + wanted + " reason=no_compiler");
                synchronized (server) {
                    if (wanted.equals(scope) && epoch == startedEpoch) state = State.EMPTY;
                }
                return;
            }
            // Parse lazily: a module closure holds thousands of files and materialising every
            // syntax tree at once exhausts the heap.
            Iterable<ParseTask> parsed = () -> files.stream().map(compiler::parse).iterator();
            var index = WorkspaceTypeIndex.fromParseTrees(
                    parsed,
                    (source, name) -> server.externalIndexForIndexing(source, compiler).containsType(name),
                    (source, declaration) -> server.canReferenceModule(declaration, source));
            List<Path> drained;
            synchronized (server) {
                if (!wanted.equals(scope) || epoch != startedEpoch) {
                    LOG.info("[index] build discarded scope=" + wanted + " current_scope=" + scope);
                    return;
                }
                server.publishCompletionSnapshot(index, server.completionIndexVersion.incrementAndGet());
                state = State.READY;
                drained = List.copyOf(queued);
                queued.clear();
                // This task is finishing, so the follow-up below is free to schedule.
                pending = null;
            }
            LOG.info(String.format("[index] built scope=%s files=%d types=%d took=%dms",
                    wanted, files.size(), index.size(),
                    Duration.between(started, Instant.now()).toMillis()));
            if (!drained.isEmpty()) filesChanged(drained);
        } catch (RuntimeException failure) {
            LOG.warning("[index] build failed scope=" + wanted + " cause=" + failure);
            synchronized (server) {
                if (wanted.equals(scope) && epoch == startedEpoch) state = State.EMPTY;
            }
        } finally {
            server.progress.end(token, "Index ready");
        }
    }

    private void merge() {
        List<Path> batch;
        Path moduleFile;
        long startedEpoch;
        synchronized (server) {
            if (state != State.READY) return;
            batch = List.copyOf(queued);
            queued.clear();
            moduleFile = server.activeModuleFile;
            startedEpoch = epoch;
        }
        if (batch.isEmpty()) return;
        var started = Instant.now();
        try {
            var compiler = moduleFile == null ? server.compiler : server.compilerFor(moduleFile);
            if (compiler == null) return;
            var parseTasks = new ArrayList<ParseTask>();
            var indexed = new ArrayList<Path>();
            for (var file : batch) {
                // A half-typed file would delete the declarations it still has; keep the old ones.
                var parsed = compiler.parse(file);
                if (parsed.hasSyntaxErrors()) continue;
                parseTasks.add(parsed);
                indexed.add(file);
            }
            if (parseTasks.isEmpty()) return;
            var base = server.completionSnapshotRef.get();
            var replaced = Set.copyOf(indexed);
            BiPredicate<Path, String> knownType = (source, name) ->
                    base.workspaceIndex()
                                    .typeInfo(name)
                                    .filter(type -> !replaced.contains(type.sourcePath))
                                    .filter(type -> server.canReferenceModule(type.sourcePath, source))
                                    .isPresent()
                            || server.externalIndexForIndexing(source, compiler).containsType(name);
            var delta = WorkspaceTypeIndex.fromParseTrees(
                    parseTasks, knownType,
                    (source, declaration) -> server.canReferenceModule(declaration, source));
            if (base.workspaceIndex().hasSameDeclarations(delta, indexed)) return;
            var merged = base.workspaceIndex().replaceWorkspaceDeclarations(delta, new LinkedHashSet<>(indexed));
            synchronized (server) {
                if (state != State.READY || epoch != startedEpoch) return;
                server.publishCompletionSnapshot(merged, server.completionIndexVersion.incrementAndGet());
            }
            server.refreshDiagnostics();
            LOG.fine(String.format("[index] merged files=%d types=%d took=%dms",
                    indexed.size(), merged.size(), Duration.between(started, Instant.now()).toMillis()));
        } catch (RuntimeException failure) {
            LOG.warning("[index] merge failed files=" + batch.size() + " cause=" + failure);
        } finally {
            // Edits that arrived while merging are still queued. This task owns `pending` and a
            // running future never reports isDone, so clear it before deciding to run again.
            synchronized (server) {
                pending = null;
                if (!queued.isEmpty() && state == State.READY) {
                    pending = server.completionIndexExecutor.schedule(
                            this::merge, MERGE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    void shutdown() {
        synchronized (server) {
            cancelPending();
        }
    }
}
