package com.xlib.txtreader;

import java.util.Objects;

final class BookKey {
    final String bookHash;
    final long fileSize;

    BookKey(String bookHash, long fileSize) {
        this.bookHash = bookHash;
        this.fileSize = fileSize;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BookKey)) return false;
        BookKey key = (BookKey) other;
        return fileSize == key.fileSize && bookHash.equals(key.bookHash);
    }

    @Override public int hashCode() {
        return Objects.hash(bookHash, fileSize);
    }
}

final class LocalProgressSnapshot {
    final long localBookId;
    final String bookHash;
    final long fileSize;
    final long offset;
    final long readAtMs;
    final long localSequence;

    LocalProgressSnapshot(long localBookId, String bookHash, long fileSize, long offset,
                          long readAtMs, long localSequence) {
        this.localBookId = localBookId;
        this.bookHash = bookHash;
        this.fileSize = fileSize;
        this.offset = offset;
        this.readAtMs = readAtMs;
        this.localSequence = localSequence;
    }

    BookKey bookKey() {
        return bookHash == null ? null : new BookKey(bookHash, fileSize);
    }
}

final class RemoteProgressSnapshot {
    final String bookHash;
    final long fileSize;
    final long offset;
    final double progress;
    final long readAtMs;
    final String version;
    final String sourceDeviceId;
    final String sourceDeviceName;
    final String sourcePlatform;
    final long fetchedAtMs;
    final String freshForLaunchId;

    RemoteProgressSnapshot(String bookHash, long fileSize, long offset, double progress,
                           long readAtMs, String version, String sourceDeviceId,
                           String sourceDeviceName, String sourcePlatform, long fetchedAtMs,
                           String freshForLaunchId) {
        this.bookHash = bookHash;
        this.fileSize = fileSize;
        this.offset = offset;
        this.progress = progress;
        this.readAtMs = readAtMs;
        this.version = version;
        this.sourceDeviceId = sourceDeviceId;
        this.sourceDeviceName = sourceDeviceName;
        this.sourcePlatform = sourcePlatform;
        this.fetchedAtMs = fetchedAtMs;
        this.freshForLaunchId = freshForLaunchId;
    }

    BookKey bookKey() {
        return new BookKey(bookHash, fileSize);
    }
}

enum SyncAvailability {
    AVAILABLE,
    OFFLINE,
    SERVICE_UNAVAILABLE,
    TOKEN_REQUIRED
}

enum ReaderComparisonState {
    PENDING,
    AWAITING_JUMP_DECISION,
    COMPLETED,
    UNAVAILABLE
}

final class SyncDevice {
    final String deviceId;
    final String deviceName;
    final String platform;
    final long lastSeenAtMs;
    final boolean revoked;

    SyncDevice(String deviceId, String deviceName, String platform, long lastSeenAtMs,
               boolean revoked) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.platform = platform;
        this.lastSeenAtMs = lastSeenAtMs;
        this.revoked = revoked;
    }
}

final class SyncUiState {
    final boolean enabled;
    final boolean serviceConfigured;
    final String email;
    final String deviceName;
    final String deviceId;
    final SyncAvailability availability;
    final long lastAttemptAtMs;
    final long lastSuccessAtMs;
    final String lastFailureCode;
    final boolean busy;

    SyncUiState(boolean enabled, boolean serviceConfigured, String email, String deviceName,
                String deviceId, SyncAvailability availability, long lastAttemptAtMs,
                long lastSuccessAtMs, String lastFailureCode, boolean busy) {
        this.enabled = enabled;
        this.serviceConfigured = serviceConfigured;
        this.email = email;
        this.deviceName = deviceName;
        this.deviceId = deviceId;
        this.availability = availability;
        this.lastAttemptAtMs = lastAttemptAtMs;
        this.lastSuccessAtMs = lastSuccessAtMs;
        this.lastFailureCode = lastFailureCode;
        this.busy = busy;
    }
}

final class SyncActionResult<T> {
    final T value;
    final String errorCode;

    private SyncActionResult(T value, String errorCode) {
        this.value = value;
        this.errorCode = errorCode;
    }

    static <T> SyncActionResult<T> success(T value) {
        return new SyncActionResult<>(value, null);
    }

    static <T> SyncActionResult<T> failure(String errorCode) {
        return new SyncActionResult<>(null, errorCode);
    }

    boolean isSuccess() {
        return errorCode == null;
    }

}

final class ProgressUploadTracker {
    private long lastObservedSequence;

    void baseline(long sequence) {
        lastObservedSequence = sequence;
    }

    boolean observeForRequest(long sequence, boolean forceLatest) {
        if (!forceLatest && sequence == lastObservedSequence) return false;
        lastObservedSequence = sequence;
        return true;
    }

    long lastObservedSequence() {
        return lastObservedSequence;
    }
}
