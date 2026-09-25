package io.github.ak811.ase.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into normalized terms.
 *
 * <p>A token is a maximal run of Arabic-script letters (with their diacritics and
 * any zero-width joiners inside the word), or a maximal run of other letters and
 * digits (Latin words, numbers). Everything else — whitespace, punctuation such
 * as {@code ، ؛ ؟} — separates tokens. The class is stateless and thread-safe.
 */
public final class Tokenizer {
    /** Longer tokens are almost always noise (base64, hashes, URLs) and are dropped. */
    public static final int MAX_TERM_LENGTH = 64;

    private static final int OTHER = 0;
    private static final int PERSIAN = 1;
    private static final int ALNUM = 2;
    private static final int MARK = 3;
    private static final int JOINER = 4;

    public List<Token> tokenize(CharSequence text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        int length = text.length();
        int i = 0;
        while (i < length) {
            int kind = classify(text.charAt(i));
            if (kind != PERSIAN && kind != ALNUM) {
                i++;
                continue;
            }
            int start = i++;
            while (i < length) {
                int next = classify(text.charAt(i));
                if (next == kind || next == MARK || (next == JOINER && kind == PERSIAN)) {
                    i++;
                } else {
                    break;
                }
            }
            int end = i;
            while (end > start && classify(text.charAt(end - 1)) == JOINER) {
                end--;
            }
            String term = PersianNormalizer.normalize(text, start, end);
            if (!term.isEmpty() && term.length() <= MAX_TERM_LENGTH) {
                tokens.add(new Token(term, start, end));
            }
        }
        return tokens;
    }

    /** Convenience: only the normalized terms, in order, duplicates included. */
    public List<String> terms(CharSequence text) {
        List<Token> tokens = tokenize(text);
        List<String> terms = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            terms.add(token.term());
        }
        return terms;
    }

    private static int classify(char c) {
        if (c == PersianNormalizer.ZWNJ || c == PersianNormalizer.ZWJ) {
            return JOINER;
        }
        if (Character.isLetter(c)) {
            return PersianNormalizer.isArabicScript(c) ? PERSIAN : ALNUM;
        }
        if (Character.isDigit(c)) {
            return ALNUM;
        }
        if (PersianNormalizer.isMark(c)) {
            return MARK;
        }
        return OTHER;
    }
}
