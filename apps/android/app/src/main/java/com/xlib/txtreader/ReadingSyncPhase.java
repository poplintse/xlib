package com.xlib.txtreader;

/** Per-open-book rules. Credential rebuilds never reset the deletion pause. */
final class ReadingSyncPhase {
    enum ComparisonAction { PROMPT, UPLOAD, NONE }
    ComparisonAction compare(LocalProgressSnapshot local, RemoteProgressSnapshot remote,
                             String deviceId, String launchId, String promptedVersion, boolean temporary) {
        if (comparison == ReaderComparisonState.COMPLETED || awaitingChoice() || temporary) return ComparisonAction.NONE;
        if (SyncRules.shouldPrompt(local, remote, deviceId, launchId, promptedVersion, temporary)) {
            awaitChoice();
            return ComparisonAction.PROMPT;
        }
        return SyncRules.shouldUploadLocal(local, remote) ? ComparisonAction.UPLOAD : ComparisonAction.NONE;
    }
    private boolean positioned, offlineReading, uploadPaused;
    private ReaderComparisonState comparison = ReaderComparisonState.PENDING;
    ReaderComparisonState comparison() { return comparison; }
    boolean awaitingChoice() { return comparison == ReaderComparisonState.AWAITING_JUMP_DECISION; }
    boolean uploadPaused() { return uploadPaused; }
    void pauseUpload(boolean paused) { uploadPaused = paused; }
    void positionReady() { positioned = true; }
    void requirePosition() { positioned = false; }
    boolean canRead() { return positioned && !awaitingChoice() && (comparison == ReaderComparisonState.COMPLETED || offlineReading); }
    boolean canUpload() { return positioned && comparison == ReaderComparisonState.COMPLETED && !uploadPaused; }
    void requireComparison() { comparison = ReaderComparisonState.PENDING; }
    void awaitChoice() { comparison = ReaderComparisonState.AWAITING_JUMP_DECISION; }
    void offline() { comparison = ReaderComparisonState.UNAVAILABLE; offlineReading = true; }
    void complete() { comparison = ReaderComparisonState.COMPLETED; offlineReading = false; }
}
