package com.xlib.txtreader;

import java.io.File;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

final class ReaderCacheStore {
    private static final int READER_CACHE_MAGIC = 0x584C4932;
    private static final int MAX_READER_CACHE_TEXT_BYTES = 128 * 1024 * 8;
    private final File directory;
    private final Set<Long> deleted = new HashSet<>();
    ReaderCacheStore(File directory) { this.directory = directory; }
    ReaderCache read(Book book) {
        File source = new File(book.path);
        File cacheFile = readerCacheFile(book);
        if (!source.exists() || !cacheFile.exists()) return null;
        try (DataInputStream input = new DataInputStream(new FileInputStream(cacheFile))) {
            if (input.readInt() != READER_CACHE_MAGIC) return null;
            long fileSize = input.readLong();
            long modifiedAt = input.readLong();
            long windowStart = input.readLong();
            int bytesRead = input.readInt();
            int textLength = input.readInt();
            if (fileSize != source.length() || modifiedAt != source.lastModified()
                    || windowStart < 0 || windowStart > fileSize || bytesRead < 0
                    || bytesRead > fileSize - windowStart
                    || textLength < 0 || textLength > MAX_READER_CACHE_TEXT_BYTES) {
                return null;
            }
            byte[] textBytes = new byte[textLength];
            input.readFully(textBytes);
            return new ReaderCache(fileSize, windowStart, bytesRead,
                    new String(textBytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    synchronized void write(ReaderCacheWrite cache) {
        if (deleted.contains(cache.book.id)) return;
        byte[] textBytes = cache.text.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length > MAX_READER_CACHE_TEXT_BYTES) return;
        File directory = this.directory;
        if (!directory.exists() && !directory.mkdirs()) return;
        File target = readerCacheFile(cache.book);
        File temporary = new File(directory, target.getName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(temporary))) {
            output.writeInt(READER_CACHE_MAGIC);
            output.writeLong(cache.fileSize);
            output.writeLong(cache.modifiedAt);
            output.writeLong(cache.windowStart);
            output.writeInt(cache.bytesRead);
            output.writeInt(textBytes.length);
            output.write(textBytes);
            output.flush();
            if (target.exists() && !target.delete()) return;
            if (!temporary.renameTo(target)) {
                boolean ignored = temporary.delete();
            }
        } catch (Exception ignored) {
            boolean deleted = temporary.delete();
        }
    }

    private File readerCacheFile(Book book) {
        return new File(this.directory, book.id + ".window");
    }

    synchronized void delete(Book book) {
        deleted.add(book.id);
        File cacheFile = readerCacheFile(book);
        if (cacheFile.exists()) {
            boolean ignored = cacheFile.delete();
        }
    }

    static final class ReaderCache {
        final long fileSize;
        final long windowStart;
        final int bytesRead;
        final String text;

        ReaderCache(long fileSize, long windowStart, int bytesRead, String text) {
            this.fileSize = fileSize;
            this.windowStart = windowStart;
            this.bytesRead = bytesRead;
            this.text = text;
        }
    }

    static final class ReaderCacheWrite {
        final Book book;
        final long fileSize;
        final long modifiedAt;
        final long windowStart;
        final int bytesRead;
        final String text;

        ReaderCacheWrite(Book book, long fileSize, long modifiedAt, long windowStart,
                         int bytesRead, String text) {
            this.book = book;
            this.fileSize = fileSize;
            this.modifiedAt = modifiedAt;
            this.windowStart = windowStart;
            this.bytesRead = bytesRead;
            this.text = text;
        }
    }

}
