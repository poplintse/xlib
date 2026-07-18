package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class CacheCombineStackTest {
    private static final int SEGMENT_BYTES = 100;

    @Test
    public void mergesInitialSegmentsIntoOneContinuousCache() {
        CacheSegment first = actualSegment(0, "第一段🙂");
        CacheSegment second = actualSegment(first.endOffset(), "第二段");
        CacheCombineStack stack = newStack();

        stack.reset(Arrays.asList(first, second));
        CombinedCacheSnapshot combined = stack.snapshot();

        assertEquals(0, combined.offset);
        assertEquals("第一段🙂第二段", combined.text);
        assertEquals(combined.bytesRead, combined.offsetMap.totalBytes());
        assertEquals(1, stack.segmentCount());
    }

    @Test
    public void requestsRefillOnlyInsideTenPercentOfTwoSegmentWindow() {
        CacheCombineStack stack = newStack();
        stack.reset(Arrays.asList(asciiSegment(100, 'A'), asciiSegment(200, 'B')));

        assertFalse(stack.needsBackwardRefill(121));
        assertTrue(stack.needsBackwardRefill(120));
        assertFalse(stack.needsForwardRefill(279, 1_000));
        assertTrue(stack.needsForwardRefill(280, 1_000));
    }

    @Test
    public void mergesForwardRefillBeforeDiscardingFarEdge() {
        CacheCombineStack stack = newStack();
        stack.reset(Arrays.asList(asciiSegment(0, 'A'), asciiSegment(100, 'B')));

        stack.appendForward(asciiSegment(200, 'C'));
        CombinedCacheSnapshot combined = stack.snapshot();

        assertEquals(100, combined.offset);
        assertEquals("B".repeat(100) + "C".repeat(100), combined.text);
        assertEquals(200, combined.bytesRead);
        assertEquals(combined.bytesRead, combined.offsetMap.totalBytes());
        assertEquals(2, stack.segmentCount());
    }

    @Test
    public void mergesBackwardRefillBeforeDiscardingFarEdge() {
        CacheCombineStack stack = newStack();
        stack.reset(Arrays.asList(asciiSegment(100, 'B'), asciiSegment(200, 'C')));

        stack.appendBackward(asciiSegment(0, 'A'));
        CombinedCacheSnapshot combined = stack.snapshot();

        assertEquals(0, combined.offset);
        assertEquals("A".repeat(100) + "B".repeat(100), combined.text);
        assertEquals(200, combined.bytesRead);
        assertEquals(combined.bytesRead, combined.offsetMap.totalBytes());
    }

    @Test
    public void forwardTrimCanMoveToNearbyParagraphBoundaryWithoutCreatingASeam() {
        CacheCombineStack stack = new CacheCombineStack(
                20, 0.10f, StandardCharsets.UTF_8);
        CacheSegment first = actualSegment(0, "a".repeat(20));
        CacheSegment second = actualSegment(first.endOffset(), "bbbb\n" + "b".repeat(15));
        CacheSegment third = actualSegment(second.endOffset(), "c".repeat(20));
        stack.reset(Arrays.asList(first, second));

        stack.appendForward(third);
        CombinedCacheSnapshot combined = stack.snapshot();

        assertEquals(25, combined.offset);
        assertEquals("b".repeat(15) + "c".repeat(20), combined.text);
        assertEquals(combined.bytesRead, combined.offsetMap.totalBytes());
    }

    @Test
    public void backendCopyCanSlideWithoutMutatingPublishedCache() {
        CacheCombineStack published = newStack();
        published.reset(Arrays.asList(asciiSegment(0, 'A'), asciiSegment(100, 'B')));
        CacheCombineStack prepared = published.copy();

        assertSame(published.snapshot(), prepared.snapshot());
        assertSame(published.snapshot().offsetMap, prepared.snapshot().offsetMap);

        prepared.appendForward(asciiSegment(200, 'C'));

        assertEquals(0, published.startOffset());
        assertEquals("A".repeat(100) + "B".repeat(100), published.snapshot().text);
        assertEquals(100, prepared.startOffset());
        assertEquals("B".repeat(100) + "C".repeat(100), prepared.snapshot().text);
    }

    @Test
    public void doesNotRefillPastFileEdges() {
        CacheCombineStack stack = newStack();
        stack.reset(Arrays.asList(asciiSegment(0, 'A'), asciiSegment(100, 'B')));

        assertFalse(stack.needsBackwardRefill(0));
        assertFalse(stack.needsForwardRefill(200, 200));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonContiguousSegments() {
        CacheCombineStack stack = newStack();
        stack.reset(Arrays.asList(asciiSegment(0, 'A'), asciiSegment(101, 'B')));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSegmentWithInconsistentByteMap() {
        CacheCombineStack stack = newStack();
        ByteOffsetMap map = ByteOffsetMap.create("abc", StandardCharsets.UTF_8);

        stack.reset(Arrays.asList(new CacheSegment(0L, "abc", 2, map)));
    }

    @Test
    public void restoresAlreadyCombinedCacheWithoutReintroducingSegments() {
        CacheCombineStack stack = newStack();
        CombinedCacheSnapshot cached = combinedCache(500, "中".repeat(60));

        stack.resetFromCombined(cached);
        CombinedCacheSnapshot restored = stack.snapshot();

        assertEquals(cached.offset, restored.offset);
        assertEquals(cached.bytesRead, restored.bytesRead);
        assertEquals(cached.text, restored.text);
        assertSame(cached, restored);
        assertSame(cached.offsetMap, restored.offsetMap);
        assertEquals(restored.bytesRead, restored.offsetMap.totalBytes());
        assertEquals(2, stack.segmentCount());
    }

    private CacheCombineStack newStack() {
        return new CacheCombineStack(SEGMENT_BYTES, 0.10f, StandardCharsets.UTF_8);
    }

    private CacheSegment asciiSegment(long offset, char value) {
        return actualSegment(offset, String.valueOf(value).repeat(SEGMENT_BYTES));
    }

    private CacheSegment actualSegment(long offset, String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return new CacheSegment(offset, text, bytes,
                ByteOffsetMap.create(text, StandardCharsets.UTF_8));
    }

    private CombinedCacheSnapshot combinedCache(long offset, String text) {
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return new CombinedCacheSnapshot(offset, text, bytes,
                ByteOffsetMap.create(text, StandardCharsets.UTF_8));
    }
}
