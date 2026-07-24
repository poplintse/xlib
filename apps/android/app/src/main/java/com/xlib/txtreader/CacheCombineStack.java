package com.xlib.txtreader;

import java.nio.charset.Charset;
import java.util.List;

/**
 * A continuous, double-ended moving reader cache. Disk segments are only refill inputs:
 * once accepted they are merged into one cache with one byte map, so consumers never see
 * segment boundaries.
 */
final class CacheCombineStack {
    private static final int WINDOW_SEGMENT_COUNT = 2;

    private final int segmentBytes;
    private final float refillRatio;
    private final Charset charset;
    private CombinedCacheSnapshot cache;

    CacheCombineStack(int segmentBytes, float refillRatio, Charset charset) {
        this.segmentBytes = Math.max(1, segmentBytes);
        this.refillRatio = Math.max(0f, Math.min(1f, refillRatio));
        this.charset = charset;
    }

    void reset(List<CacheSegment> segments) {
        cache = null;
        CacheSegment previous = null;
        for (CacheSegment segment : segments) {
            if (segment == null || segment.bytesRead <= 0) continue;
            requireConsistent(segment);
            if (previous != null && previous.endOffset() != segment.offset) {
                throw new IllegalArgumentException("Cache segments must be contiguous");
            }
            cache = cache == null ? fromSegment(segment) : merge(cache, segment);
            previous = segment;
        }
        trimStartToWindow();
    }

    void resetFromCombined(CombinedCacheSnapshot combined) {
        if (combined != null && combined.bytesRead > 0
                && !combined.hasConsistentByteMap()) {
            throw new IllegalArgumentException("Combined cache byte map is inconsistent");
        }
        cache = combined == null || combined.bytesRead <= 0 ? null : combined;
        trimStartToWindow();
    }

    boolean needsBackwardRefill(long anchorOffset) {
        if (isEmpty() || startOffset() <= 0L) return false;
        return Math.max(0L, anchorOffset - startOffset()) <= refillThresholdBytes();
    }

    boolean needsForwardRefill(long anchorOffset, long fileSize) {
        if (isEmpty() || endOffset() >= Math.max(0L, fileSize)) return false;
        return Math.max(0L, endOffset() - anchorOffset) <= refillThresholdBytes();
    }

    void appendBackward(CacheSegment segment) {
        if (segment == null || segment.bytesRead <= 0) return;
        requireConsistent(segment);
        if (!isEmpty() && segment.endOffset() != startOffset()) {
            throw new IllegalArgumentException("Backward cache segment is not contiguous");
        }
        cache = isEmpty() ? fromSegment(segment) : appendBackwardAndTrim(segment, cache);
    }

    void appendForward(CacheSegment segment) {
        if (segment == null || segment.bytesRead <= 0) return;
        requireConsistent(segment);
        if (!isEmpty() && segment.offset != endOffset()) {
            throw new IllegalArgumentException("Forward cache segment is not contiguous");
        }
        cache = isEmpty() ? fromSegment(segment) : appendForwardAndTrim(cache, segment);
    }

    CombinedCacheSnapshot snapshot() {
        if (isEmpty()) return new CombinedCacheSnapshot(0L, "", 0,
                ByteOffsetMap.create("", charset));
        return cache;
    }

    CacheCombineStack copy() {
        CacheCombineStack copy = new CacheCombineStack(segmentBytes, refillRatio, charset);
        copy.cache = isEmpty() ? null : cache;
        return copy;
    }

    long startOffset() {
        return isEmpty() ? 0L : cache.offset;
    }

    long endOffset() {
        return isEmpty() ? 0L : cache.endOffset();
    }

    int segmentCount() {
        if (isEmpty()) return 0;
        return Math.min(WINDOW_SEGMENT_COUNT,
                Math.max(1, (int) (((long) cache.bytesRead + segmentBytes - 1L)
                        / segmentBytes)));
    }

    private long refillThresholdBytes() {
        return Math.max(1L,
                Math.round(segmentBytes * WINDOW_SEGMENT_COUNT * refillRatio));
    }

    private boolean isEmpty() {
        return cache == null || cache.bytesRead <= 0;
    }

    private static void requireConsistent(CacheSegment segment) {
        if (!segment.hasConsistentByteMap()) {
            throw new IllegalArgumentException("Cache segment byte map is inconsistent");
        }
    }

    private CombinedCacheSnapshot merge(CombinedCacheSnapshot first, CacheSegment second) {
        if (first.endOffset() != second.offset) {
            throw new IllegalArgumentException("Cache segments must be contiguous");
        }
        String text = first.text + second.text;
        int bytes = first.bytesRead + second.bytesRead;
        return new CombinedCacheSnapshot(first.offset, text, bytes,
                ByteOffsetMap.create(text, charset));
    }

    private CombinedCacheSnapshot fromSegment(CacheSegment source) {
        return new CombinedCacheSnapshot(source.offset, source.text, source.bytesRead,
                source.offsetMap);
    }

    private CombinedCacheSnapshot appendForwardAndTrim(
            CombinedCacheSnapshot first, CacheSegment second) {
        String combinedText = first.text + second.text;
        int combinedBytes = first.bytesRead + second.bytesRead;
        if (combinedBytes <= maxWindowBytes()) {
            return snapshot(first.offset, combinedText, combinedBytes);
        }
        int excessBytes = combinedBytes - maxWindowBytes();
        int startChar = combinedCharIndexForByteOffset(
                first.text, first.offsetMap, first.bytesRead,
                second.offsetMap, excessBytes);
        int newline = combinedText.indexOf('\n', startChar);
        if (newline >= 0) {
            int newlineBytes = combinedByteOffsetForCharIndex(
                    first.text, first.offsetMap, first.bytesRead,
                    second.offsetMap, newline + 1);
            int maxLookAhead = Math.max(4096, segmentBytes / 4);
            if (newlineBytes - excessBytes <= maxLookAhead) startChar = newline + 1;
        }
        int removedBytes = combinedByteOffsetForCharIndex(
                first.text, first.offsetMap, first.bytesRead,
                second.offsetMap, startChar);
        if (removedBytes <= 0 || removedBytes >= combinedBytes) {
            return snapshot(first.offset, combinedText, combinedBytes);
        }
        String text = combinedText.substring(startChar);
        return snapshot(first.offset + removedBytes, text, combinedBytes - removedBytes);
    }

    private CombinedCacheSnapshot appendBackwardAndTrim(
            CacheSegment first, CombinedCacheSnapshot second) {
        String combinedText = first.text + second.text;
        int combinedBytes = first.bytesRead + second.bytesRead;
        if (combinedBytes <= maxWindowBytes()) {
            return snapshot(first.offset, combinedText, combinedBytes);
        }
        int endChar = combinedCharIndexForByteOffset(
                first.text, first.offsetMap, first.bytesRead,
                second.offsetMap, maxWindowBytes());
        int keptBytes = combinedByteOffsetForCharIndex(
                first.text, first.offsetMap, first.bytesRead,
                second.offsetMap, endChar);
        if (keptBytes <= 0 || keptBytes >= combinedBytes) {
            return snapshot(first.offset, combinedText, combinedBytes);
        }
        return snapshot(first.offset, combinedText.substring(0, endChar), keptBytes);
    }

    private CombinedCacheSnapshot snapshot(long offset, String text, int bytes) {
        return new CombinedCacheSnapshot(
                offset, text, bytes, ByteOffsetMap.create(text, charset));
    }

    private static int combinedCharIndexForByteOffset(
            String firstText, ByteOffsetMap firstMap, int firstBytes,
            ByteOffsetMap secondMap, long byteOffset) {
        if (byteOffset <= firstBytes) {
            return firstMap.charIndexForByteOffset(byteOffset);
        }
        return firstText.length()
                + secondMap.charIndexForByteOffset(byteOffset - firstBytes);
    }

    private static int combinedByteOffsetForCharIndex(
            String firstText, ByteOffsetMap firstMap, int firstBytes,
            ByteOffsetMap secondMap, int charIndex) {
        if (charIndex <= firstText.length()) {
            return firstMap.byteOffsetForCharIndex(charIndex);
        }
        return firstBytes
                + secondMap.byteOffsetForCharIndex(charIndex - firstText.length());
    }

    private void trimStartToWindow() {
        if (isEmpty() || cache.bytesRead <= maxWindowBytes()) return;
        int excessBytes = cache.bytesRead - maxWindowBytes();
        int startChar = cache.offsetMap.charIndexForByteOffset(excessBytes);
        int newline = cache.text.indexOf('\n', startChar);
        if (newline >= 0) {
            int newlineBytes = cache.offsetMap.byteOffsetForCharIndex(newline + 1);
            int maxLookAhead = Math.max(4096, segmentBytes / 4);
            if (newlineBytes - excessBytes <= maxLookAhead) startChar = newline + 1;
        }
        int removedBytes = cache.offsetMap.byteOffsetForCharIndex(startChar);
        if (removedBytes <= 0 || removedBytes >= cache.bytesRead) return;
        String text = cache.text.substring(startChar);
        cache = new CombinedCacheSnapshot(cache.offset + removedBytes, text,
                cache.bytesRead - removedBytes, ByteOffsetMap.create(text, charset));
    }

    private int maxWindowBytes() {
        return (int) Math.min(Integer.MAX_VALUE,
                (long) segmentBytes * WINDOW_SEGMENT_COUNT);
    }
}
