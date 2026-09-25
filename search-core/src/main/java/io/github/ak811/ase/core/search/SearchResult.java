package io.github.ak811.ase.core.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The outcome of one {@link Searcher#search} call. */
public final class SearchResult {
    private final String query;
    private final String correctedQuery;
    private final List<String> terms;
    private final MatchMode matchMode;
    private final int totalHits;
    private final int offset;
    private final List<SearchHit> hits;
    private final long tookNanos;

    SearchResult(String query, String correctedQuery, List<String> terms, MatchMode matchMode,
                 int totalHits, int offset, List<SearchHit> hits, long tookNanos) {
        this.query = query;
        this.correctedQuery = correctedQuery;
        this.terms = Collections.unmodifiableList(new ArrayList<>(terms));
        this.matchMode = matchMode;
        this.totalHits = totalHits;
        this.offset = offset;
        this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
        this.tookNanos = tookNanos;
    }

    /** The query exactly as the user typed it. */
    public String query() {
        return query;
    }

    /** The spell-corrected query that was actually searched, or {@code null} if nothing was corrected. */
    public String correctedQuery() {
        return correctedQuery;
    }

    public boolean wasCorrected() {
        return correctedQuery != null;
    }

    /** Distinct normalized terms that were searched for. */
    public List<String> terms() {
        return terms;
    }

    public MatchMode matchMode() {
        return matchMode;
    }

    /** Number of matching documents, regardless of offset and limit. */
    public int totalHits() {
        return totalHits;
    }

    public int offset() {
        return offset;
    }

    /** The requested page of hits, best first. */
    public List<SearchHit> hits() {
        return hits;
    }

    public long tookNanos() {
        return tookNanos;
    }

    public double tookSeconds() {
        return tookNanos / 1e9;
    }
}
