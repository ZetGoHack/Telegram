package org.telegram.rawgram;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatUsersActivity;

/**
 * Split media rights (RawChatUiConfig.splitMediaRights): Telegram shows send_stickers / send_gifs / send_games /
 * send_inline as one «Стикеры и GIF» row and toggles them together; the server keeps them as four separate flags,
 * so rawGram shows four rows (like Nagram). Counters grow from 10 to 13 media rights.
 */
public final class RawPermissions {

    public static final String STICKERS = "Стикеры";
    public static final String GIFS = "GIF";
    public static final String GAMES = "Игры";
    public static final String INLINE = "Через инлайн-ботов";

    private RawPermissions() {
    }

    public static boolean split() {
        return RawChatUiConfig.splitMediaRights.get();
    }

    /** Extra rows compared to Telegram's count. */
    public static int extra() {
        return split() ? 3 : 0;
    }

    public static int mediaTotal() {
        return 10 + extra();
    }

    /** Allowed media rights, counting GIFs / games / inline separately when split. */
    public static int mediaCount(TLRPC.TL_chatBannedRights rights) {
        int count = ChatUsersActivity.getSendMediaSelectedCount(rights);
        if (split()) {
            if (!rights.send_gifs) {
                count++;
            }
            if (!rights.send_games) {
                count++;
            }
            if (!rights.send_inline) {
                count++;
            }
        }
        return count;
    }
}
