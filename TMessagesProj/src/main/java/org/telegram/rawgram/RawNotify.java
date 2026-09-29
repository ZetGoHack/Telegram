package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

/** rawGram notifications: Telegram bulletins with a bar at the bottom that shrinks until the bulletin hides. */
public class RawNotify {

    public static final int DURATION = 2750;

    /** Shows inside a bottom sheet (e.g. the raw viewer). */
    public static void show(BottomSheet sheet, int icon, CharSequence text) {
        if (sheet == null || !sheet.isShowing()) {
            show(icon, text);
            return;
        }
        make(sheet.container, sheet.getContext(), null, icon, text, DURATION);
    }

    /** Shows over whatever screen is currently on top. */
    public static void show(int icon, CharSequence text) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (fragment.visibleDialog instanceof BottomSheet) {
            BottomSheet visible = (BottomSheet) fragment.visibleDialog;
            make(visible.container, visible.getContext(), fragment.getResourceProvider(), icon, text, DURATION);
            return;
        }
        TimerLayout layout = new TimerLayout(fragment.getParentActivity(), fragment.getResourceProvider(), DURATION);
        layout.bind(icon, text);
        Bulletin.make(fragment, layout, DURATION).show();
    }

    private static void make(FrameLayout container, Context context, Theme.ResourcesProvider resourcesProvider, int icon, CharSequence text, int duration) {
        TimerLayout layout = new TimerLayout(context, resourcesProvider, duration);
        layout.bind(icon, text);
        Bulletin.make(container, layout, duration).show();
    }

    private static class TimerLayout extends Bulletin.SimpleLayout {
        private final TimerBar bar;

        TimerLayout(@NonNull Context context, Theme.ResourcesProvider resourcesProvider, int duration) {
            super(context, resourcesProvider);
            bar = new TimerBar(context, duration, getThemedColor(Theme.key_undo_infoColor));
            addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 3, Gravity.BOTTOM, 12, 0, 12, 3));
        }

        void bind(int icon, CharSequence text) {
            if (icon != 0) {
                imageView.setImageResource(icon);
            }
            textView.setText(text);
            textView.setSingleLine(false);
            textView.setMaxLines(3);
        }

        @Override
        protected void onShow() {
            super.onShow();
            bar.start();
        }
    }

    /** Thin rounded line that shrinks from full width to zero over the bulletin duration. */
    private static class TimerBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int duration;
        private long startTime;

        TimerBar(Context context, int duration, int color) {
            super(context);
            this.duration = duration;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            trackPaint.setColor((color & 0x00FFFFFF) | 0x26000000);
            trackPaint.setStrokeCap(Paint.Cap.ROUND);
            trackPaint.setStrokeWidth(AndroidUtilities.dp(2));
        }

        void start() {
            startTime = SystemClock.elapsedRealtime();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float y = getHeight() / 2f;
            float left = AndroidUtilities.dp(1);
            float right = getWidth() - AndroidUtilities.dp(1);
            canvas.drawLine(left, y, right, y, trackPaint);
            float progress = 1f;
            if (startTime != 0) {
                progress = 1f - Math.min(1f, (SystemClock.elapsedRealtime() - startTime) / (float) duration);
            }
            if (progress > 0) {
                canvas.drawLine(left, y, left + (right - left) * progress, y, paint);
                invalidate();
            }
        }
    }
}
