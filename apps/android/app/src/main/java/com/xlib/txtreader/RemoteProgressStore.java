package com.xlib.txtreader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RemoteProgressStore {
    private final LocalDatabase database;
    private final Map<BookKey, RemoteProgressSnapshot> snapshots = new HashMap<>();
    private String email = "";

    RemoteProgressStore(LocalDatabase database) { this.database = database; }

    synchronized void open(String requestedEmail) {
        String normalized = SyncTokenStore.normalizeEmail(requestedEmail);
        snapshots.clear();
        email = normalized;
        for (RemoteProgressSnapshot snapshot : database.loadRemote(normalized, null)) {
            snapshots.put(snapshot.bookKey(), snapshot);
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
        database.clearRemote();
    }

    synchronized void remove(BookKey key) {
        snapshots.remove(key);
        persist();
    }

    private RemoteProgressSnapshot copyWithFreshness(RemoteProgressSnapshot item, long fetchedAt,
                                                     String launchId) {
        return new RemoteProgressSnapshot(item.bookHash, item.fileSize, item.offset,
                item.progress, item.readAtMs, item.version, item.sourceDeviceId,
                item.sourceDeviceName, item.sourcePlatform, fetchedAt, launchId);
    }

    private void persist() {
        database.replaceRemote(email, new java.util.ArrayList<>(snapshots.values()), null);
    }
}
