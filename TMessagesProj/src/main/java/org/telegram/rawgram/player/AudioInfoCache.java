package org.telegram.rawgram.player;

import android.text.TextUtils;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;

/**
 * The tags (cover, lyrics) of the last few music files, parsed ahead of time for the neighbours of the playing track,
 * so switching tracks doesn't parse the file on the UI thread. Keyed by path, length and modification time.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later): AudioInfoCache.
 */
public final class AudioInfoCache {

    private static final int MAX_ENTRIES = 3;

    private static final LinkedHashMap<String, AudioInfo> cache = new LinkedHashMap<String, AudioInfo>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AudioInfo> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private static final HashSet<String> pending = new HashSet<>();

    private AudioInfoCache() {
    }

    /** The cached tags of {@code file}, or parses them now (on the calling thread). */
    public static AudioInfo get(File file) {
        if (file == null) {
            return null;
        }
        String key = key(file);
        synchronized (cache) {
            AudioInfo cached = cache.get(key);
            if (cached != null && !recycled(cached)) {
                return cached;
            }
        }
        AudioInfo info = AudioInfo.getAudioInfo(file);
        if (info != null) {
            synchronized (cache) {
                cache.put(key, info);
            }
        }
        return info;
    }

    /** A cover that someone recycled (a parsed file is then read again). */
    private static boolean recycled(AudioInfo info) {
        return info.getCover() != null && info.getCover().isRecycled() || info.getSmallCover() != null && info.getSmallCover().isRecycled();
    }

    /** Parses the tags of a downloaded music file on a background queue. */
    public static void preload(MessageObject messageObject) {
        if (messageObject == null || !messageObject.isMusic()) {
            return;
        }
        final File file = getFile(messageObject);
        if (file == null || !file.exists()) {
            return;
        }
        final String key = key(file);
        synchronized (cache) {
            if (cache.containsKey(key) || !pending.add(key)) {
                return;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            AudioInfo info = AudioInfo.getAudioInfo(file);
            synchronized (cache) {
                pending.remove(key);
                if (info != null) {
                    cache.put(key, info);
                }
            }
        });
    }

    private static File getFile(MessageObject messageObject) {
        String attachPath = messageObject.messageOwner != null ? messageObject.messageOwner.attachPath : null;
        if (!TextUtils.isEmpty(attachPath)) {
            File file = new File(attachPath);
            if (file.exists()) {
                return file;
            }
        }
        return messageObject.messageOwner == null ? null : FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
    }

    private static String key(File file) {
        return file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
    }
}
