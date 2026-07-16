package com.xlib.txtreader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded, bidirectional moving window of ready-to-display pages. */
final class ReaderPageWindow {
    private final int maxPages;
    private final ArrayList<ReaderPage> pages = new ArrayList<>();
    private int currentIndex = -1;

    ReaderPageWindow(int maxPages) {
        this.maxPages = Math.max(3, maxPages);
    }

    void clear() {
        pages.clear();
        currentIndex = -1;
    }

    boolean reset(List<ReaderPage> readyPages, long anchorOffset, long fileSize) {
        ArrayList<ReaderPage> validated = new ArrayList<>();
        if (readyPages != null) validated.addAll(readyPages);
        Collections.sort(validated, (first, second) ->
                Long.compare(first.startOffset, second.startOffset));
        if (!isContiguous(validated)) return false;
        pages.clear();
        pages.addAll(validated);
        currentIndex = findPageIndex(anchorOffset, fileSize);
        if (currentIndex < 0 && !pages.isEmpty()) currentIndex = 0;
        trimAroundCurrent();
        return currentIndex >= 0;
    }

    boolean appendForwardPages(List<ReaderPage> readyPages, long expectedBoundary,
                               long anchorOffset, long fileSize) {
        if (pages.isEmpty() || expectedBoundary != lastEndOffset()
                || !isContiguous(readyPages) || readyPages.isEmpty()
                || readyPages.get(0).startOffset != expectedBoundary) {
            return false;
        }
        return appendValidatedPages(readyPages, anchorOffset, fileSize, false);
    }

    boolean prependBackwardPages(List<ReaderPage> readyPages, long expectedBoundary,
                                 long anchorOffset, long fileSize) {
        if (pages.isEmpty() || expectedBoundary != firstStartOffset()
                || !isContiguous(readyPages) || readyPages.isEmpty()
                || readyPages.get(readyPages.size() - 1).endOffset != expectedBoundary) {
            return false;
        }
        return appendValidatedPages(readyPages, anchorOffset, fileSize, true);
    }

    private boolean appendValidatedPages(List<ReaderPage> readyPages, long anchorOffset,
                                         long fileSize, boolean prepend) {
        ReaderPage current = current();
        if (prepend) pages.addAll(0, readyPages);
        else pages.addAll(readyPages);
        long currentOffset = current == null ? anchorOffset : current.startOffset;
        currentIndex = findPageIndex(currentOffset, fileSize);
        if (currentIndex < 0) currentIndex = findPageIndex(anchorOffset, fileSize);
        if (currentIndex < 0) return false;
        trimAroundCurrent();
        return true;
    }

    ReaderPage current() {
        return currentIndex >= 0 && currentIndex < pages.size()
                ? pages.get(currentIndex) : null;
    }

    boolean moveBackward() {
        if (currentIndex <= 0) return false;
        currentIndex--;
        return true;
    }

    boolean moveForward() {
        if (currentIndex < 0 || currentIndex + 1 >= pages.size()) return false;
        currentIndex++;
        return true;
    }

    int pagesBefore() {
        return Math.max(0, currentIndex);
    }

    int pagesAfter() {
        return currentIndex < 0 ? 0 : Math.max(0, pages.size() - currentIndex - 1);
    }

    boolean isEmpty() {
        return current() == null;
    }

    long firstStartOffset() {
        return pages.isEmpty() ? 0L : pages.get(0).startOffset;
    }

    long lastEndOffset() {
        return pages.isEmpty() ? 0L : pages.get(pages.size() - 1).endOffset;
    }

    private int findPageIndex(long offset, long fileSize) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).contains(offset, fileSize)) return i;
        }
        return -1;
    }

    private static boolean isContiguous(List<ReaderPage> readyPages) {
        if (readyPages == null || readyPages.isEmpty()) return false;
        ReaderPage previous = null;
        for (ReaderPage page : readyPages) {
            if (page == null || page.endOffset <= page.startOffset
                    || (previous != null && previous.endOffset != page.startOffset)) {
                return false;
            }
            previous = page;
        }
        return true;
    }

    private void trimAroundCurrent() {
        if (currentIndex < 0 || pages.size() <= maxPages) return;
        int half = maxPages / 2;
        int start = Math.max(0, currentIndex - half);
        int end = Math.min(pages.size(), start + maxPages);
        start = Math.max(0, end - maxPages);
        ArrayList<ReaderPage> kept = new ArrayList<>(pages.subList(start, end));
        currentIndex -= start;
        pages.clear();
        pages.addAll(kept);
    }
}
