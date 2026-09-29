package org.telegram.rawgram;

import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.ui.Components.CubicBezierInterpolator;

/** Shared animation helpers so rawGram UI moves the same way Telegram's does. */
public class RawAnim {

    public static final long DURATION = 280;

    /** Animates the next layout change of the container (expand/collapse, rows appearing). */
    public static void layout(ViewGroup container) {
        layout(container, null);
    }

    /**
     * @param appearing a view that goes from GONE to VISIBLE: it keeps the bounds it had when it was hidden,
     *                  so it (and its children) must only fade in, never slide from that stale spot
     */
    public static void layout(ViewGroup container, View appearing) {
        if (container == null) {
            return;
        }
        // rows move first; appearing content fades in once there is room for it,
        // disappearing content fades out quickly so it never overlaps what slides in
        android.transition.TransitionSet set = new android.transition.TransitionSet();
        set.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        android.transition.ChangeBounds bounds = new android.transition.ChangeBounds();
        bounds.setDuration(DURATION);
        bounds.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        if (appearing != null) {
            bounds.excludeTarget(appearing, true);
            bounds.excludeChildren(appearing, true);
        }
        android.transition.Fade fadeOut = new android.transition.Fade(android.transition.Fade.OUT);
        fadeOut.setDuration(90);
        android.transition.Fade fadeIn = new android.transition.Fade(android.transition.Fade.IN);
        fadeIn.setDuration(180);
        fadeIn.setStartDelay(DURATION / 2);
        if (appearing != null) {
            // a delayed Fade leaves the view fully opaque until its animator starts, which shows as a blink:
            // hide it right away and fade it in by hand once the rows have made room
            fadeIn.excludeTarget(appearing, true);
            appearing.animate().cancel();
            appearing.setAlpha(0f);
            appearing.animate().alpha(1f).setStartDelay(DURATION / 2).setDuration(180)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        }
        set.addTransition(bounds).addTransition(fadeOut).addTransition(fadeIn);
        TransitionManager.beginDelayedTransition(rootOf(container), set);
    }

    /** Scroll views and sheets resize with their content: animate from the topmost ViewGroup. */
    private static ViewGroup rootOf(ViewGroup view) {
        ViewGroup root = view;
        while (root.getParent() instanceof ViewGroup) {
            root = (ViewGroup) root.getParent();
        }
        return root;
    }

    /**
     * Expands or collapses a block by animating its height and alpha from wherever it is now.
     * Deterministic on every run (unlike layout transitions, which remember stale bounds of hidden views);
     * the parents simply re-layout each frame, so everything below slides along.
     */
    public static void expand(View view, boolean show) {
        if (view == null) {
            return;
        }
        Object running = view.getTag(org.telegram.messenger.R.id.rawgram_expand_animator);
        if (running instanceof android.animation.Animator) {
            ((android.animation.Animator) running).cancel();
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        int from = view.getVisibility() == View.VISIBLE ? view.getHeight() : 0;
        int to = 0;
        if (show) {
            View parent = (View) view.getParent();
            int width = parent != null ? parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight() : view.getWidth();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                width -= ((ViewGroup.MarginLayoutParams) lp).leftMargin + ((ViewGroup.MarginLayoutParams) lp).rightMargin;
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            to = view.getMeasuredHeight();
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
            }
            view.setVisibility(View.VISIBLE);
        }
        final int start = from;
        final int end = to;
        final float alphaFrom = view.getAlpha();
        final float alphaTo = show ? 1f : 0f;
        lp.height = start;
        view.setLayoutParams(lp);
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            lp.height = (int) (start + (end - start) * t);
            // content shows up in the second half of an expand, disappears in the first half of a collapse
            float alphaT = show ? Math.max(0f, (t - 0.35f) / 0.65f) : Math.min(1f, t / 0.5f);
            view.setAlpha(alphaFrom + (alphaTo - alphaFrom) * alphaT);
            view.setLayoutParams(lp);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                view.setTag(org.telegram.messenger.R.id.rawgram_expand_animator, null);
                if (cancelled) {
                    return;
                }
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setLayoutParams(lp);
                view.setAlpha(alphaTo);
                if (!show) {
                    view.setVisibility(View.GONE);
                }
            }
        });
        view.setTag(org.telegram.messenger.R.id.rawgram_expand_animator, animator);
        animator.start();
    }

    /** Short fade out, swap, fade in: for content that changes in place (code block, preview). */
    public static void crossfade(View view, Runnable swap) {
        if (view == null || !view.isAttachedToWindow()) {
            swap.run();
            return;
        }
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(90).setInterpolator(CubicBezierInterpolator.EASE_OUT).withEndAction(() -> {
            swap.run();
            view.animate().alpha(1f).setDuration(160).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }).start();
    }

    /** Small pop for something that just changed state (a chip, a button, the auto spinner). */
    public static void pop(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setScaleX(0.9f);
        view.setScaleY(0.9f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(DURATION).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
    }
}
