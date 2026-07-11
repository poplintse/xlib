package com.xlib.txtreader;

final class ReaderPosition {
    private ReaderPosition() {
    }

    static boolean cacheCoversOffset(long fileSize, long windowStart, int bytesRead,
                                     long targetOffset) {
        long windowEnd = windowEnd(fileSize, windowStart, bytesRead);
        return targetOffset >= windowStart
                && targetOffset <= windowEnd
                && (targetOffset < windowEnd || windowEnd == fileSize);
    }

    static long previewOffset(long fileSize, long windowStart, int bytesRead,
                              long targetOffset) {
        long windowEnd = windowEnd(fileSize, windowStart, bytesRead);
        return Math.max(windowStart, Math.min(targetOffset, windowEnd));
    }

    private static long windowEnd(long fileSize, long windowStart, int bytesRead) {
        return Math.min(Math.max(0L, fileSize),
                Math.max(0L, windowStart) + Math.max(0, bytesRead));
    }
}
