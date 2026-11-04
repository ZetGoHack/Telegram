package org.telegram.rawgram.player.motion;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.player.PlayerCache;
import org.telegram.rawgram.player.TrackQuery;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Animated (motion) album covers from Apple Music: the song is found in the public catalog by title and artist, the
 * album's editorial square video is picked, and the H.264 variant closest to the cover size is downloaded from its HLS
 * playlist into a single mp4 in the cache (200 MB, oldest first out). Misses are remembered for three days. The
 * catalog token is the one the music.apple.com web player ships in its script, cached until it expires.
 *
 * <p>Only used when the «Обложки из Apple Music» switch is on.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later): MotionCoverRepository; the HTTP part is rewritten on
 * HttpURLConnection.
 */
public final class MotionCoverRepository {

    public interface Callback {
        void onResult(String path);
    }

    static final DispatchQueue queue = new DispatchQueue("playerMotionCovers");

    static final long MISS_TTL = 3L * 24 * 60 * 60 * 1000;
    private static final long TOKEN_MARGIN = 60L * 60 * 1000;
    private static final long MAX_VIDEO_BYTES = 48L * 1024 * 1024;
    private static final long CACHE_LIMIT = 200L * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private static final int SEARCH_LIMIT = 10;
    /** A variant counts as big enough at 90% of the cover size. */
    private static final float VARIANT_SLACK = 0.9f;
    private static final String CACHE_DIR = "motion_covers";
    private static final String CACHE_SALT = "\u00014";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";

    private static final Pattern SCRIPT = Pattern.compile("/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\\.js");
    private static final Pattern JWT = Pattern.compile("ey[A-Za-z0-9_-]+\\.ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("[:,]BANDWIDTH=(\\d+)");
    private static final Pattern MAP_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern MAP_RANGE = Pattern.compile("BYTERANGE=\"(\\d+)(?:@(\\d+))?\"");

    private static volatile String pendingKey;
    private static String storefront;
    private static String token;
    private static long tokenExpiresAt;

    private MotionCoverRepository() {
    }

    /** A byte range of one URL; a negative length means the whole file. */
    private static final class Part {
        final String url;
        final long offset;
        final long length;

        Part(String url, long offset, long length) {
            this.url = url;
            this.offset = offset;
            this.length = length;
        }
    }

    private static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /**
     * Path of the motion cover of a track, or null. Only the latest request runs; older ones that are still queued
     * answer null.
     */
    public static void fetch(TrackQuery query, int size, Callback callback) {
        final String key = query.key();
        pendingKey = key;
        queue.postRunnable(() -> {
            String path = null;
            if (key.equals(pendingKey)) {
                try {
                    path = load(query, key, size);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            final String result = path;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
        });
    }

    private static String load(TrackQuery query, String key, int size) throws Exception {
        File directory = PlayerCache.directory(CACHE_DIR);
        if (directory == null) {
            return null;
        }
        String name = Utilities.MD5(key + CACHE_SALT);
        File file = new File(directory, name + ".mp4");
        if (file.length() > 0) {
            if (!file.setLastModified(System.currentTimeMillis())) {
                FileLog.d("motion covers: can't touch " + file);
            }
            return file.getAbsolutePath();
        }
        File miss = new File(directory, name + ".none");
        if (System.currentTimeMillis() - miss.lastModified() < MISS_TTL) {
            return null;
        }
        String video = findVideo(query);
        if (video == null) {
            new FileOutputStream(miss).close();
        } else {
            download(video, file, size);
        }
        trim(directory);
        return video != null && file.length() > 0 ? file.getAbsolutePath() : null;
    }

    /** Catalog songs matching "artist title" (up to ten), or null. */
    static JSONArray searchSongs(TrackQuery query) throws Exception {
        final String term = TextUtils.isEmpty(query.artist()) ? query.title() : query.artist() + " " + query.title();
        JSONObject response = catalog("search", builder -> builder
                .appendQueryParameter("types", "songs")
                .appendQueryParameter("limit", String.valueOf(SEARCH_LIMIT))
                .appendQueryParameter("term", term));
        JSONObject results = response != null ? response.optJSONObject("results") : null;
        JSONObject songs = results != null ? results.optJSONObject("songs") : null;
        return songs != null ? songs.optJSONArray("data") : null;
    }

    private static String findVideo(TrackQuery query) throws Exception {
        ArrayList<JSONObject> songs = MotionCoverMatcher.bestSongs(searchSongs(query), query.artist(), query.title(), query.duration());
        for (JSONObject song : songs) {
            final String isrc = song.optString("isrc");
            if (isrc.isEmpty()) {
                continue;
            }
            JSONObject response = catalog("songs", builder -> builder
                    .appendQueryParameter("include", "albums")
                    .appendQueryParameter("extend[albums]", "editorialVideo")
                    .appendQueryParameter("filter[isrc]", isrc));
            String artist = TextUtils.isEmpty(query.artist()) ? song.optString("artistName") : query.artist();
            String video = MotionCoverMatcher.pickVideo(response != null ? response.optJSONArray("data") : null, query.album(), song.optString("albumName"), artist);
            if (video != null) {
                return video;
            }
        }
        return null;
    }

    private static JSONObject catalog(String path, Utilities.Callback<Uri.Builder> params) throws Exception {
        if (storefront == null) {
            String country = Locale.getDefault().getCountry().toLowerCase(Locale.ROOT);
            storefront = country.length() == 2 ? country : "us";
        }
        Reply reply = catalogGet(path, params, token(false));
        if (reply.code == 401) {
            reply = catalogGet(path, params, token(true));
        }
        if (reply.code == 400 && !"us".equals(storefront)) {
            storefront = "us";
            reply = catalogGet(path, params, token(false));
        }
        if (reply.code < 200 || reply.code >= 300) {
            // an error, not a miss: throwing keeps it from being remembered as "no cover" for three days
            throw new IllegalStateException("apple music http " + reply.code);
        }
        return new JSONObject(reply.body);
    }

    private static Reply catalogGet(String path, Utilities.Callback<Uri.Builder> params, String bearer) throws Exception {
        Uri.Builder builder = Uri.parse("https://amp-api.music.apple.com/v1/catalog/" + storefront + "/" + path).buildUpon();
        params.run(builder);
        HttpURLConnection connection = open(builder.build().toString());
        try {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setRequestProperty("Origin", "https://music.apple.com");
            connection.setRequestProperty("Referer", "https://music.apple.com/");
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Reply(code, in == null ? "" : readText(in));
        } finally {
            connection.disconnect();
        }
    }

    /** The web player's developer token: from memory, the cache file, or scraped from music.apple.com. */
    private static String token(boolean refresh) throws Exception {
        long now = System.currentTimeMillis();
        if (!refresh) {
            if (token == null) {
                readToken();
            }
            if (token != null && now < tokenExpiresAt - TOKEN_MARGIN) {
                return token;
            }
        }
        HashSet<String> seen = new HashSet<>();
        Matcher scripts = SCRIPT.matcher(getText("https://music.apple.com/us/browse"));
        while (scripts.find()) {
            if (!seen.add(scripts.group())) {
                continue;
            }
            Matcher jwts = JWT.matcher(getText("https://music.apple.com" + scripts.group()));
            while (jwts.find()) {
                long expiry = webPlayerTokenExpiry(jwts.group());
                if (expiry > now) {
                    token = jwts.group();
                    tokenExpiresAt = expiry;
                    writeToken();
                    return token;
                }
            }
        }
        throw new IllegalStateException("apple music token not found");
    }

    private static long webPlayerTokenExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            JSONObject header = new JSONObject(new String(Base64.decode(parts[0], Base64.URL_SAFE), StandardCharsets.UTF_8));
            JSONObject payload = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8));
            if ("WebPlayKid".equals(header.optString("kid")) || "AMPWebPlay".equals(payload.optString("iss"))) {
                return payload.optLong("exp") * 1000;
            }
        } catch (Exception ignore) {
        }
        return 0;
    }

    private static void readToken() {
        File directory = PlayerCache.directory(CACHE_DIR);
        String text = directory != null ? AndroidUtilities.readRes(new File(directory, "token")) : null;
        if (text == null) {
            return;
        }
        String[] lines = text.trim().split("\n");
        if (lines.length == 2) {
            try {
                tokenExpiresAt = Long.parseLong(lines[1].trim());
                token = lines[0].trim();
            } catch (NumberFormatException e) {
                FileLog.e(e);
            }
        }
    }

    private static void writeToken() {
        File directory = PlayerCache.directory(CACHE_DIR);
        if (directory == null) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(new File(directory, "token"))) {
            out.write((token + "\n" + tokenExpiresAt).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void download(String master, File file, int size) throws Exception {
        String variant = pickVariant(master, getText(master), size);
        if (variant == null) {
            FileLog.d("motion covers: no h264 variant in " + master);
            return;
        }
        ArrayList<Part> parts = parseParts(variant, getText(variant));
        if (parts.isEmpty()) {
            FileLog.d("motion covers: empty playlist " + variant);
            return;
        }
        File temp = new File(file.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (isSingleFile(parts)) {
                    Part last = parts.get(parts.size() - 1);
                    copy(new Part(last.url, 0, last.offset + last.length), out, 0);
                } else {
                    long total = 0;
                    for (Part part : parts) {
                        total = copy(part, out, total);
                    }
                }
            }
            if (!temp.renameTo(file)) {
                throw new IllegalStateException("can't move " + temp);
            }
        } catch (Exception e) {
            delete(temp);
            throw e;
        }
    }

    /**
     * The H.264 SDR variant of a master playlist: the smallest one at least as big as the cover, else the biggest one;
     * the lower bandwidth wins between equal sizes.
     */
    private static String pickVariant(String master, String playlist, int size) {
        int target = (int) (size * VARIANT_SLACK);
        String[] lines = playlist.split("\n");
        String best = null;
        int bestSize = 0;
        long bestBandwidth = 0;
        for (int i = 0; i < lines.length - 1; i++) {
            String info = lines[i].trim();
            String uri = lines[i + 1].trim();
            if (!info.startsWith("#EXT-X-STREAM-INF:") || !info.contains("avc1") || info.contains("VIDEO-RANGE=PQ") || info.contains("VIDEO-RANGE=HLG") || uri.isEmpty() || uri.startsWith("#")) {
                continue;
            }
            Matcher resolution = RESOLUTION.matcher(info);
            if (!resolution.find()) {
                continue;
            }
            Matcher bandwidthMatcher = BANDWIDTH.matcher(info);
            int variantSize = Math.max(Integer.parseInt(resolution.group(1)), Integer.parseInt(resolution.group(2)));
            long bandwidth = bandwidthMatcher.find() ? Long.parseLong(bandwidthMatcher.group(1)) : Long.MAX_VALUE;
            boolean big = variantSize >= target;
            boolean take;
            if (best == null) {
                take = true;
            } else if (big != (bestSize >= target)) {
                take = big;
            } else if (variantSize != bestSize) {
                take = big ? variantSize < bestSize : variantSize > bestSize;
            } else {
                take = bandwidth < bestBandwidth;
            }
            if (take) {
                best = uri;
                bestSize = variantSize;
                bestBandwidth = bandwidth;
            }
        }
        return best != null ? resolve(master, best) : null;
    }

    /** Init segment and media segments of a media playlist, with their byte ranges. */
    private static ArrayList<Part> parseParts(String playlistUrl, String playlist) {
        ArrayList<Part> parts = new ArrayList<>();
        String lastUrl = null;
        long rangeOffset = -1;
        long rangeLength = -1;
        long nextOffset = 0;
        for (String raw : playlist.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MAP:")) {
                Matcher uri = MAP_URI.matcher(line);
                if (!uri.find()) {
                    continue;
                }
                String url = resolve(playlistUrl, uri.group(1));
                Matcher range = MAP_RANGE.matcher(line);
                if (range.find()) {
                    long length = Long.parseLong(range.group(1));
                    long offset = range.group(2) != null ? Long.parseLong(range.group(2)) : 0;
                    parts.add(new Part(url, offset, length));
                    nextOffset = offset + length;
                } else {
                    parts.add(new Part(url, 0, -1));
                    nextOffset = 0;
                }
                lastUrl = url;
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                String value = line.substring("#EXT-X-BYTERANGE:".length());
                int at = value.indexOf('@');
                rangeLength = Long.parseLong(at >= 0 ? value.substring(0, at) : value);
                rangeOffset = at >= 0 ? Long.parseLong(value.substring(at + 1)) : -1;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String url = resolve(playlistUrl, line);
                if (rangeLength >= 0) {
                    if (rangeOffset >= 0) {
                        nextOffset = rangeOffset;
                    } else if (!url.equals(lastUrl)) {
                        nextOffset = 0;
                    }
                    parts.add(new Part(url, nextOffset, rangeLength));
                    nextOffset += rangeLength;
                } else {
                    parts.add(new Part(url, 0, -1));
                    nextOffset = 0;
                }
                rangeOffset = -1;
                rangeLength = -1;
                lastUrl = url;
            }
        }
        return parts;
    }

    /** True when all parts are back-to-back ranges of one file, so it can be fetched in one request. */
    private static boolean isSingleFile(ArrayList<Part> parts) {
        String url = parts.get(0).url;
        long expected = 0;
        for (Part part : parts) {
            if (!part.url.equals(url) || part.length < 0 || part.offset != expected) {
                return false;
            }
            expected += part.length;
        }
        return true;
    }

    private static long copy(Part part, OutputStream out, long total) throws Exception {
        HttpURLConnection connection = open(part.url);
        try {
            if (part.length >= 0) {
                connection.setRequestProperty("Range", "bytes=" + part.offset + "-" + (part.offset + part.length - 1));
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300 || (part.offset > 0 && code != 206)) {
                throw new IllegalStateException("motion cover http " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_VIDEO_BYTES) {
                        throw new IllegalStateException("motion cover is too large");
                    }
                    out.write(buffer, 0, read);
                }
            }
            return total;
        } finally {
            connection.disconnect();
        }
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("http " + code + " for " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                return readText(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_TEXT_BYTES) {
                throw new IllegalStateException("response too large");
            }
        }
        return out.toString("UTF-8");
    }

    private static String resolve(String base, String relative) {
        return URI.create(base).resolve(relative).toString();
    }

    /** Drops expired misses and the oldest videos above the cache limit (the newest one always stays). */
    private static void trim(File directory) {
        long now = System.currentTimeMillis();
        File[] misses = directory.listFiles((dir, name) -> name.endsWith(".none"));
        if (misses != null) {
            for (File miss : misses) {
                if (now - miss.lastModified() >= MISS_TTL) {
                    delete(miss);
                }
            }
        }
        File[] videos = directory.listFiles((dir, name) -> name.endsWith(".mp4"));
        if (videos == null) {
            return;
        }
        long total = 0;
        for (File video : videos) {
            total += video.length();
        }
        Arrays.sort(videos, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < videos.length - 1 && total > CACHE_LIMIT; i++) {
            total -= videos[i].length();
            delete(videos[i]);
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) {
            FileLog.d("motion covers: can't delete " + file);
        }
    }
}
