package com.xlib.txtreader;

/** Per-open-book gates; rebuilding credentials must never reset deletion pause. */
final class ReadingSyncPhase {
    boolean positioned;
    boolean comparisonComplete;
    boolean offlineReading;
    boolean awaitingChoice;
    boolean uploadPaused;

    boolean canRead() { return positioned && !awaitingChoice && (comparisonComplete || offlineReading); }
    boolean canUpload() { return positioned && comparisonComplete && !awaitingChoice && !uploadPaused; }
    void requireComparison() { comparisonComplete = false; awaitingChoice = false; }
    void offline() { requireComparison(); offlineReading = true; }
    void complete() { comparisonComplete = true; offlineReading = false; awaitingChoice = false; }
}
