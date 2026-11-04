package org.telegram.rawgram.nowplaying;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A track for the «Сейчас играет» card: what the exteraGram API reports for {@code profiles/{id}/now-playing}, or the
 * profile's saved music ({@link #platform} {@value NowPlayingController#PLATFORM_TELEGRAM}). Ported from exteraGram's
 * {@code NowPlayingDTO} (exteraGram, GPLv3).
 */
public final class NowPlayingTrack {

    public final String trackName;
    public final List<String> artists;
    public final String albumName;
    public final String coverUrl;
    public final String previewUrl;
    public final String songUrl;
    public final boolean isPlaying;
    public final String deviceName;
    public final String platform;
    public final Long duration;

    public NowPlayingTrack(String trackName, List<String> artists, String albumName, String coverUrl, String previewUrl,
                           String songUrl, boolean isPlaying, String deviceName, String platform, Long duration) {
        this.trackName = trackName;
        this.artists = artists;
        this.albumName = albumName;
        this.coverUrl = coverUrl;
        this.previewUrl = previewUrl;
        this.songUrl = songUrl;
        this.isPlaying = isPlaying;
        this.deviceName = deviceName;
        this.platform = platform;
        this.duration = duration;
    }

    /** Copy with other artists, album and cover (exteraGram's {@code copy(artists, albumName, coverUrl)}). */
    NowPlayingTrack withItunes(List<String> artists, String albumName, String coverUrl) {
        return new NowPlayingTrack(trackName, artists, albumName, coverUrl, previewUrl, songUrl, isPlaying, deviceName, platform, duration);
    }

    public boolean isTelegram() {
        return NowPlayingController.PLATFORM_TELEGRAM.equals(platform);
    }

    /** API JSON (Gson field names of exteraGram's DTO); null when there is no track name. */
    static NowPlayingTrack fromJson(JSONObject o) {
        if (o == null || o.isNull("trackName")) {
            return null;
        }
        List<String> artists = null;
        JSONArray array = o.optJSONArray("artists");
        if (array != null) {
            artists = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                if (!array.isNull(i)) {
                    artists.add(array.optString(i));
                }
            }
        }
        boolean playing = o.has("isPlaying") ? o.optBoolean("isPlaying") : o.optBoolean("playing");
        Long duration = o.isNull("duration") ? null : o.optLong("duration");
        return new NowPlayingTrack(o.optString("trackName"), artists == null ? null : Collections.unmodifiableList(artists),
                string(o, "albumName"), string(o, "coverUrl"), string(o, "previewUrl"), string(o, "songUrl"), playing,
                string(o, "deviceName"), string(o, "platform"), duration);
    }

    private static String string(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NowPlayingTrack)) {
            return false;
        }
        NowPlayingTrack t = (NowPlayingTrack) other;
        return isPlaying == t.isPlaying && Objects.equals(trackName, t.trackName) && Objects.equals(artists, t.artists)
                && Objects.equals(albumName, t.albumName) && Objects.equals(coverUrl, t.coverUrl)
                && Objects.equals(previewUrl, t.previewUrl) && Objects.equals(songUrl, t.songUrl)
                && Objects.equals(deviceName, t.deviceName) && Objects.equals(platform, t.platform)
                && Objects.equals(duration, t.duration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trackName, artists, albumName, coverUrl, previewUrl, songUrl, isPlaying, deviceName, platform, duration);
    }
}
