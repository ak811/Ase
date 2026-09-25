package io.github.ak811.ase.core.analysis;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * High-frequency function words. They are still indexed (so phrases like
 * "to be or not to be" work); the searcher simply doesn't require them to match.
 * Entries are in normalized form (lower-case, accents folded, Persian letters unified).
 */
final class StopWords {
    private static final Set<String> LATIN = setOf(
            // English
            "a", "about", "above", "after", "again", "against", "all", "also", "am", "an", "and", "any", "are",
            "as", "at", "be", "because", "been", "before", "being", "below", "between", "both", "but", "by",
            "can", "could", "did", "do", "does", "doing", "dont", "down", "during", "each", "few", "for", "from",
            "further", "had", "has", "have", "having", "he", "her", "here", "hers", "him", "his", "how", "i",
            "if", "in", "into", "is", "it", "its", "just", "me", "more", "most", "my", "no", "nor", "not", "of",
            "off", "on", "once", "only", "or", "other", "our", "ours", "out", "over", "own", "same", "she",
            "should", "so", "some", "such", "than", "that", "the", "their", "theirs", "them", "then", "there",
            "these", "they", "this", "those", "through", "to", "too", "under", "until", "up", "very", "was",
            "we", "were", "what", "when", "where", "which", "while", "who", "whom", "why", "will", "with",
            "would", "you", "your", "yours",
            // French, Spanish, German, Italian, Portuguese (only words that are not English content words)
            "le", "la", "les", "des", "du", "et", "un", "une", "est", "dans", "pour", "que", "qui", "sur",
            "pas", "au", "aux", "ce", "cette", "il", "elle", "nous", "vous", "ils", "avec", "par",
            "el", "los", "las", "del", "y", "es", "una", "por", "con", "para", "se", "lo", "al", "su", "sus",
            "der", "das", "den", "dem", "des", "und", "ist", "ein", "eine", "einer", "nicht", "mit", "von",
            "zu", "auf", "im", "sich", "di", "che", "gli", "della", "os", "da", "dos", "em", "um", "uma", "nao");

    private static final Set<String> ARABIC_SCRIPT = setOf(
            // Persian
            "و", "در", "به", "از", "که", "این", "را", "با", "است", "برای", "آن", "یک", "خود", "تا", "بر",
            "هم", "نیز", "شد", "ما", "اما", "یا", "شده", "باید", "هر", "آنها", "بود", "او", "دیگر", "وی",
            "کنند", "کند", "دارد", "شود", "همه", "نه", "ولی", "پس", "اگر", "بین", "چه", "روی", "همین",
            "چون", "شوند", "بودن", "کرد", "کرده", "کردن", "میشود", "میکند", "ای", "اینکه", "آنکه", "هیچ",
            "شما", "ایشان", "من", "تو", "آنان", "اینها",
            // Arabic
            "فی", "علی", "الی", "عن", "ان", "هذا", "هذه", "التی", "الذی", "لا", "کان", "مع", "هو", "هی");

    private static final Set<String> CYRILLIC = setOf(
            "и", "в", "во", "не", "на", "я", "что", "он", "с", "со", "как", "а", "то", "все", "она", "так",
            "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "только", "ее", "мне", "было",
            "вот", "от", "меня", "еще", "нет", "о", "из", "ему", "это", "для", "при", "или", "был", "были");

    private StopWords() {
    }

    /** Like {@code Set.of}, but tolerates words shared by several languages. */
    private static Set<String> setOf(String... words) {
        return Set.copyOf(new HashSet<>(Arrays.asList(words)));
    }

    static boolean contains(ScriptGroup group, String surface) {
        switch (group) {
            case LATIN:
                return LATIN.contains(surface);
            case ARABIC:
                return ARABIC_SCRIPT.contains(surface);
            case CYRILLIC:
                return CYRILLIC.contains(surface);
            default:
                return false;
        }
    }
}
