package com.xlib.txtreader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

final class BookHashCache {
    private static final int BUFFER_SIZE = 128 * 1024;

    private final LocalDatabase database;

    BookHashCache(LocalDatabase database) { this.database = database; }

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

    void remove(long localBookId) { database.removeHash(localBookId); }

    private HashResult read(long localBookId) { return database.readHash(localBookId); }

    private void write(long localBookId, HashResult result) { database.writeHash(localBookId, result); }

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
