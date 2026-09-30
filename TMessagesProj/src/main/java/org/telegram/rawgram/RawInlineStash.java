package org.telegram.rawgram;

import android.util.SparseArray;

import org.telegram.tgnet.TLRPC;

import java.util.HashSet;

/**
 * Inline results parked by the user: the bot, the query and the exact answer, kept across chats
 * so the same results can be brought back (without a new request) in any chat that allows inline bots.
 * Each account has its own tray: results fetched by one account never show up in another.
 */
public class RawInlineStash {

    public static class Entry {
        public TLRPC.User bot;
        public String query;
        public TLRPC.messages_BotResults response;
        public final HashSet<String> highlightIds = new HashSet<>();
        public long sourceDialogId;
        public long savedAt;
    }

    private static final SparseArray<Entry> current = new SparseArray<>();
    private static final SparseArray<Entry> pendingRestore = new SparseArray<>();
    private static final java.util.ArrayList<Runnable> listeners = new java.util.ArrayList<>();

    public static Entry get(int account) {
        return current.get(account);
    }

    public static boolean has(int account) {
        return current.get(account) != null;
    }

    public static void save(int account, Entry entry) {
        current.put(account, entry);
        notifyChanged();
    }

    public static void clear(int account) {
        current.remove(account);
        notifyChanged();
    }

    /** Marks the stash to be served instead of a network request for the next matching inline query. */
    public static void beginRestore(int account, Entry entry) {
        pendingRestore.put(account, entry);
    }

    /** Called by the inline adapter before it requests results; returns the stash if it matches. */
    public static Entry takePending(int account, TLRPC.User user, String query, String offset) {
        Entry entry = pendingRestore.get(account);
        if (entry == null || user == null || entry.bot == null || entry.bot.id != user.id
                || query == null || !query.equals(entry.query) || offset != null && !offset.isEmpty()) {
            return null;
        }
        pendingRestore.remove(account);
        return entry;
    }

    public static void addListener(Runnable listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyChanged() {
        for (Runnable r : new java.util.ArrayList<>(listeners)) {
            r.run();
        }
    }
}
