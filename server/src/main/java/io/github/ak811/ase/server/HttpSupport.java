package io.github.ak811.ase.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Response helpers shared by the handlers. */
final class HttpSupport {
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' data:; style-src 'self'; "
            + "script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'";
    private static final int GZIP_THRESHOLD = 1024;

    private HttpSupport() {
    }

    static void securityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        if (!headers.containsKey("Cache-Control")) {
            headers.set("Cache-Control", "no-store");
        }
        send(exchange, status, Json.write(body).getBytes(StandardCharsets.UTF_8), null);
    }

    static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        sendJson(exchange, status, Map.of("error", error));
    }

    /**
     * Sends {@code body}, gzip-compressed when the client accepts it and it is worth it.
     * {@code gzipped} may hold a precompressed copy.
     */
    static void send(HttpExchange exchange, int status, byte[] body, byte[] gzipped) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        securityHeaders(headers);
        headers.add("Vary", "Accept-Encoding");
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        byte[] payload = body;
        if (acceptsGzip(exchange) && body.length >= GZIP_THRESHOLD) {
            payload = gzipped != null ? gzipped : gzip(body);
            headers.set("Content-Encoding", "gzip");
        }
        if (head || status == 304 || status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }

    static boolean acceptsGzip(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(data.length / 3 + 64);
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** Decodes {@code a=1&b=x+y}; later duplicates win. Throws IllegalArgumentException on bad escapes. */
    static Map<String, String> queryParameters(HttpExchange exchange) {
        Map<String, String> parameters = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return parameters;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parameters.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters;
    }

    /** The client address, taken from the last X-Forwarded-For hop when a proxy is trusted. */
    static String clientAddress(HttpExchange exchange, boolean trustProxy) {
        if (trustProxy) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                String last = hops[hops.length - 1].strip();
                if (!last.isEmpty() && last.length() <= 64) {
                    return last;
                }
            }
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }
}
