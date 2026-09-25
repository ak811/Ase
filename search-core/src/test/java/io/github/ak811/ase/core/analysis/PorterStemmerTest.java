package io.github.ak811.ase.core.analysis;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PorterStemmerTest {
    private static void check(String word, String stem) {
        assertEquals(word, stem, PorterStemmer.stem(word));
    }

    @Test
    public void matchesReferenceOutputs() {
        check("caresses", "caress");
        check("ponies", "poni");
        check("cats", "cat");
        check("feed", "feed");
        check("agreed", "agre");
        check("plastered", "plaster");
        check("motoring", "motor");
        check("sing", "sing");
        check("conflated", "conflat");
        check("troubled", "troubl");
        check("sized", "size");
        check("hopping", "hop");
        check("filing", "file");
        check("happy", "happi");
        check("relational", "relat");
        check("conditional", "condit");
        check("generalization", "gener");
        check("running", "run");
        check("searching", "search");
        check("engines", "engin");
        check("is", "is");
    }
}
