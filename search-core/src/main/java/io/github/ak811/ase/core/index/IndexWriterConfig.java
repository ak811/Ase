package io.github.ak811.ase.core.index;

import io.github.ak811.ase.core.analysis.AnalyzerConfig;

/**
 * @param analyzer          how text is turned into terms
 * @param titleBoost        weight of a title occurrence relative to a body occurrence (BM25F-style)
 * @param memoryBudgetBytes postings are spilled to temporary files beyond this much buffered data
 * @param maxLexiconWords   rarest spelling/autocomplete words are pruned beyond this count
 */
public record IndexWriterConfig(AnalyzerConfig analyzer, float titleBoost, long memoryBudgetBytes,
                                int maxLexiconWords) {

    public static final float DEFAULT_TITLE_BOOST = 3f;
    public static final long DEFAULT_MEMORY_BUDGET = 256L * 1024 * 1024;
    public static final int DEFAULT_MAX_LEXICON_WORDS = 2_000_000;

    public IndexWriterConfig {
        if (analyzer == null) {
            throw new IllegalArgumentException("analyzer is null");
        }
        if (!(titleBoost >= 1f) || Float.isInfinite(titleBoost)) {
            throw new IllegalArgumentException("titleBoost must be a finite number >= 1");
        }
        if (memoryBudgetBytes < 1024 * 1024) {
            throw new IllegalArgumentException("memoryBudgetBytes must be at least 1 MiB");
        }
        if (maxLexiconWords < 1000) {
            throw new IllegalArgumentException("maxLexiconWords must be at least 1000");
        }
    }

    public static IndexWriterConfig defaults() {
        return new IndexWriterConfig(AnalyzerConfig.defaults(), DEFAULT_TITLE_BOOST,
                DEFAULT_MEMORY_BUDGET, DEFAULT_MAX_LEXICON_WORDS);
    }

    public IndexWriterConfig withAnalyzer(AnalyzerConfig value) {
        return new IndexWriterConfig(value, titleBoost, memoryBudgetBytes, maxLexiconWords);
    }

    public IndexWriterConfig withTitleBoost(float value) {
        return new IndexWriterConfig(analyzer, value, memoryBudgetBytes, maxLexiconWords);
    }

    public IndexWriterConfig withMemoryBudgetBytes(long value) {
        return new IndexWriterConfig(analyzer, titleBoost, value, maxLexiconWords);
    }

    public IndexWriterConfig withMaxLexiconWords(int value) {
        return new IndexWriterConfig(analyzer, titleBoost, memoryBudgetBytes, value);
    }
}
