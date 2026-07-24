package com.xlib.txtreader;

import android.content.Context;
import android.graphics.Canvas;
import android.text.StaticLayout;
import android.widget.TextView;

/** Draws the exact page slice produced by the background paginator. */
final class ReaderPageTextView extends TextView {
    private ReaderPage renderedPage;

    ReaderPageTextView(Context context) {
        super(context);
    }

    void setReaderPage(ReaderPage page, CharSequence accessibilityText) {
        renderedPage = page;
        super.setText(accessibilityText);
        invalidate();
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
        layout.draw(canvas);
        canvas.restore();
    }
}
