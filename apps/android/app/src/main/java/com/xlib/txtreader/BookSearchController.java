package com.xlib.txtreader;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Serial UI-owned query sessions; disk work can complete after cancellation without publishing. */
final class BookSearchController {
    interface Listener { void completed(SearchSession session, SearchBatch batch, Exception error); }
    private final Executor worker;
    private final Executor ui;
    private long generation;
    private SearchSession loading;
    BookSearchController(Executor worker, Executor ui) { this.worker = worker; this.ui = ui; }
    void cancel() {
        generation++;
        if (loading != null) loading.loading = false;
        loading = null;
    }
    boolean wrap(SearchSession session) {
        if (session == null || session.loading || !session.needsWrapConfirmation) return false;
        session.wrappedToStart = true;
        session.needsWrapConfirmation = false;
        session.nextOffset = 0;
        return true;
    }
    void load(SearchSession session, Listener listener) {
        if (session == null || session.loading || session.complete || session.needsWrapConfirmation) return;
        cancel();
        loading = session;
        session.loading = true;
        long request = generation;
        long start = session.nextOffset;
        long end = session.wrappedToStart ? session.originOffset : session.fileSize;
        worker.execute(() -> {
            SearchBatch batch = null;
            Exception error = null;
            try { batch = searchBookBatch(session.book, session.query, start, end); }
            catch (Exception failure) { error = failure; }
            SearchBatch result = batch;
            Exception failure = error;
            ui.execute(() -> {
                if (request != generation) return;
                loading = null;
                session.loading = false;
                if (failure == null) {
                    session.results.addAll(result.results);
                    session.nextOffset = result.nextOffset;
                    if (result.reachedBoundary) {
                        if (session.wrappedToStart || session.originOffset == 0) session.complete = true;
                        else session.needsWrapConfirmation = true;
                    }
                }
                listener.completed(session, result, failure);
            });
        });
    }
    private static Charset charsetFor(String encoding) {
        try { return Charset.forName(encoding); }
        catch (Exception ignored) { return Charset.forName("UTF-8"); }
    }
    private static final int SEARCH_READ_BYTES = 64 * 1024;
    private static final int SEARCH_RESULT_LIMIT = 200;
    private static final int SEARCH_CONTEXT_CHARS = 45;
    private static final int SEARCH_SNIPPET_MAX_READ_BYTES = 64 * 1024;
    private static SearchBatch searchBookBatch(Book book, String query, long startOffset, long endOffset)
            throws Exception {
        File file = new File(book.path);
        Charset charset = charsetFor(book.encoding);
        ReaderTextSearch.Batch batch = ReaderTextSearch.find(
                file, book.encoding, query, startOffset, endOffset,
                SEARCH_READ_BYTES, SEARCH_RESULT_LIMIT);
        int queryByteLength = query.getBytes(charset).length;
        List<SearchResult> results = new ArrayList<>();
        for (long offset : batch.offsets) {
            results.add(new SearchResult(offset, makeSearchSnippet(
                    file, offset, query, book.encoding, queryByteLength)));
        }
        return new SearchBatch(
                results, batch.nextOffset, batch.reachedBoundary);
    }

    private static String makeSearchSnippet(File file, long matchOffset, String query, String encoding,
                                     int queryByteLength) throws Exception {
        long desiredStart = Math.max(0L, matchOffset - SEARCH_CONTEXT_CHARS * 4L);
        long contextStart = ReaderSegmentSource.findReadableOffset(file, desiredStart, encoding);
        long prefixBytes = Math.max(0L, matchOffset - contextStart);
        if (prefixBytes > SEARCH_SNIPPET_MAX_READ_BYTES / 2L) return query;
        int readLength = (int) Math.min(SEARCH_SNIPPET_MAX_READ_BYTES,
                Math.max(2048L, prefixBytes
                        + SEARCH_CONTEXT_CHARS * 4L + queryByteLength + 1024L));
        CacheSegment context = ReaderSegmentSource.read(file, contextStart, readLength, charsetFor(encoding));
        int index = ByteOffsetMap.create(context.text, charsetFor(encoding))
                .charIndexForByteOffset(matchOffset - context.offset);
        int start = Math.max(0, index - SEARCH_CONTEXT_CHARS);
        int end = Math.min(context.text.length(), index + query.length() + SEARCH_CONTEXT_CHARS);
        return context.text.substring(start, end).replace('\n', ' ').replace('\r', ' ');
    }

    static final class SearchResult {
        final long offset;
        final String snippet;

        SearchResult(long offset, String snippet) {
            this.offset = offset;
            this.snippet = snippet;
        }
    }

    static final class SearchBatch {
        final List<SearchResult> results;
        final long nextOffset;
        final boolean reachedBoundary;

        SearchBatch(List<SearchResult> results, long nextOffset, boolean reachedBoundary) {
            this.results = results;
            this.nextOffset = nextOffset;
            this.reachedBoundary = reachedBoundary;
        }
    }

    static final class SearchSession {
        final Book book;
        final String query;
        final long originOffset;
        final long fileSize;
        final long returnOffset;
        final float returnProgress;
        final long returnUpdatedAt;
        final List<SearchResult> results = new ArrayList<>();
        long nextOffset;
        boolean loading;
        boolean wrappedToStart;
        boolean needsWrapConfirmation;
        boolean complete;

        SearchSession(Book book, String query, long originOffset, long fileSize) {
            this.book = book;
            this.query = query;
            this.originOffset = originOffset;
            this.fileSize = fileSize;
            this.returnOffset = book.offset;
            this.returnProgress = book.progress;
            this.returnUpdatedAt = book.updatedAt;
            this.nextOffset = originOffset;
        }
    }

}
