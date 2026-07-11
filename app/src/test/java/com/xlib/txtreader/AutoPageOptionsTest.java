package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AutoPageOptionsTest {
    @Test
    public void acceptsOnlySupportedIntervals() {
        assertEquals(0, AutoPageOptions.normalize(-1));
        assertEquals(0, AutoPageOptions.normalize(3));
        assertEquals(5, AutoPageOptions.normalize(5));
        assertEquals(10, AutoPageOptions.normalize(10));
        assertEquals(20, AutoPageOptions.normalize(20));
    }

    @Test
    public void formatsCompactToolbarLabels() {
        assertEquals("OFF", AutoPageOptions.shortLabel(0));
        assertEquals("5s", AutoPageOptions.shortLabel(5));
        assertEquals("10s", AutoPageOptions.shortLabel(10));
        assertEquals("20s", AutoPageOptions.shortLabel(20));
    }
}
