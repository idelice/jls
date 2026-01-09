package org.javacs;

import com.sun.source.tree.CompilationUnitTree;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.tools.JavaFileObject;

/**
 * Unified compilation cache that stores all compilation results (full, navigation, completion).
 * Cache is invalidated on document save to ensure consistency.
 *
 * Design principles:
 * - Single source of truth for all compilation caching
 * - Save-based invalidation (not per-keystroke)
 * - Separate scopes for different compilation modes
 * - Thread-safe for concurrent LSP requests
 */
public class UnifiedCompilationCache {

    private static final Logger LOG = Logger.getLogger("main");

    /**
     * Compilation scope determines which compiler and options to use.
     */
    public enum Scope {
        /** Full compilation with Lombok annotation processing for diagnostics */
        FULL_COMPILE,

        /** Navigation operations (hover, definition, references) - no Lombok expansion */
        NAVIGATION,

        /** Completion with -proc:none for speed */
        COMPLETION
    }

    /**
     * Cached compilation result with metadata
     */
    private static class CachedCompilation {
        final CompileBatch batch;
        final Set<Path> sourceFiles;
        final long workspaceVersion;
        final long timestamp;
        final Scope scope;

        CachedCompilation(CompileBatch batch, Set<Path> sourceFiles, long workspaceVersion, Scope scope) {
            this.batch = batch;
            this.sourceFiles = new HashSet<>(sourceFiles);
            this.workspaceVersion = workspaceVersion;
            this.timestamp = System.currentTimeMillis();
            this.scope = scope;
        }

        boolean isValid(Set<Path> requestedFiles, long currentWorkspaceVersion) {
            // Invalid if workspace changed (file saved)
            if (this.workspaceVersion != currentWorkspaceVersion) {
                return false;
            }
            // Invalid if different set of files
            if (!this.sourceFiles.equals(requestedFiles)) {
                return false;
            }
            return true;
        }
    }

    // Cache storage: one cache entry per scope
    private final Map<Scope, CachedCompilation> cache = new ConcurrentHashMap<>();

    // Parent service for creating compilations
    private final JavaCompilerService parent;

    // Cache statistics
    private final Map<Scope, Integer> hits = new ConcurrentHashMap<>();
    private final Map<Scope, Integer> misses = new ConcurrentHashMap<>();

    public UnifiedCompilationCache(JavaCompilerService parent) {
        this.parent = parent;
        for (Scope scope : Scope.values()) {
            hits.put(scope, 0);
            misses.put(scope, 0);
        }
    }

    /**
     * Get or create a compilation for the given scope and sources.
     * Thread-safe with per-scope locking.
     */
    public synchronized CompileBatch get(Scope scope, Collection<? extends JavaFileObject> sources) {
        var sourceFiles = extractSourcePaths(sources);
        var currentVersion = FileStore.workspaceVersion();

        var cached = cache.get(scope);
        if (cached != null && cached.isValid(sourceFiles, currentVersion)) {
            hits.merge(scope, 1, Integer::sum);
            LOG.fine(String.format("Cache HIT [%s]: files=%d version=%d",
                scope, sourceFiles.size(), currentVersion));
            return cached.batch;
        }

        // Cache miss - compile
        misses.merge(scope, 1, Integer::sum);
        LOG.fine(String.format("Cache MISS [%s]: files=%d version=%d reason=%s",
            scope, sourceFiles.size(), currentVersion,
            cached == null ? "not-cached" :
            cached.workspaceVersion != currentVersion ? "version-changed" : "files-changed"));

        // Invalidate old cache to release the compiler
        invalidate(scope);

        var batch = createCompilation(scope, sources);
        cache.put(scope, new CachedCompilation(batch, sourceFiles, currentVersion, scope));

        logCacheStats();
        return batch;
    }

    /**
     * Invalidate all caches (called on document save)
     */
    public synchronized void invalidateAll() {
        var count = cache.size();
        for (var entry : cache.values()) {
            try {
                entry.batch.close();
            } catch (Exception e) {
                LOG.warning("Failed to close cached compilation: " + e.getMessage());
            }
        }
        cache.clear();
        LOG.info(String.format("Cache invalidated: cleared %d entries", count));
        logCacheStats();
    }

    /**
     * Invalidate specific scope (partial invalidation)
     */
    public synchronized void invalidate(Scope scope) {
        var cached = cache.remove(scope);
        if (cached != null) {
            try {
                cached.batch.close();
            } catch (Exception e) {
                LOG.warning("Failed to close cached compilation: " + e.getMessage());
            }
            LOG.fine(String.format("Cache invalidated: %s", scope));
        }
    }

    /**
     * Invalidate caches for specific files
     */
    public synchronized void invalidateFiles(Set<Path> files) {
        var toRemove = new ArrayList<Scope>();
        for (var entry : cache.entrySet()) {
            if (!Collections.disjoint(entry.getValue().sourceFiles, files)) {
                toRemove.add(entry.getKey());
            }
        }
        for (var scope : toRemove) {
            invalidate(scope);
        }
    }

    /**
     * Get cache statistics for monitoring
     */
    public Map<String, Object> getStats() {
        var stats = new HashMap<String, Object>();
        for (var scope : Scope.values()) {
            var scopeStats = new HashMap<String, Object>();
            scopeStats.put("hits", hits.getOrDefault(scope, 0));
            scopeStats.put("misses", misses.getOrDefault(scope, 0));
            var total = hits.getOrDefault(scope, 0) + misses.getOrDefault(scope, 0);
            var hitRate = total > 0 ? (100.0 * hits.getOrDefault(scope, 0) / total) : 0.0;
            scopeStats.put("hitRate", String.format("%.1f%%", hitRate));
            scopeStats.put("cached", cache.containsKey(scope));
            stats.put(scope.name(), scopeStats);
        }
        return stats;
    }

    /**
     * Clear statistics
     */
    public void clearStats() {
        hits.clear();
        misses.clear();
        for (Scope scope : Scope.values()) {
            hits.put(scope, 0);
            misses.put(scope, 0);
        }
    }

    private void logCacheStats() {
        if (LOG.isLoggable(java.util.logging.Level.FINE)) {
            var stats = getStats();
            LOG.fine("Cache stats: " + stats);
        }
    }

    private Set<Path> extractSourcePaths(Collection<? extends JavaFileObject> sources) {
        var paths = new HashSet<Path>();
        for (var source : sources) {
            if (source instanceof SourceFileObject sfo) {
                paths.add(sfo.path);
            } else {
                // Try to extract path from URI
                try {
                    var uri = source.toUri();
                    if (uri.getScheme().equals("file")) {
                        paths.add(Path.of(uri));
                    }
                } catch (Exception e) {
                    // Ignore, can't extract path
                }
            }
        }
        return paths;
    }

    private CompileBatch createCompilation(Scope scope, Collection<? extends JavaFileObject> sources) {
        switch (scope) {
            case FULL_COMPILE:
                // Full compilation with Lombok for lint/save
                return parent.compileFullWithLombok(sources);

            case NAVIGATION:
                // Navigation without Lombok expansion for speed
                return parent.compileNavigation(sources);

            case COMPLETION:
                // Lightweight completion with proc:none
                return parent.compileCompletion(sources);

            default:
                throw new IllegalArgumentException("Unknown scope: " + scope);
        }
    }

    /**
     * Close all cached compilations (cleanup on shutdown)
     */
    public synchronized void close() {
        for (var cached : cache.values()) {
            try {
                cached.batch.close();
            } catch (Exception e) {
                LOG.warning("Failed to close cached compilation on shutdown: " + e.getMessage());
            }
        }
        cache.clear();
        LOG.info("Unified cache closed");
    }
}
