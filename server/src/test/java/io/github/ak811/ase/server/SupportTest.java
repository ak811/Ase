package io.github.ak811.ase.server;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SupportTest {
    @Test
    public void jsonEscapesAndFormats() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("s", "a\"b\\c\n\u0001</script>&\u2028فارسی");
        value.put("n", 3);
        value.put("d", 1.5);
        value.put("whole", 2.0);
        value.put("nan", Double.NaN);
        value.put("list", List.of(true, new int[]{1, 2}));
        value.put("null", null);
        assertEquals("{\"s\":\"a\\\"b\\\\c\\n\\u0001\\u003c/script\\u003e\\u0026\\u2028فارسی\",\"n\":3,\"d\":1.5,"
                + "\"whole\":2,\"nan\":null,\"list\":[true,[1,2]],\"null\":null}", Json.write(value));
    }

    @Test
    public void rateLimiterRefillsOverTime() {
        RateLimiter limiter = new RateLimiter(60); // one per second
        long now = 0;
        for (int i = 0; i < 60; i++) {
            assertEquals(0, limiter.acquire("a", now));
        }
        assertTrue(limiter.acquire("a", now) >= 1);
        assertEquals("other clients are independent", 0, limiter.acquire("b", now));
        assertEquals(0, limiter.acquire("a", now + 1_000_000_000L));
        assertFalse(new RateLimiter(0).enabled());
        assertEquals(0, new RateLimiter(0).acquire("a", 0));
    }

    @Test
    public void configReadsFlagsThenEnvironment() {
        ServerConfig fromEnv = ServerConfig.parse(new String[0],
                Map.of("ASE_INDEX", "data/index.idx", "PORT", "9000", "ASE_TRUST_PROXY", "true"));
        assertEquals(Path.of("data/index.idx"), fromEnv.index());
        assertEquals(9000, fromEnv.port());
        assertTrue(fromEnv.trustProxy());
        assertEquals(ServerConfig.DEFAULT_HOST, fromEnv.host());

        ServerConfig flags = ServerConfig.parse(new String[]{"--index", "a.idx", "--port=8081", "--host", "0.0.0.0",
                "--rate-limit", "0", "--site-name", "Docs"}, Map.of("ASE_INDEX", "ignored", "ASE_PORT", "1"));
        assertEquals(Path.of("a.idx"), flags.index());
        assertEquals(8081, flags.port());
        assertEquals("0.0.0.0", flags.host());
        assertEquals(0, flags.rateLimitPerMinute());
        assertEquals("Docs", flags.siteName());

        for (String[] bad : new String[][]{{}, {"--index"}, {"--index", "a", "--port", "x"},
                {"--index", "a", "--port", "70000"}, {"--index", "a", "--bogus", "1"}, {"--index", "a", "--threads", "0"}}) {
            try {
                ServerConfig.parse(bad, Map.of());
                fail(String.join(" ", bad));
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void mainReportsUsageErrors() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, WebServer.run(new String[0], Map.of(), new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--index is required"));
        assertEquals(0, WebServer.run(new String[]{"--help"}, Map.of(), new PrintStream(out), new PrintStream(err)));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: ase-server"));
        assertEquals(1, WebServer.run(new String[]{"--index", "/nonexistent/x.idx", "--port", "0"}, Map.of(),
                new PrintStream(out), new PrintStream(err)));
    }

    @Test
    public void escapesHtml() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;Tom &amp; Jerry&#39;s&lt;/a&gt;",
                StaticHandler.escapeHtml("<a href=\"x\">Tom & Jerry's</a>"));
    }
}
