package io.github.ak811.ase.indexer;

import io.github.ak811.ase.core.index.IndexReader;
import io.github.ak811.ase.core.search.SearchHit;
import io.github.ak811.ase.core.search.SearchResult;
import io.github.ak811.ase.core.search.Searcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    private static final String JSONL = "\uFEFF{\"url\": \"https://example.org/1\", \"title\": \"Search basics\", "
            + "\"body\": \"Inverted indexes map words to documents.\", \"tags\": [\"a\", {\"b\": 1}], \"n\": -1.5e3}\n"
            + "\n"
            + "{\"id\": 42, \"title\": \"Café \\u00e9t\\u00e9\", \"text\": \"Line one\\nline two\", \"draft\": false}\n";

    @Test
    public void indexesEverySupportedFormat() throws IOException {
        File corpus = temp.newFolder("corpus");
        write(new File(corpus, "WebIR-001.xml"), WEBIR_XML);
        write(new File(corpus, "shiraz.html"),
                "<html><head><meta charset=utf-8><title>شیراز</title></head><body>حافظیه در شیراز است</body></html>");
        write(new File(corpus, "notes/tabriz.txt"), "\uFEFF\n  تبریز  \nتبریز شهری در شمال‌غرب ایران است.\n");
        write(new File(corpus, "guide.md"), "# Getting started\n\nInstall the **engine** and run it.\n");
        write(new File(corpus, "data/articles.jsonl"), JSONL);
        write(new File(corpus, "ignored.pdf"), "not indexed");
        Path output = temp.getRoot().toPath().resolve("out/index.idx");

        Result result = runCli("--input", corpus.getPath(), "--output", output.toString());
        assertEquals(result.err, IndexerCli.EXIT_OK, result.code);
        assertTrue(result.out, result.out.contains("Indexed 7 documents"));
        assertTrue(result.out.contains("Skipped 1 documents without any text"));

        try (IndexReader reader = IndexReader.open(output)) {
            Searcher searcher = new Searcher(reader);

            SearchHit tehran = searcher.search("تهران").hits().get(0);
            assertEquals("https://example.com/a", tehran.url());
            assertEquals("تهران & البرز", tehran.title().text());
            assertEquals("script contents must not be indexed", 0, searcher.search("نامرئی").totalHits());
            assertEquals("shiraz.html", searcher.search("حافظیه").hits().get(0).url());

            SearchHit tabriz = searcher.search("تبریز").hits().get(0);
            assertEquals("notes/tabriz.txt", tabriz.url());
            assertEquals("تبریز", tabriz.title().text());

            SearchHit guide = searcher.search("engine").hits().get(0);
            assertEquals("guide.md", guide.url());
            assertEquals("Getting started", guide.title().text());

            SearchResult json = searcher.search("inverted indexes");
            assertEquals("https://example.org/1", json.hits().get(0).url());
            SearchHit cafe = searcher.search("ete").hits().get(0);
            assertEquals("42", cafe.url());
            assertEquals("Café été", cafe.title().text());
            assertEquals("Line one line two", cafe.excerpt().text());
        }
    }

    @Test
    public void malformedFilesFailUnlessSkipped() throws IOException {
        File corpus = temp.newFolder("bad");
        write(new File(corpus, "a.txt"), "عنوان\nمتن");
        write(new File(corpus, "broken.xml"), "<ROOT><DOC><URL>x</URL>");
        write(new File(corpus, "broken.jsonl"), "{\"title\": \"ok\"}\n{\"title\": oops}\n");
        Path output = temp.getRoot().toPath().resolve("index.idx");

        Result strict = runCli("-i", corpus.getPath(), "-o", output.toString());
        assertEquals(IndexerCli.EXIT_FAILURE, strict.code);
        assertTrue(strict.err.contains("--skip-bad-files"));
        assertFalse("a failed run must not leave an index behind", Files.exists(output));
        try (var files = Files.list(temp.getRoot().toPath())) {
            assertTrue("no temporary files are left", files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }

        Result lenient = runCli("-i", corpus.getPath(), "-o", output.toString(), "--skip-bad-files");
        assertEquals(lenient.err, IndexerCli.EXIT_OK, lenient.code);
        assertTrue(lenient.err.contains("warning: skipping"));
        assertTrue(lenient.err.contains("line 2"));
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    public void honoursNoStemmingAndSmallMemoryBudgets() throws IOException {
        File corpus = temp.newFolder("plain");
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            lines.append("{\"url\":\"u").append(i).append("\",\"body\":\"running word").append(i).append(" shared\"}\n");
        }
        write(new File(corpus, "docs.jsonl"), lines.toString());
        Path output = temp.getRoot().toPath().resolve("plain.idx");
        Result result = runCli("-i", corpus.getPath(), "-o", output.toString(), "--no-stemming", "--memory-mb=16");
        assertEquals(result.err, IndexerCli.EXIT_OK, result.code);
        try (IndexReader reader = IndexReader.open(output)) {
            assertFalse(reader.analyzerConfig().stemming());
            assertTrue(reader.termId("running") >= 0);
            assertEquals(-1, reader.termId("run"));
            assertEquals(4000, new Searcher(reader).search("shared").totalHits());
        }
    }

    @Test
    public void usageErrorsReturnExitCode2() {
        assertEquals(IndexerCli.EXIT_USAGE, runCli().code);
        assertEquals(IndexerCli.EXIT_USAGE, runCli("--input").code);
        assertEquals(IndexerCli.EXIT_USAGE, runCli("-i", "x", "-o", "y", "--title-boost", "0.5").code);
        assertEquals(IndexerCli.EXIT_USAGE, runCli("-i", "x", "-o", "y", "--memory-mb", "1").code);
        assertEquals(IndexerCli.EXIT_USAGE, runCli("--bogus").code);
        assertEquals(IndexerCli.EXIT_OK, runCli("--help").code);
    }

    @Test
    public void inlineOptionValuesAreAccepted() {
        CliOptions options = CliOptions.parse(new String[]{"--input=a", "--output=b.idx", "--title-boost=2", "--no-stemming"});
        assertEquals(1, options.inputs.size());
        assertEquals(2f, options.titleBoost, 0f);
        assertFalse(options.stemming);
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
        int code = IndexerCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {
    }
}
