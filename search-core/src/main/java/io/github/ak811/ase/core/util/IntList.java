package io.github.ak811.ase.core.util;

import java.util.Arrays;

/** A growable {@code int} array that avoids boxing in hot paths. */
public final class IntList {
    private int[] values;
    private int size;

    public IntList() {
        this(8);
    }

    public IntList(int initialCapacity) {
        values = new int[Math.max(1, initialCapacity)];
    }

    public void add(int value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, ByteList.grow(values.length));
        }
        values[size++] = value;
    }

    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
        return values[index];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
    }

    public int[] toArray() {
        return Arrays.copyOf(values, size);
    }
}
