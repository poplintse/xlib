package com.xlib.txtreader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** UI-owned formal position commit; immediately exposes movement to in-flight comparisons. */
final class ReadingProgressRecorder {
    private final LocalProgressStore local;
    private final SyncClock clock;
    private final Set<Long> pending = new HashSet<>();
    ReadingProgressRecorder(LocalProgressStore local, SyncClock clock) { this.local = local; this.clock = clock; }
    boolean record(Book book, long offset, Long preservedReadAt, boolean preparing) {
        if (!FormalReadingProgress.apply(book, offset, preservedReadAt, clock.nowMs(), preparing)) return false;
        pending.add(book.id);
        local.updatePosition(book.id, book.fileSize, book.offset, book.updatedAt);
        return true;
    }
    void saved(List<Book> books, Book preserved, long offset, long readAt) {
        for (Book book : books) {
            if (pending.contains(book.id)) local.updatePosition(book.id, book.fileSize,
                    book == preserved ? offset : book.offset, book == preserved ? readAt : book.updatedAt);
        }
        pending.clear();
    }
}
