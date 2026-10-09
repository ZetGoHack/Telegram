package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.drawable.GradientDrawable;

import androidx.core.graphics.ColorUtils;

/**
 * Small main-screen options from Nagram / NekoX: archive by pull (openArchiveOnPull), no unarchive by swipe
 * (DoNotUnarchiveBySwipe), folder tabs without counters (IgnoreUnreadCount), the downloads icon always shown
 * (AlwaysShowDownloadIcon) and an outlined folder tab selector (tabStyleStroke).
 */
public final class RawMainScreen {

    private RawMainScreen() {
    }

    /** FilterTabsView: the selected tab's pill, right before it is drawn. */
    public static void tabSelector(GradientDrawable selector, int activeColor, int lineColor) {
        if (RawChatUiConfig.tabStroke.get()) {
            selector.setStroke(dp(1), activeColor);
            selector.setColor(ColorUtils.setAlphaComponent(lineColor, 50));
            selector.setAlpha(255);
        } else {
            selector.setStroke(0, 0);
            selector.setAlpha(31);
        }
    }
}
