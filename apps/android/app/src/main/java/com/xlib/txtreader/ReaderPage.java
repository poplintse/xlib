package com.xlib.txtreader;

import android.text.StaticLayout;

/** One immutable, already-paginated page owned by the reader sliding window. */
final class ReaderPage {
    final long startOffset;
    final long endOffset;
    final String text;
    final StaticLayout renderLayout;
    final int layoutTop;
    final int layoutBottom;

    ReaderPage(long startOffset, long endOffset, String text) {
        this(startOffset, endOffset, text, null, 0, 0);
    }

    ReaderPage(long startOffset, long endOffset, String text,
               StaticLayout renderLayout, int layoutTop, int layoutBottom) {
        this.startOffset = Math.max(0L, startOffset);
        this.endOffset = Math.max(this.startOffset, endOffset);
        this.text = text == null ? "" : text;
        this.renderLayout = renderLayout;
        this.layoutTop = Math.max(0, layoutTop);
        this.layoutBottom = Math.max(this.layoutTop, layoutBottom);
    }

    boolean contains(long offset, long fileSize) {
        return offset >= startOffset
                && (offset < endOffset || (endOffset >= fileSize && offset == endOffset));
    }
}
