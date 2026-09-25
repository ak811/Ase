package io.github.ak811.ase.app;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.PluralsRes;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ak811.ase.R;
import io.github.ak811.ase.core.index.SearchIndex;
import io.github.ak811.ase.core.search.SearchOptions;
import io.github.ak811.ase.core.search.SearchResult;
import io.github.ak811.ase.core.search.Searcher;

/**
 * Owns the index and runs all loading and searching on one background thread,
 * so the main thread never blocks and state survives configuration changes.
 * Every request gets a generation number; results of superseded requests are dropped.
 */
public final class SearchViewModel extends AndroidViewModel {
    private static final String TAG = "SearchViewModel";
    private static final int PAGE_SIZE = 50;

    private final IndexRepository repository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "search-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final MutableLiveData<UiState> state = new MutableLiveData<>(UiState.loadingIndex());
    private final MutableLiveData<Event<String>> messages = new MutableLiveData<>();

    // Written on the worker thread, read on the main thread.
    private volatile Searcher searcher;
    private volatile IndexInfo indexInfo;

    public SearchViewModel(@NonNull Application application) {
        super(application);
        repository = new IndexRepository(application);
        loadIndex(false);
    }

    public LiveData<UiState> state() {
        return state;
    }

    public LiveData<Event<String>> messages() {
        return messages;
    }

    public void search(@Nullable String query, boolean autoCorrect) {
        Searcher current = searcher;
        IndexInfo info = indexInfo;
        if (current == null || info == null) {
            return;
        }
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            messages.setValue(new Event<>(getApplication().getString(R.string.empty_query)));
            return;
        }
        UiState previous = state.getValue();
        SearchResult shown = previous != null && previous.status == UiState.Status.RESULTS ? previous.result : null;

        int request = generation.incrementAndGet();
        state.setValue(UiState.searching(info, trimmed, shown));
        SearchOptions options = SearchOptions.defaults().withLimit(PAGE_SIZE).withAutoCorrect(autoCorrect);
        worker.execute(() -> {
            if (request != generation.get()) {
                return; // superseded before it started
            }
            UiState next;
            try {
                next = UiState.results(info, trimmed, current.search(trimmed, options));
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.e(TAG, "Search failed", e);
                next = UiState.searchError(info, trimmed, describe(e));
            }
            publish(request, next);
        });
    }

    /** Leaves search mode and shows the start screen. */
    public void clearResults() {
        IndexInfo info = indexInfo;
        if (info == null) {
            return;
        }
        generation.incrementAndGet();
        state.setValue(UiState.ready(info));
    }

    public void importIndex(@NonNull Uri uri) {
        int request = generation.incrementAndGet();
        state.setValue(UiState.loadingIndex());
        worker.execute(() -> {
            try {
                SearchIndex index = repository.importFrom(uri);
                install(index, IndexInfo.Source.IMPORTED);
                postMessage(plural(R.plurals.index_imported, index.documentCount()));
                publish(request, UiState.ready(indexInfo));
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "Import failed", e);
                postMessage(getApplication().getString(R.string.import_failed, describe(e)));
                IndexInfo info = indexInfo;
                publish(request, info != null ? UiState.ready(info) : UiState.noIndex());
            }
        });
    }

    public void restoreBundledIndex() {
        loadIndex(true);
    }

    private void loadIndex(boolean deleteImportedFirst) {
        int request = generation.incrementAndGet();
        state.setValue(UiState.loadingIndex());
        worker.execute(() -> {
            try {
                if (deleteImportedFirst && !repository.deleteImported()) {
                    postMessage(getApplication().getString(R.string.unknown_error));
                }
                IndexRepository.Loaded loaded = repository.load();
                if (loaded == null) {
                    searcher = null;
                    indexInfo = null;
                    publish(request, UiState.noIndex());
                    return;
                }
                install(loaded.index, loaded.source);
                publish(request, UiState.ready(indexInfo));
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "Loading the index failed", e);
                searcher = null;
                indexInfo = null;
                boolean canRestore = repository.hasImportedIndex() && repository.hasBundledIndex();
                publish(request, UiState.indexError(describe(e), canRestore));
            }
        });
    }

    /** Runs on the worker thread. Building the searcher also builds the spell-correction model. */
    private void install(SearchIndex index, IndexInfo.Source source) {
        Searcher next = new Searcher(index);
        indexInfo = new IndexInfo(index.documentCount(), source, repository.hasBundledIndex());
        searcher = next;
    }

    private void publish(int request, UiState next) {
        mainHandler.post(() -> {
            if (request == generation.get()) {
                state.setValue(next);
            }
        });
    }

    private void postMessage(String message) {
        mainHandler.post(() -> messages.setValue(new Event<>(message)));
    }

    private String plural(@PluralsRes int id, int count) {
        String formatted = NumberFormat.getIntegerInstance().format(count);
        return getApplication().getResources().getQuantityString(id, count, formatted);
    }

    private String describe(Throwable error) {
        if (error instanceof OutOfMemoryError) {
            return getApplication().getString(R.string.out_of_memory);
        }
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? getApplication().getString(R.string.unknown_error)
                : message;
    }

    @Override
    protected void onCleared() {
        generation.incrementAndGet();
        worker.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
