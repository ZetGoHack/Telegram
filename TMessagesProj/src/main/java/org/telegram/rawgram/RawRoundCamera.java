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

    /** A choice made in the «ask» menu, consumed when the next round video starts. */
    private static Boolean pendingFront;
    private static long pendingAt;
    /** A choice not used this soon (the start was stopped, e.g. by a permission request) is dropped. */
    private static final long PENDING_MS = 5000;
    /** Set while the start triggered by the menu runs, so it doesn't ask again. */
    private static boolean choosing;

    private RawRoundCamera() {
    }

    /** InstantCameraView.showCamera (InstantCameraView2 via initialFacing): true = front, false = main, null = keep Telegram's choice (the last used camera). */
    public static Boolean startFront() {
        if (pendingFront != null) {
            Boolean front = pendingFront;
            pendingFront = null;
            if (android.os.SystemClock.elapsedRealtime() - pendingAt <= PENDING_MS) {
                return front;
            }
        }
        int mode = RawChatUiConfig.roundCamera.get();
        // «Фронтальная» means front every time: InstantCameraView2 would otherwise reopen the last used camera
        return mode == RawChatUiConfig.ROUND_BACK ? Boolean.FALSE : mode == RawChatUiConfig.ROUND_FRONT ? Boolean.TRUE : null;
    }

    /** InstantCameraView2 (the Camera2 round video recorder): the camera it starts with instead of the last used one. */
    public static org.telegram.utils.recorder.round.RoundVideoSession.CameraFacing initialFacing(
            org.telegram.utils.recorder.round.RoundVideoSession.CameraFacing last) {
        Boolean front = startFront();
        if (front == null) {
            return last;
        }
        return front ? org.telegram.utils.recorder.round.RoundVideoSession.CameraFacing.FRONT
                : org.telegram.utils.recorder.round.RoundVideoSession.CameraFacing.BACK;
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
        pendingAt = android.os.SystemClock.elapsedRealtime();
        choosing = true;
        start.run();
    }
}
