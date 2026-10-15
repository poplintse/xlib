package com.xlib.txtreader;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.SeekBar;

final class UiKit {
    static final int LIGHT_BACKGROUND = Color.rgb(246, 247, 244);
    static final int LIGHT_SURFACE = Color.rgb(255, 255, 252);
    static final int LIGHT_SURFACE_VARIANT = Color.rgb(232, 239, 236);
    static final int LIGHT_TEXT = Color.rgb(28, 31, 29);
    static final int LIGHT_MUTED = Color.rgb(99, 107, 102);
    static final int LIGHT_ACCENT = Color.rgb(26, 104, 91);
    static final int LIGHT_ACCENT_CONTAINER = Color.rgb(210, 239, 230);
    static final int LIGHT_SWITCH_DISABLED_TRACK = Color.rgb(216, 224, 220);

    static final int DARK_BACKGROUND = Color.rgb(16, 19, 18);
    static final int DARK_SURFACE = Color.rgb(28, 32, 30);
    static final int DARK_SURFACE_VARIANT = Color.rgb(42, 49, 46);
    static final int DARK_TEXT = Color.rgb(237, 241, 237);
    static final int DARK_MUTED = Color.rgb(174, 183, 178);
    static final int DARK_ACCENT = Color.rgb(137, 216, 197);
    static final int DARK_ACCENT_CONTAINER = Color.rgb(30, 79, 69);
    static final int DARK_SWITCH_DISABLED_TRACK = Color.rgb(54, 62, 58);

    private UiKit() {
    }

    static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    static Drawable rounded(Context context, int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radiusDp));
        return shape;
    }

    static Drawable roundedStroke(Context context, int color, int strokeColor,
                                  int radiusDp, int strokeDp) {
        GradientDrawable shape = (GradientDrawable) rounded(context, color, radiusDp);
        shape.setStroke(dp(context, strokeDp), strokeColor);
        return shape;
    }

    static Drawable interactive(Context context, int color, int radiusDp, int rippleColor) {
        Drawable content = rounded(context, color, radiusDp);
        Drawable mask = rounded(context, Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static void styleCard(Context context, View view, int color, int radiusDp, float elevationDp) {
        view.setBackground(rounded(context, color, radiusDp));
        view.setElevation(dp(context, (int) elevationDp));
        view.setClipToOutline(true);
    }

    static void styleIconButton(Context context, ImageButton button, int foreground,
                                int container, int radiusDp) {
        button.setColorFilter(foreground);
        button.setBackground(interactive(context, container, radiusDp,
                withAlpha(foreground, 30)));
        button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
    }

    static void styleButton(Context context, Button button, int background, int foreground,
                            int radiusDp) {
        button.setBackground(interactive(context, background, radiusDp,
                withAlpha(foreground, 28)));
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans", Typeface.BOLD));
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinWidth(0);
    }

    static void styleTitle(TextView view, int color, float sizeSp) {
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        view.setIncludeFontPadding(false);
    }

    static void styleReaderSeekBar(Context context, SeekBar seekBar, boolean dark) {
        int trackColor = dark ? Color.rgb(96, 112, 103) : Color.rgb(128, 142, 134);
        int progressColor = dark ? DARK_ACCENT : Color.rgb(14, 71, 60);
        Drawable track = rounded(context, trackColor, 3);
        ClipDrawable progress = new ClipDrawable(rounded(context, progressColor, 3),
                Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, progress});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            layers.setLayerHeight(i, dp(context, 6));
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        // Clear inherited widget tints so both themes keep the full track visible.
        seekBar.setProgressTintList(null);
        seekBar.setProgressBackgroundTintList(null);
        seekBar.setProgressDrawable(layers);
        seekBar.setSplitTrack(false);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(progressColor);
        thumb.setSize(dp(context, 20), dp(context, 20));
        thumb.setStroke(dp(context, 2), dark ? DARK_SURFACE : LIGHT_SURFACE);
        seekBar.setThumbTintList(null);
        seekBar.setThumb(thumb);
        seekBar.setThumbOffset(dp(context, 10));
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
