package io.github.ak811.ase.core.spell;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persian letters that are commonly confused, either because they sound the
 * same (س ص ث, ز ذ ض ظ, ت ط, ح ه, ق غ) or because they sit next to each other /
 * differ by one dot on common keyboards (ک گ, ب پ, ج چ, ز ژ, ر ز, د ذ, ح خ).
 * Arabic/Persian variants of the same letter (ي/ی, ك/ک) are not listed here
 * because {@link io.github.ak811.ase.core.text.PersianNormalizer} already unifies them.
 */
public final class ConfusableLetters {
    private static final String[] GROUPS = {
            "کگ", "بپ", "جچ", "زژ", "رز", "زذضظ", "دذ", "حخ", "حه", "سصث", "تط", "قغ", "اآع", "یئ",
    };
    private static final char[] NONE = new char[0];
    private static final Map<Character, char[]> ALTERNATIVES;

    static {
        Map<Character, Set<Character>> groups = new HashMap<>();
        for (String group : GROUPS) {
            for (int i = 0; i < group.length(); i++) {
                char c = group.charAt(i);
                Set<Character> alternatives = groups.get(c);
                if (alternatives == null) {
                    alternatives = new LinkedHashSet<>();
                    groups.put(c, alternatives);
                }
                for (int j = 0; j < group.length(); j++) {
                    if (j != i) {
                        alternatives.add(group.charAt(j));
                    }
                }
            }
        }
        Map<Character, char[]> alternatives = new HashMap<>();
        for (Map.Entry<Character, Set<Character>> entry : groups.entrySet()) {
            char[] chars = new char[entry.getValue().size()];
            int i = 0;
            for (Character c : entry.getValue()) {
                chars[i++] = c;
            }
            alternatives.put(entry.getKey(), chars);
        }
        ALTERNATIVES = alternatives;
    }

    private ConfusableLetters() {
    }

    /** Letters commonly confused with {@code c}; empty if none. */
    public static char[] alternativesFor(char c) {
        char[] alternatives = ALTERNATIVES.get(c);
        return alternatives == null ? NONE : alternatives.clone();
    }

    public static boolean areConfusable(char a, char b) {
        char[] alternatives = ALTERNATIVES.get(a);
        if (alternatives == null) {
            return false;
        }
        for (char alternative : alternatives) {
            if (alternative == b) {
                return true;
            }
        }
        return false;
    }
}
