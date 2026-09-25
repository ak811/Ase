package io.github.ak811.ase.core.search;

/** How the returned hits relate to the query. */
public enum MatchMode {
    /** Every hit matches every required part of the query. */
    ALL_TERMS,
    /**
     * No document matched everything, so documents matching some parts are returned,
     * ranked first by how many parts they match.
     */
    ANY_TERMS
}
