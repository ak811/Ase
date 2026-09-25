package io.github.ak811.ase.core.spell;

import io.github.ak811.ase.core.index.IndexReader;
import io.github.ak811.ase.core.index.IndexWriter;
import io.github.ak811.ase.core.index.IndexWriterConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SpellCorrectorTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void suggestsCorpusWords() throws IOException {
        Path path = temp.getRoot().toPath().resolve("index.idx");
        try (IndexWriter writer = new IndexWriter(path, IndexWriterConfig.defaults())) {
            writer.addDocument("", "", "language languages library kitchen");
            writer.addDocument("", "", "language کتابخانه کتاب Москва");
            writer.commit();
        }
        try (IndexReader reader = IndexReader.open(path)) {
            SpellCorrector corrector = new SpellCorrector(reader.lexicon());
            assertEquals("language", corrector.suggest("langauge").word());
            assertEquals("library", corrector.suggest("libary").word());
            assertEquals("کتابخانه", corrector.suggest("کتاپخانه").word());
            assertEquals("москва", corrector.suggest("масква").word());
            assertNull("known words are not corrected", corrector.suggest("kitchen"));
            assertNull("nothing close", corrector.suggest("zyxwvut"));
            assertNull(corrector.suggest("a"));
        }
    }
}
