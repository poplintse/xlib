package com.xlib.txtreader;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A fixed-size, double-ended moving reader window. Disk I/O is deliberately kept outside
 * this class: callers prepend/append a ready segment, and the opposite edge is discarded.
 */
final class CacheCombineStack {
    private static final int WINDOW_SEGMENT_COUNT = 2;

    private final int segmentBytes;
    private final float refillRatio;
    private final Deque<CacheSegment> segments = new ArrayDeque<>();

    CacheCombineStack(int segmentBytes, float refillRatio) {
        this.segmentBytes = Math.max(1, segmentBytes);
        this.refillRatio = Math.max(0f, Math.min(1f, refillRatio));
    }

    void reset(List<CacheSegment> segments) {
        this.segments.clear();
        CacheSegment previous = null;
        for (CacheSegment segment : segments) {
            if (segment == null || segment.bytesRead <= 0) continue;
            if (previous != null && previous.endOffset() != segment.offset) {
                throw new IllegalArgumentException("Cache segments must be contiguous");
            }
            this.segments.addLast(segment);
            previous = segment;
        }
        while (this.segments.size() > WINDOW_SEGMENT_COUNT) this.segments.removeFirst();
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
        if (!isEmpty() && segment.endOffset() != startOffset()) {
            throw new IllegalArgumentException("Backward cache segment is not contiguous");
        }
        segments.addFirst(segment);
        while (segments.size() > WINDOW_SEGMENT_COUNT) segments.removeLast();
    }

    void appendForward(CacheSegment segment) {
        if (segment == null || segment.bytesRead <= 0) return;
        if (!isEmpty() && segment.offset != endOffset()) {
            throw new IllegalArgumentException("Forward cache segment is not contiguous");
        }
        segments.addLast(segment);
        while (segments.size() > WINDOW_SEGMENT_COUNT) segments.removeFirst();
    }

    CacheSegment combine(Charset charset) {
        if (isEmpty()) return new CacheSegment(0L, "", 0,
                ByteOffsetMap.create("", charset));
        StringBuilder text = new StringBuilder();
        int bytes = 0;
        for (CacheSegment segment : segments) {
            text.append(segment.text);
            bytes += segment.bytesRead;
        }
        String combined = text.toString();
        return new CacheSegment(startOffset(), combined, bytes,
                ByteOffsetMap.create(combined, charset));
    }

    long startOffset() {
        return segments.isEmpty() ? 0L : segments.peekFirst().offset;
    }

    long endOffset() {
        return segments.isEmpty() ? 0L : segments.peekLast().endOffset();
    }

    int segmentCount() {
        return segments.size();
    }

    private long refillThresholdBytes() {
        return Math.max(1L,
                Math.round(segmentBytes * WINDOW_SEGMENT_COUNT * refillRatio));
    }

    private boolean isEmpty() {
        return segments.isEmpty();
    }
}
