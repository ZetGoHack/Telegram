package org.telegram.rawgram.badges;

import org.telegram.messenger.ApplicationLoader;

/**
 * Strings of {@code strings_raw_badges.xml}. LocaleController only knows strings.xml and would show LOC_ERR
 * for them, so they are read from the app resources.
 */
public final class RawBadgeText {

    private RawBadgeText() {
    }

    public static String get(int res) {
        return ApplicationLoader.applicationContext.getString(res);
    }

    public static String format(int res, Object... args) {
        return ApplicationLoader.applicationContext.getString(res, args);
    }
}
