package org.telegram.rawgram;

import org.telegram.tgnet.TLRPC;

/**
 * Peer ids in the two conventions developers meet:
 * MTProto (the raw id from the API) and Bot API
 * (users as is, basic groups as -id, channels and supergroups as -100id).
 */
public class RawIds {

    public static String format(long id, boolean isChannel, boolean isChat, int format) {
        if (format == RawgramConfig.ID_BOTAPI) {
            if (isChannel) {
                return "-100" + id;
            } else if (isChat) {
                return "-" + id;
            }
        }
        return Long.toString(id);
    }

    public static String forUser(TLRPC.User user, int format) {
        return user == null ? null : format(user.id, false, false, format);
    }

    public static String forChat(TLRPC.Chat chat, int format) {
        if (chat == null) {
            return null;
        }
        boolean channel = !(chat instanceof TLRPC.TL_chat) && !(chat instanceof TLRPC.TL_chatForbidden) && !(chat instanceof TLRPC.TL_chatEmpty);
        return format(chat.id, channel, !channel, format);
    }

    public static String label(int format) {
        return "ID";
    }
}
