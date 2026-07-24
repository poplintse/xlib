package com.xlib.txtreader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class LocalProgressStoreTest {
    private static final String HASH =
            "d8f2b4873f0b71b6fdfca1f55f65e17100f15b06cb74cb90cdabf936c18c4f2a";

    @Test
    public void identityDoesNotCreateAProgressChangeSequence() {
        LocalProgressStore store = new LocalProgressStore();
        LocalProgressSnapshot seeded = store.seed(7L, 100L, 10L, 50L);
        LocalProgressSnapshot identified = store.setIdentity(7L, HASH, 100L);
        assertEquals(seeded.localSequence, identified.localSequence);
    }

    @Test
    public void actualPositionChangeAdvancesSequenceOnlyOnce() {
        LocalProgressStore store = new LocalProgressStore();
        store.seed(7L, 100L, 10L, 50L);
        store.setIdentity(7L, HASH, 100L);
        LocalProgressSnapshot changed = store.updatePosition(7L, 100L, 20L, 51L);
        LocalProgressSnapshot unchanged = store.updatePosition(7L, 100L, 20L, 51L);
        assertEquals(1L, changed.localSequence);
        assertSame(changed, unchanged);
    }

    @Test
    public void offsetIsClampedToFileRange() {
        LocalProgressStore store = new LocalProgressStore();
        LocalProgressSnapshot snapshot = store.seed(7L, 100L, 120L, 50L);
        assertEquals(100L, snapshot.offset);
    }
}
