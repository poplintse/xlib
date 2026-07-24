package com.xlib.txtreader;

final class Bookmark {
    final long id;
    final long bookId;
    final long offset;
    final long createdAt;

    Bookmark(long id, long bookId, long offset, long createdAt) {
        this.id = id;
        this.bookId = bookId;
        this.offset = offset;
        this.createdAt = createdAt;
    }
}
