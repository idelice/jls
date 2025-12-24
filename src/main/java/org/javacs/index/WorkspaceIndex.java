package org.javacs.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.reflect.TypeToken;
import org.javacs.FileStore;
import org.javacs.JsonHelper;

public final class WorkspaceIndex {
    private WorkspaceIndex() {}

    private static final Map<String, Set<Path>> tokenToFiles = new HashMap<>();
    private static final Map<Path, Set<String>> fileToTokens = new HashMap<>();
    private static final Map<Path, Long> fileToModified = new HashMap<>();
    private static Path cacheFile = null;

    public static synchronized void clear() {
        tokenToFiles.clear();
        fileToTokens.clear();
        fileToModified.clear();
    }

    public static synchronized void removeFile(Path file) {
        var prior = fileToTokens.remove(file);
        fileToModified.remove(file);
        if (prior == null) return;
        for (var token : prior) {
            var files = tokenToFiles.get(token);
            if (files == null) continue;
            files.remove(file);
            if (files.isEmpty()) {
                tokenToFiles.remove(token);
            }
        }
    }

    public static synchronized void updateFile(Path file) {
        if (!FileStore.isJavaFile(file)) {
            removeFile(file);
            return;
        }
        var modified = FileStore.modified(file);
        if (modified == null) {
            removeFile(file);
            return;
        }
        var modifiedMillis = modified.toEpochMilli();
        var priorModified = fileToModified.get(file);
        var priorTokens = fileToTokens.get(file);
        if (priorModified != null && priorModified == modifiedMillis && priorTokens != null) {
            return;
        }
        var started = System.nanoTime();
        var tokens = tokenize(FileStore.contents(file));
        var prior = fileToTokens.put(file, tokens);
        fileToModified.put(file, modifiedMillis);
        if (prior == null) {
            for (var token : tokens) {
                tokenToFiles.computeIfAbsent(token, k -> new HashSet<>()).add(file);
            }
            LOG.fine(
                    String.format(
                            "Index update (new file): %s tokens=%d in %d ms",
                            file.getFileName(),
                            tokens.size(),
                            (System.nanoTime() - started) / 1_000_000));
            return;
        }
        for (var oldToken : prior) {
            if (tokens.contains(oldToken)) continue;
            var files = tokenToFiles.get(oldToken);
            if (files == null) continue;
            files.remove(file);
            if (files.isEmpty()) {
                tokenToFiles.remove(oldToken);
            }
        }
        for (var token : tokens) {
            if (prior.contains(token)) continue;
            tokenToFiles.computeIfAbsent(token, k -> new HashSet<>()).add(file);
        }
        LOG.fine(
                String.format(
                        "Index update (changed file): %s tokens=%d in %d ms",
                        file.getFileName(),
                        tokens.size(),
                        (System.nanoTime() - started) / 1_000_000));
    }

    public static synchronized void updateFileTokens(Path file, Set<String> tokens, long modifiedEpochMillis) {
        var prior = fileToTokens.put(file, tokens);
        fileToModified.put(file, modifiedEpochMillis);
        if (prior == null) {
            for (var token : tokens) {
                tokenToFiles.computeIfAbsent(token, k -> new HashSet<>()).add(file);
            }
            return;
        }
        for (var oldToken : prior) {
            if (tokens.contains(oldToken)) continue;
            var files = tokenToFiles.get(oldToken);
            if (files == null) continue;
            files.remove(file);
            if (files.isEmpty()) {
                tokenToFiles.remove(oldToken);
            }
        }
        for (var token : tokens) {
            if (prior.contains(token)) continue;
            tokenToFiles.computeIfAbsent(token, k -> new HashSet<>()).add(file);
        }
    }

    public static synchronized Set<Path> filesContaining(String token) {
        var started = System.nanoTime();
        var files = tokenToFiles.get(token);
        if (files == null) return Set.of();
        var result = new HashSet<>(files);
        LOG.fine(
                String.format(
                        "Index lookup token=%s files=%d in %d ms",
                        token,
                        result.size(),
                        (System.nanoTime() - started) / 1_000_000));
        return result;
    }

    public static synchronized Set<Path> filesContainingAny(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return Set.of();
        var started = System.nanoTime();
        var result = new HashSet<Path>();
        for (var token : tokens) {
            if (token == null || token.isEmpty()) continue;
            var files = tokenToFiles.get(token);
            if (files != null) {
                result.addAll(files);
            }
        }
        LOG.fine(
                String.format(
                        "Index lookup tokens=%d files=%d in %d ms",
                        tokens.size(),
                        result.size(),
                        (System.nanoTime() - started) / 1_000_000));
        return result;
    }

    public static synchronized boolean isCached(Path file, long modifiedEpochMillis) {
        var priorModified = fileToModified.get(file);
        return priorModified != null && priorModified == modifiedEpochMillis && fileToTokens.containsKey(file);
    }

    public static synchronized void setCacheFile(Path file) {
        cacheFile = file;
    }

    public static synchronized int loadCache(Set<Path> workspaceRoots) {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            return 0;
        }
        try (var reader = Files.newBufferedReader(cacheFile)) {
            @SuppressWarnings("unchecked")
            List<IndexCacheEntry> list =
                    JsonHelper.GSON.fromJson(reader, new TypeToken<List<IndexCacheEntry>>() {}.getType());
            if (list == null) return 0;
            clear();
            int loaded = 0;
            for (var entry : list) {
                if (entry == null || entry.path == null || entry.tokens == null) continue;
                var path = Path.of(entry.path);
                if (!FileStore.isJavaFile(path)) continue;
                if (!isUnderRoots(path, workspaceRoots)) continue;
                try {
                    if (!Files.isRegularFile(path)) continue;
                    var modified = Files.getLastModifiedTime(path).toInstant().toEpochMilli();
                    if (modified != entry.modifiedEpochMillis) continue;
                    updateFileTokens(path, new HashSet<>(entry.tokens), entry.modifiedEpochMillis);
                    loaded++;
                } catch (IOException e) {
                    // ignore invalid entries
                }
            }
            return loaded;
        } catch (IOException e) {
            LOG.warning("Failed to read workspace index cache: " + e.getMessage());
            return 0;
        }
    }

    public static synchronized void saveCache() {
        if (cacheFile == null) return;
        List<IndexCacheEntry> list = new ArrayList<>(fileToTokens.size());
        for (var entry : fileToTokens.entrySet()) {
            var file = entry.getKey();
            var modified = fileToModified.get(file);
            if (modified == null) continue;
            list.add(new IndexCacheEntry(file.toString(), modified, new ArrayList<>(entry.getValue())));
        }
        try (var writer = Files.newBufferedWriter(cacheFile)) {
            JsonHelper.GSON.toJson(list, writer);
        } catch (IOException e) {
            LOG.warning("Failed to write workspace index cache: " + e.getMessage());
        }
    }

    public static Set<String> tokenizeContents(String contents) {
        return tokenize(contents);
    }

    private static Set<String> tokenize(String contents) {
        var tokens = new HashSet<String>();
        int i = 0;
        while (i < contents.length()) {
            char c = contents.charAt(i);
            if (!Character.isJavaIdentifierStart(c)) {
                i++;
                continue;
            }
            int start = i;
            i++;
            while (i < contents.length() && Character.isJavaIdentifierPart(contents.charAt(i))) {
                i++;
            }
            if (i > start) {
                tokens.add(contents.substring(start, i));
            }
        }
        return tokens;
    }

    private static boolean isUnderRoots(Path path, Set<Path> roots) {
        if (roots == null || roots.isEmpty()) return true;
        for (var root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static class IndexCacheEntry {
        final String path;
        final long modifiedEpochMillis;
        final List<String> tokens;

        IndexCacheEntry(String path, long modifiedEpochMillis, List<String> tokens) {
            this.path = path;
            this.modifiedEpochMillis = modifiedEpochMillis;
            this.tokens = tokens;
        }
    }

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");
}
