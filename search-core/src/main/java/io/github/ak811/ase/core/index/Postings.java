package io.github.ak811.ase.core.index;

import java.util.Arrays;

/**
 * The documents containing one term, in ascending id order, with per-field frequencies.
 * Positions are decoded lazily because most queries never need them.
 */
public final class Postings {
    private final byte[] data;
    private final int[] documentIds;
    private final int[] titleFrequencies;
    private final int[] bodyFrequencies;
    private final int[] positionsStart;
    private final int[] positionsEnd;

    private Postings(byte[] data, int size) {
        this.data = data;
        this.documentIds = new int[size];
        this.titleFrequencies = new int[size];
        this.bodyFrequencies = new int[size];
        this.positionsStart = new int[size];
        this.positionsEnd = new int[size];
    }

    static Postings decode(byte[] data, int documentFrequency, int documentCount) throws IndexFormatException {
        Postings postings = new Postings(data, documentFrequency);
        IndexFormat.Cursor cursor = new IndexFormat.Cursor(data, 0, data.length);
        int previous = -1;
        for (int i = 0; i < documentFrequency; i++) {
            int delta = cursor.readVarInt();
            int document = previous + delta;
            if (delta <= 0 || document >= documentCount || document < 0) {
                throw new IndexFormatException("corrupt postings: bad document id");
            }
            postings.documentIds[i] = document;
            postings.titleFrequencies[i] = cursor.readVarInt();
            postings.bodyFrequencies[i] = cursor.readVarInt();
            int positionsLength = cursor.readVarInt();
            postings.positionsStart[i] = cursor.position();
            cursor.skip(positionsLength);
            postings.positionsEnd[i] = cursor.position();
            previous = document;
        }
        if (cursor.hasRemaining()) {
            throw new IndexFormatException("corrupt postings: trailing bytes");
        }
        return postings;
    }

    public int size() {
        return documentIds.length;
    }

    public int documentId(int index) {
        return documentIds[index];
    }

    public int titleFrequency(int index) {
        return titleFrequencies[index];
    }

    public int bodyFrequency(int index) {
        return bodyFrequencies[index];
    }

    /** Index of {@code documentId} in this list, or a negative value if absent. */
    public int find(int documentId) {
        return Arrays.binarySearch(documentIds, documentId);
    }

    /** Word positions of the term in the document at {@code index}, ascending. */
    public int[] positions(int index) {
        int count = titleFrequencies[index] + bodyFrequencies[index];
        int[] positions = new int[count];
        IndexFormat.Cursor cursor = new IndexFormat.Cursor(data, positionsStart[index], positionsEnd[index]);
        try {
            int previous = 0;
            for (int i = 0; i < count; i++) {
                int value = cursor.readVarInt();
                previous = i == 0 ? value : previous + value;
                positions[i] = previous;
            }
        } catch (IndexFormatException e) {
            throw new IllegalStateException("corrupt positions", e);
        }
        return positions;
    }

    long memoryBytes() {
        return data.length + 20L * documentIds.length + 64;
    }
}
