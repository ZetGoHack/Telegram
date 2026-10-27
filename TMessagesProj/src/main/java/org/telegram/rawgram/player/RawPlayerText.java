package org.telegram.rawgram.player;

import org.telegram.messenger.ApplicationLoader;

/**
 * The player's strings live in strings_raw_player.xml, which the localization table of the app
 * (built from strings.xml only) does not know, so they are read from the resources directly.
 */
public final class RawPlayerText {

    private RawPlayerText() {
    }

    public static String get(int res) {
        return ApplicationLoader.applicationContext.getString(res);
    }

    public static String format(int res, Object... args) {
        return ApplicationLoader.applicationContext.getString(res, args);
    }
}
