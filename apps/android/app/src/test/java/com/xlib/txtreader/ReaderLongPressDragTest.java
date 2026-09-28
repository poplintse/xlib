package com.xlib.txtreader;

import android.app.Activity;
import android.os.Looper;
import android.text.StaticLayout;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
public class ReaderLongPressDragTest {
    @Test public void heldFingerExtendsSelectionAndMenuAppearsOnRelease() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ReaderPageTextView view = new ReaderPageTextView(activity);
        String text = "alpha beta gamma";
        StaticLayout layout = mock(StaticLayout.class);
        when(layout.getHeight()).thenReturn(40);
        when(layout.getLineForVertical(anyInt())).thenReturn(0);
        when(layout.getLineForOffset(anyInt())).thenReturn(0);
        when(layout.getLineStart(0)).thenReturn(0);
        when(layout.getLineEnd(0)).thenReturn(text.length());
        when(layout.getLineLeft(0)).thenReturn(0f);
        when(layout.getLineRight(0)).thenReturn(160f);
        when(layout.getLineTop(0)).thenReturn(0);
        when(layout.getLineBottom(0)).thenReturn(40);
        when(layout.getParagraphDirection(0)).thenReturn(1);
        when(layout.getOffsetForHorizontal(eq(0), anyFloat())).thenAnswer(invocation ->
                Math.max(0, Math.min(text.length(),
                        Math.round((float) invocation.getArgument(1) / 10f))));
        when(layout.getPrimaryHorizontal(anyInt())).thenAnswer(invocation ->
                (int) invocation.getArgument(0) * 10f);
        view.setTextSize(32);
        view.setReaderPage(new ReaderPage(0, text.length(), text, layout, 0,
                layout.getHeight()), text);
        FrameLayout root = new FrameLayout(activity);
        root.addView(view, new FrameLayout.LayoutParams(600, 100));
        activity.setContentView(root);
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(600,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(100,
                        android.view.View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 600, 100);

        float y = 20;
        float wordX = 80;
        touch(view, MotionEvent.ACTION_DOWN, wordX, y);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                ViewConfiguration.getLongPressTimeout() + 1));
        assertEquals("beta", selectedText(view, text));
        assertFalse(menuVisible(view));

        touch(view, MotionEvent.ACTION_MOVE, 160, y);
        assertEquals("beta gamma", selectedText(view, text));
        touch(view, MotionEvent.ACTION_MOVE, 0, y);
        assertEquals("alpha beta", selectedText(view, text));
        touch(view, MotionEvent.ACTION_MOVE, wordX, y);
        assertEquals("beta", selectedText(view, text));

        touch(view, MotionEvent.ACTION_UP, wordX, y);
        assertTrue(menuVisible(view));
    }

    private static void touch(ReaderPageTextView view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        try {
            view.handleSelectionTouch(event, view);
        } finally {
            event.recycle();
        }
    }

    private static String selectedText(ReaderPageTextView view, String text) throws Exception {
        return text.substring(field(view, "selectionStart"), field(view, "selectionEnd"));
    }

    private static int field(ReaderPageTextView view, String name) throws Exception {
        Field field = ReaderPageTextView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(view);
    }

    private static boolean menuVisible(ReaderPageTextView view) throws Exception {
        Field field = ReaderPageTextView.class.getDeclaredField("selectionMenu");
        field.setAccessible(true);
        return field.get(view) != null;
    }
}
