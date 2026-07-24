package com.xlib.txtreader;

import android.content.Context;
import android.os.Handler;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

final class ProgressSyncCoordinator {
    interface Listener {
        void onSyncStateChanged(SyncUiState state);
        void onRemoteJumpAvailable(String sessionId, long localBookId,
                                   RemoteProgressSnapshot remote);
    }

    interface ActionCallback<T> {
        void onResult(SyncActionResult<T> result);
    }

    private static final long PERIOD_MS = 20_000L;

    private final ScheduledExecutorService serial = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService hashExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler;
    private final Listener listener;
    private final LocalProgressStore localStore;
    private final RemoteProgressStore remoteStore;
    private final BookHashCache hashCache;
    private final SyncTokenStore tokenStore;
    private final SyncServerConfig serverConfig;
    private final SyncApiClient api;
    private final ConnectivityMonitor connectivity;
    private final String appVersion;
    private final String launchId = UUID.randomUUID().toString();
    private final Set<BookKey> disabledBookKeys = new HashSet<>();

    private volatile SyncUiState uiState;
    private boolean foreground;
    private boolean networkAvailable;
    private boolean pullCompletedForLaunch;
    private boolean busy;
    private long lastAttemptAtMs;
    private long lastSuccessAtMs;
    private String lastFailureCode;
    private SyncAvailability availability = SyncAvailability.AVAILABLE;
    private ReaderSession session;
    private ScheduledFuture<?> periodicFuture;
    private ScheduledFuture<?> recoveryFuture;
    private int healthAttempt;
    private long sessionGeneration;

    ProgressSyncCoordinator(Context context, Handler mainHandler, Listener listener,
                            LocalProgressStore localStore, RemoteProgressStore remoteStore,
                            BookHashCache hashCache, SyncTokenStore tokenStore,
                            SyncServerConfig serverConfig, SyncApiClient api, String appVersion) {
        this.mainHandler = mainHandler;
        this.listener = listener;
        this.localStore = localStore;
        this.remoteStore = remoteStore;
        this.hashCache = hashCache;
        this.tokenStore = tokenStore;
        this.serverConfig = serverConfig;
        this.api = api;
        this.appVersion = appVersion;
        this.connectivity = new ConnectivityMonitor(context, available -> {
            try {
                serial.execute(() -> handleConnectivity(available));
            } catch (RejectedExecutionException ignored) {
                // Activity teardown won the race with a final network callback.
            }
        });
        remoteStore.open(tokenStore.email());
        networkAvailable = true;
        publishState();
    }

    void start() {
        connectivity.start();
    }

    void onForeground() {
        serial.execute(() -> {
            foreground = true;
            if (!tokenStore.enabled()) {
                publishState();
                return;
            }
            if (!networkAvailable) {
                availability = SyncAvailability.OFFLINE;
                publishState();
                return;
            }
            if (availability == SyncAvailability.TOKEN_REQUIRED) {
                publishState();
                return;
            }
            pullRemote(false);
        });
    }

    void onBackground() {
        serial.execute(() -> {
            cancelPeriodic();
            syncLatest(true);
            foreground = false;
            publishState();
        });
    }

    void shutdown() {
        connectivity.stop();
        serial.shutdownNow();
        hashExecutor.shutdownNow();
    }

    void openBook(long localBookId, File file, long fileSize, long offset, long readAtMs) {
        serial.execute(() -> {
            long generation = ++sessionGeneration;
            cancelPeriodic();
            localStore.seed(localBookId, fileSize, offset, readAtMs);
            session = new ReaderSession(UUID.randomUUID().toString(), localBookId, file,
                    ReaderComparisonState.PENDING);
            session.active = true;
            if (!tokenStore.enabled()) {
                publishState();
                return;
            }
            resolveHash(generation, localBookId, file);
        });
    }

    void closeBook() {
        serial.execute(() -> {
            sessionGeneration++;
            session = null;
            cancelPeriodic();
        });
    }

    void setReaderActive(boolean active, boolean temporarySearchReading) {
        serial.execute(() -> {
            if (session == null) return;
            session.active = active;
            session.temporarySearchReading = temporarySearchReading;
            if (active && !temporarySearchReading) schedulePeriodicIfAllowed();
            else cancelPeriodic();
        });
    }

    void onLocalProgressChanged(long localBookId, long fileSize, long offset, long readAtMs) {
        serial.execute(() -> localStore.updatePosition(localBookId, fileSize, offset, readAtMs));
    }

    void onJumpDeclined(String sessionId) {
        serial.execute(() -> completeJumpDecision(sessionId));
    }

    void onRemoteJumpApplied(String sessionId, long fileSize, long offset, long readAtMs) {
        serial.execute(() -> {
            if (session == null || !session.sessionId.equals(sessionId)) return;
            localStore.updatePosition(session.localBookId, fileSize, offset, readAtMs);
            completeJumpDecision(sessionId);
        });
    }

    void startSync(String email, String deviceName, ActionCallback<Void> callback) {
        serial.execute(() -> {
            String normalized = SyncTokenStore.normalizeEmail(email);
            if (!isValidEmail(normalized)) {
                deliver(callback, SyncActionResult.failure("INVALID_EMAIL"));
                return;
            }
            String normalizedDeviceName = SyncTokenStore.normalizeDeviceName(deviceName);
            if (!SyncTokenStore.isValidDeviceName(normalizedDeviceName)) {
                deliver(callback, SyncActionResult.failure("INVALID_DEVICE_NAME"));
                return;
            }
            if (!api.configured()) {
                availability = SyncAvailability.SERVICE_UNAVAILABLE;
                lastFailureCode = "SERVICE_NOT_CONFIGURED";
                publishState();
                deliver(callback, SyncActionResult.failure(lastFailureCode));
                return;
            }
            if (!networkAvailable) {
                availability = SyncAvailability.OFFLINE;
                publishState();
                deliver(callback, SyncActionResult.failure("OFFLINE"));
                return;
            }
            busy = true;
            publishState();
            try {
                lastAttemptAtMs = System.currentTimeMillis();
                SyncApiClient.StartSyncResponse response = api.startSync(normalized,
                        tokenStore.deviceId(), normalizedDeviceName, appVersion);
                String previousEmail = tokenStore.email();
                tokenStore.saveDeviceName(normalizedDeviceName);
                tokenStore.save(response.email, response.token);
                if (!response.email.equals(previousEmail)) remoteStore.clear();
                remoteStore.open(response.email);
                pullCompletedForLaunch = false;
                disabledBookKeys.clear();
                if (session != null) {
                    session.comparisonState = ReaderComparisonState.PENDING;
                    if (session.bookKey == null) {
                        long generation = ++sessionGeneration;
                        resolveHash(generation, session.localBookId, session.file);
                    }
                }
                availability = SyncAvailability.AVAILABLE;
                pullRemote(false);
                deliver(callback, SyncActionResult.success(null));
            } catch (Exception error) {
                handleFailure(error, null);
                deliver(callback, SyncActionResult.failure(errorCode(error)));
            } finally {
                busy = false;
                publishState();
            }
        });
    }

    String deviceName() {
        return tokenStore.deviceName();
    }

    void disableSync(ActionCallback<Void> callback) {
        serial.execute(() -> {
            tokenStore.clear();
            remoteStore.clear();
            pullCompletedForLaunch = false;
            disabledBookKeys.clear();
            cancelPeriodic();
            cancelRecovery();
            if (session != null) session.comparisonState = ReaderComparisonState.PENDING;
            availability = SyncAvailability.AVAILABLE;
            lastFailureCode = null;
            publishState();
            deliver(callback, SyncActionResult.success(null));
        });
    }

    void refreshRemote(ActionCallback<Void> callback) {
        serial.execute(() -> {
            if (!canCallBusinessApi()) {
                deliver(callback, SyncActionResult.failure(stateErrorCode()));
                return;
            }
            boolean success = pullRemote(false);
            deliver(callback, success ? SyncActionResult.success(null)
                    : SyncActionResult.failure(lastFailureCode == null
                            ? "REFRESH_FAILED" : lastFailureCode));
        });
    }

    void saveServerUrl(String serverUrl, ActionCallback<Void> callback) {
        serial.execute(() -> {
            String previousUrl = serverConfig.url();
            if (!serverConfig.save(serverUrl)) {
                deliver(callback, SyncActionResult.failure("INVALID_SERVER_URL"));
                return;
            }
            api.setBaseUrl(serverConfig.url());
            boolean changed = !previousUrl.equals(serverConfig.url());
            pullCompletedForLaunch = false;
            cancelPeriodic();
            cancelRecovery();
            if (changed) {
                tokenStore.clear();
                remoteStore.clear();
                disabledBookKeys.clear();
                availability = SyncAvailability.AVAILABLE;
                lastFailureCode = null;
                if (session != null) session.comparisonState = ReaderComparisonState.PENDING;
                publishState();
                deliver(callback, SyncActionResult.success(null));
                return;
            }
            boolean tokenRequired = availability == SyncAvailability.TOKEN_REQUIRED;
            if (!tokenRequired) {
                availability = networkAvailable ? SyncAvailability.AVAILABLE
                        : SyncAvailability.OFFLINE;
                lastFailureCode = null;
            }
            if (session != null) session.comparisonState = ReaderComparisonState.PENDING;
            publishState();
            if (tokenStore.enabled() && networkAvailable && !tokenRequired) pullRemote(false);
            deliver(callback, SyncActionResult.success(null));
        });
    }

    String serverUrl() {
        return serverConfig.url();
    }

    void loadDevices(ActionCallback<List<SyncDevice>> callback) {
        serial.execute(() -> {
            if (!canCallBusinessApi()) {
                deliver(callback, SyncActionResult.failure(stateErrorCode()));
                return;
            }
            try {
                lastAttemptAtMs = System.currentTimeMillis();
                List<SyncDevice> devices = api.listDevices(tokenStore.token(),
                        tokenStore.deviceId());
                markSuccess();
                deliver(callback, SyncActionResult.success(devices));
            } catch (Exception error) {
                handleFailure(error, null);
                deliver(callback, SyncActionResult.failure(errorCode(error)));
            }
        });
    }

    void revokeDevice(String targetDeviceId, ActionCallback<Void> callback) {
        serial.execute(() -> {
            if (targetDeviceId.equals(tokenStore.deviceId())) {
                deliver(callback, SyncActionResult.failure("CURRENT_DEVICE"));
                return;
            }
            if (!canCallBusinessApi()) {
                deliver(callback, SyncActionResult.failure(stateErrorCode()));
                return;
            }
            try {
                lastAttemptAtMs = System.currentTimeMillis();
                api.revokeDevice(tokenStore.token(), tokenStore.deviceId(), targetDeviceId);
                markSuccess();
                deliver(callback, SyncActionResult.success(null));
            } catch (Exception error) {
                handleFailure(error, null);
                deliver(callback, SyncActionResult.failure(errorCode(error)));
            }
        });
    }

    void deleteRemoteProgress(ActionCallback<Void> callback) {
        serial.execute(() -> {
            if (!canCallBusinessApi()) {
                deliver(callback, SyncActionResult.failure(stateErrorCode()));
                return;
            }
            try {
                lastAttemptAtMs = System.currentTimeMillis();
                api.deleteProgress(tokenStore.token(), tokenStore.deviceId());
                remoteStore.replaceAll(tokenStore.email(), Collections.emptyList(), launchId);
                markSuccess();
                deliver(callback, SyncActionResult.success(null));
            } catch (Exception error) {
                handleFailure(error, null);
                deliver(callback, SyncActionResult.failure(errorCode(error)));
            }
        });
    }

    SyncUiState state() {
        return uiState;
    }

    private void resolveHash(long generation, long localBookId, File file) {
        hashExecutor.execute(() -> {
            BookHashCache.HashResult result = null;
            try {
                result = hashCache.resolve(localBookId, file);
            } catch (Exception ignored) {
                // Hash failure disables only this book for the current session.
            }
            BookHashCache.HashResult finalResult = result;
            try {
                serial.execute(() -> {
                    if (generation != sessionGeneration || session == null
                        || session.localBookId != localBookId) return;
                if (finalResult == null || finalResult.fileSize <= 0L) {
                        session.comparisonState = ReaderComparisonState.UNAVAILABLE;
                        return;
                    }
                    LocalProgressSnapshot local = localStore.setIdentity(localBookId,
                        finalResult.bookHash, finalResult.fileSize);
                    session.bookKey = local == null ? null : local.bookKey();
                    if (session.bookKey == null || disabledBookKeys.contains(session.bookKey)) return;
                    if (!tokenStore.enabled()) return;
                    if (!networkAvailable) {
                        session.comparisonState = ReaderComparisonState.UNAVAILABLE;
                        return;
                    }
                    if (pullCompletedForLaunch) compareCurrentBook();
                    else pullRemote(false);
                });
            } catch (RejectedExecutionException ignored) {
                // Coordinator was shut down while hashing.
            }
        });
    }

    private boolean pullRemote(boolean syncAfterPull) {
        if (!tokenStore.enabled()) return false;
        if (!api.configured()) {
            availability = SyncAvailability.SERVICE_UNAVAILABLE;
            lastFailureCode = "SERVICE_NOT_CONFIGURED";
            if (session != null && session.comparisonState != ReaderComparisonState.COMPLETED) {
                session.comparisonState = ReaderComparisonState.UNAVAILABLE;
            }
            publishState();
            return false;
        }
        if (!networkAvailable) {
            availability = SyncAvailability.OFFLINE;
            if (session != null && session.comparisonState != ReaderComparisonState.COMPLETED) {
                session.comparisonState = ReaderComparisonState.UNAVAILABLE;
            }
            publishState();
            return false;
        }
        busy = true;
        publishState();
        try {
            lastAttemptAtMs = System.currentTimeMillis();
            List<RemoteProgressSnapshot> items = api.pullProgress(tokenStore.token(),
                    tokenStore.deviceId());
            remoteStore.replaceAll(tokenStore.email(), items, launchId);
            pullCompletedForLaunch = true;
            markSuccess();
            healthAttempt = 0;
            cancelRecovery();
            compareCurrentBook();
            if (syncAfterPull) syncLatest(true);
            schedulePeriodicIfAllowed();
            return true;
        } catch (Exception error) {
            if (session != null && session.comparisonState != ReaderComparisonState.COMPLETED) {
                session.comparisonState = ReaderComparisonState.UNAVAILABLE;
            }
            handleFailure(error, null);
            return false;
        } finally {
            busy = false;
            publishState();
        }
    }

    private void compareCurrentBook() {
        if (session == null || session.bookKey == null
                || session.comparisonState == ReaderComparisonState.COMPLETED
                || session.comparisonState == ReaderComparisonState.AWAITING_JUMP_DECISION) {
            return;
        }
        LocalProgressSnapshot local = localStore.get(session.localBookId);
        RemoteProgressSnapshot remote = remoteStore.get(session.bookKey);
        if (SyncRules.shouldPrompt(local, remote, tokenStore.deviceId(), launchId,
                session.promptedRemoteVersion, session.temporarySearchReading)) {
            session.comparisonState = ReaderComparisonState.AWAITING_JUMP_DECISION;
            session.promptedRemoteVersion = remote.version;
            String sessionId = session.sessionId;
            long localBookId = session.localBookId;
            mainHandler.post(() -> listener.onRemoteJumpAvailable(sessionId, localBookId, remote));
            cancelPeriodic();
            return;
        }
        completeComparison();
    }

    private void completeJumpDecision(String sessionId) {
        if (session == null || !session.sessionId.equals(sessionId)
                || session.comparisonState != ReaderComparisonState.AWAITING_JUMP_DECISION) return;
        completeComparison();
    }

    private void completeComparison() {
        if (session == null) return;
        session.comparisonState = ReaderComparisonState.COMPLETED;
        LocalProgressSnapshot current = localStore.get(session.localBookId);
        session.uploadTracker.baseline(current == null ? 0L : current.localSequence);
        schedulePeriodicIfAllowed();
    }

    private void schedulePeriodicIfAllowed() {
        cancelPeriodic();
        if (!canSyncCurrentSession()) return;
        periodicFuture = serial.scheduleWithFixedDelay(() -> syncLatest(false), PERIOD_MS,
                PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void syncLatest(boolean forceLatest) {
        if (!canSyncCurrentSession()) return;
        LocalProgressSnapshot snapshot = localStore.get(session.localBookId);
        if (snapshot == null || snapshot.bookHash == null
                || disabledBookKeys.contains(snapshot.bookKey())) return;
        if (!session.uploadTracker.observeForRequest(snapshot.localSequence, forceLatest)) return;
        lastAttemptAtMs = System.currentTimeMillis();
        try {
            RemoteProgressSnapshot finalState = api.syncProgress(tokenStore.token(),
                    tokenStore.deviceId(), snapshot);
            remoteStore.put(tokenStore.email(), finalState, launchId);
            markSuccess();
        } catch (Exception error) {
            handleFailure(error, snapshot.bookKey());
        }
    }

    private boolean canSyncCurrentSession() {
        return foreground && session != null && session.active
                && !session.temporarySearchReading
                && session.comparisonState == ReaderComparisonState.COMPLETED
                && session.bookKey != null && canCallBusinessApi();
    }

    private boolean canCallBusinessApi() {
        return tokenStore.enabled() && api.configured() && networkAvailable
                && availability == SyncAvailability.AVAILABLE;
    }

    private void handleConnectivity(boolean available) {
        boolean recovered = !networkAvailable && available;
        networkAvailable = available;
        if (!available) {
            availability = SyncAvailability.OFFLINE;
            cancelPeriodic();
            cancelRecovery();
            if (session != null && session.comparisonState != ReaderComparisonState.COMPLETED) {
                session.comparisonState = ReaderComparisonState.UNAVAILABLE;
            }
            publishState();
            return;
        }
        if (!tokenStore.enabled() || availability == SyncAvailability.TOKEN_REQUIRED) {
            publishState();
            return;
        }
        if (recovered) pullRemote(true);
        else {
            availability = SyncAvailability.AVAILABLE;
            schedulePeriodicIfAllowed();
            publishState();
        }
    }

    private void handleFailure(Exception error, BookKey affectedBook) {
        lastFailureCode = errorCode(error);
        if (error instanceof SyncApiClient.ApiException) {
            SyncApiClient.ApiException apiError = (SyncApiClient.ApiException) error;
            if (apiError.status == 401) {
                availability = SyncAvailability.TOKEN_REQUIRED;
                cancelPeriodic();
                cancelRecovery();
            } else if (apiError.status == 429) {
                availability = SyncAvailability.SERVICE_UNAVAILABLE;
                cancelPeriodic();
                schedulePullRecovery(apiError.retryAfterMs);
            } else if (apiError.status == 500 || apiError.status == 503) {
                markServiceUnavailable();
            } else if (apiError.status == 403) {
                availability = SyncAvailability.SERVICE_UNAVAILABLE;
                cancelPeriodic();
                cancelRecovery();
            } else if (affectedBook != null && (apiError.status == 400
                    || apiError.status == 413 || apiError.status == 422)) {
                disabledBookKeys.add(affectedBook);
            }
        } else if (error instanceof IOException) {
            markServiceUnavailable();
        } else if (error instanceof SyncApiClient.ServiceNotConfiguredException) {
            availability = SyncAvailability.SERVICE_UNAVAILABLE;
            cancelPeriodic();
        }
        publishState();
    }

    private void markServiceUnavailable() {
        availability = SyncAvailability.SERVICE_UNAVAILABLE;
        cancelPeriodic();
        scheduleHealthProbe();
    }

    private void scheduleHealthProbe() {
        cancelRecovery();
        if (!foreground || !networkAvailable || !tokenStore.enabled()) return;
        long delay = SyncRules.healthBackoffMs(healthAttempt++);
        recoveryFuture = serial.schedule(() -> {
            try {
                api.health();
                healthAttempt = 0;
                availability = SyncAvailability.AVAILABLE;
                pullRemote(true);
            } catch (Exception error) {
                lastFailureCode = errorCode(error);
                scheduleHealthProbe();
                publishState();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void schedulePullRecovery(long delayMs) {
        cancelRecovery();
        if (!foreground || !networkAvailable || !tokenStore.enabled()) return;
        recoveryFuture = serial.schedule(() -> pullRemote(true), delayMs, TimeUnit.MILLISECONDS);
    }

    private void cancelPeriodic() {
        if (periodicFuture != null) periodicFuture.cancel(false);
        periodicFuture = null;
    }

    private void cancelRecovery() {
        if (recoveryFuture != null) recoveryFuture.cancel(false);
        recoveryFuture = null;
    }

    private void markSuccess() {
        availability = SyncAvailability.AVAILABLE;
        lastSuccessAtMs = System.currentTimeMillis();
        lastFailureCode = null;
        publishState();
    }

    private void publishState() {
        boolean enabled = tokenStore.enabled();
        SyncAvailability stateAvailability = enabled ? availability : SyncAvailability.AVAILABLE;
        SyncUiState state = new SyncUiState(enabled, api.configured(), tokenStore.email(),
                tokenStore.deviceName(), tokenStore.deviceId(), stateAvailability,
                lastAttemptAtMs, lastSuccessAtMs, lastFailureCode, busy);
        uiState = state;
        mainHandler.post(() -> listener.onSyncStateChanged(state));
    }

    private String stateErrorCode() {
        if (!tokenStore.enabled()) return "TOKEN_REQUIRED";
        if (!api.configured()) return "SERVICE_NOT_CONFIGURED";
        if (!networkAvailable) return "OFFLINE";
        return lastFailureCode == null ? "SERVICE_UNAVAILABLE" : lastFailureCode;
    }

    private static String errorCode(Exception error) {
        if (error instanceof SyncApiClient.ApiException) {
            return ((SyncApiClient.ApiException) error).code;
        }
        if (error instanceof SyncApiClient.ServiceNotConfiguredException) {
            return "SERVICE_NOT_CONFIGURED";
        }
        if (error instanceof SyncApiClient.ProtocolException) return "INVALID_RESPONSE";
        if (error instanceof IOException) return "CONNECTION_FAILED";
        return "SYNC_FAILED";
    }

    private <T> void deliver(ActionCallback<T> callback, SyncActionResult<T> result) {
        if (callback != null) mainHandler.post(() -> callback.onResult(result));
    }

    private static boolean isValidEmail(String email) {
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && at < email.length() - 3
                && email.indexOf('.', at) > at + 1 && email.length() <= 254;
    }

    private static final class ReaderSession {
        final String sessionId;
        final long localBookId;
        final File file;
        ReaderComparisonState comparisonState;
        BookKey bookKey;
        String promptedRemoteVersion;
        final ProgressUploadTracker uploadTracker = new ProgressUploadTracker();
        boolean temporarySearchReading;
        boolean active;

        ReaderSession(String sessionId, long localBookId, File file,
                      ReaderComparisonState comparisonState) {
            this.sessionId = sessionId;
            this.localBookId = localBookId;
            this.file = file;
            this.comparisonState = comparisonState;
        }
    }
}
