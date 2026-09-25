package io.github.ak811.ase.core.search;

import io.github.ak811.ase.core.index.IndexReader;
import io.github.ak811.ase.core.index.IndexWriter;
import io.github.ak811.ase.core.index.IndexWriterConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SuggesterTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void completesTheLastWordByPopularity() throws IOException {
        Path path = temp.getRoot().toPath().resolve("index.idx");
        try (IndexWriter writer = new IndexWriter(path, IndexWriterConfig.defaults())) {
            writer.addDocument("", "", "search engines search");
            writer.addDocument("", "", "search season");
            writer.addDocument("", "", "Café culture");
            writer.addDocument("", "", "کتاب‌ها و کتابخانه");
            writer.addDocument("", "", "東京大学");
            writer.commit();
        }
        try (IndexReader reader = IndexReader.open(path)) {
            Suggester suggester = new Suggester(reader);
            assertEquals(List.of("search", "season"), suggester.suggest("sea", 5));
            assertEquals(List.of("best search"), suggester.suggest("best sear", 1));
            assertEquals(List.of("café"), suggester.suggest("caf", 5));
            List<String> persian = suggester.suggest("کتا", 5);
            assertTrue(persian.toString(), persian.contains("کتاب‌ها") && persian.contains("کتابخانه"));
            assertTrue(suggester.suggest("sea ", 5).isEmpty());
            assertTrue(suggester.suggest("s", 5).isEmpty());
            assertTrue(suggester.suggest("東京", 5).isEmpty());
            assertTrue(suggester.suggest("", 5).isEmpty());
            assertTrue(suggester.suggest("x".repeat(500), 5).isEmpty());
        }
    }
}
