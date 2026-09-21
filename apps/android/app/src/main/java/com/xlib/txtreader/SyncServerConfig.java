package com.xlib.txtreader;

import java.net.URI;

final class SyncServerConfig {
    static final String DEFAULT_URL = "https://xunit.cc/xlib/backend";
    private static final String KEY_SERVER_URL = "sync_server_url";

    private final LocalDatabase database;

    SyncServerConfig(LocalDatabase database) {
        this.database = database;
        if (!database.hasSyncValue(KEY_SERVER_URL)) database.putSyncValue(KEY_SERVER_URL, DEFAULT_URL);
    }

    synchronized String url() { return normalize(database.syncValue(KEY_SERVER_URL, DEFAULT_URL)); }

    synchronized boolean save(String value) {
        String normalized = normalize(value);
        if (!isValid(normalized)) return false;
        database.putSyncValue(KEY_SERVER_URL, normalized);
        return true;
    }

    static boolean isValid(String value) {
        try {
            URI uri = URI.create(normalize(value));
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isEmpty()
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
