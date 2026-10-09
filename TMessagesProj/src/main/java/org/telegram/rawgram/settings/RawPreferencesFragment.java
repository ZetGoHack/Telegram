package org.telegram.rawgram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;

/**
 * Common frame of rawGram's settings screens, after exteraGram's BasePreferencesActivity (exteraSquad, GPL):
 * the list of rounded section cards scrolls under a floating glass action bar (back button and title in
 * glass pills), the screen background fades in under the bar once the list is scrolled, the list runs
 * edge to edge with the system bars as padding, and the scroll position survives theme changes.
 * Subclasses only build the list ({@link #createListView}) and handle their rows.
 */
public abstract class RawPreferencesFragment extends BaseFragment {

    private static final long FADE_DURATION = 320;
    private static final int FADE_HEIGHT_DP = 60;

    protected RecyclerListView listView;
    protected LinearLayoutManager layoutManager;

    private final BlurredBackgroundSourceColor glassSource = new BlurredBackgroundSourceColor();
    private ChatActivityFadeView fadeView;
    private ValueAnimator fadeAnimator;
    private boolean scrolled;
    private float fadeAlpha;
    private Parcelable listState;

    /** Title of the action bar. */
    protected abstract String getTitle();

    /** Creates the list with its adapter and click listeners. Sections, padding and layout manager are set here. */
    protected abstract RecyclerListView createListView(Context context);

    /**
     * True when the list starts with its own big title (the hub header): the bar has only the back button, without
     * a title pill.
     */
    protected boolean hideTitleAtTop() {
        return false;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        if (hideTitleAtTop()) {
            // the screen's own header names it: only the back button floats, no empty title pill
            actionBar.setGlassOnlyBack();
        } else {
            actionBar.setTitle(getTitle());
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.setCastShadows(false);
        actionBar.setAddToContainer(false);

        int background = getThemedColor(Theme.key_windowBackgroundGray);
        glassSource.setColor(background);
        actionBar.setupGlass(new BlurredBackgroundDrawableViewFactory(glassSource), BlurredBackgroundProviderImpl.topPanelChatActivity(getResourceProvider()));
        int textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        actionBar.setTitleColor(textColor);
        actionBar.setItemsColor(textColor, false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);

        ContentView content = new ContentView(context);
        content.setBackgroundColor(background);

        listView = createListView(context);
        listView.setSections();
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, listTopPadding(AndroidUtilities.statusBarHeight), 0, 0);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        if (listState != null) {
            layoutManager.onRestoreInstanceState(listState);
            listState = null;
        }
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateScrolled(true);
            }
        });
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fadeView = new ChatActivityFadeView(context);
        fadeView.setupColorKey(Theme.key_windowBackgroundGray);
        fadeView.setFadeHeightTop(dp(FADE_HEIGHT_DP));
        fadeView.setFadeTopAlpha(0);
        content.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        content.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        scrolled = false;
        setFadeAlpha(0);
        return fragmentView = content;
    }

    /** List top padding: room for the status bar and the floating action bar. */
    protected int listTopPadding(int topInset) {
        return topInset + ActionBar.getCurrentActionBarHeight();
    }

    @Override
    public void clearViews() {
        if (fragmentView != null && layoutManager != null) {
            listState = layoutManager.onSaveInstanceState();
        }
        super.clearViews();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, listTopPadding(top), 0, bottom);
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundGray)) > .721f;
    }

    private void updateTopFade() {
        if (fadeView != null && actionBar != null) {
            fadeView.setFadeZoneTop(actionBar.getMeasuredHeight() + dp(2));
        }
    }

    private void updateScrolled(boolean animated) {
        if (listView == null) {
            return;
        }
        boolean value = listView.canScrollVertically(-1);
        if (value == scrolled) {
            return;
        }
        scrolled = value;
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }
        float target = value ? 1f : 0f;
        if (!animated) {
            setFadeAlpha(target);
            return;
        }
        fadeAnimator = ValueAnimator.ofFloat(fadeAlpha, target);
        fadeAnimator.addUpdateListener(a -> setFadeAlpha((float) a.getAnimatedValue()));
        fadeAnimator.setDuration(FADE_DURATION);
        fadeAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        fadeAnimator.start();
    }

    private void setFadeAlpha(float alpha) {
        fadeAlpha = alpha;
        if (fadeView != null) {
            fadeView.setFadeTopAlpha(Math.round(alpha * 255));
        }

    }

    /** Root view: re-measures the fade zone with the action bar and follows theme changes. */
    private class ContentView extends FrameLayout implements Theme.Colorable {
        ContentView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            updateTopFade();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            updateScrolled(false);
        }

        @Override
        public void updateColors() {
            int color = getThemedColor(Theme.key_windowBackgroundGray);
            setBackgroundColor(color);
            glassSource.setColor(color);
            updateTopFade();
        }
    }
}
