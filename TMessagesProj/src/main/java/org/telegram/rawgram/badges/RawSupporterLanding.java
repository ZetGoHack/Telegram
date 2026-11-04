package org.telegram.rawgram.badges;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.RawBadges;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.lang.ref.WeakReference;

/**
 * The welcome sheet's arrow flying into the badge next to your name when «Номерной бейдж» opens your profile:
 * a snapshot of the arrow is taken as the sheet closes, then drawn on the window overlay from there to the badge
 * of the opening profile, crossfading into the badge itself.
 * <p>
 * Ported from exteraGram's {@code SupporterBadgeLanding} (exteraGram, GPLv3).
 */
public final class RawSupporterLanding {

    /** A snapshot older than this is stale (the profile did not open right away). */
    private static final long MAX_AGE_MS = 2000;
    private static final long DURATION = 800;
    private static final float CROSSFADE_START = 0.35f;
    private static final float CROSSFADE_LENGTH = 0.45f;
    private static final int FALLBACK_Y_DP = 120;
    private static final int FALLBACK_SIZE_DP = 24;

    private static Pending pending;

    private RawSupporterLanding() {
    }

    private static final class Pending {
        final long userId;
        final Bitmap bitmap;
        final float screenX;
        final float screenY;
        final float contentSize;
        final WeakReference<View> cover;
        final long time = SystemClock.elapsedRealtime();

        Pending(long userId, Bitmap bitmap, float screenX, float screenY, float contentSize, View cover) {
            this.userId = userId;
            this.bitmap = bitmap;
            this.screenX = screenX;
            this.screenY = screenY;
            this.contentSize = contentSize;
            this.cover = new WeakReference<>(cover);
        }
    }

    /** Snapshots {@code view} (the arrow) for the flight; {@code contentScale} is the arrow's size within it. */
    static boolean prepare(long userId, View view, float contentScale) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        Bitmap bitmap = view instanceof TextureView ? ((TextureView) view).getBitmap() : null;
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        clear();
        pending = new Pending(userId, bitmap, location[0] + view.getWidth() / 2f, location[1] + view.getHeight() / 2f,
                view.getHeight() * contentScale, view.getRootView());
        return true;
    }

    private static void clear() {
        if (pending != null) {
            pending.bitmap.recycle();
            pending = null;
        }
    }

    /**
     * ProfileActivity.onTransitionAnimationStart (opening): flies a prepared snapshot into the badge of
     * {@code name} (the expanded name), holding the number tag until it lands.
     */
    public static void start(BaseFragment fragment, long userId, SimpleTextView name) {
        final Pending from = pending;
        if (from == null || from.userId != userId) {
            return;
        }
        pending = null;
        Activity activity = fragment.getParentActivity();
        if (activity == null || SystemClock.elapsedRealtime() - from.time > MAX_AGE_MS) {
            from.bitmap.recycle();
            return;
        }
        final View decorView = activity.getWindow().getDecorView();
        final View cover = from.cover.get();
        final WeakReference<SimpleTextView> nameRef = new WeakReference<>(name);
        final Flight flight = new Flight(from, () -> {
            SimpleTextView view = nameRef.get();
            return view == null ? null : RawSupporterEnterFrom.of(view, RawBadges.profileBadgeDrawable(view));
        });
        // the sheet's window is still there at first; follow to the activity once it goes
        final View.OnAttachStateChangeListener onDetach = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                v.removeOnAttachStateChangeListener(this);
                flight.moveTo(decorView);
            }
        };
        if (cover != null && cover != decorView && cover.isAttachedToWindow()) {
            flight.moveTo(cover);
            cover.addOnAttachStateChangeListener(onDetach);
        } else {
            flight.moveTo(decorView);
        }
        final RawSupporterHint hint = RawSupporterHint.peek(fragment);
        if (hint != null) {
            hint.hold();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(a -> {
            flight.progress = (float) a.getAnimatedValue();
            flight.invalidateSelf();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cover != null) {
                    cover.removeOnAttachStateChangeListener(onDetach);
                }
                flight.moveTo(null);
                flight.land();
                from.bitmap.recycle();
                try {
                    decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } catch (Exception ignore) {
                }
                if (hint != null) {
                    hint.release();
                }
            }
        });
        animator.setDuration(DURATION);
        animator.setInterpolator(null);
        animator.start();
    }

    interface Target {
        RawSupporterEnterFrom get();
    }

    /** The flying snapshot, drawn on a view's overlay. */
    private static final class Flight extends Drawable {
        private final Pending from;
        private final Target target;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int[] location = new int[2];
        private final RectF rect = new RectF();
        private final Rect savedBounds = new Rect();
        private View host;
        private RawSupporterEnterFrom lastTarget;
        float progress;

        Flight(Pending from, Target target) {
            this.from = from;
            this.target = target;
        }

        void moveTo(View view) {
            if (host == view) {
                return;
            }
            if (host != null) {
                host.getOverlay().remove(this);
            }
            host = view;
            if (view != null) {
                setBounds(0, 0, view.getWidth(), view.getHeight());
                view.getOverlay().add(this);
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (host == null) {
                return;
            }
            RawSupporterEnterFrom current = target.get();
            if (current != null) {
                lastTarget = current;
            }
            host.getLocationOnScreen(location);
            float fromX = from.screenX - location[0];
            float fromY = from.screenY - location[1];
            Drawable drawable = lastTarget != null ? lastTarget.drawable : null;
            float toX, toY, toSize;
            if (drawable != null) {
                toX = lastTarget.screenX - location[0];
                toY = lastTarget.screenY - location[1];
                toSize = lastTarget.scale * drawable.getIntrinsicWidth();
            } else {
                toX = fromX;
                toY = AndroidUtilities.dp(FALLBACK_Y_DP);
                toSize = AndroidUtilities.dp(FALLBACK_SIZE_DP);
            }
            float t = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(progress);
            float x = AndroidUtilities.lerp(fromX, toX, CubicBezierInterpolator.EASE_BOTH.getInterpolation(progress));
            float y = AndroidUtilities.lerp(fromY, toY, t);
            float size = AndroidUtilities.lerp(from.contentSize, toSize, t);
            float crossfade = Utilities.clamp((progress - CROSSFADE_START) / CROSSFADE_LENGTH, 1f, 0f);
            if (crossfade < 1f) {
                float scale = size / from.contentSize;
                float halfW = from.bitmap.getWidth() * scale / 2f;
                float halfH = from.bitmap.getHeight() * scale / 2f;
                rect.set(x - halfW, y - halfH, x + halfW, y + halfH);
                bitmapPaint.setAlpha((int) ((1f - crossfade) * 255));
                canvas.drawBitmap(from.bitmap, null, rect, bitmapPaint);
            }
            if (drawable != null) {
                if (crossfade > 0f) {
                    int w = drawable.getIntrinsicWidth();
                    int h = drawable.getIntrinsicHeight();
                    canvas.save();
                    float scale = size / w;
                    canvas.scale(scale, scale, x, y);
                    drawable.copyBounds(savedBounds);
                    drawable.setBounds((int) x - w / 2, (int) y - h / 2, (int) x + w / 2, (int) y + h / 2);
                    drawable.setAlpha((int) (crossfade * 255));
                    drawable.draw(canvas);
                    drawable.setBounds(savedBounds);
                    canvas.restore();
                }
                // the badge at its place stays hidden until the flight lands
                drawable.setAlpha(0);
            }
        }

        /** Shows the real badge again. */
        void land() {
            if (lastTarget != null) {
                lastTarget.drawable.setAlpha(255);
                lastTarget.invalidate();
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
