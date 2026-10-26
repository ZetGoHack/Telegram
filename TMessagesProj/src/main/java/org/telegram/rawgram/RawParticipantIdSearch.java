package org.telegram.rawgram;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatUsersActivity;

import java.util.ArrayList;

/**
 * Search by id in a channel's or supergroup's member lists (ChatUsersActivity: members, admins, restricted, the
 * blacklist): the server search there matches names and usernames only. An id-like query resolves the peers with that id
 * from the local cache (like {@link RawIdSearch}) and asks channels.getParticipant for each; those in the list being
 * searched go to the top of the group results.
 */
public final class RawParticipantIdSearch {

    private int generation;
    private ArrayList<TLRPC.ChannelParticipant> found;
    private boolean merged;
    private Runnable lateApply;

    /**
     * Start of SearchAdapterHelper.queryServerSearch for a channel. {@code type}: ChatUsersActivity.TYPE_*;
     * {@code lateApply}: merges and refreshes when the lookup ends after the server responses.
     */
    public void start(int account, long channelId, int type, String query, Runnable lateApply) {
        final int gen = ++generation;
        found = null;
        merged = false;
        this.lateApply = lateApply;
        if (channelId == 0 || !RawgramConfig.isIdSearch() || query == null) {
            return;
        }
        RawIdLookup.Candidates c = RawIdLookup.forQuery(query);
        if (c.isEmpty()) {
            return;
        }
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            ArrayList<TLObject> peers = new ArrayList<>();
            try {
                RawIdLookup.fromMemoryAndDb(account, c, peers);
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || peers.isEmpty()) {
                    return;
                }
                MessagesController controller = MessagesController.getInstance(account);
                ArrayList<TLRPC.ChannelParticipant> result = new ArrayList<>();
                int[] left = {peers.size()};
                for (TLObject peer : peers) {
                    TLRPC.TL_channels_getParticipant req = new TLRPC.TL_channels_getParticipant();
                    req.channel = controller.getInputChannel(channelId);
                    if (peer instanceof TLRPC.User) {
                        controller.putUser((TLRPC.User) peer, true);
                        req.participant = controller.getInputPeer(((TLRPC.User) peer).id);
                    } else {
                        controller.putChat((TLRPC.Chat) peer, true);
                        req.participant = controller.getInputPeer(-((TLRPC.Chat) peer).id);
                    }
                    ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        if (gen != generation) {
                            return;
                        }
                        if (response instanceof TLRPC.TL_channels_channelParticipant) {
                            TLRPC.TL_channels_channelParticipant res = (TLRPC.TL_channels_channelParticipant) response;
                            controller.putUsers(res.users, false);
                            controller.putChats(res.chats, false);
                            if (inList(res.participant, type)) {
                                result.add(res.participant);
                            }
                        }
                        if (--left[0] == 0 && !result.isEmpty()) {
                            found = result;
                            if (merged && this.lateApply != null) {
                                this.lateApply.run();
                            }
                        }
                    }));
                }
            });
        });
    }

    /** After the server responses are applied: puts the found participants first (dropping their duplicates below). */
    public void apply(ArrayList<TLObject> group, LongSparseArray<TLObject> map) {
        merged = true;
        if (found == null || group == null) {
            return;
        }
        for (TLRPC.ChannelParticipant p : found) {
            long id = MessageObject.getPeerId(p.peer);
            for (int i = group.size() - 1; i >= 0; i--) {
                TLObject o = group.get(i);
                if (o instanceof TLRPC.ChannelParticipant && MessageObject.getPeerId(((TLRPC.ChannelParticipant) o).peer) == id) {
                    group.remove(i);
                }
            }
            if (map != null) {
                map.put(id, p);
            }
        }
        group.addAll(0, found);
    }

    /** Whether the participant belongs to the list searched (mirrors the filters of SearchAdapterHelper). */
    private static boolean inList(TLRPC.ChannelParticipant p, int type) {
        if (p == null || p.peer == null) {
            return false;
        }
        boolean banned = p instanceof TLRPC.TL_channelParticipantBanned;
        boolean kicked = banned && p.banned_rights != null && p.banned_rights.view_messages;
        switch (type) {
            case ChatUsersActivity.TYPE_ADMIN:
                return p instanceof TLRPC.TL_channelParticipantAdmin || p instanceof TLRPC.TL_channelParticipantCreator;
            case ChatUsersActivity.TYPE_BANNED:
                return kicked;
            case ChatUsersActivity.TYPE_KICKED:
                return banned && !kicked;
            default:
                return !banned && !(p instanceof TLRPC.TL_channelParticipantLeft);
        }
    }
}
