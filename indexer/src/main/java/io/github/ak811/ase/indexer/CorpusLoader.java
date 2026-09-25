package io.github.ak811.ase.indexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Loads documents from supported files:
 * <ul>
 *   <li>{@code .xml} — WebIR records (see {@link WebIrXmlReader})</li>
 *   <li>{@code .html}, {@code .htm} — one document per file; title from {@code <title>}</li>
 *   <li>{@code .txt}, {@code .md}, {@code .markdown} — one document per file; first non-blank line is
 *       the title (a leading Markdown {@code #} is removed), UTF-8</li>
 *   <li>{@code .jsonl}, {@code .ndjson} — one JSON document per line (see {@link JsonLinesReader})</li>
 * </ul>
 * Local files are identified by their path relative to the input root, using
 * forward slashes, so no machine-specific paths end up in the index.
 */
final class CorpusLoader {
    private final HtmlTextExtractor html;
    private final WebIrXmlReader xml;
    private final JsonLinesReader jsonLines;

    CorpusLoader(HtmlTextExtractor html, WebIrXmlReader xml, JsonLinesReader jsonLines) {
        this.html = html;
        this.xml = xml;
        this.jsonLines = jsonLines;
    }

    /** Supported files under {@code input} (or {@code input} itself), in a stable order. */
    List<Path> listFiles(Path input) throws IOException {
        if (Files.isDirectory(input)) {
            try (Stream<Path> paths = Files.walk(input)) {
                return paths.filter(Files::isRegularFile)
                        .filter(CorpusLoader::isSupported)
                        .sorted()
                        .toList();
            }
        }
        if (!Files.isRegularFile(input)) {
            throw new IOException("input not found: " + input);
        }
        if (!isSupported(input)) {
            throw new IOException("unsupported file type (expected .xml, .html, .htm, .txt, .md, .jsonl or .ndjson): "
                    + input);
        }
        return List.of(input);
    }

    /** Loads one file; returns the number of documents it produced. */
    int loadFile(Path root, Path file, DocumentSink sink) throws IOException {
        String ext = extension(file);
        switch (ext) {
            case "xml":
                return xml.read(file, html, sink);
            case "html":
            case "htm": {
                HtmlTextExtractor.Extracted text = html.extract(file.toFile());
                sink.accept(relativeName(root, file), text.title, text.body);
                return 1;
            }
            case "txt":
            case "md":
            case "markdown": {
                loadText(root, file, sink);
                return 1;
            }
            case "jsonl":
            case "ndjson":
                return jsonLines.read(file, sink);
            default:
                throw new IOException("unsupported file type: " + file);
        }
    }

    private static void loadText(Path root, Path file, DocumentSink sink) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        String title = "";
        String body = content;
        for (String line : content.split("\\R", -1)) {
            if (!line.isBlank()) {
                title = line.trim().replaceFirst("^#+\\s*", "");
                int titleEnd = content.indexOf(line) + line.length();
                body = content.substring(titleEnd);
                break;
            }
        }
        sink.accept(relativeName(root, file), title, collapseWhitespace(body));
    }

    static String relativeName(Path root, Path file) {
        Path base = Files.isDirectory(root) ? root : root.toAbsolutePath().getParent();
        Path relative = base == null ? file.getFileName() : base.toAbsolutePath().relativize(file.toAbsolutePath());
        return relative.toString().replace('\\', '/');
    }

    static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    static boolean isSupported(Path file) {
        switch (extension(file)) {
            case "xml":
            case "html":
            case "htm":
            case "txt":
            case "md":
            case "markdown":
            case "jsonl":
            case "ndjson":
                return true;
            default:
                return false;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
