package com.xlib.txtreader;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The only Android SQLite boundary. Business code uses typed methods, never SQL. */
final class LocalDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "xlib.db";
    private static final int DATABASE_VERSION = 1;
    private static final String LEGACY_MIGRATION = "android-shared-preferences-v1";

    private final Context context;
    private final File booksDirectory;

    private LocalDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
        this.booksDirectory = new File(context.getFilesDir(), "books");
    }

    static LocalDatabase open(Context context, SharedPreferences legacy) throws Exception {
        LocalDatabase database = new LocalDatabase(context);
        database.setWriteAheadLoggingEnabled(true);
        SQLiteDatabase sql = database.getWritableDatabase();
        sql.setForeignKeyConstraintsEnabled(true);
        database.migrateLegacy(sql, legacy);
        return database;
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table books (local_id text primary key, title text not null, "
                + "source_name text not null, author text not null, relative_path text not null unique, "
                + "file_size integer not null check(file_size >= 0), source_modified_at_ms integer, "
                + "encoding text not null)");
        db.execSQL("create table reading_progress (book_id text primary key references books(local_id) "
                + "on delete cascade, offset_bytes integer not null check(offset_bytes >= 0), read_at_ms integer)");
        db.execSQL("create table book_preferences (book_id text primary key references books(local_id) "
                + "on delete cascade, page_mode integer not null check(page_mode in (0,1)))");
        db.execSQL("create table bookmarks (local_id text primary key, book_id text not null references "
                + "books(local_id) on delete cascade, offset_bytes integer not null check(offset_bytes >= 0), "
                + "excerpt text, created_at_ms integer not null, unique(book_id, offset_bytes))");
        db.execSQL("create table toc_documents (book_id text primary key references books(local_id) "
                + "on delete cascade, source_file_size integer not null, source_modified_at_ms integer not null, "
                + "encoding text, generator_schema_version integer not null)");
        db.execSQL("create table toc_entries (book_id text not null references toc_documents(book_id) "
                + "on delete cascade, ordinal integer not null, entry_id text, title text not null, "
                + "offset_bytes integer not null, level integer not null, primary key(book_id, ordinal))");
        db.execSQL("create table settings (key text primary key, value text not null)");
        db.execSQL("create table sync_configuration (singleton_id integer primary key check(singleton_id=1), "
                + "configured_email text, authenticated_email text, device_id text, device_name text, "
                + "server_url text, has_started integer not null default 0 check(has_started in (0,1)), "
                + "credential_server_url text, active_email text, active_device_name text)");
        db.execSQL("insert into sync_configuration(singleton_id) values(1)");
        db.execSQL("create table book_hash_cache (book_id text primary key references books(local_id) "
                + "on delete cascade, source_file_size integer not null, source_modified_at_ms integer not null, "
                + "book_hash text not null)");
        db.execSQL("create table remote_progress_cache (account_scope text not null, book_hash text not null, "
                + "file_size integer not null, offset_bytes integer not null, read_at_ms integer not null, "
                + "version text not null, source_device_id text not null, source_device_name text not null, "
                + "source_platform text not null, fetched_at_ms integer, primary key(account_scope, book_hash, file_size))");
        db.execSQL("create table pending_file_deletions (relative_path text primary key, requested_at_ms integer not null)");
        db.execSQL("create table legacy_migrations (migration_id text primary key, source_fingerprint text not null, "
                + "completed_at_ms integer not null, imported_rows integer not null)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported local database upgrade " + oldVersion + " -> " + newVersion);
    }

    synchronized List<Book> loadBooks() {
        retryPendingFileDeletions();
        SQLiteDatabase db = getReadableDatabase();
        ArrayList<Book> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "select b.local_id,b.title,b.source_name,b.author,b.relative_path,b.file_size,b.encoding,"
                        + "p.offset_bytes,p.read_at_ms,coalesce(bp.page_mode,1) "
                        + "from books b left join reading_progress p on p.book_id=b.local_id "
                        + "left join book_preferences bp on bp.book_id=b.local_id order by coalesce(p.read_at_ms,0) desc", null)) {
            while (cursor.moveToNext()) {
                File file = new File(context.getFilesDir(), cursor.getString(4));
                if (!file.exists()) continue;
                Book book = new Book();
                book.id = Long.parseLong(cursor.getString(0));
                book.title = cursor.getString(1);
                book.sourceName = cursor.getString(2);
                book.author = cursor.getString(3);
                book.path = file.getAbsolutePath();
                book.fileSize = file.length();
                book.encoding = cursor.getString(6);
                book.offset = cursor.isNull(7) ? 0L : Math.min(book.fileSize, cursor.getLong(7));
                book.progress = book.fileSize <= 0 ? 0f : (float) book.offset / book.fileSize;
                book.updatedAt = cursor.isNull(8) ? 0L : cursor.getLong(8);
                book.pageMode = cursor.getInt(9) != 0;
                result.add(book);
            }
        }
        return result;
    }

    synchronized boolean saveBooks(List<Book> books, Book preservedBook, long preservedOffset,
                                   float preservedProgress) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Set<String> retained = new HashSet<>();
            for (Book book : books) {
                String id = String.valueOf(book.id);
                retained.add(id);
                String relative = managedRelativePath(new File(book.path));
                ContentValues values = new ContentValues();
                values.put("local_id", id); values.put("title", safe(book.title));
                values.put("source_name", safe(book.sourceName)); values.put("author", safe(book.author));
                values.put("relative_path", relative); values.put("file_size", book.fileSize);
                values.put("source_modified_at_ms", new File(book.path).lastModified());
                values.put("encoding", safeEncoding(book.encoding));
                if (db.update("books", values, "local_id=?", new String[]{id}) == 0) {
                    db.insertOrThrow("books", null, values);
                }

                long offset = book == preservedBook ? preservedOffset : book.offset;
                ContentValues progress = new ContentValues();
                progress.put("book_id", id); progress.put("offset_bytes", Math.max(0L, Math.min(offset, book.fileSize)));
                long readAt = book == preservedBook ? preservedBook.updatedAt : book.updatedAt;
                if (readAt > 0L) progress.put("read_at_ms", readAt); else progress.putNull("read_at_ms");
                db.insertWithOnConflict("reading_progress", null, progress, SQLiteDatabase.CONFLICT_REPLACE);

                ContentValues preference = new ContentValues();
                preference.put("book_id", id); preference.put("page_mode", book.pageMode ? 1 : 0);
                db.insertWithOnConflict("book_preferences", null, preference, SQLiteDatabase.CONFLICT_REPLACE);
            }
            try (Cursor cursor = db.rawQuery("select local_id from books", null)) {
                while (cursor.moveToNext()) {
                    String existing = cursor.getString(0);
                    if (!retained.contains(existing)) db.delete("books", "local_id=?", new String[]{existing});
                }
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    synchronized void deleteBook(Book book) {
        SQLiteDatabase db = getWritableDatabase();
        String relative;
        try { relative = managedRelativePath(new File(book.path)); }
        catch (Exception error) { throw new IllegalArgumentException("book path is outside managed storage", error); }
        db.beginTransaction();
        try {
            ContentValues pending = new ContentValues(); pending.put("relative_path", relative);
            pending.put("requested_at_ms", System.currentTimeMillis());
            db.insertWithOnConflict("pending_file_deletions", null, pending, SQLiteDatabase.CONFLICT_REPLACE);
            db.delete("books", "local_id=?", new String[]{String.valueOf(book.id)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        retryPendingFileDeletions();
    }

    synchronized List<Bookmark> loadBookmarks(long bookId) {
        ArrayList<Bookmark> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "select local_id,offset_bytes,created_at_ms from bookmarks where book_id=? order by offset_bytes",
                new String[]{String.valueOf(bookId)})) {
            while (cursor.moveToNext()) result.add(new Bookmark(Long.parseLong(cursor.getString(0)),
                    bookId, cursor.getLong(1), cursor.getLong(2)));
        }
        return result;
    }

    synchronized boolean addBookmark(long bookId, long offset) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("local_id", String.valueOf(now)); values.put("book_id", String.valueOf(bookId));
        values.put("offset_bytes", offset); values.putNull("excerpt"); values.put("created_at_ms", now);
        return getWritableDatabase().insertWithOnConflict("bookmarks", null, values,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    synchronized void deleteBookmarks(long bookId) {
        getWritableDatabase().delete("bookmarks", "book_id=?", new String[]{String.valueOf(bookId)});
    }

    synchronized void deleteBookmark(Bookmark bookmark) {
        getWritableDatabase().delete("bookmarks", "local_id=? and book_id=? and offset_bytes=?",
                new String[]{String.valueOf(bookmark.id), String.valueOf(bookmark.bookId), String.valueOf(bookmark.offset)});
    }

    synchronized TocDocument readToc(Book book) {
        SQLiteDatabase db = getReadableDatabase();
        long size, modified;
        String encoding;
        try (Cursor cursor = db.rawQuery("select source_file_size,source_modified_at_ms,encoding "
                + "from toc_documents where book_id=?", new String[]{String.valueOf(book.id)})) {
            if (!cursor.moveToFirst()) return null;
            size = cursor.getLong(0); modified = cursor.getLong(1); encoding = cursor.getString(2);
        }
        File source = new File(book.path);
        if (!source.exists() || size != source.length() || modified != source.lastModified()) return null;
        ArrayList<TocEntry> entries = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("select level,title,offset_bytes from toc_entries "
                + "where book_id=? order by ordinal", new String[]{String.valueOf(book.id)})) {
            while (cursor.moveToNext()) entries.add(new TocEntry(cursor.getInt(0), cursor.getString(1), cursor.getLong(2)));
        }
        return new TocDocument(size, modified, encoding == null ? book.encoding : encoding, entries);
    }

    synchronized void writeToc(Book book, TocDocument document) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues doc = new ContentValues();
            doc.put("book_id", String.valueOf(book.id)); doc.put("source_file_size", document.fileSize);
            doc.put("source_modified_at_ms", document.modifiedAt); doc.put("encoding", document.encoding);
            doc.put("generator_schema_version", 1);
            db.insertWithOnConflict("toc_documents", null, doc, SQLiteDatabase.CONFLICT_REPLACE);
            db.delete("toc_entries", "book_id=?", new String[]{String.valueOf(book.id)});
            for (int i = 0; i < document.entries.size(); i++) {
                TocEntry entry = document.entries.get(i);
                ContentValues row = new ContentValues();
                row.put("book_id", String.valueOf(book.id)); row.put("ordinal", i);
                row.putNull("entry_id"); row.put("title", entry.title);
                row.put("offset_bytes", entry.offset); row.put("level", entry.level);
                db.insertOrThrow("toc_entries", null, row);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void deleteToc(long bookId) {
        getWritableDatabase().delete("toc_documents", "book_id=?", new String[]{String.valueOf(bookId)});
    }

    synchronized String setting(String key, String fallback) {
        try (Cursor cursor = getReadableDatabase().rawQuery("select value from settings where key=?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : fallback;
        }
    }

    synchronized boolean hasSetting(String key) {
        try (Cursor cursor = getReadableDatabase().rawQuery("select 1 from settings where key=?", new String[]{key})) {
            return cursor.moveToFirst();
        }
    }

    synchronized void putSetting(String key, String value) {
        ContentValues row = new ContentValues(); row.put("key", key); row.put("value", value);
        getWritableDatabase().insertWithOnConflict("settings", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized String syncValue(String key, String fallback) {
        String column = syncColumn(key);
        try (Cursor cursor = getReadableDatabase().rawQuery("select " + column
                + " from sync_configuration where singleton_id=1", null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : fallback;
        }
    }

    synchronized boolean syncBoolean(String key, boolean fallback) {
        String value = syncValue(key, null);
        return value == null ? fallback : "1".equals(value);
    }

    synchronized boolean hasSyncValue(String key) {
        return syncValue(key, null) != null;
    }

    synchronized void putSyncValue(String key, String value) {
        ContentValues row = new ContentValues();
        if (value == null) row.putNull(syncColumn(key)); else if ("sync_started".equals(key))
            row.put(syncColumn(key), Boolean.parseBoolean(value) || "1".equals(value) ? 1 : 0);
        else row.put(syncColumn(key), value);
        getWritableDatabase().update("sync_configuration", row, "singleton_id=1", null);
    }

    synchronized void removeSyncValue(String key) {
        if ("sync_started".equals(key)) putSyncValue(key, "0");
        else putSyncValue(key, null);
    }

    synchronized BookHashCache.HashResult readHash(long bookId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("select book_hash,source_file_size,source_modified_at_ms "
                + "from book_hash_cache where book_id=?", new String[]{String.valueOf(bookId)})) {
            return cursor.moveToFirst() ? new BookHashCache.HashResult(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)) : null;
        }
    }

    synchronized void writeHash(long bookId, BookHashCache.HashResult value) {
        ContentValues row = new ContentValues(); row.put("book_id", String.valueOf(bookId));
        row.put("book_hash", value.bookHash); row.put("source_file_size", value.fileSize);
        row.put("source_modified_at_ms", value.modifiedAt);
        getWritableDatabase().insertWithOnConflict("book_hash_cache", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void removeHash(long bookId) {
        getWritableDatabase().delete("book_hash_cache", "book_id=?", new String[]{String.valueOf(bookId)});
    }

    synchronized List<RemoteProgressSnapshot> loadRemote(String account, String launchId) {
        ArrayList<RemoteProgressSnapshot> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("select book_hash,file_size,offset_bytes,read_at_ms,"
                + "version,source_device_id,source_device_name,source_platform,fetched_at_ms from remote_progress_cache "
                + "where account_scope=?", new String[]{account})) {
            while (cursor.moveToNext()) {
                long size = cursor.getLong(1), offset = cursor.getLong(2);
                result.add(new RemoteProgressSnapshot(cursor.getString(0), size, offset,
                        SyncRules.progress(offset, size), cursor.getLong(3), cursor.getString(4),
                        cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getLong(8), launchId));
            }
        }
        return result;
    }

    synchronized void replaceRemote(String account, List<RemoteProgressSnapshot> items, String launchId) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("remote_progress_cache", "account_scope=?", new String[]{account});
            for (RemoteProgressSnapshot item : items) putRemote(db, account, item, System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void putRemote(String account, RemoteProgressSnapshot item, String launchId) {
        putRemote(getWritableDatabase(), account, item, System.currentTimeMillis());
    }

    synchronized void clearRemote() { getWritableDatabase().delete("remote_progress_cache", null, null); }

    synchronized void removeRemote(String account, BookKey key) {
        getWritableDatabase().delete("remote_progress_cache", "account_scope=? and book_hash=? and file_size=?",
                new String[]{account, key.bookHash, String.valueOf(key.fileSize)});
    }

    private void putRemote(SQLiteDatabase db, String account, RemoteProgressSnapshot item, long fetchedAt) {
        ContentValues row = new ContentValues(); row.put("account_scope", account); row.put("book_hash", item.bookHash);
        row.put("file_size", item.fileSize); row.put("offset_bytes", item.offset); row.put("read_at_ms", item.readAtMs);
        row.put("version", item.version); row.put("source_device_id", item.sourceDeviceId);
        row.put("source_device_name", item.sourceDeviceName); row.put("source_platform", item.sourcePlatform);
        row.put("fetched_at_ms", fetchedAt);
        db.insertWithOnConflict("remote_progress_cache", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void migrateLegacy(SQLiteDatabase db, SharedPreferences legacy) throws Exception {
        String fingerprint = legacyFingerprint(legacy);
        try (Cursor cursor = db.rawQuery("select source_fingerprint from legacy_migrations where migration_id=?",
                new String[]{LEGACY_MIGRATION})) {
            if (cursor.moveToFirst()) {
                if (!fingerprint.equals(cursor.getString(0))) {
                    throw new IllegalStateException("legacy storage changed after migration");
                }
                return;
            }
        }
        db.beginTransaction();
        try {
            int imported = migrateBooks(db, legacy);
            imported += migrateBookmarks(db, legacy);
            migrateSettings(db, legacy);
            migrateSyncConfiguration(db, legacy);
            migrateHashes(db, legacy);
            migrateRemote(db, legacy);
            migrateTocFiles(db);
            ContentValues migration = new ContentValues(); migration.put("migration_id", LEGACY_MIGRATION);
            migration.put("source_fingerprint", fingerprint); migration.put("completed_at_ms", System.currentTimeMillis());
            migration.put("imported_rows", imported); db.insertOrThrow("legacy_migrations", null, migration);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private int migrateBooks(SQLiteDatabase db, SharedPreferences legacy) throws Exception {
        String raw = legacy.getString("books", "[]");
        JSONArray books = new JSONArray(raw);
        int count = 0;
        for (int i = 0; i < books.length(); i++) {
            JSONObject item = books.getJSONObject(i);
            long id = item.getLong("id");
            File file = new File(item.getString("path"));
            if (!file.exists()) throw new IllegalStateException("legacy book file is missing");
            String relative = managedRelativePath(file);
            ContentValues book = new ContentValues(); book.put("local_id", String.valueOf(id));
            book.put("title", item.optString("title")); book.put("source_name", item.optString("sourceName", ""));
            book.put("author", item.optString("author", "")); book.put("relative_path", relative);
            book.put("file_size", file.length()); book.put("source_modified_at_ms", file.lastModified());
            book.put("encoding", item.optString("encoding", "UTF-8")); db.insertOrThrow("books", null, book);
            long offset = item.optLong("offset", 0L);
            if (offset <= 0 && item.optDouble("progress", 0d) > 0) offset = (long) (file.length() * item.optDouble("progress"));
            ContentValues progress = new ContentValues(); progress.put("book_id", String.valueOf(id));
            progress.put("offset_bytes", Math.max(0, Math.min(offset, file.length())));
            long readAt = item.optLong("updatedAt", 0L); if (readAt > 0) progress.put("read_at_ms", readAt); else progress.putNull("read_at_ms");
            db.insertOrThrow("reading_progress", null, progress);
            ContentValues preference = new ContentValues(); preference.put("book_id", String.valueOf(id));
            preference.put("page_mode", item.optBoolean("pageMode", true) ? 1 : 0);
            db.insertOrThrow("book_preferences", null, preference); count++;
        }
        return count;
    }

    private int migrateBookmarks(SQLiteDatabase db, SharedPreferences legacy) throws Exception {
        JSONArray bookmarks = new JSONArray(legacy.getString("bookmarks", "[]"));
        int count = 0;
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject item = bookmarks.getJSONObject(i); String bookId = String.valueOf(item.getLong("bookId"));
            if (!bookExists(db, bookId)) throw new IllegalStateException("legacy bookmark has no book");
            ContentValues row = new ContentValues(); row.put("local_id", String.valueOf(item.getLong("id")));
            row.put("book_id", bookId); row.put("offset_bytes", item.getLong("offset")); row.putNull("excerpt");
            row.put("created_at_ms", item.getLong("createdAt")); db.insertOrThrow("bookmarks", null, row); count++;
        }
        return count;
    }

    private void migrateSettings(SQLiteDatabase db, SharedPreferences legacy) {
        String[] keys = {"auto_toc", "app_theme", "keep_screen_on", "auto_page_interval", "sensitivity",
                "font_family", "font_size", "line_spacing"};
        Map<String, ?> values = legacy.getAll();
        for (String key : keys) if (values.containsKey(key)) {
            ContentValues row = new ContentValues(); row.put("key", key); row.put("value", String.valueOf(values.get(key)));
            db.insertOrThrow("settings", null, row);
        }
    }

    private void migrateSyncConfiguration(SQLiteDatabase db, SharedPreferences legacy) {
        String[] keys = {"sync_configured_email", "sync_email", "sync_device_id", "sync_device_name",
                "sync_server_url", "sync_active_server_url", "sync_active_email", "sync_active_device_name"};
        ContentValues row = new ContentValues();
        for (String key : keys) if (legacy.contains(key)) row.put(syncColumn(key), legacy.getString(key, ""));
        row.put("has_started", legacy.getBoolean("sync_started", false) ? 1 : 0);
        db.update("sync_configuration", row, "singleton_id=1", null);
    }

    private void migrateHashes(SQLiteDatabase db, SharedPreferences legacy) {
        for (Map.Entry<String, ?> entry : legacy.getAll().entrySet()) {
            if (!entry.getKey().startsWith("sync_book_hash_") || !(entry.getValue() instanceof String)) continue;
            try {
                String bookId = entry.getKey().substring("sync_book_hash_".length());
                if (!bookExists(db, bookId)) continue;
                JSONObject object = new JSONObject((String) entry.getValue());
                String hash = object.getString("hash"); if (!hash.matches("[0-9a-f]{64}")) continue;
                ContentValues row = new ContentValues(); row.put("book_id", bookId); row.put("book_hash", hash);
                row.put("source_file_size", object.getLong("fileSize")); row.put("source_modified_at_ms", object.getLong("modifiedAt"));
                db.insertOrThrow("book_hash_cache", null, row);
            } catch (Exception ignored) { }
        }
    }

    private void migrateRemote(SQLiteDatabase db, SharedPreferences legacy) {
        String account = SyncTokenStore.normalizeEmail(legacy.getString("sync_remote_email", ""));
        if (account.isEmpty()) return;
        try {
            JSONArray items = new JSONArray(legacy.getString("sync_remote_items", "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject object = items.getJSONObject(i); String hash = object.getString("bookHash");
                long size = object.getLong("fileSize"), offset = object.getLong("offset");
                if (!hash.matches("[0-9a-f]{64}") || size <= 0 || offset < 0 || offset > size) continue;
                RemoteProgressSnapshot snapshot = new RemoteProgressSnapshot(hash, size, offset,
                        object.optDouble("progress", SyncRules.progress(offset, size)), object.getLong("readAtMs"),
                        object.getString("version"), object.getString("sourceDeviceId"),
                        object.getString("sourceDeviceName"), object.getString("sourcePlatform"),
                        object.optLong("fetchedAtMs", 0L), null);
                putRemote(db, account, snapshot, snapshot.fetchedAtMs);
            }
        } catch (Exception ignored) { }
    }

    private void migrateTocFiles(SQLiteDatabase db) {
        File directory = new File(context.getFilesDir(), "toc"); File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String bookId = null;
            try {
                String name = file.getName(); if (!name.endsWith(".json")) continue;
                bookId = name.substring(0, name.length() - 5); if (!bookExists(db, bookId)) continue;
                byte[] data = new byte[(int) file.length()];
                int total = 0;
                try (FileInputStream input = new FileInputStream(file)) {
                    while (total < data.length) {
                        int read = input.read(data, total, data.length - total);
                        if (read < 0) break;
                        total += read;
                    }
                }
                JSONObject root = new JSONObject(new String(data, 0, total, StandardCharsets.UTF_8));
                JSONArray entries = root.getJSONArray("entries");
                ContentValues doc = new ContentValues(); doc.put("book_id", bookId);
                doc.put("source_file_size", root.getLong("fileSize")); doc.put("source_modified_at_ms", root.getLong("modifiedAt"));
                doc.put("encoding", root.optString("encoding", "UTF-8")); doc.put("generator_schema_version", 1);
                db.insertOrThrow("toc_documents", null, doc);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject item = entries.getJSONObject(i); ContentValues row = new ContentValues();
                    row.put("book_id", bookId); row.put("ordinal", i); row.putNull("entry_id");
                    row.put("title", item.optString("title")); row.put("offset_bytes", item.optLong("offset"));
                    row.put("level", item.optInt("level", 1)); db.insertOrThrow("toc_entries", null, row);
                }
            } catch (Exception ignored) {
                if (bookId != null) db.delete("toc_documents", "book_id=?", new String[]{bookId});
            }
        }
    }

    private boolean bookExists(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.rawQuery("select 1 from books where local_id=?", new String[]{id})) { return cursor.moveToFirst(); }
    }

    private void retryPendingFileDeletions() {
        SQLiteDatabase db = getWritableDatabase();
        ArrayList<String> completed = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("select relative_path from pending_file_deletions", null)) {
            while (cursor.moveToNext()) {
                String relative = cursor.getString(0);
                File file = new File(context.getFilesDir(), relative);
                if (!file.exists() || file.delete()) completed.add(relative);
            }
        }
        for (String relative : completed) {
            db.delete("pending_file_deletions", "relative_path=?", new String[]{relative});
        }
    }

    private String managedRelativePath(File file) throws Exception {
        String root = booksDirectory.getCanonicalPath() + File.separator;
        String path = file.getCanonicalPath();
        if (!path.startsWith(root)) throw new IllegalArgumentException("book path is outside managed storage");
        return "books/" + path.substring(root.length());
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String safeEncoding(String value) { return value == null || value.isEmpty() ? "UTF-8" : value; }

    private static String syncColumn(String key) {
        switch (key) {
            case "sync_configured_email": return "configured_email";
            case "sync_email": return "authenticated_email";
            case "sync_device_id": return "device_id";
            case "sync_device_name": return "device_name";
            case "sync_server_url": return "server_url";
            case "sync_started": return "has_started";
            case "sync_active_server_url": return "credential_server_url";
            case "sync_active_email": return "active_email";
            case "sync_active_device_name": return "active_device_name";
            default: throw new IllegalArgumentException("unknown sync configuration key");
        }
    }

    private static String legacyFingerprint(SharedPreferences preferences) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ArrayList<String> keys = new ArrayList<>(preferences.getAll().keySet());
        Collections.sort(keys);
        for (String key : keys) {
            if (key.equals("sync_token_ciphertext") || key.equals("sync_token_iv")) continue;
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            Object value = preferences.getAll().get(key);
            digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
