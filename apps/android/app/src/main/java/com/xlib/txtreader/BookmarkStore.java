package com.xlib.txtreader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class BookmarkStore {
    private static final String KEY_BOOKMARKS = "bookmarks";
    private final SharedPreferences preferences;
    private final LocalDatabase database;

    BookmarkStore(SharedPreferences preferences) {
        this.preferences = preferences;
        this.database = null;
    }

    BookmarkStore(LocalDatabase database) { this.preferences = null; this.database = database; }

    List<Bookmark> load(long bookId) {
        if (database != null) return database.loadBookmarks(bookId);
        ArrayList<Bookmark> result = new ArrayList<>();
        for (Bookmark bookmark : loadAll()) {
            if (bookmark.bookId == bookId) result.add(bookmark);
        }
        return result;
    }

    boolean add(long bookId, long offset) {
        if (database != null) return database.addBookmark(bookId, offset);
        ArrayList<Bookmark> all = loadAll();
        for (Bookmark bookmark : all) {
            if (bookmark.bookId == bookId && bookmark.offset == offset) return false;
        }
        long now = System.currentTimeMillis();
        all.add(0, new Bookmark(now, bookId, offset, now));
        save(all);
        return true;
    }

    void deleteForBook(long bookId) {
        if (database != null) { database.deleteBookmarks(bookId); return; }
        ArrayList<Bookmark> remaining = new ArrayList<>();
        for (Bookmark bookmark : loadAll()) {
            if (bookmark.bookId != bookId) remaining.add(bookmark);
        }
        save(remaining);
    }

    void delete(Bookmark target) {
        if (database != null) { database.deleteBookmark(target); return; }
        ArrayList<Bookmark> all = loadAll();
        for (int i = 0; i < all.size(); i++) {
            Bookmark item = all.get(i);
            if (item.id == target.id && item.bookId == target.bookId && item.offset == target.offset) {
                all.remove(i);
                save(all);
                return;
            }
        }
    }

    private ArrayList<Bookmark> loadAll() {
        ArrayList<Bookmark> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_BOOKMARKS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new Bookmark(item.optLong("id"), item.optLong("bookId"),
                        item.optLong("offset"), item.optLong("createdAt")));
            }
        } catch (Exception ignored) {
            result.clear();
        }
        return result;
    }

    private void save(List<Bookmark> bookmarks) {
        try {
            JSONArray array = new JSONArray();
            for (Bookmark bookmark : bookmarks) {
                JSONObject item = new JSONObject();
                item.put("id", bookmark.id);
                item.put("bookId", bookmark.bookId);
                item.put("offset", bookmark.offset);
                item.put("createdAt", bookmark.createdAt);
                array.put(item);
            }
            preferences.edit().putString(KEY_BOOKMARKS, array.toString()).apply();
        } catch (Exception ignored) {
            // Preserve the last valid snapshot if serialization fails.
        }
    }
}
