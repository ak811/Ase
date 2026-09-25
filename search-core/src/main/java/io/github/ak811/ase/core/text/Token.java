package io.github.ak811.ase.core.text;

/**
 * A normalized term together with the span of the original text it came from.
 * Offsets refer to the raw text, so they can be used for highlighting.
 */
public final class Token {
    private final String term;
    private final int start;
    private final int end;

    public Token(String term, int start, int end) {
        if (term == null || start < 0 || end < start) {
            throw new IllegalArgumentException("invalid token: " + term + " [" + start + ", " + end + ")");
        }
        this.term = term;
        this.start = start;
        this.end = end;
    }

    /** The normalized form used for indexing and lookup. */
    public String term() {
        return term;
    }

    /** Inclusive start offset in the original text. */
    public int start() {
        return start;
    }

    /** Exclusive end offset in the original text. */
    public int end() {
        return end;
    }

    @Override
    public String toString() {
        return term + "[" + start + "," + end + ")";
    }
}
