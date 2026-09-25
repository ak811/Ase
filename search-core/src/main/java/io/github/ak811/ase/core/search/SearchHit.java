package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.index.Document;

/** One ranked result. Documents are never modified; display data lives in the snippets. */
public final class SearchHit {
    private final Document document;
    private final float score;
    private final int matchedTerms;
    private final Snippet title;
    private final Snippet excerpt;

    public SearchHit(Document document, float score, int matchedTerms, Snippet title, Snippet excerpt) {
        this.document = document;
        this.score = score;
        this.matchedTerms = matchedTerms;
        this.title = title;
        this.excerpt = excerpt;
    }

    public Document document() {
        return document;
    }

    /** Cosine similarity between the document and the query terms it contains. */
    public float score() {
        return score;
    }

    /** Number of distinct query terms found in the document. */
    public int matchedTerms() {
        return matchedTerms;
    }

    /** The full title with query terms highlighted. */
    public Snippet title() {
        return title;
    }

    /** A window of the body around the best match, with query terms highlighted. */
    public Snippet excerpt() {
        return excerpt;
    }
}
