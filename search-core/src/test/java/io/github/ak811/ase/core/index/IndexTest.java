package io.github.ak811.ase.core.index;

import io.github.ak811.ase.core.search.SearchHit;
import io.github.ak811.ase.core.search.SearchOptions;
import io.github.ak811.ase.core.search.SearchResult;
import io.github.ak811.ase.core.search.Searcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IndexTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path writeSample(Path path) throws IOException {
        try (IndexWriter writer = new IndexWriter(path, IndexWriterConfig.defaults())) {
            writer.addDocument("https://example.com/a", "Tehran", "Tehran is the capital of Iran. Tehran is large.");
            writer.addDocument("docs/b.txt", "اصفهان", "اصفهان نصف جهان است.");
            writer.addDocument(null, null, "Emoji \uD83D\uDE00 and café.");
            writer.commit();
        }
        return path;
    }

    @Test
    public void storesDocumentsAndPostings() throws IOException {
        Path path = writeSample(temp.getRoot().toPath().resolve("index.idx"));
        try (IndexReader reader = IndexReader.open(path)) {
            assertEquals(3, reader.documentCount());
            assertEquals(new StoredDocument(0, "https://example.com/a", "Tehran",
                    "Tehran is the capital of Iran. Tehran is large."), reader.document(0));
            assertEquals("", reader.document(2).url());
            assertEquals("Emoji \uD83D\uDE00 and café.", reader.document(2).body());

            Postings tehran = reader.postings(reader.termId("tehran"));
            assertEquals(1, tehran.size());
            assertEquals(1, tehran.titleFrequency(0));
            assertEquals(2, tehran.bodyFrequency(0));
            int[] positions = tehran.positions(0);
            assertEquals(0, positions[0]);
            assertTrue("body positions come after the field gap", positions[1] > IndexFormat.FIELD_GAP);

            assertEquals(-1, reader.termId("missing"));
            assertTrue(reader.lexicon().indexOf("cafe") >= 0);
            assertEquals("café", reader.lexicon().display(reader.lexicon().indexOf("cafe")));
            assertTrue(reader.averageDocumentLength() > 0);
        }
    }

    @Test
    public void spilledRunsProduceTheSameIndexAsASinglePass() throws IOException {
        Path small = temp.getRoot().toPath().resolve("small.idx");
        Path large = temp.getRoot().toPath().resolve("large.idx");
        IndexStats spilled = build(small, IndexWriterConfig.defaults().withMemoryBudgetBytes(1 << 20));
        IndexStats single = build(large, IndexWriterConfig.defaults());
        assertTrue("expected several runs, got " + spilled.spilledRuns(), spilled.spilledRuns() > 2);
        assertEquals(1, single.spilledRuns());
        assertEquals(single.terms(), spilled.terms());

        try (IndexReader a = IndexReader.open(small); IndexReader b = IndexReader.open(large)) {
            for (String term : List.of("w1", "w999", "common", "rare7")) {
                Postings pa = a.postings(a.termId(term));
                Postings pb = b.postings(b.termId(term));
                assertEquals(term, pb.size(), pa.size());
                for (int i = 0; i < pa.size(); i++) {
                    assertEquals(pb.documentId(i), pa.documentId(i));
                    assertArrayEquals(pb.positions(i), pa.positions(i));
                }
            }
            Searcher sa = new Searcher(a);
            Searcher sb = new Searcher(b);
            for (String query : List.of("common w5", "\"w10 w11\"", "rare7 -w3")) {
                SearchResult ra = sa.search(query, SearchOptions.defaults().withLimit(50));
                SearchResult rb = sb.search(query, SearchOptions.defaults().withLimit(50));
                assertEquals(query, rb.totalHits(), ra.totalHits());
                assertEquals(query, ids(rb), ids(ra));
            }
        }
    }

    private static List<Integer> ids(SearchResult result) {
        List<Integer> ids = new ArrayList<>();
        for (SearchHit hit : result.hits()) {
            ids.add(hit.documentId());
        }
        return ids;
    }

    private static IndexStats build(Path path, IndexWriterConfig config) throws IOException {
        Random random = new Random(42);
        try (IndexWriter writer = new IndexWriter(path, config)) {
            for (int d = 0; d < 6000; d++) {
                StringBuilder body = new StringBuilder("common ");
                for (int w = 0; w < 40; w++) {
                    body.append('w').append(random.nextInt(2000)).append(' ');
                }
                body.append("rare").append(d % 97);
                writer.addDocument("doc/" + d, "Title " + d, body.toString());
            }
            return writer.commit();
        }
    }

    @Test
    public void closingWithoutCommitLeavesNoFiles() throws IOException {
        Path directory = temp.newFolder("abort").toPath();
        try (IndexWriter writer = new IndexWriter(directory.resolve("index.idx"),
                IndexWriterConfig.defaults().withMemoryBudgetBytes(1 << 20))) {
            for (int i = 0; i < 3000; i++) {
                writer.addDocument("", "t", "word" + i + " other" + (i * 7));
            }
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    public void commitReplacesAnExistingIndexAtomically() throws IOException {
        Path path = writeSample(temp.getRoot().toPath().resolve("index.idx"));
        try (IndexWriter writer = new IndexWriter(path, IndexWriterConfig.defaults())) {
            writer.addDocument("", "Only", "one document");
            writer.commit();
        }
        try (IndexReader reader = IndexReader.open(path)) {
            assertEquals(1, reader.documentCount());
        }
    }

    @Test
    public void emptyIndexIsValid() throws IOException {
        Path path = temp.getRoot().toPath().resolve("empty.idx");
        try (IndexWriter writer = new IndexWriter(path, IndexWriterConfig.defaults())) {
            writer.commit();
        }
        try (IndexReader reader = IndexReader.open(path)) {
            assertEquals(0, reader.documentCount());
            assertEquals(0, new Searcher(reader).search("anything").totalHits());
        }
    }

    @Test
    public void rejectsCorruptAndForeignFiles() throws IOException {
        Path path = writeSample(temp.getRoot().toPath().resolve("index.idx"));
        byte[] original = Files.readAllBytes(path);

        byte[] flipped = original.clone();
        flipped[original.length - IndexFormat.FOOTER_SIZE - 5] ^= 0x55; // inside the checksummed tail
        assertRejected(flipped, "checksum");

        assertRejected(java.util.Arrays.copyOf(original, original.length - 10), "truncated");
        assertRejected("hello, world, this is not an index at all ....................................".getBytes(), "Not a search index");

        byte[] version = original.clone();
        version[7] = 9;
        assertRejected(version, "version");
    }

    private void assertRejected(byte[] data, String expectedMessage) throws IOException {
        Path file = temp.newFile().toPath();
        Files.write(file, data);
        try {
            IndexReader.open(file).close();
            fail("expected rejection: " + expectedMessage);
        } catch (IndexFormatException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
        }
    }

    @Test
    public void writerCannotBeReusedAfterCommit() throws IOException {
        IndexWriter writer = new IndexWriter(temp.getRoot().toPath().resolve("x.idx"), IndexWriterConfig.defaults());
        writer.commit();
        try {
            writer.addDocument("", "", "x");
            fail();
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
        writer.close();
    }
}
