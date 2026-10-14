package com.xlib.txtreader;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/** Keeps horizontal shelf gestures out of the vertical ScrollView. */
final class BookRowGestures implements View.OnTouchListener, View.OnAttachStateChangeListener {
    private final View row;
    private final View swipeActions;
    private final View longPressDelete;
    private final int actionWidth;
    private final int touchSlop;
    private final Runnable longPress;
    private float downX;
    private float downY;
    private float startTranslation;
    private boolean horizontal;
    private boolean vertical;
    private boolean longPressed;
    private boolean menuOpen;
    private boolean menuOpenAtDown;

    BookRowGestures(View row, View swipeActions, View longPressDelete, int actionWidth) {
        this.row = row;
        this.swipeActions = swipeActions;
        this.longPressDelete = longPressDelete;
        this.actionWidth = actionWidth;
        touchSlop = ViewConfiguration.get(row.getContext()).getScaledTouchSlop();
        longPress = row::performLongClick;
        row.setOnLongClickListener(view -> {
            longPressed = true;
            settle(false);
            longPressDelete.setVisibility(View.VISIBLE);
            return true;
        });
        row.setOnTouchListener(this);
        row.addOnAttachStateChangeListener(this);
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                row.removeCallbacks(longPress);
                row.animate().cancel();
                downX = event.getRawX();
                downY = event.getRawY();
                startTranslation = row.getTranslationX();
                menuOpenAtDown = menuOpen;
                horizontal = vertical = longPressed = false;
                // Let the child classify the first move before ScrollView can cancel it.
                disallowParentIntercept(true);
                row.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (longPressed) return true;
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!horizontal && !vertical && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
                    row.removeCallbacks(longPress);
                    horizontal = Math.abs(dx) > Math.abs(dy);
                    vertical = !horizontal;
                    if (vertical) disallowParentIntercept(false);
                    else longPressDelete.setVisibility(View.GONE);
                }
                if (horizontal) {
                    float translation = Math.min(0, Math.max(-actionWidth, startTranslation + dx));
                    swipeActions.setVisibility(translation < 0 ? View.VISIBLE : View.INVISIBLE);
                    row.setTranslationX(translation);
                }
                return true;
            case MotionEvent.ACTION_UP:
                row.removeCallbacks(longPress);
                disallowParentIntercept(false);
                if (horizontal) {
                    settle(row.getTranslationX() <= -actionWidth / 2f);
                } else if (vertical) {
                    settle(menuOpenAtDown);
                } else if (!longPressed) {
                    if (menuOpenAtDown || row.getTranslationX() < 0) settle(false);
                    else if (longPressDelete.getVisibility() == View.VISIBLE) {
                        longPressDelete.setVisibility(View.GONE);
                    } else row.performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                row.removeCallbacks(longPress);
                disallowParentIntercept(false);
                if (!longPressed) settle(menuOpenAtDown);
                // Ignore the rest of a cancelled/multi-touch gesture until the next down.
                horizontal = false;
                vertical = true;
                return true;
            default:
                return true;
        }
    }

    private void settle(boolean open) {
        menuOpen = open;
        row.animate().cancel();
        if (open) swipeActions.setVisibility(View.VISIBLE);
        row.animate().translationX(open ? -actionWidth : 0).setDuration(160)
                .withEndAction(() -> {
                    if (!menuOpen) swipeActions.setVisibility(View.INVISIBLE);
                }).start();
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = row.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }

    @Override public void onViewAttachedToWindow(View view) { }

    @Override public void onViewDetachedFromWindow(View view) {
        row.removeCallbacks(longPress);
        row.animate().cancel();
    }
}
