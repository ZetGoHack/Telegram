package org.telegram.rawgram.player;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

/**
 * Smoother track switching for the new music player (MediaController hooks): the fading-out player stops reacting to
 * the next one and is released off the critical path, and more music is loaded well before the end of the playlist.
 * With the new player style off, stock behaviour is kept.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later): MediaController.cleanupPlayer / playNextMessageWithoutOrder.
 */
public final class PlayerSwitching {

    /** How many tracks before the end of the playlist more music is requested. */
    private static final int PRELOAD_AHEAD = 10;
    private static final long DEFERRED_RELEASE_MS = 1000;

    private PlayerSwitching() {
    }

    /** Before the music player fades out: it must not pause itself when the next track starts. */
    public static void onFadeOutStart(VideoPlayer player) {
        if (RawPlayerConfig.getNewPlayerStyle()) {
            player.stopPausingOthers();
        }
    }

    /** Releases the old music player: deferred with the new player style, at once otherwise (stock). */
    public static void release(VideoPlayer player) {
        if (RawPlayerConfig.getNewPlayerStyle()) {
            player.releasePlayerDeferred(DEFERRED_RELEASE_MS);
        } else {
            player.releasePlayer(true);
        }
    }

    /** Requests more music when the playing track is within {@link #PRELOAD_AHEAD} tracks of the end in the playing direction. */
    public static void preloadAhead(MediaController controller, ArrayList<MessageObject> playlist, MessageObject playing, boolean savedMusicList, boolean playOrderReversed) {
        if (!RawPlayerConfig.getNewPlayerStyle()) {
            return;
        }
        int index = playlist.indexOf(playing);
        boolean nearEnd = savedMusicList ? index >= playlist.size() - PRELOAD_AHEAD : !playOrderReversed && index < PRELOAD_AHEAD;
        if (nearEnd) {
            controller.loadMoreMusic();
        }
    }
}
