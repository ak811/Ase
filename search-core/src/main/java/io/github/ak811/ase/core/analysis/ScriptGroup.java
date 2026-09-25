package io.github.ak811.ase.core.analysis;

/**
 * Writing systems the analyzer treats differently. Words never span two groups,
 * so mixed text such as {@code iPhoneهای} splits at the script boundary.
 */
public enum ScriptGroup {
    LATIN,
    /** Arabic script: Persian, Arabic, Urdu, … */
    ARABIC,
    CYRILLIC,
    GREEK,
    HEBREW,
    HANGUL,
    /** Han, Hiragana, Katakana, Bopomofo: no spaces between words, indexed as character bigrams. */
    CJK,
    /** Thai, Lao, Khmer, Myanmar: no spaces between words, indexed as character bigrams. */
    SOUTHEAST_ASIAN,
    /** Standalone numbers. */
    DIGIT,
    /** Any other script (Devanagari, Armenian, Georgian, Ethiopic, …): Unicode normalization only. */
    OTHER;

    /** True for scripts written without spaces, which are indexed as overlapping character bigrams. */
    public boolean usesBigrams() {
        return this == CJK || this == SOUTHEAST_ASIAN;
    }

    /** The group of a letter, or {@code null} for letters of the Common/Inherited scripts. */
    static ScriptGroup ofLetter(int codePoint) {
        switch (Character.UnicodeScript.of(codePoint)) {
            case LATIN:
                return LATIN;
            case ARABIC:
                return ARABIC;
            case CYRILLIC:
                return CYRILLIC;
            case GREEK:
                return GREEK;
            case HEBREW:
                return HEBREW;
            case HANGUL:
                return HANGUL;
            case HAN:
            case HIRAGANA:
            case KATAKANA:
            case BOPOMOFO:
                return CJK;
            case THAI:
            case LAO:
            case KHMER:
            case MYANMAR:
                return SOUTHEAST_ASIAN;
            case COMMON:
            case INHERITED:
                return null;
            default:
                return OTHER;
        }
    }
}
