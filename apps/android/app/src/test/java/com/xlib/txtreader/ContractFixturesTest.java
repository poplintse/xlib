package com.xlib.txtreader;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContractFixturesTest {
    private String fixture(String name) throws Exception {
        try (java.io.InputStream stream = getClass().getResourceAsStream("/" + name + ".json")) {
            assertNotNull("Missing canonical fixture " + name, stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void sharedProgressBehavior() throws Exception {
        JSONArray cases = new JSONArray(fixture("progress-behavior"));
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            LocalProgressSnapshot local = new LocalProgressSnapshot(1, c.getString("bookHash"),
                c.getLong("fileSize"), c.getLong("localOffset"), c.getLong("localReadAtMs"), 0);
            RemoteProgressSnapshot remote = new RemoteProgressSnapshot(local.bookHash, local.fileSize,
                c.getLong("remoteOffset"), 0, c.getLong("remoteReadAtMs"), "1", c.getString("remoteDeviceId"),
                "设备", "ios", 0, "launch");
            assertEquals(c.getString("id"), c.getBoolean("expectedPrompt"), SyncRules.shouldPrompt(
                local, remote, c.getString("currentDeviceId"), "launch", null, false));
        }
    }

    @Test public void actualTransportUsesGoldenRequestResponseAndErrors() throws Exception {
        JSONObject wire = new JSONObject(fixture("wire"));
        JSONObject request = wire.getJSONObject("ProgressSyncRequest");
        JSONObject item = request.getJSONArray("items").getJSONObject(0);
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> device = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(5000);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                for (int n = 0; n < 2; n++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        java.io.InputStream input = socket.getInputStream();
                        String requestLine = line(input);
                        method.set(requestLine.split(" ")[0]);
                        boolean sync = requestLine.contains("/v1/progress/sync ");
                        int length = 0;
                        String header;
                        while (!(header = line(input)).isEmpty()) {
                            int colon = header.indexOf(':');
                            String key = header.substring(0, colon).trim();
                            String value = header.substring(colon + 1).trim();
                            if (key.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(value);
                            if (key.equalsIgnoreCase("Authorization")) authorization.set(value);
                            if (key.equalsIgnoreCase("X-Device-Id")) device.set(value);
                        }
                        received.set(new String(input.readNBytes(length), StandardCharsets.UTF_8));
                        byte[] body = wire.getJSONObject(sync ? "ProgressSyncResponse" : "ErrorEnvelope")
                            .toString().getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 " + (sync ? "200 OK" : "403 Forbidden")
                            + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                        socket.getOutputStream().flush();
                    }
                }
            } catch (Throwable error) { serverError.set(error); }
        });
        worker.start();
        String address = "http://127.0.0.1:" + server.getLocalPort();
        // Test-only loopback transport; production remains HTTPS-only.
        try (org.mockito.MockedStatic<SyncServerConfig> config = org.mockito.Mockito.mockStatic(
                SyncServerConfig.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            config.when(() -> SyncServerConfig.isValid(address)).thenReturn(true);
            SyncApiClient client = new SyncApiClient(address);
            String id = wire.getJSONObject("StartSyncRequest").getJSONObject("device").getString("deviceId");
            RemoteProgressSnapshot result = client.syncProgress("fixture-token", id,
                new LocalProgressSnapshot(1, item.getString("bookHash"), item.getLong("fileSize"),
                    item.getLong("offset"), item.getLong("readAtMs"), 1));
            assertJson(request, new JSONObject(received.get()));
            assertEquals("POST", method.get());
            assertEquals("Bearer fixture-token", authorization.get());
            assertEquals(id, device.get());
            assertEquals(item.getLong("fileSize"), result.fileSize);
            assertEquals(item.getLong("offset"), result.offset);
            assertEquals(item.getLong("readAtMs"), result.readAtMs);
            assertEquals("9007199254740993", result.version);
            SyncApiClient.ApiException error = assertThrows(SyncApiClient.ApiException.class,
                () -> client.pullProgress("fixture-token", id));
            assertEquals(403, error.status);
            assertEquals(wire.getJSONObject("ErrorEnvelope").getJSONObject("error").getString("code"), error.code);
            assertFalse(error.retryable);
        } finally {
            server.close();
            worker.join(6000);
        }
        assertFalse("Fixture server did not finish", worker.isAlive());
        assertNull(serverError.get());
    }
    private static void assertJson(Object expected, Object actual) throws Exception {
        if (expected instanceof JSONObject) {
            assertTrue(actual instanceof JSONObject);
            JSONObject left = (JSONObject) expected, right = (JSONObject) actual;
            assertEquals(left.length(), right.length());
            java.util.Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                assertJson(left.get(key), right.get(key));
            }
        } else if (expected instanceof JSONArray) {
            assertTrue(actual instanceof JSONArray);
            JSONArray left = (JSONArray) expected, right = (JSONArray) actual;
            assertEquals(left.length(), right.length());
            for (int i = 0; i < left.length(); i++) assertJson(left.get(i), right.get(i));
        } else { assertEquals(expected, actual); }
    }

    private static String line(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).trim();
            bytes.write(value);
        }
        throw new java.io.EOFException("Incomplete HTTP fixture request");
    }

}
