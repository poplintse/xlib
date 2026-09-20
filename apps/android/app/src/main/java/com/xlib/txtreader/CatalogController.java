package com.xlib.txtreader;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

final class CatalogController {
    interface Enabled { boolean get(); }
    interface Listener { void completed(TocDocument document, Exception error); }
    private final TocStore toc;
    private final BookmarkStore bookmarks;
    private final Executor worker, ui;
    private final Set<Long> deleted = new HashSet<>();
    private boolean closed;
    CatalogController(TocStore toc, BookmarkStore bookmarks, Executor worker, Executor ui) {
        this.toc = toc; this.bookmarks = bookmarks; this.worker = worker; this.ui = ui;
    }
    synchronized void close() { closed = true; }
    synchronized void delete(Book book) {
        deleted.add(book.id);
        toc.delete(book);
        bookmarks.deleteForBook(book.id);
    }
    TocDocument read(Book book) { return toc.read(book); }
    boolean bookmark(Book book) { return bookmarks.add(book.id, book.offset); }
    List<Bookmark> bookmarks(Book book) { return bookmarks.load(book.id); }
    void deleteBookmark(Bookmark bookmark) { bookmarks.delete(bookmark); }
    void generate(Book book, Enabled enabled, boolean onlyMissing, Listener listener) {
        worker.execute(() -> {
            TocDocument result = null;
            Exception failure = null;
            try {
                synchronized (this) {
                    if (closed || deleted.contains(book.id) || !enabled.get()) return;
                    if (onlyMissing && toc.read(book) != null) return;
                }
                result = TocGenerator.generate(new File(book.path), book.encoding);
                synchronized (this) {
                    if (closed || deleted.contains(book.id) || !enabled.get()) return;
                    toc.write(book, result);
                }
            } catch (Exception error) { failure = error; }
            TocDocument document = result;
            Exception error = failure;
            ui.execute(() -> {
                synchronized (this) { if (closed || deleted.contains(book.id)) return; }
                listener.completed(document, error);
            });
        });
    }
}
