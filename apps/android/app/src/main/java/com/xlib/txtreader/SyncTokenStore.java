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
    private static final String KEY_DEVICE_ID = "sync_device_id";
    private static final String KEY_DEVICE_NAME = "sync_device_name";

    private final SharedPreferences preferences;

    SyncTokenStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    synchronized void save(String email, String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(KEY_EMAIL, normalizeEmail(email))
                .putString(KEY_TOKEN_CIPHERTEXT,
                        Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_TOKEN_IV,
                        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
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
        return token() != null;
    }

    synchronized String email() {
        return preferences.getString(KEY_EMAIL, "");
    }

    synchronized void saveDeviceName(String deviceName) {
        String normalized = normalizeDeviceName(deviceName);
        if (!normalized.isEmpty()) {
            preferences.edit().putString(KEY_DEVICE_NAME, normalized).apply();
        }
    }

    synchronized void clear() {
        preferences.edit()
                .remove(KEY_EMAIL)
                .remove(KEY_TOKEN_CIPHERTEXT)
                .remove(KEY_TOKEN_IV)
                .apply();
    }

    synchronized String deviceId() {
        String existing = preferences.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) return existing;
        String created = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        preferences.edit().putString(KEY_DEVICE_ID, created).apply();
        return created;
    }

    synchronized String deviceName() {
        String existing = preferences.getString(KEY_DEVICE_NAME, null);
        if (existing != null && !existing.trim().isEmpty()) return existing;
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "Android" : Build.MODEL.trim();
        String combined = manufacturer.isEmpty() || model.toLowerCase(Locale.ROOT)
                .startsWith(manufacturer.toLowerCase(Locale.ROOT))
                ? model : manufacturer + " " + model;
        String value = combined.trim().isEmpty() ? "Android 设备" : combined.trim();
        if (value.length() > 80) value = value.substring(0, 80);
        preferences.edit().putString(KEY_DEVICE_NAME, value).apply();
        return value;
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
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    static boolean isValidDeviceName(String deviceName) {
        if (deviceName == null) return false;
        String normalized = deviceName.trim();
        return !normalized.isEmpty() && normalized.length() <= 80;
    }
}
