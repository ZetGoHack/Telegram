package org.telegram.rawgram.player.lyrics;

import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.rawgram.player.PlayerCache;
import org.telegram.rawgram.player.PlayerCovers;
import org.telegram.rawgram.player.TrackQuery;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Lyrics lookup: tags of the playing file, then the disk cache, then lrclib.net (only when the online switch is on).
 * Everything that is found is cached on disk and in memory.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later); the HTTP part is rewritten on HttpURLConnection and the
 * second provider is dropped.
 */
public final class LyricsRepository {

    public static final int RESULT_OK = 0;
    public static final int RESULT_NOT_FOUND = 1;
    public static final int RESULT_ERROR = 2;

    private static final String PROVIDER = "LRCLIB";
    private static final int CACHE_VERSION = 1;
    private static final int SOURCE_ONLINE = 1;
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;

    private static final LruCache<String, Lyrics> memory = new LruCache<>(24);
    private static final LruCache<String, Boolean> missing = new LruCache<>(64);

    private LyricsRepository() {
    }

    public interface Callback {
        void onResult(Lyrics lyrics, int result);
    }

    public static Lyrics fromTags(MessageObject messageObject) {
        AudioInfo info = PlayerCovers.getPlayingAudioInfo(messageObject);
        if (info == null) {
            return null;
        }
        return Lyrics.parse(info.getLyrics(), 0);
    }

    public static Lyrics getCached(TrackQuery query) {
        return query == null ? null : memory.get(query.key());
    }

    public static boolean isKnownMissing(TrackQuery query) {
        return query != null && missing.get(query.key()) != null;
    }

    /** Disk cache only, no network. */
    public static void loadCached(TrackQuery query, final Callback callback) {
        final String key = query.key();
        Lyrics cached = memory.get(key);
        if (cached != null) {
            callback.onResult(cached, RESULT_OK);
            return;
        }
        Utilities.externalNetworkQueue.postRunnable(() -> {
            final Lyrics disk = readDisk(key);
            AndroidUtilities.runOnUIThread(() -> {
                if (disk != null) {
                    memory.put(key, disk);
                }
                callback.onResult(disk, disk != null ? RESULT_OK : RESULT_NOT_FOUND);
            });
        });
    }

    /** Disk cache, then the network. */
    public static void fetch(final TrackQuery query, final Callback callback) {
        final String key = query.key();
        Lyrics cached = memory.get(key);
        if (cached != null) {
            callback.onResult(cached, RESULT_OK);
            return;
        }
        Utilities.externalNetworkQueue.postRunnable(() -> {
            Lyrics found = readDisk(key);
            int result = RESULT_OK;
            if (found == null) {
                boolean failed = false;
                try {
                    found = requestLrclib(query);
                } catch (Throwable th) {
                    FileLog.e(th);
                    failed = true;
                }
                if (found == null) {
                    result = failed ? RESULT_ERROR : RESULT_NOT_FOUND;
                } else {
                    writeDisk(key, found);
                }
            }
            final Lyrics lyrics = found;
            final int finalResult = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (lyrics != null) {
                    memory.put(key, lyrics);
                    missing.remove(key);
                } else if (finalResult == RESULT_NOT_FOUND) {
                    missing.put(key, Boolean.TRUE);
                }
                callback.onResult(lyrics, finalResult);
            });
        });
    }

    private static Lyrics requestLrclib(TrackQuery query) throws Exception {
        if (!TextUtils.isEmpty(query.artist())) {
            Uri.Builder get = Uri.parse("https://lrclib.net/api/get").buildUpon()
                    .appendQueryParameter("artist_name", query.artist())
                    .appendQueryParameter("track_name", query.title());
            if (!TextUtils.isEmpty(query.album())) {
                get.appendQueryParameter("album_name", query.album());
            }
            if (query.duration() > 0) {
                get.appendQueryParameter("duration", String.valueOf(query.duration()));
            }
            String body = get(get.build().toString());
            if (body != null) {
                Lyrics exact = fromLrclib(new JSONObject(body));
                if (exact != null) {
                    return exact;
                }
            }
        }
        Uri.Builder search = Uri.parse("https://lrclib.net/api/search").buildUpon()
                .appendQueryParameter("track_name", query.title());
        if (!TextUtils.isEmpty(query.artist())) {
            search.appendQueryParameter("artist_name", query.artist());
        }
        String body = get(search.build().toString());
        if (body == null) {
            return null;
        }
        JSONArray array = new JSONArray(body);
        int bestScore = Integer.MIN_VALUE;
        Lyrics best = null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            Lyrics candidate = item == null ? null : fromLrclib(item);
            if (candidate == null) {
                continue;
            }
            int score = candidate.synced ? 40 : 0;
            if (query.duration() > 0 && item.has("duration")) {
                int diff = (int) Math.abs(Math.round(item.optDouble("duration", 0.0)) - (long) query.duration());
                if (diff > 3) {
                    diff = Math.min(diff, 60) + 20;
                }
                score -= diff;
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static String optString(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return null;
        }
        String value = object.optString(key, null);
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static Lyrics fromLrclib(JSONObject object) {
        if (object.optBoolean("instrumental", false)) {
            return Lyrics.instrumental(SOURCE_ONLINE, PROVIDER);
        }
        Lyrics synced = Lyrics.parse(optString(object, "syncedLyrics"), SOURCE_ONLINE, PROVIDER);
        if (synced != null && synced.synced) {
            return synced;
        }
        Lyrics plain = Lyrics.parse(optString(object, "plainLyrics"), SOURCE_ONLINE, PROVIDER);
        return plain != null ? plain : synced;
    }

    /** Null on 404, an exception on other failures. */
    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "rawGram/" + BuildVars.BUILD_VERSION_STRING);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("lyrics http " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_RESPONSE) {
                        throw new IllegalStateException("lyrics response too large");
                    }
                }
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static File cacheFile(String key) {
        File directory = PlayerCache.directory("lyrics");
        return directory == null ? null : new File(directory, Utilities.MD5(key) + ".json");
    }

    private static Lyrics readDisk(String key) {
        try {
            File file = cacheFile(key);
            if (file == null || !file.exists()) {
                return null;
            }
            String text = AndroidUtilities.readRes(file);
            if (text == null) {
                return null;
            }
            JSONObject object = new JSONObject(text);
            if (object.optInt("v", 0) != CACHE_VERSION) {
                return null;
            }
            String provider = optString(object, "provider");
            if (provider == null) {
                provider = PROVIDER;
            }
            if (object.optBoolean("instrumental", false)) {
                return Lyrics.instrumental(SOURCE_ONLINE, provider);
            }
            return Lyrics.parse(optString(object, "text"), SOURCE_ONLINE, provider);
        } catch (Throwable th) {
            FileLog.e(th);
            return null;
        }
    }

    private static void writeDisk(String key, Lyrics lyrics) {
        try {
            File file = cacheFile(key);
            if (file == null) {
                return;
            }
            JSONObject object = new JSONObject();
            object.put("v", CACHE_VERSION);
            object.put("provider", lyrics.provider);
            object.put("instrumental", lyrics.instrumental);
            object.put("text", lyrics.toText());
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(object.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable th) {
            FileLog.e(th);
        }
    }
}
