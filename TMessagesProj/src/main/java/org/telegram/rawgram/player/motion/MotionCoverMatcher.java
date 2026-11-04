package org.telegram.rawgram.player.motion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.rawgram.player.TrackQuery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Picks the Apple Music catalog song that is the playing track (title, artist, duration and version tags such as
 * "remix" or "live" must agree) and the album motion video that belongs to it.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later): MotionCoverMatcher.
 */
final class MotionCoverMatcher {

    private static final String[] MOTION_KEYS = {"motionDetailSquare", "motionSquareVideo1x1"};
    private static final String[] VERSION_TAGS = {"remix", "live", "acoustic", "sped up", "speed up", "slowed", "reverb", "instrumental", "karaoke", "cover", "demo", "extended", "nightcore", "mixed", "acapella", "a cappella", "radio edit", "unplugged", "8d"};
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile("\\s*(?:,|&|/|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\band\\b)\\s*", Pattern.CASE_INSENSITIVE);

    private static final int SCORE_TITLE_EXACT = 40;
    private static final int SCORE_TITLE_PARTIAL = 20;
    private static final int SCORE_ARTIST = 40;
    private static final int SCORE_DURATION = 30;
    /** Seconds of difference that still count as the same recording. */
    private static final int DURATION_CLOSE = 3;
    /** Seconds of difference above which a song is rejected. */
    private static final int DURATION_LIMIT = 10;
    private static final int MAX_CANDIDATES = 3;

    private MotionCoverMatcher() {
    }

    /** The best-scoring songs (up to three distinct ISRCs) of a catalog search, best first. */
    static ArrayList<JSONObject> bestSongs(JSONArray songs, String artist, String title, int duration) {
        ArrayList<JSONObject> best = new ArrayList<>();
        String wantTitle = TrackQuery.normalize(title);
        String wantArtist = TrackQuery.normalize(artist);
        if (songs == null || wantTitle.isEmpty()) {
            return best;
        }
        HashSet<String> wantTags = versionTags(title);
        HashSet<String> isrcs = new HashSet<>();
        int bestScore = 0;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            JSONObject attributes = song != null ? song.optJSONObject("attributes") : null;
            if (attributes == null) {
                continue;
            }
            String name = attributes.optString("name");
            String gotTitle = TrackQuery.normalize(name);
            int score;
            if (gotTitle.equals(wantTitle)) {
                score = SCORE_TITLE_EXACT;
            } else if (!gotTitle.isEmpty() && (gotTitle.contains(wantTitle) || wantTitle.contains(gotTitle)) && versionTags(name).equals(wantTags)) {
                score = SCORE_TITLE_PARTIAL;
            } else {
                continue;
            }
            if (!wantArtist.isEmpty()) {
                if (!artistMatches(attributes.optString("artistName"), artist)) {
                    continue;
                }
                score += SCORE_ARTIST;
            }
            long millis = attributes.optLong("durationInMillis");
            long diff = duration <= 0 || millis <= 0 ? -1 : Math.abs(Math.round(millis / 1000.0) - duration);
            boolean close = diff >= 0 && diff <= DURATION_CLOSE;
            if (diff > DURATION_LIMIT || (wantArtist.isEmpty() && !close)) {
                continue;
            }
            if (close) {
                score += SCORE_DURATION;
            }
            if (score > bestScore) {
                best.clear();
                isrcs.clear();
                bestScore = score;
            }
            if (score == bestScore && best.size() < MAX_CANDIDATES && isrcs.add(attributes.optString("isrc"))) {
                best.add(attributes);
            }
        }
        return best;
    }

    /**
     * The motion video of the right album among the songs of one ISRC: the album with the playing track's album name
     * first, then the album the catalog song belongs to, then any own release of the artist (not a compilation).
     */
    static String pickVideo(JSONArray songs, String album, String songAlbum, String artist) {
        if (songs == null) {
            return null;
        }
        String wantAlbum = albumKey(album);
        String catalogAlbum = albumKey(songAlbum);
        String sameAsSong = null;
        String ownRelease = null;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            JSONObject relationships = song != null ? song.optJSONObject("relationships") : null;
            JSONObject albums = relationships != null ? relationships.optJSONObject("albums") : null;
            JSONArray data = albums != null ? albums.optJSONArray("data") : null;
            if (data == null) {
                continue;
            }
            for (int j = 0; j < data.length(); j++) {
                JSONObject albumObject = data.optJSONObject(j);
                JSONObject attributes = albumObject != null ? albumObject.optJSONObject("attributes") : null;
                String url = attributes != null ? motionUrl(attributes.optJSONObject("editorialVideo")) : null;
                if (url == null) {
                    continue;
                }
                String name = albumKey(attributes.optString("name"));
                if (TrackQuery.normalize(name).contains("djmix")) {
                    continue;
                }
                if (!wantAlbum.isEmpty() && !name.isEmpty() && name.equals(wantAlbum)) {
                    return url;
                }
                if (sameAsSong == null && !name.isEmpty() && name.equals(catalogAlbum)) {
                    sameAsSong = url;
                }
                if (ownRelease == null && isOwnRelease(attributes, artist)) {
                    ownRelease = url;
                }
            }
        }
        return sameAsSong != null ? sameAsSong : ownRelease;
    }

    private static String motionUrl(JSONObject editorialVideo) {
        if (editorialVideo == null) {
            return null;
        }
        for (String key : MOTION_KEYS) {
            JSONObject video = editorialVideo.optJSONObject(key);
            String url = video != null ? video.optString("video") : "";
            if (url.startsWith("https://")) {
                return url;
            }
        }
        return null;
    }

    private static String albumKey(String album) {
        String key = TrackQuery.normalize(album);
        return !key.isEmpty() || album == null ? key : album.trim().toLowerCase(Locale.ROOT);
    }

    private static HashSet<String> versionTags(String title) {
        HashSet<String> tags = new HashSet<>();
        if (title != null) {
            String words = " " + TrackQuery.NOT_WORD.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ").trim() + " ";
            for (String tag : VERSION_TAGS) {
                if (words.contains(" " + tag + " ")) {
                    tags.add(tag);
                }
            }
        }
        return tags;
    }

    private static boolean isOwnRelease(JSONObject album, String artist) {
        return !album.optBoolean("isCompilation")
                && !TrackQuery.normalize(album.optString("artistName")).equals("variousartists")
                && artistMatches(album.optString("artistName"), artist);
    }

    private static boolean artistMatches(String catalogArtist, String artist) {
        String got = TrackQuery.normalize(catalogArtist);
        String want = TrackQuery.normalize(artist);
        if (got.isEmpty() || want.isEmpty()) {
            return false;
        }
        if (got.contains(want) || want.contains(got)) {
            return true;
        }
        for (String part : ARTIST_SEPARATOR.split(artist)) {
            String name = TrackQuery.normalize(part);
            if (name.length() >= 2 && got.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
