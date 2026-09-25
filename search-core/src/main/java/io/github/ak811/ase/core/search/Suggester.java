package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.analysis.Analyzer;
import io.github.ak811.ase.core.analysis.Token;
import io.github.ak811.ase.core.index.IndexReader;
import io.github.ak811.ase.core.index.Lexicon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Autocomplete: completes the last word of a partial query with the most frequent corpus
 * words that start with it. Works in any script except those indexed as bigrams. Thread-safe.
 */
public final class Suggester {
    public static final int MAX_INPUT_LENGTH = 200;
    private static final int MAX_SCANNED = 50_000;

    private final Lexicon lexicon;
    private final Analyzer analyzer;

    public Suggester(IndexReader reader) {
        this.lexicon = reader.lexicon();
        this.analyzer = new Analyzer(reader.analyzerConfig());
    }

    public List<String> suggest(String input, int limit) {
        if (input == null || input.isEmpty() || input.length() > MAX_INPUT_LENGTH || limit <= 0
                || Character.isWhitespace(input.charAt(input.length() - 1))) {
            return List.of();
        }
        List<Token> tokens = analyzer.analyze(input);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Token last = tokens.get(tokens.size() - 1);
        if (last.end() != input.length() || last.group().usesBigrams() || last.surface().length() < 2) {
            return List.of();
        }
        String prefix = last.surface();
        int start = lexicon.prefixStart(prefix);
        int end = Math.min(lexicon.prefixEnd(prefix), start + MAX_SCANNED);
        PriorityQueue<Integer> top = new PriorityQueue<>(Comparator.comparingInt(lexicon::documentFrequency));
        for (int i = start; i < end; i++) {
            if (!lexicon.word(i).equals(prefix)) {
                top.add(i);
                if (top.size() > limit) {
                    top.poll();
                }
            }
        }
        List<Integer> ordered = new ArrayList<>(top);
        ordered.sort(Comparator.comparingInt(lexicon::documentFrequency).reversed()
                .thenComparing(lexicon::word));
        String base = input.substring(0, last.start());
        Set<String> suggestions = new LinkedHashSet<>();
        for (int index : ordered) {
            suggestions.add(base + lexicon.display(index));
        }
        return new ArrayList<>(suggestions);
    }
}
