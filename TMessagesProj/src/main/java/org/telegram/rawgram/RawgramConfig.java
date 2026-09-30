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

    // ---- recent stickers: how many are kept locally (0 = the server's stickers_recent_limit) ----

    public static final int RECENT_STICKERS_MIN = 20;
    public static final int RECENT_STICKERS_MAX = 200;
    public static final int RECENT_STICKERS_STEP = 10;

    private static int recentStickersLimit = -1;

    public static int getRecentStickersLimit() {
        if (recentStickersLimit < 0) {
            if (org.telegram.messenger.ApplicationLoader.applicationContext == null) {
                return 0;
            }
            recentStickersLimit = prefs().getInt("recentStickersLimit", 0);
        }
        return recentStickersLimit;
    }

    public static void setRecentStickersLimit(int value) {
        recentStickersLimit = clamp(value, RECENT_STICKERS_MIN, RECENT_STICKERS_MAX);
        prefs().edit().putInt("recentStickersLimit", recentStickersLimit).apply();
    }

    /** The limit to apply: the user's choice, or the server's one if nothing was chosen. */
    public static int recentStickersLimit(int serverLimit) {
        int limit = getRecentStickersLimit();
        return limit > 0 ? limit : serverLimit;
    }

    /** Last view chosen in the raw code block: JSON (true) or the flat field list (false). */
    public static boolean isRawViewJson() {
        return prefs().getBoolean("rawViewJson", false);
    }

    public static void setRawViewJson(boolean value) {
        prefs().edit().putBoolean("rawViewJson", value).apply();
    }

    // ---- numbers: 100K vs 100 000 (read on every formatted number, so cached) ----

    private static int fullNumbers = -1;

    public static boolean isFullNumbers() {
        if (fullNumbers < 0) {
            if (ApplicationLoader.applicationContext == null) {
                return false;
            }
            fullNumbers = prefs().getBoolean("fullNumbers", false) ? 1 : 0;
        }
        return fullNumbers == 1;
    }

    public static void setFullNumbers(boolean value) {
        fullNumbers = value ? 1 : 0;
        prefs().edit().putBoolean("fullNumbers", value).apply();
    }

    // ---- chat: hide the soft keyboard when the message list is dragged ----

    private static int hideKeyboardOnScroll = -1;

    public static boolean isHideKeyboardOnScroll() {
        if (hideKeyboardOnScroll < 0) {
            if (ApplicationLoader.applicationContext == null) {
                return false;
            }
            hideKeyboardOnScroll = prefs().getBoolean("hideKeyboardOnScroll", false) ? 1 : 0;
        }
        return hideKeyboardOnScroll == 1;
    }

    public static void setHideKeyboardOnScroll(boolean value) {
        hideKeyboardOnScroll = value ? 1 : 0;
        prefs().edit().putBoolean("hideKeyboardOnScroll", value).apply();
    }

    // ---- peer id row in profiles ----

    public static final int ID_OFF = 0;
    public static final int ID_MTPROTO = 1;
    public static final int ID_BOTAPI = 2;

    public static int getIdFormat() {
        return prefs().getInt("idFormat", ID_BOTAPI);
    }

    public static void setIdFormat(int value) {
        prefs().edit().putInt("idFormat", value).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
