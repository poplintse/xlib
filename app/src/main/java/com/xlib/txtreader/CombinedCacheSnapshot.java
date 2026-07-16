package com.xlib.txtreader;

/** Immutable, continuous reader cache published after all disk segments have been merged. */
final class CombinedCacheSnapshot {
    final long offset;
    final String text;
    final int bytesRead;
    final ByteOffsetMap offsetMap;

    CombinedCacheSnapshot(long offset, String text, int bytesRead, ByteOffsetMap offsetMap) {
        this.offset = Math.max(0L, offset);
        this.text = text == null ? "" : text;
        this.bytesRead = Math.max(0, bytesRead);
        this.offsetMap = offsetMap;
    }

    long endOffset() {
        return offset + bytesRead;
    }
}
