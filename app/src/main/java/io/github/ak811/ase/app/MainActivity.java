package io.github.ak811.ase.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.snackbar.Snackbar;

import java.text.NumberFormat;
import java.util.Locale;

import io.github.ak811.ase.R;
import io.github.ak811.ase.core.index.Document;
import io.github.ak811.ase.core.search.MatchMode;
import io.github.ak811.ase.core.search.SearchHit;
import io.github.ak811.ase.core.search.SearchResult;
import io.github.ak811.ase.databinding.ActivityMainBinding;

public final class MainActivity extends AppCompatActivity {
    private static final int MAX_DIALOG_CHARS = 20_000;

    private ActivityMainBinding binding;
    private SearchViewModel viewModel;
    private SearchResultAdapter adapter;
    @Nullable
    private UiState currentState;
    @Nullable
    private SearchResult shownResult;

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    viewModel.importIndex(uri);
                }
            });

    private final OnBackPressedCallback backToStart = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            viewModel.clearResults();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyWindowInsets();

        viewModel = new ViewModelProvider(this).get(SearchViewModel.class);

        adapter = new SearchResultAdapter(this::openHit);
        binding.resultsList.setLayoutManager(new LinearLayoutManager(this));
        binding.resultsList.setAdapter(adapter);
        binding.resultsList.addItemDecoration(
                new MaterialDividerItemDecoration(this, LinearLayoutManager.VERTICAL));

        binding.searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch();
                return true;
            }
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    submitSearch();
                }
                return true;
            }
            return false;
        });
        binding.searchLayout.setStartIconOnClickListener(v -> submitSearch());
        binding.correctionText.setOnClickListener(v -> searchOriginalQuery());
        binding.importButton.setOnClickListener(v -> launchImport());
        binding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        getOnBackPressedDispatcher().addCallback(this, backToStart);

        viewModel.state().observe(this, this::render);
        viewModel.messages().observe(this, event -> {
            String message = event.consume();
            if (message != null) {
                Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void render(UiState state) {
        currentState = state;
        boolean busy = state.status == UiState.Status.LOADING_INDEX || state.status == UiState.Status.SEARCHING;
        binding.progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        binding.searchLayout.setEnabled(state.index != null);
        backToStart.setEnabled(state.isSearchMode());
        updateMenu(state);

        binding.statusText.setVisibility(View.GONE);
        binding.correctionText.setVisibility(View.GONE);
        binding.importButton.setVisibility(View.GONE);

        switch (state.status) {
            case LOADING_INDEX:
                showMessage(getString(R.string.loading_index));
                break;
            case NO_INDEX:
                showMessage(getString(R.string.no_index));
                binding.importButton.setVisibility(View.VISIBLE);
                break;
            case INDEX_ERROR:
                showMessage(getString(R.string.index_error, state.error));
                binding.importButton.setVisibility(View.VISIBLE);
                break;
            case READY:
                IndexInfo info = state.index;
                int count = info == null ? 0 : info.documentCount;
                showMessage(getResources().getQuantityString(R.plurals.documents_indexed, count, formatCount(count)));
                break;
            case SEARCHING:
                if (state.result != null) {
                    renderResult(state.result); // keep the previous results visible while searching
                } else {
                    hideMessage();
                    showResults(false);
                }
                break;
            case RESULTS:
                if (state.result != null) {
                    renderResult(state.result);
                }
                break;
            case SEARCH_ERROR:
                showMessage(getString(R.string.search_error, state.error));
                break;
        }
    }

    private void renderResult(@NonNull SearchResult result) {
        if (result.wasCorrected()) {
            binding.correctionText.setText(
                    getString(R.string.showing_results_for, result.correctedQuery(), result.query()));
            binding.correctionText.setVisibility(View.VISIBLE);
        }

        if (result.totalHits() == 0) {
            String shown = result.wasCorrected() ? result.correctedQuery() : result.query();
            showMessage(getString(R.string.no_results, shown));
            return;
        }

        String seconds = String.format(Locale.getDefault(), "%.3f", result.tookSeconds());
        String summary = getResources().getQuantityString(
                R.plurals.results_summary, result.totalHits(), formatCount(result.totalHits()), seconds);
        if (result.matchMode() == MatchMode.ANY_TERMS) {
            summary = summary + "\n" + getString(R.string.partial_matches);
        }
        binding.statusText.setText(summary);
        binding.statusText.setVisibility(View.VISIBLE);

        hideMessage();
        showResults(true);
        if (result != shownResult) {
            shownResult = result;
            adapter.submitList(result.hits(), () -> binding.resultsList.scrollToPosition(0));
        }
    }

    private void showMessage(String message) {
        binding.messageText.setText(message);
        binding.messagePanel.setVisibility(View.VISIBLE);
        showResults(false);
    }

    private void hideMessage() {
        binding.messagePanel.setVisibility(View.GONE);
    }

    private void showResults(boolean visible) {
        binding.resultsList.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            shownResult = null;
            adapter.submitList(null);
        }
    }

    private void updateMenu(UiState state) {
        MenuItem restore = binding.toolbar.getMenu().findItem(R.id.action_restore_bundled);
        if (restore != null) {
            restore.setVisible(state.canRestoreBundled);
        }
        MenuItem importItem = binding.toolbar.getMenu().findItem(R.id.action_import);
        if (importItem != null) {
            importItem.setEnabled(state.status != UiState.Status.LOADING_INDEX);
        }
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_import) {
            launchImport();
            return true;
        }
        if (id == R.id.action_restore_bundled) {
            viewModel.restoreBundledIndex();
            return true;
        }
        return false;
    }

    private void submitSearch() {
        CharSequence text = binding.searchInput.getText();
        hideKeyboard();
        viewModel.search(text == null ? "" : text.toString(), true);
    }

    /** "Search instead for …": run the original query without spelling correction. */
    private void searchOriginalQuery() {
        UiState state = currentState;
        if (state == null || state.result == null || !state.result.wasCorrected()) {
            return;
        }
        String original = state.result.query();
        binding.searchInput.setText(original);
        binding.searchInput.setSelection(binding.searchInput.length());
        viewModel.search(original, false);
    }

    private void launchImport() {
        try {
            importLauncher.launch(new String[]{"*/*"});
        } catch (ActivityNotFoundException e) {
            Snackbar.make(binding.getRoot(), R.string.cannot_open_link, Snackbar.LENGTH_LONG).show();
        }
    }

    private void openHit(SearchHit hit) {
        Document document = hit.document();
        Uri uri = Uri.parse(document.url());
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
            } catch (ActivityNotFoundException e) {
                Snackbar.make(binding.getRoot(), R.string.cannot_open_link, Snackbar.LENGTH_LONG).show();
            }
            return;
        }
        // Local documents (e.g. from a .txt corpus) have no web page: show the text instead.
        String body = document.body();
        if (body.length() > MAX_DIALOG_CHARS) {
            body = body.substring(0, MAX_DIALOG_CHARS) + "…";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(document.title().isEmpty() ? getString(R.string.untitled) : document.title())
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void hideKeyboard() {
        WindowCompat.getInsetsController(getWindow(), binding.searchInput)
                .hide(WindowInsetsCompat.Type.ime());
        binding.searchInput.clearFocus();
    }

    private static String formatCount(int count) {
        return NumberFormat.getIntegerInstance().format(count);
    }
}
