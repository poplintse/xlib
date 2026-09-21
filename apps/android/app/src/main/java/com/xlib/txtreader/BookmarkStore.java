package com.xlib.txtreader;

import java.util.List;

final class BookmarkStore {
    private final LocalDatabase database;

    BookmarkStore(LocalDatabase database) { this.database = database; }

    List<Bookmark> load(long bookId) { return database.loadBookmarks(bookId); }

    boolean add(long bookId, long offset) { return database.addBookmark(bookId, offset); }

    void deleteForBook(long bookId) { database.deleteBookmarks(bookId); }

    void delete(Bookmark target) { database.deleteBookmark(target); }
}
