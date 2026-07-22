package com.xlib.txtreader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProgressUploadTrackerTest {
    @Test
    public void failedRequestSequenceIsNotSelectedAgainByPeriodicCheck() {
        ProgressUploadTracker tracker = new ProgressUploadTracker();
        tracker.baseline(3L);
        assertTrue(tracker.observeForRequest(4L, false));
        // Request success/failure does not alter the tracker: observation happened at dispatch.
        assertFalse(tracker.observeForRequest(4L, false));
        assertEquals(4L, tracker.lastObservedSequence());
    }

    @Test
    public void newerPositionAndExplicitRecoveryUseLatestSnapshot() {
        ProgressUploadTracker tracker = new ProgressUploadTracker();
        tracker.baseline(3L);
        assertTrue(tracker.observeForRequest(4L, false));
        assertTrue(tracker.observeForRequest(5L, false));
        assertTrue(tracker.observeForRequest(5L, true));
    }
}
