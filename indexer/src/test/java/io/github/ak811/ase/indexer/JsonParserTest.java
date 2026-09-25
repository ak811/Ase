package io.github.ak811.ase.indexer;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class JsonParserTest {
    private static Map<String, Object> parse(String json) {
        return new JsonLinesReader.JsonParser(json).parseObjectLine();
    }

    @Test
    public void parsesStringsWithEscapes() {
        Map<String, Object> object = parse("{\"a\":\"x\\\"y\\\\z\\u0041\\ud83d\\ude00\", \"b\": null, \"c\": [1, [2]], \"d\": {\"e\": true}}");
        assertEquals("x\"y\\zA\uD83D\uDE00", object.get("a"));
        assertEquals(4, object.size());
    }

    @Test
    public void rejectsInvalidJson() {
        for (String bad : new String[]{"[1]", "{", "{\"a\" 1}", "{\"a\":1,}", "{\"a\":\"\u0001\"}", "{\"a\":1} x",
                "{\"a\":tru}", "{\"a\":\"\\q\"}", "{" + "\"a\":{".repeat(100) + "}"}) {
            try {
                parse(bad);
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }
}
