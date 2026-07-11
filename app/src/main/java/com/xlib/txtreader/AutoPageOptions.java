package com.xlib.txtreader;

final class AutoPageOptions {
    static final int OFF = 0;
    static final int TEN_SECONDS = 10;
    static final int FIFTEEN_SECONDS = 15;
    static final int TWENTY_SECONDS = 20;
    static final int DEFAULT_SECONDS = FIFTEEN_SECONDS;

    private AutoPageOptions() {
    }

    static int normalize(int seconds) {
        return seconds == TEN_SECONDS || seconds == FIFTEEN_SECONDS
                || seconds == TWENTY_SECONDS
                ? seconds
                : OFF;
    }

}
