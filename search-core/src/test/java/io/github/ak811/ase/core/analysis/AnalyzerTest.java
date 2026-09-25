package io.github.ak811.ase.core.analysis;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnalyzerTest {
    private final Analyzer stemming = new Analyzer(AnalyzerConfig.defaults());
    private final Analyzer plain = new Analyzer(new AnalyzerConfig(false));

    private static List<String> terms(Analyzer analyzer, String text) {
        List<String> terms = new ArrayList<>();
        for (Token token : analyzer.analyze(text)) {
            terms.add(token.term());
        }
        return terms;
    }

    @Test
    public void stemsEnglishAndFoldsCase() {
        assertEquals(List.of("the", "run", "engin", "comput"), terms(stemming, "The Running ENGINES computers"));
        assertEquals(List.of("the", "running", "engines"), terms(plain, "The Running ENGINES"));
    }

    @Test
    public void foldsLatinAccentsAndSpecialLetters() {
        assertEquals(List.of("cafe", "naive", "strasse", "istanbul", "oeuvre"),
                terms(plain, "Café naïve Straße İstanbul Œuvre"));
    }

    @Test
    public void handlesApostrophesAndPossessives() {
        assertEquals(List.of("dont", "engine", "oclock"), terms(plain, "don't engine's o'clock"));
        assertEquals(List.of("rock", "n", "roll"), terms(plain, "rock 'n' roll"));
    }

    @Test
    public void keepsDigitsWithLatinLettersButSplitsOtherwise() {
        assertEquals(List.of("mp3", "3d", "2024", "1402", "سال"), terms(plain, "MP3 3D 2024 ۱۴۰۲سال"));
    }

    @Test
    public void flagsStopWordsWithoutRemovingThem() {
        List<Token> tokens = stemming.analyze("the cat and the hat");
        assertEquals(5, tokens.size());
        assertTrue(tokens.get(0).stopword());
        assertFalse(tokens.get(1).stopword());
    }

    @Test
    public void unifiesPersianAndArabicLetterVariants() {
        assertEquals(terms(plain, "کتابخانه ملی"), terms(plain, "كتابخانه ملي"));
        assertEquals(List.of("کتاب"), terms(plain, "کِتـــاب"));
    }

    @Test
    public void joinsDetachedPersianAffixes() {
        assertEquals(List.of("میخواهم"), terms(plain, "می خواهم"));
        assertEquals(terms(plain, "می خواهم"), terms(plain, "می‌خواهم"));
        assertEquals(terms(plain, "می خواهم"), terms(plain, "میخواهم"));
        Token merged = stemming.analyze("کتاب ها").get(0);
        assertEquals(0, merged.start());
        assertEquals(7, merged.end());
    }

    @Test
    public void stemsPersianConservatively() {
        assertEquals(List.of("کتاب"), terms(stemming, "کتاب‌ها"));
        assertEquals(List.of("کتاب"), terms(stemming, "کتاب ها"));
        assertEquals(List.of("کتاب"), terms(stemming, "کتابهای"));
        assertEquals(List.of("بزرگ"), terms(stemming, "بزرگ‌ترین"));
        assertEquals(List.of("گل"), terms(stemming, "گل‌ها"));
        assertEquals(List.of("تنها", "دختر", "انگشتر"), terms(stemming, "تنها دختر انگشتر"));
    }

    @Test
    public void foldsCyrillicYoAndStress() {
        assertEquals(List.of("елка", "москва"), terms(plain, "Ёлка Москва́"));
        assertEquals(List.of("замок"), terms(plain, "за́мок"));
    }

    @Test
    public void splitsCjkIntoBigrams() {
        assertEquals(List.of("東京", "京大", "大学"), terms(plain, "東京大学"));
        assertEquals(List.of("学"), terms(plain, "学"));
        List<Token> tokens = plain.analyze("東京大学");
        assertEquals(List.of(0, 1, 2), List.of(tokens.get(0).position(), tokens.get(1).position(), tokens.get(2).position()));
    }

    @Test
    public void splitsThaiIntoBigramsKeepingVowelMarks() {
        List<String> thai = terms(plain, "ภาษาไทย");
        assertEquals(6, thai.size());
        assertEquals("ภา", thai.get(0));
    }

    @Test
    public void separatesScriptsAndKeepsOffsets() {
        String text = "iPhone های جدید";
        List<Token> tokens = plain.analyze(text);
        assertEquals("iphone", tokens.get(0).term());
        assertEquals("iPhone", text.substring(tokens.get(0).start(), tokens.get(0).end()));
        assertTrue("stranded suffix is a stop word", tokens.get(1).stopword());
    }

    @Test
    public void handlesOtherScripts() {
        assertEquals(List.of("שלום"), terms(plain, "שָׁלוֹם"));
        assertEquals(List.of("αθηνα"), terms(plain, "Ἀθῆνα"));
        assertEquals(List.of("हिन्दी"), terms(plain, "हिन्दी"));
    }

    @Test
    public void positionsIncreaseFromTheGivenStart() {
        List<Token> tokens = plain.analyze("one two three", 10);
        assertEquals(10, tokens.get(0).position());
        assertEquals(12, tokens.get(2).position());
    }

    @Test
    public void dropsOverlongTokensAndEmptyInput() {
        String longWord = "a".repeat(Analyzer.MAX_TERM_LENGTH + 1);
        assertEquals(List.of("ok"), terms(plain, longWord + " ok"));
        assertTrue(plain.analyze("").isEmpty());
        assertTrue(plain.analyze(null).isEmpty());
        assertTrue(plain.analyze("  ،؟!…  ").isEmpty());
    }
}
