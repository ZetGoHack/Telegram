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
import org.telegram.ui.Components.CubicBezierInterpolator;
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
        make(sheet.container, sheet.getContext(), null, icon, text, DURATION).show();
    }

    /** Shows over whatever screen is currently on top. */
    public static void show(int icon, CharSequence text) {
        show(icon, text, null);
    }

    /** Same, and a tap on the bulletin hides it and runs {@code onClick}. */
    public static void show(int icon, CharSequence text, Runnable onClick) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Bulletin bulletin;
        if (fragment.visibleDialog instanceof BottomSheet) {
            BottomSheet visible = (BottomSheet) fragment.visibleDialog;
            bulletin = make(visible.container, visible.getContext(), fragment.getResourceProvider(), icon, text, DURATION);
        } else {
            TimerLayout layout = new TimerLayout(fragment.getParentActivity(), fragment.getResourceProvider(), DURATION);
            layout.bind(icon, text);
            bulletin = Bulletin.make(fragment, layout, DURATION);
        }
        if (onClick != null) {
            bulletin.setOnClickListener(v -> {
                bulletin.hide();
                onClick.run();
            });
        }
        bulletin.show();
    }

    private static Bulletin make(FrameLayout container, Context context, Theme.ResourcesProvider resourcesProvider, int icon, CharSequence text, int duration) {
        TimerLayout layout = new TimerLayout(context, resourcesProvider, duration);
        layout.bind(icon, text);
        return Bulletin.make(container, layout, duration);
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

        @NonNull
        @Override
        public Transition createTransition() {
            return RawMotion.active() ? RawMotion.bulletinTransition() : super.createTransition();
        }

        @Override
        protected void onEnterTransitionStart() {
            super.onEnterTransitionStart();
            if (!RawMotion.active()) {
                return;
            }
            // the icon pops in with a small twist while the panel lands, the text follows it from the left
            imageView.setRotation(0f);
            RawMotion.popIn(imageView, 0.3f, -30f, 60, 420);
            textView.animate().cancel();
            textView.setAlpha(0f);
            textView.setTranslationX(-AndroidUtilities.dp(10));
            textView.animate().alpha(1f).translationX(0f)
                    .setStartDelay(100).setDuration(360).setInterpolator(RawMotion.EMPHASIZED).start();
        }
    }

    /**
     * Thin rounded line that shrinks from full width to zero over the bulletin duration. With rawGram motion the
     * line first draws itself out as the bulletin lands, then shrinks steadily (time stays linear) with a round
     * head, fading over the last moments.
     */
    private static class TimerBar extends View {
        private static final long FILL = 360;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int duration;
        private long startTime;
        private boolean motion;

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
            motion = RawMotion.active();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float y = getHeight() / 2f;
            float left = AndroidUtilities.dp(1);
            float right = getWidth() - AndroidUtilities.dp(1);
            canvas.drawLine(left, y, right, y, trackPaint);
            float progress = 1f;
            long elapsed = startTime != 0 ? SystemClock.elapsedRealtime() - startTime : 0;
            if (startTime != 0) {
                progress = 1f - Math.min(1f, elapsed / (float) duration);
            }
            if (motion && elapsed < FILL) {
                // draw out to the current length first
                progress *= CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(elapsed / (float) FILL);
            }
            if (progress > 0) {
                int alpha = paint.getAlpha();
                if (motion && progress < 0.08f && elapsed >= FILL) {
                    paint.setAlpha((int) (alpha * progress / 0.08f));
                }
                float end = left + (right - left) * progress;
                canvas.drawLine(left, y, end, y, paint);
                if (motion) {
                    canvas.drawCircle(end, y, AndroidUtilities.dp(1.6f), paint);
                }
                paint.setAlpha(alpha);
                invalidate();
            }
        }
    }
}
