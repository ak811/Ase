package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.index.Document;
import io.github.ak811.ase.core.index.IndexBuilder;
import io.github.ak811.ase.core.index.SearchIndex;
import io.github.ak811.ase.core.text.PersianNormalizer;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SearcherTest {
    private SearchIndex index;
    private Searcher searcher;

    @Before
    public void setUp() {
        IndexBuilder builder = new IndexBuilder();
        builder.addDocument("https://example.com/0", "کتابخانه ملی",
                "کتابخانه ملی ایران در تهران قرار دارد و کتاب‌های زیادی دارد.");
        builder.addDocument("https://example.com/1", "تاریخ تهران",
                "تهران پایتخت ایران است. کتاب تاریخ تهران خواندنی است.");
        builder.addDocument("https://example.com/2", "آشپزی",
                "خورش قورمه‌سبزی یکی از غذاهای محبوب ایرانی است.");
        builder.addDocument("https://example.com/3", "Search engines",
                "A search engine ranks documents with TF-IDF weights.");
        index = builder.build();
        searcher = new Searcher(index);
    }

    @Test
    public void findsAllDocumentsContainingATerm() {
        SearchResult result = searcher.search("تهران");
        assertEquals(2, result.totalHits());
        assertEquals(MatchMode.ALL_TERMS, result.matchMode());
    }

    @Test
    public void titleMatchesRankHigher() {
        SearchResult result = searcher.search("تهران");
        assertEquals(1, result.hits().get(0).document().id());
    }

    @Test
    public void multiTermQueriesRequireAllTerms() {
        SearchResult result = searcher.search("کتاب تاریخ");
        assertEquals(MatchMode.ALL_TERMS, result.matchMode());
        assertEquals(1, result.totalHits());
        assertEquals(1, result.hits().get(0).document().id());
    }

    @Test
    public void scoresSumOverAllQueryTerms() {
        SearchResult single = searcher.search("تاریخ");
        SearchResult both = searcher.search("کتاب تاریخ");
        assertTrue(both.hits().get(0).score() > single.hits().get(0).score());
    }

    @Test
    public void fallsBackToPartialMatchesWhenNoDocumentHasAllTerms() {
        SearchResult result = searcher.search("تهران قورمه‌سبزی");
        assertEquals(MatchMode.ANY_TERMS, result.matchMode());
        assertEquals(3, result.totalHits());
    }

    @Test
    public void arabicKeyboardVariantsMatchWithoutCorrection() {
        SearchResult result = searcher.search("كتابخانه ملي");
        assertNull(result.correctedQuery());
        assertEquals(0, result.hits().get(0).document().id());
    }

    @Test
    public void zwnjIsIgnoredWhenMatching() {
        assertEquals(1, searcher.search("کتابهای").totalHits());
        assertEquals(1, searcher.search("کتاب‌های").totalHits());
    }

    @Test
    public void correctsConfusableLetters() {
        SearchResult result = searcher.search("کتاپخانه");
        assertEquals("کتابخانه", result.correctedQuery());
        assertEquals(0, result.hits().get(0).document().id());
    }

    @Test
    public void correctionKeepsTheRestOfTheQueryIntact() {
        SearchResult result = searcher.search("  تاریخ  تهرران ");
        assertEquals("تاریخ  تهران", result.correctedQuery());
        assertEquals(1, result.totalHits());
    }

    @Test
    public void autoCorrectCanBeDisabled() {
        SearchResult result = searcher.search("کتاپخانه", SearchOptions.defaults().withAutoCorrect(false));
        assertNull(result.correctedQuery());
        assertEquals(0, result.totalHits());
        assertTrue(result.hits().isEmpty());
    }

    @Test
    public void unknownWordsWithoutSuggestionsReturnNoHits() {
        SearchResult result = searcher.search("زرافه");
        assertEquals(0, result.totalHits());
    }

    @Test
    public void repeatedSearchesDoNotModifyDocuments() {
        Document before = index.document(1);
        SearchResult first = searcher.search("تهران");
        SearchResult second = searcher.search("تهران");
        assertEquals(before, index.document(1));
        assertEquals(first.hits().get(0).excerpt(), second.hits().get(0).excerpt());
        assertEquals(first.hits().get(0).title(), second.hits().get(0).title());
    }

    @Test
    public void highlightsCoverExactlyTheMatchedWords() {
        SearchResult result = searcher.search("تهران ایران");
        assertFalse(result.hits().isEmpty());
        for (SearchHit hit : result.hits()) {
            assertHighlightsAreTerms(hit.excerpt(), result);
            assertHighlightsAreTerms(hit.title(), result);
        }
    }

    @Test
    public void latinSearchIsCaseInsensitive() {
        SearchResult result = searcher.search("SEARCH");
        assertEquals(1, result.totalHits());
        assertEquals(3, result.hits().get(0).document().id());
    }

    @Test
    public void queriesWithoutWordsReturnNothing() {
        assertEquals(0, searcher.search("  ،؟ ").totalHits());
        assertEquals(0, searcher.search(null).totalHits());
    }

    @Test
    public void paginationReturnsTheRequestedSlice() {
        SearchResult all = searcher.search("است");
        SearchResult second = searcher.search("است", SearchOptions.defaults().withOffset(1).withLimit(1));
        assertEquals(all.totalHits(), second.totalHits());
        assertEquals(1, second.hits().size());
        assertEquals(all.hits().get(1).document().id(), second.hits().get(0).document().id());

        SearchResult beyond = searcher.search("است", SearchOptions.defaults().withOffset(50));
        assertTrue(beyond.hits().isEmpty());
    }

    private static void assertHighlightsAreTerms(Snippet snippet, SearchResult result) {
        for (Highlight highlight : snippet.highlights()) {
            String word = snippet.text().substring(highlight.start(), highlight.end());
            assertTrue(word, result.terms().contains(PersianNormalizer.normalize(word)));
        }
    }
}
