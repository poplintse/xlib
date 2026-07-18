package com.xlib.txtreader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class TocGenerator {
    private static final Pattern VOLUME = Pattern.compile(
            "^(?:正文\\s*)?(?:第[零〇一二两三四五六七八九十百千万0-9]+[卷部集篇]|卷[零〇一二两三四五六七八九十百千万0-9]+)(?:\\s|　|[:：].*)?.*$");
    private static final Pattern CHAPTER = Pattern.compile(
            "^(?:第[零〇一二两三四五六七八九十百千万0-9]+[章回]|Chapter\\s*[0-9]+)(?:\\s|　|[:：].*)?.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION = Pattern.compile(
            "^(?:第[零〇一二两三四五六七八九十百千万0-9]+[节幕]|Section\\s*[0-9]+)(?:\\s|　|[:：].*)?.*$",
            Pattern.CASE_INSENSITIVE);

    private TocGenerator() { }

    static TocDocument generate(File file, String encoding) throws Exception {
        List<RawEntry> raw = new ArrayList<>();
        String normalized = encoding == null
                ? "UTF-8" : encoding.toUpperCase(Locale.ROOT);
        boolean utf16Le = normalized.startsWith("UTF-16LE");
        boolean utf16Be = normalized.startsWith("UTF-16BE");
        Charset charset = Charset.forName(encoding == null || encoding.isEmpty() ? "UTF-8" : encoding);
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            long lineOffset = 0L;
            long position = 0L;
            int first;
            while ((first = input.read()) != -1) {
                position++;
                if (utf16Le || utf16Be) {
                    int second = input.read();
                    if (second == -1) {
                        line.write(first);
                        break;
                    }
                    position++;
                    boolean newline = utf16Le
                            ? first == 0x0A && second == 0x00
                            : first == 0x00 && second == 0x0A;
                    if (newline) {
                        inspect(line.toByteArray(), charset, lineOffset, raw);
                        line.reset();
                        lineOffset = position;
                    } else {
                        line.write(first);
                        line.write(second);
                    }
                } else if (first == 0x0A) {
                    inspect(line.toByteArray(), charset, lineOffset, raw);
                    line.reset();
                    lineOffset = position;
                } else {
                    line.write(first);
                }
            }
            if (line.size() > 0) inspect(line.toByteArray(), charset, lineOffset, raw);
        }

        boolean hasVolume = false;
        boolean hasChapter = false;
        for (RawEntry item : raw) {
            hasVolume |= item.rank == 1;
            hasChapter |= item.rank == 2;
        }
        List<TocEntry> entries = new ArrayList<>(raw.size());
        for (RawEntry item : raw) {
            int level;
            if (hasVolume) level = item.rank;
            else if (hasChapter) level = item.rank - 1;
            else level = 1;
            entries.add(new TocEntry(Math.max(1, Math.min(3, level)), item.title, item.offset));
        }
        return new TocDocument(file.length(), file.lastModified(), encoding, entries);
    }

    private static void inspect(byte[] bytes, Charset charset, long offset, List<RawEntry> output) {
        String value = new String(bytes, charset).replace("\uFEFF", "").trim();
        if (value.isEmpty() || value.length() > 80) return;
        int rank = VOLUME.matcher(value).matches() ? 1
                : CHAPTER.matcher(value).matches() ? 2
                : SECTION.matcher(value).matches() ? 3 : 0;
        if (rank != 0) output.add(new RawEntry(rank, value, offset));
    }

    private static final class RawEntry {
        final int rank;
        final String title;
        final long offset;

        RawEntry(int rank, String title, long offset) {
            this.rank = rank;
            this.title = title;
            this.offset = offset;
        }
    }
}
