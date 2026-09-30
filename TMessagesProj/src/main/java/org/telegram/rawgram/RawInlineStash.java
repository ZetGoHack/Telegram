package org.telegram.rawgram;

import org.telegram.tgnet.TLRPC;

import java.util.HashSet;

/**
 * Inline results parked by the user: the bot, the query and the exact answer, kept across chats
 * so the same results can be brought back (without a new request) in any chat that allows inline bots.
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

    private static Entry current;
    private static Entry pendingRestore;
    private static final java.util.ArrayList<Runnable> listeners = new java.util.ArrayList<>();

    public static Entry get() {
        return current;
    }

    public static boolean has() {
        return current != null;
    }

    public static void save(Entry entry) {
        current = entry;
        notifyChanged();
    }

    public static void clear() {
        current = null;
        notifyChanged();
    }

    /** Marks the stash to be served instead of a network request for the next matching inline query. */
    public static void beginRestore(Entry entry) {
        pendingRestore = entry;
    }

    /** Called by the inline adapter before it requests results; returns the stash if it matches. */
    public static Entry takePending(TLRPC.User user, String query, String offset) {
        Entry entry = pendingRestore;
        if (entry == null || user == null || entry.bot == null || entry.bot.id != user.id
                || query == null || !query.equals(entry.query) || offset != null && !offset.isEmpty()) {
            return null;
        }
        pendingRestore = null;
        return entry;
    }

    public static void cancelPending() {
        pendingRestore = null;
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
