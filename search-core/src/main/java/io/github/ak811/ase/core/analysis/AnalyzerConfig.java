package io.github.ak811.ase.core.analysis;

/**
 * Analysis settings. They are stored in the index file so queries are always
 * analyzed exactly like the documents were.
 *
 * @param stemming reduce English words (Porter) and Persian words (light suffix stripping) to stems
 */
public record AnalyzerConfig(boolean stemming) {
    /**
     * Version of the analysis rules. Bump it whenever a change would produce different
     * terms for the same text, so old indexes are rejected instead of silently mismatching.
     */
    public static final int RULES_VERSION = 1;

    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig(true);
    }
}
