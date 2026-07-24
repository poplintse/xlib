package com.xlib.txtreader;

final class ReaderRuntimePolicy {
    static final long NO_AUTO_PAGE_DELAY = -1L;

    private ReaderRuntimePolicy() {
    }

    static boolean shouldKeepScreenOn(boolean preferenceEnabled, boolean readerAttached) {
        return preferenceEnabled && readerAttached;
    }

    static long autoPageDelayMillis(int seconds, boolean activityResumed,
                                    boolean hasBook, boolean readerReady,
                                    boolean searchOpen, boolean temporarySearchReading) {
        int normalized = AutoPageOptions.normalize(seconds);
        if (normalized == AutoPageOptions.OFF || !activityResumed || !hasBook || !readerReady
                || searchOpen || temporarySearchReading) {
            return NO_AUTO_PAGE_DELAY;
        }
        return normalized * 1000L;
    }

    static boolean canDispatchAutoPage(boolean pageAnimating, boolean loadingChunk,
                                       boolean suppressProgressSave, boolean readerReady) {
        return !pageAnimating && !loadingChunk && !suppressProgressSave && readerReady;
    }
}
