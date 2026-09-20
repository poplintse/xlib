package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LocalDatabaseTest {
    private Context context;
    private SharedPreferences legacy;
    private LocalDatabase database;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("xlib.db");
        legacy = context.getSharedPreferences("local-database-test", Context.MODE_PRIVATE);
        legacy.edit().clear().commit();
    }

    @After public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase("xlib.db");
        legacy.edit().clear().commit();
    }

    @Test public void migratesFormalDataSettingsAndConfigurationIdempotently() throws Exception {
        File file = managedBook("migration.txt", "第一章\n正文");
        JSONObject book = legacyBook(101L, file, 3L, 1_700_000_000_000L);
        JSONObject bookmark = new JSONObject().put("id", 201L).put("bookId", 101L)
                .put("offset", 2L).put("createdAt", 1_700_000_100_000L);
        legacy.edit()
                .putString("books", new JSONArray().put(book).toString())
                .putString("bookmarks", new JSONArray().put(bookmark).toString())
                .putFloat("font_size", 24f)
                .putString("sync_configured_email", "reader@example.com")
                .putBoolean("sync_started", true)
                .commit();

        database = LocalDatabase.open(context, legacy);
        List<Book> books = database.loadBooks();
        assertEquals(1, books.size());
        assertEquals(3L, books.get(0).offset);
        assertEquals(1, database.loadBookmarks(101L).size());
        assertEquals("24.0", database.setting("font_size", null));
        assertEquals("reader@example.com", database.syncValue("sync_configured_email", null));
        assertTrue(database.syncBoolean("sync_started", false));
        ReadingPreferences settings = new ReadingPreferences(database);
        settings.setReadingFontSize(19f);
        assertEquals("19.0", database.setting("font_size", null));
        assertEquals(24f, legacy.getFloat("font_size", 0), 0);

        database.close();
        database = LocalDatabase.open(context, legacy);
        assertEquals(1, database.loadBooks().size());
        assertEquals(1, database.loadBookmarks(101L).size());
        assertTrue(file.exists());
    }

    @Test public void orphanBookmarkRollsBackEntireMigration() throws Exception {
        JSONObject orphan = new JSONObject().put("id", 1L).put("bookId", 999L)
                .put("offset", 1L).put("createdAt", 2L);
        legacy.edit().putString("bookmarks", new JSONArray().put(orphan).toString()).commit();

        assertThrows(Exception.class, () -> LocalDatabase.open(context, legacy));
        legacy.edit().remove("bookmarks").commit();
        database = LocalDatabase.open(context, legacy);
        assertTrue(database.loadBooks().isEmpty());
    }

    @Test public void rejectsChangedLegacySourceAfterMigration() throws Exception {
        database = LocalDatabase.open(context, legacy);
        database.close();
        database = null;
        legacy.edit().putBoolean("auto_toc", true).commit();

        assertThrows(IllegalStateException.class, () -> LocalDatabase.open(context, legacy));
    }

    @Test public void missingBookFileRollsBackFormalMigration() throws Exception {
        File missing = new File(new File(context.getFilesDir(), "books"), "missing.txt");
        legacy.edit().putString("books", new JSONArray().put(legacyBook(9L, missing, 0L, 0L)).toString()).commit();

        assertThrows(Exception.class, () -> LocalDatabase.open(context, legacy));
    }

    @Test public void retriesAFileDeletionThatCouldNotComplete() throws Exception {
        File directory = new File(new File(context.getFilesDir(), "books"), "blocked.txt");
        assertTrue(directory.mkdirs());
        Files.write(new File(directory, "child").toPath(), new byte[]{1});
        legacy.edit().putString("books", new JSONArray().put(legacyBook(7L, directory, 0L, 0L)).toString()).commit();
        database = LocalDatabase.open(context, legacy);
        Book book = database.loadBooks().get(0);

        database.deleteBook(book);
        assertTrue(directory.exists());
        assertTrue(database.loadBooks().isEmpty());
        assertTrue(new File(directory, "child").delete());
        database.loadBooks();
        assertFalse(directory.exists());
    }

    @Test public void invalidTocCacheDoesNotLeaveAnEmptyDocument() throws Exception {
        File file = managedBook("toc.txt", "第一章\n正文");
        legacy.edit().putString("books", new JSONArray().put(legacyBook(12L, file, 0L, 0L)).toString()).commit();
        File directory = new File(context.getFilesDir(), "toc");
        assertTrue(directory.exists() || directory.mkdirs());
        JSONObject invalid = new JSONObject().put("fileSize", file.length())
                .put("modifiedAt", file.lastModified()).put("encoding", "UTF-8");
        Files.write(new File(directory, "12.json").toPath(), invalid.toString().getBytes(StandardCharsets.UTF_8));

        database = LocalDatabase.open(context, legacy);
        Book migrated = database.loadBooks().get(0);
        assertTrue(database.readToc(migrated) == null);
    }

    private File managedBook(String name, String text) throws Exception {
        File directory = new File(context.getFilesDir(), "books");
        assertTrue(directory.exists() || directory.mkdirs());
        File file = new File(directory, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private JSONObject legacyBook(long id, File file, long offset, long updatedAt) throws Exception {
        return new JSONObject().put("id", id).put("title", "迁移测试")
                .put("sourceName", file.getName()).put("author", "作者").put("path", file.getAbsolutePath())
                .put("fileSize", file.length()).put("encoding", "UTF-8").put("offset", offset)
                .put("progress", 0).put("pageMode", true).put("updatedAt", updatedAt);
    }
}
