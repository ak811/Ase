package io.github.ak811.ase.core.index;

import io.github.ak811.ase.core.analysis.Analyzer;
import io.github.ak811.ase.core.analysis.Token;
import io.github.ak811.ase.core.util.ByteList;
import io.github.ak811.ase.core.util.IntList;
import io.github.ak811.ase.core.util.LongList;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Deflater;

/**
 * Builds an index file with bounded memory.
 *
 * <p>Documents are compressed and streamed to the output as they arrive. Postings are
 * buffered in memory; when the buffer exceeds {@link IndexWriterConfig#memoryBudgetBytes()}
 * it is written to a sorted temporary run. {@link #commit()} merges the runs, writes the
 * dictionary, lexicon and document table, syncs the file to disk and atomically moves it
 * into place, so readers never observe a partially written index.
 *
 * <pre>{@code
 * try (IndexWriter writer = new IndexWriter(path, IndexWriterConfig.defaults())) {
 *     writer.addDocument("https://example.com", "Title", "Body text");
 *     writer.commit();
 * }
 * }</pre>
 *
 * Not thread-safe. Closing without committing deletes all temporary files.
 */
public final class IndexWriter implements Closeable {
    private static final int TERM_OVERHEAD_BYTES = 96;
    private static final int LEXICON_ENTRY_OVERHEAD_BYTES = 120;

    private final Path output;
    private final Path directory;
    private final Path tempFile;
    private final IndexWriterConfig config;
    private final Analyzer analyzer;
    private final FileOutputStream file;
    private final CountingOutputStream counter;
    private final DataOutputStream data;

    private final Map<String, TermBuffer> buffer = new HashMap<>();
    private long bufferedBytes;
    private final List<Path> runs = new ArrayList<>();

    private final Map<String, LexiconEntry> lexicon = new HashMap<>();
    private int lexiconPruneLevel;

    private final LongList recordOffsets = new LongList();
    private float[] documentLengths = new float[1024];
    private double totalLength;
    private int documentCount;

    private final Deflater deflater = new Deflater(6);
    private final ByteList record = new ByteList(4096);
    private final ByteList scratch = new ByteList(256);
    private byte[] compressed = new byte[4096];

    private boolean committed;
    private boolean closed;

    public IndexWriter(Path output, IndexWriterConfig config) throws IOException {
        this.output = output.toAbsolutePath();
        this.directory = this.output.getParent();
        this.config = config;
        this.analyzer = new Analyzer(config.analyzer());
        Files.createDirectories(directory);
        this.tempFile = Files.createTempFile(directory, this.output.getFileName() + ".", ".tmp");
        this.file = new FileOutputStream(tempFile.toFile());
        this.counter = new CountingOutputStream(new BufferedOutputStream(file, 1 << 16));
        this.data = new DataOutputStream(counter);
        try {
            writeHeader();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    public int documentCount() {
        return documentCount;
    }

    /** Adds a document and returns its id. {@code null} fields are treated as empty. */
    public int addDocument(String url, String title, String body) throws IOException {
        ensureWritable();
        if (documentCount == Integer.MAX_VALUE - 1) {
            throw new IllegalStateException("too many documents");
        }
        url = clean(url);
        title = clean(title);
        body = clean(body);
        int id = documentCount;

        recordOffsets.add(counter.count());
        writeRecord(url, title, body);

        List<Token> titleTokens = analyzer.analyze(title, 0);
        int bodyStart = titleTokens.isEmpty() ? 0
                : titleTokens.get(titleTokens.size() - 1).position() + 1 + IndexFormat.FIELD_GAP;
        List<Token> bodyTokens = analyzer.analyze(body, bodyStart);

        float length = config.titleBoost() * titleTokens.size() + bodyTokens.size();
        if (documentCount == documentLengths.length) {
            documentLengths = Arrays.copyOf(documentLengths, documentLengths.length * 2);
        }
        documentLengths[documentCount] = length;
        totalLength += length;
        documentCount++;

        Map<String, DocumentTerm> terms = new HashMap<>();
        for (Token token : titleTokens) {
            DocumentTerm term = terms.computeIfAbsent(token.term(), t -> new DocumentTerm());
            term.titleFrequency++;
            term.positions.add(token.position());
        }
        for (Token token : bodyTokens) {
            DocumentTerm term = terms.computeIfAbsent(token.term(), t -> new DocumentTerm());
            term.bodyFrequency++;
            term.positions.add(token.position());
        }
        for (Map.Entry<String, DocumentTerm> entry : terms.entrySet()) {
            TermBuffer termBuffer = buffer.get(entry.getKey());
            if (termBuffer == null) {
                termBuffer = new TermBuffer();
                buffer.put(entry.getKey(), termBuffer);
                bufferedBytes += TERM_OVERHEAD_BYTES + 2L * entry.getKey().length();
            }
            int before = termBuffer.bytes.capacity();
            termBuffer.add(id, entry.getValue(), scratch);
            bufferedBytes += termBuffer.bytes.capacity() - before;
        }

        Set<String> seen = new HashSet<>();
        addToLexicon(title, titleTokens, seen);
        addToLexicon(body, bodyTokens, seen);

        if (bufferedBytes + (long) lexicon.size() * LEXICON_ENTRY_OVERHEAD_BYTES > config.memoryBudgetBytes()) {
            flushRun();
        }
        return id;
    }

    /** Finishes the index and moves it to its final path. The writer cannot be used afterwards. */
    public IndexStats commit() throws IOException {
        ensureWritable();
        committed = true;
        try {
            flushRun();
            long postingsOffset = counter.count();
            Dictionary dictionary = mergeRuns();

            CRC32 crc = new CRC32();
            DataOutputStream tail = new DataOutputStream(new CheckedOutputStream(counter, crc));

            long dictionaryOffset = counter.count();
            IndexFormat.writeVarInt(tail, dictionary.terms.size());
            for (int i = 0; i < dictionary.terms.size(); i++) {
                IndexFormat.writeString(tail, dictionary.terms.get(i));
                IndexFormat.writeVarInt(tail, dictionary.frequencies.get(i));
                IndexFormat.writeVarLong(tail, dictionary.lengths.get(i));
            }

            long lexiconOffset = counter.count();
            List<String> words = new ArrayList<>(lexicon.keySet());
            Collections.sort(words);
            IndexFormat.writeVarInt(tail, words.size());
            for (String word : words) {
                LexiconEntry entry = lexicon.get(word);
                IndexFormat.writeString(tail, word);
                IndexFormat.writeString(tail, entry.display.equals(word) ? "" : entry.display);
                IndexFormat.writeVarInt(tail, entry.documentFrequency);
            }

            long documentTableOffset = counter.count();
            IndexFormat.writeVarInt(tail, documentCount);
            long previous = IndexFormat.HEADER_SIZE;
            for (int i = 0; i < documentCount; i++) {
                long offset = recordOffsets.get(i);
                IndexFormat.writeVarLong(tail, offset - previous);
                tail.writeFloat(documentLengths[i]);
                previous = offset;
            }
            tail.flush();

            long footerOffset = counter.count();
            data.writeLong(IndexFormat.HEADER_SIZE);
            data.writeLong(postingsOffset);
            data.writeLong(dictionaryOffset);
            data.writeLong(lexiconOffset);
            data.writeLong(documentTableOffset);
            data.writeLong(footerOffset);
            data.writeInt(documentCount);
            data.writeInt(dictionary.terms.size());
            data.writeInt(words.size());
            data.writeFloat(documentCount == 0 ? 0f : (float) (totalLength / documentCount));
            data.writeInt((int) crc.getValue());
            data.writeInt(IndexFormat.FOOTER_MAGIC);
            data.flush();
            file.getFD().sync();
            data.close();

            try {
                Files.move(tempFile, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, output, StandardCopyOption.REPLACE_EXISTING);
            }
            return new IndexStats(documentCount, dictionary.terms.size(), words.size(), Files.size(output),
                    runs.size());
        } finally {
            close();
        }
    }

    /** Releases resources. If {@link #commit()} was not called, the partial index is deleted. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        deflater.end();
        try {
            data.close();
        } catch (IOException ignored) {
            // The file is being discarded or was already closed.
        } finally {
            Files.deleteIfExists(tempFile);
            for (Path run : runs) {
                Files.deleteIfExists(run);
            }
            buffer.clear();
            lexicon.clear();
        }
    }

    // ---------------------------------------------------------------- writing

    private void writeHeader() throws IOException {
        data.writeInt(IndexFormat.MAGIC);
        data.writeInt(IndexFormat.VERSION);
        data.writeInt(config.analyzer().stemming() ? IndexFormat.FLAG_STEMMING : 0);
        data.writeFloat(config.titleBoost());
        data.writeInt(io.github.ak811.ase.core.analysis.AnalyzerConfig.RULES_VERSION);
        data.writeLong(System.currentTimeMillis());
    }

    private void writeRecord(String url, String title, String body) throws IOException {
        record.clear();
        IndexFormat.writeString(record, url);
        IndexFormat.writeString(record, title);
        IndexFormat.writeString(record, body);

        deflater.reset();
        deflater.setInput(record.array(), 0, record.size());
        deflater.finish();
        int length = 0;
        while (!deflater.finished()) {
            if (length == compressed.length) {
                compressed = Arrays.copyOf(compressed, compressed.length * 2);
            }
            length += deflater.deflate(compressed, length, compressed.length - length);
        }
        IndexFormat.writeVarInt(data, record.size());
        data.write(compressed, 0, length);
    }

    private void addToLexicon(String text, List<Token> tokens, Set<String> seen) {
        for (Token token : tokens) {
            if (token.group().usesBigrams() || !hasLetter(token.surface()) || !seen.add(token.surface())) {
                continue;
            }
            LexiconEntry entry = lexicon.get(token.surface());
            if (entry == null) {
                entry = new LexiconEntry(displayForm(text.substring(token.start(), token.end())));
                lexicon.put(token.surface(), entry);
            }
            entry.documentFrequency++;
        }
        while (lexicon.size() > config.maxLexiconWords()) {
            lexiconPruneLevel++;
            int level = lexiconPruneLevel;
            lexicon.values().removeIf(entry -> entry.documentFrequency <= level);
        }
    }

    /** How a word is shown in suggestions: lower-case, composed, joined Persian affixes use a ZWNJ. */
    private static String displayForm(String raw) {
        String composed = Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return composed.replaceAll("[ \\u00A0\\t]+", "\u200C");
    }

    private void flushRun() throws IOException {
        if (buffer.isEmpty()) {
            return;
        }
        Path run = Files.createTempFile(directory, output.getFileName() + ".", ".run");
        runs.add(run);
        List<String> terms = new ArrayList<>(buffer.keySet());
        Collections.sort(terms);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(run), 1 << 16))) {
            IndexFormat.writeVarInt(out, terms.size());
            for (String term : terms) {
                TermBuffer termBuffer = buffer.get(term);
                IndexFormat.writeString(out, term);
                IndexFormat.writeVarInt(out, termBuffer.documentFrequency);
                IndexFormat.writeVarInt(out, termBuffer.lastDocument);
                IndexFormat.writeVarInt(out, termBuffer.bytes.size());
                out.write(termBuffer.bytes.array(), 0, termBuffer.bytes.size());
            }
        }
        buffer.clear();
        bufferedBytes = 0;
    }

    /** K-way merge of the sorted runs into the postings section. */
    private Dictionary mergeRuns() throws IOException {
        Dictionary dictionary = new Dictionary();
        List<RunReader> readers = new ArrayList<>();
        PriorityQueue<RunReader> queue = new PriorityQueue<>(
                Comparator.comparing((RunReader r) -> r.term).thenComparingInt(r -> r.index));
        try {
            for (int i = 0; i < runs.size(); i++) {
                RunReader reader = new RunReader(i, runs.get(i));
                readers.add(reader);
                if (reader.advance()) {
                    queue.add(reader);
                }
            }
            ByteList delta = new ByteList(8);
            while (!queue.isEmpty()) {
                RunReader reader = queue.poll();
                String term = reader.term;
                long length = 0;
                int frequency = 0;
                int lastDocument = -1;
                while (true) {
                    if (lastDocument < 0) {
                        data.write(reader.bytes, 0, reader.length);
                        length += reader.length;
                    } else {
                        // Each run encodes its first document relative to -1; re-base it on the previous run.
                        IndexFormat.Cursor cursor = new IndexFormat.Cursor(reader.bytes, 0, reader.length);
                        int firstDocument = cursor.readVarInt() - 1;
                        delta.clear();
                        delta.addVarInt(firstDocument - lastDocument);
                        data.write(delta.array(), 0, delta.size());
                        data.write(reader.bytes, cursor.position(), reader.length - cursor.position());
                        length += delta.size() + reader.length - cursor.position();
                    }
                    frequency += reader.documentFrequency;
                    lastDocument = reader.lastDocument;
                    if (reader.advance()) {
                        queue.add(reader);
                    }
                    if (queue.isEmpty() || !queue.peek().term.equals(term)) {
                        break;
                    }
                    reader = queue.poll();
                }
                dictionary.add(term, frequency, length);
            }
        } finally {
            for (RunReader reader : readers) {
                reader.close();
            }
        }
        return dictionary;
    }

    private void ensureWritable() {
        if (committed || closed) {
            throw new IllegalStateException("the writer has been committed or closed");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean hasLetter(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- helper types

    private static final class DocumentTerm {
        int titleFrequency;
        int bodyFrequency;
        final IntList positions = new IntList(4);
    }

    private static final class TermBuffer {
        final ByteList bytes = new ByteList(16);
        int documentFrequency;
        int lastDocument = -1;

        void add(int document, DocumentTerm term, ByteList scratch) {
            bytes.addVarInt(document - lastDocument);
            bytes.addVarInt(term.titleFrequency);
            bytes.addVarInt(term.bodyFrequency);
            scratch.clear();
            int previous = 0;
            for (int i = 0; i < term.positions.size(); i++) {
                int position = term.positions.get(i);
                scratch.addVarInt(i == 0 ? position : position - previous);
                previous = position;
            }
            bytes.addVarInt(scratch.size());
            bytes.addAll(scratch);
            lastDocument = document;
            documentFrequency++;
        }
    }

    private static final class LexiconEntry {
        final String display;
        int documentFrequency;

        LexiconEntry(String display) {
            this.display = display;
        }
    }

    private static final class Dictionary {
        final List<String> terms = new ArrayList<>();
        final IntList frequencies = new IntList();
        final LongList lengths = new LongList();

        void add(String term, int frequency, long length) {
            if (length > Integer.MAX_VALUE) {
                throw new IllegalStateException("postings for '" + term + "' exceed 2 GiB");
            }
            terms.add(term);
            frequencies.add(frequency);
            lengths.add(length);
        }
    }

    private static final class RunReader implements Closeable {
        final int index;
        private final DataInputStream in;
        private int remaining;
        String term;
        int documentFrequency;
        int lastDocument;
        byte[] bytes = new byte[256];
        int length;

        RunReader(int index, Path path) throws IOException {
            this.index = index;
            this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16));
            this.remaining = readVarInt();
        }

        boolean advance() throws IOException {
            if (remaining == 0) {
                return false;
            }
            remaining--;
            int termLength = readVarInt();
            byte[] termBytes = new byte[termLength];
            in.readFully(termBytes);
            term = new String(termBytes, StandardCharsets.UTF_8);
            documentFrequency = readVarInt();
            lastDocument = readVarInt();
            length = readVarInt();
            if (bytes.length < length) {
                bytes = new byte[Math.max(length, bytes.length * 2)];
            }
            in.readFully(bytes, 0, length);
            return true;
        }

        private int readVarInt() throws IOException {
            int result = 0;
            for (int shift = 0; shift < 32; shift += 7) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("truncated run file");
                }
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new IOException("malformed run file");
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
