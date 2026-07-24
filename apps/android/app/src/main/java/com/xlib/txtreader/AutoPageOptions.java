package com.xlib.txtreader;

final class AutoPageOptions {
    static final int OFF = 0;
    static final int MIN_SECONDS = 10;
    static final int FIFTEEN_SECONDS = 15;
    static final int MAX_SECONDS = 30;
    static final int DEFAULT_SECONDS = FIFTEEN_SECONDS;

    private AutoPageOptions() {
    }

    static int normalize(int seconds) {
        return seconds >= MIN_SECONDS && seconds <= MAX_SECONDS ? seconds : OFF;
    }

    static int normalizePreference(int seconds) {
        int normalized = normalize(seconds);
        return normalized == OFF ? DEFAULT_SECONDS : normalized;
    }

}
