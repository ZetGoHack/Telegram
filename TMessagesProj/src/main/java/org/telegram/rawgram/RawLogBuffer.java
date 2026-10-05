package org.telegram.rawgram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Thread-safe FIFO of the newest log entries with a changeable limit (0 = no limit). Entries keep their full
 * objects for as long as they are in the buffer: a log with cut objects is useless for analysis, so the limit
 * is the only trade-off (memory).
 */
public final class RawLogBuffer<E> {

    public interface LimitSource {
        int get();
    }

    private final ArrayDeque<E> entries = new ArrayDeque<>();
    private final LimitSource limit;

    public RawLogBuffer(LimitSource limit) {
        this.limit = limit;
    }

    public void add(E entry) {
        synchronized (entries) {
            entries.addLast(entry);
            trimLocked();
        }
    }

    /** After the limit changed. */
    public void trim() {
        synchronized (entries) {
            trimLocked();
        }
    }

    private void trimLocked() {
        int max = limit.get();
        if (max > 0) {
            while (entries.size() > max) {
                entries.removeFirst();
            }
        }
    }

    /** Newest first. */
    public ArrayList<E> snapshot() {
        synchronized (entries) {
            ArrayList<E> list = new ArrayList<>(entries.size());
            Iterator<E> it = entries.descendingIterator();
            while (it.hasNext()) {
                list.add(it.next());
            }
            return list;
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    /** «300» / «без лимита» for subtitles and settings. */
    public static String limitText(int limit) {
        return limit <= 0 ? "без лимита" : String.valueOf(limit);
    }
}
