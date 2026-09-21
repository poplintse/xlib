package com.xlib.txtreader;

/** Typed settings repository backed by the app's single local database. */
final class ReadingPreferences {
    private final LocalDatabase database;
    ReadingPreferences(LocalDatabase database) { this.database = database; }
    boolean autoToc() { return getBoolean(KEY_AUTO_TOC, false); }
    void setAutoToc(boolean enabled) { put(KEY_AUTO_TOC, enabled); }
    void normalizeSystemTheme(boolean night) {
        if (getInt(KEY_APP_THEME, ReaderSettingsOptions.THEME_LIGHT)
                == ReaderSettingsOptions.LEGACY_THEME_SYSTEM) {
            setAppTheme(night ? ReaderSettingsOptions.THEME_DARK : ReaderSettingsOptions.THEME_LIGHT);
        }
    }
    static final String KEY_AUTO_TOC = "auto_toc";
    static final String KEY_APP_THEME = "app_theme";
    static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    static final String KEY_AUTO_PAGE_INTERVAL = "auto_page_interval";
    static final String KEY_SENSITIVITY = "sensitivity";
    static final String KEY_FONT_FAMILY = "font_family";
    static final String KEY_FONT_SIZE = "font_size";
    static final String KEY_LINE_SPACING = "line_spacing";
    int appTheme() {
        return ReaderSettingsOptions.normalizeTheme(
                getInt(KEY_APP_THEME, ReaderSettingsOptions.THEME_LIGHT));
    }

    void setAppTheme(int theme) {
        put(KEY_APP_THEME, ReaderSettingsOptions.normalizeTheme(theme));
    }

    boolean readingKeepScreenOn() {
        return getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    void setReadingKeepScreenOn(boolean enabled) {
        put(KEY_KEEP_SCREEN_ON, enabled);
    }

    int readingAutoPageInterval() {
        return AutoPageOptions.normalizePreference(getInt(KEY_AUTO_PAGE_INTERVAL,
                AutoPageOptions.DEFAULT_SECONDS));
    }

    void setReadingAutoPageInterval(int seconds) {
        put(KEY_AUTO_PAGE_INTERVAL, AutoPageOptions.normalizePreference(seconds));
    }

    int readingSensitivity() {
        return ReaderSettingsOptions.normalizeSensitivity(
                getInt(KEY_SENSITIVITY, ReaderSettingsOptions.SENSITIVITY_STANDARD));
    }

    void setReadingSensitivity(int sensitivity) {
        put(KEY_SENSITIVITY, ReaderSettingsOptions.normalizeSensitivity(sensitivity));
    }

    int readingFontFamily() {
        return ReaderSettingsOptions.normalizeFontFamily(
                getInt(KEY_FONT_FAMILY, ReaderSettingsOptions.FONT_SYSTEM));
    }

    void setReadingFontFamily(int family) {
        put(KEY_FONT_FAMILY, ReaderSettingsOptions.normalizeFontFamily(family));
    }

    float readingFontSize() {
        return ReaderSettingsOptions.normalizeFontSize(
                getFloat(KEY_FONT_SIZE, ReaderSettingsOptions.DEFAULT_FONT_SIZE));
    }

    void setReadingFontSize(float size) {
        put(KEY_FONT_SIZE, ReaderSettingsOptions.normalizeFontSize(size));
    }

    float readingLineSpacingRatio() {
        return ReaderSettingsOptions.normalizeLineSpacing(
                getFloat(KEY_LINE_SPACING,
                        ReaderSettingsOptions.DEFAULT_LINE_SPACING));
    }

    void setReadingLineSpacingRatio(float ratio) {
        put(KEY_LINE_SPACING, ReaderSettingsOptions.normalizeLineSpacing(ratio));
    }

    private boolean getBoolean(String key, boolean fallback) {
        return Boolean.parseBoolean(database.setting(key, String.valueOf(fallback)));
    }
    private int getInt(String key, int fallback) {
        try { return Integer.parseInt(database.setting(key, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private float getFloat(String key, float fallback) {
        try { return Float.parseFloat(database.setting(key, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private void put(String key, boolean value) {
        database.putSetting(key, String.valueOf(value));
    }
    private void put(String key, int value) {
        database.putSetting(key, String.valueOf(value));
    }
    private void put(String key, float value) {
        database.putSetting(key, String.valueOf(value));
    }

}
