package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

/**
 * "Чаты: вид и поведение" settings, stored in their own shared preferences file ("rawgram_chat_ui").
 * Every value is cached in a static, and every default is stock Telegram behaviour.
 *
 * Several options are ports of Nagram / NekoX settings (GPLv3):
 * CenterActionBarTitle, hideTimeForSticker, ShowMessageID, UseEditedIcon + CustomEditedMessage,
 * HideShareButtonInChannel, disableSwipeToNext(Channel/Topic), Shortcuts*, DoubleTapAction(Out), Show* menu toggles,
 * ChatDecoration, customSavePath + SaveToChatSubfolder, showViewHistory, DeleteChatForBothSides (as "remember")
 * — https://github.com/NextAlone/Nagram, https://github.com/NekoX-Dev/NekoX.
 */
public class RawChatUiConfig {

    private static SharedPreferences prefs;

    private static SharedPreferences prefs() {
        if (prefs == null) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences("rawgram_chat_ui", Context.MODE_PRIVATE);
        }
        return prefs;
    }

    private static boolean ready() {
        return ApplicationLoader.applicationContext != null;
    }

    /** A cached boolean preference. */
    public static final class Flag {
        public final String key;
        private final boolean def;
        private int cached = -1;

        Flag(String key, boolean def) {
            this.key = key;
            this.def = def;
        }

        public boolean get() {
            if (cached < 0) {
                if (!ready()) {
                    return def;
                }
                cached = prefs().getBoolean(key, def) ? 1 : 0;
            }
            return cached == 1;
        }

        public void set(boolean value) {
            cached = value ? 1 : 0;
            prefs().edit().putBoolean(key, value).apply();
        }

        public void toggle() {
            set(!get());
        }
    }

    /** A cached int preference. */
    public static final class Choice {
        public final String key;
        private final int def;
        private int cached = Integer.MIN_VALUE;

        Choice(String key, int def) {
            this.key = key;
            this.def = def;
        }

        public int get() {
            if (cached == Integer.MIN_VALUE) {
                if (!ready()) {
                    return def;
                }
                cached = prefs().getInt(key, def);
            }
            return cached;
        }

        public void set(int value) {
            cached = value;
            prefs().edit().putInt(key, value).apply();
        }
    }

    // ---- look ----

    public static final Flag centerTitle = new Flag("centerTitle", false);
    public static final Flag hideStickerTime = new Flag("hideStickerTime", false);
    public static final Flag showMessageId = new Flag("showMessageId", false);
    public static final Flag hideChannelShare = new Flag("hideChannelShare", false);

    public static final int EDITED_STOCK = 0;
    public static final int EDITED_PENCIL = 1;
    public static final int EDITED_CUSTOM = 2;
    public static final Choice editedMode = new Choice("editedMode", EDITED_STOCK);

    private static String editedText;

    public static String getEditedText() {
        if (editedText == null) {
            editedText = ready() ? prefs().getString("editedText", "") : "";
        }
        return editedText;
    }

    public static void setEditedText(String value) {
        editedText = value == null ? "" : value.trim();
        prefs().edit().putString("editedText", editedText).apply();
    }

    /** Snow on the chat background: 0 — by date (stock), 1 — always, 2 — never. */
    public static final int SNOW_STOCK = 0;
    public static final int SNOW_ALWAYS = 1;
    public static final int SNOW_NEVER = 2;
    public static final Choice chatSnow = new Choice("chatSnow", SNOW_STOCK);

    // ---- behaviour ----

    public static final Flag noSwipeNextChannel = new Flag("noSwipeNextChannel", false);
    public static final Flag noSwipeNextTopic = new Flag("noSwipeNextTopic", false);

    /** Forwarding: start without the sender / without media captions (RawForward; session choice overrides). */
    public static final Flag fwdHideSender = new Flag("fwdHideSender", false);
    public static final Flag fwdHideCaptions = new Flag("fwdHideCaptions", false);

    /** «Информация о юзере» (@usinfobot) suggestion when the input is just an ID (RawUserInfo). */
    public static final Flag usinfobotHint = new Flag("usinfobotHint", false);

    /** The profile pencil for non-admins: ChatEditActivity as a read-only overview (RawChatViewOnly). */
    public static final Flag chatViewOnly = new Flag("chatViewOnly", true);

    /** Permissions: stickers / GIFs / games / inline bots as separate rows instead of «Стикеры и GIF» (RawPermissions). */
    public static final Flag splitMediaRights = new Flag("splitMediaRights", true);

    /** Chat header without the call icon (calls stay in the chat's ⋮ menu and in the profile). */
    public static final Flag hideCallButton = new Flag("hideCallButton", false);

    /** Round video messages: which camera starts (RawRoundCamera). Front = Telegram's behaviour. */
    public static final int ROUND_FRONT = 0, ROUND_BACK = 1, ROUND_ASK = 2;
    public static final Choice roundCamera = new Choice("roundCamera", ROUND_FRONT);

    /** Chat ⋮ menu: «К началу», jumps to the first message (Nagram's ChatMenuItemToBeginning). */
    public static final Flag menuToBeginning = new Flag("menuToBeginning", true);

    /** «Добавить в…»: copy a sticker / custom emoji into an own set (RawAddToPack). */
    public static final Flag addToPack = new Flag("addToPack", true);

    /** Saved files: folder inside Pictures / Movies / Download / Music (RawSaveFolder; Nagram's customSavePath). */
    private static String saveFolder;

    public static String getSaveFolder() {
        if (saveFolder == null) {
            saveFolder = ready() ? prefs().getString("saveFolder", RawSaveFolder.DEFAULT) : RawSaveFolder.DEFAULT;
        }
        return saveFolder;
    }

    public static void setSaveFolder(String value) {
        saveFolder = RawSaveFolder.sanitize(value);
        prefs().edit().putString("saveFolder", saveFolder).apply();
    }

    /** …plus a subfolder named after the chat (Nagram's saveToChatSubfolder). */
    public static final Flag saveByChat = new Flag("saveByChat", false);

    /** «Также удалить для …»: start with the last position instead of always unchecked (RawDeleteForAll). */
    public static final Flag rememberDeleteForAll = new Flag("rememberDeleteForAll", true);
    static final Flag lastDeleteForAll = new Flag("lastDeleteForAll", false);

    public static final Flag shortcutAdmins = new Flag("shortcutAdmins", false);
    public static final Flag shortcutPermissions = new Flag("shortcutPermissions", false);
    public static final Flag shortcutMembers = new Flag("shortcutMembers", false);
    public static final Flag shortcutRecentActions = new Flag("shortcutRecentActions", false);

    public static final int TAP_REACTION = 0;
    public static final int TAP_REPLY = 1;
    public static final int TAP_COPY = 2;
    public static final int TAP_FORWARD = 3;
    public static final int TAP_EDIT = 4;
    public static final int TAP_SAVE = 5;
    public static final int TAP_NONE = 6;
    public static final int TAP_DELETE = 7;
    public static final Choice doubleTapIn = new Choice("doubleTapIn", TAP_REACTION);
    public static final Choice doubleTapOut = new Choice("doubleTapOut", TAP_REACTION);

    public static String doubleTapName(int action) {
        switch (action) {
            case TAP_REPLY: return "Ответить";
            case TAP_COPY: return "Копировать";
            case TAP_FORWARD: return "Переслать";
            case TAP_EDIT: return "Редактировать";
            case TAP_SAVE: return "В Избранное";
            case TAP_DELETE: return "Удалить";
            case TAP_NONE: return "Ничего";
            default: return "Реакция";
        }
    }

    // ---- message menu ----

    public static final Flag menuHideTranslate = new Flag("menuHideTranslate", false);
    public static final Flag menuHideReport = new Flag("menuHideReport", false);
    public static final Flag menuHidePin = new Flag("menuHidePin", false);
    public static final Flag menuHideSave = new Flag("menuHideSave", false);
    public static final Flag menuHideShare = new Flag("menuHideShare", false);
    public static final Flag menuHideCopyLink = new Flag("menuHideCopyLink", false);
    public static final Flag menuHideStatistics = new Flag("menuHideStatistics", false);
    public static final Flag menuHideFactCheck = new Flag("menuHideFactCheck", false);
    public static final Flag menuRepeat = new Flag("menuRepeat", false);
    public static final Flag menuSaveToSaved = new Flag("menuSaveToSaved", false);
    /** «История»: the chat search for the sender's messages (groups; Nagram's showViewHistory). */
    public static final Flag menuHistory = new Flag("menuHistory", true);
    /** Reply / delete / copy / edit as an icon row at the bottom of the message menu (RawMessageMenu). */
    public static final Flag menuCompact = new Flag("menuCompact", false);

    // ---- helpers for the hooks ----

    /** The "edited" word in the bubble time (ChatMessageCell), without the trailing space. */
    public static String editedMark() {
        int mode = editedMode.get();
        if (mode == EDITED_PENCIL) {
            return "✎";
        } else if (mode == EDITED_CUSTOM && !TextUtils.isEmpty(getEditedText())) {
            return getEditedText();
        }
        return LocaleController.getString(R.string.EditedMessage);
    }

    /** Appends " | id" to the bubble time when "ID сообщения" is on. */
    public static String withMessageId(String time, MessageObject message) {
        if (!showMessageId.get() || message == null || message.messageOwner == null || TextUtils.isEmpty(time)
                || message.getId() == 0 || message.isSponsored()) {
            return time;
        }
        return time + " | " + message.getId();
    }

    /** True when the sticker time + checks shouldn't be drawn. */
    public static boolean hideTimeFor(MessageObject message, boolean selected) {
        return hideStickerTime.get() && !selected && message != null && message.isAnyKindOfSticker();
    }

    /** True when the side share button of a channel post (or a post forwarded from a channel) should be hidden. */
    public static boolean hideShareButton(MessageObject message) {
        if (!hideChannelShare.get() || message == null || message.isSaved || message.messageOwner == null) {
            return false;
        }
        TLRPC.Message owner = message.messageOwner;
        return owner.post || owner.fwd_from != null && owner.fwd_from.from_id instanceof TLRPC.TL_peerChannel;
    }

    public static boolean allowSwipeToNext(boolean topic) {
        return !(topic ? noSwipeNextTopic : noSwipeNextChannel).get();
    }

    /** Chat background snow: the stock holiday check is {@code stock}. */
    public static boolean drawSnow(boolean hasBackground, boolean stock) {
        if (!hasBackground) {
            return false;
        }
        switch (chatSnow.get()) {
            case SNOW_ALWAYS: return true;
            case SNOW_NEVER: return false;
            default: return stock;
        }
    }
}
