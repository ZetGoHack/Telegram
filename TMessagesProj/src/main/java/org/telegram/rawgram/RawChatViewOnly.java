package org.telegram.rawgram;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.TextCell;

/**
 * The profile pencil for non-admins (RawChatUiConfig.chatViewOnly): ChatEditActivity opens as a read-only overview
 * with only what the server gives ordinary members — default permissions (ChatUsersActivity read-only), the admin
 * list (each admin's rights open read-only), members (unless hidden) and statistics/boosts (the regular page). Broadcast channels
 * only show boosts: their admin/subscriber lists are admin-only.
 */
public final class RawChatViewOnly {

    private RawChatViewOnly() {
    }

    /** The pencil should be shown for this chat even without admin rights. */
    public static boolean canOpen(TLRPC.Chat chat) {
        return RawChatUiConfig.chatViewOnly.get() && chat != null && !ChatObject.hasAdminRights(chat) && !ChatObject.canChangeChatInfo(chat);
    }

    /** ChatEditActivity / ChatUsersActivity opened by a non-admin: read-only overview. */
    public static boolean isViewOnly(TLRPC.Chat chat) {
        return canOpen(chat);
    }

    /** Hides what a member can't see or use; called after ChatEditActivity updates its rows. */
    public static void restrict(int account, TLRPC.Chat chat, TLRPC.ChatFull info, View reactions, View inviteLinks, View memberRequests, View log,
                                View affiliate, View permissions, View admins, View members) {
        gone(reactions);
        gone(inviteLinks);
        gone(memberRequests);
        gone(log);
        gone(affiliate);
        boolean broadcast = ChatObject.isChannelAndNotMegaGroup(chat);
        show(permissions, !broadcast && !chat.gigagroup);
        show(admins, !broadcast);
        show(members, !broadcast && (info == null || !info.participants_hidden));
        if (!broadcast && admins instanceof TextCell) {
            loadAdminCount(account, chat, info, (TextCell) admins);
        }
    }

    /** channelFull.admins_count is only filled for admins: count the admin list members can load. */
    private static void loadAdminCount(int account, TLRPC.Chat chat, TLRPC.ChatFull info, TextCell cell) {
        if (!ChatObject.isChannel(chat) || info == null || info.admins_count > 0 || cell.getTag(TAG_LOADING) != null) {
            return;
        }
        cell.setTag(TAG_LOADING, Boolean.TRUE);
        TLRPC.TL_channels_getParticipants req = new TLRPC.TL_channels_getParticipants();
        req.channel = MessagesController.getInstance(account).getInputChannel(chat.id);
        req.filter = new TLRPC.TL_channelParticipantsAdmins();
        req.offset = 0;
        req.limit = 200;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.TL_channels_channelParticipants) {
                TLRPC.TL_channels_channelParticipants participants = (TLRPC.TL_channels_channelParticipants) res;
                int count = Math.max(participants.count, participants.participants.size());
                info.admins_count = count;
                cell.setValue(String.valueOf(count), true);
            }
        }));
    }

    private static final int TAG_LOADING = 0x7f_ad_01_01;

    private static void gone(View view) {
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    private static void show(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
