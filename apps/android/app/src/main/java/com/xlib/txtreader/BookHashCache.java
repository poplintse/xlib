package com.xlib.txtreader;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

final class BookHashCache {
    private static final String PREFIX = "sync_book_hash_";
    private static final int BUFFER_SIZE = 128 * 1024;

    private final SharedPreferences preferences;

    BookHashCache(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    HashResult resolve(long localBookId, File file) throws Exception {
        long fileSize = file.length();
        long modifiedAt = file.lastModified();
        HashResult cached = read(localBookId);
        if (cached != null && cached.fileSize == fileSize && cached.modifiedAt == modifiedAt) {
            return cached;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hash.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        if (file.length() != fileSize || file.lastModified() != modifiedAt) {
            throw new IOException("book changed while hashing");
        }
        HashResult result = new HashResult(hash.toString(), fileSize, modifiedAt);
        write(localBookId, result);
        return result;
    }

    void remove(long localBookId) {
        preferences.edit().remove(PREFIX + localBookId).apply();
    }

    private HashResult read(long localBookId) {
        String raw = preferences.getString(PREFIX + localBookId, null);
        if (raw == null) return null;
        try {
            JSONObject object = new JSONObject(raw);
            String hash = object.getString("hash");
            if (!hash.matches("[0-9a-f]{64}")) return null;
            return new HashResult(hash, object.getLong("fileSize"),
                    object.getLong("modifiedAt"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void write(long localBookId, HashResult result) throws Exception {
        JSONObject object = new JSONObject();
        object.put("hash", result.bookHash);
        object.put("fileSize", result.fileSize);
        object.put("modifiedAt", result.modifiedAt);
        preferences.edit().putString(PREFIX + localBookId, object.toString()).apply();
    }

    static final class HashResult {
        final String bookHash;
        final long fileSize;
        final long modifiedAt;

        HashResult(String bookHash, long fileSize, long modifiedAt) {
            this.bookHash = bookHash;
            this.fileSize = fileSize;
            this.modifiedAt = modifiedAt;
        }
    }
}
