package io.github.ak811.ase.core.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An immutable inverted index. Create one with {@link IndexBuilder} or load one
 * with {@link IndexCodec#read}. Safe to share between threads.
 */
public final class SearchIndex {
    private final List<Document> documents;
    private final Map<String, PostingList> postings;

    SearchIndex(List<Document> documents, Map<String, PostingList> postings) {
        this.documents = Collections.unmodifiableList(new ArrayList<>(documents));
        this.postings = Collections.unmodifiableMap(postings);
    }

    public int documentCount() {
        return documents.size();
    }

    public int termCount() {
        return postings.size();
    }

    public Document document(int id) {
        return documents.get(id);
    }

    /** Returns the postings for a normalized term, or {@code null} if the term is not indexed. */
    public PostingList postings(String term) {
        return postings.get(term);
    }

    public boolean contains(String term) {
        return postings.containsKey(term);
    }

    public int documentFrequency(String term) {
        PostingList list = postings.get(term);
        return list == null ? 0 : list.size();
    }

    /** All indexed terms (unmodifiable). */
    public Set<String> vocabulary() {
        return postings.keySet();
    }

    List<Document> documents() {
        return documents;
    }
}
