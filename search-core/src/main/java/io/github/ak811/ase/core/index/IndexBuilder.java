package io.github.ak811.ase.core.index;

import io.github.ak811.ase.core.text.Token;
import io.github.ak811.ase.core.text.Tokenizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link SearchIndex}.
 *
 * <p>Weighting (SMART "ltc" on the document side):
 * <pre>
 *   tf(t, d)   = occurrences in body + titleBoost × occurrences in title
 *   idf(t)     = log10(1 + N / df(t))
 *   w(t, d)    = (1 + log10 tf(t, d)) × idf(t)
 *   stored     = w(t, d) / ‖w(·, d)‖₂          (cosine length normalization)
 * </pre>
 * At query time a document's score is the sum of the stored weights of the
 * query terms it contains, i.e. the cosine similarity to a binary query vector.
 *
 * <p>Not thread-safe. A builder can produce exactly one index.
 */
public final class IndexBuilder {
    public static final float DEFAULT_TITLE_BOOST = 5f;

    private final Tokenizer tokenizer;
    private final float titleBoost;
    private final List<Document> documents = new ArrayList<>();
    private final Map<String, TermStats> terms = new HashMap<>();
    private boolean built;

    public IndexBuilder() {
        this(DEFAULT_TITLE_BOOST);
    }

    public IndexBuilder(float titleBoost) {
        this(new Tokenizer(), titleBoost);
    }

    public IndexBuilder(Tokenizer tokenizer, float titleBoost) {
        if (tokenizer == null) {
            throw new IllegalArgumentException("tokenizer is null");
        }
        if (!(titleBoost >= 1f) || Float.isInfinite(titleBoost)) {
            throw new IllegalArgumentException("titleBoost must be a finite number >= 1, was " + titleBoost);
        }
        this.tokenizer = tokenizer;
        this.titleBoost = titleBoost;
    }

    /**
     * Adds a document and returns its id. {@code null} fields are treated as empty.
     */
    public int addDocument(String url, String title, String body) {
        if (built) {
            throw new IllegalStateException("build() has already been called");
        }
        int id = documents.size();
        Document document = new Document(id, clean(url), clean(title), clean(body));
        documents.add(document);

        Map<String, float[]> frequencies = new HashMap<>();
        accumulate(document.title(), titleBoost, frequencies);
        accumulate(document.body(), 1f, frequencies);
        for (Map.Entry<String, float[]> entry : frequencies.entrySet()) {
            TermStats stats = terms.get(entry.getKey());
            if (stats == null) {
                stats = new TermStats();
                terms.put(entry.getKey(), stats);
            }
            stats.add(id, entry.getValue()[0]);
        }
        return id;
    }

    public int documentCount() {
        return documents.size();
    }

    public SearchIndex build() {
        if (built) {
            throw new IllegalStateException("build() has already been called");
        }
        built = true;

        int n = documents.size();
        double[] squaredNorms = new double[n];
        for (TermStats stats : terms.values()) {
            double idf = Math.log10(1.0 + (double) n / stats.size);
            for (int i = 0; i < stats.size; i++) {
                double weight = (1.0 + Math.log10(stats.values[i])) * idf;
                stats.values[i] = (float) weight;
                squaredNorms[stats.docIds[i]] += weight * weight;
            }
        }

        Map<String, PostingList> postings = new HashMap<>(Math.max(16, terms.size() * 4 / 3 + 1));
        for (Map.Entry<String, TermStats> entry : terms.entrySet()) {
            TermStats stats = entry.getValue();
            int[] ids = Arrays.copyOf(stats.docIds, stats.size);
            float[] weights = new float[stats.size];
            for (int i = 0; i < stats.size; i++) {
                double norm = Math.sqrt(squaredNorms[ids[i]]);
                weights[i] = norm > 0 ? (float) (stats.values[i] / norm) : 0f;
            }
            postings.put(entry.getKey(), new PostingList(ids, weights));
        }
        terms.clear();
        return new SearchIndex(documents, postings);
    }

    private void accumulate(String text, float weight, Map<String, float[]> frequencies) {
        for (Token token : tokenizer.tokenize(text)) {
            float[] frequency = frequencies.get(token.term());
            if (frequency == null) {
                frequency = new float[1];
                frequencies.put(token.term(), frequency);
            }
            frequency[0] += weight;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** Growable (docId, value) pairs; value holds tf while building, then the raw weight. */
    private static final class TermStats {
        int[] docIds = new int[4];
        float[] values = new float[4];
        int size;

        void add(int docId, float value) {
            if (size == docIds.length) {
                int capacity = size * 2;
                docIds = Arrays.copyOf(docIds, capacity);
                values = Arrays.copyOf(values, capacity);
            }
            docIds[size] = docId;
            values[size] = value;
            size++;
        }
    }
}
