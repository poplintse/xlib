package com.xlib.txtreader;

import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SyncTokenStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "xlib_sync_token_key";
    private static final String KEY_TOKEN_CIPHERTEXT = "sync_token_ciphertext";
    private static final String KEY_TOKEN_IV = "sync_token_iv";
    private static final String KEY_EMAIL = "sync_email";
    private static final String KEY_CONFIGURED_EMAIL = "sync_configured_email";
    private static final String KEY_ACTIVE_EMAIL = "sync_active_email";
    private static final String KEY_ACTIVE_DEVICE_NAME = "sync_active_device_name";
    private static final String KEY_ACTIVE_SERVER_URL = "sync_active_server_url";
    private static final String KEY_DEVICE_ID = "sync_device_id";
    private static final String KEY_DEVICE_NAME = "sync_device_name";
    private static final String KEY_STARTED = "sync_started";

    private final SharedPreferences preferences;
    private final LocalDatabase database;

    SyncTokenStore(SharedPreferences preferences) {
        this.preferences = preferences;
        this.database = null;
    }

    SyncTokenStore(SharedPreferences securePreferences, LocalDatabase database) {
        this.preferences = securePreferences;
        this.database = database;
    }

    synchronized void save(String email, String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(KEY_TOKEN_CIPHERTEXT,
                        Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_TOKEN_IV,
                        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
        putConfigBoolean(KEY_STARTED, true);
        putConfig(KEY_EMAIL, normalizeEmail(email));
        putConfig(KEY_CONFIGURED_EMAIL, normalizeEmail(email));
    }

    synchronized String token() {
        String ciphertext = preferences.getString(KEY_TOKEN_CIPHERTEXT, null);
        String iv = preferences.getString(KEY_TOKEN_IV, null);
        if (ciphertext == null || iv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] decrypted = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            clear();
            return null;
        }
    }

    synchronized boolean enabled() {
        return configBoolean(KEY_STARTED, false) || token() != null;
    }

    synchronized void invalidateCredentials() {
        boolean started = enabled();
        clear();
        putConfigBoolean(KEY_STARTED, started);
    }

    synchronized String email() {
        return config(KEY_EMAIL, "");
    }

    synchronized String configuredEmail() {
        if (containsConfig(KEY_CONFIGURED_EMAIL)) {
            return config(KEY_CONFIGURED_EMAIL, "");
        }
        String existing = email();
        if (!existing.isEmpty()) {
            putConfig(KEY_CONFIGURED_EMAIL, existing);
        }
        return existing;
    }

    synchronized void saveConfiguredEmail(String email) {
        putConfig(KEY_CONFIGURED_EMAIL, normalizeEmail(email));
    }

    synchronized void saveActiveConfiguration(String email, String deviceName,
                                              String serverUrl) {
        putConfig(KEY_ACTIVE_EMAIL, normalizeEmail(email));
        putConfig(KEY_ACTIVE_DEVICE_NAME, normalizeDeviceName(deviceName));
        putConfig(KEY_ACTIVE_SERVER_URL, SyncServerConfig.normalize(serverUrl));
    }

    synchronized void ensureActiveConfiguration(String serverUrl) {
        if (!enabled() || containsConfig(KEY_ACTIVE_EMAIL)) return;
        saveActiveConfiguration(email(), deviceName(), serverUrl);
    }

    synchronized boolean activeConfigurationMatches(String serverUrl) {
        if (!enabled()) return false;
        if (!containsConfig(KEY_ACTIVE_EMAIL)) return true;
        return configurationMatches(configuredEmail(), deviceName(), serverUrl,
                config(KEY_ACTIVE_EMAIL, ""),
                config(KEY_ACTIVE_DEVICE_NAME, ""),
                config(KEY_ACTIVE_SERVER_URL, ""));
    }

    synchronized void saveDeviceName(String deviceName) {
        String normalized = normalizeDeviceName(deviceName);
        if (!normalized.isEmpty()) {
            putConfig(KEY_DEVICE_NAME, normalized);
        }
    }

    synchronized void clear() {
        preferences.edit()
                .remove(KEY_TOKEN_CIPHERTEXT)
                .remove(KEY_TOKEN_IV)
                .apply();
        removeConfig(KEY_STARTED);
        removeConfig(KEY_EMAIL);
        removeConfig(KEY_ACTIVE_EMAIL);
        removeConfig(KEY_ACTIVE_DEVICE_NAME);
        removeConfig(KEY_ACTIVE_SERVER_URL);
    }

    synchronized String deviceId() {
        String existing = config(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) return existing;
        String created = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        putConfig(KEY_DEVICE_ID, created);
        return created;
    }

    synchronized String deviceName() {
        String existing = config(KEY_DEVICE_NAME, null);
        if (existing != null && !existing.trim().isEmpty()) return existing;
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "Android" : Build.MODEL.trim();
        String combined = manufacturer.isEmpty() || model.toLowerCase(Locale.ROOT)
                .startsWith(manufacturer.toLowerCase(Locale.ROOT))
                ? model : manufacturer + " " + model;
        String value = combined.trim().isEmpty() ? "Android 设备" : combined.trim();
        value = normalizeDeviceName(value);
        putConfig(KEY_DEVICE_NAME, value);
        return value;
    }

    private String config(String key, String fallback) {
        return database == null ? preferences.getString(key, fallback) : database.syncValue(key, fallback);
    }

    private boolean configBoolean(String key, boolean fallback) {
        return database == null ? preferences.getBoolean(key, fallback) : database.syncBoolean(key, fallback);
    }

    private boolean containsConfig(String key) {
        return database == null ? preferences.contains(key) : database.hasSyncValue(key);
    }

    private void putConfig(String key, String value) {
        if (database == null) preferences.edit().putString(key, value).apply();
        else database.putSyncValue(key, value);
    }

    private void putConfigBoolean(String key, boolean value) {
        if (database == null) preferences.edit().putBoolean(key, value).apply();
        else database.putSyncValue(key, value ? "1" : "0");
    }

    private void removeConfig(String key) {
        if (database == null) preferences.edit().remove(key).apply();
        else database.removeSyncValue(key);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry existing = store.getEntry(KEY_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeDeviceName(String deviceName) {
        if (deviceName == null) return "";
        String normalized = deviceName.trim();
        return normalized.length() > 20 ? normalized.substring(0, 20) : normalized;
    }

    static boolean isValidDeviceName(String deviceName) {
        if (deviceName == null) return false;
        String normalized = deviceName.trim();
        return !normalized.isEmpty() && normalized.length() <= 20;
    }

    static boolean configurationMatches(String configuredEmail, String configuredDeviceName,
                                        String configuredServerUrl, String activeEmail,
                                        String activeDeviceName, String activeServerUrl) {
        return normalizeEmail(configuredEmail).equals(normalizeEmail(activeEmail))
                && normalizeDeviceName(configuredDeviceName).equals(
                        normalizeDeviceName(activeDeviceName))
                && SyncServerConfig.normalize(configuredServerUrl).equals(
                        SyncServerConfig.normalize(activeServerUrl));
    }
}
