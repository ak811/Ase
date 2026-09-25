package io.github.ak811.ase.core.index;

/** Summary of a written index. */
public record IndexStats(int documents, int terms, int lexiconWords, long bytes, int spilledRuns) {
}
