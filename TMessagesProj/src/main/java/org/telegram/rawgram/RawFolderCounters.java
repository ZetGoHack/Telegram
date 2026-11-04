package org.telegram.rawgram;

import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Folder tab unread counters for folders with a «Моя роль» filter ({@link RawFolderRoles}). Stock counts
 * unread chats per type in bulk and cannot drop the chats the role hides, so for such folders the counter is
 * recounted per dialog from the same data {@code MessagesStorage.calcUnreadCounters} collects. Folders without a
 * role keep the stock {@code DialogFilter.unreadCount}, and with no role set anywhere nothing extra runs.
 * <p>
 * Ported from exteraGram (com.exteragram.messenger.utils.chats.FolderCounters), GPLv3. exteraGram's
 * «unmuted only» tab counter mode (main tab count, forum topic mutes) is not ported.
 * <p>
 * Threading: {@link #update} and the recount run on the storage queue; the counts are published to and read
 * on the UI thread.
 */
public final class RawFolderCounters {

    private static final long RECOUNT_DELAY_MS = 500;

    private static final RawFolderCounters[] instances = new RawFolderCounters[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;
    private final AtomicBoolean recountScheduled = new AtomicBoolean();
    /** UI thread only: filterId -> unread count, for folders with a role. */
    private SparseIntArray filterCounts = new SparseIntArray();

    private RawFolderCounters(int account) {
        this.account = account;
    }

    public static RawFolderCounters getInstance(int account) {
        RawFolderCounters instance = instances[account];
        if (instance == null) {
            synchronized (RawFolderCounters.class) {
                instance = instances[account];
                if (instance == null) {
                    instances[account] = instance = new RawFolderCounters(account);
                }
            }
        }
        return instance;
    }

    /** One unread dialog (an encrypted user can stand for several secret chats: {@link #weight}). */
    private static final class UnreadDialog {
        final long id;
        final int typeFlags;
        final int weight;
        final boolean archived;
        final boolean muted;
        final TLRPC.Chat chat;

        UnreadDialog(long id, int typeFlags, int weight, boolean archived, boolean muted, TLRPC.Chat chat) {
            this.id = id;
            this.typeFlags = typeFlags;
            this.weight = weight;
            this.archived = archived;
            this.muted = muted;
            this.chat = chat;
        }
    }

    /** UI thread: the tab counter of {@code filter}. */
    public int getUnreadCount(MessagesController.DialogFilter filter) {
        if (RawFolderRoles.getMode(account, filter.id) == RawFolderRoles.MODE_NONE) {
            return filter.unreadCount;
        }
        int index = filterCounts.indexOfKey(filter.id);
        return index >= 0 ? filterCounts.valueAt(index) : filter.unreadCount;
    }

    /** Storage queue, from {@code calcUnreadCounters}: recounts the folders that have a role. */
    public void update(List<MessagesController.DialogFilter> filters, LongSparseArray<TLRPC.User> usersDict,
                       LongSparseArray<TLRPC.User> encUsersDict, LongSparseIntArray encryptedChatsByUsersCount,
                       LongSparseArray<TLRPC.Chat> chatsDict, LongSparseArray<Boolean> mutedDialogs,
                       LongSparseArray<Boolean> archivedDialogs, LongSparseArray<Integer> dialogsWithMentions) {
        if (!RawFolderRoles.hasAny(account)) {
            return;
        }
        ArrayList<UnreadDialog> dialogs = new ArrayList<>();
        for (int i = 0, n = usersDict.size(); i < n; i++) {
            TLRPC.User user = usersDict.valueAt(i);
            int type;
            if (ChatObject.isUserCollapsedInCommunity(chatsDict, user)) {
                type = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
            } else if (user.bot) {
                type = MessagesController.DIALOG_FILTER_FLAG_BOTS;
            } else if (user.self || user.contact) {
                type = MessagesController.DIALOG_FILTER_FLAG_CONTACTS;
            } else {
                type = MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
            }
            dialogs.add(new UnreadDialog(user.id, type, 1, archivedDialogs.containsKey(user.id), mutedDialogs.containsKey(user.id), null));
        }
        for (int i = 0, n = encUsersDict.size(); i < n; i++) {
            TLRPC.User user = encUsersDict.valueAt(i);
            int count = encryptedChatsByUsersCount.get(user.id, 0);
            if (count == 0) {
                continue;
            }
            int type = user.self || user.contact ? MessagesController.DIALOG_FILTER_FLAG_CONTACTS : MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
            dialogs.add(new UnreadDialog(user.id, type, count, archivedDialogs.containsKey(user.id), mutedDialogs.containsKey(user.id), null));
        }
        for (int i = 0, n = chatsDict.size(); i < n; i++) {
            TLRPC.Chat chat = chatsDict.valueAt(i);
            // chatsDict also holds the linked communities (possibly null) looked up for the collapse check
            if (chat == null || ChatObject.isCommunity(chat)) {
                continue;
            }
            long did = -chat.id;
            int type;
            if (ChatObject.isChatCollapsedInCommunity(chatsDict, chat)) {
                type = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
            } else if (ChatObject.isChannel(chat) && !chat.megagroup) {
                type = MessagesController.DIALOG_FILTER_FLAG_CHANNELS;
            } else {
                type = MessagesController.DIALOG_FILTER_FLAG_GROUPS;
            }
            boolean muted = mutedDialogs.containsKey(did) && !dialogsWithMentions.containsKey(did);
            dialogs.add(new UnreadDialog(did, type, 1, archivedDialogs.containsKey(did), muted, chat));
        }
        SparseIntArray counts = new SparseIntArray();
        for (int i = 0, n = filters.size(); i < n; i++) {
            MessagesController.DialogFilter filter = filters.get(i);
            if (RawFolderRoles.getMode(account, filter.id) != RawFolderRoles.MODE_NONE) {
                counts.put(filter.id, count(dialogs, filter));
            }
        }
        AndroidUtilities.runOnUIThread(() -> filterCounts = counts);
    }

    /**
     * Debounced full recount (runs {@code calcUnreadCounters} again) after an incremental counter update or a
     * notification settings change, which stock applies to the bulk counters only.
     */
    public void scheduleRecount() {
        if (!RawFolderRoles.hasAny(account) || !recountScheduled.compareAndSet(false, true)) {
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            recountScheduled.set(false);
            storage.resetAllUnreadCounters(false);
        }, RECOUNT_DELAY_MS);
    }

    /** Same rules as the stock bulk count, plus the role check for chats included by type. */
    private int count(List<UnreadDialog> dialogs, MessagesController.DialogFilter filter) {
        int flags = filter.flags;
        HashSet<Long> alwaysShow = new HashSet<>(filter.alwaysShow);
        HashSet<Long> neverShow = new HashSet<>(filter.neverShow);
        int count = 0;
        for (int i = 0, n = dialogs.size(); i < n; i++) {
            UnreadDialog dialog = dialogs.get(i);
            if (neverShow.contains(dialog.id)) {
                continue;
            }
            if (!alwaysShow.contains(dialog.id)) {
                if (dialog.archived && (flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) != 0) {
                    continue;
                }
                if (dialog.muted && (flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) != 0) {
                    continue;
                }
                if (dialog.chat != null && !RawFolderRoles.allows(account, filter.id, dialog.chat)) {
                    continue;
                }
                if ((dialog.typeFlags & flags) != dialog.typeFlags) {
                    continue;
                }
            }
            count += dialog.weight;
        }
        return count;
    }
}
