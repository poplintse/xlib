package com.xlib.txtreader;

import java.util.ArrayList;

final class Book {
    long id;
    String title;
    String path;
    long fileSize;
    String encoding;
    long offset;
    float progress;
    float fontSize;
    int theme;
    boolean pageMode;
    int sensitivity;
    long updatedAt;
    transient long indexFileSize;
    transient ArrayList<Long> indexOffsets;
    transient boolean indexBuilding;
}
