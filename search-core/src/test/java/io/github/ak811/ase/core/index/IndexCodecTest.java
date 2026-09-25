package io.github.ak811.ase.core.index;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class IndexCodecTest {

    private static SearchIndex sampleIndex() {
        IndexBuilder builder = new IndexBuilder();
        builder.addDocument("https://example.com/a", "تهران", "تهران پایتخت ایران است.");
        builder.addDocument("docs/b.txt", "اصفهان", "اصفهان نصف جهان است.");
        builder.addDocument("", "", "Plain English body with emoji \uD83D\uDE00.");
        return builder.build();
    }

    private static byte[] encode(SearchIndex index) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IndexCodec.write(index, out);
        return out.toByteArray();
    }

    @Test
    public void roundTripPreservesDocumentsAndPostings() throws IOException {
        SearchIndex original = sampleIndex();
        SearchIndex copy = IndexCodec.read(new ByteArrayInputStream(encode(original)));

        assertEquals(original.documentCount(), copy.documentCount());
        for (int id = 0; id < original.documentCount(); id++) {
            assertEquals(original.document(id), copy.document(id));
        }
        assertEquals(original.vocabulary(), copy.vocabulary());
        for (String term : original.vocabulary()) {
            PostingList a = original.postings(term);
            PostingList b = copy.postings(term);
            assertEquals(a.size(), b.size());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.docId(i), b.docId(i));
                assertEquals(a.weight(i), b.weight(i), 0f);
            }
        }
    }

    @Test
    public void outputIsDeterministic() throws IOException {
        assertEquals(Arrays.toString(encode(sampleIndex())), Arrays.toString(encode(sampleIndex())));
    }

    @Test
    public void rejectsNonGzipData() {
        assertFormatError(new byte[]{1, 2, 3, 4, 5});
    }

    @Test
    public void rejectsWrongMagic() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 1});
        }
        assertFormatError(bytes.toByteArray());
    }

    @Test
    public void rejectsTruncatedData() throws IOException {
        byte[] full = encode(sampleIndex());
        assertFormatError(Arrays.copyOf(full, full.length / 2));
    }

    private static void assertFormatError(byte[] data) {
        try {
            IndexCodec.read(new ByteArrayInputStream(data));
            fail("expected an IOException");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
    }
}
