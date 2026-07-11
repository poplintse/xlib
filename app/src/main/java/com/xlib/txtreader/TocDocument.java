package com.xlib.txtreader;

import java.util.List;

final class TocDocument {
    final long fileSize;
    final long modifiedAt;
    final String encoding;
    final List<TocEntry> entries;

    TocDocument(long fileSize, long modifiedAt, String encoding, List<TocEntry> entries) {
        this.fileSize = fileSize;
        this.modifiedAt = modifiedAt;
        this.encoding = encoding;
        this.entries = entries;
    }
}
