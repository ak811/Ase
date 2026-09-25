package io.github.ak811.ase.indexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads documents from supported files:
 * <ul>
 *   <li>{@code .xml} — WebIR records (see {@link WebIrXmlReader})</li>
 *   <li>{@code .html}, {@code .htm} — one document per file; title from {@code <title>}</li>
 *   <li>{@code .txt} — one document per file; first non-blank line is the title, UTF-8</li>
 * </ul>
 * Local files are identified by their path relative to the input root, using
 * forward slashes, so no machine-specific paths end up in the index.
 */
final class CorpusLoader {
    private final HtmlTextExtractor html;
    private final WebIrXmlReader xml;

    CorpusLoader(HtmlTextExtractor html, WebIrXmlReader xml) {
        this.html = html;
        this.xml = xml;
    }

    /** Supported files under {@code input} (or {@code input} itself), in a stable order. */
    List<Path> listFiles(Path input) throws IOException {
        if (Files.isDirectory(input)) {
            try (Stream<Path> paths = Files.walk(input)) {
                return paths.filter(Files::isRegularFile)
                        .filter(CorpusLoader::isSupported)
                        .sorted()
                        .collect(Collectors.toList());
            }
        }
        if (!Files.isRegularFile(input)) {
            throw new IOException("input not found: " + input);
        }
        if (!isSupported(input)) {
            throw new IOException("unsupported file type (expected .xml, .html, .htm or .txt): " + input);
        }
        return Collections.singletonList(input);
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
            case "txt": {
                loadText(root, file, sink);
                return 1;
            }
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
            if (!line.trim().isEmpty()) {
                title = line.trim();
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
