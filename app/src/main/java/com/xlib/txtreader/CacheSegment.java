package com.xlib.txtreader;

/** One contiguous, character-aligned block read from the source file. */
final class CacheSegment {
    final long offset;
    final String text;
    final int bytesRead;
    final ByteOffsetMap offsetMap;

    CacheSegment(long offset, String text, int bytesRead, ByteOffsetMap offsetMap) {
        this.offset = Math.max(0L, offset);
        this.text = text == null ? "" : text;
        this.bytesRead = Math.max(0, bytesRead);
        this.offsetMap = offsetMap;
    }

    long endOffset() {
        return offset + bytesRead;
    }
}
