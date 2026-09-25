package io.github.ak811.ase.core.search;

/**
 * One ranked result.
 *
 * @param documentId     stable id within the index
 * @param url            source URL or relative path; may be empty
 * @param title          full title with matches highlighted
 * @param excerpt        body excerpt around the best match, with matches highlighted
 * @param score          BM25 score, including the proximity boost
 * @param matchedClauses number of required query parts the document matched
 */
public record SearchHit(int documentId, String url, Snippet title, Snippet excerpt, float score,
                        int matchedClauses) {
}
