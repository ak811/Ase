package io.github.ak811.ase.core.spell;

/**
 * Optimal-string-alignment (restricted Damerau–Levenshtein) distance where
 * substituting one confusable letter for another costs less than a full edit.
 */
public final class EditDistance {
    static final double CONFUSABLE_SUBSTITUTION_COST = 0.5;

    private EditDistance() {
    }

    public static double weighted(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        double[] beforePrevious = new double[m + 1];
        double[] previous = new double[m + 1];
        double[] current = new double[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                double substitution = ca == cb ? 0
                        : ConfusableLetters.areConfusable(ca, cb) ? CONFUSABLE_SUBSTITUTION_COST : 1;
                double value = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + substitution);
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    value = Math.min(value, beforePrevious[j - 2] + 1);
                }
                current[j] = value;
            }
            double[] recycled = beforePrevious;
            beforePrevious = previous;
            previous = current;
            current = recycled;
        }
        return previous[m];
    }
}
