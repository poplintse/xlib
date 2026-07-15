package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class CacheCombineStackTest {
    private static final int SEGMENT_BYTES = 100;

    @Test
    public void combinesTwoInitialSegmentsAroundAnchor() {
        CacheCombineStack stack = new CacheCombineStack(SEGMENT_BYTES, 0.10f);
        stack.reset(Arrays.asList(segment(0, "前", 100), segment(100, "后", 100)));

        CacheSegment combined = stack.combine(StandardCharsets.UTF_8);

        assertEquals(0, combined.offset);
        assertEquals(200, combined.bytesRead);
        assertEquals("前后", combined.text);
        assertEquals(2, stack.segmentCount());
    }

    @Test
    public void requestsRefillOnlyInsideTenPercentBoundary() {
        CacheCombineStack stack = new CacheCombineStack(SEGMENT_BYTES, 0.10f);
        stack.reset(Arrays.asList(segment(100, "A", 100), segment(200, "B", 100)));

        assertFalse(stack.needsBackwardRefill(121));
        assertTrue(stack.needsBackwardRefill(120));
        assertFalse(stack.needsForwardRefill(279, 1_000));
        assertTrue(stack.needsForwardRefill(280, 1_000));
    }

    @Test
    public void slidesForwardAndDiscardsOppositeEdge() {
        CacheCombineStack stack = new CacheCombineStack(SEGMENT_BYTES, 0.10f);
        stack.reset(Arrays.asList(segment(0, "A", 100), segment(100, "B", 100)));

        stack.appendForward(segment(200, "C", 100));

        CacheSegment combined = stack.combine(StandardCharsets.UTF_8);
        assertEquals(100, combined.offset);
        assertEquals("BC", combined.text);
        assertEquals(2, stack.segmentCount());
    }

    @Test
    public void slidesBackwardAndDiscardsOppositeEdge() {
        CacheCombineStack stack = new CacheCombineStack(SEGMENT_BYTES, 0.10f);
        stack.reset(Arrays.asList(segment(100, "B", 100), segment(200, "C", 100)));

        stack.appendBackward(segment(0, "A", 100));

        CacheSegment combined = stack.combine(StandardCharsets.UTF_8);
        assertEquals(0, combined.offset);
        assertEquals(200, combined.bytesRead);
        assertEquals("AB", combined.text);
    }

    @Test
    public void doesNotRefillPastFileEdges() {
        CacheCombineStack stack = new CacheCombineStack(SEGMENT_BYTES, 0.10f);
        stack.reset(Arrays.asList(segment(0, "A", 100), segment(100, "B", 100)));

        assertFalse(stack.needsBackwardRefill(0));
        assertFalse(stack.needsForwardRefill(200, 200));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonContiguousSegments() {
        CacheCombineStack stack = new CacheCombineStack(SEGMENT_BYTES, 0.10f);
        stack.reset(Arrays.asList(segment(0, "A", 100), segment(101, "B", 100)));
    }

    @Test
    public void rebuildsByteMapForCombinedUtf8Text() {
        CacheCombineStack stack = new CacheCombineStack(SEGMENT_BYTES, 0.10f);
        CacheSegment first = actualSegment(0, "第一段🙂");
        CacheSegment second = actualSegment(first.endOffset(), "第二段");
        stack.reset(Arrays.asList(first, second));

        CacheSegment combined = stack.combine(StandardCharsets.UTF_8);

        assertEquals(combined.bytesRead, combined.offsetMap.totalBytes());
        assertEquals("第一段🙂第二段", combined.text);
    }

    @Test
    public void restoresCombinedCacheAsTwoCharacterAlignedSegments() {
        CacheCombineStack stack = new CacheCombineStack(SEGMENT_BYTES, 0.10f);
        CacheSegment cached = actualSegment(500, "中".repeat(80));

        stack.resetFromCombined(cached, StandardCharsets.UTF_8);
        CacheSegment restored = stack.combine(StandardCharsets.UTF_8);

        assertEquals(2, stack.segmentCount());
        assertEquals(cached.offset, restored.offset);
        assertEquals(cached.bytesRead, restored.bytesRead);
        assertEquals(cached.text, restored.text);
        assertEquals(restored.bytesRead, restored.offsetMap.totalBytes());
    }

    private CacheSegment segment(long offset, String text, int bytes) {
        return new CacheSegment(offset, text, bytes,
                ByteOffsetMap.create(text, StandardCharsets.UTF_8));
    }

    private CacheSegment actualSegment(long offset, String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return new CacheSegment(offset, text, bytes,
                ByteOffsetMap.create(text, StandardCharsets.UTF_8));
    }
}
