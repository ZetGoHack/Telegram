package org.telegram.rawgram;

/**
 * Static caches in Telegram that keep the context they were created with. When the activity is recreated (theme,
 * language, configuration), the old one stayed reachable through them with its whole fragment stack.
 */
public final class RawLeaks {

    private RawLeaks() {
    }

    /** LaunchActivity.onDestroy: the per-bot singletons are made again, with the new activity, when a bot needs them. */
    public static void onActivityDestroyed() {
        try {
            org.telegram.ui.bots.BotLocation.rawClear();
            org.telegram.ui.bots.BotBiometry.rawClear();
            org.telegram.ui.bots.BotDownloads.rawClear();
        } catch (Throwable ignore) {
        }
    }
}
