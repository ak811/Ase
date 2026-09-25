package io.github.ak811.ase.core.text;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TokenizerTest {
    private final Tokenizer tokenizer = new Tokenizer();

    @Test
    public void unifiesArabicAndPersianLetterVariants() {
        assertEquals(Arrays.asList("کتابهای", "من"), tokenizer.terms("كتاب‌هاي من"));
    }

    @Test
    public void removesZeroWidthNonJoinerInsideWords() {
        assertEquals(tokenizer.terms("میروم"), tokenizer.terms("می‌روم"));
    }

    @Test
    public void removesDiacriticsAndTatweel() {
        assertEquals(Arrays.asList("کتاب"), tokenizer.terms("کِتاب"));
        assertEquals(Arrays.asList("کتاب"), tokenizer.terms("کـــتاب"));
    }

    @Test
    public void foldsPresentationForms() {
        assertEquals(Arrays.asList("کتاب"), tokenizer.terms("\uFEDB\uFE98\uFE8E\uFE8F"));
    }

    @Test
    public void normalizesPersianAndArabicDigits() {
        assertEquals(Arrays.asList("سال", "1402"), tokenizer.terms("سال ۱۴۰۲"));
        assertEquals(Arrays.asList("35"), tokenizer.terms("٣٥"));
    }

    @Test
    public void splitsOnPersianPunctuation() {
        assertEquals(Arrays.asList("سلام", "خوبی", "بله"), tokenizer.terms("سلام، خوبی؟ بله؛"));
    }

    @Test
    public void lowercasesLatinAndSeparatesScripts() {
        assertEquals(Arrays.asList("hello", "جهان", "tf", "idf"), tokenizer.terms("Hello جهان! TF-IDF"));
    }

    @Test
    public void offsetsPointAtTheRawText() {
        String text = "  كتاب‌ها، و Search";
        List<Token> tokens = tokenizer.tokenize(text);
        assertEquals(3, tokens.size());
        assertEquals("كتاب‌ها", text.substring(tokens.get(0).start(), tokens.get(0).end()));
        assertEquals("کتابها", tokens.get(0).term());
        assertEquals("Search", text.substring(tokens.get(2).start(), tokens.get(2).end()));
    }

    @Test
    public void trailingJoinerIsNotPartOfTheToken() {
        String text = "کتاب\u200C ";
        Token token = tokenizer.tokenize(text).get(0);
        assertEquals(4, token.end());
    }

    @Test
    public void dropsOverlongTokensAndHandlesEmptyInput() {
        StringBuilder longToken = new StringBuilder();
        for (int i = 0; i < Tokenizer.MAX_TERM_LENGTH + 1; i++) {
            longToken.append('a');
        }
        assertTrue(tokenizer.terms(longToken + " ok").contains("ok"));
        assertEquals(1, tokenizer.terms(longToken + " ok").size());
        assertTrue(tokenizer.terms("").isEmpty());
        assertTrue(tokenizer.terms(null).isEmpty());
        assertTrue(tokenizer.terms("  ،؟!  ").isEmpty());
    }
}
