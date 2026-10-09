package org.telegram.rawgram;

import android.app.Activity;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * Small behaviour switches from «Чаты» that need more than a flag check at the hook: the call confirmation, the
 * remembered attach camera and video stabilization. Ports of Nagram's askBeforeCall, RememberLastUsedCamera and
 * CameraStabilization (GPLv3, https://github.com/NextAlone/Nagram).
 */
public final class RawBehaviour {

    private RawBehaviour() {
    }

    private static boolean callConfirmed;

    /**
     * Start of VoIPHelper.startCall (a user call). Returns true when the call waits for «Позвонить» in the dialog;
     * {@code proceed} then starts it again and passes through.
     */
    public static boolean askBeforeCall(Activity activity, TLRPC.User user, boolean video, Runnable proceed) {
        if (callConfirmed) {
            callConfirmed = false;
            return false;
        }
        if (!RawChatUiConfig.confirmCall.get() || activity == null || user == null) {
            return false;
        }
        String name = ContactsController.formatName(user.first_name, user.last_name);
        new AlertDialog.Builder(activity)
                .setTitle(video ? "Видеозвонок" : "Звонок")
                .setMessage(AndroidUtilities.replaceTags((video ? "Начать видеозвонок с **" : "Позвонить **") + name + "**?"))
                .setPositiveButton("Позвонить", (d, w) -> {
                    callConfirmed = true;
                    try {
                        proceed.run();
                    } finally {
                        callConfirmed = false;
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
        return true;
    }

    /** Camera in the attach menu: the face it opens with. */
    public static boolean attachCameraFront(boolean stock) {
        return RawChatUiConfig.cameraRemember.get() ? RawChatUiConfig.cameraLastFront.get() : stock;
    }

    /** CameraView.switchCamera: remember the face for the next attach camera. */
    public static void onCameraSwitched(boolean front) {
        if (RawChatUiConfig.cameraRemember.get()) {
            RawChatUiConfig.cameraLastFront.set(front);
        }
    }

    /** Camera2Session, video recording request: optical stabilization if the lens has it, else digital. */
    public static void stabilize(CaptureRequest.Builder builder, CameraCharacteristics characteristics) {
        if (!RawChatUiConfig.cameraStabilization.get() || builder == null || characteristics == null) {
            return;
        }
        try {
            int[] ois = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (ois != null) {
                for (int mode : ois) {
                    if (mode == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                        return;
                    }
                }
            }
            int[] vs = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (vs != null) {
                for (int mode : vs) {
                    if (mode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
