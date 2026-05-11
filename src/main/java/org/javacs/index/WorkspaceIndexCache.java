package org.javacs.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Logger;
import org.javacs.CacheAudit;

/**
 * On-disk cache for WorkspaceTypeIndex with SHA-256 content fingerprinting.
 *
 * <p>Cache location: ~/.cache/jls/server/{project-name}/{cache-key}.idx
 *
 * <p>Invalidation: any source file change, pom.xml change, JDK version change, or classpath change
 * invalidates the cache.
 */
public class WorkspaceIndexCache {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Path CACHE_ROOT =
            Paths.get(System.getProperty("user.home"), ".cache", "jls", "server");

    private WorkspaceIndexCache() {}

    /**
     * Compute cache key from workspace state.
     *
     * @param sourceFiles all workspace source files
     * @param pomXml root pom.xml
     * @param externalDependencies classpath configuration
     * @return SHA-256 hex string, or null if computation fails
     */
    public static String computeCacheKey(
            Collection<Path> sourceFiles,
            Path pomXml,
            Collection<String> externalDependencies) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var file : sourceFiles) {
                digest.update(Files.readAllBytes(file));
            }
            if (Files.exists(pomXml)) {
                digest.update(Files.readAllBytes(pomXml));
            }
            digest.update(System.getProperty("java.version").getBytes());
            var sortedDeps = new ArrayList<>(externalDependencies);
            Collections.sort(sortedDeps);
            for (var dep : sortedDeps) {
                digest.update(dep.getBytes());
            }
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            LOG.warning(
                    String.format(
                            "[perf] workspace_index_cache key_compute_failed: %s", e.getMessage()));
            return null;
        }
    }

    /**
     * Try to load cached WorkspaceTypeIndex.
     *
     * @param projectName unique project identifier
     * @param cacheKey expected cache key
     * @return loaded WorkspaceTypeIndex, or null if cache miss/corrupt
     */
    public static WorkspaceTypeIndex load(String projectName, String cacheKey) {
        if (cacheKey == null) return null;
        var cacheFile = cacheFile(projectName, cacheKey);
        if (!Files.exists(cacheFile)) {
            CacheAudit.miss("workspace_index_cache");
            return null;
        }
        try (var in =
                new ObjectInputStream(
                        new BufferedInputStream(Files.newInputStream(cacheFile)))) {
            var index = (WorkspaceTypeIndex) in.readObject();
            CacheAudit.hit("workspace_index_cache");
            CacheAudit.load("workspace_index_cache");
            LOG.info(
                    String.format(
                            "[perf] workspace_index_cache hit key=%s",
                            cacheKey.substring(0, Math.min(8, cacheKey.length()))));
            return index;
        } catch (Exception e) {
            CacheAudit.miss("workspace_index_cache");
            LOG.warning(
                    String.format(
                            "[perf] workspace_index_cache miss reason=corrupted key=%s error=%s",
                            cacheKey.substring(0, Math.min(8, cacheKey.length())),
                            e.getMessage()));
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    /**
     * Store WorkspaceTypeIndex to disk cache.
     *
     * @param projectName unique project identifier
     * @param cacheKey cache key for this workspace state
     * @param index the index to store
     */
    public static void store(String projectName, String cacheKey, WorkspaceTypeIndex index) {
        if (cacheKey == null || index == null) return;
        try {
            var cacheFile = cacheFile(projectName, cacheKey);
            try {
                Files.createDirectories(cacheFile.getParent());
            } catch (IOException e) {
                LOG.warning(
                        String.format(
                                "[perf] workspace_index_cache store_failed reason=%s",
                                e.getMessage()));
                return;
            }
            try (var out =
                    new ObjectOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(cacheFile)))) {
                out.writeObject(index);
            }
            CacheAudit.store("workspace_index_cache");
            LOG.info(
                    String.format(
                            "[perf] workspace_index_cache store key=%s size=%d",
                            cacheKey.substring(0, Math.min(8, cacheKey.length())),
                            Files.size(cacheFile)));
            cleanupOldEntries(projectName, cacheKey);
        } catch (Exception e) {
            LOG.warning(
                    String.format(
                            "[perf] workspace_index_cache store_failed reason=%s error=%s",
                            e.getClass().getSimpleName(), e.getMessage()));
        }
    }

    private static Path cacheFile(String projectName, String cacheKey) {
        return CACHE_ROOT.resolve(projectName).resolve(cacheKey + ".idx");
    }

    private static void cleanupOldEntries(String projectName, String currentKey) {
        try {
            var dir = CACHE_ROOT.resolve(projectName);
            if (!Files.exists(dir)) return;
            try (var entries = Files.list(dir)) {
                entries.filter(f -> !f.getFileName().toString().startsWith(currentKey))
                        .forEach(
                                f -> {
                                    try {
                                        Files.deleteIfExists(f);
                                    } catch (IOException ignored) {
                                    }
                                });
            }
        } catch (IOException ignored) {
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
