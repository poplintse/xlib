package com.xlib.txtreader;

import android.content.Context;
import android.os.Handler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
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

    private final SyncExecution execution;
    private final SyncClock clock;
    private final ReadingProgressRecorder progress;
    private final SyncConfigurationSession configuration;
    private final Handler mainHandler;
    private final Listener listener;
    private final LocalProgressStore localStore;
    private final RemoteProgressStore remoteStore;
    private final BookHashCache hashCache;
    private final SyncTokenStore tokenStore;
    private final SyncServerConfig serverConfig;
    private final SyncTransport api;
    private final ConnectivityMonitor connectivity;
    private final String appVersion;
    private final String launchId = UUID.randomUUID().toString();
    private final Set<BookKey> disabledBookKeys = new HashSet<>();
    private final List<ActionCallback<List<SyncDevice>>> pendingDeviceCallbacks = new ArrayList<>();

    private volatile SyncUiState uiState;
    private volatile List<SyncDevice> cachedDevices = Collections.emptyList();
    private boolean deviceLoadInFlight;
    private volatile boolean foreground;
    private volatile boolean readerVisible;
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
    private volatile long preparingBookId = -1L;
    private volatile String currentPromptId;
    private volatile Object readerToken;
    private volatile Object promptReaderToken;
    private boolean registrationScheduled;

    boolean isPreparing(long bookId) { return preparingBookId == bookId; }
    boolean isCurrentPrompt(String id) {
        return id != null && id.equals(currentPromptId) && readerToken != null
                && readerToken == promptReaderToken;
    }

    private boolean isCurrentReaderSession() {
        return session != null && session.readerToken == readerToken;
    }

    void onPageReady(long bookId) {
        Object token = readerToken;
        execution.execute(() -> {
            if (!isCurrentReaderSession() || session.readerToken != token
                    || session.localBookId != bookId) return;
            session.phase.positionReady();
            updatePreparation();
            schedulePeriodicIfAllowed();
        });
    }

    private void updatePreparation() {
        if (!isCurrentReaderSession()) return;
        preparingBookId = !session.phase.canRead() ? session.localBookId : -1L;
        publishState();
    }

    private void allowOfflineReading() {
        if (session != null) {
            session.phase.offline();
            currentPromptId = null;
            updatePreparation();
        }
    }

    private void autoStartIfNeeded() {
        if (!foreground || !networkAvailable || !tokenStore.enabled() || !hasConfigurationChanged()
                || registrationScheduled || availability == SyncAvailability.TOKEN_REQUIRED) return;
        registrationScheduled = true;
        execution.execute(() -> {
            registrationScheduled = false;
            if (!foreground || !networkAvailable || !tokenStore.enabled()
                    || !hasConfigurationChanged()) return;
            startSyncNow(tokenStore.configuredEmail(), tokenStore.deviceName(), null);
        });
    }

    ProgressSyncCoordinator(Context context, Handler mainHandler, Listener listener,
                            LocalProgressStore localStore, RemoteProgressStore remoteStore,
                            BookHashCache hashCache, SyncTokenStore tokenStore,
                            SyncServerConfig serverConfig, SyncTransport api, String appVersion) {
        this(context, mainHandler, listener, localStore, remoteStore, hashCache, tokenStore,
                serverConfig, api, appVersion, System::currentTimeMillis, new SyncExecution());
    }

    ProgressSyncCoordinator(Context context, Handler mainHandler, Listener listener,
                            LocalProgressStore localStore, RemoteProgressStore remoteStore,
                            BookHashCache hashCache, SyncTokenStore tokenStore,
                            SyncServerConfig serverConfig, SyncTransport api, String appVersion,
                            SyncClock clock, SyncExecution execution) {
        this.clock = clock;
        this.execution = execution;
        this.progress = new ReadingProgressRecorder(localStore, clock);
        this.configuration = new SyncConfigurationSession(tokenStore, serverConfig, api);
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
                execution.execute(() -> handleConnectivity(available));
            } catch (RejectedExecutionException ignored) {
                // Activity teardown won the race with a final network callback.
            }
        });
        remoteStore.open(tokenStore.email());
        tokenStore.ensureActiveConfiguration(serverConfig.url());
        networkAvailable = true;
        publishState();
    }

    void start() {
        connectivity.start();
    }

    void onForeground() {
        execution.execute(() -> {
            foreground = true;
            autoStartIfNeeded();
            if (!tokenStore.enabled() || hasConfigurationChanged()) {
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
            pullRemote();
        });
    }

    void onBackground() {
        foreground = false;
        currentPromptId = null;
        execution.execute(() -> {
            cancelPeriodic();
            if (session != null && session.phase.awaitingChoice()) {
                session.phase.requireComparison();

                session.promptedRemoteVersion = null;
            }
            foreground = false;
            publishState();
        });
    }

    void shutdown() {
        connectivity.stop();
        execution.close();
    }

    void openBook(long localBookId, File file, long fileSize, long offset, long readAtMs) {
        Object token = new Object();
        readerToken = token;
        readerVisible = true;
        preparingBookId = localBookId;
        currentPromptId = null;
        execution.execute(() -> {
            long generation = ++sessionGeneration;
            cancelPeriodic();
            localStore.seed(localBookId, fileSize, offset, readAtMs);
            session = new ReaderSession(UUID.randomUUID().toString(), localBookId, file,
                    token);
            session.active = true;
            if (!tokenStore.enabled()) {
                session.phase.complete();
                updatePreparation();
                publishState();
                return;
            }
            resolveHash(generation, localBookId, file);
        });
    }

    void closeBook() {
        readerToken = null;
        readerVisible = false;
        preparingBookId = -1L;
        currentPromptId = null;
        execution.execute(() -> {
            sessionGeneration++;
            session = null;
            cancelPeriodic();
        });
    }

    void setReaderActive(boolean active, boolean temporarySearchReading) {
        readerVisible = active && !temporarySearchReading;
        if (!active || temporarySearchReading) currentPromptId = null;
        execution.execute(() -> {
            if (session == null) return;
            session.active = active;
            session.temporarySearchReading = temporarySearchReading;
            if (active && !temporarySearchReading) {
                if (session.bookKey != null && canCallBusinessApi()) pullRemote();
                else schedulePeriodicIfAllowed();
            }
            else {
                cancelPeriodic();
                if (session.phase.awaitingChoice()) {
                    session.phase.requireComparison();

                    session.promptedRemoteVersion = null;
                    publishState();
                }
            }
        });
    }

    boolean recordProgress(Book book, long offset, Long preservedReadAtMs) {
        return progress.record(book, offset, preservedReadAtMs, isPreparing(book.id));
    }

    void onLibrarySaved(List<Book> books, Book preserved, long offset, long readAtMs) {
        progress.saved(books, preserved, offset, readAtMs);
    }

    void onLocalProgressChanged(long localBookId, long fileSize, long offset, long readAtMs) {
        // A blocking pull must compare with changes made while that request was in flight.
        localStore.updatePosition(localBookId, fileSize, offset, readAtMs);
    }

    void onJumpDeclined(String sessionId) {
        execution.execute(() -> completeJumpDecision(sessionId));
    }

    void onRemoteJumpApplied(String sessionId, long fileSize, long offset, long readAtMs) {
        execution.execute(() -> {
            if (session == null || !isCurrentPrompt(sessionId)) return;
            session.phase.requirePosition();
            localStore.updatePosition(session.localBookId, fileSize, offset, readAtMs);
            completeJumpDecision(sessionId);
        });
    }

    void startSync(String email, String deviceName, ActionCallback<Void> callback) {
        execution.execute(() -> startSyncNow(email, deviceName, callback));
    }

    private void startSyncNow(String email, String deviceName, ActionCallback<Void> callback) {
        registrationScheduled = false;
        String normalized = SyncTokenStore.normalizeEmail(email);
        if (!SyncConfigurationSession.validEmail(normalized)) {
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
            lastAttemptAtMs = clock.nowMs();
            SyncApiClient.StartSyncResponse response = api.startSync(normalized,
                    tokenStore.deviceId(), normalizedDeviceName, appVersion);
            String previousEmail = tokenStore.email();
            configuration.registered(response.email, normalizedDeviceName, response.token);
            if (!response.email.equals(previousEmail)) remoteStore.clear();
            remoteStore.open(response.email);
            pullCompletedForLaunch = false;
            disabledBookKeys.clear();
            if (session != null) {
                session.promptedRemoteVersion = null;
                session.phase.requireComparison();
                if (session.bookKey == null) {
                    long generation = ++sessionGeneration;
                    resolveHash(generation, session.localBookId, session.file);
                }
            }
            availability = SyncAvailability.AVAILABLE;
            boolean refreshed = pullRemote();
            deliver(callback, refreshed ? SyncActionResult.success(null)
                    : SyncActionResult.failure(lastFailureCode == null
                            ? "REFRESH_FAILED" : lastFailureCode));
        } catch (Exception error) {
            handleFailure(error, null);
            deliver(callback, SyncActionResult.failure(errorCode(error)));
        } finally {
            busy = false;
            publishState();
        }
    }

    String deviceName() {
        return tokenStore.deviceName();
    }

    String configuredEmail() {
        return tokenStore.configuredEmail();
    }

    String deviceId() {
        return tokenStore.deviceId();
    }

    void saveConfiguredEmail(String email, ActionCallback<Void> callback) {
        execution.execute(() -> {
            String error = configuration.saveEmail(email);
            if (error != null) {
                deliver(callback, SyncActionResult.failure(error));
                return;
            }
            updateSyncForSavedConfiguration();
            publishState();
            deliver(callback, SyncActionResult.success(null));
        });
    }

    void saveDeviceName(String deviceName, ActionCallback<Void> callback) {
        execution.execute(() -> {
            String error = configuration.saveName(deviceName);
            if (error != null) {
                deliver(callback, SyncActionResult.failure(error));
                return;
            }
            updateSyncForSavedConfiguration();
            publishState();
            deliver(callback, SyncActionResult.success(null));
        });
    }

    SyncDevice localDevice(String configuredDeviceName) {
        String normalized = SyncTokenStore.normalizeDeviceName(configuredDeviceName);
        return new SyncDevice(tokenStore.deviceId(), normalized, "android",
                clock.nowMs(), false);
    }

    void disableSync(ActionCallback<Void> callback) {
        execution.execute(() -> {
            configuration.disable();
            currentPromptId = null;
            remoteStore.clear();
            pullCompletedForLaunch = false;
            disabledBookKeys.clear();
            cachedDevices = Collections.emptyList();
            cancelPeriodic();
            cancelRecovery();
            if (session != null) session.phase.complete();
            updatePreparation();
            availability = SyncAvailability.AVAILABLE;
            lastFailureCode = null;
            publishState();
            deliver(callback, SyncActionResult.success(null));
        });
    }

    void refreshRemote(ActionCallback<Void> callback) {
        execution.execute(() -> {
            if (!canCallBusinessApi()) {
                deliver(callback, SyncActionResult.failure(stateErrorCode()));
                return;
            }
            boolean success = pullRemote();
            deliver(callback, success ? SyncActionResult.success(null)
                    : SyncActionResult.failure(lastFailureCode == null
                            ? "REFRESH_FAILED" : lastFailureCode));
        });
    }

    void saveServerUrl(String serverUrl, ActionCallback<Void> callback) {
        execution.execute(() -> {
            String error = configuration.saveServer(serverUrl);
            if (error != null) {
                deliver(callback, SyncActionResult.failure(error));
                return;
            }
            updateSyncForSavedConfiguration();
            publishState();
            deliver(callback, SyncActionResult.success(null));
        });
    }

    void saveConfiguration(String email, String deviceName, String serverUrl,
                           ActionCallback<Void> callback) {
        execution.execute(() -> {
            String error = configuration.save(email, deviceName, serverUrl);
            if (error != null) {
                deliver(callback, SyncActionResult.failure(error));
                return;
            }
            updateSyncForSavedConfiguration();
            publishState();
            deliver(callback, SyncActionResult.success(null));
        });
    }

    String serverUrl() {
        return serverConfig.url();
    }

    void loadDevices(ActionCallback<List<SyncDevice>> callback) {
        execution.execute(() -> {
            if (callback != null) pendingDeviceCallbacks.add(callback);
            if (deviceLoadInFlight) return;
            if (!canCallBusinessApi()) {
                deliverPendingDeviceCallbacks(SyncActionResult.failure(stateErrorCode()));
                return;
            }
            deviceLoadInFlight = true;
            try {
                lastAttemptAtMs = clock.nowMs();
                List<SyncDevice> devices = api.listDevices(tokenStore.token(),
                        tokenStore.deviceId());
                cachedDevices = Collections.unmodifiableList(new ArrayList<>(devices));
                markSuccess();
                deliverPendingDeviceCallbacks(SyncActionResult.success(cachedDevices));
            } catch (Exception error) {
                handleFailure(error, null);
                deliverPendingDeviceCallbacks(SyncActionResult.failure(errorCode(error)));
            } finally {
                deviceLoadInFlight = false;
            }
        });
    }

    void preloadDevices() {
        if (tokenStore.enabled() && !hasConfigurationChanged()) loadDevices(null);
    }

    List<SyncDevice> cachedDevices() {
        return cachedDevices;
    }

    private void deliverPendingDeviceCallbacks(SyncActionResult<List<SyncDevice>> result) {
        List<ActionCallback<List<SyncDevice>>> callbacks =
                new ArrayList<>(pendingDeviceCallbacks);
        pendingDeviceCallbacks.clear();
        for (ActionCallback<List<SyncDevice>> callback : callbacks) deliver(callback, result);
    }

    void revokeDevice(String targetDeviceId, ActionCallback<Void> callback) {
        execution.execute(() -> {
            if (targetDeviceId.equals(tokenStore.deviceId())) {
                deliver(callback, SyncActionResult.failure("CURRENT_DEVICE"));
                return;
            }
            if (!canCallBusinessApi()) {
                deliver(callback, SyncActionResult.failure(stateErrorCode()));
                return;
            }
            try {
                lastAttemptAtMs = clock.nowMs();
                api.revokeDevice(tokenStore.token(), tokenStore.deviceId(), targetDeviceId);
                ArrayList<SyncDevice> remaining = new ArrayList<>();
                for (SyncDevice device : cachedDevices) {
                    if (!targetDeviceId.equals(device.deviceId)) remaining.add(device);
                }
                cachedDevices = Collections.unmodifiableList(remaining);
                markSuccess();
                deliver(callback, SyncActionResult.success(null));
            } catch (Exception error) {
                handleFailure(error, null);
                deliver(callback, SyncActionResult.failure(errorCode(error)));
            }
        });
    }

    void deleteBookProgress(long localBookId, File file, ActionCallback<Void> callback) {
        execution.execute(() -> {
            if (!canCallBusinessApi()) {
                deliver(callback, SyncActionResult.failure(stateErrorCode()));
                return;
            }
            long generation = configuration.generation();
            ReaderSession opening = session;
            boolean alreadyPaused = opening != null && opening.phase.uploadPaused();
            if (opening != null && opening.localBookId == localBookId) {
                opening.phase.pauseUpload(true);
                cancelPeriodic();
            }
            execution.hash(() -> {
                BookKey key = null;
                try {
                    BookHashCache.HashResult hash = hashCache.resolve(localBookId, file);
                    if (hash.fileSize > 0) key = new BookKey(hash.bookHash, hash.fileSize);
                } catch (Exception ignored) { }
                BookKey target = key;
                try {
                    execution.execute(() -> {
                        if (generation != configuration.generation() || !canCallBusinessApi() || target == null) {
                            if (session == opening && opening != null) opening.phase.pauseUpload(alreadyPaused);
                            schedulePeriodicIfAllowed();
                            deliver(callback, SyncActionResult.failure(target == null
                                    ? "INVALID_BOOK_IDENTITY" : "CONFIGURATION_CHANGED"));
                            return;
                        }
                        try {
                            // This executor also owns uploads: all previous uploads finish before DELETE.
                            api.deleteBookProgress(tokenStore.token(), tokenStore.deviceId(), target);
                            remoteStore.remove(target);
                            if (session != null && session.localBookId == localBookId) {
                                session.phase.pauseUpload(true);
                                cancelPeriodic();
                            }
                            markSuccess();
                            deliver(callback, SyncActionResult.success(null));
                        } catch (Exception error) {
                            if (session == opening && opening != null) opening.phase.pauseUpload(alreadyPaused);
                            handleFailure(error, target);
                            deliver(callback, SyncActionResult.failure(errorCode(error)));
                        }
                    });
                } catch (RejectedExecutionException ignored) { }
            });
        });
    }

    SyncUiState state() {
        return uiState;
    }

    private boolean hasConfigurationChanged() {
        return configuration.changed();
    }

    private void updateSyncForSavedConfiguration() {
        if (!hasConfigurationChanged()) {
            schedulePeriodicIfAllowed();
            return;
        }
        cancelPeriodic();
        cancelRecovery();
        configuration.invalidate();
        currentPromptId = null;
        remoteStore.clear();
        cachedDevices = Collections.emptyList();
        pullCompletedForLaunch = false;
        lastSuccessAtMs = 0L;
        lastFailureCode = null;
        availability = networkAvailable ? SyncAvailability.AVAILABLE : SyncAvailability.OFFLINE;
        if (session != null) {
            session.promptedRemoteVersion = null;
            session.phase.offline();
        }
        updatePreparation();
        autoStartIfNeeded();
    }

    private void resolveHash(long generation, long localBookId, File file) {
        execution.hash(() -> {
            BookHashCache.HashResult result = null;
            try {
                result = hashCache.resolve(localBookId, file);
            } catch (Exception ignored) {
                // Hash failure disables only this book for the current session.
            }
            BookHashCache.HashResult finalResult = result;
            try {
                execution.execute(() -> {
                    if (generation != sessionGeneration || !isCurrentReaderSession()
                        || session.localBookId != localBookId) return;
                    if (finalResult == null || finalResult.fileSize <= 0L) {
                        allowOfflineReading();
                        return;
                    }
                    LocalProgressSnapshot local = localStore.setIdentity(localBookId,
                        finalResult.bookHash, finalResult.fileSize);
                    session.bookKey = local == null ? null : local.bookKey();
                    if (session.bookKey == null || disabledBookKeys.contains(session.bookKey)) {
                        allowOfflineReading();
                        return;
                    }
                    if (!tokenStore.enabled() || hasConfigurationChanged()) {
                        allowOfflineReading();
                        autoStartIfNeeded();
                        return;
                    }
                    if (!networkAvailable) {
                        allowOfflineReading();
                        return;
                    }
                    pullRemote();
                });
            } catch (RejectedExecutionException ignored) {
                // Coordinator was shut down while hashing.
            }
        });
    }

    private boolean pullRemote() {
        if (!tokenStore.enabled()) return false;
        if (hasConfigurationChanged()) {
            allowOfflineReading();
            autoStartIfNeeded();
            cancelPeriodic();
            publishState();
            return false;
        }
        if (!api.configured()) {
            allowOfflineReading();
            availability = SyncAvailability.SERVICE_UNAVAILABLE;
            lastFailureCode = "SERVICE_NOT_CONFIGURED";
            publishState();
            return false;
        }
        if (!networkAvailable) {
            allowOfflineReading();
            availability = SyncAvailability.OFFLINE;
            publishState();
            return false;
        }
        busy = true;
        publishState();
        try {
            lastAttemptAtMs = clock.nowMs();
            List<RemoteProgressSnapshot> items = api.pullProgress(tokenStore.token(),
                    tokenStore.deviceId());
            remoteStore.replaceAll(tokenStore.email(), items, launchId);
            pullCompletedForLaunch = true;
            markSuccess();
            healthAttempt = 0;
            cancelRecovery();
            if (session != null && session.phase.comparison() != ReaderComparisonState.AWAITING_JUMP_DECISION) {
                session.phase.requireComparison();
            }
            compareCurrentBook();
            schedulePeriodicIfAllowed();
            return true;
        } catch (Exception error) {
            allowOfflineReading();
            handleFailure(error, null);
            return false;
        } finally {
            busy = false;
            publishState();
        }
    }

    private void compareCurrentBook() {
        if (!isCurrentReaderSession() || !readerVisible || !session.active || session.temporarySearchReading || !foreground
                || session.bookKey == null
                || session.phase.comparison() == ReaderComparisonState.COMPLETED
                || session.phase.comparison() == ReaderComparisonState.AWAITING_JUMP_DECISION) {
            return;
        }
        LocalProgressSnapshot local = localStore.get(session.localBookId);
        RemoteProgressSnapshot remote = remoteStore.get(session.bookKey);
        ReadingSyncPhase.ComparisonAction action = session.phase.compare(local, remote,
                tokenStore.deviceId(), launchId, session.promptedRemoteVersion, session.temporarySearchReading);
        if (action == ReadingSyncPhase.ComparisonAction.PROMPT) {
            updatePreparation();
            session.promptedRemoteVersion = remote.version;
            String sessionId = UUID.randomUUID().toString();
            promptReaderToken = session.readerToken;
            currentPromptId = sessionId;
            long localBookId = session.localBookId;
            mainHandler.post(() -> {
                if (isCurrentPrompt(sessionId)) listener.onRemoteJumpAvailable(sessionId, localBookId, remote);
            });
            cancelPeriodic();
            return;
        }
        boolean uploadLocal = action == ReadingSyncPhase.ComparisonAction.UPLOAD;
        completeComparison();
        if (uploadLocal) syncLatest(true);
    }

    private void completeJumpDecision(String sessionId) {
        if (session == null || !isCurrentPrompt(sessionId)
                || session.phase.comparison() != ReaderComparisonState.AWAITING_JUMP_DECISION) return;
        completeComparison();
    }

    private void completeComparison() {
        if (session == null) return;
        currentPromptId = null;
        session.phase.complete();
        updatePreparation();
        LocalProgressSnapshot current = localStore.get(session.localBookId);
        session.uploadTracker.baseline(current == null ? 0L : current.localSequence);
        schedulePeriodicIfAllowed();
    }

    private void schedulePeriodicIfAllowed() {
        cancelPeriodic();
        if (!canSyncCurrentSession()) return;
        periodicFuture = execution.periodically(this::pullRemote, PERIOD_MS);
    }

    private void syncLatest(boolean forceLatest) {
        if (!canSyncCurrentSession()) return;
        LocalProgressSnapshot snapshot = localStore.get(session.localBookId);
        if (snapshot == null || snapshot.bookHash == null
                || disabledBookKeys.contains(snapshot.bookKey())) return;
        if (!session.uploadTracker.observeForRequest(snapshot.localSequence, forceLatest)) return;
        lastAttemptAtMs = clock.nowMs();
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
        return foreground && readerVisible && isCurrentReaderSession() && session.active
                && !session.temporarySearchReading
                && session.phase.comparison() == ReaderComparisonState.COMPLETED
                && session.phase.canUpload()
                && session.bookKey != null && !disabledBookKeys.contains(session.bookKey)
                && canCallBusinessApi();
    }

    private boolean canCallBusinessApi() {
        return tokenStore.enabled() && api.configured() && networkAvailable
                && availability == SyncAvailability.AVAILABLE && !hasConfigurationChanged();
    }

    private void handleConnectivity(boolean available) {
        boolean recovered = !networkAvailable && available;
        networkAvailable = available;
        if (!available) {
            allowOfflineReading();
            availability = SyncAvailability.OFFLINE;
            cancelPeriodic();
            cancelRecovery();
            publishState();
            return;
        }
        autoStartIfNeeded();
        if (!tokenStore.enabled() || hasConfigurationChanged()
                || availability == SyncAvailability.TOKEN_REQUIRED) {
            publishState();
            return;
        }
        if (recovered) pullRemote();
        else {
            availability = SyncAvailability.AVAILABLE;
            schedulePeriodicIfAllowed();
            publishState();
        }
    }

    private void handleFailure(Exception error, BookKey affectedBook) {
        allowOfflineReading();
        lastFailureCode = errorCode(error);
        if (error instanceof SyncApiClient.ApiException) {
            SyncApiClient.ApiException apiError = (SyncApiClient.ApiException) error;
            if (apiError.status == 401 || (apiError.status == 403
                    && "DEVICE_FORBIDDEN".equals(apiError.code))) {
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
        if (!foreground || !networkAvailable || !tokenStore.enabled()
                || availability == SyncAvailability.TOKEN_REQUIRED) return;
        long delay = SyncRules.healthBackoffMs(healthAttempt++);
        recoveryFuture = execution.after(() -> {
            try {
                api.health();
                healthAttempt = 0;
                availability = SyncAvailability.AVAILABLE;
                if (hasConfigurationChanged()) autoStartIfNeeded();
                else pullRemote();
            } catch (Exception error) {
                lastFailureCode = errorCode(error);
                scheduleHealthProbe();
                publishState();
            }
        }, delay);
    }

    private void schedulePullRecovery(long delayMs) {
        cancelRecovery();
        if (!foreground || !networkAvailable || !tokenStore.enabled()
                || availability == SyncAvailability.TOKEN_REQUIRED) return;
        recoveryFuture = execution.after(() -> {
            if (hasConfigurationChanged()) autoStartIfNeeded();
            else pullRemote();
        }, delayMs);
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
        lastSuccessAtMs = clock.nowMs();
        lastFailureCode = null;
        publishState();
    }

    private void publishState() {
        long generation = configuration.generation();
        boolean enabled = tokenStore.enabled();
        boolean configurationChanged = enabled && hasConfigurationChanged();
        SyncAvailability stateAvailability = enabled ? availability : SyncAvailability.AVAILABLE;
        SyncUiState state = new SyncUiState(enabled, api.configured(), tokenStore.email(),
                tokenStore.deviceName(), tokenStore.deviceId(), stateAvailability,
                lastAttemptAtMs, lastSuccessAtMs, lastFailureCode, configurationChanged, busy);
        uiState = state;
        mainHandler.post(() -> {
            if (generation == configuration.generation()) listener.onSyncStateChanged(state);
        });
    }

    private String stateErrorCode() {
        if (!tokenStore.enabled()) return "TOKEN_REQUIRED";
        if (hasConfigurationChanged()) return "CONFIGURATION_CHANGED";
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
        long generation = configuration.generation();
        if (callback != null) mainHandler.post(() -> {
            if (generation == configuration.generation()) callback.onResult(result);
        });
    }

    static boolean isConfigurationComplete(String email, String deviceName, String serverUrl) {
        return SyncConfigurationSession.complete(email, deviceName, serverUrl);
    }

    private static final class ReaderSession {
        final Object readerToken;
        final String sessionId;
        final long localBookId;
        final File file;
        BookKey bookKey;
        String promptedRemoteVersion;
        final ProgressUploadTracker uploadTracker = new ProgressUploadTracker();
        final ReadingSyncPhase phase = new ReadingSyncPhase();
        boolean temporarySearchReading;
        boolean active;

        ReaderSession(String sessionId, long localBookId, File file,
                      Object readerToken) {
            this.readerToken = readerToken;
            this.sessionId = sessionId;
            this.localBookId = localBookId;
            this.file = file;
        }
    }
}
