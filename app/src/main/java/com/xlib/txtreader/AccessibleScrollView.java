package com.xlib.txtreader;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

final class AccessibleScrollView extends ScrollView {
    AccessibleScrollView(Context context) {
        super(context);
    }

    AccessibleScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
