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

    // ---- recent stickers: how many the sticker panel shows (Telegram hardcodes 20; the server keeps up to 200) ----

    public static final int RECENT_STICKERS_MIN = 20;
    public static final int RECENT_STICKERS_MAX = 200;
    public static final int RECENT_STICKERS_STEP = 10;
    public static final int RECENT_STICKERS_DEFAULT = 20;

    private static int recentStickersShown = -1;

    public static int getRecentStickersShown() {
        if (recentStickersShown < 0) {
            if (org.telegram.messenger.ApplicationLoader.applicationContext == null) {
                return RECENT_STICKERS_DEFAULT;
            }
            recentStickersShown = clamp(prefs().getInt("recentStickersShown", RECENT_STICKERS_DEFAULT), RECENT_STICKERS_MIN, RECENT_STICKERS_MAX);
        }
        return recentStickersShown;
    }

    public static void setRecentStickersShown(int value) {
        recentStickersShown = clamp(value, RECENT_STICKERS_MIN, RECENT_STICKERS_MAX);
        prefs().edit().putInt("recentStickersShown", recentStickersShown).apply();
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

    // ---- MTProto request log (the hot-path flag is cached in RawRequestLog.enabled) ----

    public static boolean isRequestLog() {
        return prefs().getBoolean("requestLog", false);
    }

    /** Use RawRequestLog.setEnabled: it also flips the cached flag. */
    static void setRequestLog(boolean value) {
        prefs().edit().putBoolean("requestLog", value).apply();
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

    // ---- feature switches: off means Telegram behaves as stock there (cached: some are read on hot paths) ----

    /** A cached boolean preference. */
    private static final class Flag {
        final String key;
        final boolean def;
        int cached = -1;

        Flag(String key, boolean def) {
            this.key = key;
            this.def = def;
        }

        boolean get() {
            if (cached < 0) {
                if (ApplicationLoader.applicationContext == null) {
                    return def;
                }
                cached = prefs().getBoolean(key, def) ? 1 : 0;
            }
            return cached == 1;
        }

        void set(boolean value) {
            cached = value ? 1 : 0;
            prefs().edit().putBoolean(key, value).apply();
        }
    }

    private static final Flag messageDetails = new Flag("featMessageDetails", true);
    private static final Flag inlineRaw = new Flag("featInlineRaw", true);
    private static final Flag inlineTray = new Flag("featInlineTray", true);
    private static final Flag botButtonDebug = new Flag("featBotButtonDebug", true);
    private static final Flag previewRaw = new Flag("featPreviewRaw", true);
    private static final Flag objectRaw = new Flag("featObjectRaw", true);
    private static final Flag webAppData = new Flag("featWebAppData", true);
    private static final Flag hideAttachCamera = new Flag("hideAttachCamera", false);

    /** "Подробности" submenu in the message menu. */
    public static boolean isMessageDetails() {
        return messageDetails.get();
    }

    public static void setMessageDetails(boolean value) {
        messageDetails.set(value);
    }

    /** Long press raw viewer for inline results (with reroll and the send chooser). */
    public static boolean isInlineRaw() {
        return inlineRaw.get();
    }

    public static void setInlineRaw(boolean value) {
        inlineRaw.set(value);
    }

    /** "Put aside" button over inline results and the side button that brings them back. */
    public static boolean isInlineTray() {
        return inlineTray.get();
    }

    public static void setInlineTray(boolean value) {
        inlineTray.set(value);
    }

    /** Long press sheet on inline keyboard buttons and notices about silent callback answers. */
    public static boolean isBotButtonDebug() {
        return botButtonDebug.get();
    }

    public static void setBotButtonDebug(boolean value) {
        botButtonDebug.set(value);
    }

    /** Raw / copy document id items in sticker, GIF and emoji previews. */
    public static boolean isPreviewRaw() {
        return previewRaw.get();
    }

    public static void setPreviewRaw(boolean value) {
        previewRaw.set(value);
    }

    /** Raw items in profiles, sticker/emoji set sheets and the chat list preview menu. */
    public static boolean isObjectRaw() {
        return objectRaw.get();
    }

    public static void setObjectRaw(boolean value) {
        objectRaw.set(value);
    }

    /** "rawGram: данные" item in web app menus. */
    public static boolean isWebAppData() {
        return webAppData.get();
    }

    public static void setWebAppData(boolean value) {
        webAppData.set(value);
    }

    /** Hide the camera tile in the attach menu (read by ChatAttachAlert). Default off. */
    public static boolean isHideAttachCamera() {
        return hideAttachCamera.get();
    }

    public static void setHideAttachCamera(boolean value) {
        hideAttachCamera.set(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
