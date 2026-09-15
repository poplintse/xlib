package com.xlib.txtreader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class BookImportDeduplicatorTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File file(String name, String text) throws Exception {
        File file = folder.newFile(name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test public void renamedIdenticalFileIsDuplicate() throws Exception {
        File existing = file("old.txt", "相同正文");
        File renamed = file("renamed.txt", "相同正文");
        assertTrue(new BookImportDeduplicator().isDuplicate(renamed, List.of(existing)));
    }

    @Test public void sameNameAndSizeWithDifferentBytesIsNotDuplicate() throws Exception {
        File existing = file("book.txt", "abcd");
        File directory = folder.newFolder("other");
        File candidate = new File(directory, "book.txt");
        Files.write(candidate.toPath(), "abce".getBytes(StandardCharsets.UTF_8));
        assertFalse(new BookImportDeduplicator().isDuplicate(candidate, List.of(existing)));
    }

    @Test public void detectsSameBatchAndQueuedBatchBeforeUiPublication() throws Exception {
        BookImportDeduplicator guard = new BookImportDeduplicator();
        File first = file("first.txt", "正文");
        File second = file("second.txt", "正文");
        assertFalse(guard.isDuplicate(first, List.of()));
        guard.accepted(first);
        assertTrue(guard.isDuplicate(second, List.of()));
        assertTrue(guard.isDuplicate(file("next-batch.txt", "正文"), List.of()));
    }

    @Test public void deletedBookCanBeImportedAgain() throws Exception {
        BookImportDeduplicator guard = new BookImportDeduplicator();
        File existing = file("deleted.txt", "正文");
        guard.accepted(existing);
        Files.delete(existing.toPath());
        assertFalse(guard.isDuplicate(file("again.txt", "正文"), List.of(existing)));
    }

    @Test public void comparesBeyondFirstBufferAndDetectsEmptyFiles() throws Exception {
        String prefix = "a".repeat(70_000);
        File existing = file("large.txt", prefix + "b");
        assertFalse(BookImportDeduplicator.sameContent(file("different.txt", prefix + "c"), existing));
        assertTrue(BookImportDeduplicator.sameContent(file("equal.txt", prefix + "b"), existing));
        assertTrue(BookImportDeduplicator.sameContent(file("empty1.txt", ""), file("empty2.txt", "")));
    }

    @Test public void duplicatePlaceholderNeverEnablesOrStaysPending() {
        PendingBookImport entry = new PendingBookImport("重复");
        entry.start(null);
        entry.skipDuplicate();
        assertFalse(entry.canOpen());
        assertFalse(entry.isPending());
        assertEquals(PendingBookImport.State.DUPLICATE, entry.state);
    }
}
