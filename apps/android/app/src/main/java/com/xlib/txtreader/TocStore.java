package com.xlib.txtreader;

final class TocStore {
    private final LocalDatabase database;

    TocStore(LocalDatabase database) { this.database = database; }

    TocDocument read(Book book) { return database.readToc(book); }

    void write(Book book, TocDocument document) { database.writeToc(book, document); }

    void delete(Book book) { database.deleteToc(book.id); }
}
