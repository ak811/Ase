package io.github.ak811.ase.core.search;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SnippetGeneratorTest {
    private final SnippetGenerator generator = new SnippetGenerator();

    private static String filler(int words) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < words; i++) {
            text.append("واژه ");
        }
        return text.toString();
    }

    @Test
    public void shortTextIsReturnedWhole() {
        Snippet snippet = generator.excerpt("کتاب خوب", Collections.singleton("کتاب"));
        assertEquals("کتاب خوب", snippet.text());
        assertEquals(new Highlight(0, 4), snippet.highlights().get(0));
    }

    @Test
    public void longTextIsCutAroundTheMatch() {
        String text = filler(200) + "حافظ " + filler(200);
        Snippet snippet = generator.excerpt(text, Collections.singleton("حافظ"));
        assertTrue(snippet.text().startsWith("…"));
        assertTrue(snippet.text().endsWith("…"));
        assertTrue(snippet.text().length() <= SnippetGenerator.DEFAULT_MAX_LENGTH + 2);
        assertEquals(1, snippet.highlights().size());
        Highlight h = snippet.highlights().get(0);
        assertEquals("حافظ", snippet.text().substring(h.start(), h.end()));
    }

    @Test
    public void prefersTheWindowWithMoreDistinctTerms() {
        String text = "شیراز " + filler(150) + "حافظ و شیراز " + filler(150);
        Set<String> terms = new HashSet<>();
        terms.add("شیراز");
        terms.add("حافظ");
        Snippet snippet = generator.excerpt(text, terms);
        assertTrue(snippet.text().contains("حافظ"));
        assertEquals(2, snippet.highlights().size());
    }

    @Test
    public void textWithoutMatchesShowsTheBeginning() {
        String text = filler(200);
        Snippet snippet = generator.excerpt(text, Collections.singleton("حافظ"));
        assertTrue(snippet.text().startsWith("واژه"));
        assertTrue(snippet.text().endsWith("…"));
        assertTrue(snippet.highlights().isEmpty());
    }
}
