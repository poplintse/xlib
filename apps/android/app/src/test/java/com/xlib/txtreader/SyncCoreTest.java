package com.xlib.txtreader;

import android.content.SharedPreferences;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SyncCoreTest {
    private Book book() {
        Book book = new Book(); book.id = 1; book.fileSize = 1000; book.offset = 200; book.updatedAt = 100;
        return book;
    }
    @Test public void formalRecorderPreservesRemoteTimeEvenDuringPreparation() {
        LocalProgressStore local = new LocalProgressStore();
        ReadingProgressRecorder recorder = new ReadingProgressRecorder(local, () -> 5000);
        Book book = book(); local.seed(1,1000,200,100);
        assertFalse(recorder.record(book,250,null,true)); assertEquals(100,local.get(1).readAtMs);
        assertTrue(recorder.record(book,600,200L,true)); assertEquals(200,book.updatedAt);
        assertEquals(600,local.get(1).offset); assertEquals(200,local.get(1).readAtMs);
        long sequence=local.get(1).localSequence;
        recorder.saved(List.of(book),null,0,0); assertEquals(sequence,local.get(1).localSequence);
        assertFalse(recorder.record(book,600,null,false)); assertEquals(200,book.updatedAt);
    }
    @Test public void pendingSaveDuringTemporaryReadingPublishesFormalSnapshot() {
        LocalProgressStore local=new LocalProgressStore(); ReadingProgressRecorder recorder=new ReadingProgressRecorder(local,()->5000);
        Book book=book(); recorder.record(book,250,null,false);
        long sequence=local.get(1).localSequence; book.offset=900; book.progress=.9f;
        recorder.saved(List.of(book),book,250,5000);
        assertEquals(250,local.get(1).offset); assertEquals(5000,local.get(1).readAtMs);
        assertEquals(sequence,local.get(1).localSequence);
        recorder.saved(List.of(book),null,0,0); assertEquals(250,local.get(1).offset);
    }
    @Test public void invalidConfigurationNeverPartiallyWritesOrChangesTransport() {
        SharedPreferences prefs=MemoryPreferences.create();
        SyncTokenStore tokens=new SyncTokenStore(prefs); SyncServerConfig server=new SyncServerConfig(prefs);
        SyncTransport transport=mock(SyncTransport.class);
        SyncConfigurationSession config=new SyncConfigurationSession(tokens,server,transport);
        assertNull(config.save("first@example.com","Reader","https://sync.example.com"));
        clearInvocations(transport);
        assertEquals("INCOMPLETE_CONFIGURATION",config.save("second@example.com","Reader","http://invalid.example.com"));
        assertEquals("first@example.com",tokens.configuredEmail()); assertEquals("https://sync.example.com",server.url());
        assertEquals("INVALID_DEVICE_NAME",config.saveName(""));
        assertEquals("INVALID_EMAIL",config.saveEmail("invalid")); verifyNoInteractions(transport);
    }
    @Test public void configurationGenerationAdvancesOnlyWhenInvalidatedOrDisabled() {
        SyncTokenStore tokens=mock(SyncTokenStore.class); SyncTransport transport=mock(SyncTransport.class);
        SyncConfigurationSession config=new SyncConfigurationSession(tokens,new SyncServerConfig(MemoryPreferences.create()),transport);
        long generation=config.generation();
        assertNull(config.saveEmail("ONE@example.com")); assertEquals(generation,config.generation());
        config.invalidate(); assertEquals(generation+1,config.generation()); verify(tokens).invalidateCredentials();
        config.disable(); assertEquals(generation+2,config.generation()); verify(tokens).clear();
    }
    @Test public void comparisonReturnsPromptAndDeletionPauseSurvivesRemoteChoice() {
        ReadingSyncPhase phase=new ReadingSyncPhase(); phase.positionReady(); phase.pauseUpload(true);
        LocalProgressSnapshot local=new LocalProgressSnapshot(1,"a".repeat(64),1000,800,100,1);
        RemoteProgressSnapshot remote=new RemoteProgressSnapshot("a".repeat(64),1000,200,.2,200,"2","other","Other","ios",300,"launch");
        assertEquals(ReadingSyncPhase.ComparisonAction.PROMPT,phase.compare(local,remote,"self","launch",null,false));
        assertFalse(phase.canRead()); assertFalse(phase.canUpload());
        phase.requirePosition(); phase.complete(); assertFalse(phase.canRead());
        phase.positionReady(); assertTrue(phase.canRead()); assertFalse(phase.canUpload());
        phase.offline(); phase.requireComparison(); phase.complete(); assertFalse(phase.canUpload());
    }
}
