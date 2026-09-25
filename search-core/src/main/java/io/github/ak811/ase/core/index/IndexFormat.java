package io.github.ak811.ase.core.index;

import io.github.ak811.ase.core.util.ByteList;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Constants and encoding helpers for the index file.
 *
 * <pre>
 * header   int magic "ASEI", int version, int flags, float titleBoost,
 *          int analyzer rules version, long created-at millis                     (28 bytes)
 * docs     per document: varint raw length, deflate(url, title, body)
 * postings per term, sorted by term: per document
 *          varint docId delta, varint title tf, varint body tf,
 *          varint positions byte length, positions (delta varints)
 * dict     varint count; per term: string, varint df, varlong postings length
 * lexicon  varint count; per word: string word, string display ("" = same), varint df
 * docTable varint count; per document: varlong record-offset delta, float length
 * footer   long offsets (docs, postings, dict, lexicon, docTable, footer),
 *          int documents, int terms, int lexicon words, float average length,
 *          int CRC-32 of dict+lexicon+docTable, int magic "ASEF"                 (72 bytes)
 * </pre>
 * Strings are a varint byte length followed by UTF-8. Multi-byte numbers are big-endian.
 */
final class IndexFormat {
    static final int MAGIC = 0x41534549;        // "ASEI"
    static final int FOOTER_MAGIC = 0x41534546; // "ASEF"
    static final int VERSION = 2;
    static final int HEADER_SIZE = 28;
    static final int FOOTER_SIZE = 72;
    static final int FLAG_STEMMING = 1;
    /** Position gap between title and body, so phrases never span the two fields. */
    static final int FIELD_GAP = 16;

    private IndexFormat() {
    }

    static void writeVarInt(DataOutput out, int value) throws IOException {
        writeVarLong(out, value);
    }

    static void writeVarLong(DataOutput out, long value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("negative varint: " + value);
        }
        while ((value & ~0x7FL) != 0) {
            out.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    static void writeString(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    static void writeString(ByteList out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.addVarInt(bytes.length);
        out.addAll(bytes, 0, bytes.length);
    }

    /** Sequential reader over a byte array; every overrun is reported as a format error. */
    static final class Cursor {
        private final byte[] data;
        private int position;
        private final int limit;

        Cursor(byte[] data, int position, int limit) {
            this.data = data;
            this.position = position;
            this.limit = limit;
        }

        int position() {
            return position;
        }

        boolean hasRemaining() {
            return position < limit;
        }

        int readVarInt() throws IndexFormatException {
            long value = readVarLong();
            if (value > Integer.MAX_VALUE) {
                throw new IndexFormatException("varint out of range");
            }
            return (int) value;
        }

        long readVarLong() throws IndexFormatException {
            long result = 0;
            for (int shift = 0; shift < 63; shift += 7) {
                if (position >= limit) {
                    throw new IndexFormatException("unexpected end of data");
                }
                int b = data[position++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new IndexFormatException("malformed varint");
        }

        float readFloat() throws IndexFormatException {
            return Float.intBitsToFloat(readInt());
        }

        int readInt() throws IndexFormatException {
            require(4);
            int value = ((data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            position += 4;
            return value;
        }

        long readLong() throws IndexFormatException {
            long high = readInt() & 0xFFFFFFFFL;
            long low = readInt() & 0xFFFFFFFFL;
            return (high << 32) | low;
        }

        String readString() throws IndexFormatException {
            int length = readVarInt();
            require(length);
            String value = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }

        void skip(int length) throws IndexFormatException {
            require(length);
            position += length;
        }

        private void require(int length) throws IndexFormatException {
            if (length < 0 || length > limit - position) {
                throw new IndexFormatException("unexpected end of data");
            }
        }
    }
}
