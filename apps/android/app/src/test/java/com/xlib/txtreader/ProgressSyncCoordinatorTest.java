package com.xlib.txtreader;

import android.content.Context;
import android.os.Handler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class ProgressSyncCoordinatorTest {
    private static final String HASH = "a".repeat(64);
    private ProgressSyncCoordinator coordinator;
    private SyncTransport api;
    private LocalProgressStore locals;
    private SyncTokenStore tokens;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> prompt = new AtomicReference<>();
    private final AtomicReference<String> credential = new AtomicReference<>("old-token");
    private final AtomicReference<String> email = new AtomicReference<>("one@example.com");
    private final AtomicBoolean matches = new AtomicBoolean(true);
    private final AtomicBoolean offline = new AtomicBoolean();
    private final AtomicReference<List<RemoteProgressSnapshot>> remote = new AtomicReference<>(List.of());

    @Before public void setup() throws Exception {
        Handler handler = mock(Handler.class);
        when(handler.post(any())).thenAnswer(a -> { ((Runnable) a.getArgument(0)).run(); return true; });
        tokens = mock(SyncTokenStore.class);
        when(tokens.enabled()).thenReturn(true);
        when(tokens.token()).thenAnswer(a -> credential.get());
        when(tokens.email()).thenAnswer(a -> email.get());
        when(tokens.configuredEmail()).thenAnswer(a -> email.get());
        when(tokens.deviceId()).thenReturn("device");
        when(tokens.deviceName()).thenReturn("Android");
        when(tokens.activeConfigurationMatches(anyString())).thenAnswer(a -> matches.get());
        doAnswer(a -> { email.set(a.getArgument(0)); matches.set(false); return null; })
                .when(tokens).saveConfiguredEmail(anyString());
        doAnswer(a -> { credential.set(null); return null; }).when(tokens).invalidateCredentials();
        doAnswer(a -> { credential.set(a.getArgument(1)); return null; }).when(tokens).save(anyString(), anyString());
        doAnswer(a -> { matches.set(true); return null; }).when(tokens).saveActiveConfiguration(anyString(), anyString(), anyString());
        BookHashCache hash = mock(BookHashCache.class);
        when(hash.resolve(anyLong(), any())).thenReturn(new BookHashCache.HashResult(HASH, 1000, 1));
        api = mock(SyncTransport.class);
        when(api.configured()).thenReturn(true);
        when(api.startSync(anyString(), anyString(), anyString(), anyString())).thenAnswer(a -> {
            calls.add("register");
            return new SyncApiClient.StartSyncResponse("new-token", a.getArgument(0));
        });
        when(api.pullProgress(any(), anyString())).thenAnswer(a -> {
            calls.add("pull");
            if (offline.get()) throw new IOException("offline");
            return remote.get();
        });
        when(api.syncProgress(any(), anyString(), any())).thenAnswer(a -> {
            LocalProgressSnapshot local = a.getArgument(2);
            calls.add("upload:" + local.offset + ":" + local.readAtMs);
            return progress(local.offset, local.readAtMs);
        });
        doAnswer(a -> { calls.add("delete"); return null; }).when(api).deleteBookProgress(any(), anyString(), any());
        locals = new LocalProgressStore();
        coordinator = new ProgressSyncCoordinator(mock(Context.class), handler,
                new ProgressSyncCoordinator.Listener() {
                    public void onSyncStateChanged(SyncUiState state) { }
                    public void onRemoteJumpAvailable(String id, long book, RemoteProgressSnapshot value) { prompt.set(id); }
                }, locals, new RemoteProgressStore(MemoryPreferences.create()), hash, tokens,
                new SyncServerConfig(MemoryPreferences.create()), api, "test", () -> 12345L, new SyncExecution());
        coordinator.onForeground();
        await(() -> calls.contains("pull"));
    }

    @After public void stop() { if (coordinator != null) coordinator.shutdown(); }

    private void open(long offset, long time) {
        coordinator.openBook(1, new File("test-book"), 1000, offset, time);
        coordinator.onPageReady(1);
    }

    private void refresh() throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        coordinator.refreshRemote(result -> done.set(true));
        await(done::get);
    }

    @Test public void injectedClockCommitsOnlyFormalMovementAfterComparison() throws Exception {
        remote.set(List.of(progress(600, 200)));
        open(200, 100);
        await(() -> prompt.get() != null);
        Book book = new Book(); book.id = 1; book.fileSize = 1000; book.offset = 200; book.updatedAt = 100;
        assertFalse(coordinator.recordProgress(book, 250, null));
        assertEquals(100, book.updatedAt);
        coordinator.onJumpDeclined(prompt.get());
        await(() -> !coordinator.isPreparing(1));
        assertTrue(coordinator.recordProgress(book, 250, null));
        assertEquals(12345, book.updatedAt);
        assertEquals(12345, locals.get(1).readAtMs);
        assertFalse(coordinator.recordProgress(book, 250, null));
        assertTrue(coordinator.recordProgress(book, 260, null));
        assertEquals(12346, book.updatedAt);
    }

    @Test public void preReadingWaitsForChoiceAndRemotePositioningWithoutChangingReadTime() throws Exception {
        remote.set(List.of(progress(600, 200)));
        open(200, 100);
        await(() -> prompt.get() != null);
        assertTrue(coordinator.isPreparing(1));
        assertEquals(100, locals.get(1).readAtMs);
        assertFalse(calls.stream().anyMatch(v -> v.startsWith("upload")));
        coordinator.onRemoteJumpApplied(prompt.get(), 1000, 600, 200);
        await(() -> locals.get(1).offset == 600);
        assertTrue(coordinator.isPreparing(1));
        coordinator.onPageReady(1);
        await(() -> !coordinator.isPreparing(1));
        assertEquals(200, locals.get(1).readAtMs);
    }

    @Test public void offlineRecoveryComparesLatestLocalAndDoesNotUploadAfterExit() throws Exception {
        offline.set(true);
        open(200, 100);
        await(() -> !coordinator.isPreparing(1));
        coordinator.onLocalProgressChanged(1, 1000, 250, 300);
        await(() -> locals.get(1).offset == 250);
        offline.set(false);
        remote.set(List.of(progress(600, 200)));
        coordinator.onForeground();
        await(() -> calls.contains("upload:250:300"));
        coordinator.closeBook();
        refresh();
        long count = calls.stream().filter(v -> v.startsWith("upload")).count();
        coordinator.onForeground();
        refresh();
        assertEquals(count, calls.stream().filter(v -> v.startsWith("upload")).count());
    }

    @Test public void deletePausesUploadsAcrossRefreshConfigurationAndResumeUntilReopen() throws Exception {
        open(200, 100);
        await(() -> !coordinator.isPreparing(1));
        AtomicBoolean deleted = new AtomicBoolean();
        coordinator.deleteBookProgress(1, new File("test-book"), result -> deleted.set(result.isSuccess()));
        await(deleted::get);
        calls.clear();
        coordinator.onLocalProgressChanged(1, 1000, 300, 400);
        refresh();
        coordinator.saveConfiguredEmail("two@example.com", result -> { });
        await(() -> calls.contains("register"));
        refresh();
        coordinator.onBackground();
        coordinator.onForeground();
        refresh();
        assertFalse(calls.stream().anyMatch(v -> v.startsWith("upload")));
        assertEquals(400, locals.get(1).readAtMs);
        coordinator.closeBook();
        open(300, 400);
        await(() -> calls.contains("upload:300:400"));
        verify(api, atLeastOnce()).pullProgress(eq("new-token"), eq("device"));
    }

    @Test public void firstConfigurationDoesNotStartSyncAndInvalidInputDoesNotChangeIt() throws Exception {
        when(tokens.enabled()).thenReturn(false);
        calls.clear();
        AtomicBoolean done = new AtomicBoolean();
        coordinator.saveConfiguredEmail("valid@example.com", result -> done.set(true));
        await(done::get);
        assertFalse(calls.contains("register"));
        done.set(false);
        coordinator.saveConfiguredEmail("invalid", result -> { assertFalse(result.isSuccess()); done.set(true); });
        await(done::get);
        assertEquals("valid@example.com", email.get());
    }

    private static RemoteProgressSnapshot progress(long offset, long time) {
        return new RemoteProgressSnapshot(HASH, 1000, offset, offset / 1000d, time,
                "version-" + time, "other-device", "Other", "android", 0, null);
    }

    @Test public void recoveryUsesMovementDuringInFlightPull() throws Exception {
        offline.set(true);
        open(200, 100);
        await(() -> !coordinator.isPreparing(1));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(a -> {
            entered.countDown();
            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return List.of(progress(600, 200));
        }).when(api).pullProgress(any(), anyString());
        coordinator.onForeground();
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        coordinator.onLocalProgressChanged(1, 1000, 250, 300);
        release.countDown();
        await(() -> calls.contains("upload:250:300"));
        assertNull(prompt.get());
    }

    @Test public void leavingReaderInvalidatesPromptAndReturningRequiresChoiceAgain() throws Exception {
        remote.set(List.of(progress(600, 200)));
        open(200, 100);
        await(() -> prompt.get() != null);
        String old = prompt.get();
        coordinator.setReaderActive(false, false);
        assertFalse(coordinator.isCurrentPrompt(old));
        coordinator.onRemoteJumpApplied(old, 1000, 600, 200);
        refresh();
        assertEquals(200, locals.get(1).offset);
        coordinator.setReaderActive(true, false);
        await(() -> !old.equals(prompt.get()));
        coordinator.onJumpDeclined(prompt.get());
        await(() -> !coordinator.isPreparing(1));
        assertEquals(100, locals.get(1).readAtMs);
        assertEquals(200, locals.get(1).offset);
    }

    @Test public void configurationInvalidatesOldChoiceAndNeverSendsOldTokenAfterRegistration() throws Exception {
        remote.set(List.of(progress(600, 200)));
        open(200, 100);
        await(() -> prompt.get() != null);
        String old = prompt.get();
        coordinator.saveConfiguredEmail("two@example.com", result -> { });
        await(() -> calls.contains("register"));
        coordinator.onRemoteJumpApplied(old, 1000, 600, 200);
        await(() -> !old.equals(prompt.get()));
        assertFalse(coordinator.isCurrentPrompt(old));
        assertEquals(200, locals.get(1).offset);
        assertEquals(100, locals.get(1).readAtMs);
        verify(api, atLeastOnce()).pullProgress(eq("new-token"), eq("device"));
    }
    @Test public void queuedConfigurationChangesRegisterOnlyLatestSavedEmail() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(a -> {
            entered.countDown();
            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return List.of();
        }).when(api).pullProgress(any(), anyString());
        coordinator.refreshRemote(result -> { });
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        coordinator.saveConfiguredEmail("intermediate@example.com", result -> { });
        coordinator.saveConfiguredEmail("latest@example.com", result -> { });
        release.countDown();
        await(() -> calls.contains("register"));
        refresh();
        verify(api, times(1)).startSync(eq("latest@example.com"), anyString(), anyString(), anyString());
        verify(api, never()).startSync(eq("intermediate@example.com"), anyString(), anyString(), anyString());
    }

    @Test public void deletionRunsAfterInFlightUploadAndPreventsRecreation() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(a -> {
            entered.countDown();
            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            calls.add("upload-finished");
            return progress(200, 100);
        }).when(api).syncProgress(any(), anyString(), any());
        open(200, 100);
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        AtomicBoolean deleted = new AtomicBoolean();
        coordinator.deleteBookProgress(1, new File("test-book"), result -> deleted.set(result.isSuccess()));
        assertFalse(calls.contains("delete"));
        release.countDown();
        await(deleted::get);
        assertTrue(calls.indexOf("upload-finished") < calls.indexOf("delete"));
        coordinator.onLocalProgressChanged(1, 1000, 300, 400);
        refresh();
        verify(api, times(1)).syncProgress(any(), anyString(), any());
    }

    @Test public void exitingDuringRecoveryPullDoesNotUploadOrPrompt() throws Exception {
        offline.set(true);
        open(200, 100);
        await(() -> !coordinator.isPreparing(1));
        coordinator.onLocalProgressChanged(1, 1000, 300, 400);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(a -> {
            entered.countDown();
            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return List.of(progress(600, 200));
        }).when(api).pullProgress(any(), anyString());
        coordinator.onForeground();
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        coordinator.closeBook();
        release.countDown();
        refresh();
        assertFalse(calls.stream().anyMatch(v -> v.startsWith("upload")));
        assertNull(prompt.get());
    }

    @Test public void unreadImportPromptsForCloudInsteadOfUploadingZero() throws Exception {
        remote.set(List.of(progress(600, 200)));
        open(0, 0);
        await(() -> prompt.get() != null);
        assertEquals(0, locals.get(1).readAtMs);
        coordinator.onJumpDeclined(prompt.get());
        await(() -> !coordinator.isPreparing(1));
        refresh();
        verify(api, never()).syncProgress(any(), anyString(), any());
    }

    @Test public void unreadImportWithoutCloudWaitsForActualMovement() throws Exception {
        open(0, 0);
        await(() -> !coordinator.isPreparing(1));
        refresh();
        verify(api, never()).syncProgress(any(), anyString(), any());
        coordinator.onLocalProgressChanged(1, 1000, 100, 300);
        refresh();
        assertTrue(calls.contains("upload:100:300"));
    }

    @Test public void revokedDeviceRequiresManualRestartNotAutomaticRegistration() throws Exception {
        doThrow(new SyncApiClient.ApiException(403, "DEVICE_FORBIDDEN", false, 0))
                .when(api).pullProgress(any(), anyString());
        refresh();
        assertEquals(SyncAvailability.TOKEN_REQUIRED, coordinator.state().availability);
        coordinator.onForeground();
        refresh();
        verify(api, never()).startSync(anyString(), anyString(), anyString(), anyString());
        doReturn(List.of()).when(api).pullProgress(any(), anyString());
        AtomicBoolean done = new AtomicBoolean();
        coordinator.startSync(email.get(), "Android", result -> done.set(result.isSuccess()));
        await(done::get);
        assertEquals(SyncAvailability.AVAILABLE, coordinator.state().availability);
        verify(api).pullProgress("new-token", "device");
    }

    @Test public void identityForbiddenIsNotTreatedAsDeviceReregistration() throws Exception {
        doThrow(new SyncApiClient.ApiException(403, "SYNC_UNAVAILABLE", false, 0))
                .when(api).pullProgress(any(), anyString());
        refresh();
        assertEquals(SyncAvailability.SERVICE_UNAVAILABLE, coordinator.state().availability);
        verify(api, never()).startSync(anyString(), anyString(), anyString(), anyString());
    }

    @Test public void rejectedBookRemainsLocallyReadableAfterReopening() throws Exception {
        doThrow(new SyncApiClient.ApiException(422, "INVALID_PROGRESS", false, 0))
                .when(api).syncProgress(any(), anyString(), any());
        open(200, 100);
        await(() -> "INVALID_PROGRESS".equals(coordinator.state().lastFailureCode));
        coordinator.closeBook();
        open(200, 100);
        await(() -> !coordinator.isPreparing(1));
        coordinator.onLocalProgressChanged(1, 1000, 300, 400);
        refresh();
        assertEquals(300, locals.get(1).offset);
        verify(api, times(1)).syncProgress(any(), anyString(), any());
    }

    @Test public void switchingBooksDuringPullNeverUploadsPreviousBook() throws Exception {
        assertSwitchDuringPull(2);
    }

    @Test public void reopeningSameBookDuringPullAlsoInvalidatesPreviousSession() throws Exception {
        assertSwitchDuringPull(1);
    }

    private void assertSwitchDuringPull(long nextBookId) throws Exception {
        assertSwitchDuringPull(nextBookId, false);
    }

    @Test public void switchingBooksDuringPullDoesNotDeliverPreviousBooksPrompt() throws Exception {
        assertSwitchDuringPull(2, true);
    }

    private void assertSwitchDuringPull(long nextBookId, boolean newerRemote) throws Exception {
        offline.set(true);
        open(200, 100);
        await(() -> !coordinator.isPreparing(1));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean firstPull = new AtomicBoolean(true);
        doAnswer(a -> {
            entered.countDown();
            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return firstPull.getAndSet(false) && newerRemote ? List.of(progress(600, 200)) : List.of();
        }).when(api).pullProgress(any(), anyString());
        coordinator.onForeground();
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        coordinator.closeBook();
        coordinator.openBook(nextBookId, new File("next-book"), 1000, 0, 0);
        coordinator.onPageReady(nextBookId);
        release.countDown();
        await(() -> !coordinator.isPreparing(nextBookId));
        refresh();
        verify(api, never()).syncProgress(any(), anyString(), any());
        assertNull(prompt.get());
    }

    interface Condition { boolean get() throws Exception; }
    private static void await(Condition condition) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.get() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue("asynchronous operation timed out", condition.get());
    }
}
