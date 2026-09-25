package io.github.ak811.ase.indexer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.IOException;

/** Extracts a title and readable body text from HTML using jsoup. */
final class HtmlTextExtractor {

    static final class Extracted {
        final String title;
        final String body;

        Extracted(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    /** Parses an HTML string (e.g. the {@code <HTML>} field of a WebIR record). */
    Extracted extract(String html, String baseUri) {
        return fromDocument(Jsoup.parse(html, baseUri == null ? "" : baseUri));
    }

    /** Parses an HTML file, detecting its charset from the BOM or {@code <meta>} tag. */
    Extracted extract(File file) throws IOException {
        return fromDocument(Jsoup.parse(file, null));
    }

    private static Extracted fromDocument(Document document) {
        document.select("script, style, noscript, template, svg, iframe").remove();
        String title = document.title().trim();
        Element body = document.body();
        String text = body != null ? body.text() : document.text();
        return new Extracted(title, text.trim());
    }
}
