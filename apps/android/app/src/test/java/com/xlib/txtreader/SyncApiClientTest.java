package com.xlib.txtreader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class SyncApiClientTest {
    @Test
    public void deleteWithoutPayloadUsesValidEmptyJsonObject() {
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8),
                SyncApiClient.encodedRequestBody("DELETE", null));
    }

    @Test
    public void getWithoutPayloadDoesNotSendRequestBody() {
        assertNull(SyncApiClient.encodedRequestBody("GET", null));
    }

    @Test
    public void deletionAlwaysRequiresAnExactBookIdentity() {
        String hash = "a".repeat(64);
        assertEquals("/v1/progress/" + hash + "/100",
                SyncApiClient.bookProgressPath(new BookKey(hash, 100)));
        assertThrows(IllegalArgumentException.class, () -> SyncApiClient.bookProgressPath(null));
        assertThrows(IllegalArgumentException.class, () ->
                SyncApiClient.bookProgressPath(new BookKey(hash, 0)));
        assertThrows(IllegalArgumentException.class, () ->
                SyncApiClient.bookProgressPath(new BookKey("../account", 100)));
    }
}
