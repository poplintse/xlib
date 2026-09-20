package com.xlib.txtreader;

/** Serialized configuration writes and credential identity; no reader state or network requests. */
final class SyncConfigurationSession {
    private final SyncTokenStore tokens;
    private final SyncServerConfig server;
    private final SyncTransport transport;
    private volatile long generation;
    SyncConfigurationSession(SyncTokenStore tokens, SyncServerConfig server, SyncTransport transport) {
        this.tokens = tokens; this.server = server; this.transport = transport;
    }
    long generation() { return generation; }
    boolean changed() { return tokens.enabled() && (tokens.token() == null || !tokens.activeConfigurationMatches(server.url())); }
    void invalidate() { generation++; tokens.invalidateCredentials(); }
    void disable() { generation++; tokens.clear(); }
    void registered(String email, String name, String token) throws Exception {
        tokens.saveDeviceName(name);
        tokens.save(email, token);
        tokens.saveActiveConfiguration(email, name, server.url());
    }
    String saveEmail(String email) {
        String value = SyncTokenStore.normalizeEmail(email);
        if (!validEmail(value)) return "INVALID_EMAIL";
        tokens.saveConfiguredEmail(value);
        return null;
    }
    String saveName(String name) {
        String value = SyncTokenStore.normalizeDeviceName(name);
        if (!SyncTokenStore.isValidDeviceName(value)) return "INVALID_DEVICE_NAME";
        tokens.saveDeviceName(value);
        return null;
    }
    String saveServer(String url) {
        if (!server.save(url)) return "INVALID_SERVER_URL";
        transport.setBaseUrl(server.url());
        return null;
    }
    String save(String email, String name, String url) {
        String normalizedEmail = SyncTokenStore.normalizeEmail(email);
        String normalizedName = SyncTokenStore.normalizeDeviceName(name);
        if (!complete(normalizedEmail, normalizedName, url)) return "INCOMPLETE_CONFIGURATION";
        tokens.saveConfiguredEmail(normalizedEmail);
        tokens.saveDeviceName(normalizedName);
        server.save(url);
        transport.setBaseUrl(server.url());
        return null;
    }
    static boolean validEmail(String email) {
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && at < email.length() - 3
                && email.indexOf('.', at) > at + 1 && email.length() <= 254;
    }
    static boolean complete(String email, String name, String url) {
        return validEmail(SyncTokenStore.normalizeEmail(email))
                && SyncTokenStore.isValidDeviceName(name) && SyncServerConfig.isValid(url);
    }
}
