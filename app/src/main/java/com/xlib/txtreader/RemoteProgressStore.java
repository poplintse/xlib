package com.xlib.txtreader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RemoteProgressStore {
    private static final String KEY_EMAIL = "sync_remote_email";
    private static final String KEY_ITEMS = "sync_remote_items";

    private final SharedPreferences preferences;
    private final Map<BookKey, RemoteProgressSnapshot> snapshots = new HashMap<>();
    private String email = "";

    RemoteProgressStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    synchronized void open(String requestedEmail) {
        String normalized = SyncTokenStore.normalizeEmail(requestedEmail);
        snapshots.clear();
        email = normalized;
        if (!normalized.equals(preferences.getString(KEY_EMAIL, ""))) return;
        String raw = preferences.getString(KEY_ITEMS, "[]");
        try {
            JSONArray items = new JSONArray(raw);
            for (int i = 0; i < items.length(); i++) {
                RemoteProgressSnapshot snapshot = fromJson(items.getJSONObject(i), null);
                snapshots.put(snapshot.bookKey(), snapshot);
            }
        } catch (Exception ignored) {
            snapshots.clear();
        }
    }

    synchronized void replaceAll(String accountEmail, List<RemoteProgressSnapshot> items,
                                 String launchId) {
        email = SyncTokenStore.normalizeEmail(accountEmail);
        snapshots.clear();
        long fetchedAt = System.currentTimeMillis();
        for (RemoteProgressSnapshot item : items) {
            RemoteProgressSnapshot fresh = copyWithFreshness(item, fetchedAt, launchId);
            snapshots.put(fresh.bookKey(), fresh);
        }
        persist();
    }

    synchronized void put(String accountEmail, RemoteProgressSnapshot item, String launchId) {
        String normalized = SyncTokenStore.normalizeEmail(accountEmail);
        if (!normalized.equals(email)) {
            snapshots.clear();
            email = normalized;
        }
        RemoteProgressSnapshot fresh = copyWithFreshness(item, System.currentTimeMillis(), launchId);
        snapshots.put(fresh.bookKey(), fresh);
        persist();
    }

    synchronized RemoteProgressSnapshot get(BookKey key) {
        return snapshots.get(key);
    }

    synchronized void clear() {
        email = "";
        snapshots.clear();
        preferences.edit().remove(KEY_EMAIL).remove(KEY_ITEMS).apply();
    }

    private RemoteProgressSnapshot copyWithFreshness(RemoteProgressSnapshot item, long fetchedAt,
                                                     String launchId) {
        return new RemoteProgressSnapshot(item.bookHash, item.fileSize, item.offset,
                item.progress, item.readAtMs, item.version, item.sourceDeviceId,
                item.sourceDeviceName, item.sourcePlatform, fetchedAt, launchId);
    }

    private void persist() {
        try {
            JSONArray items = new JSONArray();
            for (RemoteProgressSnapshot snapshot : snapshots.values()) {
                JSONObject object = new JSONObject();
                object.put("bookHash", snapshot.bookHash);
                object.put("fileSize", snapshot.fileSize);
                object.put("offset", snapshot.offset);
                object.put("progress", snapshot.progress);
                object.put("readAtMs", snapshot.readAtMs);
                object.put("version", snapshot.version);
                object.put("sourceDeviceId", snapshot.sourceDeviceId);
                object.put("sourceDeviceName", snapshot.sourceDeviceName);
                object.put("sourcePlatform", snapshot.sourcePlatform);
                object.put("fetchedAtMs", snapshot.fetchedAtMs);
                items.put(object);
            }
            preferences.edit().putString(KEY_EMAIL, email)
                    .putString(KEY_ITEMS, items.toString()).apply();
        } catch (Exception ignored) {
            // Keep the last complete remote cache if serialization fails.
        }
    }

    private RemoteProgressSnapshot fromJson(JSONObject object, String launchId) throws Exception {
        String hash = object.getString("bookHash");
        long size = object.getLong("fileSize");
        long offset = object.getLong("offset");
        if (!hash.matches("[0-9a-f]{64}") || size <= 0L || offset < 0L || offset > size) {
            throw new IllegalArgumentException("invalid cached progress");
        }
        return new RemoteProgressSnapshot(hash, size, offset, object.getDouble("progress"),
                object.getLong("readAtMs"), object.getString("version"),
                object.getString("sourceDeviceId"), object.getString("sourceDeviceName"),
                object.getString("sourcePlatform"), object.optLong("fetchedAtMs", 0L), launchId);
    }
}
