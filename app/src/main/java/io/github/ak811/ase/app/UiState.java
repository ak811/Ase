package io.github.ak811.ase.app;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.ak811.ase.core.search.SearchResult;

/** Immutable snapshot of everything the screen shows. */
public final class UiState {
    public enum Status { LOADING_INDEX, NO_INDEX, INDEX_ERROR, READY, SEARCHING, RESULTS, SEARCH_ERROR }

    @NonNull public final Status status;
    @Nullable public final IndexInfo index;
    @Nullable public final String query;
    @Nullable public final SearchResult result;
    @Nullable public final String error;
    public final boolean canRestoreBundled;

    private UiState(@NonNull Status status, @Nullable IndexInfo index, @Nullable String query,
                    @Nullable SearchResult result, @Nullable String error, boolean canRestoreBundled) {
        this.status = status;
        this.index = index;
        this.query = query;
        this.result = result;
        this.error = error;
        this.canRestoreBundled = canRestoreBundled;
    }

    static UiState loadingIndex() {
        return new UiState(Status.LOADING_INDEX, null, null, null, null, false);
    }

    static UiState noIndex() {
        return new UiState(Status.NO_INDEX, null, null, null, null, false);
    }

    static UiState indexError(@NonNull String error, boolean canRestoreBundled) {
        return new UiState(Status.INDEX_ERROR, null, null, null, error, canRestoreBundled);
    }

    static UiState ready(@NonNull IndexInfo index) {
        return new UiState(Status.READY, index, null, null, null, index.canRestoreBundled());
    }

    static UiState searching(@NonNull IndexInfo index, @NonNull String query, @Nullable SearchResult previous) {
        return new UiState(Status.SEARCHING, index, query, previous, null, index.canRestoreBundled());
    }

    static UiState results(@NonNull IndexInfo index, @NonNull String query, @NonNull SearchResult result) {
        return new UiState(Status.RESULTS, index, query, result, null, index.canRestoreBundled());
    }

    static UiState searchError(@NonNull IndexInfo index, @NonNull String query, @NonNull String error) {
        return new UiState(Status.SEARCH_ERROR, index, query, null, error, index.canRestoreBundled());
    }

    /** Back navigation returns to the start screen in these states. */
    public boolean isSearchMode() {
        return status == Status.SEARCHING || status == Status.RESULTS || status == Status.SEARCH_ERROR;
    }
}
