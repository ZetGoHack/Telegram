package org.telegram.rawgram;

import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.ui.Components.CubicBezierInterpolator;

/** Shared animation helpers so rawGram UI moves the same way Telegram's does. */
public class RawAnim {

    public static final long DURATION = 280;

    /** Animates the next layout change of the container (expand/collapse, rows appearing). */
    public static void layout(ViewGroup container) {
        if (container == null) {
            return;
        }
        AutoTransition transition = new AutoTransition();
        transition.setDuration(DURATION);
        transition.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        transition.setOrdering(AutoTransition.ORDERING_TOGETHER);
        TransitionManager.beginDelayedTransition(rootOf(container), transition);
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
