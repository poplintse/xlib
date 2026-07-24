package com.xlib.txtreader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncTokenStoreTest {
    @Test
    public void normalizesDeviceNameWithoutChangingReadableContent() {
        assertEquals("我的手机", SyncTokenStore.normalizeDeviceName("  我的手机  "));
        assertEquals(80, SyncTokenStore.normalizeDeviceName(repeat('a', 100)).length());
    }

    @Test
    public void validatesDeviceNameRequiredForSync() {
        assertFalse(SyncTokenStore.isValidDeviceName(null));
        assertFalse(SyncTokenStore.isValidDeviceName("   "));
        assertTrue(SyncTokenStore.isValidDeviceName("Iphone2"));
        assertFalse(SyncTokenStore.isValidDeviceName(repeat('a', 81)));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
