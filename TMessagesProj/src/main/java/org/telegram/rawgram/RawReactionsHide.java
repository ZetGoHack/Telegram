package org.telegram.rawgram;

import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLRPC;

/**
 * No reactions bar in the message menu, chosen per chat type (exteraGram's HideReactionsInChannels / Groups /
 * PrivateChats, GPLv3).
 */
public final class RawReactionsHide {

    private RawReactionsHide() {
    }

    public static boolean hidden(TLRPC.Chat chat, TLRPC.User user) {
        if (chat != null) {
            return ChatObject.isChannelAndNotMegaGroup(chat)
                    ? RawChatUiConfig.hideReactionsChannels.get()
                    : RawChatUiConfig.hideReactionsGroups.get();
        }
        return user != null && RawChatUiConfig.hideReactionsPrivate.get();
    }
}
