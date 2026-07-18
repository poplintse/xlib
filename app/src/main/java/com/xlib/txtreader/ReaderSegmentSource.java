package com.xlib.txtreader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Character-safe disk access used by the cache-segment pipeline. */
final class ReaderSegmentSource {
    private static final int MAX_CHARACTER_BYTES = 4;
    private static final int SCAN_BUFFER_BYTES = 64 * 1024;

    private ReaderSegmentSource() {
    }

    static CacheSegment read(File file, long offset, int maxBytes, Charset charset)
            throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long safeOffset = Math.max(0L, Math.min(offset, input.length()));
            input.seek(safeOffset);
            int length = (int) Math.min(Math.max(0, maxBytes),
                    Math.max(0L, input.length() - safeOffset));
            byte[] bytes = new byte[length];
            int read = input.read(bytes);
            if (read <= 0) {
                return new CacheSegment(safeOffset, "", 0,
                        ByteOffsetMap.create("", charset));
            }
            return decodeCompleteSegment(safeOffset, bytes, read, charset);
        }
    }

    static long findReadableOffset(File file, long targetOffset, String encoding)
            throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            return findReadableOffset(input, input.length(), targetOffset, encoding);
        }
    }

    static List<Long> buildReadableIndex(File file, String encoding, int stepBytes)
            throws IOException {
        long size = file.length();
        long step = Math.max(1, stepBytes);
        ArrayList<Long> offsets = new ArrayList<>();
        offsets.add(0L);
        if (size <= 0L) return offsets;

        String normalized = normalizeEncoding(encoding);
        boolean utf16 = normalized.startsWith("UTF-16");
        boolean littleEndian = normalized.endsWith("LE");
        byte[] buffer = new byte[SCAN_BUFFER_BYTES];
        long position = 0L;
        long nextTarget = step;
        long lastBoundary = 0L;
        int previous = -1;
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                for (int index = 0; index < read; index++, position++) {
                    int value = buffer[index] & 0xFF;
                    if (utf16) {
                        if ((position & 1L) == 1L && previous >= 0) {
                            boolean newline = littleEndian
                                    ? previous == 0x0A && value == 0x00
                                    : previous == 0x00 && value == 0x0A;
                            if (newline) lastBoundary = position + 1L;
                        }
                        previous = value;
                    } else if (value == 0x0A) {
                        lastBoundary = position + 1L;
                    }
                    while (nextTarget <= position + 1L && nextTarget < size) {
                        if (offsets.get(offsets.size() - 1) < lastBoundary) {
                            offsets.add(lastBoundary);
                        }
                        nextTarget += step;
                    }
                }
            }
        }
        return offsets;
    }

    private static CacheSegment decodeCompleteSegment(long offset, byte[] bytes,
                                                      int read, Charset charset)
            throws IOException {
        int minimumLength = Math.max(0, read - MAX_CHARACTER_BYTES);
        CharacterCodingException lastError = null;
        for (int validLength = read; validLength >= minimumLength; validLength--) {
            if (validLength == 0 && read > 0) break;
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                String text = decoder.decode(ByteBuffer.wrap(bytes, 0, validLength)).toString();
                ByteOffsetMap map = ByteOffsetMap.create(text, charset);
                if (map.totalBytes() == validLength) {
                    return new CacheSegment(offset, text, validLength, map);
                }
            } catch (CharacterCodingException error) {
                lastError = error;
            }
        }
        throw new IOException("文本未从完整字符边界开始，无法建立可靠字节映射", lastError);
    }

    private static long findReadableOffset(RandomAccessFile input, long size,
                                           long targetOffset, String encoding)
            throws IOException {
        if (targetOffset <= 0L || size <= 0L) return 0L;
        long offset = Math.min(targetOffset, Math.max(0L, size - 1L));
        String normalized = normalizeEncoding(encoding);
        if (normalized.startsWith("UTF-16")) {
            long position = offset - (offset % 2L);
            boolean littleEndian = normalized.endsWith("LE");
            byte[] buffer = new byte[SCAN_BUFFER_BYTES];
            long scanEnd = position;
            long scanFloor = Math.max(0L, position - buffer.length);
            while (scanEnd > scanFloor) {
                long blockStart = Math.max(scanFloor, scanEnd - buffer.length);
                blockStart -= blockStart % 2L;
                int length = (int) (scanEnd - blockStart);
                input.seek(blockStart);
                input.readFully(buffer, 0, length);
                for (int index = length - 2; index >= 0; index -= 2) {
                    int first = buffer[index] & 0xFF;
                    int second = buffer[index + 1] & 0xFF;
                    boolean newline = littleEndian
                            ? first == 0x0A && second == 0x00
                            : first == 0x00 && second == 0x0A;
                    if (newline) return blockStart + index + 2L;
                }
                scanEnd = blockStart;
            }
            return position;
        }

        byte[] buffer = new byte[SCAN_BUFFER_BYTES];
        long scanEnd = offset;
        long scanFloor = Math.max(0L, offset - buffer.length);
        while (scanEnd > scanFloor) {
            long blockStart = Math.max(scanFloor, scanEnd - buffer.length);
            int length = (int) (scanEnd - blockStart);
            input.seek(blockStart);
            input.readFully(buffer, 0, length);
            for (int index = length - 1; index >= 0; index--) {
                if ((buffer[index] & 0xFF) == 0x0A) {
                    return blockStart + index + 1L;
                }
            }
            scanEnd = blockStart;
        }
        if (normalized.startsWith("UTF-8")) {
            long aligned = offset;
            while (aligned < size) {
                input.seek(aligned);
                int value = input.read();
                if (value < 0 || (value & 0xC0) != 0x80) break;
                aligned++;
            }
            return aligned;
        }
        if (normalized.startsWith("GB18030") || normalized.startsWith("GBK")) {
            return findDecodedBoundary(input, offset, Charset.forName(normalized));
        }
        return 0L;
    }

    private static long findDecodedBoundary(RandomAccessFile input, long targetOffset,
                                            Charset charset) throws IOException {
        input.seek(0L);
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        byte[] readBuffer = new byte[SCAN_BUFFER_BYTES];
        ByteBuffer encoded = ByteBuffer.allocate(SCAN_BUFFER_BYTES + MAX_CHARACTER_BYTES);
        encoded.limit(0);
        CharBuffer decoded = CharBuffer.allocate(SCAN_BUFFER_BYTES);
        long totalRead = 0L;
        long lastBoundary = 0L;
        while (totalRead < targetOffset) {
            encoded.compact();
            int requested = (int) Math.min(readBuffer.length, targetOffset - totalRead);
            int read = input.read(readBuffer, 0, requested);
            if (read < 0) break;
            encoded.put(readBuffer, 0, read);
            totalRead += read;
            encoded.flip();
            while (true) {
                CoderResult result = decoder.decode(encoded, decoded, false);
                if (result.isOverflow()) {
                    decoded.clear();
                    continue;
                }
                if (result.isError()) result.throwException();
                break;
            }
            lastBoundary = totalRead - encoded.remaining();
            decoded.clear();
        }
        return lastBoundary;
    }

    private static String normalizeEncoding(String encoding) {
        return (encoding == null ? "UTF-8" : encoding).toUpperCase(Locale.ROOT);
    }
}
