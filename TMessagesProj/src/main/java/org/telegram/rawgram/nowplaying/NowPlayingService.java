package org.telegram.rawgram.nowplaying;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * Scrobbling service a user links for the «Сейчас играет» card. Ported from exteraGram's
 * {@code NowPlayingServiceType} (exteraGram, GPLv3); {@link #name()} is the value the exteraGram API and bot use.
 */
public enum NowPlayingService {
    NONE,
    LAST_FM,
    STATS_FM;

    public String getDisplayName() {
        switch (this) {
            case LAST_FM:
                return "Last.fm";
            case STATS_FM:
                return "Stats.fm";
            default:
                return LocaleController.getString(R.string.None);
        }
    }

    /** API value → service; unknown or missing values are {@link #NONE}. */
    public static NowPlayingService fromApi(String value) {
        if (value != null) {
            for (NowPlayingService service : values()) {
                if (service.name().equalsIgnoreCase(value)) {
                    return service;
                }
            }
        }
        return NONE;
    }
}
