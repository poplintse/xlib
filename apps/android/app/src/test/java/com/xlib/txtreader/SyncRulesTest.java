package com.xlib.txtreader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncRulesTest {
    private static final String HASH =
            "d8f2b4873f0b71b6fdfca1f55f65e17100f15b06cb74cb90cdabf936c18c4f2a";

    @Test
    public void promptThresholdIsStrictlyGreaterThanOneThousandthPercent() {
        assertFalse(SyncRules.exceedsPromptThreshold(0L, 1L, 100_000L));
        assertTrue(SyncRules.exceedsPromptThreshold(0L, 2L, 100_000L));
        assertFalse(SyncRules.exceedsPromptThreshold(50_000L, 49_999L, 100_000L));
    }

    @Test
    public void progressUsesByteOffsetAndClampsRange() {
        assertEquals(0.25d, SyncRules.progress(25L, 100L), 0d);
        assertEquals(0d, SyncRules.progress(-1L, 100L), 0d);
        assertEquals(1d, SyncRules.progress(101L, 100L), 0d);
        assertEquals(0d, SyncRules.progress(10L, 0L), 0d);
    }

    @Test
    public void readTimeIsMonotonicWhenClockDoesNotAdvance() {
        assertEquals(101L, SyncRules.monotonicReadAt(90L, 100L));
        assertEquals(150L, SyncRules.monotonicReadAt(150L, 100L));
    }

    @Test
    public void promptRequiresFreshNewerOtherDeviceState() {
        LocalProgressSnapshot local = new LocalProgressSnapshot(
                1L, HASH, 100_000L, 10L, 100L, 1L);
        RemoteProgressSnapshot remote = remote(HASH, 100_000L, 20L, 101L,
                "2", "other", "launch");
        assertTrue(SyncRules.shouldPrompt(local, remote, "current", "launch", null, false));
        assertFalse(SyncRules.shouldPrompt(local, remote, "other", "launch", null, false));
        assertFalse(SyncRules.shouldPrompt(local, remote, "current", "old-launch", null, false));
        assertFalse(SyncRules.shouldPrompt(local, remote, "current", "launch", "2", false));
        assertFalse(SyncRules.shouldPrompt(local, remote, "current", "launch", null, true));
    }

    @Test
    public void promptRejectsMismatchedBookAndNonNewerTime() {
        LocalProgressSnapshot local = new LocalProgressSnapshot(
                1L, HASH, 100_000L, 10L, 100L, 1L);
        assertFalse(SyncRules.shouldPrompt(local,
                remote(HASH, 100_001L, 20L, 101L, "2", "other", "launch"),
                "current", "launch", null, false));
        assertFalse(SyncRules.shouldPrompt(local,
                remote(HASH, 100_000L, 20L, 100L, "2", "other", "launch"),
                "current", "launch", null, false));
    }

    @Test
    public void healthBackoffCapsAtFiveMinutes() {
        assertEquals(30_000L, SyncRules.healthBackoffMs(0));
        assertEquals(60_000L, SyncRules.healthBackoffMs(1));
        assertEquals(120_000L, SyncRules.healthBackoffMs(2));
        assertEquals(300_000L, SyncRules.healthBackoffMs(3));
        assertEquals(300_000L, SyncRules.healthBackoffMs(20));
    }

    private RemoteProgressSnapshot remote(String hash, long size, long offset, long readAt,
                                           String version, String deviceId, String launchId) {
        return new RemoteProgressSnapshot(hash, size, offset,
                SyncRules.progress(offset, size), readAt, version, deviceId,
                "Other device", "android", 200L, launchId);
    }
}
