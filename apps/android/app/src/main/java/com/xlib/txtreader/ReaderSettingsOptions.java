package com.xlib.txtreader;

final class ReaderSettingsOptions {
    static final int LEGACY_THEME_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;

    static final int SENSITIVITY_HIGH = 0;
    static final int SENSITIVITY_STANDARD = 1;
    static final int SENSITIVITY_LOW = 2;

    static final int FONT_SYSTEM = 0;
    static final int FONT_SERIF = 1;
    static final int FONT_MONOSPACE = 2;
    static final int FONT_SANS_SERIF = 3;
    static final int FONT_FANGSONG = 4;

    static final float MIN_FONT_SIZE = 14f;
    static final float MAX_FONT_SIZE = 34f;
    static final float DEFAULT_FONT_SIZE = 20f;
    static final float MIN_LINE_SPACING = 0.10f;
    static final float MAX_LINE_SPACING = 0.40f;
    static final float DEFAULT_LINE_SPACING = 0.18f;

    private ReaderSettingsOptions() {
    }

    static int normalizeTheme(int theme) {
        return theme == THEME_DARK ? THEME_DARK : THEME_LIGHT;
    }

    static int normalizeSensitivity(int sensitivity) {
        return sensitivity >= SENSITIVITY_HIGH && sensitivity <= SENSITIVITY_LOW
                ? sensitivity : SENSITIVITY_STANDARD;
    }

    static int normalizeFontFamily(int family) {
        return family >= FONT_SYSTEM && family <= FONT_FANGSONG ? family : FONT_SYSTEM;
    }

    static float normalizeFontSize(float size) {
        if (!Float.isFinite(size)) return DEFAULT_FONT_SIZE;
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
    }

    static float normalizeLineSpacing(float ratio) {
        if (!Float.isFinite(ratio)) return DEFAULT_LINE_SPACING;
        return Math.max(MIN_LINE_SPACING, Math.min(MAX_LINE_SPACING, ratio));
    }
}
