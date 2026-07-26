package com.xlib.txtreader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

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
}
