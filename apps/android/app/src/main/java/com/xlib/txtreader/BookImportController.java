package com.xlib.txtreader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Import use case. UI owns publication; unclaimed files remain this controller's responsibility. */
final class BookImportController<T> {
    interface Source<T> {
        String name(T source) throws Exception;
        InputStream open(T source) throws Exception;
    }
    interface Listener {
        void changed();
        void publish(Book book);
        void finished(int added, int duplicates, int failed);
    }
    private final File directory;
    private final Source<T> source;
    private final Executor worker;
    private final Executor ui;
    private final Listener listener;
    private final BookImportDeduplicator deduplicator = new BookImportDeduplicator();
    private final List<PendingBookImport> pending = new ArrayList<>();
    private final List<Book> unpublished = new ArrayList<>();
    private volatile boolean closed;

    BookImportController(File directory, Source<T> source, Executor worker, Executor ui, Listener listener) {
        this.directory = directory;
        this.source = source;
        this.worker = worker;
        this.ui = ui;
        this.listener = listener;
    }
    List<PendingBookImport> pending() { return java.util.Collections.unmodifiableList(pending); }
    synchronized void close() {
        closed = true;
        for (Book book : unpublished) discard(book);
        unpublished.clear();
    }
    private synchronized boolean retain(Book book) {
        if (closed) { discard(book); return false; }
        unpublished.add(book);
        return true;
    }
    private synchronized boolean claim(Book book) {
        return !closed && unpublished.remove(book);
    }
    void importBooks(List<T> selected, List<Book> library) {
        List<T> items = BookImportPolicy.selection(selected);
        if (closed || items.isEmpty()) return;
        List<File> existing = new ArrayList<>();
        for (Book book : library) existing.add(new File(book.path));
        List<PendingBookImport> batch = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) batch.add(new PendingBookImport("待导入书籍 " + (i + 1)));
        pending.addAll(0, batch);
        listener.changed();
        worker.execute(() -> {
            int added = 0, duplicates = 0, failed = 0;
            for (int i = 0; i < items.size() && !closed; i++) {
                PendingBookImport entry = batch.get(i);
                T item = items.get(i);
                String name = null;
                try { name = source.name(item); } catch (Exception ignored) { }
                String title = name;
                ui.execute(() -> { if (!closed) { entry.start(title); listener.changed(); } });
                try {
                    Book book = prepare(item, title, existing);
                    if (!retain(book)) return;
                    added++;
                    ui.execute(() -> {
                        if (!claim(book)) return;
                        listener.publish(book);
                        entry.complete(book);
                        listener.changed();
                    });
                } catch (BookImportDeduplicator.DuplicateBookException error) {
                    duplicates++;
                    ui.execute(() -> { if (!closed) { entry.skipDuplicate(); listener.changed(); } });
                } catch (Exception error) {
                    failed++;
                    ui.execute(() -> { if (!closed) { entry.complete(null); listener.changed(); } });
                }
            }
            int totalAdded = added, totalDuplicates = duplicates, totalFailed = failed;
            ui.execute(() -> {
                if (!closed) {
                    pending.removeAll(batch);
                    listener.changed();
                    listener.finished(totalAdded, totalDuplicates, totalFailed);
                }
            });
        });
    }
    private Book prepare(T item, String title, List<File> existing) throws Exception {
        if (title == null || title.trim().isEmpty()) title = "book-" + System.currentTimeMillis() + ".txt";
        long id = BookImportPolicy.nextId();
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create book directory");
        File target = new File(directory, id + "-" + title.replaceAll("[^A-Za-z0-9._-]", "_"));
        File temporary = new File(directory, target.getName() + ".tmp");
        try {
            try (InputStream input = source.open(item); FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("Cannot open selected file");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
            }
            if (deduplicator.isDuplicate(temporary, existing)) throw new BookImportDeduplicator.DuplicateBookException();
            if (!temporary.renameTo(target)) throw new IOException("Cannot publish imported TXT file");
        } catch (Exception error) { boolean ignored = temporary.delete(); throw error; }
        Book book = new Book();
        book.id = id; book.title = title; book.sourceName = title; book.author = "";
        book.path = target.getAbsolutePath(); book.fileSize = target.length();
        try { book.encoding = TextFileUtils.detectEncoding(target); }
        catch (Exception error) { boolean ignored = target.delete(); throw error; }
        book.offset = 0; book.progress = 0; book.pageMode = true; book.updatedAt = 0;
        deduplicator.accepted(target);
        return book;
    }
    private static void discard(Book book) { boolean ignored = new File(book.path).delete(); }
}
