package io.github.ak811.ase.core.analysis;

/**
 * A conservative Persian suffix stripper.
 *
 * <p>Suffixes written after a zero-width non-joiner (or a space, which the analyzer
 * converts to a ZWNJ) are unambiguous and always removed: plural {@code ها/های},
 * comparative/superlative {@code تر/ترین}, and the ezafe/indefinite {@code ی/ای}.
 * Without a joiner only plural and superlative endings are removed, and only when at
 * least three letters remain, so words like {@code تنها} or {@code دختر} are left alone.
 */
final class PersianStemmer {
    private static final String[] JOINED_SUFFIXES = {
            "هایشان", "هایتان", "هایمان", "هایی", "هایم", "هایت", "هایش", "های", "ها", "ترین", "تر", "ای", "ی",
    };
    private static final String[] ATTACHED_SUFFIXES = {
            "هایشان", "هایتان", "هایمان", "هایی", "های", "ها", "ترین",
    };
    private static final int MIN_STEM_LENGTH = 3;

    private PersianStemmer() {
    }

    /** {@code word} is normalized and may contain {@link PersianNormalizer#ZWNJ} boundaries. */
    static String stem(String word) {
        int joiner = word.lastIndexOf(PersianNormalizer.ZWNJ);
        if (joiner > 0) {
            String tail = word.substring(joiner + 1);
            for (String suffix : JOINED_SUFFIXES) {
                if (tail.equals(suffix)) {
                    return Analyzer.removeJoiners(word.substring(0, joiner));
                }
            }
        }
        String plain = Analyzer.removeJoiners(word);
        for (String suffix : ATTACHED_SUFFIXES) {
            if (plain.endsWith(suffix) && plain.length() - suffix.length() >= MIN_STEM_LENGTH) {
                return plain.substring(0, plain.length() - suffix.length());
            }
        }
        return plain;
    }
}
