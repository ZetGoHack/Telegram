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
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Lyrics lookup: tags of the playing file, then the disk cache, then lrclib.net and, when lrclib has no synced text,
 * lrcmux.dev (only when the online switch is on). Synced lines from lrcmux get lrclib's spelling and punctuation when
 * both texts agree on at least 90% of the words. Everything that is found is cached on disk and in memory.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later); the HTTP part is rewritten on HttpURLConnection.
 */
public final class LyricsRepository {

    public static final int RESULT_OK = 0;
    public static final int RESULT_NOT_FOUND = 1;
    public static final int RESULT_ERROR = 2;

    private static final String PROVIDER = "LRCLIB";
    private static final String PROVIDER_LRCMUX = "lrcmux";
    /** lrcmux answers further off than this (seconds) are another recording. */
    private static final int LRCMUX_DURATION_LIMIT = 5;
    /** How far ahead in the plain text a word of a synced line is looked for. */
    private static final int PUNCTUATE_LOOKAHEAD = 4;
    private static final float PUNCTUATE_MIN_MATCH = 0.9f;
    private static final int CACHE_VERSION = 1;
    private static final int SOURCE_ONLINE = 1;
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    /** Unsynced tag lyrics with fewer text lines than this are ignored, so the online lookup runs instead. */
    private static final int MIN_TAG_LINES = 4;
    private static final int LRCLIB_ATTEMPTS = 3;
    private static final long RETRY_MAX_DELAY = 2000L;
    private static final long RETRY_BASE_DELAY = 500L;
    /** Lines of tag lyrics that advertise a site or a channel. */
    private static final Pattern PROMO = Pattern.compile("https?://|www\\.|t\\.me/|\\b[\\w-]+\\.(?:com|net|org|ru|su|ua|by|kz|io|me|to|fm|cc|co|lc|ly|gg|tv|info|biz|xyz|club|top|site|online|pro|app|link|music)\\b|(?:^|\\s)@\\w{3,}", Pattern.CASE_INSENSITIVE);

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
        Lyrics lyrics = Lyrics.parse(stripPromo(info.getLyrics()), 0);
        if (lyrics == null || !lyrics.synced && textLines(lyrics) < MIN_TAG_LINES) {
            return null;
        }
        return lyrics;
    }

    private static String stripPromo(String text) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (!PROMO.matcher(line).find()) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    private static int textLines(Lyrics lyrics) {
        int count = 0;
        for (Lyrics.Line line : lyrics.lines) {
            if (!line.text.isEmpty()) {
                count++;
            }
        }
        return count;
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
                Lyrics lrclib = null;
                try {
                    lrclib = requestLrclib(query);
                } catch (Throwable th) {
                    FileLog.e(th);
                    failed = true;
                }
                if (lrclib == null || !(lrclib.synced || lrclib.instrumental)) {
                    Lyrics lrcmux = null;
                    try {
                        lrcmux = requestLrcmux(query);
                    } catch (Throwable th) {
                        FileLog.e(th);
                        failed = true;
                    }
                    if (lrcmux != null && (lrcmux.synced || lrclib == null)) {
                        Lyrics merged = lrclib != null ? punctuate(lrcmux, lrclib) : null;
                        found = merged != null ? merged : lrcmux;
                    } else {
                        found = lrclib;
                    }
                } else {
                    found = lrclib;
                }
                if (found == null) {
                    result = failed ? RESULT_ERROR : RESULT_NOT_FOUND;
                } else if (!failed) {
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
        Exception error = null;
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
            try {
                String body = get(get.build().toString(), LRCLIB_ATTEMPTS);
                if (body != null) {
                    Lyrics exact = fromLrclib(new JSONObject(body));
                    if (exact != null) {
                        return exact;
                    }
                }
            } catch (Exception e) {
                error = e;
                FileLog.e(e);
            }
        }
        Lyrics found = searchLrclib(query);
        if (found != null || error == null) {
            return found;
        }
        throw error;
    }

    private static Lyrics searchLrclib(TrackQuery query) throws Exception {
        Uri.Builder search = Uri.parse("https://lrclib.net/api/search").buildUpon()
                .appendQueryParameter("track_name", query.title());
        if (!TextUtils.isEmpty(query.artist())) {
            search.appendQueryParameter("artist_name", query.artist());
        }
        String body = get(search.build().toString(), LRCLIB_ATTEMPTS);
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

    private static Lyrics requestLrcmux(TrackQuery query) throws Exception {
        Uri.Builder get = Uri.parse("https://api.lrcmux.dev/get").buildUpon()
                .appendQueryParameter("title", query.title())
                .appendQueryParameter("level", "line")
                .appendQueryParameter("format", "json");
        if (!TextUtils.isEmpty(query.artist())) {
            get.appendQueryParameter("artist", query.artist());
        }
        if (!TextUtils.isEmpty(query.album())) {
            get.appendQueryParameter("album", query.album());
        }
        if (query.duration() > 0) {
            get.appendQueryParameter("duration", String.valueOf(query.duration()));
        }
        String body = get(get.build().toString());
        if (body == null) {
            return null;
        }
        JSONObject object = new JSONObject(body);
        JSONObject track = object.optJSONObject("track");
        JSONObject meta = object.optJSONObject("meta");
        if (query.duration() > 0 && track != null) {
            int duration = track.optInt("duration", 0);
            if (duration > 0 && Math.abs(duration - query.duration()) > LRCMUX_DURATION_LIMIT) {
                return null;
            }
        }
        JSONObject source = meta != null ? meta.optJSONObject("source") : null;
        String sourceName = source != null ? optString(source, "name") : null;
        String provider = sourceName != null ? PROVIDER_LRCMUX + " · " + sourceName : PROVIDER_LRCMUX;
        if (meta != null && meta.optBoolean("instrumental", false)) {
            return Lyrics.instrumental(SOURCE_ONLINE, provider);
        }
        JSONArray lines = object.optJSONArray("lines");
        if (lines == null || lines.length() == 0) {
            return null;
        }
        if (meta == null || "none".equals(meta.optString("level"))) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line != null) {
                    text.append(line.optString("text", "")).append('\n');
                }
            }
            return Lyrics.parse(text.toString(), SOURCE_ONLINE, provider);
        }
        ArrayList<Lyrics.Line> timed = new ArrayList<>(lines.length());
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line != null) {
                timed.add(new Lyrics.Line(Math.max(0L, line.optLong("start", 0L)), line.optString("text", "").trim()));
            }
        }
        return timed.isEmpty() ? null : Lyrics.fromTimedLines(timed, SOURCE_ONLINE, provider);
    }

    private static int firstLetter(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** {@code word} with the case of the first letter of {@code like}. */
    private static String withCase(String word, String like) {
        int at = firstLetter(word);
        int likeAt = firstLetter(like);
        if (at < 0 || likeAt < 0) {
            return word;
        }
        boolean upper = Character.isUpperCase(like.charAt(likeAt));
        char c = word.charAt(at);
        if (Character.isUpperCase(c) == upper) {
            return word;
        }
        return word.substring(0, at) + (upper ? Character.toUpperCase(c) : Character.toLowerCase(c)) + word.substring(at + 1);
    }

    private static void appendWord(StringBuilder line, String word) {
        if (word.isEmpty()) {
            return;
        }
        if (line.length() > 0) {
            line.append(' ');
        }
        line.append(word);
    }

    /**
     * The synced lines with the words (spelling, punctuation) of the plain text, each word keeping the case of its
     * first letter; null when fewer than 90% of the words could be matched.
     */
    private static Lyrics punctuate(Lyrics synced, Lyrics plain) {
        ArrayList<String> words = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();
        for (Lyrics.Line line : plain.lines) {
            for (String word : line.text.split("\\s+")) {
                String key = TrackQuery.normalize(word);
                if (!key.isEmpty()) {
                    words.add(word);
                    keys.add(key);
                }
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        ArrayList<Lyrics.Line> result = new ArrayList<>(synced.lines.size());
        int total = 0;
        int matched = 0;
        int cursor = 0;
        for (Lyrics.Line line : synced.lines) {
            StringBuilder text = new StringBuilder();
            for (String word : line.text.split("\\s+")) {
                String key = TrackQuery.normalize(word);
                if (key.isEmpty()) {
                    appendWord(text, word);
                    continue;
                }
                total++;
                int end = Math.min(cursor + PUNCTUATE_LOOKAHEAD, keys.size());
                int found = -1;
                for (int j = cursor; j < end; j++) {
                    if (keys.get(j).equals(key)) {
                        found = j;
                        break;
                    }
                }
                if (found < 0) {
                    appendWord(text, word);
                } else {
                    matched++;
                    appendWord(text, withCase(words.get(found), word));
                    cursor = found + 1;
                }
            }
            result.add(new Lyrics.Line(line.time, text.toString()));
        }
        if (total == 0 || matched < total * PUNCTUATE_MIN_MATCH) {
            return null;
        }
        return Lyrics.synced(result, synced.source, synced.provider + " + " + PROVIDER);
    }

    /** Null on 404, an exception on other failures. */
    private static String get(String url) throws Exception {
        return get(url, 1);
    }

    /** Like {@link #get(String)}, but 429 and 503 are retried up to {@code attempts} times in all. */
    private static String get(String url, int attempts) throws Exception {
        for (int attempt = 1; ; attempt++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            long delay;
            try {
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "rawGram/" + BuildVars.BUILD_VERSION_STRING);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code == 404) {
                    return null;
                }
                if (code >= 200 && code < 300) {
                    return readBody(connection);
                }
                if (code != 429 && code != 503 || attempt >= attempts) {
                    throw new IllegalStateException("lyrics http " + code);
                }
                delay = retryDelay(connection.getHeaderField("Retry-After"), attempt);
            } finally {
                connection.disconnect();
            }
            Thread.sleep(delay);
        }
    }

    private static long retryDelay(String retryAfter, int attempt) {
        if (retryAfter != null) {
            try {
                return Math.min(Long.parseLong(retryAfter.trim()) * 1000, RETRY_MAX_DELAY);
            } catch (NumberFormatException ignore) {
            }
        }
        return Math.min(RETRY_BASE_DELAY << (attempt - 1), RETRY_MAX_DELAY);
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
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
