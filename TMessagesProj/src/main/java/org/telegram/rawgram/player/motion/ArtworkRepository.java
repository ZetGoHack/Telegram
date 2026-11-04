package org.telegram.rawgram.player.motion;

import android.text.TextUtils;
import android.util.LruCache;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.player.PlayerCache;
import org.telegram.rawgram.player.TrackQuery;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Full-size cover pictures from the Apple Music catalog for tracks whose own cover is missing or smaller than
 * 640 px. The found URL (or the miss, for three days) is cached on disk and in memory; requests for the same track are
 * merged.
 *
 * <p>Only used when the «Обложки из Apple Music» switch is on.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later): ArtworkRepository.
 */
public final class ArtworkRepository {

    public interface Callback {
        void onResult(String url);
    }

    private static final int MAX_SIZE = 1200;
    private static final int MAX_URL_BYTES = 4096;
    private static final String CACHE_DIR = "artwork";
    private static final String CACHE_SALT = "\u00011";

    /** Track key to URL; an empty string marks a known miss. */
    private static final LruCache<String, String> memory = new LruCache<>(128);
    private static final HashMap<String, ArrayList<Callback>> pending = new HashMap<>();

    private ArtworkRepository() {
    }

    public static String getCached(TrackQuery query) {
        if (query == null) {
            return null;
        }
        String url = memory.get(query.key());
        return TextUtils.isEmpty(url) ? null : url;
    }

    public static boolean isResolved(TrackQuery query) {
        return query == null || memory.get(query.key()) != null;
    }

    public static void fetch(TrackQuery query, Callback callback) {
        if (query == null) {
            return;
        }
        final String key = query.key();
        String cached = memory.get(key);
        if (cached != null) {
            callback.onResult(cached.isEmpty() ? null : cached);
            return;
        }
        ArrayList<Callback> waiting = pending.get(key);
        if (waiting != null) {
            waiting.add(callback);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(callback);
        pending.put(key, waiting);
        MotionCoverRepository.queue.postRunnable(() -> {
            String url = null;
            boolean answered;
            try {
                url = load(query, key);
                answered = true;
            } catch (Throwable e) {
                FileLog.e(e);
                answered = false;
            }
            final String result = url;
            final boolean remember = answered;
            AndroidUtilities.runOnUIThread(() -> {
                if (remember) {
                    memory.put(key, result != null ? result : "");
                }
                ArrayList<Callback> callbacks = pending.remove(key);
                if (callbacks != null) {
                    for (Callback c : callbacks) {
                        c.onResult(result);
                    }
                }
            });
        });
    }

    private static String load(TrackQuery query, String key) throws Exception {
        File directory = PlayerCache.directory(CACHE_DIR);
        String name = Utilities.MD5(key + CACHE_SALT);
        File urlFile = directory != null ? new File(directory, name + ".url") : null;
        File missFile = directory != null ? new File(directory, name + ".none") : null;
        if (urlFile != null && urlFile.length() > 0) {
            String url = read(urlFile);
            if (!TextUtils.isEmpty(url)) {
                return url;
            }
        }
        if (missFile != null && System.currentTimeMillis() - missFile.lastModified() < MotionCoverRepository.MISS_TTL) {
            return null;
        }
        String url = find(query);
        if (urlFile == null || url == null) {
            if (missFile != null) {
                new FileOutputStream(missFile).close();
            }
            return url;
        }
        try (FileOutputStream out = new FileOutputStream(urlFile)) {
            out.write(url.getBytes(StandardCharsets.UTF_8));
        }
        return url;
    }

    private static String find(TrackQuery query) throws Exception {
        ArrayList<JSONObject> songs = MotionCoverMatcher.bestSongs(MotionCoverRepository.searchSongs(query), query.artist(), query.title(), query.duration());
        for (JSONObject song : songs) {
            JSONObject artwork = song.optJSONObject("artwork");
            String url = artwork != null ? artwork.optString("url") : "";
            if (url.startsWith("https://")) {
                int size = Math.max(1, Math.min(MAX_SIZE, Math.min(artwork.optInt("width", MAX_SIZE), artwork.optInt("height", MAX_SIZE))));
                return url.replace("{w}", String.valueOf(size)).replace("{h}", String.valueOf(size)).replace("{f}", "jpg");
            }
        }
        return null;
    }

    private static String read(File file) throws Exception {
        int length = (int) Math.min(file.length(), MAX_URL_BYTES);
        byte[] data = new byte[length];
        int total = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (total < length) {
                int read = in.read(data, total, length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        }
        String url = new String(data, 0, total, StandardCharsets.UTF_8).trim();
        return url.startsWith("https://") ? url : null;
    }
}
