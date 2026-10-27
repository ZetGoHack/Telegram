package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Settings of the inline calculator in the chat input ("rawgram_calc"). Every value is cached in a static.
 * The calculator itself is a port of exteraGram's "inline results" (GPLv3); the Calcmula provider is rawGram's own.
 */
public class RawCalcConfig {

    public static final int PROVIDER_LOCAL = 0;
    public static final int PROVIDER_CALCMULA = 1;
    public static final int PROVIDER_LOCAL_THEN_CALCMULA = 2;
    public static final int DISPLAY_GHOST = 0;
    public static final int DISPLAY_PANEL = 1;

    private static SharedPreferences prefs;

    private static SharedPreferences prefs() {
        if (prefs == null) {
            prefs = ApplicationLoader.applicationContext.getSharedPreferences("rawgram_calc", Context.MODE_PRIVATE);
        }
        return prefs;
    }

    private static boolean ready() {
        return ApplicationLoader.applicationContext != null;
    }

    /** A cached boolean preference. */
    public static final class Flag {
        private final String key;
        private final boolean def;
        private int cached = -1;

        Flag(String key, boolean def) {
            this.key = key;
            this.def = def;
        }

        public boolean get() {
            if (cached < 0) {
                if (!ready()) {
                    return def;
                }
                cached = prefs().getBoolean(key, def) ? 1 : 0;
            }
            return cached == 1;
        }

        public void set(boolean value) {
            cached = value ? 1 : 0;
            prefs().edit().putBoolean(key, value).apply();
        }
    }

    /** A cached int preference. */
    public static final class Choice {
        private final String key;
        private final int def;
        private int cached = Integer.MIN_VALUE;

        Choice(String key, int def) {
            this.key = key;
            this.def = def;
        }

        public int get() {
            if (cached == Integer.MIN_VALUE) {
                if (!ready()) {
                    return def;
                }
                cached = prefs().getInt(key, def);
            }
            return cached;
        }

        public void set(int value) {
            cached = value;
            prefs().edit().putInt(key, value).apply();
        }
    }

    /** The calculator in the chat input at all. */
    public static final Flag enabled = new Flag("enabled", true);
    /** Arithmetic, percent, functions, number bases. */
    public static final Flag math = new Flag("math", true);
    /** Units: length, mass, temperature, speed, time. */
    public static final Flag units = new Flag("units", true);
    /** Currencies (Stars always work offline; the rest need {@link #onlineRates}). */
    public static final Flag currency = new Flag("currency", true);
    /** «6pm est =», «now in tokyo =». */
    public static final Flag timeZones = new Flag("timeZones", false);
    /** sin / cos / tan in degrees instead of radians. */
    public static final Flag degrees = new Flag("degrees", true);
    /** Thousands separated by a space in results. */
    public static final Flag grouping = new Flag("grouping", true);
    /** Fiat and crypto rates from Coinbase; off: only Stars (the rate Telegram gives). */
    public static final Flag onlineRates = new Flag("onlineRates", true);
    /** Calcmula (a third-party server) may be asked; off by default. */
    public static final Flag calcmula = new Flag("calcmula", false);
    public static final Choice provider = new Choice("provider", PROVIDER_LOCAL);
    /** Where the answer shows: grey after the cursor, or a row above the field. */
    public static final Choice display = new Choice("display", DISPLAY_GHOST);

    /** Whether Calcmula takes part: chosen as a provider and allowed. */
    public static boolean useCalcmula() {
        return calcmula.get() && provider.get() != PROVIDER_LOCAL;
    }

    /** Whether the local parser takes part. */
    public static boolean useLocal() {
        return provider.get() != PROVIDER_CALCMULA;
    }
}
