package com.xlib.txtreader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ApplicationControllersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
    private Book book(String text) throws Exception {
        File file = temp.newFile(); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Book book = new Book(); book.id = 1; book.path = file.getAbsolutePath();
        book.fileSize = file.length(); book.encoding = "UTF-8"; return book;
    }
    private BookImportController<String> importer(File dir, Queue worker, Queue ui, BookImportController.Listener listener) {
        return new BookImportController<>(dir, new BookImportController.Source<String>() {
            public String name(String item) { return item + ".txt"; }
            public InputStream open(String item) throws Exception {
                if (item.equals("bad")) throw new IOException("unreadable");
                return new ByteArrayInputStream((item.startsWith("same") ? "same" : item).getBytes(StandardCharsets.UTF_8));
            }
        }, worker, ui, listener);
    }
    @Test public void importBatchDeduplicatesBeforePublicationAndContinuesAfterFailure() throws Exception {
        Queue worker = new Queue(), ui = new Queue();
        BookImportController.Listener listener = mock(BookImportController.Listener.class); File dir = temp.newFolder();
        BookImportController<String> imports = importer(dir, worker, ui, listener);
        imports.importBooks(Arrays.asList("same1", "same2", "bad", "last"), Collections.emptyList());
        assertEquals(4, imports.pending().size()); worker.drain(); ui.drain();
        verify(listener).finished(2, 1, 1);
        verify(listener, times(2)).publish(argThat(b -> b.updatedAt == 0 && b.offset == 0));
        assertEquals(2, Objects.requireNonNull(dir.list()).length); assertTrue(imports.pending().isEmpty());
    }
    @Test public void destructionCleansPreparedFilesWithoutUiCallbacks() throws Exception {
        Queue worker = new Queue(), ui = new Queue(); File dir = temp.newFolder();
        BookImportController.Listener listener = mock(BookImportController.Listener.class);
        BookImportController<String> imports = importer(dir, worker, ui, listener);
        imports.importBooks(Collections.singletonList("one"), Collections.emptyList()); worker.drain();
        assertEquals(1, Objects.requireNonNull(dir.list()).length);
        imports.close(); assertEquals(0, Objects.requireNonNull(dir.list()).length);
        ui.drain(); verify(listener, never()).publish(any()); verify(listener, never()).finished(anyInt(),anyInt(),anyInt());
    }
    @Test public void closingImporterKeepsPublishedBooks() throws Exception {
        Queue worker = new Queue(), ui = new Queue(); File dir = temp.newFolder();
        BookImportController<String> imports = importer(dir, worker, ui, mock(BookImportController.Listener.class));
        imports.importBooks(Collections.singletonList("one"), Collections.emptyList()); worker.drain(); ui.drain();
        imports.close(); assertEquals(1, Objects.requireNonNull(dir.list()).length);
    }
    @Test public void canceledSearchCannotAppendResultsOrNotifyOldPage() throws Exception {
        Queue worker = new Queue(), ui = new Queue(); BookSearchController search = new BookSearchController(worker, ui);
        Book book = book("Hello HELLO"); BookSearchController.SearchSession session = new BookSearchController.SearchSession(book,"hello",0,book.fileSize);
        BookSearchController.Listener listener = mock(BookSearchController.Listener.class);
        search.load(session, listener); worker.drain(); search.cancel(); ui.drain();
        assertFalse(session.loading); assertTrue(session.results.isEmpty()); verifyNoInteractions(listener);
        search.load(session, listener); worker.drain(); ui.drain(); assertEquals(2,session.results.size()); assertTrue(session.complete);
    }
    @Test public void searchWrapRequiresChoiceAndStopsAtOriginalStart() throws Exception {
        Book book = book("aa xx aa"); book.offset=5; book.updatedAt=123;
        BookSearchController search = new BookSearchController(Runnable::run,Runnable::run);
        BookSearchController.SearchSession session = new BookSearchController.SearchSession(book,"aa",5,book.fileSize);
        search.load(session,(s,b,e)->assertNull(e)); assertTrue(session.needsWrapConfirmation); assertEquals(1, session.results.size());
        search.load(session,(s,b,e)->fail()); assertTrue(search.wrap(session));
        search.load(session,(s,b,e)->assertNull(e)); assertTrue(session.complete); assertEquals(2,session.results.size());
        assertEquals(5,book.offset); assertEquals(123,book.updatedAt);
    }
    @Test public void searchFromBeginningKeepsActualFormalReturnPosition() throws Exception {
        Book book = book("aa xx aa"); book.offset=5; book.progress=.625f; book.updatedAt=123;
        BookSearchController.SearchSession session = new BookSearchController.SearchSession(book,"aa",0,book.fileSize);
        assertEquals(0,session.originOffset); assertEquals(5,session.returnOffset);
        assertEquals(.625f,session.returnProgress,0); assertEquals(123,session.returnUpdatedAt);
    }
    @Test public void searchFailureCanBeRetried() throws Exception {
        Book book = book("hello"); book.encoding = "invalid-encoding";
        BookSearchController search = new BookSearchController(Runnable::run,Runnable::run);
        BookSearchController.SearchSession session = new BookSearchController.SearchSession(book,"hello",0,book.fileSize);
        search.load(session,(s,b,e)->assertNotNull(e)); assertFalse(session.loading); assertFalse(session.complete);
        book.encoding = "UTF-8";
        search.load(session,(s,b,e)->assertNull(e)); assertTrue(session.complete);
    }
    @Test public void cacheReadsCompatibleFormatAndDeletionRejectsQueuedWrite() throws Exception {
        Book book=book("hello"); File dir=temp.newFolder(); ReaderCacheStore cache=new ReaderCacheStore(dir);
        ReaderCacheStore.ReaderCacheWrite write=new ReaderCacheStore.ReaderCacheWrite(book,5,new File(book.path).lastModified(),0,5,"hello");
        cache.write(write); assertEquals("hello",cache.read(book).text);
        cache.delete(book); cache.write(write); assertNull(cache.read(book)); assertTrue(new File(book.path).exists());
    }
    @Test public void deletedCatalogCannotBeRecreatedByQueuedGeneration() throws Exception {
        Queue worker=new Queue(), ui=new Queue(); TocStore store=mock(TocStore.class); BookmarkStore bookmarks=mock(BookmarkStore.class);
        CatalogController catalog=new CatalogController(store,bookmarks,worker,ui); Book book=book("第一章 开始\n正文");
        CatalogController.Listener listener=mock(CatalogController.Listener.class);
        catalog.generate(book,()->true,false,listener); catalog.delete(book); worker.drain(); ui.drain();
        verify(store,never()).write(any(),any()); verify(bookmarks).deleteForBook(book.id); verifyNoInteractions(listener);
    }
    @Test public void libraryDeletionRemovesOnlyTargetLocalDataAndMetadataEditKeepsProgress() throws Exception {
        BookStore store=mock(BookStore.class); CatalogController catalog=mock(CatalogController.class);
        ReaderCacheStore cache=mock(ReaderCacheStore.class); BookHashCache hashes=mock(BookHashCache.class);
        LibraryController library=new LibraryController(store,catalog,cache,hashes);
        Book first=book("first"), second=book("second"); second.id=2;
        doAnswer(invocation -> { new File(((Book) invocation.getArgument(0)).path).delete(); return null; })
                .when(store).delete(any());
        first.offset=2; first.updatedAt=123; library.add(first); library.add(second);
        library.edit(first,"New title","Author"); assertEquals(2,first.offset); assertEquals(123,first.updatedAt);
        library.delete(first); assertEquals(List.of(second),library.books());
        assertFalse(new File(first.path).exists()); assertTrue(new File(second.path).exists());
        verify(catalog).delete(first); verify(cache).delete(first); verify(hashes).remove(first.id);
        verify(store).delete(first);
        verifyNoMoreInteractions(catalog,cache,hashes);
    }
    @Test public void cacheAcceptsLegacyXli2AndIgnoresTruncation() throws Exception {
        Book book=book("hello"); File dir=temp.newFolder(); File file=new File(dir,"1.window");
        try (DataOutputStream output=new DataOutputStream(new FileOutputStream(file))) {
            output.writeInt(0x584C4932); output.writeLong(5); output.writeLong(new File(book.path).lastModified());
            output.writeLong(0); output.writeInt(5); output.writeInt(5); output.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        ReaderCacheStore cache=new ReaderCacheStore(dir); assertEquals("hello",cache.read(book).text);
        try (RandomAccessFile damaged=new RandomAccessFile(file,"rw")) { damaged.setLength(12); }
        assertNull(cache.read(book)); assertTrue(new File(book.path).exists());
    }
    @Test public void readerCloseInvalidatesBothPipelinesAndStopsAllQueues() {
        ExecutorService io=mock(ExecutorService.class), pages=mock(ExecutorService.class), index=mock(ExecutorService.class), cache=mock(ExecutorService.class);
        ReaderTaskScope scope=new ReaderTaskScope(io,pages,index,cache);
        long load=scope.loadGeneration(), page=scope.nextPage(); scope.close();
        assertNotEquals(load,scope.loadGeneration()); assertNotEquals(page,scope.pageGeneration());
        verify(io).shutdownNow();verify(pages).shutdownNow();verify(index).shutdownNow();verify(cache).shutdownNow();
    }
}
