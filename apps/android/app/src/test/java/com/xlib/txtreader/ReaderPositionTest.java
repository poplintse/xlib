package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReaderPositionTest {
    @Test
    public void recognizesOffsetsCoveredByCacheWindow() {
        assertTrue(ReaderPosition.cacheCoversOffset(10_000, 1_000, 2_000, 1_000));
        assertTrue(ReaderPosition.cacheCoversOffset(10_000, 1_000, 2_000, 2_999));
        assertFalse(ReaderPosition.cacheCoversOffset(10_000, 1_000, 2_000, 3_000));
    }

    @Test
    public void allowsExactEndOnlyAtEndOfFile() {
        assertTrue(ReaderPosition.cacheCoversOffset(3_000, 1_000, 2_000, 3_000));
        assertFalse(ReaderPosition.cacheCoversOffset(10_000, 1_000, 2_000, 3_000));
    }

    @Test
    public void clampsPreviewWithoutChangingRequestedOffset() {
        assertEquals(1_000, ReaderPosition.previewOffset(10_000, 1_000, 2_000, 100));
        assertEquals(3_000, ReaderPosition.previewOffset(10_000, 1_000, 2_000, 8_000));
        assertEquals(2_500, ReaderPosition.previewOffset(10_000, 1_000, 2_000, 2_500));
    }
}
