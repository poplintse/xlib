package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LocalDatabaseTest {
    private static final String MIGRATION_ID = "android-shared-preferences-v1";
    private Context context;
    private SharedPreferences preferences;
    private LocalDatabase database;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("xlib.db");
        preferences = context.getSharedPreferences("local-database-test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @After public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase("xlib.db");
        preferences.edit().clear().commit();
    }

    @Test public void persistsFormalDataSettingsAndConfigurationAcrossReopen() throws Exception {
        database = LocalDatabase.open(context, preferences);
        Book book = book(101L, managedBook("sqlite.txt", "第一章\n正文"), 3L, 1_700_000_000_000L);
        assertTrue(database.saveBooks(List.of(book), null, 0, 0));
        assertTrue(database.addBookmark(book.id, 2L));
        ReadingPreferences settings = new ReadingPreferences(database);
        settings.setReadingFontSize(19f);
        database.putSyncValue("sync_configured_email", "reader@example.com");

        database.close();
        database = LocalDatabase.open(context, preferences);
        assertEquals(1, database.loadBooks().size());
        assertEquals(3L, database.loadBooks().get(0).offset);
        assertEquals(1, database.loadBookmarks(book.id).size());
        assertEquals("19.0", database.setting("font_size", null));
        assertEquals("reader@example.com", database.syncValue("sync_configured_email", null));
        assertTrue(new File(book.path).exists());
    }

    @Test public void freshDatabaseWithoutMigrationLedgerDoesNotDeleteLegacyData() throws Exception {
        preferences.edit().putString("books", "unmigrated").putString("future_key", "keep").commit();
        LocalDatabase database = LocalDatabase.open(context, preferences);

        assertEquals("unmigrated", preferences.getString("books", null));
        assertEquals("keep", preferences.getString("future_key", null));
        database.close();
    }

    @Test public void upgradesV1AndCleansOnlyAllowlistedLegacyData() throws Exception {
        File bookFile = managedBook("cleanup.txt", "正文");
        createMigratedV1Database(book(7L, bookFile, 1L, 10L));
        preferences.edit()
                .putString("books", "legacy-books")
                .putString("bookmarks", "legacy-bookmarks")
                .putFloat("font_size", 24f)
                .putString("sync_book_hash_7", "legacy-hash")
                .putString("sync_token_ciphertext", "protected-token")
                .putString("sync_token_iv", "protected-iv")
                .putString("future_unknown_key", "keep")
                .commit();
        writeLedgerAndDowngradeToV1(legacyFingerprint());
        File tocDirectory = new File(context.getFilesDir(), "toc");
        assertTrue(tocDirectory.exists() || tocDirectory.mkdirs());
        File legacyToc = new File(tocDirectory, "7.json");
        Files.write(legacyToc.toPath(), "{}".getBytes(StandardCharsets.UTF_8));

        database = LocalDatabase.open(context, preferences);

        assertFalse(preferences.contains("books"));
        assertFalse(preferences.contains("bookmarks"));
        assertFalse(preferences.contains("font_size"));
        assertFalse(preferences.contains("sync_book_hash_7"));
        assertEquals("protected-token", preferences.getString("sync_token_ciphertext", null));
        assertEquals("protected-iv", preferences.getString("sync_token_iv", null));
        assertEquals("keep", preferences.getString("future_unknown_key", null));
        assertFalse(legacyToc.exists());
        assertTrue(bookFile.exists());
        assertEquals(1, database.loadBooks().size());
        assertEquals("completed", cleanupState());

        database.close();
        database = LocalDatabase.open(context, preferences);
        assertEquals("completed", cleanupState());
        assertEquals("protected-token", preferences.getString("sync_token_ciphertext", null));
    }

    @Test public void interruptedCleanupResumesWithoutDeletedSourceValues() throws Exception {
        File bookFile = managedBook("resume.txt", "正文");
        createMigratedV1Database(book(8L, bookFile, 0L, 0L));
        preferences.edit().putString("books", "legacy-books")
                .putString("bookmarks", "legacy-bookmarks").commit();
        String fingerprint = legacyFingerprint();
        writeLedgerAndDowngradeToV1(fingerprint);
        SQLiteDatabase raw = writableDatabase();
        raw.execSQL("create table legacy_cleanup (migration_id text primary key references legacy_migrations(migration_id),"
                + "source_fingerprint text not null,state text not null,started_at_ms integer not null,"
                + "completed_at_ms integer,removed_items integer not null default 0)");
        raw.execSQL("insert into legacy_cleanup values(?,?,?, ?,null,0)",
                new Object[]{MIGRATION_ID, fingerprint, "in_progress", 1L});
        raw.execSQL("pragma user_version=2");
        raw.close();
        preferences.edit().remove("books").commit();

        database = LocalDatabase.open(context, preferences);

        assertFalse(preferences.contains("bookmarks"));
        assertEquals("completed", cleanupState());
        assertTrue(bookFile.exists());
    }

    @Test public void changedSourceBlocksCleanupBeforeAnyDeletion() throws Exception {
        File bookFile = managedBook("changed.txt", "正文");
        createMigratedV1Database(book(9L, bookFile, 0L, 0L));
        preferences.edit().putString("books", "original").commit();
        writeLedgerAndDowngradeToV1(legacyFingerprint());
        preferences.edit().putString("books", "changed").commit();

        assertThrows(IllegalStateException.class, () -> LocalDatabase.open(context, preferences));
        assertEquals("changed", preferences.getString("books", null));
        assertTrue(bookFile.exists());
    }

    @Test public void missingBookFileBlocksCleanupAndPreservesLegacy() throws Exception {
        File bookFile = managedBook("missing.txt", "正文");
        createMigratedV1Database(book(10L, bookFile, 0L, 0L));
        preferences.edit().putString("books", "legacy").commit();
        writeLedgerAndDowngradeToV1(legacyFingerprint());
        assertTrue(bookFile.delete());

        assertThrows(IllegalStateException.class, () -> LocalDatabase.open(context, preferences));
        assertEquals("legacy", preferences.getString("books", null));
    }

    @Test public void retriesAFileDeletionThatCouldNotComplete() throws Exception {
        database = LocalDatabase.open(context, preferences);
        File directory = new File(new File(context.getFilesDir(), "books"), "blocked.txt");
        assertTrue(directory.mkdirs());
        Files.write(new File(directory, "child").toPath(), new byte[]{1});
        Book book = book(11L, directory, 0L, 0L);
        assertTrue(database.saveBooks(List.of(book), null, 0, 0));

        database.deleteBook(book);
        assertTrue(directory.exists());
        assertTrue(database.loadBooks().isEmpty());
        assertTrue(new File(directory, "child").delete());
        database.loadBooks();
        assertFalse(directory.exists());
    }

    @Test public void fullDatabaseRollsBackTheWholeLibraryWrite() throws Exception {
        database = LocalDatabase.open(context, preferences);
        Book original = book(20L, managedBook("original.txt", "原始正文"), 2L, 100L);
        assertTrue(database.saveBooks(List.of(original), null, 0, 0));

        SQLiteDatabase raw = database.getWritableDatabase();
        raw.disableWriteAheadLogging();
        long pages;
        try (Cursor cursor = raw.rawQuery("pragma page_count", null)) {
            assertTrue(cursor.moveToFirst());
            pages = cursor.getLong(0);
        }
        assertEquals(pages * raw.getPageSize(), raw.setMaximumSize(pages * raw.getPageSize()));

        Book oversized = book(21L, managedBook("oversized.txt", "新正文"), 1L, 200L);
        oversized.title = "x".repeat(2 * 1024 * 1024);
        assertFalse(database.saveBooks(List.of(original, oversized), null, 0, 0));

        List<Book> stored = database.loadBooks();
        assertEquals(1, stored.size());
        assertEquals(original.id, stored.get(0).id);
        assertEquals(2L, stored.get(0).offset);
        assertEquals(100L, stored.get(0).updatedAt);
    }

    private void createMigratedV1Database(Book book) throws Exception {
        database = LocalDatabase.open(context, preferences);
        assertTrue(database.saveBooks(List.of(book), null, 0, 0));
        database.close();
        database = null;
    }

    private void writeLedgerAndDowngradeToV1(String fingerprint) {
        SQLiteDatabase raw = writableDatabase();
        ContentValues ledger = new ContentValues();
        ledger.put("migration_id", MIGRATION_ID);
        ledger.put("source_fingerprint", fingerprint);
        ledger.put("completed_at_ms", 1L);
        ledger.put("imported_rows", 1);
        raw.insertOrThrow("legacy_migrations", null, ledger);
        raw.execSQL("drop table legacy_cleanup");
        raw.execSQL("pragma user_version=1");
        raw.close();
    }

    private String cleanupState() {
        SQLiteDatabase raw = SQLiteDatabase.openDatabase(context.getDatabasePath("xlib.db").getPath(), null,
                SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = raw.rawQuery("select state from legacy_cleanup where migration_id=?",
                new String[]{MIGRATION_ID})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        } finally { raw.close(); }
    }

    private SQLiteDatabase writableDatabase() {
        return SQLiteDatabase.openDatabase(context.getDatabasePath("xlib.db").getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
    }

    private String legacyFingerprint() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ArrayList<String> keys = new ArrayList<>(preferences.getAll().keySet());
        Collections.sort(keys);
        for (String key : keys) {
            if (key.equals("sync_token_ciphertext") || key.equals("sync_token_iv")) continue;
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update(String.valueOf(preferences.getAll().get(key)).getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private File managedBook(String name, String text) throws Exception {
        File directory = new File(context.getFilesDir(), "books");
        assertTrue(directory.exists() || directory.mkdirs());
        File file = new File(directory, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private Book book(long id, File file, long offset, long updatedAt) {
        Book book = new Book();
        book.id = id; book.title = "SQLite 测试"; book.sourceName = file.getName(); book.author = "作者";
        book.path = file.getAbsolutePath(); book.fileSize = file.length(); book.encoding = "UTF-8";
        book.offset = offset; book.progress = book.fileSize == 0 ? 0 : (float) offset / book.fileSize;
        book.pageMode = true; book.updatedAt = updatedAt;
        return book;
    }
}
