package org.telegram.rawgram;

import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ItemOptions;

/**
 * Which camera a round video message starts with (RawChatUiConfig.roundCamera): front (Telegram's behaviour), main
 * (rear), or ask — then holding the record button opens a menu from the same place, «Фронтальная» / «Основная», as in
 * Nagram (CameraInVideoMessages), and the chosen camera starts recording hands-free (locked).
 */
public final class RawRoundCamera {

    /** A choice made in the «ask» menu, consumed by the next InstantCameraView.showCamera. */
    private static Boolean pendingFront;
    /** Set while the start triggered by the menu runs, so it doesn't ask again. */
    private static boolean choosing;

    private RawRoundCamera() {
    }

    /** InstantCameraView.showCamera: true = front, false = main, null = keep Telegram's choice. */
    public static Boolean startFront() {
        if (pendingFront != null) {
            Boolean front = pendingFront;
            pendingFront = null;
            return front;
        }
        return RawChatUiConfig.roundCamera.get() == RawChatUiConfig.ROUND_BACK ? Boolean.FALSE : null;
    }

    /**
     * ChatActivityEnterView, when holding the record button starts a round video: in «ask» mode shows the camera
     * menu instead and returns true (the hold is consumed); {@code start} begins recording after the choice.
     */
    public static boolean askFirst(BaseFragment fragment, View anchor, Runnable start) {
        if (choosing) {
            choosing = false;
            return false;
        }
        if (RawChatUiConfig.roundCamera.get() != RawChatUiConfig.ROUND_ASK || fragment == null || anchor == null) {
            return false;
        }
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        ItemOptions.makeOptions(fragment, anchor)
                .add(R.drawable.msg_openprofile_solar, "Фронтальная", () -> choose(true, start))
                .add(R.drawable.msg_rear_camera_solar, "Основная", () -> choose(false, start))
                .setDrawScrim(false)
                .show();
        return true;
    }

    private static void choose(boolean front, Runnable start) {
        pendingFront = front;
        choosing = true;
        start.run();
    }
}
