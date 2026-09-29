package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** rawGram user settings, stored in their own shared preferences file. */
public class RawgramConfig {

    public static final int LONG_PRESS_MIN = 100;
    public static final int LONG_PRESS_MAX = 800;
    public static final int LONG_PRESS_STEP = 50;
    public static final int LONG_PRESS_DEFAULT = 200;

    public static final int STICKER_SCALE_MIN = 40;
    public static final int STICKER_SCALE_MAX = 160;
    public static final int STICKER_SCALE_STEP = 5;
    public static final int STICKER_SCALE_DEFAULT = 100;

    private static final String KEY_LONG_PRESS = "longPressDelay";
    private static final String KEY_STICKER_SCALE = "stickerScale";

    private static int longPressDelay = -1;
    private static int stickerScale = -1;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram", Context.MODE_PRIVATE);
    }

    /** Hold time in ms before a long press opens raw viewers and sticker/GIF previews. */
    public static int getLongPressDelay() {
        if (longPressDelay < 0) {
            longPressDelay = clamp(prefs().getInt(KEY_LONG_PRESS, LONG_PRESS_DEFAULT), LONG_PRESS_MIN, LONG_PRESS_MAX);
        }
        return longPressDelay;
    }

    public static void setLongPressDelay(int value) {
        longPressDelay = clamp(value, LONG_PRESS_MIN, LONG_PRESS_MAX);
        prefs().edit().putInt(KEY_LONG_PRESS, longPressDelay).apply();
    }

    /** Sticker size in chats, percent of the stock Telegram size. */
    public static int getStickerScalePercent() {
        if (stickerScale < 0) {
            stickerScale = clamp(prefs().getInt(KEY_STICKER_SCALE, STICKER_SCALE_DEFAULT), STICKER_SCALE_MIN, STICKER_SCALE_MAX);
        }
        return stickerScale;
    }

    public static float getStickerScale() {
        return getStickerScalePercent() / 100f;
    }

    public static void setStickerScalePercent(int value) {
        stickerScale = clamp(value, STICKER_SCALE_MIN, STICKER_SCALE_MAX);
        prefs().edit().putInt(KEY_STICKER_SCALE, stickerScale).apply();
    }

    /** Last view chosen in the raw code block: JSON (true) or the flat field list (false). */
    public static boolean isRawViewJson() {
        return prefs().getBoolean("rawViewJson", false);
    }

    public static void setRawViewJson(boolean value) {
        prefs().edit().putBoolean("rawViewJson", value).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
