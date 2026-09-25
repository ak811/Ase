package io.github.ak811.ase.core.spell;

import io.github.ak811.ase.core.index.Lexicon;
import io.github.ak811.ase.core.util.IntList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suggests a word from the corpus for a word that does not occur in it. Works for any
 * alphabetic script; Persian also gets confusable-letter handling.
 *
 * <ol>
 *   <li><b>Confusable letters.</b> Swap one letter for a commonly confused one
 *       ({@code کتاپ → کتاب}); take the variant found in the most documents.</li>
 *   <li><b>Character bigrams.</b> Words sharing boundary-padded bigrams are scored by Jaccard
 *       similarity, filtered by an edit distance in which confusable substitutions cost 0.5,
 *       and ranked by (distance, document frequency, similarity).</li>
 * </ol>
 *
 * Only the most frequent {@code maxWords} words are candidates, which bounds memory.
 * Suggestions always come from the corpus, so a corrected query can return results.
 * Thread-safe.
 */
public final class SpellCorrector {
    public static final int DEFAULT_MAX_WORDS = 300_000;

    private static final int MIN_WORD_LENGTH = 2;
    private static final int MAX_WORD_LENGTH = 32;
    private static final int MAX_LENGTH_DIFFERENCE = 2;
    private static final double MIN_JACCARD = 0.3;
    private static final double EPSILON = 1e-9;

    private final Lexicon lexicon;
    private final int[] lexiconIds;
    private final int[] bigramCounts;
    private final Map<String, int[]> bigramIndex;

    // Scratch space reused across calls; guarded by "this".
    private final int[] shared;
    private final IntList touched = new IntList();

    public SpellCorrector(Lexicon lexicon) {
        this(lexicon, DEFAULT_MAX_WORDS);
    }

    public SpellCorrector(Lexicon lexicon, int maxWords) {
        this.lexicon = lexicon;
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < lexicon.size(); i++) {
            if (isCorrectable(lexicon.word(i))) {
                candidates.add(i);
            }
        }
        if (candidates.size() > maxWords) {
            candidates.sort((a, b) -> Integer.compare(lexicon.documentFrequency(b), lexicon.documentFrequency(a)));
            candidates = new ArrayList<>(candidates.subList(0, maxWords));
            Collections.sort(candidates);
        }
        lexiconIds = new int[candidates.size()];
        bigramCounts = new int[candidates.size()];
        Map<String, IntList> lists = new HashMap<>();
        for (int local = 0; local < candidates.size(); local++) {
            lexiconIds[local] = candidates.get(local);
            Set<String> bigrams = bigrams(lexicon.word(lexiconIds[local]));
            bigramCounts[local] = bigrams.size();
            for (String bigram : bigrams) {
                lists.computeIfAbsent(bigram, k -> new IntList(4)).add(local);
            }
        }
        bigramIndex = new HashMap<>(lists.size() * 4 / 3 + 1);
        for (Map.Entry<String, IntList> entry : lists.entrySet()) {
            bigramIndex.put(entry.getKey(), entry.getValue().toArray());
        }
        shared = new int[candidates.size()];
    }

    /**
     * Returns a corpus word close to {@code word} (a normalized, unstemmed surface form),
     * or {@code null} if the word already occurs in the corpus or nothing is close enough.
     */
    public synchronized Correction suggest(String word) {
        if (word == null || !isCorrectable(word) || lexicon.indexOf(word) >= 0) {
            return null;
        }
        int best = bestConfusableVariant(word);
        if (best < 0) {
            best = bestBigramCandidate(word);
        }
        return best < 0 ? null : new Correction(lexicon.word(best), lexicon.display(best));
    }

    private int bestConfusableVariant(String word) {
        char[] chars = word.toCharArray();
        int best = -1;
        for (int i = 0; i < chars.length; i++) {
            char original = chars[i];
            for (char alternative : ConfusableLetters.alternativesFor(original)) {
                chars[i] = alternative;
                int index = lexicon.indexOf(new String(chars));
                if (index >= 0 && (best < 0 || lexicon.documentFrequency(index) > lexicon.documentFrequency(best)
                        || (lexicon.documentFrequency(index) == lexicon.documentFrequency(best) && index < best))) {
                    best = index;
                }
            }
            chars[i] = original;
        }
        return best;
    }

    private int bestBigramCandidate(String word) {
        Set<String> queryBigrams = bigrams(word);
        touched.clear();
        for (String bigram : queryBigrams) {
            int[] ids = bigramIndex.get(bigram);
            if (ids == null) {
                continue;
            }
            for (int local : ids) {
                if (Math.abs(lexicon.word(lexiconIds[local]).length() - word.length()) > MAX_LENGTH_DIFFERENCE) {
                    continue;
                }
                if (shared[local]++ == 0) {
                    touched.add(local);
                }
            }
        }

        double maxDistance = word.length() <= 4 ? 1.0 : 2.0;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        double bestJaccard = 0;
        for (int i = 0; i < touched.size(); i++) {
            int local = touched.get(i);
            int common = shared[local];
            shared[local] = 0;
            double jaccard = common / (double) (queryBigrams.size() + bigramCounts[local] - common);
            if (jaccard < MIN_JACCARD) {
                continue;
            }
            int index = lexiconIds[local];
            double distance = EditDistance.weighted(word, lexicon.word(index));
            if (distance > maxDistance + EPSILON) {
                continue;
            }
            if (best < 0 || isBetter(distance, index, jaccard, bestDistance, best, bestJaccard)) {
                best = index;
                bestDistance = distance;
                bestJaccard = jaccard;
            }
        }
        touched.clear();
        return best;
    }

    private boolean isBetter(double distance, int index, double jaccard,
                             double bestDistance, int best, double bestJaccard) {
        if (Math.abs(distance - bestDistance) > EPSILON) {
            return distance < bestDistance;
        }
        int frequency = lexicon.documentFrequency(index);
        int bestFrequency = lexicon.documentFrequency(best);
        if (frequency != bestFrequency) {
            return frequency > bestFrequency;
        }
        if (Math.abs(jaccard - bestJaccard) > EPSILON) {
            return jaccard > bestJaccard;
        }
        return index < best;
    }

    private static boolean isCorrectable(String word) {
        if (word.length() < MIN_WORD_LENGTH || word.length() > MAX_WORD_LENGTH) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static Set<String> bigrams(String word) {
        String padded = "\u0002" + word + "\u0003";
        Set<String> bigrams = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= padded.length(); i++) {
            bigrams.add(padded.substring(i, i + 2));
        }
        return bigrams;
    }
}
