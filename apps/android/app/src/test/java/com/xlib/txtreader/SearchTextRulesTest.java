package com.xlib.txtreader;

import org.junit.Test;
import static org.junit.Assert.*;

public class SearchTextRulesTest {
    @Test public void countsExtendedGraphemesNotUtf16OrCodePoints() {
        for (String one : new String[]{"😀", "e\u0301", "👨‍👩‍👧‍👦", "🇨🇳", "👍🏽", "字"}) {
            assertEquals(1, SearchTextRules.characterCount(one));
            assertEquals(2, SearchTextRules.characterCount(one.repeat(2)));
            assertEquals(32, SearchTextRules.characterCount(one.repeat(32)));
            assertEquals(33, SearchTextRules.characterCount(one.repeat(33)));
        }
    }
    @Test public void trimsOnlyOuterWhitespaceWithoutTruncation() {
        assertEquals("a b", SearchTextRules.normalize("\u00a0 a b \u3000"));
        assertEquals(33, SearchTextRules.characterCount(SearchTextRules.normalize("x".repeat(33))));
        assertEquals("", SearchTextRules.normalize(null));
    }
    @Test public void literalMatchingDoesNotIntroduceAccentFoldingOrRegex() {
        assertTrue(SearchTextRules.pattern("Hello").matcher("HELLO").find());
        assertFalse(SearchTextRules.pattern("e").matcher("é").find());
        assertFalse(SearchTextRules.pattern("a.b").matcher("acb").find());
    }
}
