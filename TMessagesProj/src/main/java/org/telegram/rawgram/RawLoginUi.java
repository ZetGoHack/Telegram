package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LauncherIconController;

/**
 * Login screen (IntroActivity): the rawGram app icon over the first page's Telegram logo (the intro animation is
 * native GL, so the logo is covered, fading out as the pager leaves page 0), and a long press on it that works
 * although the full-screen ViewPager above gets all touches.
 */
public final class RawLoginUi {

    /** The Telegram circle of the intro's first frame is 150dp, centered in the 200x150dp texture. */
    private static final int LOGO_DP = 152;

    private RawLoginUi() {
    }

    public static View addLogo(FrameLayout parent) {
        LogoView logo = new LogoView(parent.getContext());
        parent.addView(logo, LayoutHelper.createFrame(LOGO_DP, LOGO_DP, Gravity.CENTER));
        return logo;
    }

    /** ViewPager.onPageScrolled: visible on page 0, fading while swiping to page 1. */
    public static void onPageScrolled(View logo, int position, float offset) {
        if (logo != null) {
            logo.setAlpha(position == 0 ? 1f - offset : 0f);
        }
    }

    /** A long press on the logo, detected on the pager's touches without taking them away from paging. */
    public static void attachLongPress(View pager, View logo, Runnable onLongPress) {
        int[] logoPos = new int[2], pagerPos = new int[2];
        GestureDetector detector = new GestureDetector(pager.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (logo.getAlpha() < 0.5f) {
                    return;
                }
                logo.getLocationOnScreen(logoPos);
                pager.getLocationOnScreen(pagerPos);
                float x = pagerPos[0] + e.getX(), y = pagerPos[1] + e.getY();
                if (x >= logoPos[0] && x <= logoPos[0] + logo.getWidth() && y >= logoPos[1] && y <= logoPos[1] + logo.getHeight()) {
                    logo.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    onLongPress.run();
                }
            }
        });
        pager.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return false;
        });
    }

    /** The current launcher icon (adaptive background + foreground) clipped to a circle, like the launcher shows it. */
    private static final class LogoView extends View {
        private final Drawable background, foreground;
        private final Path clip = new Path();

        LogoView(Context context) {
            super(context);
            LauncherIconController.LauncherIcon icon = LauncherIconController.LauncherIcon.DEFAULT;
            for (LauncherIconController.LauncherIcon i : LauncherIconController.LauncherIcon.values()) {
                if (LauncherIconController.isEnabled(i)) {
                    icon = i;
                    break;
                }
            }
            background = ContextCompat.getDrawable(context, icon.background);
            foreground = ContextCompat.getDrawable(context, icon.foreground);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            clip.rewind();
            clip.addCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, Path.Direction.CW);
            // adaptive icon layers are 108dp with the visible 72dp in the middle: draw them 1.5x larger than the circle
            int extra = Math.round(Math.min(w, h) * 0.25f);
            if (background != null) {
                background.setBounds(-extra, -extra, w + extra, h + extra);
            }
            if (foreground != null) {
                foreground.setBounds(-extra, -extra, w + extra, h + extra);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.clipPath(clip);
            if (background != null) {
                background.draw(canvas);
            }
            if (foreground != null) {
                foreground.draw(canvas);
            }
            canvas.restore();
        }
    }
}
