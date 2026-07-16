package com.xlib.txtreader;

/** Chooses normal single-page refill or a coalesced/emergency page batch. */
final class ReaderPageRefillPolicy {
    static final int BACKWARD = -1;
    static final int NONE = 0;
    static final int FORWARD = 1;
    static final int TARGET_READY_PAGES = 8;
    static final int EMERGENCY_READY_PAGES = 4;

    private ReaderPageRefillPolicy() {
    }

    static Decision choose(int pagesBefore, int pagesAfter,
                           boolean canReadBackward, boolean canReadForward,
                           int preferredDirection) {
        boolean needsBackward = canReadBackward && pagesBefore < TARGET_READY_PAGES;
        boolean needsForward = canReadForward && pagesAfter < TARGET_READY_PAGES;
        if (!needsBackward && !needsForward) return null;

        boolean backwardEmergency = needsBackward && pagesBefore < EMERGENCY_READY_PAGES;
        boolean forwardEmergency = needsForward && pagesAfter < EMERGENCY_READY_PAGES;
        int direction;
        if (forwardEmergency && !backwardEmergency) {
            direction = FORWARD;
        } else if (backwardEmergency && !forwardEmergency) {
            direction = BACKWARD;
        } else if (preferredDirection == FORWARD && needsForward) {
            direction = FORWARD;
        } else if (preferredDirection == BACKWARD && needsBackward) {
            direction = BACKWARD;
        } else {
            direction = needsForward ? FORWARD : BACKWARD;
        }
        int readyPages = direction == FORWARD ? pagesAfter : pagesBefore;
        return new Decision(direction, Math.max(1, TARGET_READY_PAGES - readyPages));
    }

    static final class Decision {
        final int direction;
        final int pageCount;

        Decision(int direction, int pageCount) {
            this.direction = direction;
            this.pageCount = pageCount;
        }
    }
}
