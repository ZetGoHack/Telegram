package org.telegram.rawgram.player;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.rawgram.player.lyrics.Lyrics;
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

    /** Debug-menu check: the LRC parser reads times, the offset tag and plain text. */
    public static String selfCheck() {
        Lyrics synced = Lyrics.parse("[ar:x]\n[offset:+500]\n[00:01.50]one\n[00:03.5]two\n[00:05]three", 0);
        if (synced == null || !synced.synced || synced.lines.size() != 3) {
            throw new IllegalStateException("synced lyrics not parsed");
        }
        if (synced.lines.get(0).time != 1000 || synced.lines.get(1).time != 3000 || synced.lines.get(2).time != 4500) {
            throw new IllegalStateException("times " + synced.lines.get(0).time + "/" + synced.lines.get(1).time + "/" + synced.lines.get(2).time);
        }
        Lyrics plain = Lyrics.parse("a\nb", 0);
        if (plain == null || plain.synced || plain.lines.size() != 2) {
            throw new IllegalStateException("plain lyrics not parsed");
        }
        return "3 строки с метками, смещение, обычный текст";
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
