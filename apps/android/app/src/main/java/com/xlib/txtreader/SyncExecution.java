package com.xlib.txtreader;

import java.util.concurrent.*;

/** Owns serial API ordering, independent hash work and relative retry timers. */
final class SyncExecution {
    private final ScheduledExecutorService serial;
    private final ExecutorService hashes;
    SyncExecution() { this(Executors.newSingleThreadScheduledExecutor(), Executors.newSingleThreadExecutor()); }
    SyncExecution(ScheduledExecutorService serial, ExecutorService hashes) { this.serial = serial; this.hashes = hashes; }
    void execute(Runnable task) { serial.execute(task); }
    void hash(Runnable task) { hashes.execute(task); }
    ScheduledFuture<?> after(Runnable task, long delayMs) { return serial.schedule(task, delayMs, TimeUnit.MILLISECONDS); }
    ScheduledFuture<?> periodically(Runnable task, long periodMs) {
        return serial.scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }
    void close() { serial.shutdownNow(); hashes.shutdownNow(); }
}
