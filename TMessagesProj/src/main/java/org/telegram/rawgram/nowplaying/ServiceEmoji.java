package org.telegram.rawgram.nowplaying;

/**
 * Custom emoji drawn as the pattern on the «Сейчас играет» card, by the track's platform. Ported from exteraGram's
 * {@code ServiceEmoji} (exteraGram, GPLv3).
 */
public enum ServiceEmoji {
    MUSIC(5271627010681108586L),
    SPOTIFY(5271857023359681001L),
    TELEGRAM(5325674462522144646L);

    public final long documentId;

    ServiceEmoji(long documentId) {
        this.documentId = documentId;
    }

    /** Platform name → emoji, {@link #MUSIC} when unknown. */
    public static ServiceEmoji fromString(String name) {
        if (name != null) {
            for (ServiceEmoji emoji : values()) {
                if (emoji.name().equalsIgnoreCase(name)) {
                    return emoji;
                }
            }
        }
        return MUSIC;
    }
}
