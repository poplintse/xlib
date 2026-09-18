package com.xlib.txtreader;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.text.StaticLayout;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.os.Build;
import android.view.Gravity;
import android.view.textclassifier.TextClassifier;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.view.ViewGroup;

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
    private PopupWindow selectionMenu;
    private Runnable onSelectionStarted;
    private final Runnable longPress = this::selectWord;

    ReaderPageTextView(Context context) {
        super(context);
        setTextIsSelectable(false);
        setLongClickable(false);
        if (Build.VERSION.SDK_INT >= 26) setTextClassifier(TextClassifier.NO_OP);
    }

    // This view draws and owns its selection. Never enter the framework/OEM editor.
    @Override public ActionMode startActionMode(ActionMode.Callback callback) { return null; }
    @Override public ActionMode startActionMode(ActionMode.Callback callback, int type) { return null; }
    @Override public boolean showContextMenu() { return false; }
    @Override public boolean showContextMenu(float x, float y) { return false; }
    @Override public boolean onTextContextMenuItem(int id) { return false; }
    @Override public boolean performLongClick() { return true; }

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
                    dismissSelectionMenu();
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
                dismissSelectionMenu();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            removeCallbacks(longPress);
            draggedHandle = 0;
            if (action == MotionEvent.ACTION_UP && hasTextSelection()) showSelectionMenu();
            else if (action != MotionEvent.ACTION_UP) clearTextSelection();
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
        showSelectionMenu();
        invalidate();
    }

    private void showSelectionMenu() {
        dismissSelectionMenu();
        if (!hasTextSelection() || !isAttachedToWindow()) return;
        int foreground = getCurrentTextColor();
        boolean dark = Color.red(foreground) + Color.green(foreground) + Color.blue(foreground) > 382;
        int background = dark ? UiKit.DARK_SURFACE : UiKit.LIGHT_SURFACE;
        int text = dark ? UiKit.DARK_TEXT : UiKit.LIGHT_TEXT;
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding((int) dp(4), (int) dp(4), (int) dp(4), (int) dp(4));
        actions.setBackground(UiKit.rounded(getContext(), background, 16));
        Button copy = selectionButton(getContext().getString(android.R.string.copy), text);
        copy.setOnClickListener(v -> {
            if (!hasTextSelection() || renderedPage == null) return;
            ClipboardManager clipboard = (ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("",
                        renderedPage.text.substring(selectionStart, selectionEnd)));
                clearTextSelection();
            }
        });
        Button cancel = selectionButton(getContext().getString(android.R.string.cancel), text);
        cancel.setOnClickListener(v -> clearTextSelection());
        actions.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Rect visible = new Rect();
        getWindowVisibleDisplayFrame(visible);
        int[] screen = new int[2];
        int[] window = new int[2];
        getLocationOnScreen(screen);
        getLocationInWindow(window);
        visible.offset(window[0] - screen[0], window[1] - screen[1]);
        visible.inset((int) dp(8), (int) dp(8));
        int width = Math.min((int) dp(184), visible.width());
        if (width <= 0 || visible.height() <= 0) return;
        actions.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(visible.height(), MeasureSpec.AT_MOST));
        int height = actions.getMeasuredHeight();
        PointF start = handlePoint(true);
        PointF end = handlePoint(false);
        int x = ReaderTextSelection.clampMenuCoordinate(
                window[0] + (int) ((start.x + end.x) / 2) - width / 2,
                visible.left, visible.right, width);
        int y = ReaderTextSelection.menuTop(
                window[1] + (int) (Math.min(start.y, end.y) - getTextSize() - dp(12)),
                window[1] + (int) Math.max(start.y, end.y), height,
                visible.top, visible.bottom, (int) dp(12));
        selectionMenu = new PopupWindow(actions, width, height, false);
        // Keep touches outside the two buttons in our reader/handle gesture pipeline.
        selectionMenu.setOutsideTouchable(false);
        selectionMenu.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        selectionMenu.setBackgroundDrawable(UiKit.rounded(getContext(), background, 16));
        selectionMenu.setElevation(dp(8));
        selectionMenu.showAtLocation(this, Gravity.TOP | Gravity.LEFT, x, y);
    }

    private Button selectionButton(String label, int color) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setContentDescription(label);
        UiKit.styleButton(getContext(), button, Color.TRANSPARENT, color, 14);
        button.setTextSize(14);
        button.setMinHeight((int) dp(48));
        button.setMinimumHeight((int) dp(48));
        button.setPadding((int) dp(12), 0, (int) dp(12), 0);
        return button;
    }

    private void dismissSelectionMenu() {
        PopupWindow menu = selectionMenu;
        selectionMenu = null;
        if (menu != null) menu.dismiss();
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
        dismissSelectionMenu();
        selectionStart = selectionEnd = -1;
        draggedHandle = 0;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        clearTextSelection();
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) clearTextSelection();
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
