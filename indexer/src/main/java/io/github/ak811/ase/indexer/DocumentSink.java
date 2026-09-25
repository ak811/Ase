package io.github.ak811.ase.indexer;

import java.io.IOException;

/** Receives documents extracted from a corpus. */
@FunctionalInterface
interface DocumentSink {
    void accept(String url, String title, String body) throws IOException;
}
