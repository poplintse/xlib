package com.xlib.txtreader;

import java.util.List;

final class BookStore {
    private final LocalDatabase database;

    BookStore(LocalDatabase database) { this.database = database; }

    List<Book> load() { return database.loadBooks(); }

    boolean save(List<Book> books, Book preservedBook, long preservedOffset,
                 float preservedProgress) {
        return database.saveBooks(books, preservedBook, preservedOffset, preservedProgress);
    }

    void delete(Book book) { database.deleteBook(book); }
}
