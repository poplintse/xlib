package com.xlib.txtreader;

import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds multiple reader pages from one continuous cache snapshot without touching a View. */
final class ReaderPagePaginator {
    private CombinedCacheSnapshot laidOutCache;
    private long layoutKey = Long.MIN_VALUE;
    private StaticLayout layout;

    List<ReaderPage> paginateAround(CombinedCacheSnapshot cache, long anchorOffset,
                                    LayoutSpec spec, int pagesEachSide) {
        if (cache == null || cache.bytesRead <= 0 || cache.text.isEmpty()
                || spec == null || spec.width <= 0 || spec.height <= 0) {
            return Collections.emptyList();
        }
        StaticLayout readyLayout = layoutFor(cache, spec);
        if (readyLayout.getLineCount() <= 0) return Collections.emptyList();

        long relative = Math.max(0L,
                Math.min(anchorOffset - cache.offset, cache.bytesRead));
        int anchorChar = cache.offsetMap.charIndexForByteOffset(relative);
        int anchorLine = Math.max(0, Math.min(readyLayout.getLineCount() - 1,
                readyLayout.getLineForOffset(Math.min(anchorChar, cache.text.length()))));
        int sideCount = Math.max(1, pagesEachSide);

        ArrayList<ReaderPage> backward = new ArrayList<>();
        int endLine = anchorLine;
        for (int i = 0; i < sideCount && endLine > 0; i++) {
            int startLine = backwardStartLine(readyLayout, endLine, spec.height);
            ReaderPage page = pageForLines(cache, readyLayout, startLine, endLine);
            if (page.endOffset > page.startOffset) backward.add(page);
            endLine = startLine;
        }
        Collections.reverse(backward);

        ArrayList<ReaderPage> result = new ArrayList<>(sideCount * 2 + 1);
        result.addAll(backward);
        int startLine = anchorLine;
        for (int i = 0; i <= sideCount && startLine < readyLayout.getLineCount(); i++) {
            int nextLine = forwardEndLine(readyLayout, startLine, spec.height);
            ReaderPage page = pageForLines(cache, readyLayout, startLine, nextLine);
            if (page.endOffset > page.startOffset) result.add(page);
            startLine = nextLine;
        }
        return result;
    }

    List<ReaderPage> paginateForward(CombinedCacheSnapshot cache, long startOffset,
                                     LayoutSpec spec, int pageCount) {
        if (!canPaginate(cache, spec) || startOffset >= cache.endOffset()) {
            return Collections.emptyList();
        }
        StaticLayout readyLayout = layoutFor(cache, spec);
        int startChar = cache.offsetMap.charIndexForByteOffset(startOffset - cache.offset);
        int startLine = Math.max(0, Math.min(readyLayout.getLineCount() - 1,
                readyLayout.getLineForOffset(startChar)));
        while (startLine + 1 < readyLayout.getLineCount()
                && readyLayout.getLineStart(startLine) < startChar) {
            startLine++;
        }
        ArrayList<ReaderPage> pages = new ArrayList<>(Math.max(1, pageCount));
        for (int i = 0; i < Math.max(1, pageCount)
                && startLine < readyLayout.getLineCount(); i++) {
            int endLine = forwardEndLine(readyLayout, startLine, spec.height);
            ReaderPage page = pageForLines(cache, readyLayout, startLine, endLine);
            if (page.endOffset > page.startOffset) pages.add(page);
            startLine = endLine;
        }
        return pages;
    }

    List<ReaderPage> paginateBackward(CombinedCacheSnapshot cache, long endOffset,
                                      LayoutSpec spec, int pageCount) {
        if (!canPaginate(cache, spec) || endOffset <= cache.offset) {
            return Collections.emptyList();
        }
        StaticLayout readyLayout = layoutFor(cache, spec);
        int endChar = cache.offsetMap.charIndexForByteOffset(endOffset - cache.offset);
        int endLine;
        if (endChar >= cache.text.length()) {
            endLine = readyLayout.getLineCount();
        } else {
            endLine = readyLayout.getLineForOffset(endChar);
            if (readyLayout.getLineStart(endLine) < endChar) endLine++;
        }
        if (endLine <= 0) return Collections.emptyList();
        ArrayList<ReaderPage> pages = new ArrayList<>(Math.max(1, pageCount));
        for (int i = 0; i < Math.max(1, pageCount) && endLine > 0; i++) {
            int startLine = backwardStartLine(readyLayout, endLine, spec.height);
            ReaderPage page = pageForLines(cache, readyLayout, startLine, endLine);
            if (page.endOffset > page.startOffset) pages.add(page);
            endLine = startLine;
        }
        Collections.reverse(pages);
        return pages;
    }

    private boolean canPaginate(CombinedCacheSnapshot cache, LayoutSpec spec) {
        return cache != null && cache.bytesRead > 0 && !cache.text.isEmpty()
                && spec != null && spec.width > 0 && spec.height > 0;
    }

    private StaticLayout layoutFor(CombinedCacheSnapshot cache, LayoutSpec spec) {
        if (layout != null && laidOutCache == cache && layoutKey == spec.key) return layout;
        CharSequence displayText = highlightedText(
                cache.text, spec.highlightQuery, spec.highlightColor);
        layout = StaticLayout.Builder.obtain(
                        displayText, 0, displayText.length(), spec.paint, spec.width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(spec.lineSpacingExtra, spec.lineSpacingMultiplier)
                .setBreakStrategy(spec.breakStrategy)
                .setHyphenationFrequency(spec.hyphenationFrequency)
                .build();
        laidOutCache = cache;
        layoutKey = spec.key;
        return layout;
    }

    private static int forwardEndLine(StaticLayout layout, int startLine, int height) {
        int limit = layout.getLineTop(startLine) + Math.max(1, height);
        int end = startLine;
        while (end < layout.getLineCount() && layout.getLineBottom(end) <= limit) end++;
        return end == startLine ? Math.min(layout.getLineCount(), startLine + 1) : end;
    }

    private static int backwardStartLine(StaticLayout layout, int endLine, int height) {
        int start = endLine - 1;
        int bottom = layout.getLineBottom(endLine - 1);
        while (start > 0
                && bottom - layout.getLineTop(start - 1) <= Math.max(1, height)) {
            start--;
        }
        return start;
    }

    private static ReaderPage pageForLines(CombinedCacheSnapshot cache, StaticLayout layout,
                                           int startLine, int endLine) {
        int startChar = layout.getLineStart(startLine);
        int endChar = layout.getLineEnd(Math.max(startLine, endLine - 1));
        int startBytes = cache.offsetMap.byteOffsetForCharIndex(startChar);
        int endBytes = cache.offsetMap.byteOffsetForCharIndex(endChar);
        return new ReaderPage(cache.offset + startBytes, cache.offset + endBytes,
                cache.text.substring(startChar, endChar), layout,
                layout.getLineTop(startLine),
                layout.getLineBottom(Math.max(startLine, endLine - 1)));
    }

    private static CharSequence highlightedText(String text, String query, int color) {
        if (query == null || query.isEmpty() || text.isEmpty()) return text;
        SpannableString highlighted = new SpannableString(text);
        int from = 0;
        while (from < text.length()) {
            int match = text.indexOf(query, from);
            if (match < 0) break;
            int end = match + query.length();
            highlighted.setSpan(new BackgroundColorSpan(color), match, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = end;
        }
        return highlighted;
    }

    static final class LayoutSpec {
        final TextPaint paint;
        final long key;
        final int width;
        final int height;
        final float lineSpacingExtra;
        final float lineSpacingMultiplier;
        final int breakStrategy;
        final int hyphenationFrequency;
        final String highlightQuery;
        final int highlightColor;

        LayoutSpec(TextPaint paint, long key, int width, int height, float lineSpacingExtra,
                   float lineSpacingMultiplier, int breakStrategy, int hyphenationFrequency) {
            this(paint, key, width, height, lineSpacingExtra, lineSpacingMultiplier,
                    breakStrategy, hyphenationFrequency, null, 0);
        }

        LayoutSpec(TextPaint paint, long key, int width, int height, float lineSpacingExtra,
                   float lineSpacingMultiplier, int breakStrategy, int hyphenationFrequency,
                   String highlightQuery, int highlightColor) {
            this.paint = new TextPaint(paint);
            this.key = key;
            this.width = width;
            this.height = height;
            this.lineSpacingExtra = lineSpacingExtra;
            this.lineSpacingMultiplier = lineSpacingMultiplier;
            this.breakStrategy = breakStrategy;
            this.hyphenationFrequency = hyphenationFrequency;
            this.highlightQuery = highlightQuery;
            this.highlightColor = highlightColor;
        }
    }
}
