package com.xlib.txtreader;

import org.junit.Test;
import static org.junit.Assert.*;

public class PendingBookImportTest {
    @Test public void waitingAndImportingCannotOpen() {
        PendingBookImport entry = new PendingBookImport("书籍");
        assertEquals(PendingBookImport.State.WAITING, entry.state);
        assertFalse(entry.canOpen());
        entry.start("小说.txt");
        assertEquals(PendingBookImport.State.IMPORTING, entry.state);
        assertEquals("小说.txt", entry.title);
        assertFalse(entry.canOpen());
    }

    @Test public void successfulBookEnablesIndependentlyOfRemainingQueue() {
        PendingBookImport first = new PendingBookImport("第一本");
        PendingBookImport second = new PendingBookImport("第二本");
        PendingBookImport third = new PendingBookImport("第三本");
        first.start(null);
        Book book = new Book();
        first.complete(book);
        second.start(null);
        assertTrue(first.canOpen());
        assertSame(book, first.book);
        assertFalse(second.canOpen());
        assertFalse(third.canOpen());
    }

    @Test public void failedImportNeverEnables() {
        PendingBookImport entry = new PendingBookImport("失败书籍");
        entry.start(null);
        entry.complete(null);
        assertEquals(PendingBookImport.State.FAILED, entry.state);
        assertFalse(entry.canOpen());
        assertNull(entry.book);
    }
}
