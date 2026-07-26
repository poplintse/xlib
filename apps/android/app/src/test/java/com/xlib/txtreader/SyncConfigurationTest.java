package com.xlib.txtreader;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncConfigurationTest {
    @Test
    public void completeConfigurationRequiresAllThreeSavedValues() {
        assertTrue(ProgressSyncCoordinator.isConfigurationComplete(
                "reader@example.com", "Pixel 9", "https://sync.example.com"));
        assertFalse(ProgressSyncCoordinator.isConfigurationComplete(
                "", "Pixel 9", "https://sync.example.com"));
        assertFalse(ProgressSyncCoordinator.isConfigurationComplete(
                "reader@example.com", "", "https://sync.example.com"));
        assertFalse(ProgressSyncCoordinator.isConfigurationComplete(
                "reader@example.com", "Pixel 9", "http://sync.example.com"));
    }

    @Test
    public void deviceNameLimitIsPartOfConfigurationCompleteness() {
        assertTrue(ProgressSyncCoordinator.isConfigurationComplete(
                "reader@example.com", repeat('a', 20), "https://sync.example.com"));
        assertFalse(ProgressSyncCoordinator.isConfigurationComplete(
                "reader@example.com", repeat('a', 21), "https://sync.example.com"));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
