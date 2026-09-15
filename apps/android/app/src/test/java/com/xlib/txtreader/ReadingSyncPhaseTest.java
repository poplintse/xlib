package com.xlib.txtreader;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReadingSyncPhaseTest {
    @Test public void positioningAndComparisonBothRequired() {
        ReadingSyncPhase phase = new ReadingSyncPhase();
        phase.complete();
        assertFalse(phase.canRead());
        assertFalse(phase.canUpload());
        phase.positioned = true;
        assertTrue(phase.canRead());
        assertTrue(phase.canUpload());
        phase.awaitingChoice = true;
        assertFalse(phase.canRead());
        assertFalse(phase.canUpload());
    }
    @Test public void offlineAllowsLocalReadingButRecoveryMustCompare() {
        ReadingSyncPhase phase = new ReadingSyncPhase();
        phase.positioned = true;
        phase.offline();
        assertTrue(phase.canRead());
        assertFalse(phase.canUpload());
        phase.requireComparison();
        assertFalse(phase.canUpload());
        phase.complete();
        assertTrue(phase.canUpload());
    }
    @Test public void deletionPauseSurvivesEveryComparisonUntilNewOpen() {
        ReadingSyncPhase phase = new ReadingSyncPhase();
        phase.positioned = true;
        phase.uploadPaused = true;
        phase.offline();
        phase.requireComparison();
        phase.complete();
        assertTrue(phase.canRead());
        assertFalse(phase.canUpload());
        ReadingSyncPhase reopened = new ReadingSyncPhase();
        reopened.positioned = true;
        reopened.complete();
        assertTrue(reopened.canUpload());
    }
}
