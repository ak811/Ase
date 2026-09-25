package io.github.ak811.ase.core.index;

import java.io.IOException;

/** The file is not a valid index, is corrupt, or was written by an incompatible version. */
public final class IndexFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    public IndexFormatException(String message) {
        super(message);
    }

    public IndexFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
