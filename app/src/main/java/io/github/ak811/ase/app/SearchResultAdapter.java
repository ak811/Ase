package io.github.ak811.ase.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import io.github.ak811.ase.R;
import io.github.ak811.ase.core.index.Document;
import io.github.ak811.ase.core.search.SearchHit;
import io.github.ak811.ase.databinding.ItemSearchResultBinding;

final class SearchResultAdapter extends ListAdapter<SearchHit, SearchResultAdapter.ViewHolder> {

    interface OnHitClickListener {
        void onHitClick(SearchHit hit);
    }

    private static final DiffUtil.ItemCallback<SearchHit> DIFF = new DiffUtil.ItemCallback<SearchHit>() {
        @Override
        public boolean areItemsTheSame(@NonNull SearchHit a, @NonNull SearchHit b) {
            return a.document().id() == b.document().id();
        }

        @Override
        public boolean areContentsTheSame(@NonNull SearchHit a, @NonNull SearchHit b) {
            return a.title().equals(b.title()) && a.excerpt().equals(b.excerpt());
        }
    };

    private final OnHitClickListener listener;

    SearchResultAdapter(OnHitClickListener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemSearchResultBinding binding =
                ItemSearchResultBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    final class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemSearchResultBinding binding;

        ViewHolder(ItemSearchResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.getRoot().setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onHitClick(getItem(position));
                }
            });
        }

        void bind(SearchHit hit) {
            Document document = hit.document();
            if (!document.title().isEmpty()) {
                binding.title.setText(Highlighting.toStyledText(hit.title()));
            } else if (!document.url().isEmpty()) {
                binding.title.setText(document.url());
            } else {
                binding.title.setText(R.string.untitled);
            }

            binding.url.setText(document.url());
            binding.url.setVisibility(document.url().isEmpty() ? View.GONE : View.VISIBLE);

            CharSequence excerpt = Highlighting.toStyledText(hit.excerpt());
            binding.snippet.setText(excerpt);
            binding.snippet.setVisibility(excerpt.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }
}
