package io.github.ak811.ase.indexer;

import io.github.ak811.ase.core.index.IndexCodec;
import io.github.ak811.ase.core.index.SearchIndex;
import io.github.ak811.ase.core.search.SearchResult;
import io.github.ak811.ase.core.search.Searcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndexerCliTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String WEBIR_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<ROOT>\n"
            + "  <DOC><URL>https://example.com/a</URL>"
            + "<HTML><![CDATA[<html><head><title>تهران &amp; البرز</title><script>var x = 'نامرئی';</script></head>"
            + "<body><p>تهران پایتخت ایران است.</p></body></html>]]></HTML></DOC>\n"
            + "  <DOC><URL>https://example.com/b</URL>"
            + "<HTML>&lt;html&gt;&lt;title&gt;اصفهان&lt;/title&gt;&lt;body&gt;نصف جهان&lt;/body&gt;&lt;/html&gt;</HTML></DOC>\n"
            + "  <DOC><URL>https://example.com/empty</URL><HTML></HTML></DOC>\n"
            + "</ROOT>\n";

    @Test
    public void indexesWebIrXmlHtmlAndTextFiles() throws IOException {
        File corpus = temp.newFolder("corpus");
        write(new File(corpus, "WebIR-001.xml"), WEBIR_XML);
        write(new File(corpus, "shiraz.html"),
                "<html><head><meta charset=utf-8><title>شیراز</title></head><body>حافظیه در شیراز است</body></html>");
        write(new File(corpus, "notes/tabriz.txt"), "\uFEFF\n  تبریز  \nتبریز شهری در شمال‌غرب ایران است.\n");
        File output = new File(temp.getRoot(), "out/index.bin");

        Result result = runCli("--input", corpus.getPath(), "--output", output.getPath());
        assertEquals(result.err, IndexerCli.EXIT_OK, result.code);
        assertTrue(result.out.contains("Indexed 4 documents"));
        assertTrue(result.out.contains("Skipped 1 documents without any text"));

        SearchIndex index;
        try (InputStream in = Files.newInputStream(output.toPath())) {
            index = IndexCodec.read(in);
        }
        Searcher searcher = new Searcher(index);

        SearchResult tehran = searcher.search("تهران");
        assertEquals(1, tehran.totalHits());
        assertEquals("https://example.com/a", tehran.hits().get(0).document().url());
        assertEquals("تهران & البرز", tehran.hits().get(0).document().title());

        assertEquals("script contents must not be indexed", 0, searcher.search("نامرئی").totalHits());
        assertEquals(1, searcher.search("اصفهان").totalHits());
        assertEquals("shiraz.html", searcher.search("حافظیه").hits().get(0).document().url());

        SearchResult tabriz = searcher.search("تبریز");
        assertEquals("notes/tabriz.txt", tabriz.hits().get(0).document().url());
        assertEquals("تبریز", tabriz.hits().get(0).document().title());
        assertEquals("تبریز شهری در شمال‌غرب ایران است.", tabriz.hits().get(0).document().body());
    }

    @Test
    public void malformedFilesFailUnlessSkipped() throws IOException {
        File corpus = temp.newFolder("bad");
        write(new File(corpus, "a.txt"), "عنوان\nمتن");
        write(new File(corpus, "broken.xml"), "<ROOT><DOC><URL>x</URL>");
        File output = new File(temp.getRoot(), "index.bin");

        Result strict = runCli("-i", corpus.getPath(), "-o", output.getPath());
        assertEquals(IndexerCli.EXIT_FAILURE, strict.code);
        assertTrue(strict.err.contains("--skip-bad-files"));

        Result lenient = runCli("-i", corpus.getPath(), "-o", output.getPath(), "--skip-bad-files");
        assertEquals(lenient.err, IndexerCli.EXIT_OK, lenient.code);
        assertTrue(lenient.err.contains("warning: skipping"));
        assertTrue(output.isFile());
    }

    @Test
    public void usageErrorsReturnExitCode2() {
        assertEquals(IndexerCli.EXIT_USAGE, runCli().code);
        assertEquals(IndexerCli.EXIT_USAGE, runCli("--input").code);
        assertEquals(IndexerCli.EXIT_USAGE, runCli("-i", "x", "-o", "y", "--title-boost", "0.5").code);
        assertEquals(IndexerCli.EXIT_USAGE, runCli("--bogus").code);
        assertEquals(IndexerCli.EXIT_OK, runCli("--help").code);
    }

    @Test
    public void inlineOptionValuesAreAccepted() {
        CliOptions options = CliOptions.parse(new String[]{"--input=a", "--output=b.bin", "--title-boost=3"});
        assertEquals(1, options.inputs.size());
        assertEquals(3f, options.titleBoost, 0f);
    }

    @Test
    public void truncationNeverSplitsSurrogatePairs() {
        assertEquals("ab", IndexerCli.truncate("ab\uD83D\uDE00", 3));
        assertEquals("abc", IndexerCli.truncate("abc", 0));
    }

    private static void write(File file, String content) throws IOException {
        Path path = file.toPath();
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Result runCli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = IndexerCli.run(args, new PrintStream(out, true), new PrintStream(err, true));
        return new Result(code, out.toString(), err.toString());
    }

    private static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }
}
