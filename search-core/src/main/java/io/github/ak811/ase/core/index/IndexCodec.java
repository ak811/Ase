package io.github.ak811.ase.core.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * Reads and writes the versioned binary index format.
 *
 * <pre>
 * gzip(
 *   int32   magic "ASEI"
 *   int32   format version
 *   varint  document count N
 *   N ×     { string url, string title, string body }       ids are implicit 0..N-1
 *   varint  term count T
 *   T ×     { string term, varint df,
 *             df × varint docId delta (first value absolute),
 *             df × float32 weight }                          terms sorted, output is deterministic
 * )
 * string = varint byte length + UTF-8 bytes
 * </pre>
 *
 * Unlike Java serialization the format is compact, fast to load, independent of
 * class names (so R8/ProGuard can rename freely), and validated on read.
 */
public final class IndexCodec {
    public static final int FORMAT_VERSION = 1;

    private static final int MAGIC = 0x41534549; // "ASEI"
    private static final int BUFFER_SIZE = 1 << 16;
    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;

    private IndexCodec() {
    }

    /** Writes the index to {@code out}. The stream is closed when this method returns. */
    public static void write(SearchIndex index, OutputStream out) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(out, BUFFER_SIZE);
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(gzip, BUFFER_SIZE))) {
            data.writeInt(MAGIC);
            data.writeInt(FORMAT_VERSION);

            List<Document> documents = index.documents();
            writeVarInt(data, documents.size());
            for (Document document : documents) {
                writeString(data, document.url());
                writeString(data, document.title());
                writeString(data, document.body());
            }

            List<String> terms = new ArrayList<>(index.vocabulary());
            Collections.sort(terms);
            writeVarInt(data, terms.size());
            for (String term : terms) {
                PostingList postings = index.postings(term);
                writeString(data, term);
                writeVarInt(data, postings.size());
                int previous = 0;
                for (int i = 0; i < postings.size(); i++) {
                    int docId = postings.docId(i);
                    writeVarInt(data, i == 0 ? docId : docId - previous);
                    previous = docId;
                }
                for (int i = 0; i < postings.size(); i++) {
                    data.writeFloat(postings.weight(i));
                }
            }
        }
    }

    /**
     * Reads an index from {@code in}. The stream is closed when this method returns.
     *
     * @throws IndexFormatException if the data is not a valid index of a supported version
     */
    public static SearchIndex read(InputStream in) throws IOException {
        GZIPInputStream gzip;
        try {
            gzip = new GZIPInputStream(in, BUFFER_SIZE);
        } catch (ZipException | EOFException e) {
            closeQuietly(in);
            throw new IndexFormatException("Not a search index file", e);
        } catch (IOException e) {
            closeQuietly(in);
            throw e;
        }
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(gzip, BUFFER_SIZE))) {
            return readIndex(data);
        } catch (EOFException e) {
            throw new IndexFormatException("Index file is truncated", e);
        } catch (ZipException e) {
            throw new IndexFormatException("Index file is corrupt", e);
        }
    }

    private static SearchIndex readIndex(DataInputStream data) throws IOException {
        if (data.readInt() != MAGIC) {
            throw new IndexFormatException("Not a search index file");
        }
        int version = data.readInt();
        if (version != FORMAT_VERSION) {
            throw new IndexFormatException("Unsupported index format version " + version
                    + " (this build reads version " + FORMAT_VERSION + "); rebuild the index with the matching indexer");
        }

        int documentCount = readVarInt(data);
        List<Document> documents = new ArrayList<>(Math.min(documentCount, 1 << 16));
        for (int id = 0; id < documentCount; id++) {
            String url = readString(data);
            String title = readString(data);
            String body = readString(data);
            documents.add(new Document(id, url, title, body));
        }

        int termCount = readVarInt(data);
        Map<String, PostingList> postings = new HashMap<>(Math.min(termCount, 1 << 22) * 4 / 3 + 1);
        for (int t = 0; t < termCount; t++) {
            String term = readString(data);
            int df = readVarInt(data);
            if (df <= 0 || df > documentCount) {
                throw new IndexFormatException("Invalid document frequency " + df + " for term '" + term + "'");
            }
            int[] docIds = new int[df];
            int previous = -1;
            for (int i = 0; i < df; i++) {
                int delta = readVarInt(data);
                int docId = i == 0 ? delta : previous + delta;
                if (docId <= previous || docId >= documentCount) {
                    throw new IndexFormatException("Invalid posting list for term '" + term + "'");
                }
                docIds[i] = docId;
                previous = docId;
            }
            float[] weights = new float[df];
            for (int i = 0; i < df; i++) {
                weights[i] = data.readFloat();
            }
            if (postings.put(term, new PostingList(docIds, weights)) != null) {
                throw new IndexFormatException("Duplicate term '" + term + "'");
            }
        }

        if (data.read() != -1) {
            throw new IndexFormatException("Unexpected data after the end of the index");
        }
        return new SearchIndex(documents, postings);
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("negative varint: " + value);
        }
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (result < 0) {
                    throw new IndexFormatException("Varint out of range");
                }
                return result;
            }
        }
        throw new IndexFormatException("Malformed varint");
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length > MAX_STRING_BYTES) {
            throw new IndexFormatException("String of " + length + " bytes exceeds the limit");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Already failing; the original exception is more useful.
        }
    }
}
