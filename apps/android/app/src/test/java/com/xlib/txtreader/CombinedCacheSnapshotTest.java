package com.xlib.txtreader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class CombinedCacheSnapshotTest {
    @Test
    public void validatesByteMapAndFileRangeTogether() {
        CombinedCacheSnapshot valid = snapshot(10L, "中文");
        CombinedCacheSnapshot wrongBytes = new CombinedCacheSnapshot(
                10L, "中文", 1, valid.offsetMap);

        assertTrue(valid.isWithinFile(100L));
        assertFalse(valid.isWithinFile(15L));
        assertFalse(wrongBytes.isWithinFile(100L));
    }

    private CombinedCacheSnapshot snapshot(long offset, String text) {
        ByteOffsetMap map = ByteOffsetMap.create(text, StandardCharsets.UTF_8);
        return new CombinedCacheSnapshot(offset, text, map.totalBytes(), map);
    }
}
