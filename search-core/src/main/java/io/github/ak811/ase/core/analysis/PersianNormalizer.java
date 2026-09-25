package io.github.ak811.ase.core.analysis;

import java.text.Normalizer;

/**
 * Canonicalizes Arabic-script text so visually identical words match:
 * Arabic yeh/kaf become Persian {@code ی}/{@code ک}; {@code ة ۀ ہ} become {@code ه};
 * hamza-carrying alefs become {@code ا}; {@code ؤ} becomes {@code و}; diacritics, tatweel,
 * and bidi marks are removed; presentation forms are folded; digits become ASCII.
 *
 * <p>Zero-width joiners are kept as a single {@link #ZWNJ} so the stemmer can see
 * word-internal boundaries such as {@code کتاب‌ها}; {@link Analyzer} removes them afterwards.
 */
final class PersianNormalizer {
    static final char ZWNJ = '\u200C';
    static final char ZWJ = '\u200D';

    private PersianNormalizer() {
    }

    static String normalize(CharSequence text, int start, int end) {
        StringBuilder out = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (isPresentationForm(c)) {
                String folded = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
                for (int j = 0; j < folded.length(); j++) {
                    append(out, folded.charAt(j));
                }
            } else {
                append(out, c);
            }
        }
        // Joiners at either end carry no meaning.
        int from = 0;
        int to = out.length();
        while (from < to && out.charAt(from) == ZWNJ) {
            from++;
        }
        while (to > from && out.charAt(to - 1) == ZWNJ) {
            to--;
        }
        return out.substring(from, to);
    }

    private static void append(StringBuilder out, char c) {
        switch (c) {
            case ZWNJ:
            case ZWJ:
                if (out.length() > 0 && out.charAt(out.length() - 1) != ZWNJ) {
                    out.append(ZWNJ);
                }
                return;
            case '\u0640': // tatweel
            case '\u200E': // left-to-right mark
            case '\u200F': // right-to-left mark
            case '\uFEFF': // byte-order mark
                return;
            case '\u064A': // ي
            case '\u0649': // ى
                out.append('\u06CC');
                return;
            case '\u0643': // ك
                out.append('\u06A9');
                return;
            case '\u0629': // ة
            case '\u06C0': // ۀ
            case '\u06C1': // ہ
                out.append('\u0647');
                return;
            case '\u0623': // أ
            case '\u0625': // إ
            case '\u0671': // ٱ
                out.append('\u0627');
                return;
            case '\u0624': // ؤ
                out.append('\u0648');
                return;
            default:
                break;
        }
        if (Analyzer.isMark(c)) {
            return;
        }
        if (Character.isDigit(c)) {
            out.append((char) ('0' + Character.digit(c, 10)));
            return;
        }
        out.append(Character.toLowerCase(c));
    }

    private static boolean isPresentationForm(char c) {
        return (c >= '\uFB50' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFE');
    }
}
