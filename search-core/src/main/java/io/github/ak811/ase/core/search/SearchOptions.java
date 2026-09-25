package io.github.ak811.ase.core.search;

/** Immutable search parameters. Start from {@link #defaults()} and use the {@code with…} methods. */
public final class SearchOptions {
    public static final int MAX_LIMIT = 1000;

    private static final SearchOptions DEFAULTS = new SearchOptions(0, 20, true);

    private final int offset;
    private final int limit;
    private final boolean autoCorrect;

    private SearchOptions(int offset, int limit, boolean autoCorrect) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        this.offset = offset;
        this.limit = limit;
        this.autoCorrect = autoCorrect;
    }

    /** offset 0, limit 20, auto-correct on. */
    public static SearchOptions defaults() {
        return DEFAULTS;
    }

    public SearchOptions withOffset(int offset) {
        return new SearchOptions(offset, limit, autoCorrect);
    }

    public SearchOptions withLimit(int limit) {
        return new SearchOptions(offset, limit, autoCorrect);
    }

    /** When enabled, query terms missing from the index are replaced by spelling suggestions. */
    public SearchOptions withAutoCorrect(boolean autoCorrect) {
        return new SearchOptions(offset, limit, autoCorrect);
    }

    public int offset() {
        return offset;
    }

    public int limit() {
        return limit;
    }

    public boolean autoCorrect() {
        return autoCorrect;
    }
}
