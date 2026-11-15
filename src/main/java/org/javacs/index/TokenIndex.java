package org.javacs.index;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.javacs.FileStore;

/**
 * Approximate prefilter for find-references: token text to the files that may mention it. A hit only
 * means "worth compiling"; the compiler still decides whether an exact element match exists. Files
 * outside the index are always candidates. Updates are independent of the declaration snapshot
 * because body edits change tokens without changing declarations.
 *
 * <p>{@link #replaceFiles} and {@link #replaceWith} consume an unpublished instance; they must not
 * be called with the live index.
 */
public class TokenIndex {
    private static final int[] NONE = new int[0];

    private Object2IntOpenHashMap<String> tokenIds = new Object2IntOpenHashMap<>();
    private ArrayList<String> tokensById = new ArrayList<>();

    private Object2IntOpenHashMap<Path> fileIds = new Object2IntOpenHashMap<>();
    private ArrayList<Path> filesById = new ArrayList<>();

    /** Token id -> file ids. */
    private Int2ObjectOpenHashMap<IntArrayList> filesByToken = new Int2ObjectOpenHashMap<>();
    /** File id -> token ids. */
    private Int2ObjectOpenHashMap<int[]> tokensByFile = new Int2ObjectOpenHashMap<>();

    private List<Path> unscanned = List.of();
    private long unscannedRevision = -1;

    {
        tokenIds.defaultReturnValue(-1);
        fileIds.defaultReturnValue(-1);
    }

    public synchronized void replaceFile(Path file, Set<String> tokens) {
        int fileId = fileIds.getOrDefault(file, -1);
        if (fileId < 0) {
            fileId = filesById.size();
            fileIds.put(file, fileId);
            filesById.add(file);
        } else {
            removePostings(fileId);
        }
        if (tokens.isEmpty()) {
            tokensByFile.put(fileId, NONE);
            invalidate();
            return;
        }
        var ids = new int[tokens.size()];
        var next = 0;
        for (var token : tokens) ids[next++] = intern(token);
        tokensByFile.put(fileId, ids);
        for (var tokenId : ids) filesByToken.computeIfAbsent(tokenId, __ -> new IntArrayList()).add(fileId);
        invalidate();
    }

    public synchronized void removeFile(Path file) {
        int fileId = fileIds.removeInt(file);
        if (fileId < 0) return;
        removePostings(fileId);
        tokensByFile.remove(fileId);
        filesById.set(fileId, null);
        invalidate();
    }

    public synchronized void clear() {
        tokenIds = new Object2IntOpenHashMap<>();
        tokenIds.defaultReturnValue(-1);
        tokensById = new ArrayList<>();
        fileIds = new Object2IntOpenHashMap<>();
        fileIds.defaultReturnValue(-1);
        filesById = new ArrayList<>();
        filesByToken = new Int2ObjectOpenHashMap<>();
        tokensByFile = new Int2ObjectOpenHashMap<>();
        invalidate();
    }

    // Files the index never scanned are candidates too: it may not cover every workspace file.
    public synchronized List<Path> filesContainingToken(String token) {
        var candidates = new ArrayList<Path>();
        int tokenId = tokenIds.getOrDefault(token, -1);
        if (tokenId >= 0) {
            var postings = filesByToken.get(tokenId);
            if (postings != null) {
                for (var i = 0; i < postings.size(); i++) {
                    var file = filesById.get(postings.getInt(i));
                    if (file != null) candidates.add(file);
                }
            }
        }
        candidates.addAll(unscannedFiles());
        return candidates;
    }

    public synchronized void replaceFiles(TokenIndex source, Collection<Path> files) {
        for (var file : files) {
            int sourceId = source.fileIds.getOrDefault(file, -1);
            var ids = sourceId < 0 ? null : source.tokensByFile.get(sourceId);
            if (ids == null) continue;
            var tokens = new ArrayList<String>(ids.length);
            for (var tokenId : ids) tokens.add(source.tokensById.get(tokenId));
            replaceFile(file, Set.copyOf(tokens));
        }
    }

    public synchronized void replaceWith(TokenIndex other) {
        tokenIds = other.tokenIds;
        tokensById = other.tokensById;
        fileIds = other.fileIds;
        filesById = other.filesById;
        filesByToken = other.filesByToken;
        tokensByFile = other.tokensByFile;
        invalidate();
        other.clear();
    }

    private void invalidate() {
        unscanned = List.of();
        unscannedRevision = -1;
    }

    private int intern(String token) {
        int id = tokenIds.getOrDefault(token, -1);
        if (id < 0) {
            id = tokensById.size();
            tokenIds.put(token, id);
            tokensById.add(token);
        }
        return id;
    }

    private void removePostings(int fileId) {
        var tokens = tokensByFile.get(fileId);
        if (tokens == null) return;
        for (var tokenId : tokens) {
            var postings = filesByToken.get(tokenId);
            if (postings == null) continue;
            postings.rem(fileId);
            if (postings.isEmpty()) filesByToken.remove(tokenId);
        }
    }

    private List<Path> unscannedFiles() {
        long revision = FileStore.contentRevision();
        if (unscannedRevision == revision) return unscanned;
        var files = new ArrayList<Path>();
        for (var file : FileStore.all()) {
            if (!fileIds.containsKey(file)) files.add(file);
        }
        unscanned = files;
        unscannedRevision = revision;
        return unscanned;
    }
}
