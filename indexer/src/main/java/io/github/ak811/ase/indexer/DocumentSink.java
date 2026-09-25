package io.github.ak811.ase.indexer;

/** Receives documents extracted from a corpus. */
@FunctionalInterface
interface DocumentSink {
    void accept(String url, String title, String body);
}
