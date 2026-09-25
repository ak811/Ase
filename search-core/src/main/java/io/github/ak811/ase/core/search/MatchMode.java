package io.github.ak811.ase.core.search;

/** How the returned hits relate to the query terms. */
public enum MatchMode {
    /** Every hit contains every query term. */
    ALL_TERMS,
    /**
     * No document contained all terms, so hits containing any term are returned,
     * ranked first by how many query terms they contain.
     */
    ANY_TERMS
}
