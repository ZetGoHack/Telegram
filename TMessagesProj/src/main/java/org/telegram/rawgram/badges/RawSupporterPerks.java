package org.telegram.rawgram.badges;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * The perks list shared by the supporter sheets. A row gets a chevron and opens something only when its action is
 * not null.
 * <p>
 * Ported from exteraGram's {@code SupporterPerks} (exteraGram, GPLv3).
 */
final class RawSupporterPerks {

    private RawSupporterPerks() {
    }

    static void addTo(Context context, Utilities.Callback<View> add, Theme.ResourcesProvider resourcesProvider,
                      Runnable onBadge, Runnable onNowPlaying, Runnable onEarlyBuilds) {
        RawSupporterFeatureCell badge = new RawSupporterFeatureCell(context, R.drawable.menu_supporter_badge,
                RawBadgeText.get(R.string.RawSupporterPerkBadge), RawBadgeText.get(R.string.RawSupporterPerkBadgeInfo), resourcesProvider);
        badge.setAction(onBadge);
        add.run(badge);

        RawSupporterFeatureCell nowPlaying = new RawSupporterFeatureCell(context, R.drawable.msg_voice_headphones,
                RawBadgeText.get(R.string.RawSupporterPerkNowPlaying), RawBadgeText.get(R.string.RawSupporterPerkNowPlayingInfo), resourcesProvider);
        nowPlaying.setAction(onNowPlaying);
        add.run(nowPlaying);

        add.run(new RawSupporterFeatureCell(context, R.drawable.msg_calendar2,
                RawBadgeText.get(R.string.RawSupporterPerkRegDate), RawBadgeText.get(R.string.RawSupporterPerkRegDateInfo), resourcesProvider));

        RawSupporterFeatureCell earlyBuilds = new RawSupporterFeatureCell(context, R.drawable.msg_channel,
                RawBadgeText.get(R.string.RawSupporterPerkEarlyBuilds), RawBadgeText.get(R.string.RawSupporterPerkEarlyBuildsInfo), resourcesProvider);
        earlyBuilds.setAction(onEarlyBuilds);
        add.run(earlyBuilds);
    }
}
