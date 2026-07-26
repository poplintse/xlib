package com.xlib.txtreader;

final class KeyboardAvoidancePolicy {
    private KeyboardAvoidancePolicy() {
    }

    static int scrollDelta(int fieldTop, int fieldBottom, int viewportTop, int viewportBottom,
                           int topPadding, int bottomPadding) {
        int safeTop = viewportTop + Math.max(0, topPadding);
        int safeBottom = viewportBottom - Math.max(0, bottomPadding);
        if (fieldBottom > safeBottom) {
            return fieldBottom - safeBottom;
        }
        if (fieldTop < safeTop) {
            return fieldTop - safeTop;
        }
        return 0;
    }
}
