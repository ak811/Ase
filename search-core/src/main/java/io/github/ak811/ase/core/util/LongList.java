package io.github.ak811.ase.core.util;

import java.util.Arrays;

/** A growable {@code long} array. */
public final class LongList {
    private long[] values = new long[16];
    private int size;

    public void add(long value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, ByteList.grow(values.length));
        }
        values[size++] = value;
    }

    public long get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        }
        return values[index];
    }

    public int size() {
        return size;
    }
}
