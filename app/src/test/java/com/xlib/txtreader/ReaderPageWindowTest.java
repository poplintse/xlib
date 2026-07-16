package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class ReaderPageWindowTest {
    @Test
    public void movesOnlyThroughReadyPages() {
        ReaderPageWindow window = new ReaderPageWindow(7);
        window.reset(Arrays.asList(page(0), page(100), page(200)), 100, 1_000);

        assertEquals(1, window.pagesBefore());
        assertEquals(1, window.pagesAfter());
        assertTrue(window.moveForward());
        assertEquals(200, window.current().startOffset);
        assertFalse(window.moveForward());
        assertTrue(window.moveBackward());
        assertEquals(100, window.current().startOffset);
    }

    @Test
    public void appendsPrefetchedPagesWithoutChangingCurrentPage() {
        ReaderPageWindow window = new ReaderPageWindow(7);
        window.reset(Arrays.asList(page(100), page(200), page(300)), 200, 1_000);

        assertTrue(window.prependBackwardPages(
                Arrays.asList(page(0)), 100, 200, 1_000));
        assertTrue(window.appendForwardPages(
                Arrays.asList(page(400)), 400, 200, 1_000));

        assertEquals(200, window.current().startOffset);
        assertEquals(2, window.pagesBefore());
        assertEquals(2, window.pagesAfter());
    }

    @Test
    public void trimsFarPagesAroundCurrentPage() {
        ReaderPageWindow window = new ReaderPageWindow(5);
        window.reset(Arrays.asList(
                page(0), page(100), page(200), page(300),
                page(400), page(500), page(600)), 300, 1_000);

        assertEquals(300, window.current().startOffset);
        assertEquals(2, window.pagesBefore());
        assertEquals(2, window.pagesAfter());
    }

    @Test
    public void oneConsumedPageIsReplacedAtReadingDirectionEdge() {
        ReaderPageWindow window = new ReaderPageWindow(5);
        window.reset(Arrays.asList(
                page(0), page(100), page(200), page(300), page(400)),
                200, 2_000);

        assertTrue(window.moveForward());
        assertTrue(window.appendForwardPages(
                Arrays.asList(page(500)), 500, 300, 2_000));

        assertEquals(300, window.current().startOffset);
        assertEquals(2, window.pagesBefore());
        assertEquals(2, window.pagesAfter());
        assertEquals(600, window.lastEndOffset());
    }

    @Test
    public void rejectsGapOverlapAndStaleBoundary() {
        ReaderPageWindow window = new ReaderPageWindow(7);
        assertTrue(window.reset(Arrays.asList(page(100), page(200), page(300)),
                200, 1_000));

        assertFalse(window.appendForwardPages(
                Arrays.asList(page(500)), 400, 200, 1_000));
        assertFalse(window.appendForwardPages(
                Arrays.asList(page(400)), 300, 200, 1_000));
        assertFalse(window.prependBackwardPages(
                Arrays.asList(page(0), page(200)), 100, 200, 1_000));
        assertEquals(200, window.current().startOffset);
        assertEquals(1, window.pagesBefore());
        assertEquals(1, window.pagesAfter());
    }

    private ReaderPage page(long offset) {
        return new ReaderPage(offset, offset + 100, "p" + offset);
    }
}
