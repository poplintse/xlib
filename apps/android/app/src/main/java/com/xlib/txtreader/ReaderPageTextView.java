package com.xlib.txtreader;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.text.StaticLayout;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

/** Draws the exact page slice produced by the background paginator. */
final class ReaderPageTextView extends TextView {
    private ReaderPage renderedPage;
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path selectionPath = new Path();
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private int draggedHandle;
    private boolean consumeGesture;
    private float downX;
    private float downY;
    private float dragDeltaX;
    private float dragDeltaY;
    private ActionMode selectionMenu;
    private Runnable onSelectionStarted;
    private final Runnable longPress = this::selectWord;

    ReaderPageTextView(Context context) {
        super(context);
    }

    void setReaderPage(ReaderPage page, CharSequence accessibilityText) {
        clearTextSelection();
        renderedPage = page;
        super.setText(accessibilityText);
        invalidate();
    }

    void setOnSelectionStarted(Runnable listener) {
        onSelectionStarted = listener;
    }

    boolean hasTextSelection() {
        return selectionStart >= 0;
    }

    // Called by the reader's gesture owner BEFORE its tap/swipe detection. Coordinates
    // are converted from the ScrollView, including safe insets and scroll position.
    boolean handleSelectionTouch(MotionEvent event, View source) {
        int[] origin = new int[2];
        int[] target = new int[2];
        source.getLocationOnScreen(origin);
        getLocationOnScreen(target);
        float x = event.getX() + origin[0] - target[0];
        float y = event.getY() + origin[1] - target[1];
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            removeCallbacks(longPress);
            downX = x;
            downY = y;
            consumeGesture = selectionStart >= 0;
            draggedHandle = 0;
            if (consumeGesture) {
                PointF start = handlePoint(true);
                PointF end = handlePoint(false);
                float ds = distance(x, y, start);
                float de = distance(x, y, end);
                if (Math.min(ds, de) <= dp(28)) {
                    draggedHandle = ds <= de ? 1 : 2;
                    PointF point = draggedHandle == 1 ? start : end;
                    dragDeltaX = point.x - x;
                    StaticLayout layout = renderedPage.renderLayout;
                    int offset = firstLayoutOffset() + (draggedHandle == 1
                            ? selectionStart : selectionEnd - 1);
                    int line = layout.getLineForOffset(offset);
                    dragDeltaY = getPaddingTop() - renderedPage.layoutTop
                            + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f - y;
                    if (selectionMenu != null) selectionMenu.hide(-1);
                } else {
                    clearTextSelection();
                    consumeGesture = true; // Dismissal must not also turn the page.
                }
            } else if (hitText(x, y)) {
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(x - downX, y - downY)
                    > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                removeCallbacks(longPress);
            }
            if (draggedHandle != 0 && selectionStart >= 0) {
                int offset = offsetAt(x + dragDeltaX, y + dragDeltaY);
                offset = ReaderTextSelection.boundary(renderedPage.text, offset);
                if (draggedHandle == 1 && offset < selectionEnd) selectionStart = offset;
                if (draggedHandle == 2 && offset > selectionStart) selectionEnd = offset;
                invalidate();
                if (selectionMenu != null) selectionMenu.hide(-1);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            removeCallbacks(longPress);
            draggedHandle = 0;
            if (selectionMenu != null) {
                selectionMenu.hide(0);
                selectionMenu.invalidateContentRect();
            }
        }
        return consumeGesture;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float distance(float x, float y, PointF point) {
        return (float) Math.hypot(x - point.x, y - point.y);
    }

    private int firstLayoutOffset() {
        StaticLayout layout = renderedPage.renderLayout;
        return layout.getLineStart(layout.getLineForVertical(renderedPage.layoutTop));
    }

    private boolean hitText(float x, float y) {
        if (renderedPage == null || renderedPage.renderLayout == null
                || renderedPage.text.isEmpty()) return false;
        StaticLayout layout = renderedPage.renderLayout;
        float ly = y - getPaddingTop() + renderedPage.layoutTop;
        int line = layout.getLineForVertical((int) ly);
        float lx = x - getPaddingLeft();
        return ly >= renderedPage.layoutTop && ly < renderedPage.layoutBottom
                && lx >= layout.getLineLeft(line) && lx <= layout.getLineRight(line);
    }

    private int offsetAt(float x, float y) {
        StaticLayout layout = renderedPage.renderLayout;
        int ly = Math.max(renderedPage.layoutTop, Math.min(renderedPage.layoutBottom - 1,
                (int) (y - getPaddingTop() + renderedPage.layoutTop)));
        int offset = layout.getOffsetForHorizontal(layout.getLineForVertical(ly),
                x - getPaddingLeft()) - firstLayoutOffset();
        return Math.max(0, Math.min(renderedPage.text.length(), offset));
    }

    private void selectWord() {
        if (!hitText(downX, downY) || !isAttachedToWindow()) return;
        int[] range = ReaderTextSelection.wordAt(renderedPage.text, offsetAt(downX, downY));
        if (range[0] == range[1]) return;
        selectionStart = range[0];
        selectionEnd = range[1];
        consumeGesture = true;
        if (onSelectionStarted != null) onSelectionStarted.run();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        selectionMenu = startActionMode(new ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, android.R.id.copy, Menu.NONE, android.R.string.copy)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != android.R.id.copy || selectionStart < 0) return false;
                ClipboardManager clipboard = (ClipboardManager)
                        getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(
                        "", renderedPage.text.substring(selectionStart, selectionEnd)));
                mode.finish();
                return true;
            }
            @Override public void onDestroyActionMode(ActionMode mode) {
                selectionMenu = null;
                selectionStart = selectionEnd = -1;
                draggedHandle = 0;
                invalidate();
            }
            @Override public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                if (!hasTextSelection()) {
                    outRect.setEmpty();
                    return;
                }
                PointF start = handlePoint(true);
                PointF end = handlePoint(false);
                outRect.set((int) Math.min(start.x, end.x),
                        (int) Math.max(0, Math.min(start.y, end.y) - getTextSize() - dp(12)),
                        (int) Math.max(start.x, end.x) + 1,
                        (int) Math.max(start.y, end.y));
            }
        }, ActionMode.TYPE_FLOATING);
        if (selectionMenu == null) clearTextSelection();
        invalidate();
    }

    private PointF handlePoint(boolean start) {
        StaticLayout layout = renderedPage.renderLayout;
        int offset = firstLayoutOffset() + (start ? selectionStart : selectionEnd);
        int line = layout.getLineForOffset(Math.max(0, start ? offset : offset - 1));
        float x = !start && offset == layout.getLineEnd(line)
                ? (layout.getParagraphDirection(line) == 1
                    ? layout.getLineRight(line) : layout.getLineLeft(line))
                : layout.getPrimaryHorizontal(offset);
        float bottom = getPaddingTop() + layout.getLineBottom(line) - renderedPage.layoutTop;
        return new PointF(getPaddingLeft() + x,
                Math.min(getHeight() - dp(9), bottom + dp(8)));
    }

    void clearTextSelection() {
        removeCallbacks(longPress);
        if (selectionMenu != null) selectionMenu.finish();
        selectionMenu = null;
        selectionStart = selectionEnd = -1;
        draggedHandle = 0;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        clearTextSelection();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ReaderPage page = renderedPage;
        StaticLayout layout = page == null ? null : page.renderLayout;
        if (layout == null) {
            super.onDraw(canvas);
            return;
        }
        int contentLeft = getPaddingLeft();
        int contentTop = getPaddingTop();
        int contentRight = Math.max(contentLeft, getWidth() - getPaddingRight());
        int pageHeight = Math.max(0, page.layoutBottom - page.layoutTop);
        int contentBottom = Math.min(getHeight() - getPaddingBottom(),
                contentTop + pageHeight);
        canvas.save();
        canvas.clipRect(contentLeft, contentTop, contentRight, contentBottom);
        canvas.translate(contentLeft, contentTop - page.layoutTop);
        if (selectionStart >= 0) {
            selectionPath.reset();
            layout.getSelectionPath(firstLayoutOffset() + selectionStart,
                    firstLayoutOffset() + selectionEnd, selectionPath);
            selectionPaint.setColor(UiKit.withAlpha(getCurrentTextColor(), 60));
            canvas.drawPath(selectionPath, selectionPaint);
        }
        layout.draw(canvas);
        canvas.restore();
        if (selectionStart >= 0) {
            selectionPaint.setColor(getCurrentTextColor());
            PointF start = handlePoint(true);
            PointF end = handlePoint(false);
            canvas.drawCircle(start.x, start.y, dp(8), selectionPaint);
            canvas.drawCircle(end.x, end.y, dp(8), selectionPaint);
        }
    }
}
