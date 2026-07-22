package com.xlib.txtreader;

import java.util.HashMap;
import java.util.Map;

final class LocalProgressStore {
    private final Map<Long, LocalProgressSnapshot> snapshots = new HashMap<>();
    private long sequence;

    synchronized LocalProgressSnapshot seed(long localBookId, long fileSize, long offset,
                                            long readAtMs) {
        LocalProgressSnapshot previous = snapshots.get(localBookId);
        String hash = previous == null ? null : previous.bookHash;
        long currentSequence = previous == null ? sequence : previous.localSequence;
        LocalProgressSnapshot snapshot = new LocalProgressSnapshot(localBookId, hash, fileSize,
                clampOffset(offset, fileSize), readAtMs, currentSequence);
        snapshots.put(localBookId, snapshot);
        return snapshot;
    }

    synchronized LocalProgressSnapshot setIdentity(long localBookId, String bookHash,
                                                   long fileSize) {
        LocalProgressSnapshot previous = snapshots.get(localBookId);
        if (previous == null) return null;
        LocalProgressSnapshot snapshot = new LocalProgressSnapshot(localBookId, bookHash, fileSize,
                clampOffset(previous.offset, fileSize), previous.readAtMs,
                previous.localSequence);
        snapshots.put(localBookId, snapshot);
        return snapshot;
    }

    synchronized LocalProgressSnapshot updatePosition(long localBookId, long fileSize,
                                                       long offset, long readAtMs) {
        LocalProgressSnapshot previous = snapshots.get(localBookId);
        String hash = previous == null ? null : previous.bookHash;
        long safeOffset = clampOffset(offset, fileSize);
        if (previous != null && previous.fileSize == fileSize && previous.offset == safeOffset
                && previous.readAtMs == readAtMs) {
            return previous;
        }
        LocalProgressSnapshot snapshot = new LocalProgressSnapshot(localBookId, hash, fileSize,
                safeOffset, readAtMs, ++sequence);
        snapshots.put(localBookId, snapshot);
        return snapshot;
    }

    synchronized LocalProgressSnapshot get(long localBookId) {
        return snapshots.get(localBookId);
    }

    private static long clampOffset(long offset, long fileSize) {
        return Math.max(0L, Math.min(offset, Math.max(0L, fileSize)));
    }
}
