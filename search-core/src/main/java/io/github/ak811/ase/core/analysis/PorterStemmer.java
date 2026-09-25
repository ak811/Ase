package io.github.ak811.ase.core.analysis;

/**
 * The Porter (1980) English stemmer, following Martin Porter's reference implementation.
 * Input must be lower-case ASCII letters; e.g. {@code running → run}, {@code engines → engin}.
 */
final class PorterStemmer {
    private final char[] b;
    private int k;
    private int j;

    private PorterStemmer(String word) {
        b = new char[word.length() + 2]; // step 1b may lengthen the word by one letter
        word.getChars(0, word.length(), b, 0);
        k = word.length() - 1;
    }

    static String stem(String word) {
        if (word.length() <= 2) {
            return word;
        }
        PorterStemmer s = new PorterStemmer(word);
        s.step1();
        s.step2();
        s.step3();
        s.step4();
        s.step5();
        s.step6();
        return new String(s.b, 0, s.k + 1);
    }

    private boolean cons(int i) {
        switch (b[i]) {
            case 'a':
            case 'e':
            case 'i':
            case 'o':
            case 'u':
                return false;
            case 'y':
                return i == 0 || !cons(i - 1);
            default:
                return true;
        }
    }

    /** Number of consonant–vowel sequences in b[0..j]. */
    private int m() {
        int n = 0;
        int i = 0;
        while (true) {
            if (i > j) {
                return n;
            }
            if (!cons(i)) {
                break;
            }
            i++;
        }
        i++;
        while (true) {
            while (true) {
                if (i > j) {
                    return n;
                }
                if (cons(i)) {
                    break;
                }
                i++;
            }
            i++;
            n++;
            while (true) {
                if (i > j) {
                    return n;
                }
                if (!cons(i)) {
                    break;
                }
                i++;
            }
            i++;
        }
    }

    private boolean vowelInStem() {
        for (int i = 0; i <= j; i++) {
            if (!cons(i)) {
                return true;
            }
        }
        return false;
    }

    private boolean doubleConsonant(int i) {
        return i >= 1 && b[i] == b[i - 1] && cons(i);
    }

    private boolean cvc(int i) {
        if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) {
            return false;
        }
        char c = b[i];
        return c != 'w' && c != 'x' && c != 'y';
    }

    private boolean ends(String s) {
        int length = s.length();
        int offset = k - length + 1;
        if (offset < 0) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (b[offset + i] != s.charAt(i)) {
                return false;
            }
        }
        j = k - length;
        return true;
    }

    private void setTo(String s) {
        int length = s.length();
        int offset = j + 1;
        for (int i = 0; i < length; i++) {
            b[offset + i] = s.charAt(i);
        }
        k = j + length;
    }

    private void replace(String s) {
        if (m() > 0) {
            setTo(s);
        }
    }

    /** Plurals and -ed / -ing. */
    private void step1() {
        if (b[k] == 's') {
            if (ends("sses")) {
                k -= 2;
            } else if (ends("ies")) {
                setTo("i");
            } else if (b[k - 1] != 's') {
                k--;
            }
        }
        if (ends("eed")) {
            if (m() > 0) {
                k--;
            }
        } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
            k = j;
            if (ends("at")) {
                setTo("ate");
            } else if (ends("bl")) {
                setTo("ble");
            } else if (ends("iz")) {
                setTo("ize");
            } else if (doubleConsonant(k)) {
                k--;
                char c = b[k];
                if (c == 'l' || c == 's' || c == 'z') {
                    k++;
                }
            } else if (m() == 1 && cvc(k)) {
                setTo("e");
            }
        }
    }

    /** Terminal y to i when there is another vowel in the stem. */
    private void step2() {
        if (ends("y") && vowelInStem()) {
            b[k] = 'i';
        }
    }

    /** Double suffixes to single ones. */
    private void step3() {
        if (k == 0) {
            return;
        }
        switch (b[k - 1]) {
            case 'a':
                if (ends("ational")) {
                    replace("ate");
                } else if (ends("tional")) {
                    replace("tion");
                }
                break;
            case 'c':
                if (ends("enci")) {
                    replace("ence");
                } else if (ends("anci")) {
                    replace("ance");
                }
                break;
            case 'e':
                if (ends("izer")) {
                    replace("ize");
                }
                break;
            case 'l':
                if (ends("bli")) {
                    replace("ble");
                } else if (ends("alli")) {
                    replace("al");
                } else if (ends("entli")) {
                    replace("ent");
                } else if (ends("eli")) {
                    replace("e");
                } else if (ends("ousli")) {
                    replace("ous");
                }
                break;
            case 'o':
                if (ends("ization")) {
                    replace("ize");
                } else if (ends("ation")) {
                    replace("ate");
                } else if (ends("ator")) {
                    replace("ate");
                }
                break;
            case 's':
                if (ends("alism")) {
                    replace("al");
                } else if (ends("iveness")) {
                    replace("ive");
                } else if (ends("fulness")) {
                    replace("ful");
                } else if (ends("ousness")) {
                    replace("ous");
                }
                break;
            case 't':
                if (ends("aliti")) {
                    replace("al");
                } else if (ends("iviti")) {
                    replace("ive");
                } else if (ends("biliti")) {
                    replace("ble");
                }
                break;
            case 'g':
                if (ends("logi")) {
                    replace("log");
                }
                break;
            default:
                break;
        }
    }

    /** -ic-, -full, -ness etc. */
    private void step4() {
        switch (b[k]) {
            case 'e':
                if (ends("icate")) {
                    replace("ic");
                } else if (ends("ative")) {
                    replace("");
                } else if (ends("alize")) {
                    replace("al");
                }
                break;
            case 'i':
                if (ends("iciti")) {
                    replace("ic");
                }
                break;
            case 'l':
                if (ends("ical")) {
                    replace("ic");
                } else if (ends("ful")) {
                    replace("");
                }
                break;
            case 's':
                if (ends("ness")) {
                    replace("");
                }
                break;
            default:
                break;
        }
    }

    /** -ant, -ence etc. in context <c>vcvc<v>. */
    private void step5() {
        if (k == 0) {
            return;
        }
        boolean found;
        switch (b[k - 1]) {
            case 'a':
                found = ends("al");
                break;
            case 'c':
                found = ends("ance") || ends("ence");
                break;
            case 'e':
                found = ends("er");
                break;
            case 'i':
                found = ends("ic");
                break;
            case 'l':
                found = ends("able") || ends("ible");
                break;
            case 'n':
                found = ends("ant") || ends("ement") || ends("ment") || ends("ent");
                break;
            case 'o':
                found = (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) || ends("ou");
                break;
            case 's':
                found = ends("ism");
                break;
            case 't':
                found = ends("ate") || ends("iti");
                break;
            case 'u':
                found = ends("ous");
                break;
            case 'v':
                found = ends("ive");
                break;
            case 'z':
                found = ends("ize");
                break;
            default:
                found = false;
                break;
        }
        if (found && m() > 1) {
            k = j;
        }
    }

    /** Final -e and -ll. */
    private void step6() {
        j = k;
        if (b[k] == 'e') {
            int measure = m();
            if (measure > 1 || (measure == 1 && !cvc(k - 1))) {
                k--;
            }
        }
        if (b[k] == 'l' && doubleConsonant(k) && m() > 1) {
            k--;
        }
    }
}
