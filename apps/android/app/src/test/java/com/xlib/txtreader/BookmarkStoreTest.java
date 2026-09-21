package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BookmarkStoreTest {
    private Context context;
    private LocalDatabase database;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("xlib.db");
        database = LocalDatabase.open(context,
                context.getSharedPreferences("bookmark-store-test", Context.MODE_PRIVATE));
        File directory = new File(context.getFilesDir(), "books");
        assertTrue(directory.exists() || directory.mkdirs());
        Book first = book(1, new File(directory, "one.txt"));
        Book second = book(2, new File(directory, "two.txt"));
        Files.write(new File(first.path).toPath(), "one".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(new File(second.path).toPath(), "two".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        first.fileSize = new File(first.path).length(); second.fileSize = new File(second.path).length();
        assertTrue(database.saveBooks(List.of(first, second), null, 0, 0));
    }

    @After public void tearDown() {
        database.close();
        context.deleteDatabase("xlib.db");
    }

    @Test public void exactPositionUniquenessAndScopedSingleDeletion() {
        BookmarkStore store = new BookmarkStore(database);
        assertTrue(store.add(1, 1));
        Bookmark original = store.load(1).get(0);
        assertFalse(store.add(1, 1));
        assertEquals(original.createdAt, store.load(1).get(0).createdAt);
        assertTrue(store.add(1, 2));
        assertTrue(store.add(2, 1));
        store.delete(original);
        assertEquals(1, store.load(1).size());
        assertEquals(2, store.load(1).get(0).offset);
        assertEquals(1, store.load(2).size());
        store.delete(store.load(1).get(0));
        assertTrue(store.load(1).isEmpty());
        assertEquals(1, store.load(2).size());
    }

    private Book book(long id, File file) {
        Book book = new Book();
        book.id = id; book.title = file.getName(); book.sourceName = file.getName(); book.author = "";
        book.path = file.getAbsolutePath(); book.encoding = "UTF-8"; book.pageMode = true;
        return book;
    }
}
