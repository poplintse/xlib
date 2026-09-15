package com.xlib.txtreader;

final class FormalReadingProgress {
    private FormalReadingProgress() { }

    static boolean apply(Book book, long offset, Long preservedTime, long now, boolean preparing) {
        if (preparing && preservedTime == null) return false;
        long safeOffset = Math.max(0L, Math.min(offset, Math.max(0L, book.fileSize)));
        if (book.offset == safeOffset && preservedTime == null) return false;
        book.offset = safeOffset;
        book.progress = (float) SyncRules.progress(safeOffset, book.fileSize);
        book.updatedAt = preservedTime == null ? SyncRules.monotonicReadAt(now, book.updatedAt) : preservedTime;
        return true;
    }
}
