package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.index.Document;
import io.github.ak811.ase.core.index.PostingList;
import io.github.ak811.ase.core.index.SearchIndex;
import io.github.ak811.ase.core.spell.SpellCorrector;
import io.github.ak811.ase.core.text.Token;
import io.github.ak811.ase.core.text.Tokenizer;
import io.github.ak811.ase.core.util.IntList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes queries against a {@link SearchIndex}.
 *
 * <p>Queries are tokenized with the same normalization as documents. Missing
 * terms are optionally spell-corrected. Documents are scored by summing the
 * cosine-normalized TF-IDF weights of the query terms they contain. Documents
 * containing all terms are returned; if there are none, documents containing any
 * term are returned, ranked by number of matched terms first and score second.
 *
 * <p>Thread-safe: concurrent searches share the immutable index.
 */
public final class Searcher {
    /** Longer queries are truncated; also keeps per-document match counts within a byte. */
    public static final int MAX_QUERY_TERMS = 32;

    private final SearchIndex index;
    private final Tokenizer tokenizer;
    private final SpellCorrector spellCorrector;
    private final SnippetGenerator snippets;

    /** Creates a searcher; building the spell-correction model takes time proportional to the vocabulary. */
    public Searcher(SearchIndex index) {
        this(index, new Tokenizer(), new SpellCorrector(index), new SnippetGenerator());
    }

    public Searcher(SearchIndex index, Tokenizer tokenizer, SpellCorrector spellCorrector, SnippetGenerator snippets) {
        if (index == null || tokenizer == null || spellCorrector == null || snippets == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        this.index = index;
        this.tokenizer = tokenizer;
        this.spellCorrector = spellCorrector;
        this.snippets = snippets;
    }

    public SearchIndex index() {
        return index;
    }

    public SearchResult search(String query) {
        return search(query, SearchOptions.defaults());
    }

    public SearchResult search(String query, SearchOptions options) {
        long startNanos = System.nanoTime();
        String raw = query == null ? "" : query;

        List<Token> tokens = tokenizer.tokenize(raw);
        if (tokens.size() > MAX_QUERY_TERMS) {
            tokens = tokens.subList(0, MAX_QUERY_TERMS);
        }

        List<String> effectiveTerms = new ArrayList<>(tokens.size());
        String correctedQuery = options.autoCorrect()
                ? correct(raw, tokens, effectiveTerms)
                : collectTerms(tokens, effectiveTerms);
        Set<String> terms = new LinkedHashSet<>(effectiveTerms);
        if (terms.isEmpty()) {
            return new SearchResult(raw, correctedQuery, Collections.<String>emptyList(), MatchMode.ALL_TERMS,
                    0, options.offset(), Collections.<SearchHit>emptyList(), System.nanoTime() - startNanos);
        }

        int documentCount = index.documentCount();
        float[] scores = new float[documentCount];
        byte[] matched = new byte[documentCount];
        IntList candidates = new IntList();
        for (String term : terms) {
            PostingList postings = index.postings(term);
            if (postings == null) {
                continue;
            }
            for (int i = 0; i < postings.size(); i++) {
                int docId = postings.docId(i);
                if (matched[docId]++ == 0) {
                    candidates.add(docId);
                }
                scores[docId] += postings.weight(i);
            }
        }

        int required = terms.size();
        int allTermsCount = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (matched[candidates.get(i)] == required) {
                allTermsCount++;
            }
        }
        MatchMode mode = allTermsCount > 0 || candidates.size() == 0 ? MatchMode.ALL_TERMS : MatchMode.ANY_TERMS;
        int totalHits = mode == MatchMode.ALL_TERMS ? allTermsCount : candidates.size();

        int wanted = (int) Math.min(totalHits, (long) options.offset() + options.limit());
        TopDocs top = new TopDocs(wanted, scores, matched);
        for (int i = 0; i < candidates.size(); i++) {
            int docId = candidates.get(i);
            if (mode == MatchMode.ANY_TERMS || matched[docId] == required) {
                top.offer(docId);
            }
        }
        int[] ranked = top.sortedBestFirst();

        List<SearchHit> hits = new ArrayList<>(Math.max(0, ranked.length - options.offset()));
        for (int i = options.offset(); i < ranked.length; i++) {
            int docId = ranked[i];
            Document document = index.document(docId);
            hits.add(new SearchHit(document, scores[docId], matched[docId],
                    snippets.highlight(document.title(), terms),
                    snippets.excerpt(document.body(), terms)));
        }
        return new SearchResult(raw, correctedQuery, new ArrayList<>(terms), mode, totalHits,
                options.offset(), hits, System.nanoTime() - startNanos);
    }

    /** Replaces unknown terms with suggestions; returns the rewritten query or null if unchanged. */
    private String correct(String raw, List<Token> tokens, List<String> out) {
        StringBuilder rewritten = new StringBuilder(raw.length());
        int cursor = 0;
        boolean changed = false;
        for (Token token : tokens) {
            String term = token.term();
            if (!index.contains(term)) {
                String suggestion = spellCorrector.suggest(term);
                if (suggestion != null) {
                    rewritten.append(raw, cursor, token.start()).append(suggestion);
                    cursor = token.end();
                    changed = true;
                    out.add(suggestion);
                    continue;
                }
            }
            out.add(term);
        }
        if (!changed) {
            return null;
        }
        rewritten.append(raw, cursor, raw.length());
        return rewritten.toString().trim();
    }

    private static String collectTerms(List<Token> tokens, List<String> out) {
        for (Token token : tokens) {
            out.add(token.term());
        }
        return null;
    }

    /** Bounded min-heap keeping the best {@code capacity} documents without boxing. */
    private static final class TopDocs {
        private final int[] heap;
        private final float[] scores;
        private final byte[] matched;
        private int size;

        TopDocs(int capacity, float[] scores, byte[] matched) {
            this.heap = new int[capacity];
            this.scores = scores;
            this.matched = matched;
        }

        void offer(int docId) {
            if (heap.length == 0) {
                return;
            }
            if (size < heap.length) {
                heap[size] = docId;
                siftUp(size++);
            } else if (ranksBefore(docId, heap[0])) {
                heap[0] = docId;
                siftDown(0);
            }
        }

        int[] sortedBestFirst() {
            int[] sorted = new int[size];
            for (int i = size - 1; i >= 0; i--) {
                sorted[i] = heap[0];
                heap[0] = heap[--size];
                siftDown(0);
            }
            return sorted;
        }

        /** More matched terms first, then higher score, then lower id (stable, deterministic). */
        private boolean ranksBefore(int a, int b) {
            if (matched[a] != matched[b]) {
                return matched[a] > matched[b];
            }
            int byScore = Float.compare(scores[a], scores[b]);
            if (byScore != 0) {
                return byScore > 0;
            }
            return a < b;
        }

        private void siftUp(int i) {
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (!ranksBefore(heap[parent], heap[i])) {
                    return;
                }
                swap(parent, i);
                i = parent;
            }
        }

        private void siftDown(int i) {
            while (true) {
                int left = 2 * i + 1;
                int right = left + 1;
                int worst = i;
                if (left < size && ranksBefore(heap[worst], heap[left])) {
                    worst = left;
                }
                if (right < size && ranksBefore(heap[worst], heap[right])) {
                    worst = right;
                }
                if (worst == i) {
                    return;
                }
                swap(i, worst);
                i = worst;
            }
        }

        private void swap(int a, int b) {
            int tmp = heap[a];
            heap[a] = heap[b];
            heap[b] = tmp;
        }
    }
}
