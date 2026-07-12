package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AutoPageOptionsTest {
    @Test
    public void acceptsIntervalsFromTenToThirtySeconds() {
        assertEquals(0, AutoPageOptions.normalize(-1));
        assertEquals(0, AutoPageOptions.normalize(3));
        assertEquals(0, AutoPageOptions.normalize(5));
        assertEquals(10, AutoPageOptions.normalize(10));
        assertEquals(15, AutoPageOptions.normalize(15));
        assertEquals(20, AutoPageOptions.normalize(20));
        assertEquals(30, AutoPageOptions.normalize(30));
        assertEquals(0, AutoPageOptions.normalize(31));
    }

}
