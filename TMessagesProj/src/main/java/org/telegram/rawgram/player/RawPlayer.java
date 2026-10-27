package org.telegram.rawgram.player;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;

/**
 * Entry point of the rawGram music player. {@link #create} replaces {@code new AudioPlayerAlert(...)} at the call
 * sites: it returns the new sheet when it is switched on and the playing item is music, the stock alert otherwise.
 *
 * <p>Ported from exteraGram's PlayerLauncher (exteraSquad, GPL-2.0-or-later).
 */
public final class RawPlayer {

    private RawPlayer() {
    }

    public static BottomSheet create(Context context, Theme.ResourcesProvider resourcesProvider) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (RawPlayerConfig.getNewPlayerStyle() && (playing == null || playing.isMusic())) {
            return new MusicPlayerSheet(context, resourcesProvider);
        }
        return new AudioPlayerAlert(context, resourcesProvider);
    }

    /** Shuffle / repeat changed outside of the sheet (notification actions). */
    public static void onOrderChanged() {
        MusicPlayerSheet sheet = MusicPlayerSheet.instance;
        if (sheet != null) {
            sheet.updateOrderButtons(true);
        }
    }

    /** True when the dialog must stay open while the app is in the background. */
    public static boolean keepOnPause(Object dialog) {
        return dialog instanceof MusicPlayerSheet && ApplicationLoader.mainInterfacePaused;
    }
}
