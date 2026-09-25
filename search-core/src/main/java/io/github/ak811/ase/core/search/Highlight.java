package io.github.ak811.ase.core.search;

/** A half-open character range {@code [start, end)} to emphasize in a {@link Snippet}. */
public final class Highlight {
    private final int start;
    private final int end;

    public Highlight(int start, int end) {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid range [" + start + ", " + end + ")");
        }
        this.start = start;
        this.end = end;
    }

    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Highlight && ((Highlight) o).start == start && ((Highlight) o).end == end;
    }

    @Override
    public int hashCode() {
        return 31 * start + end;
    }

    @Override
    public String toString() {
        return "[" + start + ", " + end + ")";
    }
}
