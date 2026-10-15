package com.xlib.txtreader;

import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReaderProgressControlTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private SeekBar seekBar;
    private TextView percentage;

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        File directory = new File(activity.getFilesDir(), "books");
        assertTrue(directory.exists() || directory.mkdirs());
        File file = new File(directory, "progress-test.txt");
        Files.write(file.toPath(), "测试阅读进度\n".repeat(100).getBytes(StandardCharsets.UTF_8));
        Book book = new Book();
        book.id = 8001;
        book.title = "进度测试";
        book.sourceName = file.getName();
        book.author = "";
        book.path = file.getAbsolutePath();
        book.fileSize = file.length();
        book.encoding = "UTF-8";
        book.offset = book.fileSize / 4;
        book.progress = 0.25f;
        book.pageMode = true;
        ((LibraryController) field("library").get(activity)).add(book);
        field("currentBook").set(activity, book);
        Method showReader = MainActivity.class.getDeclaredMethod("showReader", Book.class);
        showReader.setAccessible(true);
        showReader.invoke(activity, book);
        seekBar = (SeekBar) field("seekBar").get(activity);
        percentage = (TextView) ((ViewGroup) seekBar.getParent()).getChildAt(0);
    }

    @After public void tearDown() { controller.pause().stop().destroy(); }

    @Test public void leftPercentageStartsAtCurrentProgressAndFollowsProgrammaticUpdates() {
        assertPercentage(25f);
        seekBar.setProgress(763);
        assertPercentage(76.3f);
        seekBar.setProgress(1000);
        assertPercentage(100f);
        seekBar.setProgress(0);
        assertPercentage(0f);
    }

    @Test public void leftPercentageFollowsFingerBeforeSeekPreviewCompletes() throws Exception {
        seekBar.layout(0, 0, 400, 44);
        touch(MotionEvent.ACTION_DOWN, 140);
        touch(MotionEvent.ACTION_MOVE, 320);
        assertTrue(field("seekTracking").getBoolean(activity));
        assertTrue(seekBar.getProgress() > 500);
        assertPercentage(seekBar.getProgress() / 10f);
        assertEquals(seekBar.getProgress() / 1000f,
                field("pendingSeekProgress").getFloat(activity), 0.0001f);
        touch(MotionEvent.ACTION_UP, 320);
        assertFalse(field("seekTracking").getBoolean(activity));
        assertPercentage(seekBar.getProgress() / 10f);
    }

    @Test public void fullTrackAndFilledProgressHaveClearContrastInBothThemes() {
        for (boolean dark : new boolean[]{false, true}) {
            UiKit.styleReaderSeekBar(activity, seekBar, dark);
            LayerDrawable layers = (LayerDrawable) seekBar.getProgressDrawable();
            int track = ((GradientDrawable) layers.findDrawableByLayerId(android.R.id.background))
                    .getColor().getDefaultColor();
            int fill = ((GradientDrawable) ((ClipDrawable) layers.findDrawableByLayerId(
                    android.R.id.progress)).getDrawable()).getColor().getDefaultColor();
            int surface = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
            assertTrue("Full track must remain visible against the menu", contrast(track, surface) >= 3);
            assertTrue("Filled progress must stand out from the track", contrast(fill, track) >= 3);
        }
    }

    private void assertPercentage(float value) {
        assertEquals(String.format(Locale.getDefault(), "%.1f%%", value), percentage.getText().toString());
    }

    private void touch(int action, float x) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, 22, 0);
        try { seekBar.onTouchEvent(event); }
        finally { event.recycle(); }
    }

    private static Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color))
                + 0.0722 * linear(Color.blue(color));
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
