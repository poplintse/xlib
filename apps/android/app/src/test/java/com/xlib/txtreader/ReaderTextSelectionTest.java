package com.xlib.txtreader;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReaderTextSelectionTest {
    @Test public void selectsWordAndPreservesOriginalCase() {
        String text = "Hello reader, welcome";
        int[] range = ReaderTextSelection.wordAt(text, 9);
        assertEquals("reader", text.substring(range[0], range[1]));
    }

    @Test public void clampsEmptyAndOutsidePageOffsets() {
        assertArrayEquals(new int[]{0, 0}, ReaderTextSelection.wordAt("", 10));
        assertArrayEquals(new int[]{0, 5}, ReaderTextSelection.wordAt("Hello", -20));
        assertArrayEquals(new int[]{0, 5}, ReaderTextSelection.wordAt("Hello", 200));
    }

    @Test public void handlesChinesePunctuationAndMultipleLines() {
        String text = "阅读文字，\n第二行文字。";
        for (int i = 0; i < text.length(); i++) {
            int[] range = ReaderTextSelection.wordAt(text, i);
            assertTrue(range[0] <= i);
            assertTrue(range[1] > i);
            assertTrue(range[1] <= text.length());
        }
    }

    @Test public void dragDoesNotSplitSurrogatePairsOrCombiningCharacters() {
        assertEquals(1, ReaderTextSelection.boundary("A😀B", 2));
        assertEquals(0, ReaderTextSelection.boundary("e\u0301x", 1));
        assertEquals(0, ReaderTextSelection.boundary("abc", -1));
        assertEquals(3, ReaderTextSelection.boundary("abc", 99));
    }

    @Test public void wordSelectionContainsWholeEmoji() {
        String text = "A 😀 B";
        int[] range = ReaderTextSelection.wordAt(text, 3);
        assertEquals("😀", text.substring(range[0], range[1]));
    }
}
