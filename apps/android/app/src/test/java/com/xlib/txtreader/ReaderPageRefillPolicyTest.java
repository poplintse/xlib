package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReaderPageRefillPolicyTest {
    @Test
    public void normalPageTurnRequestsOneReplacementPage() {
        ReaderPageRefillPolicy.Decision decision = ReaderPageRefillPolicy.choose(
                8, 7, true, true, ReaderPageRefillPolicy.FORWARD);

        assertEquals(ReaderPageRefillPolicy.FORWARD, decision.direction);
        assertEquals(1, decision.pageCount);
    }

    @Test
    public void rapidTurnsCoalesceMissingPagesIntoOneBatch() {
        ReaderPageRefillPolicy.Decision decision = ReaderPageRefillPolicy.choose(
                8, 5, true, true, ReaderPageRefillPolicy.FORWARD);

        assertEquals(ReaderPageRefillPolicy.FORWARD, decision.direction);
        assertEquals(3, decision.pageCount);
    }

    @Test
    public void emergencySideOverridesNonEmergencyPreferredDirection() {
        ReaderPageRefillPolicy.Decision decision = ReaderPageRefillPolicy.choose(
                3, 7, true, true, ReaderPageRefillPolicy.FORWARD);

        assertEquals(ReaderPageRefillPolicy.BACKWARD, decision.direction);
        assertEquals(5, decision.pageCount);
    }
}
