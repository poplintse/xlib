package com.xlib.txtreader;

import java.text.BreakIterator;
import java.util.Locale;

/** Page-local UTF-16 indices; never reading-progress byte offsets. */
final class ReaderTextSelection {
    private ReaderTextSelection() { }

    static int clampMenuCoordinate(int value, int start, int end, int extent) {
        return Math.max(start, Math.min(value, Math.max(start, end - extent)));
    }

    static int menuTop(int selectionTop, int selectionBottom, int height,
                       int viewportTop, int viewportBottom, int gap) {
        int above = selectionTop - gap - height;
        int preferred = above >= viewportTop ? above : selectionBottom + gap;
        return clampMenuCoordinate(preferred, viewportTop, viewportBottom, height);
    }

    static int[] wordAt(String text, int offset) {
        if (text.isEmpty()) return new int[]{0, 0};
        int index = boundary(text, Math.min(offset, text.length() - 1));
        BreakIterator words = BreakIterator.getWordInstance(Locale.getDefault());
        words.setText(text);
        int start = words.preceding(index + 1);
        int end = words.following(index);
        return new int[]{start == BreakIterator.DONE ? 0 : start,
                end == BreakIterator.DONE ? text.length() : end};
    }

    static int boundary(String text, int offset) {
        int index = Math.max(0, Math.min(text.length(), offset));
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.getDefault());
        characters.setText(text);
        return characters.isBoundary(index) ? index : characters.preceding(index);
    }
}
