package com.xlib.txtreader;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BookRowGesturesTest {
    private ActivityController<Activity> controller;
    private ScrollView scroll;
    private LinearLayout row;
    private LinearLayout actions;
    private Button inlineDelete;
    private Button swipeDelete;
    private final AtomicInteger opens = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private long downTime;
    private long eventTime;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        FrameLayout container = new FrameLayout(activity);
        actions = new LinearLayout(activity);
        actions.setVisibility(View.INVISIBLE);
        Button edit = new Button(activity);
        edit.setText("编辑");
        actions.addView(edit, new LinearLayout.LayoutParams(64, 140));
        swipeDelete = new Button(activity);
        swipeDelete.setText("删除");
        swipeDelete.setOnClickListener(v -> deletes.incrementAndGet());
        actions.addView(swipeDelete, new LinearLayout.LayoutParams(64, 140));
        container.addView(actions, new FrameLayout.LayoutParams(128, 140, android.view.Gravity.END));
        row = new LinearLayout(activity);
        View title = new View(activity);
        row.addView(title, new LinearLayout.LayoutParams(0, 140, 1));
        inlineDelete = new Button(activity);
        inlineDelete.setText("删除");
        inlineDelete.setVisibility(View.GONE);
        inlineDelete.setOnClickListener(v -> deletes.incrementAndGet());
        row.addView(inlineDelete, new LinearLayout.LayoutParams(48, 140));
        row.setOnClickListener(v -> opens.incrementAndGet());
        container.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140));
        new BookRowGestures(row, actions, inlineDelete, 128);
        list.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140));
        list.addView(new View(activity), new LinearLayout.LayoutParams(400, 600));
        scroll.addView(list);
        activity.setContentView(scroll);
        layout();
    }

    @After public void tearDown() { controller.pause().stop().destroy(); }

    @Test public void diagonalLeftSwipeStaysOpenAfterParentScrollThresholdIsCrossed() {
        touch(MotionEvent.ACTION_DOWN, 300, 60);
        touch(MotionEvent.ACTION_MOVE, 275, 70);
        touch(MotionEvent.ACTION_MOVE, 170, 95);
        touch(MotionEvent.ACTION_UP, 170, 95);
        finishAnimation();
        assertEquals(-128, row.getTranslationX(), 0.01f);
        assertEquals(View.VISIBLE, actions.getVisibility());
        assertEquals(0, scroll.getScrollY());
        assertEquals(0, opens.get());
        touch(MotionEvent.ACTION_DOWN, scroll.getWidth() - 20, 60);
        touch(MotionEvent.ACTION_UP, scroll.getWidth() - 20, 60);
        finishAnimation();
        assertEquals(1, deletes.get());
    }

    @Test public void halfwaySwipeOpensAndRightSwipeCloses() {
        touch(MotionEvent.ACTION_DOWN, 300, 60);
        touch(MotionEvent.ACTION_MOVE, 230, 65);
        touch(MotionEvent.ACTION_UP, 230, 65);
        finishAnimation();
        assertEquals(-128, row.getTranslationX(), 0.01f);
        touch(MotionEvent.ACTION_DOWN, 150, 60);
        touch(MotionEvent.ACTION_MOVE, 260, 65);
        touch(MotionEvent.ACTION_UP, 260, 65);
        finishAnimation();
        assertEquals(0, row.getTranslationX(), 0.01f);
        assertEquals(View.INVISIBLE, actions.getVisibility());
        assertEquals(0, opens.get());
    }

    @Test public void verticalSwipeScrollsShelfWithoutOpeningBookOrDeleteActions() {
        touch(MotionEvent.ACTION_DOWN, 300, 100);
        touch(MotionEvent.ACTION_MOVE, 298, 75);
        touch(MotionEvent.ACTION_MOVE, 298, 20);
        touch(MotionEvent.ACTION_MOVE, 298, -30);
        touch(MotionEvent.ACTION_UP, 298, -30);
        finishAnimation();
        assertTrue(scroll.getScrollY() > 0);
        assertEquals(0, row.getTranslationX(), 0.01f);
        assertEquals(View.INVISIBLE, actions.getVisibility());
        assertEquals(View.GONE, inlineDelete.getVisibility());
        assertEquals(0, opens.get());
    }

    @Test public void longPressShowsClickableDeleteButtonWithoutOpeningBookOnRelease() {
        touch(MotionEvent.ACTION_DOWN, 200, 60);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                ViewConfiguration.getLongPressTimeout() + 1));
        touch(MotionEvent.ACTION_UP, 200, 60);
        finishAnimation();
        layout();
        assertEquals(View.VISIBLE, inlineDelete.getVisibility());
        assertEquals(0, opens.get());
        touch(MotionEvent.ACTION_DOWN, 375, 60);
        touch(MotionEvent.ACTION_UP, 375, 60);
        finishAnimation();
        assertEquals(1, deletes.get());
    }

    @Test public void cancelledPressCannotRevealDeleteLaterAndShortTapStillOpensBook() {
        touch(MotionEvent.ACTION_DOWN, 200, 60);
        touch(MotionEvent.ACTION_CANCEL, 200, 60);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                ViewConfiguration.getLongPressTimeout() + 1));
        assertEquals(View.GONE, inlineDelete.getVisibility());
        touch(MotionEvent.ACTION_DOWN, 200, 60);
        touch(MotionEvent.ACTION_UP, 200, 60);
        assertEquals(1, opens.get());
    }

    private void layout() {
        scroll.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, 400, 240);
    }

    private void touch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime = SystemClock.uptimeMillis();
        else eventTime += 16;
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try { scroll.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private void finishAnimation() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    }
}
