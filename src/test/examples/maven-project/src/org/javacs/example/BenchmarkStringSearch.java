package org.javacs.example;

import java.nio.ByteBuffer;

// Translated from https://golang.org/src/strings/search.go

// Search efficiently finds strings in a source text. It's implemented
// using the Boyer-Moore string search algorithm:
// https://en.wikipedia.org/wiki/Boyer-Moore_string_search_algorithm
// https://www.cs.utexas.edu/~moore/publications/fstrpos.pdf (note: this aged
// document uses 1-based indexing)
class BenchmarkStringSearch {
    // pattern is the string that we are searching for in the text.
    private final byte[] pattern;

    // badCharSkip[b] contains the distance between the last byte of pattern
    // and the rightmost occurrence of b in pattern. If b is not in pattern,
    // badCharSkip[b] is len(pattern).
    //
    // Whenever a mismatch is found with byte b in the text, we can safely
    // shift the matching frame at least badCharSkip[b] until the next time
    // the matching char could be in alignment.
    // TODO 256 is not coloring
    private final int[] badCharSkip = new int[256];

    // goodSuffixSkip[i] defines how far we can shift the matching frame given
    // that the suffix pattern[i+1:] matches, but the byte pattern[i] does
    // not. There are two cases to consider:
    //
    // 1. The matched suffix occurs elsewhere in pattern (with a different
    // byte preceding it that we might possibly match). In this case, we can
    // shift the matching frame to align with the next suffix chunk. For
    // example, the pattern "mississi" has the suffix "issi" next occurring
    // (in right-to-left order) at index 1, so goodSuffixSkip[3] ==
    // shift+len(suffix) == 3+4 == 7.
    //
    // 2. If the matched suffix does not occur elsewhere in pattern, then the
    // matching frame may share part of its prefix with the end of the
    // matching suffix. In this case, goodSuffixSkip[i] will contain how far
    // to shift the frame to align this portion of the prefix to the
    // suffix. For example, in the pattern "abcxxxabc", when the first
    // mismatch from the back is found to be in position 3, the matching
    // suffix "xxabc" is not found elsewhere in the pattern. However, its
    // rightmost "abc" (at position 6) is a prefix of the whole pattern, so
    // goodSuffixSkip[3] == shift+len(suffix) == 6+5 == 11.
    private final int[] goodSuffixSkip;

    BenchmarkStringSearch(String pattern) {
        this(pattern.getBytes());
    }

    BenchmarkStringSearch(byte[] pattern) {
        this.pattern = pattern;
        this.goodSuffixSkip = new int[pattern.length];

        // last is the index of the last character in the pattern.
        var last = pattern.length - 1;

        // Build bad character table.
        // Bytes not in the pattern can skip one pattern's length.
        for (var i = 0; i < badCharSkip.length; i++) {
            badCharSkip[i] = pattern.length;
        }
        // The loop condition is < instead of <= so that the last byte does not
        // have a zero distance to itself. Finding this byte out of place implies
        // that it is not in the last position.
        for (var i = 0; i < last; i++) {
            badCharSkip[pattern[i] + 128] = last - i;
        }

        // Build good suffix table.
        // First pass: set each value to the next index which starts a prefix of
        // pattern.
        var lastPrefix = last;
        for (var i = last; i >= 0; i--) {
            if (hasPrefix(pattern, new Slice(pattern, i + 1))) lastPrefix = i + 1;
            // lastPrefix is the shift, and (last-i) is len(suffix).
            goodSuffixSkip[i] = lastPrefix + last - i;
        }
        // Second pass: find repeats of pattern's suffix starting from the front.
        for (var i = 0; i < last; i++) {
            var lenSuffix = longestCommonSuffix(pattern, new Slice(pattern, 1, i + 1));
            if (pattern[i - lenSuffix] != pattern[last - lenSuffix]) {
                // (last-i) is the shift, and lenSuffix is len(suffix).
                goodSuffixSkip[last - lenSuffix] = lenSuffix + last - i;
            }
        }
    }

    int next(String text) {
        return next(text.getBytes());
    }

    int next(byte[] text) {
        return next(ByteBuffer.wrap(text));
    }

    int next(ByteBuffer text) {
        return next(text, 0);
    }

    int next(ByteBuffer text, int startingAfter) {
        var i = startingAfter + pattern.length - 1;
        while (i < text.limit()) {
            // Compare backwards from the end until the first unmatching character.
            var j = pattern.length - 1;
            while (j >= 0 && text.get(i) == pattern[j]) {
                i--;
                j--;
            }
            if (j < 0) {
                return i + 1; // match
            }
            i += Math.max(badCharSkip[text.get(i) + 128], goodSuffixSkip[j]);
        }
        return -1;
    }

    boolean isWordChar(byte b) {
        char c = (char) (b + 128);
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '$' || c == '_';
    }

    boolean startsWord(ByteBuffer text, int offset) {
        if (offset == 0) return true;
        return !isWordChar(text.get(offset - 1));
    }

    boolean endsWord(ByteBuffer text, int offset) {
        if (offset + 1 >= text.limit()) return true;
        return !isWordChar(text.get(offset + 1));
    }

    boolean isWord(ByteBuffer text, int offset) {
        return startsWord(text, offset) && endsWord(text, offset + pattern.length - 1);
    }

    int nextWord(String text) {
        return nextWord(text.getBytes());
    }

    int nextWord(byte[] text) {
        return nextWord(ByteBuffer.wrap(text));
    }

    int nextWord(ByteBuffer text) {
        var i = 0;
        while (true) {
            i = next(text, i);
            if (i == -1) return -1;
            if (isWord(text, i)) return i;
            i++;
        }
    }

    private boolean hasPrefix(byte[] s, Slice prefix) {
        for (int i = 0; i < prefix.length(); i++) {
            if (s[i] != prefix.get(i)) return false;
        }
        return true;
    }

    private int longestCommonSuffix(byte[] a, Slice b) {
        int i = 0;
        for (; i < a.length && i < b.length(); i++) {
            if (a[a.length - 1 - i] != b.get(b.length() - 1 - i)) {
                break;
            }
        }
        return i;
    }

    private static class Slice {
        private final byte[] target;
        private int from, until;

        int length() {
            return until - from;
        }

        byte get(int i) {
            return target[from + i];
        }

        public Slice(byte[] target, int from) {
            this(target, from, target.length);
        }

        public Slice(byte[] target, int from, int until) {
            this.target = target;
            this.from = from;
            this.until = until;
        }
    }
}
