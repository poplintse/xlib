package com.xlib.txtreader;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BookStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void missingReadTimeDoesNotInventReadingEventAndKnownTimesSurvive() throws Exception {
        SharedPreferences preferences = MemoryPreferences.create();
        JSONObject book = new JSONObject().put("id", 1)
                .put("path", temporary.newFile("book.txt").getAbsolutePath())
                .put("encoding", "UTF-8");
        BookStore store = new BookStore(preferences);
        for (Long time : new Long[]{null, 0L, 123L}) {
            if (time == null) book.remove("updatedAt");
            else book.put("updatedAt", time);
            preferences.edit().putString("books", new JSONArray().put(book).toString()).apply();
            Book loaded = store.load().get(0);
            assertEquals(time == null ? 0L : time.longValue(), loaded.updatedAt);
            assertTrue(store.save(java.util.List.of(loaded), null, 0, 0));
            assertEquals(loaded.updatedAt, store.load().get(0).updatedAt);
        }
    }
}
