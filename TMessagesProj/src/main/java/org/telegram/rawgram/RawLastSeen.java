package org.telegram.rawgram;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * «Был(а) в сети: 5 минут назад» for last seen less than a day ago (exteraGram's RelativeLastSeen, GPLv3).
 */
public final class RawLastSeen {

    private RawLastSeen() {
    }

    /** The relative text for a last-seen time in seconds, or null to keep the stock format. */
    public static String format(long date) {
        if (!RawChatUiConfig.relativeLastSeen.get()) {
            return null;
        }
        long diff = System.currentTimeMillis() / 1000 - date;
        if (diff < 0) {
            diff = 0;
        }
        if (diff >= 86400) {
            return null;
        }
        return LocaleController.formatString(R.string.LastSeenDateFormatted, LocaleController.formatRelativeDate(diff));
    }
}
