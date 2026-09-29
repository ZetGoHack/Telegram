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
