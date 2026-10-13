package org.telegram.rawgram;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * «Отключить вибрацию» (Nagram's disableVibration): instead of a check at each of Telegram's ~270 haptic calls, every
 * view of a window gets haptic feedback switched off (re-applied after layouts, at most a few times a second, so views
 * added later are covered), and the shared helpers that bypass the view setting (AndroidUtilities.vibrate*, mini app vibration) check the flag.
 * Read when a window is created, so switching it applies after a restart.
 */
public final class RawHaptics {

    private RawHaptics() {
    }

    private static final long DELAY_MS = 250;
    private static int enabled = -1;
    private static final Map<View, Boolean> attached = new WeakHashMap<>();

    /** The switch as it was when the app started. */
    public static boolean off() {
        if (enabled < 0) {
            enabled = RawChatUiConfig.noVibration.get() ? 1 : 0;
        }
        return enabled == 1;
    }

    /** A window's root (activity content, dialog, popup): its views stop producing haptic feedback. */
    public static void attach(View root) {
        if (root == null || !off()) {
            return;
        }
        apply(root);
        if (attached.put(root, Boolean.TRUE) != null) {
            return; // a popup shown again
        }
        // views added later are caught after a layout, but the tree is walked at most once per DELAY_MS
        boolean[] pending = new boolean[1];
        Runnable walk = () -> {
            pending[0] = false;
            apply(root);
        };
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            if (!pending[0]) {
                pending[0] = true;
                root.postDelayed(walk, DELAY_MS);
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    private static void apply(View view) {
        if (view.isHapticFeedbackEnabled()) {
            view.setHapticFeedbackEnabled(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                apply(group.getChildAt(i));
            }
        }
    }
}
