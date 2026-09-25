package io.github.ak811.ase.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Serves the web page from classpath {@code /public/}. Files are loaded once at startup,
 * precompressed, and revalidated with ETags. {@code index.html} gets the site name injected.
 */
final class StaticHandler implements HttpHandler {
    static final List<String> FILES = List.of("index.html", "assets/app.js", "assets/styles.css", "favicon.svg");

    private final Map<String, Asset> assets = new HashMap<>();

    private record Asset(byte[] body, byte[] gzipped, String contentType, String etag) {
    }

    StaticHandler(String siteName) throws IOException {
        for (String file : FILES) {
            byte[] body;
            try (InputStream in = StaticHandler.class.getResourceAsStream("/public/" + file)) {
                if (in == null) {
                    throw new IOException("missing web resource /public/" + file);
                }
                body = in.readAllBytes();
            }
            if (file.equals("index.html")) {
                body = new String(body, StandardCharsets.UTF_8)
                        .replace("{{SITE_NAME}}", escapeHtml(siteName))
                        .getBytes(StandardCharsets.UTF_8);
            }
            assets.put("/" + file, new Asset(body, HttpSupport.gzip(body), contentType(file), etag(body)));
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                HttpSupport.sendError(exchange, 405, "method_not_allowed", "Only GET is supported.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Asset asset = assets.get(path.equals("/") ? "/index.html" : path);
            if (asset == null) {
                byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                HttpSupport.send(exchange, 404, body, null);
                return;
            }
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", asset.contentType());
            headers.set("ETag", asset.etag());
            headers.set("Cache-Control", "no-cache");
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ifNoneMatch != null && ifNoneMatch.contains(asset.etag())) {
                HttpSupport.send(exchange, 304, new byte[0], null);
                return;
            }
            HttpSupport.send(exchange, 200, asset.body(), asset.gzipped());
        } finally {
            exchange.close();
        }
    }

    private static String contentType(String file) {
        if (file.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (file.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (file.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (file.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static String etag(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "\"" + HexFormat.of().formatHex(digest, 0, 12) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String escapeHtml(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
