package org.telegram.rawgram;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;

/**
 * A playing voice message or round video pauses when the app is minimized (exteraGram's PauseOnMinimizeVoice /
 * PauseOnMinimizeRound, GPLv3).
 */
public final class RawPauseOnMinimize {

    private RawPauseOnMinimize() {
    }

    /** LaunchActivity.onPause. */
    public static void onMinimized() {
        boolean voice = RawChatUiConfig.pauseVoiceOnMinimize.get();
        boolean round = RawChatUiConfig.pauseRoundOnMinimize.get();
        if (!voice && !round) {
            return;
        }
        MediaController controller = MediaController.getInstance();
        MessageObject playing = controller.getPlayingMessageObject();
        if (playing == null || controller.isMessagePaused() || !controller.isPlayingMessage(playing)) {
            return;
        }
        if ((voice && playing.isVoice()) || (round && playing.isRoundVideo())) {
            controller.pauseMessage(playing);
        }
    }
}
