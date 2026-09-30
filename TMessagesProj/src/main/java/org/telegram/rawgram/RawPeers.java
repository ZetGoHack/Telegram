package org.telegram.rawgram;

import android.text.TextUtils;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

/**
 * "Who is this id": resolves a peer from Telegram's local cache (MessagesController),
 * never touches the network. Ids are formatted via RawIds with the user's id format
 * (Bot API when the id display is off, so chats stay distinguishable from users).
 */
public class RawPeers {

    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_USER = 1;
    public static final int KIND_BOT = 2;
    public static final int KIND_GROUP = 3;
    public static final int KIND_CHANNEL = 4;

    public static class Info {
        /** Dialog id: user id as is, chats and channels negative. */
        public final long dialogId;
        public final int kind;
        public final String name;
        public final String username;
        public final String id;
        public final boolean isCached;

        Info(long dialogId, int kind, String name, String username, String id, boolean isCached) {
            this.dialogId = dialogId;
            this.kind = kind;
            this.name = name;
            this.username = username;
            this.id = id;
            this.isCached = isCached;
        }

        /** "Name (@username)  ·  id", or just the id when the peer isn't cached. */
        public String format() {
            if (TextUtils.isEmpty(name)) {
                return TextUtils.isEmpty(username) ? id : "@" + username + "  ·  " + id;
            }
            return name + (TextUtils.isEmpty(username) ? "" : " (@" + username + ")") + "  ·  " + id;
        }
    }

    public static Info resolve(int account, TLRPC.Peer peer) {
        if (peer == null) {
            return null;
        }
        // the Peer tells channel from basic group even when the chat isn't cached
        Boolean channel = peer instanceof TLRPC.TL_peerChannel ? Boolean.TRUE : peer instanceof TLRPC.TL_peerChat ? Boolean.FALSE : null;
        return resolve(account, DialogObject.getPeerDialogId(peer), channel);
    }

    public static Info resolve(int account, long dialogId) {
        return resolve(account, dialogId, null);
    }

    /** Sticker set owners, via-bots, contacts: plain user ids. */
    public static Info user(int account, long userId) {
        return resolve(account, userId, null);
    }

    private static Info resolve(int account, long dialogId, Boolean channelHint) {
        MessagesController mc = MessagesController.getInstance(account);
        int format = idFormat();
        if (dialogId >= 0) {
            TLRPC.User user = mc.getUser(dialogId);
            String id = RawIds.format(dialogId, false, false, format);
            if (user == null) {
                return new Info(dialogId, KIND_UNKNOWN, null, null, id, false);
            }
            return new Info(dialogId, UserObject.isBot(user) ? KIND_BOT : KIND_USER,
                    UserObject.getUserName(user), UserObject.getPublicUsername(user), id, true);
        }
        long chatId = -dialogId;
        TLRPC.Chat chat = mc.getChat(chatId);
        if (chat == null) {
            // uncached: without a hint assume a channel/supergroup, basic groups are rare nowadays
            boolean channel = channelHint == null || channelHint;
            return new Info(dialogId, KIND_UNKNOWN, null, null, RawIds.format(chatId, channel, !channel, format), false);
        }
        return new Info(dialogId, ChatObject.isChannelAndNotMegaGroup(chat) ? KIND_CHANNEL : KIND_GROUP,
                chat.title, ChatObject.getPublicUsername(chat), RawIds.forChat(chat, format), true);
    }

    private static int idFormat() {
        int format = RawgramConfig.getIdFormat();
        return format == RawgramConfig.ID_OFF ? RawgramConfig.ID_BOTAPI : format;
    }
}
