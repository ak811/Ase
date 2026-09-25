package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.analysis.AnalyzerConfig;
import io.github.ak811.ase.core.index.IndexReader;
import io.github.ak811.ase.core.index.IndexWriter;
import io.github.ak811.ase.core.index.IndexWriterConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SearcherTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private IndexReader reader;
    private Searcher searcher;

    private static final String[][] DOCS = {
            {"en/search", "Search engines", "A search engine ranks documents using an inverted index and BM25."},
            {"en/pasta", "Cooking pasta", "Boil water, salt it, and cook the pasta. The stove is the engine of a kitchen."},
            {"en/db", "Database design", "A database stores records. Good database design helps every search engine."},
            {"en/far", "Notes", "Search is useful. " + "filler ".repeat(60) + "An engine appears much later."},
            {"en/near", "More notes", "A search engine appears here. " + "filler ".repeat(60)},
            {"fa/lib", "کتابخانه ملی", "کتاب‌های زیادی در کتابخانه ملی ایران نگهداری می‌شود. می خواهم کتاب بخوانم."},
            {"fa/city", "اصفهان", "اصفهان شهری تاریخی است و کتابخانه‌های قدیمی دارد."},
            {"fr/cafe", "Le café", "Les cafés parisiens servent du café."},
            {"ja/tokyo", "東京", "東京大学は日本の大学です。"},
            {"ru/moscow", "Москва", "Москва — столица России. Ёлка на площади."},
            {"en/hamlet", "Hamlet", "To be, or not to be, that is the question."},
    };

    @Before
    public void setUp() throws IOException {
        Path path = temp.getRoot().toPath().resolve("index.idx");
        try (IndexWriter writer = new IndexWriter(path, IndexWriterConfig.defaults())) {
            for (String[] doc : DOCS) {
                writer.addDocument(doc[0], doc[1], doc[2]);
            }
            writer.commit();
        }
        reader = IndexReader.open(path);
        searcher = new Searcher(reader);
    }

    @After
    public void tearDown() throws IOException {
        reader.close();
    }

    private List<String> urls(String query) throws IOException {
        return urls(searcher.search(query, SearchOptions.defaults().withLimit(100)));
    }

    private static List<String> urls(SearchResult result) {
        List<String> urls = new ArrayList<>();
        for (SearchHit hit : result.hits()) {
            urls.add(hit.url());
        }
        return urls;
    }

    @Test
    public void ranksTitleMatchesFirstAndStemsQueries() throws IOException {
        List<String> results = urls("searching engines");
        assertEquals("en/search", results.get(0));
        assertTrue(results.contains("en/db"));
        assertFalse(results.contains("en/pasta"));
    }

    @Test
    public void phrasesRequireAdjacencyAndOrder() throws IOException {
        assertEquals(List.of("en/search"), urls("\"inverted index\""));
        assertTrue(urls("\"index inverted\"").isEmpty());
        assertEquals(List.of("en/hamlet"), urls("\"to be or not to be\""));
    }

    @Test
    public void excludesNegatedWordsAndPhrases() throws IOException {
        assertFalse(urls("engine -database").contains("en/db"));
        assertFalse(urls("engine -\"inverted index\"").contains("en/search"));
        assertTrue(urls("engine -\"index inverted\"").contains("en/search"));
    }

    @Test
    public void expandsPrefixes() throws IOException {
        List<String> results = urls("datab*");
        assertEquals(List.of("en/db"), results);
    }

    @Test
    public void matchesCompoundsWrittenApart() throws IOException {
        assertEquals(List.of("en/db"), urls("data base"));
        assertEquals(List.of("fa/lib", "fa/city"), urls("کتاب خانه").subList(0, 2));
    }

    @Test
    public void ignoresStopWordsUnlessTheQueryIsOnlyStopWords() throws IOException {
        assertEquals(urls("engine"), urls("the engine of"));
        assertEquals(List.of("en/hamlet"), urls("to be or not"));
    }

    @Test
    public void fallsBackToPartialMatches() throws IOException {
        SearchResult result = searcher.search("pasta kangaroo");
        assertEquals(MatchMode.ANY_TERMS, result.matchMode());
        assertEquals("en/pasta", result.hits().get(0).url());
        assertEquals(MatchMode.ALL_TERMS, searcher.search("pasta stove").matchMode());
    }

    @Test
    public void correctsSpellingInEnglishAndPersian() throws IOException {
        SearchResult english = searcher.search("serch engnes");
        assertEquals("search engines", english.correctedQuery());
        assertEquals("en/search", english.hits().get(0).url());

        SearchResult persian = searcher.search("کتاپخانه");
        assertEquals("کتابخانه", persian.correctedQuery());
        assertTrue(urls(persian).contains("fa/lib"));
    }

    @Test
    public void autoCorrectCanBeDisabled() throws IOException {
        SearchResult result = searcher.search("serch", SearchOptions.defaults().withAutoCorrect(false));
        assertNull(result.correctedQuery());
        assertEquals(0, result.totalHits());
    }

    @Test
    public void persianAffixesAndLetterVariantsMatch() throws IOException {
        assertTrue(urls("كتابها").contains("fa/lib"));
        assertTrue(urls("میخواهم").contains("fa/lib"));
        assertTrue(urls("می خواهم").contains("fa/lib"));
    }

    @Test
    public void searchesOtherLanguages() throws IOException {
        assertEquals(List.of("fr/cafe"), urls("CAFE"));
        assertEquals(List.of("ja/tokyo"), urls("東京大学"));
        assertEquals(List.of("ja/tokyo"), urls("大学"));
        assertEquals(List.of("ru/moscow"), urls("елка"));
    }

    @Test
    public void proximityRanksCloseTermsHigher() throws IOException {
        List<String> results = urls("search engine");
        assertTrue(results.indexOf("en/near") < results.indexOf("en/far"));
    }

    @Test
    public void pagesThroughResults() throws IOException {
        SearchResult all = searcher.search("engine", SearchOptions.defaults().withLimit(100));
        assertTrue(all.totalHits() >= 4);
        SearchResult second = searcher.search("engine", new SearchOptions(1, 2, true));
        assertEquals(all.totalHits(), second.totalHits());
        assertEquals(urls(all).subList(1, 3), urls(second));
        assertTrue(searcher.search("engine", new SearchOptions(500, 10, true)).hits().isEmpty());
    }

    @Test
    public void highlightsMatchesInTitleAndExcerpt() throws IOException {
        SearchHit hit = searcher.search("inverted").hits().get(0);
        Snippet excerpt = hit.excerpt();
        Highlight highlight = excerpt.highlights().get(0);
        assertEquals("inverted", excerpt.text().substring(highlight.start(), highlight.end()));

        SearchHit persian = searcher.search("کتابخانه").hits().get(0);
        Highlight title = persian.title().highlights().get(0);
        assertEquals("کتابخانه", persian.title().text().substring(title.start(), title.end()));
    }

    @Test
    public void handlesEmptyAndHostileQueries() throws IOException {
        assertEquals(0, searcher.search("").totalHits());
        assertEquals(0, searcher.search("   \"\" - * ").totalHits());
        assertEquals(0, searcher.search("-engine").totalHits());
        assertEquals(0, searcher.search("\"unterminated phrase").totalHits());
        String huge = "engine ".repeat(10_000);
        assertTrue(searcher.search(huge).totalHits() > 0);
    }

    @Test
    public void respectsIndexWithoutStemming() throws IOException {
        Path path = temp.getRoot().toPath().resolve("plain.idx");
        try (IndexWriter writer = new IndexWriter(path,
                IndexWriterConfig.defaults().withAnalyzer(new AnalyzerConfig(false)))) {
            writer.addDocument("a", "", "running engines");
            writer.commit();
        }
        try (IndexReader plain = IndexReader.open(path)) {
            Searcher plainSearcher = new Searcher(plain);
            assertEquals(1, plainSearcher.search("running").totalHits());
            assertEquals(0, plainSearcher.search("run", SearchOptions.defaults().withAutoCorrect(false)).totalHits());
        }
    }
}
