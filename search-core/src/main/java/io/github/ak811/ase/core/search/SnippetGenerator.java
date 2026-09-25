package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.analysis.Analyzer;
import io.github.ak811.ase.core.analysis.Token;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Produces highlighted titles and query-focused excerpts. Thread-safe. */
public final class SnippetGenerator {
    public static final int DEFAULT_MAX_LENGTH = 240;
    /** Only the start of very long bodies is scanned for matches. */
    static final int MAX_SCANNED_CHARS = 200_000;

    private static final String ELLIPSIS = "…";
    private static final int MAX_ANCHORS = 256;

    private final Analyzer analyzer;
    private final int maxLength;

    public SnippetGenerator(Analyzer analyzer) {
        this(analyzer, DEFAULT_MAX_LENGTH);
    }

    public SnippetGenerator(Analyzer analyzer, int maxLength) {
        if (maxLength < 40) {
            throw new IllegalArgumentException("maxLength must be >= 40");
        }
        this.analyzer = analyzer;
        this.maxLength = maxLength;
    }

    /** The whole text with every occurrence of {@code terms} highlighted. */
    public Snippet highlight(String text, Set<String> terms) {
        List<Highlight> highlights = new ArrayList<>();
        for (Token match : matches(text, terms)) {
            highlights.add(new Highlight(match.start(), match.end()));
        }
        return new Snippet(text, highlights);
    }

    /**
     * About {@code maxLength} characters around the region with the most distinct query
     * terms, cut at word boundaries, with ellipses where text was omitted.
     */
    public Snippet excerpt(String text, Set<String> terms) {
        if (text.isEmpty()) {
            return Snippet.plain("");
        }
        List<Token> matches = matches(text, terms);
        int length = text.length();
        int start;
        int end;
        if (length <= maxLength) {
            start = 0;
            end = length;
        } else if (matches.isEmpty()) {
            start = 0;
            end = backToBoundary(text, maxLength, maxLength * 2 / 3);
        } else {
            Token anchor = bestAnchor(matches);
            start = Math.max(0, anchor.start() - maxLength / 4);
            end = Math.min(length, start + maxLength);
            if (end == length) {
                start = Math.max(0, length - maxLength);
            }
            if (start > 0) {
                start = forwardToBoundary(text, start, anchor.start());
            }
            if (end < length) {
                end = backToBoundary(text, end, anchor.end());
            }
        }
        if (start > 0 && start < length && Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        if (end > start && end < length && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }

        StringBuilder snippet = new StringBuilder(end - start + 2);
        if (start > 0) {
            snippet.append(ELLIPSIS);
        }
        int shift = snippet.length() - start;
        snippet.append(text, start, end);
        if (end < length) {
            snippet.append(ELLIPSIS);
        }
        List<Highlight> highlights = new ArrayList<>();
        for (Token match : matches) {
            if (match.start() >= start && match.end() <= end) {
                highlights.add(new Highlight(match.start() + shift, match.end() + shift));
            }
        }
        return new Snippet(snippet.toString(), highlights);
    }

    private List<Token> matches(String text, Set<String> terms) {
        List<Token> matches = new ArrayList<>();
        if (terms.isEmpty() || text.isEmpty()) {
            return matches;
        }
        String scanned = text.length() > MAX_SCANNED_CHARS ? text.substring(0, MAX_SCANNED_CHARS) : text;
        int lastEnd = 0;
        for (Token token : analyzer.analyze(scanned)) {
            // CJK bigrams overlap; keep highlights disjoint by trimming to the previous end.
            if (terms.contains(token.term()) && token.end() > lastEnd) {
                matches.add(token.start() < lastEnd ? token.shiftStart(lastEnd) : token);
                lastEnd = token.end();
            }
        }
        return matches;
    }

    private Token bestAnchor(List<Token> matches) {
        int limit = Math.min(matches.size(), MAX_ANCHORS);
        Token best = matches.get(0);
        int bestCoverage = 0;
        Set<String> covered = new HashSet<>();
        for (int i = 0; i < limit; i++) {
            Token anchor = matches.get(i);
            int windowEnd = Math.max(0, anchor.start() - maxLength / 4) + maxLength;
            covered.clear();
            for (int j = i; j < limit && matches.get(j).end() <= windowEnd; j++) {
                covered.add(matches.get(j).term());
            }
            if (covered.size() > bestCoverage) {
                bestCoverage = covered.size();
                best = anchor;
            }
        }
        return best;
    }

    private static int forwardToBoundary(String text, int position, int limit) {
        for (int i = position; i < limit; i++) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return position;
    }

    private static int backToBoundary(String text, int position, int limit) {
        for (int i = position; i > limit; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return position;
    }
}
