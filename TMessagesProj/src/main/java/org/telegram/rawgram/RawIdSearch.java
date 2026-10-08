package org.telegram.rawgram;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

/**
 * Search by id in the user / chat pickers (SearchAdapterHelper: group exceptions and restrictions, admins, privacy
 * exceptions, adding members, folders, notification exceptions…): an id-like query puts the peers with that id from
 * Telegram's local cache at the top of the global results, like {@link RawIdLookup} does in the chats search.
 * <p>
 * The cache lookup runs on the storage queue next to the server requests; whichever ends last merges it in.
 */
public final class RawIdSearch {

    private int generation;
    private ArrayList<TLObject> found;
    private boolean merged;
    private Runnable lateApply;

    /**
     * Start of SearchAdapterHelper.queryServerSearch. {@code accept}: the picker's own filters (bots, self, chats…);
     * {@code lateApply}: merges and refreshes when the lookup ends after the server responses.
     */
    public void start(int account, String query, boolean enabled, Utilities.CallbackReturn<TLObject, Boolean> accept, Runnable lateApply) {
        final int gen = ++generation;
        found = null;
        merged = false;
        this.lateApply = lateApply;
        if (!enabled || !RawgramConfig.isIdSearch() || query == null) {
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
                ArrayList<TLObject> accepted = new ArrayList<>();
                for (TLObject peer : peers) {
                    if (peer instanceof TLRPC.User) {
                        controller.putUser((TLRPC.User) peer, true);
                    } else if (peer instanceof TLRPC.Chat) {
                        controller.putChat((TLRPC.Chat) peer, true);
                    }
                    if (accept == null || Boolean.TRUE.equals(accept.run(peer))) {
                        accepted.add(peer);
                    }
                }
                if (accepted.isEmpty()) {
                    return;
                }
                found = accepted;
                if (merged && this.lateApply != null) {
                    this.lateApply.run();
                }
            });
        });
    }

    /** After the server responses are applied: puts the found peers first (dropping their duplicates below). */
    public void apply(ArrayList<TLObject> global, LongSparseArray<TLObject> map) {
        merged = true;
        if (found == null || global == null) {
            return;
        }
        for (int i = global.size() - 1; i >= 0; i--) {
            if (contains(found, global.get(i))) {
                global.remove(i);
            }
        }
        global.addAll(0, found);
        if (map != null) {
            for (TLObject peer : found) {
                map.put(key(peer), peer);
            }
        }
    }

    private static boolean contains(ArrayList<TLObject> list, TLObject obj) {
        long key = key(obj);
        if (key == 0) {
            return false;
        }
        for (TLObject o : list) {
            if (key(o) == key) {
                return true;
            }
        }
        return false;
    }

    private static long key(TLObject obj) {
        if (obj instanceof TLRPC.User) {
            return ((TLRPC.User) obj).id;
        }
        if (obj instanceof TLRPC.Chat) {
            return -((TLRPC.Chat) obj).id;
        }
        return 0;
    }
}
