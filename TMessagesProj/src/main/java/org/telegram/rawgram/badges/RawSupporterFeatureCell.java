package org.telegram.rawgram.badges;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;

/**
 * A perk row of the supporter sheets: outline icon, bold title, grey description, and a chevron when the row
 * leads somewhere.
 * <p>
 * Ported from exteraGram's {@code SupporterFeatureCell} (exteraGram, GPLv3).
 */
public class RawSupporterFeatureCell extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final FrameLayout content;
    private final LinkSpanDrawable.LinksTextView subtitleView;
    private final ImageView nextIcon;

    public RawSupporterFeatureCell(Context context, int icon, CharSequence title, CharSequence subtitle, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        boolean rtl = LocaleController.isRTL;
        int gravity = rtl ? Gravity.RIGHT : Gravity.LEFT;

        content = new FrameLayout(context);
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ImageView iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setImageResource(icon);
        iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), PorterDuff.Mode.SRC_IN));
        content.addView(iconView, LayoutHelper.createFrame(24, 24, gravity | Gravity.TOP, rtl ? 0 : 21, 10, rtl ? 21 : 0, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTextSize(15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(gravity);
        texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        subtitleView.setText(subtitle);
        subtitleView.setTypeface(android.graphics.Typeface.DEFAULT);
        subtitleView.setTextSize(14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        subtitleView.setLinkTextColor(Theme.getColor(Theme.key_chat_messageLinkIn, resourcesProvider));
        subtitleView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        subtitleView.setGravity(gravity);
        texts.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        content.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity | Gravity.TOP, rtl ? 48 : 64, 8, rtl ? 64 : 48, 9));

        nextIcon = new ImageView(context);
        nextIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        nextIcon.setImageResource(R.drawable.msg_arrowright);
        nextIcon.setColorFilter(Theme.getColor(Theme.key_switchTrack, resourcesProvider));
        nextIcon.setScaleX(rtl ? -1f : 1f);
        nextIcon.setVisibility(GONE);
        content.addView(nextIcon, LayoutHelper.createFrame(24, 24, (rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 18, 0, 18, 0));
    }

    public void setSubtitle(CharSequence subtitle) {
        subtitleView.setText(subtitle);
    }

    /** Makes the row tappable (selector and chevron); null makes it plain text again. */
    public void setAction(Runnable action) {
        nextIcon.setVisibility(action != null ? VISIBLE : GONE);
        content.setBackground(action != null ? Theme.getSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), false) : null);
        if (action != null) {
            content.setOnClickListener(v -> action.run());
        } else {
            content.setOnClickListener(null);
            content.setClickable(false);
        }
    }
}
