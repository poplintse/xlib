package com.xlib.txtreader;
import org.junit.Test;
import static org.junit.Assert.*;

public class FormalReadingProgressTest {
    @Test public void onlyActualFormalMovementCreatesTime() {
        Book book = new Book();
        book.fileSize = 1000; book.offset = 200; book.updatedAt = 100;
        assertFalse(FormalReadingProgress.apply(book, 200, null, 500, false));
        assertFalse(FormalReadingProgress.apply(book, 300, null, 500, true));
        assertEquals(100, book.updatedAt);
        assertEquals(200, book.offset);
        assertTrue(FormalReadingProgress.apply(book, 100, null, 500, false));
        assertEquals(500, book.updatedAt);
        assertTrue(FormalReadingProgress.apply(book, 600, 200L, 999, true));
        assertEquals(200, book.updatedAt);
        assertEquals(600, book.offset);
    }
}
