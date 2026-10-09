package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.DialogsActivity;

/**
 * «Кнопка поиска у вкладок» (Nagram's MainTabsShowSearchButton): a round glass button next to the bottom tabs that
 * opens the chats search. Read when the main screen is created.
 */
public final class RawTabsSearch {

    private RawTabsSearch() {
    }

    /**
     * MainTabsActivity, after the tabs are put into their wrapper. Returns the button (or null when the option is off);
     * the tabs get a right margin for it.
     */
    public static View attach(FrameLayout wrapper, View tabs, BlurredBackgroundDrawableViewFactory glass,
                              BlurredBackgroundColorProvider colors, Theme.ResourcesProvider rp, Runnable onClick) {
        if (!RawUiConfig.mainTabsSearch()) {
            return null;
        }
        Context context = wrapper.getContext();
        int size = DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS;
        FrameLayout button = new FrameLayout(context);
        BlurredBackgroundDrawable background = glass.create(button, colors);
        background.setRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
        background.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
        button.setBackground(background);
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.outline_header_search);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_glass_tabUnselected, rp), PorterDuff.Mode.SRC_IN));
        button.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        button.setOnClickListener(v -> onClick.run());
        button.setContentDescription("Поиск");
        wrapper.addView(button, LayoutHelper.createFrame(size, size, Gravity.BOTTOM | Gravity.RIGHT));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tabs.getLayoutParams();
        lp.rightMargin = dp(size - DialogsActivity.MAIN_TABS_MARGIN);
        tabs.setLayoutParams(lp);
        return button;
    }

    /** checkUi_tabsPosition: the button hides with the tabs. */
    public static void sync(View button, float visible) {
        if (button == null) {
            return;
        }
        button.setAlpha(visible);
        button.setVisibility(visible > 0 ? View.VISIBLE : View.GONE);
        button.setClickable(visible >= 1);
    }
}
