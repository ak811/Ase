package io.github.ak811.ase.core.index;

import java.util.Objects;

/** An immutable indexed document. Ids are dense: {@code 0 .. documentCount - 1}. */
public final class Document {
    private final int id;
    private final String url;
    private final String title;
    private final String body;

    public Document(int id, String url, String title, String body) {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0");
        }
        this.id = id;
        this.url = Objects.requireNonNull(url, "url");
        this.title = Objects.requireNonNull(title, "title");
        this.body = Objects.requireNonNull(body, "body");
    }

    public int id() {
        return id;
    }

    /** Source URL or relative path; may be empty. */
    public String url() {
        return url;
    }

    /** Document title; may be empty. */
    public String title() {
        return title;
    }

    /** Plain-text body used for snippets; may be empty. */
    public String body() {
        return body;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Document)) {
            return false;
        }
        Document other = (Document) o;
        return id == other.id && url.equals(other.url) && title.equals(other.title) && body.equals(other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, url, title, body);
    }

    @Override
    public String toString() {
        return "Document{id=" + id + ", url='" + url + "', title='" + title + "'}";
    }
}
