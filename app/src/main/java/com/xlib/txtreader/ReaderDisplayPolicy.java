package com.xlib.txtreader;

final class ReaderDisplayPolicy {
    private ReaderDisplayPolicy() {
    }

    static boolean isDarkTheme(int theme) {
        return ReaderSettingsOptions.normalizeTheme(theme)
                == ReaderSettingsOptions.THEME_DARK;
    }

    static int tapToleranceDp(int sensitivity) {
        int normalized = ReaderSettingsOptions.normalizeSensitivity(sensitivity);
        if (normalized == ReaderSettingsOptions.SENSITIVITY_HIGH) return 20;
        if (normalized == ReaderSettingsOptions.SENSITIVITY_LOW) return 12;
        return 16;
    }

    static int swipeThresholdDp(int sensitivity) {
        int normalized = ReaderSettingsOptions.normalizeSensitivity(sensitivity);
        if (normalized == ReaderSettingsOptions.SENSITIVITY_HIGH) return 48;
        if (normalized == ReaderSettingsOptions.SENSITIVITY_LOW) return 96;
        return 72;
    }

    static float lineSpacingExtraSp(float fontSize, float ratio) {
        float normalizedSize = ReaderSettingsOptions.normalizeFontSize(fontSize);
        float normalizedRatio = ReaderSettingsOptions.normalizeLineSpacing(ratio);
        return Math.max(2f, normalizedSize * normalizedRatio);
    }

    static String fontFamilyName(int family) {
        int normalized = ReaderSettingsOptions.normalizeFontFamily(family);
        if (normalized == ReaderSettingsOptions.FONT_SERIF
                || normalized == ReaderSettingsOptions.FONT_FANGSONG) return "serif";
        if (normalized == ReaderSettingsOptions.FONT_MONOSPACE) return "monospace";
        if (normalized == ReaderSettingsOptions.FONT_SANS_SERIF) return "sans-serif";
        return "sans";
    }

    static boolean fontUsesItalicStyle(int family) {
        return ReaderSettingsOptions.normalizeFontFamily(family)
                == ReaderSettingsOptions.FONT_FANGSONG;
    }
}
