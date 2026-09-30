package org.telegram.rawgram;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MTProto sniffer: the last {@link #CAPACITY} RPC calls made through ConnectionsManager.
 * The hook in ConnectionsManager.sendRequestInternal reads {@link #enabled} first, so a disabled log
 * costs one volatile read per request. Requests are recorded on the stage queue, responses on the
 * network thread, listeners are notified on the UI thread (throttled).
 */
public class RawRequestLog {

    public static final int CAPACITY = 300;
    /** Only this many newest entries keep request/response objects; older ones keep metadata only. */
    public static final int KEEP_OBJECTS = 50;
    private static final long NOTIFY_DELAY = 300;

    /** Checked by the ConnectionsManager hook before anything else. */
    public static volatile boolean enabled;
    /** Viewer pause: requests are not recorded while set. Not persisted. */
    public static volatile boolean paused;

    static {
        try {
            enabled = ApplicationLoader.applicationContext != null && RawgramConfig.isRequestLog();
        } catch (Throwable ignore) {
            enabled = false;
        }
    }

    public static class Entry {
        public final long seq;
        public final int account;
        public final int token;
        public final String method;
        public final int datacenterId;
        public final int connectionType;
        /** Wall clock time of sending, ms. */
        public final long sendTime;
        final long startElapsed;

        /** -1 while waiting for the answer. */
        public volatile long durationMs = -1;
        public volatile String responseType;
        public volatile int errorCode;
        public volatile String errorText;
        /** Bytes of the serialized answer as received from the network, 0 if unknown. */
        public volatile int responseSize;

        volatile TLObject request;
        volatile TLObject response;
        volatile TLRPC.TL_error error;
        /** Objects were released because the entry fell out of the newest {@link #KEEP_OBJECTS}. */
        volatile boolean objectsDropped;

        Entry(long seq, int account, int token, String method, int datacenterId, int connectionType, TLObject request) {
            this.seq = seq;
            this.account = account;
            this.token = token;
            this.method = method;
            this.datacenterId = datacenterId;
            this.connectionType = connectionType;
            this.request = request;
            this.sendTime = System.currentTimeMillis();
            this.startElapsed = SystemClock.elapsedRealtime();
        }

        public boolean isPending() {
            return durationMs < 0;
        }

        public boolean isError() {
            return errorText != null;
        }

        public boolean hasObjects() {
            return !objectsDropped;
        }

        public TLObject getRequest() {
            return request;
        }

        public TLObject getResponse() {
            return response;
        }

        public TLRPC.TL_error getError() {
            return error;
        }

        /** File traffic: responses are big and their buffers are freed right after delivery. */
        public boolean isFileConnection() {
            return (connectionType & (ConnectionsManager.ConnectionTypeDownload | ConnectionsManager.ConnectionTypeUpload)) != 0;
        }
    }

    private static final Object lock = new Object();
    private static final Entry[] ring = new Entry[CAPACITY];
    private static int head; // next write position
    private static int size;
    private static long seqCounter;

    private static final ConcurrentHashMap<Class<?>, String> methodNames = new ConcurrentHashMap<>();
    private static final ArrayList<Runnable> listeners = new ArrayList<>(); // UI thread only
    private static volatile boolean notifyScheduled;
    private static final Runnable notifyRunnable = () -> {
        notifyScheduled = false;
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    };

    public static void setEnabled(boolean value) {
        enabled = value;
        RawgramConfig.setRequestLog(value);
        if (!value) {
            paused = false;
        }
        scheduleNotify();
    }

    /** Called by ConnectionsManager right before the request goes to native code. */
    public static Entry onSend(int account, TLObject request, int token, int datacenterId, int connectionType) {
        if (paused || request == null) {
            return null;
        }
        String method = methodName(request);
        Entry entry;
        synchronized (lock) {
            entry = new Entry(++seqCounter, account, token, method, datacenterId, connectionType, request);
            ring[head] = entry;
            head = (head + 1) % CAPACITY;
            if (size < CAPACITY) {
                size++;
            }
            if (size > KEEP_OBJECTS) {
                Entry old = ring[(head - 1 - KEEP_OBJECTS + CAPACITY * 2) % CAPACITY];
                if (old != null) {
                    old.objectsDropped = true;
                    old.request = null;
                    old.response = null;
                }
            }
        }
        scheduleNotify();
        return entry;
    }

    /** Called on the network thread once the answer (or error) is parsed. */
    public static void onResponse(Entry entry, TLObject response, TLRPC.TL_error error, int responseSize) {
        entry.responseSize = responseSize;
        if (response != null) {
            entry.responseType = methodName(response);
        }
        if (error != null) {
            entry.errorCode = error.code;
            entry.errorText = error.text != null ? error.text : "ERROR";
        }
        synchronized (lock) {
            if (!entry.objectsDropped) {
                entry.error = error;
                if (!entry.isFileConnection()) {
                    entry.response = response;
                }
            }
        }
        entry.durationMs = Math.max(0, SystemClock.elapsedRealtime() - entry.startElapsed);
        scheduleNotify();
    }

    /** The answer arrived but could not be deserialized (unknown constructor, layer mismatch). */
    public static void onParseFailed(Entry entry, Throwable e, int responseSize) {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = 0;
        error.text = "DESERIALIZE_FAILED: " + e;
        onResponse(entry, null, error, responseSize);
    }

    /** Newest first. */
    public static ArrayList<Entry> snapshot() {
        synchronized (lock) {
            ArrayList<Entry> list = new ArrayList<>(size);
            for (int i = 1; i <= size; i++) {
                list.add(ring[(head - i + CAPACITY) % CAPACITY]);
            }
            return list;
        }
    }

    public static void clear() {
        synchronized (lock) {
            for (int i = 0; i < CAPACITY; i++) {
                ring[i] = null;
            }
            head = 0;
            size = 0;
        }
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

    /** TL_messages_getBotCallbackAnswer → messages.getBotCallbackAnswer. */
    public static String methodName(Object object) {
        if (object == null) {
            return "null";
        }
        Class<?> cls = object.getClass();
        String name = methodNames.get(cls);
        if (name == null) {
            name = cls.getSimpleName();
            if (name.isEmpty()) {
                name = cls.getName();
            }
            if (name.startsWith("TL_")) {
                name = name.substring(3);
            }
            int underscore = name.indexOf('_');
            if (underscore > 0 && underscore < name.length() - 1) {
                name = name.substring(0, underscore) + "." + name.substring(underscore + 1);
            }
            methodNames.put(cls, name);
        }
        return name;
    }
}
