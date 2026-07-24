package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReaderDisplayPolicyTest {
    @Test
    public void themeChoiceHasOnlyFixedLightAndDarkModes() {
        assertFalse(ReaderDisplayPolicy.isDarkTheme(ReaderSettingsOptions.THEME_LIGHT));
        assertTrue(ReaderDisplayPolicy.isDarkTheme(ReaderSettingsOptions.THEME_DARK));
        assertFalse(ReaderDisplayPolicy.isDarkTheme(
                ReaderSettingsOptions.LEGACY_THEME_SYSTEM));
    }

    @Test
    public void sensitivityChangesBothTapAndSwipeThresholds() {
        assertEquals(20, ReaderDisplayPolicy.tapToleranceDp(
                ReaderSettingsOptions.SENSITIVITY_HIGH));
        assertEquals(16, ReaderDisplayPolicy.tapToleranceDp(
                ReaderSettingsOptions.SENSITIVITY_STANDARD));
        assertEquals(12, ReaderDisplayPolicy.tapToleranceDp(
                ReaderSettingsOptions.SENSITIVITY_LOW));
        assertEquals(48, ReaderDisplayPolicy.swipeThresholdDp(
                ReaderSettingsOptions.SENSITIVITY_HIGH));
        assertEquals(72, ReaderDisplayPolicy.swipeThresholdDp(
                ReaderSettingsOptions.SENSITIVITY_STANDARD));
        assertEquals(96, ReaderDisplayPolicy.swipeThresholdDp(
                ReaderSettingsOptions.SENSITIVITY_LOW));
    }

    @Test
    public void fontChoicesMapToReaderTypefaceInputs() {
        assertEquals("sans", ReaderDisplayPolicy.fontFamilyName(
                ReaderSettingsOptions.FONT_SYSTEM));
        assertEquals("sans-serif", ReaderDisplayPolicy.fontFamilyName(
                ReaderSettingsOptions.FONT_SANS_SERIF));
        assertEquals("serif", ReaderDisplayPolicy.fontFamilyName(
                ReaderSettingsOptions.FONT_SERIF));
        assertEquals("serif", ReaderDisplayPolicy.fontFamilyName(
                ReaderSettingsOptions.FONT_FANGSONG));
        assertEquals("monospace", ReaderDisplayPolicy.fontFamilyName(
                ReaderSettingsOptions.FONT_MONOSPACE));
        assertFalse(ReaderDisplayPolicy.fontUsesItalicStyle(
                ReaderSettingsOptions.FONT_SERIF));
        assertTrue(ReaderDisplayPolicy.fontUsesItalicStyle(
                ReaderSettingsOptions.FONT_FANGSONG));
    }

    @Test
    public void lineSpacingChangesRenderedExtraSpacing() {
        assertEquals(2f, ReaderDisplayPolicy.lineSpacingExtraSp(14f, 0.10f), 0f);
        assertEquals(3.6f, ReaderDisplayPolicy.lineSpacingExtraSp(20f, 0.18f), 0.0001f);
        assertEquals(13.6f, ReaderDisplayPolicy.lineSpacingExtraSp(34f, 0.40f), 0.0001f);
    }
}
