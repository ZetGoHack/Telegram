package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

/**
 * «Папки внизу» (Nagram's FoldersAtBottom / FoldersHelper): the folder tabs bar sits above the bottom tabs instead of
 * under the header. Read once per process (applies after a restart); DialogsActivity asks {@link #on()} wherever the
 * bar's height counts toward the header, and places the bar with {@link #place}.
 */
public final class RawFoldersBottom {

    /** Folder bar height: 36 dp tabs + 7 dp padding on both sides. */
    public static final int BAR_DP = 36 + 7 + 7;

    private static int on = -1;

    private RawFoldersBottom() {
    }

    public static boolean on() {
        if (on < 0) {
            on = RawUiConfig.foldersAtBottom() ? 1 : 0;
        }
        return on == 1;
    }

    /** DialogsActivity.createView: the bar with its glass background, glued to the bottom. */
    public static void setup(ViewGroup contentView, FilterTabsView tabs, BlurredBackgroundDrawableViewFactory glass, Theme.ResourcesProvider rp) {
        BlurredBackgroundDrawable background = glass.create(tabs, BlurredBackgroundProviderImpl.topPanel(rp));
        background.setRadius(dp(18));
        background.setPadding(dp(6.666f));
        tabs.setPadding(0, dp(7), 0, dp(7));
        tabs.setBlurredBackground(background);
        contentView.addView(tabs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, BAR_DP, Gravity.BOTTOM, 4, 0, 4, 0));
    }

    private static boolean shown(View tabs) {
        return on() && tabs != null && tabs.getVisibility() == View.VISIBLE && tabs.getAlpha() > 0;
    }

    /** The bar above the bottom tabs (or above the navigation bar where there are no tabs). */
    public static void place(View tabs, int navigationBarHeight, int bottomTabsHeight) {
        if (!on() || tabs == null) {
            return;
        }
        tabs.setTranslationY(-navigationBarHeight - bottomTabsHeight + dp(bottomTabsHeight > 0 ? 4 : -6));
    }

    /** How much higher the floating buttons go while the bar is shown. */
    public static float fabShift(View tabs) {
        return shown(tabs) ? dp(BAR_DP - 4) * tabs.getAlpha() : 0;
    }

    /** Extra list padding at the bottom, so the last chat isn't under the bar. */
    public static int listPadding(View tabs) {
        return shown(tabs) ? dp(BAR_DP) : 0;
    }

    /** Extra height above the bottom tabs' blur region that the bar needs. */
    public static int blurExtra(View tabs) {
        return shown(tabs) ? dp(BAR_DP + 8) : 0;
    }
}
