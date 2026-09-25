package io.github.ak811.ase.core.index;

/** A document as stored in the index. Ids are dense: {@code 0 .. documentCount - 1}. */
public record StoredDocument(int id, String url, String title, String body) {
}
