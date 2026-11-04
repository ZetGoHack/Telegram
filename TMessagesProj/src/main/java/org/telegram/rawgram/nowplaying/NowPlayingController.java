package org.telegram.rawgram.nowplaying;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.RawBadgeEditor;
import org.telegram.rawgram.RawgramConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ProfileMusicView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * «Сейчас играет»: the track a user is listening to on Last.fm / stats.fm, shown as a card in their profile.
 * Ported from exteraGram's {@code NowPlayingController} (exteraGram, GPLv3, https://github.com/exteraSquad/exteraGram),
 * with the Kotlin coroutines replaced by a dispatch queue and {@link HttpURLConnection}.
 * <p>
 * The live track comes from the exteraGram API ({@code GET profiles/{userId}/now-playing}; the server reads the linked
 * service), asked only for users with an exteraGram badge, as exteraGram does. Otherwise the profile's saved music is
 * used, matched against the iTunes search for album and artwork. Linking your own service goes through the exteraGram
 * API bot ({@code set_now_playing <SERVICE> <username>} / {@code clear_now_playing}).
 * <p>
 * Everything is off unless {@link RawgramConfig#isNowPlaying()}: no requests.
 */
public final class NowPlayingController {

    static final String PLATFORM_TELEGRAM = "TELEGRAM";

    private static final String BASE_URL = "https://api.exteragram.app/api/v1/";
    private static final String ITUNES_SEARCH_URL = "https://itunes.apple.com/search";
    private static final String ITUNES_LIMIT = "5";
    private static final String ITUNES_ARTWORK_SMALL = "100x100";
    private static final String ITUNES_ARTWORK_LARGE = "300x300";
    private static final int ITUNES_CACHE_SIZE = 50;
    /** exteraGram's client: 5 s connect / read. */
    private static final int TIMEOUT_MS = 5_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final String RESULT_OK = "ok";

    private static final String PREFS = "rawgram_nowplaying";
    private static final String KEY_SERVICE = "service_";
    private static final String KEY_USERNAME = "username_";

    /**
     * exteraGram's remote flag {@code separate_music_styles}: when set, saved music keeps Telegram's own music row in
     * the profile header and the card shows only live tracks. rawGram keeps Telegram's row, so this is on.
     */
    private static final boolean SEPARATE_MUSIC_STYLES = true;

    private static final Pattern ARTISTS_SPLITTER = Pattern.compile("(?i)\\s*(?:,|&|\\bfeat\\b\\.?|\\bft\\b\\.?)\\s*");
    private static final LruCache<String, List<ItunesTrack>> itunesCache = new LruCache<>(ITUNES_CACHE_SIZE);

    private static volatile DispatchQueue queue;

    private NowPlayingController() {
    }

    /** The feature switch (it also requires the exteraGram badges switch). */
    public static boolean isEnabled() {
        return RawgramConfig.isNowPlaying();
    }

    // ---------------------------------------------------------------- card

    /** Whether a loaded card is shown (exteraGram: not for saved music when the styles are separate). */
    public static boolean shouldShowCard(NowPlayingCardData data) {
        if (data == null) {
            return false;
        }
        return !(data.track.isTelegram() && isSeparateStylesSupported());
    }

    public static boolean isSeparateStylesSupported() {
        return SEPARATE_MUSIC_STYLES;
    }

    /** A running {@link #getCurrentPlayingTrack} call; {@link #cancel()} drops the result and closes the connection. */
    public static final class Request {
        private volatile boolean cancelled;
        private volatile boolean done;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            HttpURLConnection c = connection;
            if (c != null) {
                disconnectAsync(c);
            }
        }

        public boolean isActive() {
            return !cancelled && !done;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    /** {@link HttpURLConnection#disconnect()} may block: not on the UI thread. */
    private static void disconnectAsync(HttpURLConnection connection) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                connection.disconnect();
            } catch (Throwable ignore) {
            }
        });
    }

    /**
     * The track to show for {@code userId}: the live one from the API when {@code checkApi} (the user has a badge) and
     * it is playing, else the saved music. {@code callback} runs on the UI thread with the track (or null) and the time
     * the lookup took, in ms; it never runs after {@link Request#cancel()}.
     */
    public static Request getCurrentPlayingTrack(long userId, TLRPC.Document savedMusic, boolean checkApi, Utilities.Callback2<NowPlayingTrack, Long> callback) {
        final Request request = new Request();
        final long startTime = System.currentTimeMillis();
        queue().postRunnable(() -> {
            NowPlayingTrack liveTrack = null;
            try {
                if (checkApi && isEnabled() && !request.isCancelled()) {
                    liveTrack = fetchLiveTrack(userId, request);
                }
            } catch (Throwable t) {
                if (!request.isCancelled()) {
                    FileLog.e(t);
                }
            }
            NowPlayingTrack finalTrack;
            if (liveTrack != null && liveTrack.isPlaying) {
                finalTrack = liveTrack;
            } else if (request.isCancelled()) {
                finalTrack = null;
            } else {
                finalTrack = processSavedMusic(savedMusic, request);
            }
            final NowPlayingTrack result = finalTrack;
            AndroidUtilities.runOnUIThread(() -> {
                request.done = true;
                if (!request.isCancelled()) {
                    callback.run(result, System.currentTimeMillis() - startTime);
                }
            });
        });
        return request;
    }

    private static NowPlayingTrack fetchLiveTrack(long userId, Request request) throws Exception {
        String body = get(BASE_URL + "profiles/" + userId + "/now-playing", request);
        return body == null ? null : NowPlayingTrack.fromJson(new JSONObject(body));
    }

    /** Saved music → a track (platform TELEGRAM), with album / artwork / artists from iTunes when styles are shared. */
    private static NowPlayingTrack processSavedMusic(TLRPC.Document savedMusic, Request request) {
        if (savedMusic == null) {
            return null;
        }
        CharSequence titleText = ProfileMusicView.getTitle(savedMusic);
        String title = titleText instanceof String ? (String) titleText : null;
        if (title == null) {
            title = LocaleController.getString(R.string.AudioUnknownTitle);
        }
        CharSequence authorText = ProfileMusicView.getAuthor(savedMusic);
        String author = authorText instanceof String ? (String) authorText : null;
        if (author == null) {
            author = LocaleController.getString(R.string.AudioUnknownArtist);
        }
        if (title == null || author == null) {
            return null;
        }
        NowPlayingTrack base = new NowPlayingTrack(title, splitArtists(author), null, null, null, null, true, null, PLATFORM_TELEGRAM, null);
        if (isSeparateStylesSupported()) {
            return base;
        }
        List<ItunesTrack> list = fetchItunesTrack(author, title, request);
        if (list == null || list.isEmpty()) {
            return base;
        }
        ItunesTrack match = null;
        for (ItunesTrack itunesTrack : list) {
            boolean commonArtist = hasCommonArtist(base.artists, itunesTrack.artistName).common;
            if (itunesTrack.trackName != null && itunesTrack.trackName.equalsIgnoreCase(base.trackName) && commonArtist) {
                match = itunesTrack;
                break;
            }
        }
        if (match == null) {
            match = list.get(0);
        }
        List<String> artists = hasCommonArtist(base.artists, match.artistName).artists;
        String album = match.collectionName != null ? match.collectionName : base.albumName;
        String cover = !TextUtils.isEmpty(match.artworkUrl100)
                ? match.artworkUrl100.replace(ITUNES_ARTWORK_SMALL, ITUNES_ARTWORK_LARGE)
                : base.coverUrl;
        return base.withItunes(artists, album, cover);
    }

    private static List<String> splitArtists(String artists) {
        String[] parts = ARTISTS_SPLITTER.split(artists, 0);
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() > 0) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static final class ArtistMatch {
        final boolean common;
        final List<String> artists;

        ArtistMatch(boolean common, List<String> artists) {
            this.common = common;
            this.artists = artists;
        }
    }

    /** Whether iTunes' artist line shares an artist with ours; if it does, iTunes' list replaces ours. */
    private static ArtistMatch hasCommonArtist(List<String> baseArtists, String itunesArtists) {
        if (itunesArtists == null || itunesArtists.trim().isEmpty()) {
            return new ArtistMatch(false, baseArtists);
        }
        List<String> itunesList = splitArtists(itunesArtists);
        if (baseArtists == null || baseArtists.isEmpty()) {
            return new ArtistMatch(false, itunesList);
        }
        boolean common = false;
        outer:
        for (String base : baseArtists) {
            String lower = base.toLowerCase(Locale.ROOT);
            String translit = AndroidUtilities.translitSafe(lower);
            for (String other : itunesList) {
                String otherLower = other.toLowerCase(Locale.ROOT);
                String otherTranslit = AndroidUtilities.translitSafe(otherLower);
                if (otherLower.equals(lower) || otherLower.equals(translit) || otherTranslit.equals(lower)) {
                    common = true;
                    break outer;
                }
            }
        }
        return new ArtistMatch(common, common ? itunesList : baseArtists);
    }

    /** One iTunes search result (the fields exteraGram reads). */
    private static final class ItunesTrack {
        final String trackName;
        final String artistName;
        final String collectionName;
        final String artworkUrl100;

        ItunesTrack(JSONObject o) {
            trackName = o.isNull("trackName") ? null : o.optString("trackName", null);
            artistName = o.isNull("artistName") ? null : o.optString("artistName", null);
            collectionName = o.isNull("collectionName") ? null : o.optString("collectionName", null);
            artworkUrl100 = o.isNull("artworkUrl100") ? null : o.optString("artworkUrl100", null);
        }
    }

    private static List<ItunesTrack> fetchItunesTrack(String performer, String title, Request request) {
        try {
            if (performer.trim().isEmpty() && title.trim().isEmpty()) {
                return null;
            }
            String cacheKey = performer + " - " + title;
            List<ItunesTrack> cached = itunesCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            String url = Uri.parse(ITUNES_SEARCH_URL).buildUpon()
                    .appendQueryParameter("term", title + " - " + performer)
                    .appendQueryParameter("entity", "song")
                    .appendQueryParameter("limit", ITUNES_LIMIT)
                    .build().toString();
            String body = get(url, request);
            if (body == null) {
                return null;
            }
            JSONArray array = new JSONObject(body).optJSONArray("results");
            List<ItunesTrack> results = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o != null) {
                        results.add(new ItunesTrack(o));
                    }
                }
            }
            results = Collections.unmodifiableList(results);
            itunesCache.put(cacheKey, results);
            return results;
        } catch (Exception e) {
            if (request == null || !request.isCancelled()) {
                FileLog.e(e);
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- own link

    /** Your linked service: {@code serviceType} + {@code username}. Ported from exteraGram's {@code NowPlayingInfoDTO}. */
    public static final class Info {
        public final NowPlayingService serviceType;
        public final String username;

        public Info(NowPlayingService serviceType, String username) {
            this.serviceType = serviceType == null ? NowPlayingService.NONE : serviceType;
            this.username = username;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Last known link of the current account, without a request; null when not loaded yet. */
    public static Info getCachedInfo() {
        long selfId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        SharedPreferences p = prefs();
        String service = p.getString(KEY_SERVICE + selfId, null);
        return service == null ? null : new Info(NowPlayingService.fromApi(service), p.getString(KEY_USERNAME + selfId, null));
    }

    private static void cacheInfo(long selfId, Info info) {
        SharedPreferences.Editor e = prefs().edit();
        if (info == null) {
            e.remove(KEY_SERVICE + selfId).remove(KEY_USERNAME + selfId);
        } else {
            e.putString(KEY_SERVICE + selfId, info.serviceType.name());
            if (info.username == null) {
                e.remove(KEY_USERNAME + selfId);
            } else {
                e.putString(KEY_USERNAME + selfId, info.username);
            }
        }
        e.apply();
    }

    /**
     * Your link (exteraGram's {@code getNowPlayingInfo}): the stored one, else {@code GET profiles/{selfId}}.
     * {@code callback} runs on the UI thread, with null when unknown.
     */
    public static void getNowPlayingInfo(Utilities.Callback<Info> callback) {
        final long selfId = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        Info cached = getCachedInfo();
        if (cached != null || !isEnabled()) {
            AndroidUtilities.runOnUIThread(() -> callback.run(cached));
            return;
        }
        queue().postRunnable(() -> {
            Info info = null;
            try {
                String body = get(BASE_URL + "profiles/" + selfId, null);
                if (body != null) {
                    JSONObject nowPlaying = new JSONObject(body).optJSONObject("nowPlaying");
                    if (nowPlaying != null) {
                        info = new Info(NowPlayingService.fromApi(nowPlaying.optString("serviceType")),
                                nowPlaying.isNull("username") ? null : nowPlaying.optString("username", null));
                        cacheInfo(selfId, info);
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            final Info result = info;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    /**
     * Links (or with {@link NowPlayingService#NONE} unlinks) your service through the exteraGram API bot.
     * {@code callback} gets true when the bot answered {@code ok} (UI thread).
     */
    public static void updateNowPlayingInfo(int account, Info info, Utilities.Callback<Boolean> callback) {
        String query;
        if (info == null || info.serviceType == NowPlayingService.NONE) {
            query = "clear_now_playing";
        } else {
            query = "set_now_playing " + info.serviceType.name() + " " + info.username;
        }
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        RawBadgeEditor.sendBotRequest(account, query, answer -> {
            if (RESULT_OK.equals(answer)) {
                cacheInfo(selfId, info == null ? new Info(NowPlayingService.NONE, null) : info);
                callback.run(true);
            } else {
                callback.run(false);
            }
        });
    }

    // ---------------------------------------------------------------- network

    private static DispatchQueue queue() {
        if (queue == null) {
            synchronized (NowPlayingController.class) {
                if (queue == null) {
                    queue = new DispatchQueue("rawNowPlaying");
                }
            }
        }
        return queue;
    }

    /** GET returning the body on 200, else null. {@code request} (optional) can abort it. */
    private static String get(String url, Request request) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (request != null) {
            request.connection = connection;
        }
        try {
            if (request != null && request.isCancelled()) {
                return null;
            }
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            return readBody(connection.getInputStream());
        } finally {
            if (request != null) {
                request.connection = null;
            }
            connection.disconnect();
        }
    }

    private static String readBody(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("now playing response too large");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
