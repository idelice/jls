package org.javacs.index;

import java.nio.file.Path;
import java.util.*;

/**
 * Inverted index: identifier name → set of files containing that identifier.
 * Built during workspace parse and updated incrementally on file changes.
 * Used for fast "is this name referenced anywhere?" checks.
 */
public class WordIndex {

    public static final WordIndex EMPTY = new WordIndex(Map.of(), Map.of());

    // word → files containing it
    private final Map<String, Set<Path>> index;
    // file → words in that file (for incremental removal)
    private final Map<Path, Set<String>> fileWords;

    private WordIndex(Map<String, Set<Path>> index, Map<Path, Set<String>> fileWords) {
        this.index = index;
        this.fileWords = fileWords;
    }

    /** Check if any file other than declaringFile contains this word. */
    public boolean hasReference(String word, Path declaringFile) {
        var files = index.get(word);
        if (files == null || files.isEmpty()) return false;
        if (files.size() > 1) return true;
        return !files.contains(declaringFile);
    }

    /** Check which names from the set have references in files other than declaringFile. */
    public Set<String> referencedNames(Set<String> names, Path declaringFile) {
        var found = new HashSet<String>();
        for (var name : names) {
            if (hasReference(name, declaringFile)) {
                found.add(name);
            }
        }
        return found;
    }

    public int size() {
        return index.size();
    }

    /** Builder for constructing a WordIndex during the parse pass. */
    public static class Builder {
        private final Map<String, Set<Path>> index = new HashMap<>();
        private final Map<Path, Set<String>> fileWords = new HashMap<>();

        public void addWords(Path file, Set<String> words) {
            fileWords.put(file, words);
            for (var word : words) {
                index.computeIfAbsent(word, k -> new HashSet<>()).add(file);
            }
        }

        public WordIndex build() {
            return new WordIndex(index, fileWords);
        }
    }

    /**
     * Create a new WordIndex with updated entries for the given files.
     * Removes old entries for those files and adds new ones.
     */
    public WordIndex replaceFiles(Map<Path, Set<String>> updatedFiles) {
        var newIndex = new HashMap<>(this.index);
        var newFileWords = new HashMap<>(this.fileWords);

        // Remove old entries for updated files
        for (var file : updatedFiles.keySet()) {
            var oldWords = newFileWords.remove(file);
            if (oldWords != null) {
                for (var word : oldWords) {
                    var files = newIndex.get(word);
                    if (files != null) {
                        files.remove(file);
                        if (files.isEmpty()) newIndex.remove(word);
                    }
                }
            }
        }

        // Add new entries
        for (var entry : updatedFiles.entrySet()) {
            var file = entry.getKey();
            var words = entry.getValue();
            newFileWords.put(file, words);
            for (var word : words) {
                newIndex.computeIfAbsent(word, k -> new HashSet<>()).add(file);
            }
        }

        return new WordIndex(newIndex, newFileWords);
    }
}
