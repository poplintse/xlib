package com.xlib.txtreader;

final class TocEntry {
    final int level;
    final String title;
    final long offset;

    TocEntry(int level, String title, long offset) {
        this.level = level;
        this.title = title;
        this.offset = offset;
    }
}
