package com.xlib.txtreader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class BookStore {
    private static final String KEY_BOOKS = "books";
    private static final int THEME_SYSTEM = 0;
    private static final int SENSITIVITY_HIGH = 0;
    private static final int SENSITIVITY_STANDARD = 1;
    private static final int SENSITIVITY_LOW = 2;

    private final SharedPreferences preferences;

    BookStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    List<Book> load() {
        List<Book> books = new ArrayList<>();
        String raw = preferences.getString(KEY_BOOKS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Book book = fromJson(array.getJSONObject(i));
                File file = new File(book.path);
                if (!book.path.isEmpty() && file.exists()) {
                    book.fileSize = file.length();
                    if (book.encoding.isEmpty()) book.encoding = TextFileUtils.detectEncoding(file);
                    if (book.offset <= 0 && book.progress > 0) {
                        book.offset = (long) (book.fileSize * book.progress);
                    }
                    books.add(book);
                }
            }
        } catch (Exception ignored) {
            books.clear();
        }
        return books;
    }

    void save(List<Book> books, Book preservedBook, long preservedOffset,
              float preservedProgress) {
        try {
            JSONArray array = new JSONArray();
            for (Book book : books) {
                long offset = book == preservedBook ? preservedOffset : book.offset;
                float progress = book == preservedBook ? preservedProgress : book.progress;
                array.put(toJson(book, offset, progress));
            }
            preferences.edit().putString(KEY_BOOKS, array.toString()).apply();
        } catch (Exception ignored) {
            // Keep the last valid snapshot if serialization ever fails.
        }
    }

    private Book fromJson(JSONObject item) {
        Book book = new Book();
        book.id = item.optLong("id");
        book.title = item.optString("title");
        book.path = item.optString("path");
        book.fileSize = item.optLong("fileSize", 0L);
        book.encoding = item.optString("encoding", "UTF-8");
        book.offset = item.optLong("offset", 0L);
        book.progress = (float) item.optDouble("progress", 0d);
        book.fontSize = (float) item.optDouble("fontSize", 20d);
        book.theme = item.optInt("theme", THEME_SYSTEM);
        book.pageMode = item.optBoolean("pageMode", true);
        book.sensitivity = Math.max(SENSITIVITY_HIGH, Math.min(SENSITIVITY_LOW,
                item.optInt("sensitivity", SENSITIVITY_STANDARD)));
        book.updatedAt = item.optLong("updatedAt", System.currentTimeMillis());
        return book;
    }

    private JSONObject toJson(Book book, long offset, float progress) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", book.id);
        item.put("title", book.title);
        item.put("path", book.path);
        item.put("fileSize", book.fileSize);
        item.put("encoding", book.encoding);
        item.put("offset", offset);
        item.put("progress", progress);
        item.put("fontSize", book.fontSize);
        item.put("theme", book.theme);
        item.put("pageMode", book.pageMode);
        item.put("sensitivity", book.sensitivity);
        item.put("updatedAt", book.updatedAt);
        return item;
    }
}
