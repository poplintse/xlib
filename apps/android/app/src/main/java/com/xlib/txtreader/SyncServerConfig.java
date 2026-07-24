package com.xlib.txtreader;

import android.content.SharedPreferences;

import java.net.URI;

final class SyncServerConfig {
    static final String DEFAULT_URL = "https://xunit.cc/xlib/backend";
    private static final String KEY_SERVER_URL = "sync_server_url";

    private final SharedPreferences preferences;

    SyncServerConfig(SharedPreferences preferences) {
        this.preferences = preferences;
        if (!preferences.contains(KEY_SERVER_URL)) {
            preferences.edit().putString(KEY_SERVER_URL, DEFAULT_URL).apply();
        }
    }

    synchronized String url() {
        String value = preferences.getString(KEY_SERVER_URL, DEFAULT_URL);
        return normalize(value == null ? DEFAULT_URL : value);
    }

    synchronized boolean save(String value) {
        String normalized = normalize(value);
        if (!isValid(normalized)) return false;
        preferences.edit().putString(KEY_SERVER_URL, normalized).apply();
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
