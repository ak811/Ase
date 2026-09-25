package io.github.ak811.ase.core.analysis;

/**
 * One analyzed word.
 *
 * @param term     the index term (normalized and, if enabled, stemmed)
 * @param surface  the normalized but unstemmed form, used for spelling and autocomplete
 * @param start    inclusive start offset in the analyzed text
 * @param end      exclusive end offset in the analyzed text
 * @param position word position, used for phrase and proximity matching
 * @param group    the writing system of the word
 * @param stopword whether the word is a common function word
 */
public record Token(String term, String surface, int start, int end, int position,
                    ScriptGroup group, boolean stopword) {

    public Token {
        if (term == null || term.isEmpty() || surface == null || start < 0 || end < start || position < 0) {
            throw new IllegalArgumentException("invalid token " + term + " [" + start + ", " + end + ")");
        }
    }

    /** The same token with offsets moved by {@code delta}, for text analyzed as a substring. */
    public Token shift(int delta) {
        return delta == 0 ? this : new Token(term, surface, start + delta, end + delta, position, group, stopword);
    }

    /** The same token starting at {@code newStart} (used to keep overlapping highlights disjoint). */
    public Token shiftStart(int newStart) {
        return new Token(term, surface, newStart, end, position, group, stopword);
    }
}
