package io.github.ak811.ase.core.spell;

import io.github.ak811.ase.core.index.IndexBuilder;
import io.github.ak811.ase.core.index.SearchIndex;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SpellCorrectorTest {
    private SpellCorrector corrector;

    @Before
    public void setUp() {
        IndexBuilder builder = new IndexBuilder();
        builder.addDocument("", "", "کتاب کتابخانه تهران اصفهان گربه پنجره search engine");
        builder.addDocument("", "", "کتاب دانشگاه تهران");
        SearchIndex index = builder.build();
        corrector = new SpellCorrector(index);
    }

    @Test
    public void fixesConfusableLetters() {
        assertEquals("کتاب", corrector.suggest("کتاپ"));
        assertEquals("گربه", corrector.suggest("کربه"));
    }

    @Test
    public void fixesInsertionsDeletionsAndTranspositions() {
        assertEquals("تهران", corrector.suggest("تهرران"));
        assertEquals("اصفهان", corrector.suggest("اصفان"));
        assertEquals("دانشگاه", corrector.suggest("دانشگاه".substring(0, 3) + "گش" + "اه"));
        assertEquals("engine", corrector.suggest("engnie"));
    }

    @Test
    public void leavesKnownTermsAndNumbersAlone() {
        assertNull(corrector.suggest("کتاب"));
        assertNull(corrector.suggest("1402"));
        assertNull(corrector.suggest("ک"));
    }

    @Test
    public void refusesDistantWords() {
        assertNull(corrector.suggest("هواپیما"));
    }

    @Test
    public void confusableSubstitutionIsCheaperThanOtherEdits() {
        assertEquals(0.5, EditDistance.weighted("کتاپ", "کتاب"), 1e-9);
        assertEquals(1.0, EditDistance.weighted("کتام", "کتاب"), 1e-9);
        assertEquals(1.0, EditDistance.weighted("ab", "ba"), 1e-9);
    }
}
