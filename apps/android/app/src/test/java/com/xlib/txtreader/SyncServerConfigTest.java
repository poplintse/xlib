package com.xlib.txtreader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncServerConfigTest {
    @Test
    public void defaultServerMatchesProductConfiguration() {
        assertEquals("https://xunit.cc/xlib/backend", SyncServerConfig.DEFAULT_URL);
    }

    @Test
    public void acceptsHttpsAndNormalizesTrailingSlash() {
        assertTrue(SyncServerConfig.isValid("https://xunit.cc/xlib/backend/"));
        assertEquals("https://xunit.cc/xlib/backend",
                SyncServerConfig.normalize(" https://xunit.cc/xlib/backend/ "));
    }

    @Test
    public void rejectsCleartextCredentialsAndAmbiguousSuffixes() {
        assertFalse(SyncServerConfig.isValid("http://xunit.cc/xlib/backend"));
        assertFalse(SyncServerConfig.isValid("https://user@xunit.cc/xlib/backend"));
        assertFalse(SyncServerConfig.isValid("https://xunit.cc/xlib/backend?token=x"));
        assertFalse(SyncServerConfig.isValid("https://xunit.cc/xlib/backend#fragment"));
    }
}
