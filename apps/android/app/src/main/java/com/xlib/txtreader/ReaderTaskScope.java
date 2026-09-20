package com.xlib.txtreader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Worker lifetime and stale-result epochs. Layout/cache epochs remain separate renderer state. */
final class ReaderTaskScope {
    final ExecutorService io, pages, index, cache;
    private volatile long loadGeneration, pageGeneration;
    ReaderTaskScope() {
        this(Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(),
                Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor());
    }
    ReaderTaskScope(ExecutorService io, ExecutorService pages, ExecutorService index, ExecutorService cache) {
        this.io = io; this.pages = pages; this.index = index; this.cache = cache;
    }
    long loadGeneration() { return loadGeneration; }
    long pageGeneration() { return pageGeneration; }
    long nextPage() { return ++pageGeneration; }
    long cancel() { pageGeneration++; return ++loadGeneration; }
    void close() {
        cancel();
        io.shutdownNow(); pages.shutdownNow(); index.shutdownNow(); cache.shutdownNow();
    }
}
