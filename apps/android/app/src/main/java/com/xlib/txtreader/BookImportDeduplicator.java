package com.xlib.txtreader;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Owned by the serial library worker; includes accepted files awaiting UI publication. */
final class BookImportDeduplicator {
    private final List<File> accepted = new ArrayList<>();

    boolean isDuplicate(File candidate, List<File> library) throws IOException {
        Iterator<File> iterator = accepted.iterator();
        while (iterator.hasNext()) if (!iterator.next().isFile()) iterator.remove();
        for (File file : library) if (sameContent(candidate, file)) return true;
        for (File file : accepted) if (sameContent(candidate, file)) return true;
        return false;
    }

    void accepted(File file) { accepted.add(file); }

    static boolean sameContent(File candidate, File existing) throws IOException {
        if (!existing.isFile() || candidate.length() != existing.length()) return false;
        try (DataInputStream left = new DataInputStream(new FileInputStream(candidate));
             DataInputStream right = new DataInputStream(new FileInputStream(existing))) {
            byte[] a = new byte[64 * 1024];
            byte[] b = new byte[a.length];
            long remaining = candidate.length();
            while (remaining > 0) {
                int count = (int) Math.min(a.length, remaining);
                left.readFully(a, 0, count);
                right.readFully(b, 0, count);
                for (int i = 0; i < count; i++) if (a[i] != b[i]) return false;
                remaining -= count;
            }
            return left.read() == -1 && right.read() == -1;
        } catch (IOException error) {
            if (!existing.exists()) return false;
            throw error;
        }
    }

    static final class DuplicateBookException extends IOException { }
}
