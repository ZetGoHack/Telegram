package org.telegram.rawgram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Incoming updates log: everything the server pushes (ConnectionsManager.onUnparsedMessageReceived) — Updates
 * containers and short updates — with the full objects, as many as the limit in the developer settings allows.
 * (Constructors the client can't parse throw inside TLdeserialize before the hook and are not logged.) Updates that come back as RPC results (sendMessage etc.) are in the request log.
 * The hook reads {@link #enabled} first: a disabled log costs one volatile read per push.
 */
public final class RawUpdatesLog {

    private static final long NOTIFY_DELAY = 300;

    /** Checked by the ConnectionsManager hook before anything else. */
    public static volatile boolean enabled;
    /** Viewer pause: pushes are not recorded while set. Not persisted. */
    public static volatile boolean paused;

    static {
        try {
            enabled = ApplicationLoader.applicationContext != null && RawgramConfig.isUpdatesLog();
        } catch (Throwable ignore) {
            enabled = false;
        }
    }

    /** Update types that arrive constantly and bury the interesting ones (hidden by the viewer's «noise» filter). */
    private static final String[] NOISE = {
            "updateUserStatus", "updateUserTyping", "updateChatUserTyping", "updateChannelUserTyping", "updateEncryptedChatTyping",
            "updateGroupCallParticipants", "updateBotWebhookJSON", "updateGroupCallConnection"
    };

    public static final class Entry {
        public final long seq;
        public final int account;
        public final long time;
        public final long messageId;
        /** Container type: updates, updatesCombined, updateShort, updateShortMessage, ... */
        public final String type;
        /** Inner update types in order, e.g. [updateNewMessage, updateReadHistoryInbox]. */
        public final ArrayList<String> inner;
        /** «updateNewMessage, updateReadHistoryInbox ×2». */
        public final String summary;
        public final boolean noise;
        public final TLObject object;
        public final int constructor;

        Entry(long seq, int account, long messageId, TLObject object, int constructor) {
            this.seq = seq;
            this.account = account;
            this.time = System.currentTimeMillis();
            this.messageId = messageId;
            this.object = object;
            this.constructor = constructor;
            this.type = object != null ? RawRequestLog.methodName(object) : String.format("unknown 0x%08x", constructor);
            this.inner = innerTypes(object);
            this.summary = summarize(inner);
            this.noise = isNoise(inner);
        }

        public ArrayList<TLRPC.Update> updates() {
            ArrayList<TLRPC.Update> list = new ArrayList<>();
            if (object instanceof TLRPC.Updates) {
                TLRPC.Updates u = (TLRPC.Updates) object;
                if (u.updates != null) {
                    list.addAll(u.updates);
                }
                if (u.update != null) {
                    list.add(u.update);
                }
            }
            return list;
        }
    }

    private static final RawLogBuffer<Entry> buffer = new RawLogBuffer<>(RawgramConfig::getUpdatesLogLimit);
    private static final Object seqLock = new Object();
    private static long seqCounter;
    private static final ArrayList<Runnable> listeners = new ArrayList<>(); // UI thread only
    private static volatile boolean notifyScheduled;
    private static final Runnable notifyRunnable = () -> {
        notifyScheduled = false;
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    };

    private RawUpdatesLog() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        RawgramConfig.setUpdatesLog(value);
        if (!value) {
            paused = false;
        }
        scheduleNotify();
    }

    public static int limit() {
        return RawgramConfig.getUpdatesLogLimit();
    }

    public static void setLimit(int limit) {
        RawgramConfig.setUpdatesLogLimit(limit);
        buffer.trim();
        scheduleNotify();
    }

    /** ConnectionsManager.onUnparsedMessageReceived, network thread. {@code object} is null for unknown constructors. */
    public static void onPush(int account, TLObject object, int constructor, long messageId) {
        if (paused) {
            return;
        }
        long seq;
        synchronized (seqLock) {
            seq = ++seqCounter;
        }
        buffer.add(new Entry(seq, account, messageId, object, constructor));
        scheduleNotify();
    }

    /** Newest first. */
    public static ArrayList<Entry> snapshot() {
        return buffer.snapshot();
    }

    public static void clear() {
        buffer.clear();
        scheduleNotify();
    }

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void scheduleNotify() {
        if (!notifyScheduled) {
            notifyScheduled = true;
            AndroidUtilities.runOnUIThread(notifyRunnable, NOTIFY_DELAY);
        }
    }

    private static ArrayList<String> innerTypes(TLObject object) {
        ArrayList<String> list = new ArrayList<>();
        if (object instanceof TLRPC.Updates) {
            TLRPC.Updates u = (TLRPC.Updates) object;
            if (u.updates != null) {
                for (TLRPC.Update update : u.updates) {
                    list.add(RawRequestLog.methodName(update));
                }
            }
            if (u.update != null) {
                list.add(RawRequestLog.methodName(u.update));
            }
        }
        return list;
    }

    private static String summarize(ArrayList<String> inner) {
        if (inner.isEmpty()) {
            return "";
        }
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (String name : inner) {
            Integer n = counts.get(name);
            counts.put(name, n == null ? 1 : n + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey());
            if (e.getValue() > 1) {
                sb.append(" ×").append(e.getValue());
            }
        }
        return sb.toString();
    }

    private static boolean isNoise(ArrayList<String> inner) {
        if (inner.isEmpty()) {
            return false;
        }
        for (String name : inner) {
            boolean noisy = false;
            for (String n : NOISE) {
                if (n.equals(name)) {
                    noisy = true;
                    break;
                }
            }
            if (!noisy) {
                return false;
            }
        }
        return true;
    }
}
