package io.github.ak811.ase.server;

import io.github.ak811.ase.core.index.IndexWriter;
import io.github.ak811.ase.core.index.IndexWriterConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebServerTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final HttpClient client = HttpClient.newHttpClient();
    private Path index;
    private WebServer server;
    private String base;

    @Before
    public void setUp() throws IOException {
        index = temp.getRoot().toPath().resolve("index.idx");
        writeIndex(index, 3);
        server = start(120);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private WebServer start(int rateLimit) throws IOException {
        ServerConfig config = new ServerConfig(index, "127.0.0.1", 0, 4, "Test <Search>", rateLimit, false, 0, 8);
        WebServer started = new WebServer(config);
        started.start();
        base = "http://127.0.0.1:" + started.port();
        return started;
    }

    private static void writeIndex(Path path, int englishDocuments) throws IOException {
        Path temp = path.resolveSibling("next.idx");
        try (IndexWriter writer = new IndexWriter(temp, IndexWriterConfig.defaults())) {
            for (int i = 0; i < englishDocuments; i++) {
                writer.addDocument("https://example.com/" + i, "Search engines " + i,
                        "A search engine ranks documents with an inverted index. Document " + i + ".");
            }
            writer.addDocument("local/fa.txt", "کتابخانه ملی", "کتاب‌های زیادی در کتابخانه ملی نگهداری می‌شود.");
            writer.addDocument("local/script.txt", "<script>alert(1)</script>", "Tricky title with markup.");
            writer.commit();
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, Map.of());
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String q(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    @Test
    public void searchReturnsRankedHighlightedJson() throws Exception {
        HttpResponse<String> response = get("/api/search?q=" + q("searching engines") + "&size=2");
        assertEquals(200, response.statusCode());
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        String body = response.body();
        assertTrue(body, body.contains("\"total\":3"));
        assertTrue(body.contains("\"matchMode\":\"all\""));
        assertTrue(body.contains("\"url\":\"https://example.com/"));
        assertTrue(body.contains("\"highlights\":[[0,6],[7,14]]"));
        assertEquals(2, body.split("\"score\"").length - 1);
    }

    @Test
    public void searchCorrectsSpellingUnlessExact() throws Exception {
        assertTrue(get("/api/search?q=" + q("serch")).body().contains("\"correctedQuery\":\"search\""));
        String exact = get("/api/search?q=" + q("serch") + "&exact=1").body();
        assertTrue(exact.contains("\"correctedQuery\":null"));
        assertTrue(exact.contains("\"total\":0"));
    }

    @Test
    public void searchesPersianText() throws Exception {
        String body = get("/api/search?q=" + q("كتاب ها")).body();
        assertTrue(body, body.contains("\"url\":\"local/fa.txt\""));
    }

    @Test
    public void escapesMarkupInJson() throws Exception {
        String body = get("/api/search?q=" + q("tricky")).body();
        assertTrue(body.contains("\\u003cscript\\u003e"));
        assertFalse(body.contains("<script>"));
    }

    @Test
    public void validatesParameters() throws Exception {
        assertEquals(400, get("/api/search?q=x&page=0").statusCode());
        assertEquals(400, get("/api/search?q=x&size=51").statusCode());
        assertEquals(400, get("/api/search?q=x&page=abc").statusCode());
        assertEquals(400, get("/api/search?q=x&page=1001").statusCode());
        HttpResponse<String> tooLong = get("/api/search?q=" + "a".repeat(1001));
        assertEquals(400, tooLong.statusCode());
        assertTrue(tooLong.body().contains("\"code\":\"bad_request\""));
        assertEquals(200, get("/api/search").statusCode());
    }

    @Test
    public void rejectsOtherMethods() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/search?q=x"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElse(""));
    }

    @Test
    public void servesSuggestionsStatsDocumentsAndHealth() throws Exception {
        assertTrue(get("/api/suggest?q=sea").body().contains("\"suggestions\":[\"search\"]"));
        String stats = get("/api/stats").body();
        assertTrue(stats, stats.contains("\"documents\":5"));
        assertTrue(stats.contains("\"siteName\":\"Test \\u003cSearch\\u003e\""));
        String document = get("/api/documents/3").body();
        assertTrue(document.contains("\"url\":\"local/fa.txt\""));
        assertTrue(document.contains("\"truncated\":false"));
        assertEquals(404, get("/api/documents/99").statusCode());
        assertEquals(404, get("/api/documents/-1").statusCode());
        assertEquals(404, get("/api/documents/x").statusCode());
        assertEquals(404, get("/api/unknown").statusCode());
        assertTrue(get("/healthz").body().contains("\"status\":\"ok\""));
    }

    @Test
    public void servesThePageWithSecurityHeadersAndCaching() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>Test &lt;Search&gt;</title>"));
        assertFalse(page.body().contains("{{SITE_NAME}}"));
        assertEquals(HttpSupport.CONTENT_SECURITY_POLICY, page.headers().firstValue("Content-Security-Policy").orElse(""));
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElse(""));

        HttpResponse<String> script = get("/assets/app.js");
        assertEquals(200, script.statusCode());
        assertTrue(script.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        String etag = script.headers().firstValue("ETag").orElseThrow();
        assertEquals(304, get("/assets/app.js", Map.of("If-None-Match", etag)).statusCode());

        HttpResponse<byte[]> gzipped = client.send(HttpRequest.newBuilder(URI.create(base + "/assets/app.js"))
                .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("gzip", gzipped.headers().firstValue("Content-Encoding").orElse(""));
        String unzipped = new String(new GZIPInputStream(new ByteArrayInputStream(gzipped.body())).readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(script.body(), unzipped);

        assertEquals(404, get("/missing.html").statusCode());
        assertEquals(200, get("/favicon.svg").statusCode());
        assertEquals(200, get("/assets/styles.css").statusCode());
    }

    @Test
    public void rateLimitsApiRequestsPerClient() throws Exception {
        server.close();
        server = start(3);
        for (int i = 0; i < 3; i++) {
            assertEquals(200, get("/api/stats").statusCode());
        }
        HttpResponse<String> limited = get("/api/stats");
        assertEquals(429, limited.statusCode());
        assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElse("0")) >= 1);
        assertEquals("the page and health check are not rate limited", 200, get("/").statusCode());
        assertEquals(200, get("/healthz").statusCode());
    }

    @Test
    public void hotReloadsANewIndexAndKeepsServingOnBadFiles() throws Exception {
        writeIndex(index, 7);
        Files.setLastModifiedTime(index, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertTrue(server.service().reloadIfChanged());
        assertTrue(get("/api/stats").body().contains("\"documents\":9"));

        Path broken = index.resolveSibling("broken.idx");
        Files.writeString(broken, "definitely not an index ".repeat(20));
        Files.move(broken, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        assertFalse(server.service().reloadIfChanged());
        assertTrue("the previous index keeps serving", get("/api/search?q=search").body().contains("\"total\":7"));
        assertFalse("a failing file is not retried until it changes", server.service().reloadIfChanged());
    }

    @Test
    public void failsFastOnAMissingIndex() {
        ServerConfig config = new ServerConfig(temp.getRoot().toPath().resolve("missing.idx"), "127.0.0.1", 0, 1,
                "x", 0, false, 0, 1);
        try {
            new WebServer(config).close();
            org.junit.Assert.fail();
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
