package io.github.ak811.ase.indexer;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Streams documents from JSON Lines files: one JSON object per line, e.g.
 * <pre>{@code {"url": "https://…", "title": "…", "body": "…"}}</pre>
 * The body may also be called {@code text} or {@code content}; {@code id} is used when
 * there is no {@code url}. Other fields are ignored. Blank lines are skipped.
 */
final class JsonLinesReader {

    /** Reads every line of {@code file}; returns the number of records read. */
    int read(Path file, DocumentSink sink) throws IOException {
        int records = 0;
        int lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> object;
                try {
                    object = new JsonParser(line).parseObjectLine();
                } catch (IllegalArgumentException e) {
                    throw new IOException("line " + lineNumber + ": " + e.getMessage(), e);
                }
                String url = firstString(object, "url", "id");
                String title = firstString(object, "title");
                String body = firstString(object, "body", "text", "content");
                sink.accept(url, title, CorpusLoader.collapseWhitespace(body));
                records++;
            }
        }
        return records;
    }

    private static String firstString(Map<String, Object> object, String... keys) {
        for (String key : keys) {
            Object value = object.get(key);
            if (value instanceof String) {
                return ((String) value).strip();
            }
            if (value instanceof BigDecimal) {
                return ((BigDecimal) value).toPlainString();
            }
        }
        return "";
    }

    /** A small, strict JSON parser. Numbers are returned exactly, as {@link BigDecimal}. */
    static final class JsonParser {
        private static final int MAX_DEPTH = 64;
        private final String text;
        private int position;

        JsonParser(String text) {
            this.text = text;
        }

        Map<String, Object> parseObjectLine() {
            skipWhitespace();
            if (peek() != '{') {
                throw error("expected a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) parseValue(0);
            skipWhitespace();
            if (position != text.length()) {
                throw error("unexpected trailing characters");
            }
            return object;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject(depth);
                case '[':
                    return parseArray(depth);
                case '"':
                    return parseString();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> parseObject(int depth) {
            Map<String, Object> object = new HashMap<>();
            position++;
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a field name");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return object;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private Object parseArray(int depth) {
            position++;
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return null;
            }
            while (true) {
                parseValue(depth + 1);
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return null; // arrays are not used; skip their content
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            position++;
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char escape = next();
                    switch (escape) {
                        case '"':
                        case '\\':
                        case '/':
                            out.append(escape);
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'f':
                            out.append('\f');
                            break;
                        case 'n':
                            out.append('\n');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case 'u':
                            if (position + 4 > text.length()) {
                                throw error("truncated \\u escape");
                            }
                            try {
                                out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            } catch (NumberFormatException e) {
                                throw error("invalid \\u escape");
                            }
                            position += 4;
                            break;
                        default:
                            throw error("invalid escape '\\" + escape + "'");
                    }
                } else if (c < 0x20) {
                    throw error("control character in string");
                } else {
                    out.append(c);
                }
            }
        }

        private BigDecimal parseNumber() {
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            try {
                return new BigDecimal(text.substring(start, position));
            } catch (NumberFormatException e) {
                throw error("invalid number");
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, position)) {
                throw error("unexpected token");
            }
            position += word.length();
            return value;
        }

        private void expect(char expected) {
            if (next() != expected) {
                throw error("expected '" + expected + "'");
            }
        }

        private char peek() {
            if (position >= text.length()) {
                throw error("unexpected end of line");
            }
            return text.charAt(position);
        }

        private char next() {
            char c = peek();
            position++;
            return c;
        }

        private void skipWhitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                    return;
                }
                position++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at column " + (position + 1));
        }
    }
}
