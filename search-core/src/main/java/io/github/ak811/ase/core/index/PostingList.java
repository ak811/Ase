package io.github.ak811.ase.core.index;

/**
 * Documents containing one term, sorted by ascending document id, with the
 * term's length-normalized TF-IDF weight in each document.
 */
public final class PostingList {
    private final int[] docIds;
    private final float[] weights;

    PostingList(int[] docIds, float[] weights) {
        if (docIds.length != weights.length) {
            throw new IllegalArgumentException("docIds and weights differ in length");
        }
        this.docIds = docIds;
        this.weights = weights;
    }

    /** Document frequency of the term. */
    public int size() {
        return docIds.length;
    }

    public int docId(int index) {
        return docIds[index];
    }

    public float weight(int index) {
        return weights[index];
    }
}
