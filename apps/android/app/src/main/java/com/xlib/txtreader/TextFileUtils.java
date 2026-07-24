package com.xlib.txtreader;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class TextFileUtils {
    private TextFileUtils() {
    }

    static String detectEncoding(File file) {
        byte[] bytes = new byte[4096];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(bytes);
            if (hasPrefix(bytes, read, 0xEF, 0xBB, 0xBF)) return "UTF-8";
            if (hasPrefix(bytes, read, 0xFF, 0xFE)) return "UTF-16LE";
            if (hasPrefix(bytes, read, 0xFE, 0xFF)) return "UTF-16BE";
            boolean sampleStopsBeforeEof = read == bytes.length && input.read() != -1;
            return isValidUtf8Sample(bytes, Math.max(0, read), sampleStopsBeforeEof)
                    ? "UTF-8" : "GB18030";
        } catch (Exception ignored) {
            return "GB18030";
        }
    }

    private static boolean isValidUtf8Sample(byte[] bytes, int length,
                                             boolean sampleStopsBeforeEof) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = ByteBuffer.wrap(bytes, 0, length);
        CharBuffer decoded = CharBuffer.allocate(length);
        CoderResult result = decoder.decode(encoded, decoded, !sampleStopsBeforeEof);
        return result.isUnderflow();
    }

    static String formatFileSize(long bytes) {
        long value = Math.max(0L, bytes);
        if (value < 1024L) return value + " Byte";
        if (value < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f KB", value / 1024f);
        }
        if (value < 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f MB", value / (1024f * 1024f));
        }
        return String.format(Locale.getDefault(), "%.1f GB", value / (1024f * 1024f * 1024f));
    }

    private static boolean hasPrefix(byte[] bytes, int length, int... prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }
}
