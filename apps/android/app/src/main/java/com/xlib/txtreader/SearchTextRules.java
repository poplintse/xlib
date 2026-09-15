package com.xlib.txtreader;

import com.ibm.icu.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;

final class SearchTextRules {
    private SearchTextRules() { }
    static String normalize(String input) {
        if (input == null) return "";
        int start = 0, end = input.length();
        while (start < end && space(input.codePointAt(start))) start += Character.charCount(input.codePointAt(start));
        while (end > start && space(input.codePointBefore(end))) end -= Character.charCount(input.codePointBefore(end));
        return input.substring(start, end);
    }
    private static boolean space(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }
    static int characterCount(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        int count = 0;
        iterator.first();
        while (iterator.next() != BreakIterator.DONE) count++;
        return count;
    }
    static Pattern pattern(String query) {
        return Pattern.compile(query, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
