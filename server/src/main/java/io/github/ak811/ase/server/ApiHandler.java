package io.github.ak811.ase.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.ak811.ase.core.index.IndexReader;
import io.github.ak811.ase.core.index.StoredDocument;
import io.github.ak811.ase.core.search.Highlight;
import io.github.ak811.ase.core.search.MatchMode;
import io.github.ak811.ase.core.search.SearchHit;
import io.github.ak811.ase.core.search.SearchOptions;
import io.github.ak811.ase.core.search.SearchResult;
import io.github.ak811.ase.core.search.Searcher;
import io.github.ak811.ase.core.search.Snippet;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The JSON API:
 * <ul>
 *   <li>{@code GET /api/search?q=…&page=1&size=10&exact=0}</li>
 *   <li>{@code GET /api/suggest?q=…}</li>
 *   <li>{@code GET /api/documents/{id}}</li>
 *   <li>{@code GET /api/stats}</li>
 * </ul>
 */
final class ApiHandler implements HttpHandler {
    private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_DOCUMENT_CHARS = 500_000;
    private static final int SUGGESTIONS = 8;

    private final SearchService service;
    private final RateLimiter rateLimiter;
    private final ServerConfig config;
    private final String version;

    ApiHandler(SearchService service, RateLimiter rateLimiter, ServerConfig config, String version) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.version = version;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        int status = 500;
        try {
            status = dispatch(exchange);
        } catch (IllegalArgumentException e) {
            status = 400;
            HttpSupport.sendError(exchange, 400, "bad_request", e.getMessage());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.SEVERE, "request failed: " + exchange.getRequestURI().getPath(), e);
            HttpSupport.sendError(exchange, 500, "internal_error", "Something went wrong. Please try again.");
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            int finalStatus = status;
            LOG.fine(() -> exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + finalStatus + " " + millis + "ms");
            exchange.close();
        }
    }

    private int dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            HttpSupport.sendError(exchange, 405, "method_not_allowed", "Only GET is supported.");
            return 405;
        }
        long wait = rateLimiter.acquire(HttpSupport.clientAddress(exchange, config.trustProxy()), System.nanoTime());
        if (wait > 0) {
            exchange.getResponseHeaders().set("Retry-After", String.valueOf(wait));
            HttpSupport.sendError(exchange, 429, "rate_limited", "Too many requests. Please slow down.");
            return 429;
        }

        String path = exchange.getRequestURI().getPath();
        Map<String, String> parameters = HttpSupport.queryParameters(exchange);
        SearchService.Engine engine = service.engine();
        switch (path) {
            case "/api/search":
                HttpSupport.sendJson(exchange, 200, search(engine, parameters));
                return 200;
            case "/api/suggest":
                exchange.getResponseHeaders().set("Cache-Control", "private, max-age=60");
                HttpSupport.sendJson(exchange, 200, suggest(engine, parameters));
                return 200;
            case "/api/stats":
                HttpSupport.sendJson(exchange, 200, stats(engine));
                return 200;
            default:
                if (path.startsWith("/api/documents/")) {
                    return document(exchange, engine, path.substring("/api/documents/".length()));
                }
                HttpSupport.sendError(exchange, 404, "not_found", "Unknown API endpoint.");
                return 404;
        }
    }

    private Map<String, Object> search(SearchService.Engine engine, Map<String, String> parameters) throws IOException {
        String query = parameters.getOrDefault("q", "");
        if (query.length() > Searcher.MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("The query is too long (maximum " + Searcher.MAX_QUERY_LENGTH + " characters).");
        }
        int page = intParameter(parameters, "page", 1, 1, Integer.MAX_VALUE);
        int size = intParameter(parameters, "size", 10, 1, MAX_PAGE_SIZE);
        long offset = (long) (page - 1) * size;
        if (offset + size > SearchOptions.MAX_WINDOW) {
            throw new IllegalArgumentException("Only the first " + SearchOptions.MAX_WINDOW + " results can be paged through.");
        }
        boolean exact = "1".equals(parameters.get("exact")) || "true".equals(parameters.get("exact"));
        SearchResult result = engine.searcher().search(query, new SearchOptions((int) offset, size, !exact));

        List<Object> hits = new ArrayList<>();
        for (SearchHit hit : result.hits()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", hit.documentId());
            json.put("url", hit.url());
            json.put("title", snippet(hit.title()));
            json.put("snippet", snippet(hit.excerpt()));
            json.put("score", Math.round(hit.score() * 1000) / 1000.0);
            hits.add(json);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("query", result.query());
        json.put("correctedQuery", result.correctedQuery());
        json.put("matchMode", result.matchMode() == MatchMode.ALL_TERMS ? "all" : "any");
        json.put("total", result.totalHits());
        json.put("page", page);
        json.put("size", size);
        json.put("tookMs", Math.round(result.tookMillis() * 100) / 100.0);
        json.put("hits", hits);
        return json;
    }

    private Map<String, Object> suggest(SearchService.Engine engine, Map<String, String> parameters) {
        String query = parameters.getOrDefault("q", "");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("query", query);
        json.put("suggestions", engine.suggester().suggest(query, SUGGESTIONS));
        return json;
    }

    private Map<String, Object> stats(SearchService.Engine engine) {
        IndexReader reader = engine.reader();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("siteName", config.siteName());
        json.put("version", version);
        json.put("documents", reader.documentCount());
        json.put("terms", reader.termCount());
        json.put("indexBytes", reader.fileSize());
        json.put("indexedAt", Instant.ofEpochMilli(reader.createdAtMillis()).toString());
        json.put("stemming", reader.analyzerConfig().stemming());
        return json;
    }

    private int document(HttpExchange exchange, SearchService.Engine engine, String idText) throws IOException {
        int id;
        try {
            id = Integer.parseInt(idText);
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id < 0 || id >= engine.reader().documentCount()) {
            HttpSupport.sendError(exchange, 404, "not_found", "No such document.");
            return 404;
        }
        StoredDocument document = engine.reader().document(id);
        String body = document.body();
        boolean truncated = body.length() > MAX_DOCUMENT_CHARS;
        if (truncated) {
            int end = MAX_DOCUMENT_CHARS;
            if (Character.isHighSurrogate(body.charAt(end - 1))) {
                end--;
            }
            body = body.substring(0, end);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", document.id());
        json.put("url", document.url());
        json.put("title", document.title());
        json.put("body", body);
        json.put("truncated", truncated);
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=300");
        HttpSupport.sendJson(exchange, 200, json);
        return 200;
    }

    private static Map<String, Object> snippet(Snippet snippet) {
        List<int[]> ranges = new ArrayList<>();
        for (Highlight highlight : snippet.highlights()) {
            ranges.add(new int[]{highlight.start(), highlight.end()});
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("text", snippet.text());
        json.put("highlights", ranges);
        return json;
    }

    private static int intParameter(Map<String, String> parameters, String name, int defaultValue, int min, int max) {
        String value = parameters.get(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("'" + name + "' must be between " + min + " and " + max + ".");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + name + "' must be a whole number.");
        }
    }
}
