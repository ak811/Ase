package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.analysis.Analyzer;
import io.github.ak811.ase.core.analysis.AnalyzerConfig;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SnippetGeneratorTest {
    private final SnippetGenerator generator = new SnippetGenerator(new Analyzer(AnalyzerConfig.defaults()));

    @Test
    public void highlightsStemmedMatches() {
        Snippet snippet = generator.highlight("Running engines run", Set.of("run"));
        assertEquals(2, snippet.highlights().size());
        assertEquals("Running", snippet.text().substring(0, snippet.highlights().get(0).end()));
    }

    @Test
    public void excerptCentersOnMatchesWithEllipses() {
        String text = "start " + "lorem ".repeat(100) + "target word here " + "ipsum ".repeat(100);
        Snippet snippet = generator.excerpt(text, Set.of("target"));
        assertTrue(snippet.text().startsWith("…"));
        assertTrue(snippet.text().endsWith("…"));
        assertTrue(snippet.text().length() <= SnippetGenerator.DEFAULT_MAX_LENGTH + 2);
        Highlight h = snippet.highlights().get(0);
        assertEquals("target", snippet.text().substring(h.start(), h.end()));
    }

    @Test
    public void excerptWithoutMatchesShowsTheBeginning() {
        String text = "alpha " + "beta ".repeat(100);
        Snippet snippet = generator.excerpt(text, Set.of("missing"));
        assertTrue(snippet.text().startsWith("alpha"));
        assertTrue(snippet.highlights().isEmpty());
    }

    @Test
    public void overlappingCjkHighlightsStayDisjoint() {
        Snippet snippet = generator.highlight("東京大学", Set.of("東京", "京大", "大学"));
        int previousEnd = 0;
        for (Highlight highlight : snippet.highlights()) {
            assertTrue(highlight.start() >= previousEnd);
            previousEnd = highlight.end();
        }
        assertEquals(4, previousEnd);
    }

    @Test
    public void neverSplitsSurrogatePairs() {
        String text = "\uD83D\uDE00".repeat(300);
        Snippet snippet = generator.excerpt(text, Set.of());
        String body = snippet.text().replace("…", "");
        assertEquals(0, body.length() % 2);
    }
}
