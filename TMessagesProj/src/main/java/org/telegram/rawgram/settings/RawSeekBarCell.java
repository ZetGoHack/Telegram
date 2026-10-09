package org.telegram.rawgram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

/**
 * Settings slider in exteraGram's style: a bold accent title with the current value in a tinted pill next to
 * it, the minimum and maximum under the title (the one the thumb approaches lights up) and the seek bar below.
 * Values move in whole steps; the callback runs live while dragging.
 * Ported from exteraGram's AltSeekbar (exteraSquad, GPL).
 */
public class RawSeekBarCell extends FrameLayout {

    private static final int HEIGHT_DP = 112;
    private static final int SIDE_DP = 21;

    private final TextView titleView;
    private final AnimatedTextView valueView;
    private final TextView minView;
    private final TextView maxView;
    private final SeekBarView seekBar;

    private int min, max, step = 1;
    private int value;
    private String suffix = "";
    private Utilities.Callback<Integer> onChange;

    public RawSeekBarCell(Context context) {
        super(context);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        header.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        valueView = new AnimatedTextView(context, false, true, true) {
            private final Drawable pill = Theme.createRoundRectDrawable(dp(4), Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader), 0.15f));

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                pill.setBounds(0, 0, (int) (getPaddingLeft() + getDrawable().getCurrentWidth() + getPaddingRight()), getMeasuredHeight());
                pill.draw(canvas);
                super.onDraw(canvas);
            }
        };
        valueView.setAnimationProperties(0.45f, 0, 240, CubicBezierInterpolator.EASE_OUT_QUINT);
        valueView.setAllowCancel(true);
        valueView.setTypeface(AndroidUtilities.bold());
        valueView.setPadding(dp(5.33f), dp(2), dp(5.33f), dp(2));
        valueView.setTextSize(dp(12));
        header.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 17, Gravity.CENTER_VERTICAL, 6, 1, 0, 0));
        addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, SIDE_DP, 17, SIDE_DP, 0));

        FrameLayout limits = new FrameLayout(context);
        minView = new TextView(context);
        minView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        minView.setGravity(Gravity.LEFT);
        limits.addView(minView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        maxView = new TextView(context);
        maxView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        maxView.setGravity(Gravity.RIGHT);
        limits.addView(maxView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        addView(limits, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, SIDE_DP, 52, SIDE_DP, 0));

        seekBar = new SeekBarView(context, true, null);
        seekBar.setReportChanges(true);
        seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                int newValue = valueFor(progress);
                if (newValue != value) {
                    value = newValue;
                    updateTexts(true);
                    if (onChange != null) {
                        onChange.run(newValue);
                    }
                }
            }

            @Override
            public int getStepsCount() {
                return (max - min) / step;
            }
        });
        addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP, 6, 68, 6, 0));

        updateColors();
    }

    private int valueFor(float progress) {
        int steps = (max - min) / step;
        return min + Math.round(progress * steps) * step;
    }

    /**
     * @param title    bold label over the slider
     * @param suffix   unit appended to the value and to the limits ("%", " мс", or "")
     * @param onChange called with every new value while dragging
     */
    public void bind(String title, int min, int max, int step, int value, String suffix, Utilities.Callback<Integer> onChange) {
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.suffix = suffix == null ? "" : suffix;
        this.onChange = onChange;
        this.value = Math.max(min, Math.min(max, value));
        titleView.setText(title);
        minView.setText(min + this.suffix);
        maxView.setText(max + this.suffix);
        seekBar.setSeparatorsCount((max - min) / this.step + 1);
        seekBar.setProgress(max == min ? 0 : (this.value - min) / (float) (max - min));
        valueView.cancelAnimation();
        updateTexts(false);
    }

    private void updateTexts(boolean animated) {
        valueView.setText(value + suffix, animated);
        // the limit the value approaches is tinted with the accent, as in exteraGram
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        float range = max - min;
        float position = range <= 0 ? 0 : (value - min) / range;
        float toMax = Utilities.clamp((position - 0.75f) / 0.25f, 1f, 0f);
        float toMin = Utilities.clamp((0.25f - position) / 0.25f, 1f, 0f);
        maxView.setTextColor(ColorUtils.blendARGB(gray, accent, toMax));
        minView.setTextColor(ColorUtils.blendARGB(gray, accent, toMin));
    }

    private void updateColors() {
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader);
        titleView.setTextColor(accent);
        valueView.setTextColor(accent);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        minView.setTextColor(gray);
        maxView.setTextColor(gray);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY));
    }
}
