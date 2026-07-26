package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KeyboardAvoidancePolicyTest {
    @Test
    public void scrollsFieldAboveKeyboardSafeArea() {
        assertEquals(64, KeyboardAvoidancePolicy.scrollDelta(
                500, 564, 100, 524, 12, 24));
    }

    @Test
    public void scrollsFieldDownWhenItIsAboveViewport() {
        assertEquals(-22, KeyboardAvoidancePolicy.scrollDelta(
                90, 136, 100, 700, 12, 24));
    }

    @Test
    public void leavesFullyVisibleFieldInPlace() {
        assertEquals(0, KeyboardAvoidancePolicy.scrollDelta(
                240, 286, 100, 700, 12, 24));
    }
}
