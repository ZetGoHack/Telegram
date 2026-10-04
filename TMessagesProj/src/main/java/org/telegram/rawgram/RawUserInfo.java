package org.telegram.rawgram;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Optional @usinfobot integration (RawChatUiConfig.usinfobotHint): when the chat input is just an ID, the mentions list
 * shows a «Информация о юзере» row ({@link Hint}, a fake user so it looks like a mention suggestion). A tap sends the
 * ID as a background inline query to the bot and sends its first result (the full info; the second one is the bare ID).
 * The bot answers «¯\_(ツ)_/¯» when it knows nothing: then only a notice is shown. The field is cleared only after
 * a successful send, and only if it still holds the same ID.
 */
public final class RawUserInfo {

    public static final String BOT = "usinfobot";
    private static final String NOT_FOUND = "¯\\_(ツ)_/¯";
    private static final Pattern ID = Pattern.compile("-?\\d{5,20}");

    private static boolean busy;

    private RawUserInfo() {
    }

    /** The mention-like row; {@link #query} is the ID as typed. */
    public static final class Hint extends TLRPC.TL_user {
        public final String query;

        Hint(String query) {
            this.query = query;
        }
    }

    /** The single-row result for the mentions list, or null when the input isn't an ID (or the feature is off). */
    public static ArrayList<TLObject> hintFor(int account, String text, BaseFragment fragment) {
        if (!RawChatUiConfig.usinfobotHint.get() || !(fragment instanceof ChatActivity) || text == null) {
            return null;
        }
        ChatActivity chat = (ChatActivity) fragment;
        if (chat.getCurrentEncryptedChat() != null || chat.getChatMode() == ChatActivity.MODE_SCHEDULED) {
            return null;
        }
        String query = text.trim();
        if (!ID.matcher(query).matches()) {
            return null;
        }
        try {
            Long.parseLong(query);
        } catch (NumberFormatException e) {
            return null;
        }
        Hint hint = new Hint(query);
        hint.first_name = "Информация о юзере";
        hint.username = BOT;
        // the bot's avatar when it's cached, otherwise a letter avatar colored by the ID
        TLRPC.User bot = cachedBot(account);
        if (bot != null) {
            hint.id = bot.id;
            hint.access_hash = bot.access_hash;
            hint.photo = bot.photo;
        } else {
            hint.id = Math.abs(Long.parseLong(query));
        }
        ArrayList<TLObject> list = new ArrayList<>();
        list.add(hint);
        return list;
    }

    private static TLRPC.User cachedBot(int account) {
        TLObject object = MessagesController.getInstance(account).getUserOrChat(BOT);
        return object instanceof TLRPC.User ? (TLRPC.User) object : null;
    }

    public static void lookup(RawChatHooks.Host host, Hint hint) {
        if (busy) {
            return;
        }
        Utilities.Callback3<TLRPC.BotInlineResult, TLRPC.User, Runnable> sender = host.foreignInlineSender();
        if (sender == null || !(host.fragment() instanceof ChatActivity)) {
            RawNotify.show(R.drawable.msg_warning, "Здесь нельзя отправить результат бота");
            return;
        }
        int account = host.account();
        long dialogId = ((ChatActivity) host.fragment()).getDialogId();
        busy = true;
        resolveBot(account, bot -> {
            if (bot == null) {
                busy = false;
                RawNotify.show(R.drawable.msg_warning, "Не удалось найти @" + BOT);
                return;
            }
            TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
            req.bot = MessagesController.getInstance(account).getInputUser(bot);
            req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
            req.query = hint.query;
            req.offset = "";
            ConnectionsManager.getInstance(account).sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (!(res instanceof TLRPC.messages_BotResults)) {
                    RawNotify.show(R.drawable.msg_warning, "@" + BOT + ": " + (error != null ? error.text : "нет ответа"));
                    return;
                }
                TLRPC.messages_BotResults results = (TLRPC.messages_BotResults) res;
                MessagesController.getInstance(account).putUsers(results.users, false);
                TLRPC.BotInlineResult first = results.results.isEmpty() ? null : results.results.get(0);
                if (first == null || isNotFound(first)) {
                    RawNotify.show(R.drawable.msg_info, "Ничего не найдено по ID " + hint.query);
                    return;
                }
                first.query_id = results.query_id;
                sender.run(first, bot, () -> {
                    ChatActivityEnterView enterView = host.enterView();
                    CharSequence text = enterView != null ? enterView.getFieldText() : null;
                    if (text != null && hint.query.equals(text.toString().trim())) {
                        enterView.setFieldText("");
                    }
                });
            }));
        });
    }

    private static boolean isNotFound(TLRPC.BotInlineResult result) {
        String text = result.send_message != null ? result.send_message.message : null;
        return contains(text) || contains(result.title) || contains(result.description);
    }

    private static boolean contains(String s) {
        return !TextUtils.isEmpty(s) && s.contains(NOT_FOUND);
    }

    private static void resolveBot(int account, Utilities.Callback<TLRPC.User> done) {
        TLRPC.User cached = cachedBot(account);
        if (cached != null) {
            done.run(cached);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = BOT;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.User bot = null;
            if (res instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer peer = (TLRPC.TL_contacts_resolvedPeer) res;
                MessagesController.getInstance(account).putUsers(peer.users, false);
                MessagesController.getInstance(account).putChats(peer.chats, false);
                for (int i = 0; i < peer.users.size(); i++) {
                    if (BOT.equalsIgnoreCase(peer.users.get(i).username)) {
                        bot = peer.users.get(i);
                    }
                }
                if (bot == null && !peer.users.isEmpty()) {
                    bot = peer.users.get(0);
                }
            }
            done.run(bot);
        }));
    }
}
