package io.github.ak811.ase.core.util;

import java.util.Arrays;

/** A growable byte array with variable-length integer encoding. */
public final class ByteList {
    private byte[] bytes;
    private int size;

    public ByteList() {
        this(16);
    }

    public ByteList(int initialCapacity) {
        bytes = new byte[Math.max(1, initialCapacity)];
    }

    public void add(byte value) {
        ensure(1);
        bytes[size++] = value;
    }

    public void addAll(byte[] source, int offset, int length) {
        ensure(length);
        System.arraycopy(source, offset, bytes, size, length);
        size += length;
    }

    public void addAll(ByteList other) {
        addAll(other.bytes, 0, other.size);
    }

    /** Appends a non-negative int as an unsigned LEB128 varint. */
    public void addVarInt(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative varint: " + value);
        }
        while ((value & ~0x7F) != 0) {
            add((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        add((byte) value);
    }

    public int size() {
        return size;
    }

    public void clear() {
        size = 0;
    }

    public byte[] array() {
        return bytes;
    }

    public byte[] toArray() {
        return Arrays.copyOf(bytes, size);
    }

    /** Heap bytes currently reserved, used for memory accounting. */
    public int capacity() {
        return bytes.length;
    }

    private void ensure(int extra) {
        int needed = size + extra;
        if (needed < 0) {
            throw new OutOfMemoryError("ByteList too large");
        }
        if (needed > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(needed, grow(bytes.length)));
        }
    }

    static int grow(int capacity) {
        int next = capacity + (capacity >> 1) + 8;
        if (next < 0) {
            throw new OutOfMemoryError("capacity overflow");
        }
        return next;
    }
}
