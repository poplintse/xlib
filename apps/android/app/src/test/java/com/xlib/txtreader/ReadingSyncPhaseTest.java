package com.xlib.txtreader;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReadingSyncPhaseTest {
    @Test public void positioningAndComparisonBothRequired() {
        ReadingSyncPhase phase = new ReadingSyncPhase();
        phase.complete();
        assertFalse(phase.canRead());
        assertFalse(phase.canUpload());
        phase.positionReady();
        assertTrue(phase.canRead());
        assertTrue(phase.canUpload());
        phase.awaitChoice();
        assertFalse(phase.canRead());
        assertFalse(phase.canUpload());
    }
    @Test public void offlineAllowsLocalReadingButRecoveryMustCompare() {
        ReadingSyncPhase phase = new ReadingSyncPhase();
        phase.positionReady();
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
        phase.positionReady();
        phase.pauseUpload(true);
        phase.offline();
        phase.requireComparison();
        phase.complete();
        assertTrue(phase.canRead());
        assertFalse(phase.canUpload());
        ReadingSyncPhase reopened = new ReadingSyncPhase();
        reopened.positionReady();
        reopened.complete();
        assertTrue(reopened.canUpload());
    }
}
