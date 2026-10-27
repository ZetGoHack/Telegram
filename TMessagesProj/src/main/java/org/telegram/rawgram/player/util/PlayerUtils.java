package org.telegram.rawgram.player.util;

import android.graphics.Typeface;

/**
 * Small helpers the ported player code expects from the base app.
 */
public final class PlayerUtils {

    private PlayerUtils() {
    }

    public static Typeface regular() {
        return Typeface.DEFAULT;
    }
}
