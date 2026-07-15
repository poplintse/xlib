package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReaderSettingsOptionsTest {
    @Test
    public void validatesThemeSensitivityAndFontChoices() {
        assertEquals(ReaderSettingsOptions.THEME_LIGHT,
                ReaderSettingsOptions.normalizeTheme(-1));
        assertEquals(ReaderSettingsOptions.THEME_LIGHT,
                ReaderSettingsOptions.normalizeTheme(
                        ReaderSettingsOptions.LEGACY_THEME_SYSTEM));
        assertEquals(ReaderSettingsOptions.THEME_DARK,
                ReaderSettingsOptions.normalizeTheme(ReaderSettingsOptions.THEME_DARK));
        assertEquals(ReaderSettingsOptions.SENSITIVITY_STANDARD,
                ReaderSettingsOptions.normalizeSensitivity(99));
        assertEquals(ReaderSettingsOptions.SENSITIVITY_HIGH,
                ReaderSettingsOptions.normalizeSensitivity(ReaderSettingsOptions.SENSITIVITY_HIGH));
        assertEquals(ReaderSettingsOptions.FONT_SYSTEM,
                ReaderSettingsOptions.normalizeFontFamily(-1));
        assertEquals(ReaderSettingsOptions.FONT_FANGSONG,
                ReaderSettingsOptions.normalizeFontFamily(ReaderSettingsOptions.FONT_FANGSONG));
    }

    @Test
    public void clampsFontSizeAndRecoversNonFiniteValues() {
        assertEquals(14f, ReaderSettingsOptions.normalizeFontSize(10f), 0f);
        assertEquals(22f, ReaderSettingsOptions.normalizeFontSize(22f), 0f);
        assertEquals(34f, ReaderSettingsOptions.normalizeFontSize(40f), 0f);
        assertEquals(20f, ReaderSettingsOptions.normalizeFontSize(Float.NaN), 0f);
        assertEquals(20f, ReaderSettingsOptions.normalizeFontSize(Float.POSITIVE_INFINITY), 0f);
    }

    @Test
    public void clampsLineSpacingAndRecoversNonFiniteValues() {
        assertEquals(0.10f, ReaderSettingsOptions.normalizeLineSpacing(0f), 0f);
        assertEquals(0.18f, ReaderSettingsOptions.normalizeLineSpacing(0.18f), 0f);
        assertEquals(0.40f, ReaderSettingsOptions.normalizeLineSpacing(1f), 0f);
        assertEquals(0.18f, ReaderSettingsOptions.normalizeLineSpacing(Float.NaN), 0f);
        assertEquals(0.18f,
                ReaderSettingsOptions.normalizeLineSpacing(Float.NEGATIVE_INFINITY), 0f);
    }
}
