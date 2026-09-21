package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BookStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private LocalDatabase database;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("xlib.db");
        database = LocalDatabase.open(context,
                context.getSharedPreferences("book-store-test", Context.MODE_PRIVATE));
    }

    @After public void tearDown() {
        database.close();
        context.deleteDatabase("xlib.db");
    }

    @Test public void missingReadTimeDoesNotInventReadingEventAndKnownTimesSurvive() throws Exception {
        File directory = new File(context.getFilesDir(), "books");
        assertTrue(directory.exists() || directory.mkdirs());
        File file = new File(directory, "book.txt");
        Files.copy(temporary.newFile("source.txt").toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        BookStore store = new BookStore(database);
        for (long time : new long[]{0L, 123L}) {
            Book book = new Book();
            book.id = 1; book.title = "测试"; book.sourceName = "book.txt"; book.author = "";
            book.path = file.getAbsolutePath(); book.fileSize = file.length(); book.encoding = "UTF-8";
            book.offset = 0; book.progress = 0; book.pageMode = true; book.updatedAt = time;
            assertTrue(store.save(List.of(book), null, 0, 0));
            Book loaded = store.load().get(0);
            assertEquals(time, loaded.updatedAt);
            assertTrue(store.save(List.of(loaded), null, 0, 0));
            assertEquals(time, store.load().get(0).updatedAt);
        }
    }
}
