package io.github.ak811.ase.core.text;

import java.text.Normalizer;

/**
 * Canonicalizes Persian text (and incidental Arabic or Latin text) so that
 * words which look the same but use different code points become one term.
 *
 * <ul>
 *   <li>Arabic yeh/alef maksura ({@code ي ى}) become Persian yeh ({@code ی}).</li>
 *   <li>Arabic kaf ({@code ك}) becomes Persian keheh ({@code ک}).</li>
 *   <li>Teh marbuta and heh-with-hamza ({@code ة ۀ}) become heh ({@code ه}).</li>
 *   <li>Hamza-carrying alefs ({@code أ إ ٱ}) become alef; {@code ؤ} becomes waw.</li>
 *   <li>Diacritics (harakat), tatweel, ZWNJ/ZWJ and bidi marks are removed,
 *       so {@code می‌روم} and {@code میروم} match.</li>
 *   <li>Arabic presentation forms are folded with NFKC.</li>
 *   <li>Persian and Arabic-Indic digits become ASCII digits; letters are lower-cased.</li>
 * </ul>
 *
 * The normalizer never changes the raw text it is given; it only produces
 * the normalized term, so token offsets stay valid for highlighting.
 */
public final class PersianNormalizer {
    static final char ZWNJ = '\u200C';
    static final char ZWJ = '\u200D';

    private PersianNormalizer() {
    }

    public static String normalize(CharSequence text) {
        return normalize(text, 0, text.length());
    }

    public static String normalize(CharSequence text, int start, int end) {
        StringBuilder out = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (isPresentationForm(c)) {
                String folded = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
                for (int j = 0; j < folded.length(); j++) {
                    appendCanonical(out, folded.charAt(j));
                }
            } else {
                appendCanonical(out, c);
            }
        }
        return out.toString();
    }

    private static void appendCanonical(StringBuilder out, char c) {
        switch (c) {
            case ZWNJ:
            case ZWJ:
            case '\u0640': // tatweel
            case '\u200E': // left-to-right mark
            case '\u200F': // right-to-left mark
            case '\uFEFF': // zero-width no-break space / BOM
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
        if (isMark(c)) {
            return;
        }
        if (Character.isDigit(c)) {
            int digit = Character.digit(c, 10);
            if (digit >= 0) {
                out.append((char) ('0' + digit));
                return;
            }
        }
        out.append(Character.toLowerCase(c));
    }

    static boolean isArabicScript(char c) {
        return (c >= '\u0600' && c <= '\u06FF')
                || (c >= '\u0750' && c <= '\u077F')
                || (c >= '\u08A0' && c <= '\u08FF')
                || isPresentationForm(c);
    }

    static boolean isPresentationForm(char c) {
        return (c >= '\uFB50' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFE');
    }

    static boolean isMark(char c) {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
}
