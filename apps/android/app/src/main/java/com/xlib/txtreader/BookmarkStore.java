package com.xlib.txtreader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class BookmarkStore {
    private static final String KEY_BOOKMARKS = "bookmarks";
    private final SharedPreferences preferences;

    BookmarkStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    List<Bookmark> load(long bookId) {
        ArrayList<Bookmark> result = new ArrayList<>();
        for (Bookmark bookmark : loadAll()) {
            if (bookmark.bookId == bookId) result.add(bookmark);
        }
        return result;
    }

    boolean add(long bookId, long offset) {
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
        ArrayList<Bookmark> remaining = new ArrayList<>();
        for (Bookmark bookmark : loadAll()) {
            if (bookmark.bookId != bookId) remaining.add(bookmark);
        }
        save(remaining);
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
