package io.github.ak811.ase.indexer;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams records from WebIR-style XML files:
 *
 * <pre>{@code
 * <ROOT>
 *   <DOC>
 *     <URL>https://…</URL>
 *     <HTML><![CDATA[ <html>…</html> ]]></HTML>
 *   </DOC>
 *   …
 * </ROOT>
 * }</pre>
 *
 * Element names are case-insensitive. The file is streamed, so its size is not
 * limited by memory. DTDs and external entities are disabled (no XXE).
 */
final class WebIrXmlReader {
    private final XMLInputFactory factory;

    WebIrXmlReader() {
        factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
    }

    /** Reads every {@code <DOC>} in {@code file}; returns the number of records read. */
    int read(Path file, HtmlTextExtractor html, DocumentSink sink) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return read(in, html, sink);
        } catch (XMLStreamException e) {
            throw new IOException("malformed XML: " + e.getMessage(), e);
        }
    }

    int read(InputStream in, HtmlTextExtractor html, DocumentSink sink) throws XMLStreamException {
        XMLStreamReader reader = factory.createXMLStreamReader(in);
        try {
            int records = 0;
            boolean inDoc = false;
            String url = null;
            String markup = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("DOC".equalsIgnoreCase(name)) {
                        inDoc = true;
                        url = null;
                        markup = null;
                    } else if (inDoc && "URL".equalsIgnoreCase(name)) {
                        url = readText(reader).trim();
                    } else if (inDoc && "HTML".equalsIgnoreCase(name)) {
                        markup = readText(reader);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && inDoc
                        && "DOC".equalsIgnoreCase(reader.getLocalName())) {
                    inDoc = false;
                    String source = url == null ? "" : url;
                    HtmlTextExtractor.Extracted text = html.extract(markup == null ? "" : markup, source);
                    sink.accept(source, text.title, text.body);
                    records++;
                }
            }
            return records;
        } finally {
            reader.close();
        }
    }

    /** All character data inside the current element, including nested elements. */
    private static String readText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    depth++;
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    depth--;
                    break;
                case XMLStreamConstants.CHARACTERS:
                case XMLStreamConstants.CDATA:
                case XMLStreamConstants.SPACE:
                case XMLStreamConstants.ENTITY_REFERENCE:
                    text.append(reader.getText());
                    break;
                default:
                    break;
            }
        }
        return text.toString();
    }
}
