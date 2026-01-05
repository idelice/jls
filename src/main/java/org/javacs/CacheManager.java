package org.javacs;

import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import org.javacs.index.WorkspaceIndex;

/**
 * Persists lightweight per-file metadata (hash, last-modified, tokens, package name) so we can skip
 * expensive indexing work for unchanged files between sessions.
 */
public class CacheManager {
    private final Map<Path, CacheEntry> entries = new ConcurrentHashMap<>();
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "jls-cache-writer"));
    private final Path cacheFile;
    private final Set<Path> workspaceRoots;
    private static final Logger LOG = Logger.getLogger("main");

    public CacheManager(Path cacheFile, Set<Path> workspaceRoots) {
        this.cacheFile = cacheFile;
        this.workspaceRoots = workspaceRoots;
    }

    public void load() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return;
        }
        try (var reader = Files.newBufferedReader(cacheFile)) {
            @SuppressWarnings("unchecked")
            List<CacheEntryDTO> list =
                    JsonHelper.GSON.fromJson(reader, new TypeToken<List<CacheEntryDTO>>() {}.getType());
            if (list == null) return;
            entries.clear();
            for (var dto : list) {
                if (dto == null || dto.path == null || dto.hash == null) continue;
                var path = Path.of(dto.path);
                if (!isUnderRoots(path)) continue;
                entries.put(
                        path,
                        new CacheEntry(
                                dto.hash,
                                dto.modifiedEpochMillis,
                                dto.tokens == null ? Collections.emptySet() : Set.copyOf(dto.tokens),
                                dto.packageName == null ? "" : dto.packageName));
            }
            pruneMissingAsync();
        } catch (IOException e) {
            LOG.warning("Failed to read cache file " + cacheFile + ": " + e.getMessage());
        }
    }

    public Optional<CacheEntry> validate(Path file, String currentHash, long modifiedEpochMillis) {
        var entry = entries.get(file);
        if (entry == null) return Optional.empty();
        if (entry.modifiedEpochMillis != modifiedEpochMillis) return Optional.empty();
        if (!entry.hash.equals(currentHash)) return Optional.empty();
        return Optional.of(entry);
    }

    public void invalidate(Path file) {
        entries.remove(file);
        saveAsync();
    }

    public void recordAsync(Path file, String contents, long modifiedEpochMillis) {
        writer.submit(() -> recordBlocking(file, contents, modifiedEpochMillis));
    }

    public void recordBlocking(Path file, String contents, long modifiedEpochMillis) {
        try {
            var hash = hash(contents.getBytes(StandardCharsets.UTF_8));
            var tokens = WorkspaceIndex.tokenizeContents(contents);
            var packageName = StringSearch.packageName(contents);
            entries.put(file, new CacheEntry(hash, modifiedEpochMillis, tokens, packageName));
            saveBlocking();
        } catch (RuntimeException e) {
            LOG.warning("Failed to update cache for " + file + ": " + e.getMessage());
        }
    }

    public void pruneMissingAsync() {
        writer.submit(this::pruneMissingBlocking);
    }

    private void pruneMissingBlocking() {
        try {
            entries.keySet().removeIf(f -> !Files.exists(f) || !isUnderRoots(f));
            saveBlocking();
        } catch (RuntimeException e) {
            LOG.warning("Failed to prune cache: " + e.getMessage());
        }
    }

    public void saveAsync() {
        writer.submit(this::saveBlocking);
    }

    private void saveBlocking() {
        if (cacheFile == null) return;
        try {
            Files.createDirectories(cacheFile.getParent());
        } catch (IOException ignored) {
        }
        List<CacheEntryDTO> list = new ArrayList<>(entries.size());
        for (var entry : entries.entrySet()) {
            var dto = new CacheEntryDTO();
            dto.path = entry.getKey().toString();
            dto.hash = entry.getValue().hash;
            dto.modifiedEpochMillis = entry.getValue().modifiedEpochMillis;
            dto.tokens = new ArrayList<>(entry.getValue().tokens);
            dto.packageName = entry.getValue().packageName;
            list.add(dto);
        }
        try (var writer = Files.newBufferedWriter(cacheFile)) {
            JsonHelper.GSON.toJson(list, writer);
        } catch (IOException e) {
            LOG.warning("Failed to write cache file " + cacheFile + ": " + e.getMessage());
        }
    }

    public static String hash(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean isUnderRoots(Path file) {
        if (workspaceRoots == null || workspaceRoots.isEmpty()) return true;
        for (var root : workspaceRoots) {
            if (file.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    public record CacheEntry(String hash, long modifiedEpochMillis, Set<String> tokens, String packageName) {}

    private static class CacheEntryDTO {
        String path;
        String hash;
        long modifiedEpochMillis;
        List<String> tokens;
        String packageName;
    }
}
