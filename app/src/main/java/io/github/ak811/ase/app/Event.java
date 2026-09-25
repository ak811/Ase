package io.github.ak811.ase.app;

import androidx.annotation.Nullable;

/** A value that should be handled once (e.g. a snackbar), even if LiveData redelivers it. */
final class Event<T> {
    private final T content;
    private boolean handled;

    Event(T content) {
        this.content = content;
    }

    /** Returns the content the first time, then {@code null}. Call on the main thread. */
    @Nullable
    T consume() {
        if (handled) {
            return null;
        }
        handled = true;
        return content;
    }
}
