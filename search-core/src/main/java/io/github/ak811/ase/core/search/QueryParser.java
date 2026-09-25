package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.analysis.Analyzer;
import io.github.ak811.ase.core.analysis.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the query syntax:
 * <ul>
 *   <li>{@code word} — required (with fallback to partial matches when nothing matches everything)</li>
 *   <li>{@code "exact phrase"} — the words must appear consecutively</li>
 *   <li>{@code -word}, {@code -"phrase"} — exclude documents containing it</li>
 *   <li>{@code prefix*} — any word starting with the prefix</li>
 * </ul>
 * Anything else is plain text; unbalanced quotes run to the end of the query.
 */
final class QueryParser {

    enum Kind { WORD, PREFIX, PHRASE }

    /** One element of the query. {@code tokens} have offsets into the raw query. */
    record Part(Kind kind, List<Token> tokens, boolean negated, int segment) {
        Token token() {
            return tokens.get(0);
        }
    }

    private QueryParser() {
    }

    static List<Part> parse(String raw, Analyzer analyzer) {
        List<Part> parts = new ArrayList<>();
        int length = raw.length();
        int i = 0;
        int segment = 0;
        while (i < length) {
            int quote = raw.indexOf('"', i);
            int plainEnd = quote < 0 ? length : quote;
            parsePlain(raw, i, plainEnd, segment++, analyzer, parts);
            if (quote < 0) {
                break;
            }
            int close = raw.indexOf('"', quote + 1);
            int phraseEnd = close < 0 ? length : close;
            boolean negated = quote > 0 && raw.charAt(quote - 1) == '-'
                    && (quote == 1 || Character.isWhitespace(raw.charAt(quote - 2)));
            List<Token> tokens = shift(analyzer.analyze(raw.substring(quote + 1, phraseEnd)), quote + 1);
            if (!tokens.isEmpty()) {
                parts.add(new Part(Kind.PHRASE, tokens, negated, segment++));
            }
            i = close < 0 ? length : close + 1;
        }
        return parts;
    }

    private static void parsePlain(String raw, int from, int to, int segment, Analyzer analyzer, List<Part> parts) {
        if (from >= to) {
            return;
        }
        List<Token> tokens = shift(analyzer.analyze(raw.substring(from, to)), from);
        int i = 0;
        while (i < tokens.size()) {
            int chunkStart = chunkStart(raw, from, tokens.get(i).start());
            int j = i + 1;
            while (j < tokens.size() && chunkStart(raw, from, tokens.get(j).start()) == chunkStart) {
                j++;
            }
            List<Token> chunk = tokens.subList(i, j);
            boolean negated = raw.charAt(chunkStart) == '-';
            if (negated) {
                parts.add(new Part(chunk.size() == 1 ? Kind.WORD : Kind.PHRASE, List.copyOf(chunk), true, segment));
            } else {
                for (Token token : chunk) {
                    boolean prefix = token.end() < to && raw.charAt(token.end()) == '*';
                    parts.add(new Part(prefix ? Kind.PREFIX : Kind.WORD, List.of(token), false, segment));
                }
            }
            i = j;
        }
    }

    /** Start of the whitespace-delimited chunk containing {@code offset}. */
    private static int chunkStart(String raw, int from, int offset) {
        int start = offset;
        while (start > from && !Character.isWhitespace(raw.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    private static List<Token> shift(List<Token> tokens, int delta) {
        List<Token> shifted = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            shifted.add(token.shift(delta));
        }
        return shifted;
    }
}
