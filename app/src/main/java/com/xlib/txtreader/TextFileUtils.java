package com.xlib.txtreader;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, Math.max(0, read)));
                return "UTF-8";
            } catch (CharacterCodingException ignored) {
                return "GB18030";
            }
        } catch (Exception ignored) {
            return "GB18030";
        }
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
