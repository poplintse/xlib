package com.xlib.txtreader;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BookLongPressDeleteTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private LibraryController library;
    private Book target;
    private Book other;
    private View row;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        Field field = MainActivity.class.getDeclaredField("library");
        field.setAccessible(true);
        library = (LibraryController) field.get(activity);
        target = addBook(7001, "待删除书籍");
        other = addBook(7002, "保留书籍");
        assertTrue(library.save(null, 0, 0));
        Method showLibrary = MainActivity.class.getDeclaredMethod("showLibrary");
        showLibrary.setAccessible(true);
        showLibrary.invoke(activity);
        TextView title = findText(activity.getWindow().getDecorView(), target.title);
        assertNotNull(title);
        row = (View) title.getParent().getParent();
    }

    @After public void tearDown() {
        controller.pause().stop().destroy();
    }

    @Test public void longPressDeleteCanBeCancelledThenConfirmedWithoutDeletingOtherBook() {
        assertTrue(row.performLongClick());
        View delete = findVisibleDelete(row);
        assertNotNull(delete);
        assertTrue(delete.performClick());
        Dialog first = ShadowDialog.getLatestDialog();
        assertTrue(first.isShowing());
        findText(first.getWindow().getDecorView(), "取消").performClick();
        assertTrue(library.books().contains(target));
        assertTrue(new File(target.path).exists());

        delete.performClick();
        Dialog second = ShadowDialog.getLatestDialog();
        findText(second.getWindow().getDecorView(), "删除").performClick();
        assertFalse(library.books().contains(target));
        assertFalse(new File(target.path).exists());
        assertTrue(library.books().contains(other));
        assertTrue(new File(other.path).exists());
        assertNull(findText(activity.getWindow().getDecorView(), target.title));
        assertNotNull(findText(activity.getWindow().getDecorView(), other.title));
    }

    private Book addBook(long id, String title) throws Exception {
        File dir = new File(activity.getFilesDir(), "books");
        assertTrue(dir.exists() || dir.mkdirs());
        File file = new File(dir, id + ".txt");
        Files.write(file.toPath(), "测试正文".getBytes(StandardCharsets.UTF_8));
        Book book = new Book();
        book.id = id;
        book.title = title;
        book.author = "";
        book.sourceName = file.getName();
        book.path = file.getAbsolutePath();
        book.fileSize = file.length();
        book.encoding = "UTF-8";
        book.pageMode = true;
        library.add(book);
        return book;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView match = findText(group.getChildAt(i), text);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static View findVisibleDelete(View view) {
        if (view instanceof ImageButton && view.getVisibility() == View.VISIBLE
                && "删除书籍".contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findVisibleDelete(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }
}
