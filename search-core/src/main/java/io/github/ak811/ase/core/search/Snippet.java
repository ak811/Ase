package io.github.ak811.ase.core.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Display text plus the ranges within it that matched the query. */
public final class Snippet {
    private final String text;
    private final List<Highlight> highlights;

    public Snippet(String text, List<Highlight> highlights) {
        this.text = Objects.requireNonNull(text, "text");
        for (Highlight highlight : highlights) {
            if (highlight.end() > text.length()) {
                throw new IllegalArgumentException("highlight " + highlight + " exceeds text length " + text.length());
            }
        }
        this.highlights = Collections.unmodifiableList(new ArrayList<>(highlights));
    }

    public String text() {
        return text;
    }

    /** Non-overlapping ranges in ascending order. */
    public List<Highlight> highlights() {
        return highlights;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Snippet)) {
            return false;
        }
        Snippet other = (Snippet) o;
        return text.equals(other.text) && highlights.equals(other.highlights);
    }

    @Override
    public int hashCode() {
        return 31 * text.hashCode() + highlights.hashCode();
    }

    @Override
    public String toString() {
        return text + " " + highlights;
    }
}
