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
        ArrayList<ReaderPage> candidate = new ArrayList<>();
        if (readyPages != null) candidate.addAll(readyPages);
        Collections.sort(candidate, (first, second) ->
                Long.compare(first.startOffset, second.startOffset));
        if (!isContiguous(candidate)) return false;
        int candidateIndex = findPageIndex(candidate, anchorOffset, fileSize);
        if (candidateIndex < 0) return false;
        commit(trimmed(candidate, candidateIndex));
        return true;
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
        ArrayList<ReaderPage> candidate = new ArrayList<>(pages.size() + readyPages.size());
        if (prepend) candidate.addAll(readyPages);
        candidate.addAll(pages);
        if (!prepend) candidate.addAll(readyPages);
        long currentOffset = current == null ? anchorOffset : current.startOffset;
        int candidateIndex = findPageIndex(candidate, currentOffset, fileSize);
        if (candidateIndex < 0) return false;
        commit(trimmed(candidate, candidateIndex));
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
        return findPageIndex(pages, offset, fileSize);
    }

    private static int findPageIndex(List<ReaderPage> candidates,
                                     long offset, long fileSize) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).contains(offset, fileSize)) return i;
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

    private WindowState trimmed(ArrayList<ReaderPage> candidate, int candidateIndex) {
        if (candidate.size() <= maxPages) {
            return new WindowState(candidate, candidateIndex);
        }
        int half = maxPages / 2;
        int start = Math.max(0, candidateIndex - half);
        int end = Math.min(candidate.size(), start + maxPages);
        start = Math.max(0, end - maxPages);
        return new WindowState(
                new ArrayList<>(candidate.subList(start, end)), candidateIndex - start);
    }

    private void commit(WindowState state) {
        pages.clear();
        pages.addAll(state.pages);
        currentIndex = state.currentIndex;
    }

    private static final class WindowState {
        final ArrayList<ReaderPage> pages;
        final int currentIndex;

        WindowState(ArrayList<ReaderPage> pages, int currentIndex) {
            this.pages = pages;
            this.currentIndex = currentIndex;
        }
    }
}
