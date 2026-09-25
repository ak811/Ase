package io.github.ak811.ase.core.index;

import io.github.ak811.ase.core.analysis.AnalyzerConfig;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Read access to an index file.
 *
 * <p>Only the term dictionary, the lexicon and one offset and length per document are
 * loaded into memory. Postings and documents stay on disk and are read with positional
 * I/O on demand; recently used postings are kept in an LRU cache. The structural parts
 * of the file are checksummed and validated when it is opened.
 *
 * <p>Thread-safe. Methods throw {@link IOException} if the file becomes unreadable.
 */
public final class IndexReader implements Closeable {
    public static final long DEFAULT_CACHE_BYTES = 64L * 1024 * 1024;

    private final Path path;
    private final FileChannel channel;
    private final long fileSize;
    private final AnalyzerConfig analyzerConfig;
    private final float titleBoost;
    private final long createdAtMillis;

    private final String[] terms;
    private final int[] documentFrequencies;
    private final long[] postingsOffsets;
    private final int[] postingsLengths;
    private final Lexicon lexicon;
    private final long[] recordOffsets;
    private final float[] documentLengths;
    private final float averageDocumentLength;
    private final PostingsCache cache;

    private IndexReader(Path path, FileChannel channel, long cacheBytes) throws IOException {
        this.path = path;
        this.channel = channel;
        this.fileSize = channel.size();
        this.cache = new PostingsCache(cacheBytes);
        if (fileSize < IndexFormat.HEADER_SIZE + IndexFormat.FOOTER_SIZE) {
            throw new IndexFormatException("Not a search index file (too small)");
        }

        IndexFormat.Cursor header = new IndexFormat.Cursor(read(0, IndexFormat.HEADER_SIZE), 0, IndexFormat.HEADER_SIZE);
        if (header.readInt() != IndexFormat.MAGIC) {
            throw new IndexFormatException("Not a search index file");
        }
        int version = header.readInt();
        if (version != IndexFormat.VERSION) {
            throw new IndexFormatException("Unsupported index format version " + version
                    + " (expected " + IndexFormat.VERSION + "); rebuild the index");
        }
        int flags = header.readInt();
        titleBoost = header.readFloat();
        int rulesVersion = header.readInt();
        if (rulesVersion != AnalyzerConfig.RULES_VERSION) {
            throw new IndexFormatException("Index was built with analyzer rules version " + rulesVersion
                    + " (expected " + AnalyzerConfig.RULES_VERSION + "); rebuild the index");
        }
        createdAtMillis = header.readLong();
        analyzerConfig = new AnalyzerConfig((flags & IndexFormat.FLAG_STEMMING) != 0);

        long footerStart = fileSize - IndexFormat.FOOTER_SIZE;
        IndexFormat.Cursor footer = new IndexFormat.Cursor(read(footerStart, IndexFormat.FOOTER_SIZE), 0,
                IndexFormat.FOOTER_SIZE);
        long documentsOffset = footer.readLong();
        long postingsOffset = footer.readLong();
        long dictionaryOffset = footer.readLong();
        long lexiconOffset = footer.readLong();
        long documentTableOffset = footer.readLong();
        long footerOffset = footer.readLong();
        int documentCount = footer.readInt();
        int termCount = footer.readInt();
        int lexiconCount = footer.readInt();
        averageDocumentLength = footer.readFloat();
        int expectedCrc = footer.readInt();
        if (footer.readInt() != IndexFormat.FOOTER_MAGIC) {
            throw new IndexFormatException("Index file is truncated or incomplete");
        }
        if (documentsOffset != IndexFormat.HEADER_SIZE || postingsOffset < documentsOffset
                || dictionaryOffset < postingsOffset || lexiconOffset < dictionaryOffset
                || documentTableOffset < lexiconOffset || footerOffset < documentTableOffset
                || footerOffset != footerStart || documentCount < 0 || termCount < 0 || lexiconCount < 0) {
            throw new IndexFormatException("Index file has an invalid layout");
        }
        long tailLength = footerOffset - dictionaryOffset;
        if (tailLength > Integer.MAX_VALUE - 16) {
            throw new IndexFormatException("Index dictionary is too large to load");
        }
        byte[] tail = read(dictionaryOffset, (int) tailLength);
        CRC32 crc = new CRC32();
        crc.update(tail);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IndexFormatException("Index file is corrupt (checksum mismatch)");
        }

        IndexFormat.Cursor dictionary = new IndexFormat.Cursor(tail, 0, (int) (lexiconOffset - dictionaryOffset));
        if (dictionary.readVarInt() != termCount) {
            throw new IndexFormatException("Index dictionary is inconsistent");
        }
        terms = new String[termCount];
        documentFrequencies = new int[termCount];
        postingsOffsets = new long[termCount];
        postingsLengths = new int[termCount];
        long offset = postingsOffset;
        for (int i = 0; i < termCount; i++) {
            terms[i] = dictionary.readString();
            if (i > 0 && terms[i - 1].compareTo(terms[i]) >= 0) {
                throw new IndexFormatException("Index dictionary is not sorted");
            }
            documentFrequencies[i] = dictionary.readVarInt();
            long length = dictionary.readVarLong();
            if (documentFrequencies[i] <= 0 || documentFrequencies[i] > documentCount || length > Integer.MAX_VALUE) {
                throw new IndexFormatException("Index dictionary entry is invalid");
            }
            postingsOffsets[i] = offset;
            postingsLengths[i] = (int) length;
            offset += length;
        }
        if (offset != dictionaryOffset) {
            throw new IndexFormatException("Index postings section is inconsistent");
        }

        IndexFormat.Cursor lexiconData = new IndexFormat.Cursor(tail, (int) (lexiconOffset - dictionaryOffset),
                (int) (documentTableOffset - dictionaryOffset));
        if (lexiconData.readVarInt() != lexiconCount) {
            throw new IndexFormatException("Index lexicon is inconsistent");
        }
        String[] words = new String[lexiconCount];
        String[] displays = new String[lexiconCount];
        int[] wordFrequencies = new int[lexiconCount];
        for (int i = 0; i < lexiconCount; i++) {
            words[i] = lexiconData.readString();
            String display = lexiconData.readString();
            displays[i] = display.isEmpty() ? null : display;
            wordFrequencies[i] = lexiconData.readVarInt();
            if (i > 0 && words[i - 1].compareTo(words[i]) >= 0) {
                throw new IndexFormatException("Index lexicon is not sorted");
            }
        }
        lexicon = new Lexicon(words, displays, wordFrequencies);

        IndexFormat.Cursor table = new IndexFormat.Cursor(tail, (int) (documentTableOffset - dictionaryOffset),
                (int) tailLength);
        if (table.readVarInt() != documentCount) {
            throw new IndexFormatException("Index document table is inconsistent");
        }
        recordOffsets = new long[documentCount + 1];
        documentLengths = new float[documentCount];
        long recordOffset = documentsOffset;
        for (int i = 0; i < documentCount; i++) {
            recordOffset += table.readVarLong();
            recordOffsets[i] = recordOffset;
            documentLengths[i] = table.readFloat();
            if (i > 0 && recordOffsets[i] <= recordOffsets[i - 1]) {
                throw new IndexFormatException("Index document table is invalid");
            }
        }
        recordOffsets[documentCount] = postingsOffset;
        if (documentCount > 0 && (recordOffsets[0] != documentsOffset || recordOffsets[documentCount - 1] >= postingsOffset)) {
            throw new IndexFormatException("Index document table is invalid");
        }
        if (table.hasRemaining()) {
            throw new IndexFormatException("Index document table has trailing data");
        }
    }

    public static IndexReader open(Path path) throws IOException {
        return open(path, DEFAULT_CACHE_BYTES);
    }

    public static IndexReader open(Path path, long cacheBytes) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new IndexReader(path, channel, cacheBytes);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    public Path path() {
        return path;
    }

    public long fileSize() {
        return fileSize;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public AnalyzerConfig analyzerConfig() {
        return analyzerConfig;
    }

    public float titleBoost() {
        return titleBoost;
    }

    public int documentCount() {
        return documentLengths.length;
    }

    public int termCount() {
        return terms.length;
    }

    public float averageDocumentLength() {
        return averageDocumentLength;
    }

    /** Title-weighted token count, as used by BM25. */
    public float documentLength(int documentId) {
        return documentLengths[documentId];
    }

    public Lexicon lexicon() {
        return lexicon;
    }

    /** Id of {@code term}, or -1 if it is not indexed. */
    public int termId(String term) {
        int index = Arrays.binarySearch(terms, term);
        return index >= 0 ? index : -1;
    }

    public String term(int termId) {
        return terms[termId];
    }

    public int documentFrequency(int termId) {
        return documentFrequencies[termId];
    }

    public Postings postings(int termId) throws IOException {
        Postings cached = cache.get(termId);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = read(postingsOffsets[termId], postingsLengths[termId]);
        Postings postings = Postings.decode(bytes, documentFrequencies[termId], documentCount());
        cache.put(termId, postings);
        return postings;
    }

    public StoredDocument document(int id) throws IOException {
        if (id < 0 || id >= documentCount()) {
            throw new IllegalArgumentException("no document with id " + id);
        }
        long start = recordOffsets[id];
        int length = (int) (recordOffsets[id + 1] - start);
        byte[] stored = read(start, length);
        IndexFormat.Cursor cursor = new IndexFormat.Cursor(stored, 0, stored.length);
        int rawLength = cursor.readVarInt();
        byte[] raw = new byte[rawLength];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(stored, cursor.position(), stored.length - cursor.position());
            int produced = 0;
            while (produced < rawLength) {
                int n = inflater.inflate(raw, produced, rawLength - produced);
                if (n == 0 && (inflater.finished() || inflater.needsInput())) {
                    break;
                }
                produced += n;
            }
            if (produced != rawLength) {
                throw new IndexFormatException("Document " + id + " is corrupt");
            }
        } catch (DataFormatException e) {
            throw new IndexFormatException("Document " + id + " is corrupt", e);
        } finally {
            inflater.end();
        }
        IndexFormat.Cursor fields = new IndexFormat.Cursor(raw, 0, raw.length);
        return new StoredDocument(id, fields.readString(), fields.readString(), fields.readString());
    }

    @Override
    public void close() throws IOException {
        cache.clear();
        channel.close();
    }

    private byte[] read(long position, int length) throws IOException {
        byte[] bytes = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long at = position;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, at);
            if (n < 0) {
                throw new EOFException("Index file is truncated");
            }
            at += n;
        }
        return bytes;
    }

    /** Size-bounded LRU cache of decoded postings. */
    private static final class PostingsCache {
        private final long capacityBytes;
        private long usedBytes;
        private final LinkedHashMap<Integer, Postings> entries = new LinkedHashMap<>(64, 0.75f, true);

        PostingsCache(long capacityBytes) {
            this.capacityBytes = Math.max(0, capacityBytes);
        }

        synchronized Postings get(int termId) {
            return entries.get(termId);
        }

        synchronized void put(int termId, Postings postings) {
            long size = postings.memoryBytes();
            if (size > capacityBytes / 4) {
                return; // one huge list should not evict everything else
            }
            Postings previous = entries.put(termId, postings);
            if (previous != null) {
                usedBytes -= previous.memoryBytes();
            }
            usedBytes += size;
            var iterator = entries.entrySet().iterator();
            while (usedBytes > capacityBytes && iterator.hasNext()) {
                Map.Entry<Integer, Postings> eldest = iterator.next();
                usedBytes -= eldest.getValue().memoryBytes();
                iterator.remove();
            }
        }

        synchronized void clear() {
            entries.clear();
            usedBytes = 0;
        }
    }
}
