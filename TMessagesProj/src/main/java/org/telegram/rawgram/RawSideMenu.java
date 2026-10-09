package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.drawer.RawDrawerContainer;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MenuDrawable;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SwipeGestureSettingsView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

/**
 * «Боковое меню»: the entry points the Telegram classes call (one-line hooks). The menu itself is
 * {@link RawDrawerContainer}, a port of exteraGram's drawer, laid over the app as a child of LaunchActivity's root
 * (one per LaunchActivity). It opens with the ☰ button of the chats list or, as in exteraGram, a swipe to the right
 * on the chats list (anywhere on the first folder when folders are not switched by swiping, otherwise from the left
 * edge) and closes with back (including the predictive back gesture), a tap outside or a swipe to the left.
 */
public final class RawSideMenu {

    private static RawDrawerContainer instance;

    private RawSideMenu() {
    }

    public static boolean enabled() {
        return RawUiConfig.sideMenu();
    }

    /** LaunchActivity.onCreate, after the drawer layout container is added to the root. */
    public static void attach(LaunchActivity activity, FrameLayout root) {
        instance = null;
        if (!enabled()) {
            return;
        }
        instance = new RawDrawerContainer(activity);
        root.addView(instance, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    /** DialogsActivity: the ☰ button. */
    public static boolean open() {
        if (instance == null) {
            return false;
        }
        instance.openDrawer(true);
        return true;
    }

    /**
     * LaunchActivity.onBackPressed(boolean): {@code invoked == false} is the start of a predictive back gesture,
     * {@code true} is the back itself. Returns true when the menu took it.
     */
    public static boolean onBack(boolean invoked) {
        if (instance == null || !instance.isDrawerOpen() || instance.isPreviewAbove()) {
            // an account preview over the menu is closed by the app itself
            return false;
        }
        if (invoked) {
            instance.commitPredictiveBack();
        } else {
            instance.startPredictiveBack();
        }
        return true;
    }

    /** LaunchActivity's back animation callback: the predictive back gesture moved. */
    public static void onBackProgress(float progress) {
        if (instance != null) {
            instance.updatePredictiveBackProgress(progress);
        }
    }

    /** LaunchActivity's back animation callback: the predictive back gesture was cancelled. */
    public static void onBackCancelled() {
        if (instance != null) {
            instance.cancelPredictiveBack();
        }
    }

    /** LaunchActivity.dispatchTouchEvent: the swipe from the left edge of the chats list opens the menu. */
    public static boolean dispatchTouchEvent(LaunchActivity activity, MotionEvent ev, Utilities.CallbackReturn<MotionEvent, Boolean> superDispatch) {
        RawDrawerContainer drawer = instance;
        if (drawer == null) {
            return superDispatch.run(ev);
        }
        if (drawer.isEdgeSwipeTracking()) {
            drawer.handleEdgeSwipeTouch(ev);
            return true;
        }
        if (drawer.isDrawerOpen()) {
            // the open menu is on top of the app and handles its own touches
            return superDispatch.run(ev);
        }
        int start = ev.getActionMasked() == MotionEvent.ACTION_DOWN ? swipeStartArea(activity, ev) : RawDrawerContainer.SWIPE_NONE;
        if (drawer.handleEdgeSwipeIntercept(ev, start)) {
            // the app saw the start of this gesture: cancel it there
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            superDispatch.run(cancel);
            cancel.recycle();
            return true;
        }
        return superDispatch.run(ev);
    }

    /** Where a swipe that starts with {@code down} may open the menu: nowhere, only from the left edge, or anywhere. */
    private static int swipeStartArea(LaunchActivity activity, MotionEvent down) {
        if (activity.getActionBarLayout() == null || activity.getActionBarLayout().getFragmentStack().size() != 1
                || !activity.getActionBarLayout().allowSwipe()) {
            return RawDrawerContainer.SWIPE_NONE;
        }
        BaseFragment last = activity.getActionBarLayout().getLastFragment();
        if (!(last instanceof MainTabsActivity) || !((MainTabsActivity) last).rawIsChatsPage()
                || last.getLastSheet() != null && last.getLastSheet().attachedToParent()) {
            return RawDrawerContainer.SWIPE_NONE;
        }
        DialogsActivity dialogs = ((MainTabsActivity) last).getDialogsActivity();
        if (dialogs == null || dialogs.getFragmentView() == null || !dialogs.rawCanSwipeOpenSideMenu()) {
            return RawDrawerContainer.SWIPE_EDGE;
        }
        View root = activity.getActionBarLayout().getView();
        int[] location = new int[2];
        root.getLocationInWindow(location);
        float x = down.getX() - location[0], y = down.getY() - location[1];
        // the bottom tab bar and anything under the finger that scrolls sideways keep the gesture
        int tabsTop = root.getHeight() - AndroidUtilities.navigationBarHeight - dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
        if (y >= tabsTop || findScrollingChild(root, x, y) != null) {
            return RawDrawerContainer.SWIPE_EDGE;
        }
        return RawDrawerContainer.SWIPE_ANYWHERE;
    }

    /** DialogsActivity.rawCanSwipeOpenSideMenu: the list is idle and a swipe to the right does not switch folders. */
    public static boolean canSwipeOpen(DialogsActivity dialogs, boolean busy, FilterTabsView filterTabs) {
        if (busy || dialogs.isArchive() || dialogs.getActionBar() != null && dialogs.getActionBar().isActionModeShowed()) {
            return false;
        }
        if (filterTabs == null || filterTabs.getVisibility() != View.VISIBLE) {
            return true;
        }
        if (filterTabs.isEditing() || filterTabs.isAnimatingIndicator()) {
            return false;
        }
        return SharedConfig.getChatSwipeAction(dialogs.getCurrentAccount()) != SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS
                || filterTabs.isFirstTabSelected();
    }

    private static final Rect hitRect = new Rect();

    private static View findScrollingChild(View view, float x, float y) {
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            child.getHitRect(hitRect);
            if (!hitRect.contains((int) x, (int) y)) {
                continue;
            }
            if (child.canScrollHorizontally(-1)) {
                return child;
            }
            View found = findScrollingChild(child, x - hitRect.left, y - hitRect.top);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * DrawerLayoutContainer.isDrawCurrentPreviewFragmentAbove: while the menu shows an account preview, the fragment
     * stack skips drawing it and the menu draws it above itself.
     */
    public static boolean drawsPreviewAbove() {
        RawDrawerContainer drawer = instance;
        if (drawer == null || !drawer.isPreviewAbove()) {
            return false;
        }
        drawer.invalidate();
        return true;
    }

    /** DialogsActivity: the header shows ☰, so the collapsed stories keep clear of it. */
    public static boolean hasMenuButton(ActionBar actionBar) {
        return actionBar != null && actionBar.getBackButton() != null && actionBar.getBackButton().getDrawable() instanceof MenuDrawable;
    }

    /** DialogsActivity: the chats of another account shown as a preview from the menu (no search field there). */
    public static boolean isAccountPreview(BaseFragment fragment) {
        return fragment.isInPreviewMode() && fragment.getArguments() != null
                && fragment.getArguments().getBoolean(RawDrawerContainer.ARG_ACCOUNT_PREVIEW, false);
    }
}
