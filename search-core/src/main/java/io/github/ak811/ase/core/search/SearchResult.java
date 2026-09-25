package io.github.ak811.ase.core.search;

import java.util.List;

/**
 * The outcome of a search.
 *
 * @param query          the query as typed
 * @param correctedQuery the spell-corrected query that was searched, or {@code null}
 * @param terms          the index terms that were highlighted
 * @param matchMode      whether hits match all required parts or only some
 * @param totalHits      number of matching documents, regardless of paging
 * @param offset         index of the first returned hit
 * @param hits           the requested page, best first
 * @param tookNanos      time spent searching
 */
public record SearchResult(String query, String correctedQuery, List<String> terms, MatchMode matchMode,
                           int totalHits, int offset, List<SearchHit> hits, long tookNanos) {

    public SearchResult {
        terms = List.copyOf(terms);
        hits = List.copyOf(hits);
    }

    public boolean wasCorrected() {
        return correctedQuery != null;
    }

    public double tookMillis() {
        return tookNanos / 1e6;
    }
}
