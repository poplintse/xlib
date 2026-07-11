package com.xlib.txtreader;

final class AutoPageOptions {
    static final int OFF = 0;
    static final int FIVE_SECONDS = 5;
    static final int TEN_SECONDS = 10;
    static final int TWENTY_SECONDS = 20;

    private AutoPageOptions() {
    }

    static int normalize(int seconds) {
        return seconds == FIVE_SECONDS || seconds == TEN_SECONDS || seconds == TWENTY_SECONDS
                ? seconds
                : OFF;
    }

    static String shortLabel(int seconds) {
        int normalized = normalize(seconds);
        return normalized == OFF ? "OFF" : normalized + "s";
    }
}
