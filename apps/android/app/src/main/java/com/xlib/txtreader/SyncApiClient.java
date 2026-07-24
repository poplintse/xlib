package com.xlib.txtreader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SyncApiClient {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private volatile String baseUrl;

    SyncApiClient(String baseUrl) {
        this.baseUrl = SyncServerConfig.normalize(baseUrl);
    }

    boolean configured() {
        return SyncServerConfig.isValid(baseUrl);
    }

    void setBaseUrl(String baseUrl) {
        this.baseUrl = SyncServerConfig.normalize(baseUrl);
    }

    StartSyncResponse startSync(String email, String deviceId, String deviceName,
                                String appVersion) throws Exception {
        JSONObject device = new JSONObject();
        device.put("deviceId", deviceId);
        device.put("deviceName", deviceName);
        device.put("platform", "android");
        device.put("appVersion", appVersion);
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("device", device);
        JSONObject response = requestJson("POST", "/v1/auth/start-sync", body, null, null);
        requireServerTime(response);
        String token = requiredString(response, "token");
        JSONObject user = response.getJSONObject("user");
        if (!isUuid(requiredString(user, "userId"))) {
            throw new ProtocolException("invalid user id");
        }
        String normalizedEmail = requiredString(user, "email");
        if (!SyncTokenStore.normalizeEmail(email).equals(normalizedEmail)) {
            throw new ProtocolException("server returned a different email");
        }
        JSONObject returnedDevice = response.getJSONObject("device");
        if (!deviceId.equals(requiredString(returnedDevice, "deviceId"))
                || !"android".equals(requiredString(returnedDevice, "platform"))
                || requiredString(returnedDevice, "deviceName").length() > 80) {
            throw new ProtocolException("invalid registered device");
        }
        return new StartSyncResponse(token, normalizedEmail);
    }

    List<RemoteProgressSnapshot> pullProgress(String token, String deviceId) throws Exception {
        JSONObject response = requestJson("GET", "/v1/progress", null, token, deviceId);
        requireServerTime(response);
        JSONArray items = response.getJSONArray("items");
        List<RemoteProgressSnapshot> result = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            result.add(parseRemote(items.getJSONObject(i)));
        }
        return result;
    }

    RemoteProgressSnapshot syncProgress(String token, String deviceId,
                                        LocalProgressSnapshot snapshot) throws Exception {
        JSONObject item = new JSONObject();
        item.put("bookHash", snapshot.bookHash);
        item.put("fileSize", snapshot.fileSize);
        item.put("offset", snapshot.offset);
        item.put("readAtMs", snapshot.readAtMs);
        JSONArray items = new JSONArray();
        items.put(item);
        JSONObject body = new JSONObject();
        body.put("items", items);
        JSONObject response = requestJson("POST", "/v1/progress/sync", body, token, deviceId);
        requireServerTime(response);
        JSONArray results = response.getJSONArray("results");
        if (results.length() != 1) throw new ProtocolException("invalid result count");
        JSONObject result = results.getJSONObject(0);
        String decision = requiredString(result, "decision");
        if (!decision.equals("accepted") && !decision.equals("server_kept")
                && !decision.equals("unchanged")) {
            throw new ProtocolException("invalid sync decision");
        }
        return parseRemote(result.getJSONObject("state"));
    }

    List<SyncDevice> listDevices(String token, String deviceId) throws Exception {
        JSONObject response = requestJson("GET", "/v1/devices", null, token, deviceId);
        requireServerTime(response);
        JSONArray items = response.optJSONArray("items");
        if (items == null) items = response.optJSONArray("devices");
        if (items == null) throw new ProtocolException("missing devices");
        List<SyncDevice> devices = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String returnedDeviceId = requiredString(item, "deviceId");
            if (!isUuid(returnedDeviceId)) throw new ProtocolException("invalid device id");
            devices.add(new SyncDevice(returnedDeviceId,
                    requiredString(item, "deviceName"), requiredString(item, "platform"),
                    item.optLong("lastSeenAtMs", 0L),
                    item.optBoolean("revoked", item.optBoolean("isRevoked", false))
                            || (item.has("revokedAtMs") && !item.isNull("revokedAtMs"))));
        }
        return devices;
    }

    void revokeDevice(String token, String deviceId, String targetDeviceId) throws Exception {
        requestNoContent("DELETE", "/v1/devices/" + targetDeviceId, token, deviceId);
    }

    void deleteProgress(String token, String deviceId) throws Exception {
        requestNoContent("DELETE", "/v1/progress", token, deviceId);
    }

    void health() throws Exception {
        JSONObject response = requestJson("GET", "/health", null, null, null);
        if (!"ok".equals(requiredString(response, "status"))) {
            throw new ProtocolException("health response is not ok");
        }
    }

    private RemoteProgressSnapshot parseRemote(JSONObject item) throws Exception {
        String hash = requiredString(item, "bookHash");
        long fileSize = item.getLong("fileSize");
        long offset = item.getLong("offset");
        double progress = item.getDouble("progress");
        long readAtMs = item.getLong("readAtMs");
        String version = requiredString(item, "version");
        JSONObject device = item.getJSONObject("device");
        String sourceDeviceId = requiredString(device, "deviceId");
        String sourceDeviceName = requiredString(device, "deviceName");
        String platform = requiredString(device, "platform");
        if (!hash.matches("[0-9a-f]{64}") || fileSize <= 0L || offset < 0L
                || offset > fileSize || !Double.isFinite(progress) || progress < 0d
                || progress > 1d || readAtMs < 0L || version.isEmpty()
                || !isUuid(sourceDeviceId) || sourceDeviceName.length() > 80
                || !(platform.equals("android") || platform.equals("ios"))) {
            throw new ProtocolException("invalid progress state");
        }
        return new RemoteProgressSnapshot(hash, fileSize, offset,
                SyncRules.progress(offset, fileSize), readAtMs, version,
                sourceDeviceId, sourceDeviceName, platform, 0L, null);
    }

    private JSONObject requestJson(String method, String path, JSONObject body, String token,
                                   String deviceId) throws Exception {
        HttpResult result = execute(method, path, body, token, deviceId);
        if (result.status < 200 || result.status >= 300) throw apiError(result);
        if (result.body.length == 0) throw new ProtocolException("empty JSON response");
        return new JSONObject(new String(result.body, StandardCharsets.UTF_8));
    }

    private void requestNoContent(String method, String path, String token,
                                  String deviceId) throws Exception {
        HttpResult result = execute(method, path, null, token, deviceId);
        if (result.status < 200 || result.status >= 300) throw apiError(result);
    }

    private HttpResult execute(String method, String path, JSONObject body, String token,
                               String deviceId) throws Exception {
        if (!configured()) throw new ServiceNotConfiguredException();
        String requestBaseUrl = baseUrl;
        HttpURLConnection connection = (HttpURLConnection) new URL(requestBaseUrl + path)
                .openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (deviceId != null) connection.setRequestProperty("X-Device-Id", deviceId);
            if (body != null) {
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(encoded.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(encoded);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            byte[] response = stream == null ? new byte[0] : readLimited(stream);
            return new HttpResult(status, response, connection.getHeaderField("Retry-After"));
        } finally {
            connection.disconnect();
        }
    }

    private byte[] readLimited(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new ProtocolException("response too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private ApiException apiError(HttpResult result) {
        String code = "HTTP_" + result.status;
        boolean retryable = result.status == 429 || result.status == 500 || result.status == 503;
        try {
            JSONObject root = new JSONObject(new String(result.body, StandardCharsets.UTF_8));
            JSONObject error = root.getJSONObject("error");
            code = error.optString("code", code);
            retryable = error.optBoolean("retryable", retryable);
        } catch (Exception ignored) {
            // Status code remains the anonymous diagnostic value.
        }
        long retryAfterMs = parseRetryAfter(result.retryAfter);
        return new ApiException(result.status, code, retryable, retryAfterMs);
    }

    private long parseRetryAfter(String value) {
        if (value == null) return 60_000L;
        try {
            return Math.max(1_000L, Long.parseLong(value.trim()) * 1_000L);
        } catch (NumberFormatException ignored) {
            return 60_000L;
        }
    }

    private static String requiredString(JSONObject object, String name) throws Exception {
        String value = object.getString(name);
        if (value == null || value.isEmpty()) throw new ProtocolException("missing " + name);
        return value;
    }

    private static boolean isUuid(String value) {
        return value != null && value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static void requireServerTime(JSONObject object) throws Exception {
        if (object.getLong("serverTimeMs") < 0L) {
            throw new ProtocolException("invalid server time");
        }
    }

    static final class StartSyncResponse {
        final String token;
        final String email;

        StartSyncResponse(String token, String email) {
            this.token = token;
            this.email = email;
        }
    }

    static final class ApiException extends Exception {
        final int status;
        final String code;
        final boolean retryable;
        final long retryAfterMs;

        ApiException(int status, String code, boolean retryable, long retryAfterMs) {
            super(code);
            this.status = status;
            this.code = code;
            this.retryable = retryable;
            this.retryAfterMs = retryAfterMs;
        }
    }

    static final class ProtocolException extends Exception {
        ProtocolException(String message) {
            super(message);
        }
    }

    static final class ServiceNotConfiguredException extends Exception {
        ServiceNotConfiguredException() {
            super("sync service is not configured");
        }
    }

    private static final class HttpResult {
        final int status;
        final byte[] body;
        final String retryAfter;

        HttpResult(int status, byte[] body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }
}
