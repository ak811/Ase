package io.github.ak811.ase.core.search;

import java.util.List;

/** Display text plus the ranges within it that matched the query (ascending, non-overlapping). */
public record Snippet(String text, List<Highlight> highlights) {
    public Snippet {
        if (text == null) {
            throw new IllegalArgumentException("text is null");
        }
        highlights = List.copyOf(highlights);
        for (Highlight highlight : highlights) {
            if (highlight.end() > text.length()) {
                throw new IllegalArgumentException("highlight " + highlight + " exceeds the text");
            }
        }
    }

    public static Snippet plain(String text) {
        return new Snippet(text, List.of());
    }
}
