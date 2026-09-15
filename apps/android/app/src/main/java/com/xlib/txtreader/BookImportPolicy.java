package com.xlib.txtreader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class BookImportPolicy {
    static final int MAX_BOOKS = 20;
    private static long lastId;

    private BookImportPolicy() { }

    static <T> List<T> selection(List<T> items) {
        LinkedHashSet<T> unique = new LinkedHashSet<>();
        for (T item : items) {
            if (item != null) unique.add(item);
            if (unique.size() > MAX_BOOKS) {
                throw new IllegalArgumentException("每次最多选择 20 本书籍，请重新选择");
            }
        }
        return new ArrayList<>(unique);
    }

    static synchronized long nextId() {
        lastId = Math.max(System.currentTimeMillis(), lastId + 1);
        return lastId;
    }
}
