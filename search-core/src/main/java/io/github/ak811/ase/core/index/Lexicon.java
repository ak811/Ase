package io.github.ak811.ase.core.index;

/**
 * The distinct words of the corpus (normalized, unstemmed), with the number of documents
 * containing each. Used for spelling correction and autocomplete. Sorted, immutable.
 */
public final class Lexicon {
    private final String[] words;
    private final String[] displays;
    private final int[] documentFrequencies;

    Lexicon(String[] words, String[] displays, int[] documentFrequencies) {
        this.words = words;
        this.displays = displays;
        this.documentFrequencies = documentFrequencies;
    }

    public int size() {
        return words.length;
    }

    /** Normalized form, e.g. {@code cafe}. */
    public String word(int index) {
        return words[index];
    }

    /** Form shown to users, e.g. {@code café}. */
    public String display(int index) {
        return displays[index] == null ? words[index] : displays[index];
    }

    public int documentFrequency(int index) {
        return documentFrequencies[index];
    }

    public int indexOf(String word) {
        int index = java.util.Arrays.binarySearch(words, word);
        return index >= 0 ? index : -1;
    }

    /** First index whose word starts with {@code prefix}; see {@link #prefixEnd}. */
    public int prefixStart(String prefix) {
        return lowerBound(prefix);
    }

    /** One past the last index whose word starts with {@code prefix}. */
    public int prefixEnd(String prefix) {
        if (prefix.isEmpty()) {
            return words.length;
        }
        char last = prefix.charAt(prefix.length() - 1);
        if (last == Character.MAX_VALUE) {
            int i = lowerBound(prefix);
            while (i < words.length && words[i].startsWith(prefix)) {
                i++;
            }
            return i;
        }
        return lowerBound(prefix.substring(0, prefix.length() - 1) + (char) (last + 1));
    }

    private int lowerBound(String key) {
        int low = 0;
        int high = words.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (words[mid].compareTo(key) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
