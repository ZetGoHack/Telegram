package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;

import java.util.ArrayList;

/**
 * The real folder tabs of the chats list ({@link FilterTabsView} with the same glass pill background and
 * the same tab-building code as DialogsActivity.updateFilterTabs, including rawGram's {@link RawFolderTabs}),
 * filled with the account's folders, or with sample folders when the account has none. Not interactive.
 */
@SuppressLint("ViewConstructor")
public class RawFolderTabsPreview extends FrameLayout {

    public static final int HEIGHT_DP = 36 + 7 + 7;

    private static ArrayList<MessagesController.DialogFilter> sampleFilters;

    private final int account;
    private final FilterTabsView tabsView;
    private final BlurredBackgroundSourceColor backgroundSource = new BlurredBackgroundSourceColor();
    private final BlurredBackgroundDrawable background;
    private ArrayList<MessagesController.DialogFilter> filters = new ArrayList<>();
    private boolean sample;

    public RawFolderTabsPreview(Context context, int account) {
        super(context);
        this.account = account;

        tabsView = new FilterTabsView(context, null);
        tabsView.setDelegate(new FilterTabsView.FilterTabsViewDelegate() {
            @Override
            public void onPageSelected(FilterTabsView.Tab tab, boolean forward) {
            }

            @Override
            public void onPageScrolled(float progress) {
            }

            @Override
            public void onSamePageSelected() {
            }

            @Override
            public int getTabCounter(int tabId) {
                return counter(tabId);
            }

            @Override
            public boolean didSelectTab(FilterTabsView.TabView tabView, boolean selected) {
                return false;
            }

            @Override
            public boolean isTabMenuVisible() {
                return false;
            }

            @Override
            public void onDeletePressed(int id) {
            }

            @Override
            public void onPageReorder(int fromId, int toId) {
            }

            @Override
            public boolean canPerformActions() {
                return false;
            }
        });

        // same background as the chats list: the "top panel" glass pill (plain colour source, as without blur)
        backgroundSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        background = new BlurredBackgroundDrawableViewFactory(backgroundSource).create(tabsView, BlurredBackgroundProviderImpl.topPanel(null));
        background.setRadius(dp(18));
        background.setPadding(dp(6.666f));
        tabsView.setPadding(0, dp(7), 0, dp(7));
        tabsView.setBlurredBackground(background);
        addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, HEIGHT_DP, Gravity.TOP, 4, 0, 4, 0));
    }

    private static ArrayList<MessagesController.DialogFilter> sampleFilters() {
        if (sampleFilters == null) {
            ArrayList<MessagesController.DialogFilter> list = new ArrayList<>();
            list.add(sample(0, null, 0));
            list.add(sample(1, "Личные", MessagesController.DIALOG_FILTER_FLAG_CONTACTS | MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS));
            list.add(sample(2, "Группы", MessagesController.DIALOG_FILTER_FLAG_GROUPS));
            list.add(sample(3, "Боты", MessagesController.DIALOG_FILTER_FLAG_BOTS));
            list.add(sample(4, "Каналы", MessagesController.DIALOG_FILTER_FLAG_CHANNELS));
            sampleFilters = list;
        }
        return sampleFilters;
    }

    private static MessagesController.DialogFilter sample(int id, String name, int flags) {
        MessagesController.DialogFilter f = new MessagesController.DialogFilter();
        f.id = id;
        f.name = name == null ? "" : name;
        f.flags = flags;
        return f;
    }

    private int counter(int tabId) {
        if (tabId < 0 || tabId >= filters.size()) {
            return 0;
        }
        if (sample) {
            return tabId == 0 ? 3 : tabId == 2 ? 1 : 0;
        }
        MessagesController.DialogFilter f = filters.get(tabId);
        if (f.isDefault()) {
            return MessagesStorage.getInstance(account).getMainUnreadCount();
        }
        return f.unreadCount;
    }

    /** Rebuilds the tabs from the current options (the first visible tab is the open one). */
    public void bind() {
        ArrayList<MessagesController.DialogFilter> real = MessagesController.getInstance(account).getDialogFilters();
        sample = real == null || real.size() <= 1;
        filters = sample ? sampleFilters() : new ArrayList<>(real);
        // sample folders have no server ids: account -1 makes RawFolderTabs derive their icons from the flags
        int iconAccount = sample ? -1 : account;

        backgroundSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        background.updateColors();

        tabsView.resetTabId();
        tabsView.removeTabs();
        for (int a = 0, n = filters.size(); a < n; a++) {
            MessagesController.DialogFilter filter = filters.get(a);
            if (RawFolderTabs.addTab(tabsView, a, filter, iconAccount)) {
                continue;
            }
            if (filter.isDefault()) {
                tabsView.addTab(a, 0, LocaleController.getString(R.string.FilterAllChats), null, false, true, filter.locked);
            } else {
                tabsView.addTab(a, filter.localId, filter.name, filter.entities, filter.title_noanimate, false, filter.locked);
            }
        }
        if (!tabsView.isEmpty()) {
            tabsView.selectTabWithStableId(tabsView.getStableId(0));
        }
        tabsView.finishAddingTabs(false);
        tabsView.updateColors();
        tabsView.invalidate();
    }

    /** Name of the folder open in the preview, or null when it is «Все чаты». */
    public String openFolderName() {
        for (MessagesController.DialogFilter f : filters) {
            if (f.isDefault()) {
                if (!RawUiConfig.hideAllTab()) {
                    return null;
                }
                continue;
            }
            return f.name;
        }
        return null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP) + getPaddingTop() + getPaddingBottom(), MeasureSpec.EXACTLY));
    }
}
