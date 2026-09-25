package io.github.ak811.ase.app;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

import io.github.ak811.ase.core.search.Highlight;
import io.github.ak811.ase.core.search.Snippet;

/** Converts core snippets into styled text. */
final class Highlighting {
    private Highlighting() {
    }

    static CharSequence toStyledText(Snippet snippet) {
        String text = snippet.text();
        if (snippet.highlights().isEmpty()) {
            return text;
        }
        SpannableString styled = new SpannableString(text);
        int length = text.length();
        for (Highlight highlight : snippet.highlights()) {
            int start = Math.max(0, Math.min(highlight.start(), length));
            int end = Math.max(start, Math.min(highlight.end(), length));
            if (start < end) {
                styled.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return styled;
    }
}
