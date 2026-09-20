package com.xlib.txtreader;

import android.content.SharedPreferences;

/** Typed settings repository. SharedPreferences is retained only for legacy tests/fallback. */
final class ReadingPreferences {
    private final SharedPreferences preferences;
    private final LocalDatabase database;
    ReadingPreferences(SharedPreferences preferences) { this.preferences = preferences; this.database = null; }
    ReadingPreferences(LocalDatabase database) { this.preferences = null; this.database = database; }
    boolean autoToc() { return getBoolean(KEY_AUTO_TOC, false); }
    void setAutoToc(boolean enabled) { put(KEY_AUTO_TOC, enabled); }
    void migrateSystemTheme(boolean night) {
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
        return database == null ? preferences.getBoolean(key, fallback)
                : Boolean.parseBoolean(database.setting(key, String.valueOf(fallback)));
    }
    private int getInt(String key, int fallback) {
        if (database == null) return preferences.getInt(key, fallback);
        try { return Integer.parseInt(database.setting(key, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private float getFloat(String key, float fallback) {
        if (database == null) return preferences.getFloat(key, fallback);
        try { return Float.parseFloat(database.setting(key, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private void put(String key, boolean value) {
        if (database == null) preferences.edit().putBoolean(key, value).apply();
        else database.putSetting(key, String.valueOf(value));
    }
    private void put(String key, int value) {
        if (database == null) preferences.edit().putInt(key, value).apply();
        else database.putSetting(key, String.valueOf(value));
    }
    private void put(String key, float value) {
        if (database == null) preferences.edit().putFloat(key, value).apply();
        else database.putSetting(key, String.valueOf(value));
    }

}
