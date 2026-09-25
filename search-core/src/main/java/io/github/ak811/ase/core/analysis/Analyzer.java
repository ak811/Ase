package io.github.ak811.ase.core.analysis;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns text into index terms, for any language.
 *
 * <ol>
 *   <li><b>Segmentation.</b> Words are runs of letters (plus combining marks and in-word
 *       joiners/apostrophes) from one {@link ScriptGroup}; numbers are separate words unless
 *       glued to Latin, Cyrillic or Greek letters ({@code mp3}, {@code covid19}). Scripts written
 *       without spaces (Chinese, Japanese, Thai, …) are split into overlapping character bigrams.</li>
 *   <li><b>Normalization per script.</b> NFKC and case folding everywhere; accents folded for
 *       Latin and Greek ({@code café → cafe}, {@code ß → ss}); stress marks and {@code ё} folded for
 *       Cyrillic; niqqud removed for Hebrew; Arabic-script letters unified (see
 *       {@link PersianNormalizer}); every kind of digit becomes ASCII.</li>
 *   <li><b>Persian affixes.</b> Detached verb prefixes ({@code می}, {@code نمی}) and plural or
 *       superlative suffixes ({@code ها}, {@code های}, {@code ترین}) written with a space are joined to
 *       their word, so {@code می خواهم} = {@code می‌خواهم} = {@code میخواهم}.</li>
 *   <li><b>Stemming</b> (optional): Porter for English, light suffix stripping for Persian.</li>
 *   <li><b>Stop words</b> are flagged, not removed, so phrase queries still work.</li>
 * </ol>
 *
 * Stateless and thread-safe.
 */
public final class Analyzer {
    /** Longer tokens are almost always noise (hashes, base64) and are dropped. */
    public static final int MAX_TERM_LENGTH = 64;

    private static final Set<String> PERSIAN_PREFIXES = Set.of("می", "نمی");
    private static final Set<String> PERSIAN_SUFFIXES = Set.of(
            "ها", "های", "هایی", "هایم", "هایت", "هایش", "هایمان", "هایتان", "هایشان", "ترین");

    private final AnalyzerConfig config;

    public Analyzer(AnalyzerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is null");
        }
        this.config = config;
    }

    public AnalyzerConfig config() {
        return config;
    }

    public List<Token> analyze(CharSequence text) {
        return analyze(text, 0);
    }

    /** Analyzes {@code text}, numbering positions from {@code firstPosition}. */
    public List<Token> analyze(CharSequence text, int firstPosition) {
        List<Token> tokens = new ArrayList<>();
        if (text == null || text.length() == 0) {
            return tokens;
        }
        List<Word> words = segment(text);
        mergePersianAffixes(text, words);

        int position = firstPosition;
        for (Word word : words) {
            if (word.group.usesBigrams()) {
                position = emitBigrams(text, word, position, tokens);
                continue;
            }
            String surface = removeJoiners(word.normalized);
            if (surface.isEmpty() || surface.length() > MAX_TERM_LENGTH) {
                continue;
            }
            String term = stem(word.group, word.normalized, surface);
            if (term.isEmpty()) {
                continue;
            }
            boolean stopword = StopWords.contains(word.group, surface)
                    || (word.group == ScriptGroup.ARABIC
                    && (PERSIAN_SUFFIXES.contains(surface) || PERSIAN_PREFIXES.contains(surface)));
            tokens.add(new Token(term, surface, word.start, word.end, position++, word.group, stopword));
        }
        return tokens;
    }

    private String stem(ScriptGroup group, String normalized, String surface) {
        if (!config.stemming()) {
            return surface;
        }
        switch (group) {
            case LATIN:
                return isAsciiLetters(surface) ? PorterStemmer.stem(surface) : surface;
            case ARABIC:
                return PersianStemmer.stem(normalized);
            default:
                return surface;
        }
    }

    // ---------------------------------------------------------------- segmentation

    private static final class Word {
        final int start;
        final int end;
        final ScriptGroup group;
        String normalized;

        Word(int start, int end, ScriptGroup group, String normalized) {
            this.start = start;
            this.end = end;
            this.group = group;
            this.normalized = normalized;
        }
    }

    private static List<Word> segment(CharSequence text) {
        List<Word> words = new ArrayList<>();
        int length = text.length();
        ScriptGroup group = null;
        int runStart = 0;
        int runEnd = 0;
        int i = 0;
        while (i < length) {
            int cp = Character.codePointAt(text, i);
            int next = i + Character.charCount(cp);
            if (Character.isLetter(cp)) {
                ScriptGroup letterGroup = ScriptGroup.ofLetter(cp);
                boolean continues = group != null && (letterGroup == null || letterGroup == group
                        || (group == ScriptGroup.DIGIT && joinsDigits(letterGroup)));
                if (continues) {
                    if (group == ScriptGroup.DIGIT && letterGroup != null) {
                        group = letterGroup;
                    }
                    runEnd = next;
                } else {
                    addWord(text, words, group, runStart, runEnd);
                    group = letterGroup == null ? ScriptGroup.OTHER : letterGroup;
                    runStart = i;
                    runEnd = next;
                }
            } else if (Character.isDigit(cp)) {
                if (group == ScriptGroup.DIGIT || joinsDigits(group)) {
                    runEnd = next;
                } else {
                    addWord(text, words, group, runStart, runEnd);
                    group = ScriptGroup.DIGIT;
                    runStart = i;
                    runEnd = next;
                }
            } else if (isMark(cp)) {
                if (group != null) {
                    runEnd = next;
                }
            } else if (cp == PersianNormalizer.ZWNJ || cp == PersianNormalizer.ZWJ) {
                // Joiners belong inside Arabic-script and Indic words; the run end is not advanced,
                // so a trailing joiner is dropped.
                if (group != ScriptGroup.ARABIC && group != ScriptGroup.OTHER) {
                    addWord(text, words, group, runStart, runEnd);
                    group = null;
                }
            } else if (isApostrophe(cp) && group == ScriptGroup.LATIN && next < length
                    && ScriptGroup.ofLetter(Character.codePointAt(text, next)) == ScriptGroup.LATIN) {
                // don't, o'clock: keep the word together
            } else {
                addWord(text, words, group, runStart, runEnd);
                group = null;
            }
            i = next;
        }
        addWord(text, words, group, runStart, runEnd);
        return words;
    }

    private static boolean joinsDigits(ScriptGroup group) {
        return group == ScriptGroup.LATIN || group == ScriptGroup.CYRILLIC || group == ScriptGroup.GREEK;
    }

    private static void addWord(CharSequence text, List<Word> words, ScriptGroup group, int start, int end) {
        if (group == null || end <= start) {
            return;
        }
        String normalized = group.usesBigrams() ? null : normalize(group, text, start, end);
        words.add(new Word(start, end, group, normalized));
    }

    private static void mergePersianAffixes(CharSequence text, List<Word> words) {
        int i = 0;
        while (i + 1 < words.size()) {
            Word left = words.get(i);
            Word right = words.get(i + 1);
            if (left.group == ScriptGroup.ARABIC && right.group == ScriptGroup.ARABIC
                    && onlySpacesBetween(text, left.end, right.start)
                    && (PERSIAN_PREFIXES.contains(removeJoiners(left.normalized))
                    || PERSIAN_SUFFIXES.contains(removeJoiners(right.normalized)))) {
                words.set(i, new Word(left.start, right.end, ScriptGroup.ARABIC,
                        left.normalized + PersianNormalizer.ZWNJ + right.normalized));
                words.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    private static boolean onlySpacesBetween(CharSequence text, int from, int to) {
        if (from >= to) {
            return false;
        }
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\u00A0' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static int emitBigrams(CharSequence text, Word word, int position, List<Token> out) {
        IntListPair units = new IntListPair();
        int i = word.start;
        while (i < word.end) {
            int cp = Character.codePointAt(text, i);
            int next = i + Character.charCount(cp);
            if (isMark(cp) && units.size() > 0) {
                units.setLastEnd(next);
            } else {
                units.add(i, next);
            }
            i = next;
        }
        if (units.size() == 1) {
            String term = foldGeneric(text.subSequence(units.start(0), units.end(0)).toString());
            out.add(new Token(term, term, units.start(0), units.end(0), position++, word.group, false));
            return position;
        }
        for (int u = 0; u + 1 < units.size(); u++) {
            int start = units.start(u);
            int end = units.end(u + 1);
            String term = foldGeneric(text.subSequence(start, end).toString());
            out.add(new Token(term, term, start, end, position++, word.group, false));
        }
        return position;
    }

    /** Start/end offsets of the character units (base letter + marks) of a bigram word. */
    private static final class IntListPair {
        private int[] starts = new int[16];
        private int[] ends = new int[16];
        private int size;

        void add(int start, int end) {
            if (size == starts.length) {
                starts = java.util.Arrays.copyOf(starts, size * 2);
                ends = java.util.Arrays.copyOf(ends, size * 2);
            }
            starts[size] = start;
            ends[size] = end;
            size++;
        }

        void setLastEnd(int end) {
            ends[size - 1] = end;
        }

        int size() {
            return size;
        }

        int start(int i) {
            return starts[i];
        }

        int end(int i) {
            return ends[i];
        }
    }

    // ---------------------------------------------------------------- normalization

    static String normalize(ScriptGroup group, CharSequence text, int start, int end) {
        switch (group) {
            case ARABIC:
                return PersianNormalizer.normalize(text, start, end);
            case LATIN:
            case GREEK:
                return foldLatinOrGreek(text.subSequence(start, end).toString());
            case CYRILLIC:
                return foldCyrillic(text.subSequence(start, end).toString());
            case HEBREW:
                return stripMarks(foldGeneric(text.subSequence(start, end).toString()));
            default:
                return foldGeneric(text.subSequence(start, end).toString());
        }
    }

    private static String foldLatinOrGreek(String raw) {
        String decomposed = Normalizer.normalize(
                Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (isMark(c)) {
                continue;
            }
            switch (c) {
                case '\'':
                case '\u2019':
                case '\u02BC':
                    out.append('\'');
                    break;
                case 'ß':
                    out.append("ss");
                    break;
                case 'æ':
                    out.append("ae");
                    break;
                case 'œ':
                    out.append("oe");
                    break;
                case 'ø':
                    out.append('o');
                    break;
                case 'đ':
                case 'ð':
                    out.append('d');
                    break;
                case 'þ':
                    out.append("th");
                    break;
                case 'ł':
                    out.append('l');
                    break;
                case 'ı':
                    out.append('i');
                    break;
                case 'ħ':
                    out.append('h');
                    break;
                case 'ς':
                    out.append('σ');
                    break;
                default:
                    appendDigitOrChar(out, c);
                    break;
            }
        }
        String folded = out.toString();
        if (folded.endsWith("'s")) {
            folded = folded.substring(0, folded.length() - 2);
        }
        return folded.replace("'", "");
    }

    private static String foldCyrillic(String raw) {
        String decomposed = Normalizer.normalize(
                Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (c == '\u0300' || c == '\u0301') {
                continue; // stress marks
            }
            appendDigitOrChar(out, c);
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC).replace('ё', 'е');
    }

    static String foldGeneric(String raw) {
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c == PersianNormalizer.ZWNJ || c == PersianNormalizer.ZWJ) {
                continue;
            }
            appendDigitOrChar(out, c);
        }
        return out.toString();
    }

    private static String stripMarks(String text) {
        StringBuilder out = new StringBuilder(text.length());
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (!isMark(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void appendDigitOrChar(StringBuilder out, char c) {
        if (Character.isDigit(c)) {
            int digit = Character.digit(c, 10);
            if (digit >= 0) {
                out.append((char) ('0' + digit));
                return;
            }
        }
        out.append(c);
    }

    static boolean isMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isApostrophe(int codePoint) {
        return codePoint == '\'' || codePoint == '\u2019' || codePoint == '\u02BC';
    }

    private static boolean isAsciiLetters(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    static String removeJoiners(String word) {
        if (word.indexOf(PersianNormalizer.ZWNJ) < 0 && word.indexOf(PersianNormalizer.ZWJ) < 0) {
            return word;
        }
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c != PersianNormalizer.ZWNJ && c != PersianNormalizer.ZWJ) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
