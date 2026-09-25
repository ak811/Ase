package io.github.ak811.ase.core.search;

/** A half-open UTF-16 range {@code [start, end)} of a {@link Snippet} that matched the query. */
public record Highlight(int start, int end) {
    public Highlight {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid range [" + start + ", " + end + ")");
        }
    }
}
