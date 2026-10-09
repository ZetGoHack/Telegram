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
 * DisableMarkdown, disableChoosingSticker, hideSendAsChannel, showSpoilersDirectly, askBeforeCall, confirmAVMessage,
 * ConfirmAllLinks, RememberLastUsedCamera, CameraStabilization, DisableTrending*, dontSendGreetingSticker, DisableStories,
 * HideStoriesFromHeader, disableVibration, RemoveMessageTail, DateOfForwardedMsg, unreadBadgeOnBackButton, ShowFullAbout,
 * ShowOnlineStatus, HideAiEditor, HideAiSummary, ShowCopyPhoto/Frame, showDeleteDownloadedFile, ShowSetReminder, ShowText*,
 * MediaViewerMenuItem*, ChatMenuItemGoToMessage, ChatMenuItemDeleteOwnMessages, openArchiveOnPull, DoNotUnarchiveBySwipe,
 * IgnoreUnreadCount, AlwaysShowDownloadIcon, tabStyleStroke
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
    /** The original date in the «Переслано от» line (Nagram's DateOfForwardedMsg). */
    public static final Flag forwardDate = new Flag("forwardDate", false);
    /** Profile «О себе» / description without the «ещё» fold (Nagram's ShowFullAbout). */
    public static final Flag fullAbout = new Flag("fullAbout", false);
    /** Unread chats count over the chat's back arrow (RawBackBadge; NekoX's unreadBadgeOnBackButton). */
    public static final Flag backBadge = new Flag("backBadge", false);
    /** Green dot on the avatars of online senders in groups (RawOnlineDot). */
    public static final Flag onlineDot = new Flag("onlineDot", false);
    /** No «Краткое содержание» (AI summary) button on long posts (Nagram's HideAiSummary). */
    public static final Flag hideAiSummary = new Flag("hideAiSummary", false);
    /** No AI button in the input field and captions (it shows from three lines on; Nagram's HideAiEditor). */
    public static final Flag hideAiEditor = new Flag("hideAiEditor", false);
    /** Bubbles without the tail (Nagram's RemoveMessageTail). */
    public static final Flag noBubbleTail = new Flag("noBubbleTail", false);

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
    /** Chat ⋮ menu: «Перейти к сообщению» (by ID or date) and «Удалить свои сообщения» in groups (RawChatJump). */
    public static final Flag menuGoToMessage = new Flag("menuGoToMessage", false);
    public static final Flag menuDeleteOwn = new Flag("menuDeleteOwn", false);

    /** «Добавить в…»: copy a sticker / custom emoji into an own set (RawAddToPack). */
    public static final Flag addToPack = new Flag("addToPack", true);

    /** Profile of a group linked to a channel: «Канал» action button next to «Уведомления» (Nagram has it in ⋮). */
    public static boolean linkedChannelButton(TLRPC.Chat chat, TLRPC.ChatFull info) {
        return chat != null && chat.megagroup && info != null && info.linked_chat_id != 0;
    }

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

    // ---- sending and input (Nagram's DisableMarkdown, disableChoosingSticker, hideSendAsChannel, showSpoilersDirectly) ----

    /** `code`, **bold**, __italic__, ~~strike~~ and ||spoiler|| are sent as typed text. */
    public static final Flag noMarkdown = new Flag("noMarkdown", false);
    /** «печатает» instead of «выбирает стикер» while the sticker panel is open. */
    public static final Flag typingForStickers = new Flag("typingForStickers", false);
    /** No «Отправить как» avatar button in the input of groups and channels. */
    public static final Flag hideSendAs = new Flag("hideSendAs", false);
    /** Text spoilers are shown revealed (media spoilers stay). */
    public static final Flag revealSpoilers = new Flag("revealSpoilers", false);

    /** No haptic feedback anywhere in the app (RawHaptics; after a restart). */
    public static final Flag noVibration = new Flag("noVibration", false);

    // ---- confirmations (askBeforeCall, confirmAVMessage, ConfirmAllLinks) ----

    public static final Flag confirmCall = new Flag("confirmCall", false);
    /** Releasing the record button stops into the preview instead of sending. */
    public static final Flag confirmVoice = new Flag("confirmVoice", false);
    public static final Flag confirmLinks = new Flag("confirmLinks", false);

    // ---- camera (RememberLastUsedCamera, CameraStabilization) ----

    public static final Flag cameraRemember = new Flag("cameraRemember", false);
    public static final Flag cameraStabilization = new Flag("cameraStabilization", false);
    static final Flag cameraLastFront = new Flag("cameraLastFront", false);

    // ---- promo and suggestions (DisableTrending*, dontSendGreetingSticker) ----

    public static final Flag hideTrendingStickers = new Flag("hideTrendingStickers", false);
    public static final Flag hideTrendingGifs = new Flag("hideTrendingGifs", false);
    public static final Flag hideTrendingEmoji = new Flag("hideTrendingEmoji", false);
    public static final Flag hideEmojiTags = new Flag("hideEmojiTags", false);

    public static final Flag hidePhoneShare = new Flag("hidePhoneShare", false);
    public static final Flag hidePaidReaction = new Flag("hidePaidReaction", false);
    public static final Flag noGreetingSticker = new Flag("noGreetingSticker", false);
    /** Main screen: Premium offers above the chats list, birthdays of contacts. */
    public static final Flag hidePremiumHints = new Flag("hidePremiumHints", false);
    public static final Flag hideBirthdays = new Flag("hideBirthdays", false);
    /** Stories: off everywhere (rings, header, profile, posting) / only the row above the chats list. */
    public static final Flag disableStories = new Flag("disableStories", false);
    public static final Flag hideStoriesHeader = new Flag("hideStoriesHeader", false);

    // ---- main screen (RawMainScreen) ----

    public static final Flag archiveOnPull = new Flag("archiveOnPull", false);
    public static final Flag noUnarchiveSwipe = new Flag("noUnarchiveSwipe", false);
    public static final Flag noTabCounters = new Flag("noTabCounters", false);
    public static final Flag alwaysDownloads = new Flag("alwaysDownloads", false);
    public static final Flag tabStroke = new Flag("tabStroke", false);

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
    /** «Копировать фото» / «Копировать кадр», «Удалить скачанный файл», «Напомнить» (RawMessageExtras). */
    public static final Flag menuCopyPhoto = new Flag("menuCopyPhoto", false);
    public static final Flag menuDeleteFile = new Flag("menuDeleteFile", false);
    public static final Flag menuReminder = new Flag("menuReminder", false);
    /** Text formatting menu: bitmask of hidden items (RawFormatMenu). */
    /** Media viewer ⋮: «Копировать кадр», «Поставить на аватарку», «Сканировать QR-код» (RawViewerExtras). */
    public static final Flag viewerExtras = new Flag("viewerExtras", false);
    public static final Choice formatHidden = new Choice("formatHidden", 0);
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

    /** «Переслано от …» + " · date time" of the original message when «Дата оригинала» is on. */
    public static String withForwardDate(String line, MessageObject message) {
        if (!forwardDate.get() || line == null || message == null || message.messageOwner == null
                || message.messageOwner.fwd_from == null || message.messageOwner.fwd_from.date == 0) {
            return line;
        }
        long date = message.messageOwner.fwd_from.date;
        String day = LocaleController.formatDate(date);
        String time = LocaleController.getInstance().getFormatterDay().format(new java.util.Date(date * 1000L));
        return line + " · " + (day.equals(time) ? day : day + " " + time);
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
