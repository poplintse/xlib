package com.xlib.txtreader;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class BookImportPolicyTest {
    @Test public void acceptsSingleAndEmptySelection() {
        assertEquals(List.of("one"), BookImportPolicy.selection(List.of("one")));
        assertTrue(BookImportPolicy.selection(List.of()).isEmpty());
        assertTrue(BookImportPolicy.selection(Arrays.asList((String) null)).isEmpty());
    }

    @Test public void acceptsExactlyTwentyBooks() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) items.add(i);
        assertEquals(items, BookImportPolicy.selection(items));
    }

    @Test public void rejectsTwentyOneWithoutTruncatingOrChangingInput() {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 21; i++) items.add(i);
        assertThrows(IllegalArgumentException.class, () -> BookImportPolicy.selection(items));
        assertEquals(21, items.size());
    }

    @Test public void removesDuplicateAndNullItemsInSelectionOrder() {
        assertEquals(List.of("second", "first"), BookImportPolicy.selection(
                Arrays.asList("second", null, "first", "second")));
    }

    @Test public void fastImportsAlwaysReceiveDifferentIncreasingIds() {
        long previous = BookImportPolicy.nextId();
        for (int i = 0; i < 1000; i++) {
            long next = BookImportPolicy.nextId();
            assertTrue(next > previous);
            previous = next;
        }
    }
}
