package com.xlib.txtreader;

import java.util.ArrayList;

final class Book {
    long id;
    String title;
    String sourceName;
    String author;
    String path;
    long fileSize;
    String encoding;
    long offset;
    float progress;
    boolean pageMode;
    long updatedAt;
    transient long indexFileSize;
    transient ArrayList<Long> indexOffsets;
    transient boolean indexBuilding;
}
