package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReaderRuntimePolicyTest {
    @Test
    public void keepScreenOnRequiresBothPreferenceAndVisibleReader() {
        assertFalse(ReaderRuntimePolicy.shouldKeepScreenOn(false, false));
        assertFalse(ReaderRuntimePolicy.shouldKeepScreenOn(true, false));
        assertFalse(ReaderRuntimePolicy.shouldKeepScreenOn(false, true));
        assertTrue(ReaderRuntimePolicy.shouldKeepScreenOn(true, true));
    }

    @Test
    public void configuredAutoPageSecondsBecomeExactHandlerDelays() {
        assertEquals(10_000L, delayFor(10));
        assertEquals(15_000L, delayFor(15));
        assertEquals(30_000L, delayFor(30));
    }

    @Test
    public void autoPageDoesNotScheduleOutsideActiveReader() {
        long none = ReaderRuntimePolicy.NO_AUTO_PAGE_DELAY;
        assertEquals(none, ReaderRuntimePolicy.autoPageDelayMillis(
                AutoPageOptions.OFF, true, true, true, false, false));
        assertEquals(none, ReaderRuntimePolicy.autoPageDelayMillis(
                15, false, true, true, false, false));
        assertEquals(none, ReaderRuntimePolicy.autoPageDelayMillis(
                15, true, false, true, false, false));
        assertEquals(none, ReaderRuntimePolicy.autoPageDelayMillis(
                15, true, true, false, false, false));
        assertEquals(none, ReaderRuntimePolicy.autoPageDelayMillis(
                15, true, true, true, true, false));
        assertEquals(none, ReaderRuntimePolicy.autoPageDelayMillis(
                15, true, true, true, false, true));
    }

    @Test
    public void autoPageDispatchesOnlyWhenReaderIsIdle() {
        assertTrue(ReaderRuntimePolicy.canDispatchAutoPage(false, false, false, true));
        assertFalse(ReaderRuntimePolicy.canDispatchAutoPage(true, false, false, true));
        assertFalse(ReaderRuntimePolicy.canDispatchAutoPage(false, true, false, true));
        assertFalse(ReaderRuntimePolicy.canDispatchAutoPage(false, false, true, true));
        assertFalse(ReaderRuntimePolicy.canDispatchAutoPage(false, false, false, false));
    }

    private long delayFor(int seconds) {
        return ReaderRuntimePolicy.autoPageDelayMillis(
                seconds, true, true, true, false, false);
    }
}
