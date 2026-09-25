package io.github.ak811.ase.core.search;

/**
 * Paging and behaviour of one search.
 *
 * @param offset      index of the first hit to return
 * @param limit       maximum number of hits to return (1–100)
 * @param autoCorrect replace words missing from the corpus with spelling suggestions
 */
public record SearchOptions(int offset, int limit, boolean autoCorrect) {
    public static final int MAX_LIMIT = 100;
    /** Deepest hit that can be requested; deep paging is expensive and rarely useful. */
    public static final int MAX_WINDOW = 10_000;

    public SearchOptions {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if ((long) offset + limit > MAX_WINDOW) {
            throw new IllegalArgumentException("offset + limit must not exceed " + MAX_WINDOW);
        }
    }

    public static SearchOptions defaults() {
        return new SearchOptions(0, 10, true);
    }

    public SearchOptions withOffset(int value) {
        return new SearchOptions(value, limit, autoCorrect);
    }

    public SearchOptions withLimit(int value) {
        return new SearchOptions(offset, value, autoCorrect);
    }

    public SearchOptions withAutoCorrect(boolean value) {
        return new SearchOptions(offset, limit, value);
    }
}
