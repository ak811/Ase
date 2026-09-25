package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.analysis.Analyzer;
import io.github.ak811.ase.core.analysis.ScriptGroup;
import io.github.ak811.ase.core.analysis.Token;
import io.github.ak811.ase.core.index.IndexReader;
import io.github.ak811.ase.core.index.Lexicon;
import io.github.ak811.ase.core.index.Postings;
import io.github.ak811.ase.core.index.StoredDocument;
import io.github.ak811.ase.core.spell.Correction;
import io.github.ak811.ase.core.spell.SpellCorrector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Runs queries against an {@link IndexReader}.
 *
 * <p><b>Query model.</b> A query becomes a list of required <i>clauses</i> plus optional
 * exclusions. A clause is satisfied by any of its <i>alternatives</i>, and an alternative is a
 * set of terms that must all occur (consecutively, for phrases). Examples: a word is one
 * clause with one alternative; {@code comput*} has one alternative per expansion; the query
 * {@code data base} becomes one clause, {@code database} OR ({@code data} AND {@code base}),
 * when {@code database} is in the index.
 *
 * <p><b>Ranking.</b> BM25 (k1 = 1.2, b = 0.75) over a title-weighted term frequency; a clause
 * scores its best-matching alternative. Documents matching all clauses are returned; if there
 * are none, documents matching some are ranked by the number of clauses matched first. The top
 * {@value #RERANK_DEPTH} hits are then boosted by term proximity.
 *
 * <p><b>Cost.</b> Evaluation is document-at-a-time over the posting lists of the query terms,
 * so time and memory grow with the postings touched, not with the size of the corpus.
 *
 * <p>Thread-safe.
 */
public final class Searcher {
    public static final int MAX_QUERY_LENGTH = 1_000;
    static final int MAX_CLAUSES = 32;
    static final int MAX_PREFIX_EXPANSIONS = 32;
    static final int RERANK_DEPTH = 100;
    private static final float K1 = 1.2f;
    private static final float B = 0.75f;
    private static final float PROXIMITY_WEIGHT = 0.5f;

    private final IndexReader reader;
    private final Analyzer analyzer;
    private final SpellCorrector spellCorrector;
    private final SnippetGenerator snippets;

    /** Building the spelling model takes time proportional to the lexicon; reuse the instance. */
    public Searcher(IndexReader reader) {
        this(reader, new SpellCorrector(reader.lexicon()));
    }

    public Searcher(IndexReader reader, SpellCorrector spellCorrector) {
        this.reader = reader;
        this.analyzer = new Analyzer(reader.analyzerConfig());
        this.spellCorrector = spellCorrector;
        this.snippets = new SnippetGenerator(analyzer);
    }

    public IndexReader reader() {
        return reader;
    }

    public SearchResult search(String query) throws IOException {
        return search(query, SearchOptions.defaults());
    }

    public SearchResult search(String query, SearchOptions options) throws IOException {
        long startNanos = System.nanoTime();
        String raw = truncate(query == null ? "" : query);
        List<QueryParser.Part> parts = QueryParser.parse(raw, analyzer);

        Map<Token, Correction> corrections = options.autoCorrect() ? correct(parts) : Map.of();
        String correctedQuery = rewrite(raw, corrections);

        Plan plan = plan(raw, parts, corrections);
        if (plan.required.isEmpty()) {
            return new SearchResult(raw, correctedQuery, List.of(), MatchMode.ALL_TERMS, 0, options.offset(),
                    List.of(), System.nanoTime() - startNanos);
        }

        Evaluation evaluation = evaluate(plan, options);
        List<SearchHit> hits = new ArrayList<>();
        Set<String> highlightTerms = new LinkedHashSet<>(plan.highlightTerms);
        for (int i = options.offset(); i < evaluation.documents.length
                && i < (long) options.offset() + options.limit(); i++) {
            StoredDocument document = reader.document(evaluation.documents[i]);
            hits.add(new SearchHit(document.id(), document.url(),
                    snippets.highlight(document.title(), highlightTerms),
                    snippets.excerpt(document.body(), highlightTerms),
                    evaluation.scores[i], evaluation.matched[i]));
        }
        return new SearchResult(raw, correctedQuery, List.copyOf(highlightTerms), evaluation.mode,
                evaluation.total, options.offset(), hits, System.nanoTime() - startNanos);
    }

    // ---------------------------------------------------------------- spelling

    private Map<Token, Correction> correct(List<QueryParser.Part> parts) {
        Map<Token, Correction> corrections = new IdentityHashMap<>();
        for (QueryParser.Part part : parts) {
            if (part.kind() != QueryParser.Kind.WORD || part.negated()) {
                continue;
            }
            Token token = part.token();
            if (!isCorrectable(token) || reader.termId(token.term()) >= 0) {
                continue;
            }
            Correction correction = spellCorrector.suggest(token.surface());
            if (correction != null) {
                List<Token> analyzed = analyzer.analyze(correction.word());
                if (analyzed.size() == 1 && reader.termId(analyzed.get(0).term()) >= 0) {
                    corrections.put(token, correction);
                }
            }
        }
        return corrections;
    }

    private static boolean isCorrectable(Token token) {
        if (token.stopword() || token.group().usesBigrams() || token.group() == ScriptGroup.DIGIT) {
            return false;
        }
        int minimum = token.group() == ScriptGroup.ARABIC ? 2 : 3;
        return token.surface().length() >= minimum;
    }

    private static String rewrite(String raw, Map<Token, Correction> corrections) {
        if (corrections.isEmpty()) {
            return null;
        }
        List<Token> tokens = new ArrayList<>(corrections.keySet());
        tokens.sort(Comparator.comparingInt(Token::start));
        StringBuilder rewritten = new StringBuilder(raw.length());
        int cursor = 0;
        for (Token token : tokens) {
            rewritten.append(raw, cursor, token.start()).append(corrections.get(token).display());
            cursor = token.end();
        }
        rewritten.append(raw, cursor, raw.length());
        return rewritten.toString().strip();
    }

    // ---------------------------------------------------------------- planning

    /** Query terms by slot, plus the clauses that reference them. */
    private static final class Plan {
        final List<String> terms = new ArrayList<>();
        final Map<String, Integer> slots = new HashMap<>();
        final List<Clause> required = new ArrayList<>();
        final List<Clause> excluded = new ArrayList<>();
        final List<String> highlightTerms = new ArrayList<>();

        int slot(String term) {
            Integer slot = slots.get(term);
            if (slot == null) {
                slot = terms.size();
                terms.add(term);
                slots.put(term, slot);
            }
            return slot;
        }

        Alternative single(String term) {
            return new Alternative(new int[]{slot(term)}, null);
        }

        Alternative all(List<String> terms) {
            int[] slots = new int[terms.size()];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = slot(terms.get(i));
            }
            return new Alternative(slots, null);
        }

        Alternative phrase(List<Token> tokens) {
            int[] slots = new int[tokens.size()];
            int[] offsets = new int[tokens.size()];
            int first = tokens.get(0).position();
            for (int i = 0; i < slots.length; i++) {
                slots[i] = slot(tokens.get(i).term());
                offsets[i] = tokens.get(i).position() - first;
            }
            return new Alternative(slots, offsets);
        }
    }

    /** All slots must occur; for phrases, at the given relative positions. */
    private record Alternative(int[] slots, int[] offsets) {
    }

    private record Clause(List<Alternative> alternatives) {
    }

    private Plan plan(String raw, List<QueryParser.Part> parts, Map<Token, Correction> corrections) {
        Plan plan = new Plan();
        boolean onlyStopwords = true;
        for (QueryParser.Part part : parts) {
            if (!part.negated() && !(part.kind() == QueryParser.Kind.WORD && part.token().stopword())) {
                onlyStopwords = false;
                break;
            }
        }

        for (int i = 0; i < parts.size() && plan.required.size() < MAX_CLAUSES; i++) {
            QueryParser.Part part = parts.get(i);
            if (part.negated()) {
                Alternative alternative = part.kind() == QueryParser.Kind.PHRASE && part.tokens().size() > 1
                        ? plan.phrase(part.tokens()) : plan.single(part.token().term());
                plan.excluded.add(new Clause(List.of(alternative)));
                continue;
            }
            switch (part.kind()) {
                case PHRASE: {
                    Alternative alternative = part.tokens().size() > 1
                            ? plan.phrase(part.tokens()) : plan.single(part.token().term());
                    plan.required.add(new Clause(List.of(alternative)));
                    for (Token token : part.tokens()) {
                        plan.highlightTerms.add(token.term());
                    }
                    break;
                }
                case PREFIX: {
                    Token token = part.token();
                    List<String> expansions = token.surface().length() >= 2 ? expand(token.surface()) : List.of();
                    List<Alternative> alternatives = new ArrayList<>();
                    for (String term : expansions) {
                        alternatives.add(plan.single(term));
                        plan.highlightTerms.add(term);
                    }
                    if (alternatives.isEmpty()) {
                        alternatives.add(plan.single(token.term()));
                        plan.highlightTerms.add(token.term());
                    }
                    plan.required.add(new Clause(alternatives));
                    break;
                }
                default: {
                    Token token = part.token();
                    if (token.stopword() && !onlyStopwords) {
                        break;
                    }
                    QueryParser.Part next = i + 1 < parts.size() ? parts.get(i + 1) : null;
                    String compound = next == null ? null : compound(raw, part, next, corrections);
                    if (compound != null) {
                        String left = token.term();
                        String right = next.token().term();
                        plan.required.add(new Clause(List.of(plan.single(compound), plan.all(List.of(left, right)))));
                        plan.highlightTerms.addAll(List.of(compound, left, right));
                        i++;
                        break;
                    }
                    Correction correction = corrections.get(token);
                    String term = correction == null ? token.term() : analyzer.analyze(correction.word()).get(0).term();
                    plan.required.add(new Clause(List.of(plan.single(term))));
                    plan.highlightTerms.add(term);
                    break;
                }
            }
        }
        return plan;
    }

    /**
     * If two adjacent query words also exist written together ({@code data base} → {@code database},
     * {@code کتاب خانه} → {@code کتابخانه}), returns the joined term.
     */
    private String compound(String raw, QueryParser.Part left, QueryParser.Part right, Map<Token, Correction> corrections) {
        if (right.kind() != QueryParser.Kind.WORD || right.negated() || right.segment() != left.segment()) {
            return null;
        }
        Token a = left.token();
        Token b = right.token();
        if (a.stopword() || b.stopword() || a.group() != b.group() || a.group().usesBigrams()
                || a.group() == ScriptGroup.DIGIT || corrections.containsKey(a) || corrections.containsKey(b)
                || !raw.substring(a.end(), b.start()).isBlank()) {
            return null;
        }
        List<Token> joined = analyzer.analyze(raw.substring(a.start(), a.end()) + raw.substring(b.start(), b.end()));
        if (joined.size() != 1) {
            return null;
        }
        String term = joined.get(0).term();
        return !term.equals(a.term()) && !term.equals(b.term()) && reader.termId(term) >= 0 ? term : null;
    }

    /** The most frequent index terms of the corpus words starting with {@code prefix}. */
    private List<String> expand(String prefix) {
        Lexicon lexicon = reader.lexicon();
        int start = lexicon.prefixStart(prefix);
        int end = lexicon.prefixEnd(prefix);
        PriorityQueue<Integer> top = new PriorityQueue<>(Comparator.comparingInt(lexicon::documentFrequency));
        int scanned = 0;
        for (int i = start; i < end && scanned < 50_000; i++, scanned++) {
            top.add(i);
            if (top.size() > MAX_PREFIX_EXPANSIONS) {
                top.poll();
            }
        }
        Set<String> terms = new LinkedHashSet<>();
        List<Integer> ordered = new ArrayList<>(top);
        ordered.sort(Comparator.comparingInt(lexicon::documentFrequency).reversed());
        for (int index : ordered) {
            for (Token token : analyzer.analyze(lexicon.word(index))) {
                if (reader.termId(token.term()) >= 0) {
                    terms.add(token.term());
                }
            }
        }
        return new ArrayList<>(terms);
    }

    // ---------------------------------------------------------------- evaluation

    private record Evaluation(MatchMode mode, int total, int[] documents, float[] scores, int[] matched) {
    }

    private static final class Cursor {
        final int slot;
        final Postings postings;
        int index;

        Cursor(int slot, Postings postings) {
            this.slot = slot;
            this.postings = postings;
        }

        int document() {
            return postings.documentId(index);
        }
    }

    private Evaluation evaluate(Plan plan, SearchOptions options) throws IOException {
        int slotCount = plan.terms.size();
        Postings[] postings = new Postings[slotCount];
        float[] idf = new float[slotCount];
        int documentCount = reader.documentCount();
        PriorityQueue<Cursor> queue = new PriorityQueue<>(Comparator.comparingInt(Cursor::document));
        for (int slot = 0; slot < slotCount; slot++) {
            int termId = reader.termId(plan.terms.get(slot));
            if (termId >= 0) {
                postings[slot] = reader.postings(termId);
                int df = reader.documentFrequency(termId);
                idf[slot] = (float) Math.log(1 + (documentCount - df + 0.5) / (df + 0.5));
                queue.add(new Cursor(slot, postings[slot]));
            }
        }

        int capacity = Math.min(SearchOptions.MAX_WINDOW, Math.max(options.offset() + options.limit(), RERANK_DEPTH));
        TopDocs allTerms = new TopDocs(capacity);
        TopDocs anyTerms = new TopDocs(capacity);
        int allCount = 0;
        int anyCount = 0;
        int required = plan.required.size();

        int[] present = new int[slotCount];
        Arrays.fill(present, -1);
        int[] touched = new int[slotCount];
        float averageLength = Math.max(1e-6f, reader.averageDocumentLength());
        float titleBoost = reader.titleBoost();

        while (!queue.isEmpty()) {
            int document = queue.peek().document();
            int touchedCount = 0;
            while (!queue.isEmpty() && queue.peek().document() == document) {
                Cursor cursor = queue.poll();
                present[cursor.slot] = cursor.index;
                touched[touchedCount++] = cursor.slot;
                if (++cursor.index < cursor.postings.size()) {
                    queue.add(cursor);
                }
            }

            boolean excluded = false;
            for (Clause clause : plan.excluded) {
                if (clauseScore(clause, document, present, postings, idf, averageLength, titleBoost) >= 0) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                int matched = 0;
                float score = 0;
                for (Clause clause : plan.required) {
                    float clauseScore = clauseScore(clause, document, present, postings, idf, averageLength, titleBoost);
                    if (clauseScore >= 0) {
                        matched++;
                        score += clauseScore;
                    }
                }
                if (matched > 0) {
                    anyCount++;
                    anyTerms.offer(document, score, matched);
                    if (matched == required) {
                        allCount++;
                        allTerms.offer(document, score, matched);
                    }
                }
            }
            for (int t = 0; t < touchedCount; t++) {
                present[touched[t]] = -1;
            }
        }

        boolean all = allCount > 0 || anyCount == 0;
        TopDocs top = all ? allTerms : anyTerms;
        int[] documents = new int[top.size()];
        float[] scores = new float[top.size()];
        int[] matched = new int[top.size()];
        top.drainBestFirst(documents, scores, matched);
        if (required >= 2) {
            rerankByProximity(plan, postings, documents, scores, matched);
        }
        return new Evaluation(all ? MatchMode.ALL_TERMS : MatchMode.ANY_TERMS, all ? allCount : anyCount,
                documents, scores, matched);
    }

    /** Best alternative score for the document, or -1 if no alternative matches. */
    private float clauseScore(Clause clause, int document, int[] present, Postings[] postings, float[] idf,
                              float averageLength, float titleBoost) {
        float best = -1;
        for (Alternative alternative : clause.alternatives()) {
            boolean complete = true;
            for (int slot : alternative.slots()) {
                if (present[slot] < 0) {
                    complete = false;
                    break;
                }
            }
            if (!complete || (alternative.offsets() != null && !phraseMatches(alternative, present, postings))) {
                continue;
            }
            float score = 0;
            float length = reader.documentLength(document);
            for (int slot : alternative.slots()) {
                Postings list = postings[slot];
                int index = present[slot];
                float tf = titleBoost * list.titleFrequency(index) + list.bodyFrequency(index);
                score += idf[slot] * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / averageLength));
            }
            best = Math.max(best, score);
        }
        return best;
    }

    private static boolean phraseMatches(Alternative phrase, int[] present, Postings[] postings) {
        int[] slots = phrase.slots();
        int[][] positions = new int[slots.length][];
        for (int i = 0; i < slots.length; i++) {
            positions[i] = postings[slots[i]].positions(present[slots[i]]);
        }
        for (int start : positions[0]) {
            boolean all = true;
            for (int i = 1; i < slots.length && all; i++) {
                all = Arrays.binarySearch(positions[i], start + phrase.offsets()[i]) >= 0;
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /**
     * Multiplies the scores of the top hits by {@code 1 + w / (1 + slack)}, where slack is how much
     * wider the tightest window containing every matched clause is than the clauses themselves.
     */
    private void rerankByProximity(Plan plan, Postings[] postings, int[] documents, float[] scores, int[] matched) {
        int depth = Math.min(documents.length, RERANK_DEPTH);
        for (int i = 0; i < depth; i++) {
            List<int[]> events = new ArrayList<>();
            int clauses = 0;
            for (int c = 0; c < plan.required.size(); c++) {
                boolean found = false;
                for (Alternative alternative : plan.required.get(c).alternatives()) {
                    Postings list = postings[alternative.slots()[0]];
                    if (list == null) {
                        continue;
                    }
                    int index = list.find(documents[i]);
                    if (index >= 0) {
                        for (int position : list.positions(index)) {
                            events.add(new int[]{position, c});
                        }
                        found = true;
                    }
                }
                if (found) {
                    clauses++;
                }
            }
            if (clauses < 2) {
                continue;
            }
            int span = minimumSpan(events, plan.required.size(), clauses);
            int slack = Math.max(0, span - (clauses - 1));
            scores[i] *= 1 + PROXIMITY_WEIGHT / (1 + slack);
        }
        Integer[] order = new Integer[depth];
        for (int i = 0; i < depth; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            if (matched[a] != matched[b]) {
                return Integer.compare(matched[b], matched[a]);
            }
            int byScore = Float.compare(scores[b], scores[a]);
            return byScore != 0 ? byScore : Integer.compare(documents[a], documents[b]);
        });
        int[] newDocuments = new int[depth];
        float[] newScores = new float[depth];
        int[] newMatched = new int[depth];
        for (int i = 0; i < depth; i++) {
            newDocuments[i] = documents[order[i]];
            newScores[i] = scores[order[i]];
            newMatched[i] = matched[order[i]];
        }
        System.arraycopy(newDocuments, 0, documents, 0, depth);
        System.arraycopy(newScores, 0, scores, 0, depth);
        System.arraycopy(newMatched, 0, matched, 0, depth);
    }

    /** Smallest position range containing at least one event of each of {@code needed} clauses. */
    private static int minimumSpan(List<int[]> events, int clauseCount, int needed) {
        events.sort(Comparator.comparingInt(e -> e[0]));
        int[] counts = new int[clauseCount];
        int covered = 0;
        int best = Integer.MAX_VALUE;
        int left = 0;
        for (int right = 0; right < events.size(); right++) {
            if (counts[events.get(right)[1]]++ == 0) {
                covered++;
            }
            while (covered == needed) {
                best = Math.min(best, events.get(right)[0] - events.get(left)[0]);
                if (--counts[events.get(left)[1]] == 0) {
                    covered--;
                }
                left++;
            }
        }
        return best;
    }

    private static String truncate(String query) {
        if (query.length() <= MAX_QUERY_LENGTH) {
            return query;
        }
        int end = MAX_QUERY_LENGTH;
        if (Character.isHighSurrogate(query.charAt(end - 1))) {
            end--;
        }
        return query.substring(0, end);
    }

    /** Bounded min-heap of (document, score, matched) keeping the best entries. */
    private static final class TopDocs {
        private final int[] documents;
        private final float[] scores;
        private final int[] matched;
        private int size;

        TopDocs(int capacity) {
            documents = new int[capacity];
            scores = new float[capacity];
            matched = new int[capacity];
        }

        int size() {
            return size;
        }

        void offer(int document, float score, int matchedClauses) {
            if (documents.length == 0) {
                return;
            }
            if (size < documents.length) {
                set(size, document, score, matchedClauses);
                siftUp(size++);
            } else if (before(document, score, matchedClauses, 0)) {
                set(0, document, score, matchedClauses);
                siftDown(0);
            }
        }

        void drainBestFirst(int[] outDocuments, float[] outScores, int[] outMatched) {
            for (int i = size - 1; i >= 0; i--) {
                outDocuments[i] = documents[0];
                outScores[i] = scores[0];
                outMatched[i] = matched[0];
                size--;
                set(0, documents[size], scores[size], matched[size]);
                siftDown(0);
            }
        }

        private boolean before(int document, float score, int matchedClauses, int slot) {
            if (matchedClauses != matched[slot]) {
                return matchedClauses > matched[slot];
            }
            int byScore = Float.compare(score, scores[slot]);
            return byScore != 0 ? byScore > 0 : document < documents[slot];
        }

        private boolean before(int a, int b) {
            return before(documents[a], scores[a], matched[a], b);
        }

        private void set(int slot, int document, float score, int matchedClauses) {
            documents[slot] = document;
            scores[slot] = score;
            matched[slot] = matchedClauses;
        }

        private void siftUp(int i) {
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (!before(parent, i)) {
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
                if (left < size && before(worst, left)) {
                    worst = left;
                }
                if (right < size && before(worst, right)) {
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
            int document = documents[a];
            float score = scores[a];
            int matchedClauses = matched[a];
            set(a, documents[b], scores[b], matched[b]);
            set(b, document, score, matchedClauses);
        }
    }
}
