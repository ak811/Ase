package io.github.ak811.ase.core.spell;

import io.github.ak811.ase.core.index.SearchIndex;
import io.github.ak811.ase.core.util.IntList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suggests an indexed term for a query term that is not in the index.
 *
 * <ol>
 *   <li><b>Confusable letters.</b> Try swapping each letter for a commonly
 *       confused one (e.g. {@code کتاپ → کتاب}); pick the variant found in the
 *       most documents.</li>
 *   <li><b>Character bigrams.</b> Candidates sharing boundary-padded bigrams are
 *       scored by Jaccard similarity, filtered by a weighted edit distance, and
 *       ranked by (distance, document frequency, similarity).</li>
 * </ol>
 *
 * Suggestions are always terms that exist in the index, so a corrected query
 * can actually return results. Instances are thread-safe.
 */
public final class SpellCorrector {
    private static final int MIN_TERM_LENGTH = 2;
    private static final int MAX_TERM_LENGTH = 32;
    private static final int MAX_LENGTH_DIFFERENCE = 2;
    private static final double MIN_JACCARD = 0.3;
    private static final double EPSILON = 1e-9;
    private static final char WORD_START = '^';
    private static final char WORD_END = '$';

    private final SearchIndex index;
    private final String[] vocabulary;
    private final int[] documentFrequency;
    private final int[] bigramCounts;
    private final Map<String, int[]> bigramIndex;

    // Scratch space reused across calls; guarded by "this".
    private final int[] sharedBigrams;
    private final IntList touched = new IntList();

    public SpellCorrector(SearchIndex index) {
        this.index = index;
        List<String> words = new ArrayList<>();
        for (String term : index.vocabulary()) {
            if (isCorrectable(term)) {
                words.add(term);
            }
        }
        Collections.sort(words);
        vocabulary = words.toArray(new String[0]);
        documentFrequency = new int[vocabulary.length];
        bigramCounts = new int[vocabulary.length];

        Map<String, IntList> lists = new HashMap<>();
        for (int id = 0; id < vocabulary.length; id++) {
            documentFrequency[id] = index.documentFrequency(vocabulary[id]);
            Set<String> bigrams = bigrams(vocabulary[id]);
            bigramCounts[id] = bigrams.size();
            for (String bigram : bigrams) {
                IntList list = lists.get(bigram);
                if (list == null) {
                    list = new IntList(4);
                    lists.put(bigram, list);
                }
                list.add(id);
            }
        }
        bigramIndex = new HashMap<>(lists.size() * 4 / 3 + 1);
        for (Map.Entry<String, IntList> entry : lists.entrySet()) {
            bigramIndex.put(entry.getKey(), entry.getValue().toArray());
        }
        sharedBigrams = new int[vocabulary.length];
    }

    /**
     * Returns the best indexed replacement for {@code term}, or {@code null} if the
     * term is already indexed, is not a word (e.g. a number), or nothing is close enough.
     * {@code term} must already be normalized (see {@link io.github.ak811.ase.core.text.Tokenizer}).
     */
    public synchronized String suggest(String term) {
        if (term == null || !isCorrectable(term) || index.contains(term)) {
            return null;
        }
        String variant = bestConfusableVariant(term);
        return variant != null ? variant : bestBigramCandidate(term);
    }

    private String bestConfusableVariant(String term) {
        char[] chars = term.toCharArray();
        String best = null;
        int bestFrequency = 0;
        for (int i = 0; i < chars.length; i++) {
            char original = chars[i];
            for (char alternative : ConfusableLetters.alternativesFor(original)) {
                chars[i] = alternative;
                String candidate = new String(chars);
                int frequency = index.documentFrequency(candidate);
                if (frequency > bestFrequency
                        || (frequency > 0 && frequency == bestFrequency && candidate.compareTo(best) < 0)) {
                    best = candidate;
                    bestFrequency = frequency;
                }
            }
            chars[i] = original;
        }
        return best;
    }

    private String bestBigramCandidate(String term) {
        Set<String> queryBigrams = bigrams(term);
        touched.clear();
        for (String bigram : queryBigrams) {
            int[] ids = bigramIndex.get(bigram);
            if (ids == null) {
                continue;
            }
            for (int id : ids) {
                if (Math.abs(vocabulary[id].length() - term.length()) > MAX_LENGTH_DIFFERENCE) {
                    continue;
                }
                if (sharedBigrams[id]++ == 0) {
                    touched.add(id);
                }
            }
        }

        double maxDistance = term.length() <= 4 ? 1.0 : 2.0;
        int bestId = -1;
        double bestDistance = Double.MAX_VALUE;
        double bestJaccard = 0;
        for (int i = 0; i < touched.size(); i++) {
            int id = touched.get(i);
            int shared = sharedBigrams[id];
            sharedBigrams[id] = 0;
            double jaccard = shared / (double) (queryBigrams.size() + bigramCounts[id] - shared);
            if (jaccard < MIN_JACCARD) {
                continue;
            }
            double distance = EditDistance.weighted(term, vocabulary[id]);
            if (distance > maxDistance + EPSILON) {
                continue;
            }
            if (bestId < 0 || isBetter(distance, id, jaccard, bestDistance, bestId, bestJaccard)) {
                bestId = id;
                bestDistance = distance;
                bestJaccard = jaccard;
            }
        }
        touched.clear();
        return bestId < 0 ? null : vocabulary[bestId];
    }

    private boolean isBetter(double distance, int id, double jaccard,
                             double bestDistance, int bestId, double bestJaccard) {
        if (Math.abs(distance - bestDistance) > EPSILON) {
            return distance < bestDistance;
        }
        if (documentFrequency[id] != documentFrequency[bestId]) {
            return documentFrequency[id] > documentFrequency[bestId];
        }
        if (Math.abs(jaccard - bestJaccard) > EPSILON) {
            return jaccard > bestJaccard;
        }
        return id < bestId; // vocabulary is sorted, so this is alphabetical
    }

    private static boolean isCorrectable(String term) {
        if (term.length() < MIN_TERM_LENGTH || term.length() > MAX_TERM_LENGTH) {
            return false;
        }
        for (int i = 0; i < term.length(); i++) {
            if (Character.isLetter(term.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static Set<String> bigrams(String term) {
        String padded = WORD_START + term + WORD_END;
        Set<String> bigrams = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= padded.length(); i++) {
            bigrams.add(padded.substring(i, i + 2));
        }
        return bigrams;
    }
}
