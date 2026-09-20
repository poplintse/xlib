package com.xlib.txtreader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local shelf use cases. Deleting local data never calls the sync API. */
final class LibraryController {
    private final BookStore store;
    private final CatalogController catalog;
    private final ReaderCacheStore cache;
    private final BookHashCache hashes;
    private final List<Book> books = new ArrayList<>();
    LibraryController(BookStore store, CatalogController catalog, ReaderCacheStore cache, BookHashCache hashes) {
        this.store = store; this.catalog = catalog; this.cache = cache; this.hashes = hashes;
    }
    List<Book> books() { return Collections.unmodifiableList(books); }
    void load() { books.clear(); books.addAll(store.load()); }
    void add(Book book) { books.add(0, book); }
    void edit(Book book, String title, String author) { book.title = title; book.author = author; }
    boolean save(Book preserved, long offset, float progress) { return store.save(books, preserved, offset, progress); }
    void delete(Book book) {
        cache.delete(book);
        catalog.delete(book);
        hashes.remove(book.id);
        store.delete(book);
        books.remove(book);
    }
}
