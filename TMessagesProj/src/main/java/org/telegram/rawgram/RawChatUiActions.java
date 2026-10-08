package org.telegram.rawgram;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChannelAdminLogActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatUsersActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

/**
 * The chat-screen side of "Чаты: вид и поведение" (RawChatUiConfig): double tap actions, message menu
 * items, admin shortcuts in the chat menu and the centered chat title. ChatActivity reaches it through
 * {@link RawChatHooks#ui()}.
 *
 * Ideas and behaviour follow Nagram's DoubleTap / Show* / Shortcuts* / CenterActionBarTitle
 * (Nagram, GPLv3, https://github.com/NextAlone/Nagram — based on NekoX and Nekogram); the code here is
 * rewritten for Telegram 12.10.5.
 */
public class RawChatUiActions {

    // chat menu (header "⋮") items
    public static final int ITEM_ADMINS = 9310;
    public static final int ITEM_PERMISSIONS = 9311;
    public static final int ITEM_MEMBERS = 9312;
    public static final int ITEM_RECENT_ACTIONS = 9313;
    public static final int ITEM_TO_BEGINNING = 9314;

    // message menu items rawGram adds; ChatActivity.processSelectedOption hands them back here
    public static final int OPTION_REPEAT = 9320;
    public static final int OPTION_SAVE_TO_SAVED = 9321;
    public static final int OPTION_ADD_TO_PACK = 9322;
    public static final int OPTION_HISTORY = 9323;

    private final RawChatHooks.Host host;

    RawChatUiActions(RawChatHooks.Host host) {
        this.host = host;
    }

    private ChatActivity chat() {
        return host.fragment() instanceof ChatActivity ? (ChatActivity) host.fragment() : null;
    }

    private boolean noForwards(ChatActivity chat, MessageObject message) {
        return chat.isPeerNoForwards() || message.messageOwner != null && message.messageOwner.noforwards;
    }

    // ---- double tap ----

    private static int tapAction(MessageObject message) {
        return message.isOutOwner() ? RawChatUiConfig.doubleTapOut.get() : RawChatUiConfig.doubleTapIn.get();
    }

    /** ChatActivity's hasDoubleTap: null keeps Telegram's own check (the reaction), otherwise the answer. */
    public Boolean hasDoubleTap(View view) {
        ChatActivity chat = chat();
        if (chat == null || !(view instanceof ChatMessageCell)) {
            return null;
        }
        MessageObject message = ((ChatMessageCell) view).getPrimaryMessageObject();
        if (message == null) {
            return null;
        }
        int action = tapAction(message);
        if (action == RawChatUiConfig.TAP_REACTION) {
            return null;
        }
        return canRun(chat, message, action);
    }

    private boolean canRun(ChatActivity chat, MessageObject message, int action) {
        if (message.isSponsored() || message.isDateObject || message.isSending() || message.isEditing()
                || chat.isInScheduleMode() || chat.getActionBar() != null && chat.getActionBar().isActionModeShowed()) {
            return false;
        }
        switch (action) {
            case RawChatUiConfig.TAP_REPLY:
                return message.getId() > 0 && !noForwards(chat, message);
            case RawChatUiConfig.TAP_COPY:
                return !noForwards(chat, message) && (message.isDice() || !TextUtils.isEmpty(message.caption)
                        || message.type == MessageObject.TYPE_TEXT && !TextUtils.isEmpty(message.messageText));
            case RawChatUiConfig.TAP_SAVE:
                if (chat.getDialogId() == UserConfig.getInstance(host.account()).getClientUserId()) {
                    return false;
                }
                // fall through
            case RawChatUiConfig.TAP_FORWARD:
                return message.getId() > 0 && !noForwards(chat, message) && !message.needDrawBluredPreview()
                        && message.type != MessageObject.TYPE_JOINED_CHANNEL && message.messageOwner.action == null;
            case RawChatUiConfig.TAP_EDIT:
                return message.isOutOwner() && message.canEditMessage(chat.getCurrentChat()) && message.type != MessageObject.TYPE_POLL;
            case RawChatUiConfig.TAP_DELETE: {
                // same rule as the message menu's «Удалить»; the confirmation dialog offers what the rights allow
                MessageObject thread = chat.isThreadChat() ? chat.getThreadMessage() : null;
                if (thread != null && thread.getId() == message.getId()) {
                    return false;
                }
                if (message.messageOwner != null && message.messageOwner.action instanceof TLRPC.TL_messageActionTopicCreate) {
                    return false;
                }
                return message.canDeleteMessage(chat.isInScheduleMode(), chat.getCurrentChat());
            }
            default:
                return false;
        }
    }

    /** ChatActivity's onDoubleTap: returns true if rawGram handled (or swallowed) it. */
    public boolean onDoubleTap(View view) {
        ChatActivity chat = chat();
        if (chat == null || !(view instanceof ChatMessageCell)) {
            return false;
        }
        MessageObject message = ((ChatMessageCell) view).getPrimaryMessageObject();
        if (message == null) {
            return false;
        }
        int action = tapAction(message);
        if (action == RawChatUiConfig.TAP_REACTION) {
            return false;
        }
        if (action == RawChatUiConfig.TAP_NONE || !canRun(chat, message, action)) {
            return true;
        }
        MessageObject target = ((ChatMessageCell) view).getMessageObject();
        if (target == null) {
            target = message;
        }
        switch (action) {
            case RawChatUiConfig.TAP_REPLY:
                host.runMessageOption(target, ChatActivity.OPTION_REPLY);
                break;
            case RawChatUiConfig.TAP_COPY:
                host.runMessageOption(target, ChatActivity.OPTION_COPY);
                break;
            case RawChatUiConfig.TAP_FORWARD:
                host.runMessageOption(target, ChatActivity.OPTION_FORWARD);
                break;
            case RawChatUiConfig.TAP_EDIT:
                host.runMessageOption(target, target.isTodo() ? ChatActivity.OPTION_EDIT_TODO : ChatActivity.OPTION_EDIT);
                break;
            case RawChatUiConfig.TAP_SAVE:
                host.runMessageOption(target, OPTION_SAVE_TO_SAVED);
                break;
            case RawChatUiConfig.TAP_DELETE:
                // Telegram's own confirmation (OPTION_DELETE → createDeleteMessagesAlert), album-aware
                host.runMessageOption(target, ChatActivity.OPTION_DELETE);
                break;
        }
        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Exception ignore) {}
        return true;
    }

    // ---- message menu ----

    private static boolean hidden(int option) {
        switch (option) {
            case ChatActivity.OPTION_TRANSLATE:
                return RawChatUiConfig.menuHideTranslate.get();
            case ChatActivity.OPTION_REPORT_CHAT:
                return RawChatUiConfig.menuHideReport.get();
            case ChatActivity.OPTION_PIN:
            case ChatActivity.OPTION_UNPIN:
                return RawChatUiConfig.menuHidePin.get();
            case ChatActivity.OPTION_SAVE_TO_GALLERY:
            case ChatActivity.OPTION_SAVE_TO_GALLERY2:
            case ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC:
            case ChatActivity.OPTION_ADD_TO_GIFS:
                return RawChatUiConfig.menuHideSave.get();
            case ChatActivity.OPTION_SHARE:
                return RawChatUiConfig.menuHideShare.get();
            case ChatActivity.OPTION_COPY_LINK:
                return RawChatUiConfig.menuHideCopyLink.get();
            case ChatActivity.OPTION_STATISTICS:
            case ChatActivity.OPTION_VIEW_STATISTICS:
                return RawChatUiConfig.menuHideStatistics.get();
            case ChatActivity.OPTION_FACT_CHECK:
                return RawChatUiConfig.menuHideFactCheck.get();
            default:
                return false;
        }
    }

    /**
     * Removes the message menu items switched off in the settings and adds rawGram's extras. {@code canSend}:
     * the chat allows sending here and the message may be forwarded (createMenu's allowChatActions && !noforwards).
     */
    public void filterMessageMenu(MessageObject message, ArrayList<Integer> options, ArrayList<CharSequence> items, ArrayList<Integer> icons, boolean canSend) {
        ChatActivity chat = chat();
        if (chat == null || message == null || options.size() != items.size() || options.size() != icons.size()) {
            return;
        }
        for (int i = options.size() - 1; i >= 0; i--) {
            Integer option = options.get(i);
            if (option != null && hidden(option)) {
                options.remove(i);
                items.remove(i);
                icons.remove(i);
            }
        }
        if (RawAddToPack.stickerOf(message) != null) {
            int fave = options.indexOf(ChatActivity.OPTION_ADD_TO_STICKERS_OR_MASKS);
            int delete = options.indexOf(ChatActivity.OPTION_DELETE);
            int at = fave >= 0 ? fave + 1 : delete >= 0 ? delete : options.size();
            options.add(at, OPTION_ADD_TO_PACK);
            items.add(at, RawAddToPack.TITLE);
            icons.add(at, R.drawable.menu_sticker_add);
        }
        boolean plain = message.getId() > 0 && !message.isSponsored() && !message.needDrawBluredPreview() && message.messageOwner != null
                && message.messageOwner.action == null && message.type != MessageObject.TYPE_JOINED_CHANNEL && !noForwards(chat, message);
        if (plain) {
            addCopyOptions(chat, message, options, items, icons, canSend);
        }
        if (historyPeer(chat, message) != null) {
            // right after «В Избранное», else after «Переслать» / «Ответить»
            int at = options.indexOf(OPTION_SAVE_TO_SAVED);
            if (at < 0) {
                at = options.indexOf(ChatActivity.OPTION_FORWARD);
            }
            if (at < 0) {
                at = options.indexOf(ChatActivity.OPTION_REPLY);
            }
            at = at < 0 ? options.size() : at + 1;
            options.add(at, OPTION_HISTORY);
            items.add(at, "История");
            icons.add(at, R.drawable.msg_recent);
        }
    }

    /** «Повторить» and «В Избранное», after «Переслать» (or «Ответить») and before «Удалить». */
    private void addCopyOptions(ChatActivity chat, MessageObject message, ArrayList<Integer> options, ArrayList<CharSequence> items, ArrayList<Integer> icons, boolean canSend) {
        int at = options.indexOf(ChatActivity.OPTION_FORWARD);
        if (at < 0) {
            at = options.indexOf(ChatActivity.OPTION_REPLY);
        }
        at = at < 0 ? options.size() : at + 1;
        int delete = options.indexOf(ChatActivity.OPTION_DELETE);
        if (delete >= 0 && delete < at) {
            at = delete;
        }
        if (RawChatUiConfig.menuSaveToSaved.get() && chat.getDialogId() != UserConfig.getInstance(host.account()).getClientUserId()) {
            options.add(at, OPTION_SAVE_TO_SAVED);
            items.add(at, "В Избранное");
            icons.add(at, R.drawable.msg_saved);
        }
        if (RawChatUiConfig.menuRepeat.get() && canSend && !ChatObject.isMonoForum(chat.getCurrentChat())) {
            options.add(at, OPTION_REPEAT);
            items.add(at, "Повторить");
            icons.add(at, R.drawable.msg_retry);
        }
    }

    /** «История» (Nagram): the sender of a message in a group's main chat, whose messages the search can filter to. */
    private static TLRPC.Peer historyPeer(ChatActivity chat, MessageObject message) {
        TLRPC.Chat current = chat.getCurrentChat();
        if (!RawChatUiConfig.menuHistory.get() || current == null || ChatObject.isChannelAndNotMegaGroup(current)
                || chat.getChatMode() != 0 || message.getId() <= 0 || message.isSponsored() || message.messageOwner == null) {
            return null;
        }
        TLRPC.Peer peer = message.messageOwner.from_id;
        if (peer == null || peer.user_id == 0 && peer.chat_id == 0 && peer.channel_id == 0) {
            return null;
        }
        return peer;
    }

    /** After a message menu cell is made: «Добавить в…» gets its swipe-back page of sets. */
    public void bindMenuCell(org.telegram.ui.ActionBar.ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout,
                             org.telegram.ui.ActionBar.ActionBarMenuSubItem cell, Integer option, MessageObject message) {
        if (option == null || option != OPTION_ADD_TO_PACK) {
            return;
        }
        RawAddToPack.attachSubmenu(popupLayout, cell, host.account(), RawAddToPack.stickerOf(message),
                message != null ? message.messageOwner : null, host.resources(), host::closeMenu);
    }

    /** Called from processSelectedOption; returns true for rawGram's own options. */
    public boolean onMenuOption(int option, MessageObject message, MessageObject.GroupedMessages group) {
        if (option == OPTION_ADD_TO_PACK) {
            return true; // handled by the swipe-back page (bindMenuCell)
        }
        if (option == OPTION_HISTORY) {
            ChatActivity chat = chat();
            TLRPC.Peer peer = chat != null ? historyPeer(chat, message) : null;
            if (peer != null) {
                MessagesController controller = MessagesController.getInstance(host.account());
                if (peer.user_id != 0) {
                    host.searchFrom(controller.getUser(peer.user_id), null);
                } else {
                    host.searchFrom(null, controller.getChat(peer.channel_id != 0 ? peer.channel_id : peer.chat_id));
                }
            }
            return true;
        }
        if (option != OPTION_REPEAT && option != OPTION_SAVE_TO_SAVED) {
            return false;
        }
        ChatActivity chat = chat();
        if (chat == null || message == null) {
            return true;
        }
        ArrayList<MessageObject> list = new ArrayList<>();
        if (group != null && !group.messages.isEmpty()) {
            list.addAll(group.messages);
        } else {
            list.add(message);
        }
        int account = host.account();
        if (option == OPTION_SAVE_TO_SAVED) {
            long self = UserConfig.getInstance(account).getClientUserId();
            SendMessagesHelper.getInstance(account).sendMessage(list, self, false, false, true, 0, 0);
            BulletinFactory.of(chat).createSimpleBulletin(R.raw.saved_messages, "Сохранено в Избранное").show();
        } else {
            long dialogId = chat.getDialogId();
            MessageObject top = chat.isThreadChat() ? chat.getThreadMessage() : null;
            AlertsCreator.ensurePaidMessageConfirmation(account, dialogId, list.size(), payStars ->
                    SendMessagesHelper.getInstance(account).sendMessage(list, dialogId, true, false, true, 0, top, -1, payStars));
        }
        return true;
    }

    // ---- admin shortcuts in the chat menu ----

    /** Adds the enabled admin shortcuts to the chat "⋮" menu (lazily, like Telegram's own items). */
    public void addAdminShortcuts(ActionBarMenuItem headerItem, TLRPC.Chat chat) {
        if (headerItem == null || chat == null || !ChatObject.isChannel(chat) || !ChatObject.hasAdminRights(chat)) {
            return;
        }
        boolean any = false;
        if (RawChatUiConfig.shortcutPermissions.get() && ChatObject.canBlockUsers(chat)) {
            headerItem.lazilyAddSubItem(ITEM_PERMISSIONS, R.drawable.msg_permissions, chat.megagroup ? "Разрешения" : "Чёрный список");
            any = true;
        }
        if (RawChatUiConfig.shortcutAdmins.get()) {
            headerItem.lazilyAddSubItem(ITEM_ADMINS, R.drawable.msg_admins, "Администраторы");
            any = true;
        }
        if (RawChatUiConfig.shortcutMembers.get()) {
            headerItem.lazilyAddSubItem(ITEM_MEMBERS, R.drawable.msg_groups, chat.megagroup ? "Участники" : "Подписчики");
            any = true;
        }
        if (RawChatUiConfig.shortcutRecentActions.get()) {
            headerItem.lazilyAddSubItem(ITEM_RECENT_ACTIONS, R.drawable.msg_log, "Недавние действия");
            any = true;
        }
        if (any) {
            headerItem.lazilyAddColoredGap();
        }
    }

    /** «К началу» in the chat "⋮" menu (after «Поиск», as in Nagram). */
    public void addToBeginning(ActionBarMenuItem headerItem) {
        if (headerItem != null && RawChatUiConfig.menuToBeginning.get()) {
            headerItem.lazilyAddSubItem(ITEM_TO_BEGINNING, R.drawable.ic_upward, "К началу");
        }
    }

    /** Chat menu click; returns true if it was a rawGram shortcut. */
    public boolean onHeaderItemClick(int id) {
        if (id == ITEM_TO_BEGINNING) {
            ChatActivity chat = chat();
            if (chat != null) {
                // message ids start at 1; the chat loads around it and lands on the first one that still exists
                chat.scrollToMessageId(1, 0, false, 0, true, 0);
            }
            return true;
        }
        if (id != ITEM_ADMINS && id != ITEM_PERMISSIONS && id != ITEM_MEMBERS && id != ITEM_RECENT_ACTIONS) {
            return false;
        }
        ChatActivity chat = chat();
        TLRPC.Chat current = chat != null ? chat.getCurrentChat() : null;
        if (current == null) {
            return true;
        }
        if (id == ITEM_RECENT_ACTIONS) {
            chat.presentFragment(new ChannelAdminLogActivity(current));
            return true;
        }
        Bundle args = new Bundle();
        args.putLong("chat_id", current.id);
        args.putInt("type", id == ITEM_PERMISSIONS ? ChatUsersActivity.TYPE_KICKED : id == ITEM_MEMBERS ? ChatUsersActivity.TYPE_USERS : ChatUsersActivity.TYPE_ADMIN);
        ChatUsersActivity fragment = new ChatUsersActivity(args);
        fragment.setInfo(chat.getCurrentChatInfo());
        chat.presentFragment(fragment);
        return true;
    }

    // ---- centered chat title ----

    /**
     * End of ChatAvatarContainer.onLayout (chat screens only): centers the title and the subtitle on the action bar.
     * The views get centered gravity and symmetric bounds around the bar's middle, so text changes stay centered
     * without another layout pass. {@code minLeft} is where the text starts after the avatar.
     */
    public static void centerTitle(View container, int minLeft, SimpleTextView title, SimpleTextView subtitle) {
        if (!RawChatUiConfig.centerTitle.get() || !(container.getParent() instanceof View)) {
            return;
        }
        View parent = (View) container.getParent();
        int center = parent.getWidth() / 2 - container.getLeft();
        int half = Math.min(center - minLeft, container.getWidth() - center);
        if (half <= 0) {
            return;
        }
        if (title != null) {
            title.setRightDrawableOutside(false);
        }
        center(title, center, half);
        center(subtitle, center, half);
    }

    private static void center(SimpleTextView view, int center, int half) {
        if (view == null || view.getVisibility() == View.GONE) {
            return;
        }
        // keep the text centered on the bar even with an uneven padding
        int shift = (view.getPaddingRight() - view.getPaddingLeft()) / 2;
        int width = 2 * Math.max(0, half - Math.abs(shift));
        if (width <= 0) {
            return;
        }
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        int top = view.getTop(), bottom = view.getBottom();
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY));
        int left = center - width / 2 + shift;
        view.layout(left, top, left + width, bottom);
    }
}
