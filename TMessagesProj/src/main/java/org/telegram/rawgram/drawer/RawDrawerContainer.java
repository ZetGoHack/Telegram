package org.telegram.rawgram.drawer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.rawgram.RawMotion;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.ProxyListActivity;
import org.telegram.ui.SelectAnimatedEmojiDialog;
import org.telegram.ui.ThemeActivity;

/**
 * The side menu, ported from exteraGram's drawer (exteraSquad, GPL): a panel with rounded outer corners that slides
 * in from the left over a dim, pushing the app a little to the right. It follows the finger (swipe from the left edge
 * of the chats list to open, swipe left on it to close), settles with a spring and a fling, closes on a tap outside,
 * on back and with the predictive back gesture (it shrinks back while the gesture goes).
 * <p>
 * Unlike exteraGram it is not a child of DrawerLayoutContainer but an overlay in LaunchActivity's root, driven by
 * {@link org.telegram.rawgram.RawSideMenu}.
 */
@SuppressLint("ViewConstructor")
public final class RawDrawerContainer extends FrameLayout implements NotificationCenter.NotificationCenterDelegate, RawDrawerItems.Host {

    private static final int COLOR_KEY_BACKGROUND = Theme.key_windowBackgroundWhite;

    /** Where a swipe on the closed menu may start (RawSideMenu decides on ACTION_DOWN). */
    public static final int SWIPE_NONE = 0, SWIPE_EDGE = 1, SWIPE_ANYWHERE = 2;
    /** Argument of the DialogsActivity inside an account preview. */
    public static final String ARG_ACCOUNT_PREVIEW = "drawer_account_preview";

    private static final float OPEN_EPSILON = 0.001f;
    private static final int MAX_WIDTH_DP = 300;
    private static final int MIN_GAP_DP = 56;
    private static final int EDGE_SWIPE_WIDTH_DP = 24;
    private static final int CORNER_RADIUS_DP = 24;
    /** How far the app moves to the right with the open menu, as a part of the menu width. */
    private static final float APP_SHIFT = 0.3f;
    /** Dim over the app with the open menu (0x66 of 0xff). */
    private static final int SCRIM_ALPHA = 102;
    private static final float SPRING_STIFFNESS = 950f, SPRING_STIFFNESS_FAST = 1500f;
    private static final long PRESENT_DELAY = 200;

    private static final FloatPropertyCompat<RawDrawerContainer> DRAWER_OFFSET = new FloatPropertyCompat<RawDrawerContainer>("drawerOffset") {
        @Override
        public float getValue(RawDrawerContainer container) {
            return container.getDrawerOffset();
        }

        @Override
        public void setValue(RawDrawerContainer container, float value) {
            container.setDrawerOffset(value);
        }
    };

    private final LaunchActivity activity;
    private final FrameLayout drawerPanel;
    private final FrameLayout bulletinContainer;
    private final RawDrawerHeaderView headerView;
    private final RawDrawerAccountPickerView accountPickerView;
    private final RawDrawerMenuView menuView;
    private final Paint scrimPaint = new Paint();
    private final Path clipPath = new Path();
    private final float[] radii = new float[8];
    private float topRightRadius = -1, bottomRightRadius = -1;

    private int drawerWidth;
    private float progress;
    private boolean isOpen;
    private boolean isAnimating;
    private SpringAnimation springAnimation;
    private View appShiftTarget;

    // touch tracking
    private VelocityTracker velocityTracker;
    private float startX, startY, startProgress;
    private boolean tracking;
    private boolean startedEdgeSwipe;
    private boolean tapClosePending;
    private boolean animationInterruptedByTouch;

    // predictive back
    private boolean predictiveBackInProgress;
    private float predictiveBackStartProgress;

    private boolean notificationsRegistered;
    private SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow statusPopup;

    public RawDrawerContainer(LaunchActivity activity) {
        super(activity);
        this.activity = activity;
        Theme.createDialogsResources(activity);
        setVisibility(GONE);
        drawerWidth = calculateDrawerWidth();

        drawerPanel = new FrameLayout(activity);
        drawerPanel.setBackgroundColor(Theme.getColor(COLOR_KEY_BACKGROUND));
        drawerPanel.setClickable(true);
        drawerPanel.setTranslationX(-drawerWidth);
        addView(drawerPanel, new LayoutParams(drawerWidth, LayoutHelper.MATCH_PARENT, Gravity.LEFT));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        bulletinContainer = new FrameLayout(activity);
        drawerPanel.addView(bulletinContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        headerView = new RawDrawerHeaderView(activity);
        content.addView(headerView, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, dp(RawDrawerHeaderView.HEIGHT_DP)));
        accountPickerView = new RawDrawerAccountPickerView(activity);
        content.addView(accountPickerView, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        menuView = new RawDrawerMenuView(activity);
        content.addView(menuView, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f));

        setupCallbacks();
        headerView.setChevronExpanded(accountPickerView.isExpanded());
    }

    private void setupCallbacks() {
        headerView.setOnChevronClick(() -> {
            accountPickerView.toggleExpand();
            headerView.setChevronExpanded(accountPickerView.isExpanded());
        });
        headerView.setOnThemeToggle(this::toggleDayNight);
        headerView.setOnThemeToggleLongClick(() -> presentDelayed(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        headerView.setOnProfileClick(() -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
            args.putBoolean("my_profile", true);
            presentDelayed(new ProfileActivity(args));
        });
        headerView.setOnStatusClick(this::showStatusSelect);
        headerView.setOnProxyClick(() -> presentDelayed(new ProxyListActivity()));
        accountPickerView.setCallback(new RawDrawerAccountPickerView.Callback() {
            @Override
            public void onAccountSelected(int account) {
                closeDrawer(true);
                activity.switchToAccount(account, true);
            }

            @Override
            public void onAddAccount() {
                closeDrawer(true);
                AndroidUtilities.runOnUIThread(RawDrawerContainer.this::addAccount, 150);
            }

            @Override
            public void onAccountPreview(int account, View row) {
                showAccountPreview(account);
            }
        });
        menuView.setOnItemClick(() -> closeDrawer(true));
    }

    // ---- RawDrawerItems.Host ----

    @Override
    public void close() {
        closeDrawer(true);
    }

    @Override
    public void present(BaseFragment fragment) {
        closeDrawer(true);
        activity.presentFragment(fragment);
    }

    @Override
    public LaunchActivity activity() {
        return activity;
    }

    /** Header actions open their screen once the menu is mostly out of the way. */
    private void presentDelayed(BaseFragment fragment) {
        closeDrawer(true);
        AndroidUtilities.runOnUIThread(() -> activity.presentFragment(fragment), PRESENT_DELAY);
    }

    // ---- header actions ----

    /** The day / night switch: Telegram's own theme pair and circular reveal, as in the old side menu. */
    private void toggleDayNight() {
        if (DialogsActivity.switchingTheme) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", android.content.Context.MODE_PRIVATE);
        String dayThemeName = preferences.getString("lastDayTheme", "Blue");
        if (Theme.getTheme(dayThemeName) == null || Theme.getTheme(dayThemeName).isDark()) {
            dayThemeName = "Blue";
        }
        String nightThemeName = preferences.getString("lastDarkTheme", "Dark Blue");
        if (Theme.getTheme(nightThemeName) == null || !Theme.getTheme(nightThemeName).isDark()) {
            nightThemeName = "Dark Blue";
        }
        Theme.ThemeInfo active = Theme.getActiveTheme();
        if (dayThemeName.equals(nightThemeName)) {
            if (active.isDark() || dayThemeName.equals("Dark Blue") || dayThemeName.equals("Night")) {
                dayThemeName = "Blue";
            } else {
                nightThemeName = "Dark Blue";
            }
        }
        boolean toDark = dayThemeName.equals(active.getKey());
        Theme.ThemeInfo theme = Theme.getTheme(toDark ? nightThemeName : dayThemeName);
        if (theme == null) {
            return;
        }
        DialogsActivity.switchingTheme = true;
        int[] pos = headerView.getThemeTogglePosition();
        headerView.animateThemeToggle(toDark);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, theme, false, pos, -1, toDark, headerView.getThemeToggleView());
        Theme.turnOffAutoNight(BulletinFactory.of(bulletinContainer, null), () -> present(new ThemeActivity(ThemeActivity.THEME_TYPE_NIGHT)));
    }

    /** The emoji status picker under the status of the name (Premium only). */
    private void showStatusSelect() {
        BaseFragment fragment = getLastFragment();
        if (statusPopup != null || fragment == null) {
            return;
        }
        int account = UserConfig.selectedAccount;
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null || !MessagesController.getInstance(account).isPremiumUser(user)) {
            return;
        }
        SimpleTextView nameView = headerView.getNameView();
        int[] nameLocation = new int[2];
        nameView.getLocationOnScreen(nameLocation);
        int statusX = nameLocation[0] + nameView.getRightDrawableX();
        int popupWidth = (int) Math.min(dp(324), AndroidUtilities.displaySize.x * 0.95f);
        int popupX = MathUtils.clamp(statusX - popupWidth / 2, 0, AndroidUtilities.displaySize.x - popupWidth);
        int popupTop = nameLocation[1] + nameView.getHeight();

        SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] popup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        SelectAnimatedEmojiDialog dialog = new SelectAnimatedEmojiDialog(fragment, getContext(), true, Math.max(0, statusX - popupX),
                SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS, true, null, 16) {
            @Override
            protected void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                TLRPC.EmojiStatus status;
                if (documentId == null) {
                    status = new TLRPC.TL_emojiStatusEmpty();
                } else if (gift != null) {
                    TLRPC.TL_inputEmojiStatusCollectible collectible = new TLRPC.TL_inputEmojiStatusCollectible();
                    collectible.collectible_id = gift.id;
                    if (until != null) {
                        collectible.flags |= 1;
                        collectible.until = until;
                    }
                    status = collectible;
                } else {
                    TLRPC.TL_emojiStatus emojiStatus = new TLRPC.TL_emojiStatus();
                    emojiStatus.document_id = documentId;
                    if (until != null) {
                        emojiStatus.flags |= 1;
                        emojiStatus.until = until;
                    }
                    status = emojiStatus;
                }
                MessagesController.getInstance(account).updateEmojiStatus(status, gift);
                headerView.updateUserInfo();
                if (popup[0] != null) {
                    statusPopup = null;
                    popup[0].dismiss();
                }
            }
        };
        int until = DialogObject.getEmojiStatusUntil(user.emoji_status);
        if (until > 0) {
            dialog.setExpireDateHint(until);
        }
        long statusId = DialogObject.getEmojiStatusDocumentId(user.emoji_status);
        dialog.setSelected(statusId != 0 ? statusId : null);
        dialog.setSaveState(3);
        popup[0] = statusPopup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(dialog, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() {
                super.dismiss();
                statusPopup = null;
            }
        };
        int[] location = new int[2];
        getLocationOnScreen(location);
        popup[0].showAsDropDown(this, popupX, popupTop - location[1] - dp(16), Gravity.TOP | Gravity.LEFT);
        popup[0].dimBehind();
    }

    /** Telegram's add-account flow: a free slot within the (Premium) limit, or the limit sheet. */
    private void addAccount() {
        int freeAccounts = 0;
        Integer availableAccount = null;
        for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                freeAccounts++;
                if (availableAccount == null) {
                    availableAccount = a;
                }
            }
        }
        if (!UserConfig.hasPremiumOnAccounts()) {
            freeAccounts -= UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT;
        }
        if (freeAccounts > 0 && availableAccount != null) {
            activity.presentFragment(new LoginActivity(availableAccount));
        } else if (!UserConfig.hasPremiumOnAccounts()) {
            BaseFragment fragment = getLastFragment();
            if (fragment != null) {
                fragment.showDialog(new LimitReachedBottomSheet(fragment, getContext(), LimitReachedBottomSheet.TYPE_ACCOUNTS, UserConfig.selectedAccount, null));
            }
        }
    }

    // ---- open / close ----

    public boolean isDrawerOpen() {
        return progress > OPEN_EPSILON || isAnimating || predictiveBackInProgress;
    }

    public void openDrawer(boolean animated) {
        if (progress >= 1f - OPEN_EPSILON && !isAnimating) {
            isOpen = true;
            setProgress(1f);
            return;
        }
        isOpen = true;
        prepareToShow();
        if (animated) {
            animateProgress(1f, false, 0);
        } else {
            setProgress(1f);
        }
    }

    public void closeDrawer(boolean animated) {
        if (progress <= OPEN_EPSILON && !isAnimating) {
            isOpen = false;
            onCloseComplete();
            return;
        }
        isOpen = false;
        if (animated) {
            animateProgress(0f, false, 0);
        } else {
            setProgress(0f);
            onCloseComplete();
        }
    }

    /** Everything the panel needs before it slides in: size, insets, fresh contents, on top of the app. */
    private void prepareToShow() {
        bringToFront();
        updateDrawerWidth();
        updateCornerRadii();
        drawerPanel.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        AndroidUtilities.hideKeyboard(activity.getCurrentFocus());
        updateColors();
        refreshContents();
        super.setVisibility(VISIBLE);
    }

    private void refreshContents() {
        int account = UserConfig.selectedAccount;
        headerView.updateUserInfo();
        accountPickerView.loadAccounts();
        menuView.rebuildMenu(RawDrawerItems.build(this, account), account);
    }

    // ---- predictive back ----

    public boolean startPredictiveBack() {
        if (predictiveBackInProgress || tracking || startedEdgeSwipe || getVisibility() != VISIBLE) {
            return false;
        }
        if (isAnimating) {
            cancelAnimations();
        }
        if (progress <= OPEN_EPSILON) {
            return false;
        }
        predictiveBackInProgress = true;
        predictiveBackStartProgress = progress;
        tapClosePending = false;
        return true;
    }

    /** The menu goes back to half its width at the end of the gesture. */
    public void updatePredictiveBackProgress(float backProgress) {
        if (predictiveBackInProgress) {
            setProgress(predictiveBackStartProgress * (1f - MathUtils.clamp(backProgress, 0f, 1f) * 0.5f));
        }
    }

    public void cancelPredictiveBack() {
        if (!predictiveBackInProgress) {
            return;
        }
        predictiveBackInProgress = false;
        isOpen = predictiveBackStartProgress > OPEN_EPSILON;
        animateProgress(isOpen ? 1f : 0f, true, 0);
    }

    public void commitPredictiveBack() {
        if (!predictiveBackInProgress) {
            closeDrawer(true);
            return;
        }
        predictiveBackInProgress = false;
        isOpen = false;
        if (progress <= OPEN_EPSILON) {
            onCloseComplete();
        } else {
            animateProgress(0f, true, 0);
        }
    }

    // ---- progress ----

    private void setProgress(float value) {
        progress = MathUtils.clamp(value, 0f, 1f);
        syncDrawerState();
        invalidate();
    }

    private void syncDrawerState() {
        if (progress <= OPEN_EPSILON && !isAnimating && !tracking && !startedEdgeSwipe && !predictiveBackInProgress) {
            applyClosedState();
            return;
        }
        drawerPanel.setTranslationX(-drawerWidth * (1f - progress));
        shiftApp(progress <= OPEN_EPSILON || previewShown ? 0 : drawerWidth * progress * APP_SHIFT);
        if (getVisibility() != VISIBLE) {
            super.setVisibility(VISIBLE);
        }
    }

    private void applyClosedState() {
        progress = 0;
        drawerPanel.setTranslationX(-drawerWidth);
        shiftApp(0);
        if (getVisibility() != GONE) {
            super.setVisibility(GONE);
        }
        tapClosePending = false;
    }

    private float getDrawerOffset() {
        return drawerWidth * progress;
    }

    private void setDrawerOffset(float offset) {
        setProgress(drawerWidth != 0 ? MathUtils.clamp(offset, 0f, drawerWidth) / drawerWidth : 0f);
    }

    /** Moves the app (the fragment stack) a little to the right with the menu; not on tablets. */
    private void shiftApp(float translationX) {
        INavigationLayout layout = activity.getActionBarLayout();
        View target = AndroidUtilities.isTablet() || layout == null ? null : layout.getView();
        if (appShiftTarget != null && appShiftTarget != target) {
            appShiftTarget.setTranslationX(0);
        }
        appShiftTarget = target;
        if (target != null) {
            target.setTranslationX(translationX);
        }
    }

    /** A spring (or an instant jump with rawGram animations off) to {@code target}; {@code fast} after a fling or back. */
    private void animateProgress(float target, boolean fast, float velocity) {
        cancelAnimations();
        float targetOffset = drawerWidth * target;
        if (!RawMotion.active()) {
            setDrawerOffset(targetOffset);
            if (target == 0f) {
                onCloseComplete();
            }
            return;
        }
        isAnimating = true;
        SpringAnimation spring = new SpringAnimation(this, DRAWER_OFFSET);
        springAnimation = spring;
        spring.setSpring(new SpringForce(targetOffset)
                .setStiffness(fast ? SPRING_STIFFNESS_FAST : SPRING_STIFFNESS)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        if (velocity != 0) {
            spring.setStartVelocity(velocity);
        }
        spring.addEndListener((animation, canceled, value, endVelocity) -> {
            if (springAnimation == animation) {
                springAnimation = null;
            }
            if (canceled) {
                return;
            }
            isAnimating = false;
            setDrawerOffset(targetOffset);
            if (target == 0f) {
                onCloseComplete();
            }
        });
        spring.animateToFinalPosition(targetOffset);
    }

    private void cancelAnimations() {
        if (springAnimation != null) {
            SpringAnimation spring = springAnimation;
            springAnimation = null;
            spring.cancel();
        }
        isAnimating = false;
        setProgress(progress);
        if (!isOpen && progress <= OPEN_EPSILON && !tracking && !startedEdgeSwipe) {
            onCloseComplete();
        }
    }

    private void onCloseComplete() {
        isOpen = false;
        tracking = false;
        startedEdgeSwipe = false;
        animationInterruptedByTouch = false;
        predictiveBackInProgress = false;
        predictiveBackStartProgress = 0;
        setProgress(0);
        tapClosePending = false;
        dismissStatusPopup();
        menuView.clearMenu();
        if (previewShown && !previewTouch) {
            previewShown = false;
            animate().cancel();
            setAlpha(1f);
        }
    }

    // ---- drawing ----

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (progress > 0) {
            scrimPaint.setColor(Color.argb((int) (MathUtils.clamp(progress, 0f, 1f) * SCRIM_ALPHA), 0, 0, 0));
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        }
        super.dispatchDraw(canvas);
    }

    /** Rounds the outer corners of the panel like the screen's own corners (at least 24dp). */
    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        if (child != drawerPanel) {
            return super.drawChild(canvas, child, drawingTime);
        }
        float top = topRightRadius >= 0 ? topRightRadius : dp(CORNER_RADIUS_DP);
        float bottom = bottomRightRadius >= 0 ? bottomRightRadius : dp(CORNER_RADIUS_DP);
        radii[0] = radii[1] = 0;
        radii[2] = radii[3] = top;
        radii[4] = radii[5] = bottom;
        radii[6] = radii[7] = 0;
        RectF rect = AndroidUtilities.rectTmp;
        rect.set(child.getX(), child.getY(), child.getX() + child.getWidth(), child.getY() + child.getHeight());
        clipPath.rewind();
        clipPath.addRoundRect(rect, radii, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clipPath);
        boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(save);
        return result;
    }

    private void updateCornerRadii() {
        float min = dp(CORNER_RADIUS_DP);
        topRightRadius = bottomRightRadius = min;
        if (Build.VERSION.SDK_INT >= 31) {
            WindowInsets insets = getRootWindowInsets();
            if (insets != null) {
                RoundedCorner topRight = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
                RoundedCorner bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
                if (topRight != null) {
                    topRightRadius = Math.max(min, topRight.getRadius() / 2f);
                }
                if (bottomRight != null) {
                    bottomRightRadius = Math.max(min, bottomRight.getRadius() / 2f);
                }
            }
        }
    }

    private void updateDrawerWidth() {
        drawerWidth = calculateDrawerWidth();
        LayoutParams lp = (LayoutParams) drawerPanel.getLayoutParams();
        if (lp.width != drawerWidth) {
            lp.width = drawerWidth;
            drawerPanel.setLayoutParams(lp);
        }
    }

    private static int calculateDrawerWidth() {
        return Math.min(dp(MAX_WIDTH_DP), AndroidUtilities.displaySize.x - dp(MIN_GAP_DP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateDrawerWidth();
        setProgress(progress);
    }

    // ---- touches on the open menu ----

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (isClosingAnimationInProgress()) {
            return !shouldPassClosingTouchThrough(ev);
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (isAnimating) {
                cancelAnimations();
                animationInterruptedByTouch = true;
            }
            startX = ev.getX();
            startY = ev.getY();
            startProgress = progress;
            tracking = false;
            boolean outside = ev.getX() > panelRight();
            tapClosePending = outside;
            return outside;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - startX;
            if (shouldStartVisibleDrawerTracking(dx, Math.abs(ev.getY() - startY))) {
                beginVisibleDrawerTracking(ev, dx);
                return true;
            }
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (isClosingAnimationInProgress()) {
            return !shouldPassClosingTouchThrough(ev);
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isAnimating) {
                    cancelAnimations();
                    animationInterruptedByTouch = true;
                }
                startX = ev.getX();
                startY = ev.getY();
                startProgress = progress;
                tracking = false;
                tapClosePending = ev.getX() > panelRight();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) {
                    float dx = ev.getX() - startX;
                    if (shouldStartVisibleDrawerTracking(dx, Math.abs(ev.getY() - startY))) {
                        beginVisibleDrawerTracking(ev, dx);
                    }
                }
                if (tracking) {
                    setProgress(startProgress + (ev.getX() - startX) / drawerWidth);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (tracking) {
                    finishTracking();
                } else if (tapClosePending) {
                    tapClosePending = false;
                    closeDrawer(true);
                }
                tapClosePending = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (tracking) {
                    finishTracking();
                }
                tapClosePending = false;
                return true;
        }
        return true;
    }

    /** While an account preview is held: the finger drives it, as exteraGram's DrawerLayoutContainer does. */
    private boolean previewTouch;
    private boolean previewMoved;
    private float previewStartY;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (previewTouch) {
            INavigationLayout layout = activity.getActionBarLayout();
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                if (!previewMoved) {
                    previewMoved = true;
                    previewStartY = ev.getY();
                    MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                } else if (layout != null) {
                    layout.movePreviewFragment(previewStartY - ev.getY());
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                previewTouch = false;
                previewMoved = false;
                if (layout != null) {
                    layout.finishPreviewFragment();
                }
            }
            return true;
        }
        boolean handled = super.dispatchTouchEvent(ev);
        int action = ev.getActionMasked();
        if (animationInterruptedByTouch && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            animationInterruptedByTouch = false;
            settleInterruptedAnimation();
        }
        return handled;
    }

    /** A touch stopped the animation without dragging: finish going where it was going. */
    private void settleInterruptedAnimation() {
        if (isAnimating || tracking || startedEdgeSwipe || predictiveBackInProgress) {
            return;
        }
        float target = isOpen ? 1f : 0f;
        if (Math.abs(progress - target) <= OPEN_EPSILON) {
            setProgress(target);
            if (!isOpen) {
                onCloseComplete();
            }
            return;
        }
        animateProgress(target, true, 0);
    }

    private float panelRight() {
        return drawerPanel.getTranslationX() + drawerWidth;
    }

    private boolean isClosingAnimationInProgress() {
        return isAnimating && !isOpen;
    }

    private boolean shouldPassClosingTouchThrough(MotionEvent ev) {
        return ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN && ev.getX() > panelRight();
    }

    private boolean shouldStartVisibleDrawerTracking(float dx, float absDy) {
        if (dx < 0) {
            return Math.abs(dx) >= absDy && Math.abs(dx) >= closeTouchSlop();
        }
        return startProgress < 1f - OPEN_EPSILON && dx / 3f > absDy && dx >= openTouchSlop();
    }

    private void beginVisibleDrawerTracking(MotionEvent ev, float dx) {
        tracking = true;
        tapClosePending = false;
        if (isAnimating) {
            cancelAnimations();
        }
        offsetTrackingStart(ev, dx);
        resetTrackingVelocity(ev);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    // ---- swipe from the left edge of the chats list (fed by RawSideMenu from LaunchActivity) ----

    /** True while an edge swipe drives the closed menu open; RawSideMenu then sends the touches here. */
    public boolean isEdgeSwipeTracking() {
        return startedEdgeSwipe && tracking;
    }

    /**
     * Watches a gesture of the app; returns true once it becomes a swipe to the right that started at the left
     * edge (or anywhere, see {@code startArea}), from then on the touches go to {@link #handleEdgeSwipeTouch}.
     * {@code startArea} ({@link #SWIPE_NONE} / {@link #SWIPE_EDGE} / {@link #SWIPE_ANYWHERE}) is read on ACTION_DOWN.
     */
    public boolean handleEdgeSwipeIntercept(MotionEvent ev, int startArea) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX = ev.getX();
            startY = ev.getY();
            startProgress = progress;
            tracking = false;
            startedEdgeSwipe = startArea == SWIPE_ANYWHERE || startArea == SWIPE_EDGE && ev.getX() <= dp(EDGE_SWIPE_WIDTH_DP);
            if (startedEdgeSwipe) {
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain();
                }
                velocityTracker.clear();
                velocityTracker.addMovement(ev);
            }
            return false;
        }
        if (!startedEdgeSwipe) {
            return false;
        }
        if (velocityTracker != null) {
            velocityTracker.addMovement(ev);
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - startX;
            float dy = ev.getY() - startY;
            if (shouldBlockClosedDrawerSwipe(dx, dy)) {
                startedEdgeSwipe = false;
                return false;
            }
            if (dx > 0 && dx / 3f > Math.abs(dy) && dx >= openTouchSlop()) {
                beginClosedDrawerTracking(ev, dx);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            startedEdgeSwipe = false;
        }
        return false;
    }

    public void handleEdgeSwipeTouch(MotionEvent ev) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            if (tracking) {
                setProgress(startProgress + (ev.getX() - startX) / drawerWidth);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (tracking) {
                finishTracking();
            }
            startedEdgeSwipe = false;
        }
    }

    private boolean shouldBlockClosedDrawerSwipe(float dx, float dy) {
        float absDy = Math.abs(dy);
        float slop = AndroidUtilities.touchSlop > 0 ? AndroidUtilities.touchSlop : openTouchSlop();
        return absDy >= slop && absDy > Math.abs(dx);
    }

    private void beginClosedDrawerTracking(MotionEvent ev, float dx) {
        tracking = true;
        tapClosePending = false;
        if (isAnimating) {
            cancelAnimations();
        }
        prepareToShow();
        offsetTrackingStart(ev, dx);
        resetTrackingVelocity(ev);
    }

    private void offsetTrackingStart(MotionEvent ev, float dx) {
        startX += Math.signum(dx) * (dx < 0 ? closeTouchSlop() : openTouchSlop());
        startY = ev.getY();
        startProgress = progress;
    }

    private void resetTrackingVelocity(MotionEvent ev) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        } else {
            velocityTracker.clear();
        }
        velocityTracker.addMovement(ev);
    }

    private static float openTouchSlop() {
        return AndroidUtilities.getPixelsInCM(0.2f, true);
    }

    private static float closeTouchSlop() {
        return AndroidUtilities.getPixelsInCM(0.4f, true);
    }

    /** Fling velocity (px/s) that settles the menu regardless of how far it moved. */
    private static int swipeVelocity() {
        return AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y ? 1250 : 850;
    }

    /**
     * Lifting the finger: stays open past a fifth of the width when opening (four fifths when closing) or after a
     * fling to the right, closes after a fling to the left.
     */
    private void finishTracking() {
        float velocityX = 0, velocityY = 0;
        if (velocityTracker != null) {
            velocityTracker.computeCurrentVelocity(1000);
            velocityX = velocityTracker.getXVelocity();
            velocityY = velocityTracker.getYVelocity();
        }
        int flingVelocity = swipeVelocity();
        boolean farEnough = progress >= 1f / (isOpen ? 1.25f : 5f);
        boolean flingRight = velocityX >= flingVelocity && Math.abs(velocityX) >= Math.abs(velocityY);
        boolean flingLeft = velocityX < 0 && Math.abs(velocityX) >= flingVelocity;
        if ((farEnough || flingRight) && !flingLeft) {
            boolean fast = !isOpen && Math.abs(velocityX) >= flingVelocity;
            isOpen = true;
            animateProgress(1f, fast, velocityX);
        } else {
            boolean fast = isOpen && Math.abs(velocityX) >= flingVelocity;
            isOpen = false;
            animateProgress(0f, fast, velocityX);
        }
        recycleVelocityTracker();
        tracking = false;
        startedEdgeSwipe = false;
        tapClosePending = false;
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    /**
     * exteraGram's account preview (long press on another account): that account's main screen as Telegram's peek
     * preview. The finger keeps driving it (see {@link #dispatchTouchEvent}): pulling up opens it, which switches to
     * the account; lifting dismisses it. The menu fades out and the app stops being pushed aside while it is shown,
     * since the preview is drawn by the fragment stack under the menu.
     */
    private void showAccountPreview(int account) {
        INavigationLayout layout = activity.getActionBarLayout();
        if (layout == null || previewShown || !UserConfig.isValidAccount(account) || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        MainTabsActivity preview = new MainTabsActivity() {
            @Override
            public void setInPreviewMode(boolean value) {
                super.setInPreviewMode(value);
                // the tabs are separate fragments: they follow the preview mode (no status bar, no search field)
                DialogsActivity dialogs = getDialogsActivity();
                if (dialogs != null) {
                    dialogs.setInPreviewMode(value);
                }
            }

            @Override
            public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
                super.onTransitionAnimationEnd(isOpen, backward);
                if (!isOpen && backward) {
                    setPreviewHidden(false);
                }
            }

            @Override
            public void onPreviewOpenAnimationEnd() {
                super.onPreviewOpenAnimationEnd();
                setPreviewHidden(false);
                closeDrawer(false);
                if (account != UserConfig.selectedAccount) {
                    activity.switchToAccount(account, true);
                }
            }
        };
        // the preview and its chats list belong to the other account (exteraGram's prepareTabFragment)
        preview.setCurrentAccount(account);
        Bundle args = new Bundle();
        args.putBoolean(ARG_ACCOUNT_PREVIEW, true);
        DialogsActivity dialogs = preview.prepareDialogsActivity(args);
        dialogs.setCurrentAccount(account);
        dialogs.setInPreviewMode(true);
        if (layout.presentFragment(new INavigationLayout.NavigationParams(preview).setPreview(true).setCheckPresentFromDelegate(false))) {
            setPreviewHidden(true);
            previewTouch = true;
            previewMoved = false;
        }
    }

    private boolean previewShown;

    public boolean isAccountPreviewShown() {
        return previewShown;
    }

    /** The menu is above the whole app: fade it out while an account preview is shown. */
    private void setPreviewHidden(boolean hidden) {
        if (previewShown == hidden) {
            return;
        }
        previewShown = hidden;
        animate().cancel();
        if (RawMotion.active()) {
            animate().alpha(hidden ? 0f : 1f).setDuration(150).start();
        } else {
            setAlpha(hidden ? 0f : 1f);
        }
        setProgress(progress);
    }

    private BaseFragment getLastFragment() {
        INavigationLayout layout = activity.getActionBarLayout();
        BaseFragment last = layout != null ? layout.getLastFragment() : null;
        return last instanceof MainTabsActivity ? ((MainTabsActivity) last).getCurrentVisibleFragment() : last;
    }

    private void dismissStatusPopup() {
        if (statusPopup != null) {
            statusPopup.dismiss();
            statusPopup = null;
        }
    }

    // ---- lifecycle and updates ----

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerNotifications();
        Bulletin.addDelegate(bulletinContainer, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return AndroidUtilities.navigationBarHeight;
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Bulletin.removeDelegate(bulletinContainer);
        cancelAnimations();
        onCloseComplete();
        recycleVelocityTracker();
        accountPickerView.dispose();
        shiftApp(0);
        appShiftTarget = null;
        unregisterNotifications();
    }

    private static final int[] ACCOUNT_EVENTS = {
            NotificationCenter.mainUserInfoChanged,
            NotificationCenter.userEmojiStatusUpdated,
            NotificationCenter.currentUserPremiumStatusChanged,
            NotificationCenter.updateInterfaces,
            NotificationCenter.appDidLogout,
            NotificationCenter.attachMenuBotsDidLoad,
            NotificationCenter.didUpdateConnectionState,
    };
    private static final int[] GLOBAL_EVENTS = {
            NotificationCenter.didSetNewTheme,
            NotificationCenter.themeAccentListUpdated,
            NotificationCenter.notificationsCountUpdated,
            NotificationCenter.reloadInterface,
            NotificationCenter.proxySettingsChanged,
            NotificationCenter.proxyCheckDone,
    };

    private void registerNotifications() {
        if (notificationsRegistered) {
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            for (int event : ACCOUNT_EVENTS) {
                NotificationCenter.getInstance(a).addObserver(this, event);
            }
        }
        for (int event : GLOBAL_EVENTS) {
            NotificationCenter.getGlobalInstance().addObserver(this, event);
        }
        notificationsRegistered = true;
    }

    private void unregisterNotifications() {
        if (!notificationsRegistered) {
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            for (int event : ACCOUNT_EVENTS) {
                NotificationCenter.getInstance(a).removeObserver(this, event);
            }
        }
        for (int event : GLOBAL_EVENTS) {
            NotificationCenter.getGlobalInstance().removeObserver(this, event);
        }
        notificationsRegistered = false;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (getVisibility() != VISIBLE) {
            // the closed menu refreshes everything when it opens
            return;
        }
        if (id == NotificationCenter.mainUserInfoChanged || id == NotificationCenter.userEmojiStatusUpdated
                || id == NotificationCenter.currentUserPremiumStatusChanged) {
            refreshAccountViews(account, true);
        } else if (id == NotificationCenter.updateInterfaces) {
            if (args.length > 0 && args[0] instanceof Integer) {
                int mask = (Integer) args[0];
                boolean header = (mask & (MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_NAME
                        | MessagesController.UPDATE_MASK_PHONE | MessagesController.UPDATE_MASK_EMOJI_STATUS)) != 0;
                boolean accounts = (mask & (MessagesController.UPDATE_MASK_AVATAR | MessagesController.UPDATE_MASK_NAME
                        | MessagesController.UPDATE_MASK_EMOJI_STATUS)) != 0;
                if (header || accounts) {
                    refreshAccountViews(account, accounts);
                }
            }
            menuView.updateUnreadCounters(UserConfig.selectedAccount);
        } else if (id == NotificationCenter.didSetNewTheme) {
            updateColors();
        } else if (id == NotificationCenter.themeAccentListUpdated) {
            AndroidUtilities.runOnUIThread(this::updateColors);
        } else if (id == NotificationCenter.notificationsCountUpdated) {
            accountPickerView.updateUnreadCounters();
            menuView.updateUnreadCounters(UserConfig.selectedAccount);
        } else if (id == NotificationCenter.reloadInterface) {
            headerView.updateUserInfo();
            accountPickerView.updateUnreadCounters();
            menuView.updateUnreadCounters(UserConfig.selectedAccount);
            updateColors();
        } else if (id == NotificationCenter.attachMenuBotsDidLoad) {
            if (account == UserConfig.selectedAccount && isOpen) {
                refreshContents();
            }
        } else if (id == NotificationCenter.proxySettingsChanged || id == NotificationCenter.proxyCheckDone
                || id == NotificationCenter.didUpdateConnectionState) {
            headerView.updateProxyStatus();
        } else if (id == NotificationCenter.appDidLogout) {
            refreshAccountViews(account, true);
            if (isOpen) {
                closeDrawer(false);
            }
        }
    }

    private void refreshAccountViews(int account, boolean accounts) {
        if (account == UserConfig.selectedAccount) {
            headerView.updateUserInfo();
        }
        if (accounts) {
            accountPickerView.loadAccounts();
        }
    }

    private void updateColors() {
        drawerPanel.setBackgroundColor(Theme.getColor(COLOR_KEY_BACKGROUND));
        headerView.updateColors();
        accountPickerView.updateColors();
        menuView.updateColors();
        invalidate();
    }
}
