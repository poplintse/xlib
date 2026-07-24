package com.xlib.txtreader;

import java.math.BigInteger;

final class SyncRules {
    private static final BigInteger PROMPT_SCALE = BigInteger.valueOf(100_000L);

    private SyncRules() {
    }

    static long monotonicReadAt(long nowMs, long previousReadAtMs) {
        if (previousReadAtMs == Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(nowMs, previousReadAtMs + 1L);
    }

    static double progress(long offset, long fileSize) {
        if (fileSize <= 0L) return 0d;
        return Math.max(0d, Math.min(1d, offset / (double) fileSize));
    }

    static boolean exceedsPromptThreshold(long localOffset, long remoteOffset, long fileSize) {
        if (fileSize <= 0L) return false;
        long difference = Math.abs(remoteOffset - localOffset);
        return BigInteger.valueOf(difference).multiply(PROMPT_SCALE)
                .compareTo(BigInteger.valueOf(fileSize)) > 0;
    }

    static boolean shouldPrompt(LocalProgressSnapshot local, RemoteProgressSnapshot remote,
                                String currentDeviceId, String launchId,
                                String promptedRemoteVersion, boolean temporarySearchReading) {
        if (local == null || remote == null || temporarySearchReading) return false;
        if (local.bookHash == null || !local.bookHash.equals(remote.bookHash)
                || local.fileSize != remote.fileSize) return false;
        if (!launchId.equals(remote.freshForLaunchId)) return false;
        if (remote.readAtMs <= local.readAtMs) return false;
        if (currentDeviceId.equals(remote.sourceDeviceId)) return false;
        if (remote.version.equals(promptedRemoteVersion)) return false;
        return exceedsPromptThreshold(local.offset, remote.offset, local.fileSize);
    }

    static long healthBackoffMs(int attempt) {
        if (attempt <= 0) return 30_000L;
        if (attempt == 1) return 60_000L;
        if (attempt == 2) return 120_000L;
        return 300_000L;
    }
}
