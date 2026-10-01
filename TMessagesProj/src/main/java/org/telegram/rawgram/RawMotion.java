package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * rawGram motion language: one set of curves and timings for rawGram's own UI (its sheets, the compact message
 * menu row, the «Подробности» page, bulletins, the inline tray). Everything here is additive: when the switch is
 * off, or the system / power saver turns animations down, every helper is a no-op and the stock behavior stays.
 *
 * <p>Switch: {@link #isEnabled()} / {@link #setEnabled(boolean)} (SharedPreferences "rawgram_motion", key
 * "enabled", default true). {@link #active()} is what the helpers check: the switch AND system animations on AND
 * no power saver.
 */
public final class RawMotion {

    private static final String PREFS = "rawgram_motion";
    private static final String KEY_ENABLED = "enabled";

    /** Material "emphasized decelerate": fast start, long soft landing. Entrances. */
    public static final CubicBezierInterpolator EMPHASIZED = new CubicBezierInterpolator(0.05, 0.7, 0.1, 1);
    /** Gentle overshoot (~4%): things that pop into place. Softer than EASE_OUT_BACK. */
    public static final CubicBezierInterpolator SOFT_BACK = new CubicBezierInterpolator(0.34, 1.32, 0.64, 1);
    /** Emphasized accelerate: exits. */
    public static final CubicBezierInterpolator EXIT = new CubicBezierInterpolator(0.3, 0, 0.8, 0.15);

    /** Reveal cascade: first item delay, step between items, item duration, items that get their own delay. */
    public static final long REVEAL_DELAY = 70, REVEAL_STEP = 28, REVEAL_DURATION = 420;
    private static final int REVEAL_MAX_STEPS = 10, REVEAL_MAX_ITEMS = 16;

    private static Boolean enabled;

    private RawMotion() {
    }

    // ---- switch ----

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The user switch ("rawGram-анимации"); true by default. */
    public static boolean isEnabled() {
        if (enabled == null) {
            enabled = prefs().getBoolean(KEY_ENABLED, true);
        }
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        prefs().edit().putBoolean(KEY_ENABLED, value).apply();
    }

    /** The switch is on and the device is allowed to animate (system animator scale, power saver). */
    public static boolean active() {
        if (!isEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            return false;
        }
        return !LiteMode.isPowerSaverApplied();
    }

    // ---- reveal cascades ----

    /**
     * Sheet opening: once the root is first drawn, its sections (and the rows of {@code lists}, or of any
     * ScrollView's content, in place of their wrappers) rise 18dp and fade in one after another while the sheet
     * itself slides up, so the content lands a beat after the panel.
     */
    public static void reveal(ViewGroup root, ViewGroup... lists) {
        if (root == null || !active()) {
            return;
        }
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                ArrayList<View> sequence = new ArrayList<>();
                collect(root, lists, sequence);
                cascade(sequence, REVEAL_DELAY, REVEAL_STEP, 0, dp(18), REVEAL_DURATION);
                return true;
            }
        });
    }

    private static void collect(ViewGroup group, ViewGroup[] lists, ArrayList<View> out) {
        for (int i = 0; i < group.getChildCount() && out.size() < REVEAL_MAX_ITEMS; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            ViewGroup list = listInside(child, lists);
            if (list != null) {
                collect(list, null, out);
            } else {
                out.add(child);
            }
        }
    }

    /** The list to descend into for this child: one of {@code lists} it contains, or a ScrollView's content. */
    private static ViewGroup listInside(View child, ViewGroup[] lists) {
        if (lists != null) {
            for (ViewGroup list : lists) {
                if (list != null && (list == child || isAncestor(child, list))) {
                    return list;
                }
            }
        }
        if (child instanceof ScrollView && ((ScrollView) child).getChildCount() == 1 && ((ScrollView) child).getChildAt(0) instanceof ViewGroup) {
            return (ViewGroup) ((ScrollView) child).getChildAt(0);
        }
        return null;
    }

    private static boolean isAncestor(View ancestor, View view) {
        for (Object p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** The visible children of a group (descending into a ScrollView's content), for {@link #cascade}. */
    public static ArrayList<View> children(ViewGroup group) {
        ArrayList<View> out = new ArrayList<>();
        if (group != null) {
            collect(group, null, out);
        }
        return out;
    }

    /**
     * Fades the views in one after another from an offset ({@code dx, dy} px) to where they are. Views that are
     * invisible (alpha 0) are left alone. Delays stop growing after a few items so long lists don't lag behind.
     */
    public static void cascade(List<View> views, long delay, long step, float dx, float dy, long duration) {
        if (views == null || !active()) {
            return;
        }
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            float alpha = v.getAlpha();
            if (alpha <= 0f) {
                continue;
            }
            float tx = v.getTranslationX(), ty = v.getTranslationY();
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationX(tx + dx);
            v.setTranslationY(ty + dy);
            v.animate().alpha(alpha).translationX(tx).translationY(ty)
                    .setStartDelay(delay + Math.min(i, REVEAL_MAX_STEPS) * step)
                    .setDuration(duration)
                    .setInterpolator(EMPHASIZED)
                    .start();
        }
    }

    /** «Подробности» opening: the rows slide in from the right a little further than the page itself. */
    public static void cascadeFromRight(ViewGroup page) {
        if (page == null || !active()) {
            return;
        }
        cascade(children(page), 30, 22, dp(28), 0, 340);
    }

    /** Freshly expanded block: its rows settle down from 8dp as the height opens up. */
    public static void settle(ViewGroup block) {
        if (block == null || !active()) {
            return;
        }
        ArrayList<View> rows = children(block);
        for (int i = 0; i < rows.size(); i++) {
            View v = rows.get(i);
            float ty = v.getTranslationY();
            v.setTranslationY(ty - dp(8));
            v.animate().translationY(ty).setStartDelay(40 + Math.min(i, 6) * 30L).setDuration(380).setInterpolator(EMPHASIZED).start();
        }
    }

    /** Chips in a horizontal row pop in left to right (scale 0.7 → 1 with a soft overshoot). */
    public static void popRow(ViewGroup row, long delay, long step) {
        if (row == null || !active()) {
            return;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) {
                continue;
            }
            popIn(v, 0.7f, 0f, delay + Math.min(i, REVEAL_MAX_STEPS) * step, 340);
        }
    }

    /**
     * Scale (and optionally rotation) springs in with a soft overshoot while alpha fades in on its own curve
     * (an overshooting alpha would go past 1).
     */
    public static void popIn(View v, float fromScale, float fromRotation, long delay, long duration) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setScaleX(fromScale);
        v.setScaleY(fromScale);
        float rotation = v.getRotation();
        v.setRotation(rotation + fromRotation);
        v.animate().scaleX(1f).scaleY(1f).rotation(rotation)
                .setStartDelay(delay).setDuration(duration).setInterpolator(SOFT_BACK).start();
        ObjectAnimator alpha = ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f);
        alpha.setStartDelay(delay);
        alpha.setDuration(Math.min(200, duration));
        alpha.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        alpha.start();
    }

    /** Same as {@link #popRow}, but waits until the row is first drawn (the menu window is on screen). */
    public static void popRowOnShow(ViewGroup row, long delay, long step) {
        if (row == null || !active()) {
            return;
        }
        // pre-draw runs before the first frame, so the children never show at their final state first
        row.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (row.getViewTreeObserver().isAlive()) {
                    row.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                popRow(row, delay, step);
                return true;
            }
        });
    }

    // ---- touch feedback ----

    /**
     * Press feedback: the view sinks to {@code scale} while pressed and springs back on release.
     * Uses a StateListAnimator, so click/long-click listeners are untouched. Don't use on views that
     * {@link RawAnim#pop} on click (both would drive the scale).
     */
    public static void pressable(View view, float scale) {
        if (view == null || !active()) {
            return;
        }
        StateListAnimator animator = new StateListAnimator();
        animator.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled},
                scaleTo(view, scale, 110, CubicBezierInterpolator.EASE_OUT));
        animator.addState(new int[0], scaleTo(view, 1f, 300, SOFT_BACK));
        view.setStateListAnimator(animator);
    }

    private static Animator scaleTo(View view, float scale, long duration, TimeInterpolator interpolator) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, scale));
        a.setDuration(duration);
        a.setInterpolator(interpolator);
        return a;
    }

    /** Swaps a background with a short crossfade instead of a snap (selected / unselected card). */
    public static void swapBackground(View view, Drawable next) {
        if (view == null) {
            return;
        }
        Drawable current = view.getBackground();
        if (current == null || next == null || !active() || !view.isAttachedToWindow()) {
            view.setBackground(next);
            return;
        }
        if (current instanceof TransitionDrawable && ((TransitionDrawable) current).getNumberOfLayers() == 2) {
            current = ((TransitionDrawable) current).getDrawable(1);
        }
        TransitionDrawable transition = new TransitionDrawable(new Drawable[]{current, next});
        transition.setCrossFadeEnabled(true);
        view.setBackground(transition);
        transition.startTransition(220);
    }

    // ---- copy confirmation ----

    /**
     * "Copied" confirmation on the view that was tapped: an icon (ImageView / menu item icon) morphs into a check
     * and back; any other view gets a small check badge popping up at its right edge.
     */
    public static void copied(View view) {
        if (view == null || !active() || !view.isAttachedToWindow()) {
            return;
        }
        ImageView icon = null;
        if (view instanceof ImageView) {
            icon = (ImageView) view;
        } else if (view instanceof ActionBarMenuSubItem) {
            icon = ((ActionBarMenuSubItem) view).getImageView();
        }
        if (icon != null && icon.getDrawable() != null && icon.getVisibility() == View.VISIBLE) {
            CheckMorphDrawable.start(icon);
        } else {
            CheckBadgeDrawable.start(view);
        }
        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Exception ignore) {
        }
    }

    /** A check path (in a 24×24 box centered on 0,0) drawn progressively. */
    private static void drawCheck(Canvas canvas, Paint paint, float cx, float cy, float size, float progress, Path path, PathMeasure measure, Path segment) {
        if (progress <= 0f) {
            return;
        }
        float s = size / 24f;
        path.rewind();
        path.moveTo(cx - 6.5f * s, cy + 0.5f * s);
        path.lineTo(cx - 2f * s, cy + 5f * s);
        path.lineTo(cx + 7f * s, cy - 5f * s);
        measure.setPath(path, false);
        segment.rewind();
        measure.getSegment(0, measure.getLength() * Math.min(1f, progress), segment, true);
        canvas.drawPath(segment, paint);
    }

    private static float part(float t, float from, float to) {
        return Math.max(0f, Math.min(1f, (t - from) / (to - from)));
    }

    /** Wraps an icon: the icon shrinks away, a check draws itself in its place, holds, then the icon comes back. */
    private static class CheckMorphDrawable extends Drawable {
        private static final long DURATION = 1400;
        private final Drawable original;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), segment = new Path();
        private final PathMeasure measure = new PathMeasure();
        private float t;

        CheckMorphDrawable(Drawable original) {
            this.original = original;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFFFFFFF);
        }

        static void start(ImageView icon) {
            Drawable current = icon.getDrawable();
            if (current instanceof CheckMorphDrawable) {
                // already confirming: a second tap within the hold just keeps showing the check
                return;
            }
            CheckMorphDrawable morph = new CheckMorphDrawable(current);
            ColorFilter filter = icon.getColorFilter();
            if (filter == null) {
                // no tint on the view: draw the check in the theme's icon color
                morph.paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            }
            icon.setImageDrawable(morph);
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(DURATION);
            animator.setInterpolator(null);
            animator.addUpdateListener(a -> {
                morph.t = a.getAnimatedFraction();
                morph.invalidateSelf();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (icon.getDrawable() == morph) {
                        icon.setImageDrawable(morph.original);
                    }
                }
            });
            animator.start();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            // 0 … 0.13: icon out; 0.08 … 0.36: check in; hold; 0.8 … 1: check out, icon back
            float iconOut = CubicBezierInterpolator.EASE_IN.getInterpolation(part(t, 0f, 0.13f));
            float iconBack = SOFT_BACK.getInterpolation(part(t, 0.82f, 1f));
            float iconScale = t < 0.5f ? 1f - iconOut : iconBack;
            if (iconScale > 0.01f) {
                canvas.save();
                float s = 0.4f + 0.6f * iconScale;
                canvas.scale(s, s, cx, cy);
                original.setAlpha((int) (255 * Math.min(1f, iconScale)));
                original.setBounds(b);
                original.draw(canvas);
                original.setAlpha(255);
                canvas.restore();
            }
            float checkIn = part(t, 0.08f, 0.36f);
            float checkOut = part(t, 0.78f, 0.9f);
            if (checkIn > 0f && checkOut < 1f) {
                float pop = SOFT_BACK.getInterpolation(part(t, 0.08f, 0.3f));
                float size = Math.min(b.width(), b.height()) * 0.8f * (0.6f + 0.4f * pop) * (1f - 0.4f * checkOut);
                paint.setAlpha((int) (255 * (1f - checkOut)));
                drawCheck(canvas, paint, cx, cy, Math.max(size, dp(14)), CubicBezierInterpolator.EASE_OUT.getInterpolation(checkIn), path, measure, segment);
            }
        }

        @Override
        protected void onBoundsChange(@NonNull Rect bounds) {
            original.setBounds(bounds);
        }

        @Override
        public int getIntrinsicWidth() {
            return original.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return original.getIntrinsicHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            original.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            original.setColorFilter(colorFilter);
            if (colorFilter != null) {
                paint.setColor(0xFFFFFFFF);
            }
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A small accent disc with a check, popping up at the right edge of a row or card in its overlay. */
    private static class CheckBadgeDrawable extends Drawable {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), segment = new Path();
        private final PathMeasure measure = new PathMeasure();
        private float t;

        static void start(View view) {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) {
                return;
            }
            CheckBadgeDrawable badge = new CheckBadgeDrawable();
            badge.disc.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            badge.stroke.setColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            badge.setBounds(0, 0, view.getWidth(), view.getHeight());
            view.getOverlay().add(badge);
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1100);
            animator.setInterpolator(null);
            animator.addUpdateListener(a -> {
                badge.t = a.getAnimatedFraction();
                badge.invalidateSelf();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    view.getOverlay().remove(badge);
                }
            });
            animator.start();
        }

        CheckBadgeDrawable() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(dp(2));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float r = dp(11);
            float cx = b.right - dp(14) - r;
            float cy = b.exactCenterY();
            float in = SOFT_BACK.getInterpolation(part(t, 0f, 0.3f));
            float out = part(t, 0.8f, 1f);
            float scale = in * (1f - 0.3f * out);
            if (scale <= 0.01f) {
                return;
            }
            int alpha = (int) (255 * (1f - out));
            disc.setAlpha(alpha);
            stroke.setAlpha(alpha);
            canvas.drawCircle(cx, cy, r * scale, disc);
            drawCheck(canvas, stroke, cx, cy, r * 1.5f * scale, CubicBezierInterpolator.EASE_OUT.getInterpolation(part(t, 0.12f, 0.4f)), path, measure, segment);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ---- bulletins ----

    /**
     * Bulletin entrance with a little more life than the stock spring (lower damping, stiffer: one small
     * bounce), stock exit.
     */
    public static Bulletin.Layout.Transition bulletinTransition() {
        return new Bulletin.Layout.Transition() {
            @Override
            public void animateEnter(@NonNull Bulletin.Layout layout, @Nullable Runnable startAction, @Nullable Runnable endAction, @Nullable Consumer<Float> onUpdate, int bottomOffset) {
                Bulletin.Layout.IN_OUT_OFFSET_Y.setValue(layout, layout.getMeasuredHeight() + dp(8));
                if (onUpdate != null) {
                    onUpdate.accept(layout.getTranslationY());
                }
                SpringAnimation spring = new SpringAnimation(layout, Bulletin.Layout.IN_OUT_OFFSET_Y, 0);
                spring.getSpring().setDampingRatio(0.62f);
                spring.getSpring().setStiffness(520f);
                spring.addEndListener((animation, canceled, value, velocity) -> {
                    Bulletin.Layout.IN_OUT_OFFSET_Y.setValue(layout, 0);
                    if (!canceled && endAction != null) {
                        endAction.run();
                    }
                });
                if (onUpdate != null) {
                    spring.addUpdateListener((animation, value, velocity) -> onUpdate.accept(layout.getTranslationY()));
                }
                spring.start();
                if (startAction != null) {
                    startAction.run();
                }
            }

            @Override
            public void animateExit(@NonNull Bulletin.Layout layout, @Nullable Runnable startAction, @Nullable Runnable endAction, @Nullable Consumer<Float> onUpdate, int bottomOffset) {
                new Bulletin.Layout.SpringTransition().animateExit(layout, startAction, endAction, onUpdate, bottomOffset);
            }
        };
    }

    // ---- lists ----

    /** Item animator for rawGram lists: quick fades, moves on the emphasized curve, no change crossfade. */
    public static RecyclerView.ItemAnimator listAnimator() {
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setAddDuration(220);
        animator.setRemoveDuration(140);
        animator.setMoveDuration(320);
        animator.setMoveInterpolator(EMPHASIZED);
        return animator;
    }

    /**
     * Updates an adapter from {@code oldKeys} to {@code newKeys} (one key per row, equal keys = same row): rows
     * that stay keep their views and slide to their new place, new rows fade in. Falls back to
     * notifyDataSetChanged when motion is off. Rows are always rebound (contents treated as changed).
     */
    public static void dispatch(RecyclerView.Adapter<?> adapter, List<?> oldKeys, List<?> newKeys) {
        if (adapter == null) {
            return;
        }
        if (!active() || oldKeys == null || oldKeys.isEmpty() || newKeys == null) {
            adapter.notifyDataSetChanged();
            return;
        }
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldKeys.size();
            }

            @Override
            public int getNewListSize() {
                return newKeys.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                Object a = oldKeys.get(oldItemPosition);
                return a != null && a.equals(newKeys.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return false;
            }
        }, false).dispatchUpdatesTo(adapter);
    }

    // ---- small drawing helpers ----

    /**
     * Squash and stretch for a sliding thumb: extra length (px) along the travel at {@code progress}, zero at
     * rest. For self-drawn switches.
     */
    public static float stretch(float progress, float maxPx) {
        if (progress <= 0f || progress >= 1f || !active()) {
            return 0f;
        }
        return (float) Math.sin(Math.PI * progress) * maxPx;
    }
}
