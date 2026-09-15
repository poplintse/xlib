package com.xlib.txtreader;
import org.junit.Test;
import static org.junit.Assert.*;

public class BookmarkStoreTest {
    @Test public void exactPositionUniquenessAndScopedSingleDeletion() {
        BookmarkStore store = new BookmarkStore(MemoryPreferences.create());
        assertTrue(store.add(1, 10));
        Bookmark original = store.load(1).get(0);
        assertFalse(store.add(1, 10));
        assertEquals(original.createdAt, store.load(1).get(0).createdAt);
        assertTrue(store.add(1, 11));
        assertTrue(store.add(2, 10));
        store.delete(original);
        assertEquals(1, store.load(1).size());
        assertEquals(11, store.load(1).get(0).offset);
        assertEquals(1, store.load(2).size());
        store.delete(store.load(1).get(0));
        assertTrue(store.load(1).isEmpty());
        assertEquals(1, store.load(2).size());
    }
}
