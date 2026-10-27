package org.telegram.rawgram.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Switches of the rawGram music player (SharedPreferences "rawgram_player", cached). The player section is ported from
 * exteraGram (exteraSquad, GPL-2.0-or-later). Everything is off by default, so stock Telegram behavior is unchanged
 * until the new player is turned on.
 */
public final class RawPlayerConfig {

    private static final String PREFS = "rawgram_player";

    private static boolean loaded;
    private static boolean newPlayerStyle;
    private static int playerMode;
    private static boolean coverColors = true;
    private static boolean miniCoverColors;
    private static boolean speedPitch;
    private static boolean particles;
    private static boolean rhythmBackground;
    private static boolean lyricsOnline;
    private static int lyricsProgress;

    private RawPlayerConfig() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        synchronized (RawPlayerConfig.class) {
            if (loaded) {
                return;
            }
            SharedPreferences p = prefs();
            newPlayerStyle = p.getBoolean("newPlayerStyle", false);
            playerMode = p.getInt("playerMode", 0);
            coverColors = p.getBoolean("coverColors", true);
            miniCoverColors = p.getBoolean("miniCoverColors", false);
            speedPitch = p.getBoolean("speedPitch", false);
            particles = p.getBoolean("particles", false);
            rhythmBackground = p.getBoolean("rhythmBackground", false);
            lyricsOnline = p.getBoolean("lyricsOnline", false);
            lyricsProgress = p.getInt("lyricsProgress", 0);
            loaded = true;
        }
    }

    private static void put(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    private static void put(String key, int value) {
        prefs().edit().putInt(key, value).apply();
    }

    public static boolean getNewPlayerStyle() {
        load();
        return newPlayerStyle;
    }

    public static void setNewPlayerStyle(boolean value) {
        load();
        newPlayerStyle = value;
        put("newPlayerStyle", value);
    }

    /** Layout of the sheet: 0 cover, 1 lyrics, 2 queue (the last page the user left it on). */
    public static int getPlayerMode() {
        load();
        return playerMode;
    }

    public static void setPlayerMode(int value) {
        load();
        playerMode = value;
        put("playerMode", value);
    }

    public static boolean getPlayerCoverColors() {
        load();
        return coverColors;
    }

    public static void setPlayerCoverColors(boolean value) {
        load();
        coverColors = value;
        put("coverColors", value);
    }

    public static boolean getPlayerMiniCoverColors() {
        load();
        return miniCoverColors;
    }

    public static void setPlayerMiniCoverColors(boolean value) {
        load();
        miniCoverColors = value;
        put("miniCoverColors", value);
    }

    /** Playback speed changes the pitch too (like a tape), only with the new player. */
    public static boolean getPlayerSpeedPitch() {
        load();
        return speedPitch;
    }

    /** True when music speed must change the pitch (the new player is on and the option is set). */
    public static boolean tapeSpeed() {
        return getNewPlayerStyle() && getPlayerSpeedPitch();
    }

    public static void setPlayerSpeedPitch(boolean value) {
        load();
        speedPitch = value;
        put("speedPitch", value);
    }

    public static boolean getPlayerParticles() {
        load();
        return particles;
    }

    public static void setPlayerParticles(boolean value) {
        load();
        particles = value;
        put("particles", value);
    }

    public static boolean getPlayerRhythmBackground() {
        load();
        return rhythmBackground;
    }

    public static void setPlayerRhythmBackground(boolean value) {
        load();
        rhythmBackground = value;
        put("rhythmBackground", value);
    }

    /** Look up synced lyrics on lrclib.net (sends artist, title and duration to that server). */
    public static boolean getLyricsOnline() {
        load();
        return lyricsOnline;
    }

    public static void setLyricsOnline(boolean value) {
        load();
        lyricsOnline = value;
        put("lyricsOnline", value);
    }

    /** 0 off, 1 horizontal, 2 vertical progress of the lyrics line. */
    public static int getLyricsProgress() {
        load();
        return lyricsProgress;
    }

    public static void setLyricsProgress(int value) {
        load();
        lyricsProgress = value;
        put("lyricsProgress", value);
    }
}
