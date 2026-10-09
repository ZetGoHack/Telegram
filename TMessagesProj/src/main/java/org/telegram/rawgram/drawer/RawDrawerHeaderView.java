package org.telegram.rawgram.drawer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.RawUi;
import org.telegram.rawgram.RawUiConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumGradient;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.DialogsActivity;

/**
 * The top of the side menu: a large avatar (opens the own profile), the day / night switch with the sun animation
 * (long press opens the theme settings), a proxy pill with the ping while a proxy is set up, the name with the
 * emoji status (tap to change it, Premium) and the username or phone under it; the name line expands the account
 * list. Ported from exteraGram's drawer (exteraSquad, GPL).
 */
final class RawDrawerHeaderView extends FrameLayout {

    static final int HEIGHT_DP = 160;
    private static final int AVATAR_DP = 72;

    private static final int COLOR_KEY_TEXT = Theme.key_windowBackgroundWhiteBlackText;
    private static final int COLOR_KEY_SUBTITLE = Theme.key_windowBackgroundWhiteGrayText2;
    private static final int COLOR_KEY_ICON = Theme.key_windowBackgroundWhiteGrayIcon;
    private static final int COLOR_KEY_STATUS = Theme.key_profile_verifiedBackground;
    private static final int COLOR_KEY_PROXY_ON = Theme.key_windowBackgroundWhiteGreenText;

    private static final int PROXY_HIDDEN = 0, PROXY_IDLE = 1, PROXY_WITH_PING = 2;

    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final BackupImageView avatarView;
    private final FrameLayout themeToggleBackground;
    private final RLottieImageView themeToggleView;
    private final RLottieDrawable sunDrawable;
    private final FrameLayout proxyButton;
    private final ImageView proxyIcon;
    private final AnimatedTextView proxyTextView;
    private final SimpleTextView nameView;
    private final SimpleTextView subtitleView;
    private final ImageView chevronView;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusDrawable;
    /** exteraGram badge after the status (RawBadges), in the name's second right slot. */
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable badgeDrawable;

    private Runnable onChevronClick, onThemeToggle, onThemeToggleLongClick, onProfileClick, onStatusClick, onProxyClick;
    private boolean chevronExpanded;
    private int lastProxyState = -1;
    private int lastProxyColor = -1;

    RawDrawerHeaderView(Context context) {
        super(context);

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(avatarRadius());
        avatarView.setOnClickListener(v -> run(onProfileClick));
        addView(avatarView, LayoutHelper.createFrame(AVATAR_DP, AVATAR_DP, Gravity.LEFT | Gravity.TOP, 16, 16, 0, 0));

        themeToggleBackground = new FrameLayout(context);
        ScaleStateListAnimator.apply(themeToggleBackground);
        themeToggleBackground.setBackground(Theme.createRoundRectDrawable(dp(18), pillColor(COLOR_KEY_ICON)));
        addView(themeToggleBackground, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.TOP, 0, 16, 16, 0));

        sunDrawable = new RLottieDrawable(R.raw.sun, dp(24), dp(24), true, null);
        sunDrawable.setPlayInDirectionOfCustomEndFrame(true);
        themeToggleView = new RLottieImageView(context);
        themeToggleView.setAnimation(sunDrawable);
        themeToggleView.setScaleType(ImageView.ScaleType.CENTER);
        themeToggleBackground.addView(themeToggleView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        setThemeToggleStaticState(Theme.isCurrentThemeDark());
        themeToggleBackground.setOnClickListener(v -> {
            themeToggleBackground.setPressed(false);
            themeToggleBackground.setScaleX(1f);
            themeToggleBackground.setScaleY(1f);
            run(onThemeToggle);
        });
        themeToggleBackground.setOnLongClickListener(v -> {
            if (onThemeToggleLongClick == null) {
                return false;
            }
            onThemeToggleLongClick.run();
            return true;
        });
        updateThemeToggleColors();

        proxyButton = new FrameLayout(context);
        ScaleStateListAnimator.apply(proxyButton);
        proxyButton.setBackground(Theme.createRoundRectDrawable(dp(18), pillColor(COLOR_KEY_ICON)));
        proxyButton.setOnClickListener(v -> run(onProxyClick));
        addView(proxyButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.RIGHT | Gravity.TOP, 0, 16, 60, 0));
        LinearLayout proxyContent = new LinearLayout(context);
        proxyContent.setOrientation(LinearLayout.HORIZONTAL);
        proxyContent.setGravity(Gravity.CENTER);
        proxyContent.setPadding(dp(6), 0, dp(6), 0);
        proxyButton.addView(proxyContent, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        proxyIcon = new ImageView(context);
        proxyIcon.setScaleType(ImageView.ScaleType.CENTER);
        proxyContent.addView(proxyIcon, LayoutHelper.createLinear(24, 24));
        proxyTextView = new AnimatedTextView(context, true, true, true);
        proxyTextView.setTextSize(dp(13));
        proxyTextView.adaptWidth = true;
        proxyTextView.setTypeface(AndroidUtilities.bold());
        proxyTextView.setTextColor(Theme.getColor(COLOR_KEY_ICON));
        proxyTextView.setPadding(dp(2), 0, dp(4), 0);
        proxyTextView.setVisibility(GONE);
        proxyContent.addView(proxyTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        FrameLayout nameBlock = new FrameLayout(context);
        nameBlock.setOnClickListener(v -> run(onChevronClick));
        addView(nameBlock, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.TOP, 0, 100, 0, 0));

        nameView = new SimpleTextView(context);
        nameView.setTextSize(15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
        nameView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        nameView.setEllipsizeByGradient(true);
        nameView.setCanHideRightDrawable(false);
        nameView.setRightDrawableOutside(true);
        nameView.setClickable(false);
        nameBlock.addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 24, Gravity.LEFT | Gravity.TOP, 16, 0, 64, 0));
        statusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(nameView, dp(22));
        nameView.setRightDrawable(statusDrawable);
        nameView.setRightDrawableOnClick(v -> run(onStatusClick));
        badgeDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(nameView, dp(20));

        subtitleView = new SimpleTextView(context);
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(Theme.getColor(COLOR_KEY_SUBTITLE));
        subtitleView.setMaxLines(1);
        subtitleView.setClickable(false);
        nameBlock.addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 16, 26, 64, 0));

        chevronView = new ImageView(context);
        chevronView.setImageResource(R.drawable.msg_expand);
        chevronView.setScaleType(ImageView.ScaleType.CENTER);
        chevronView.setColorFilter(colorFilter(COLOR_KEY_SUBTITLE));
        nameBlock.addView(chevronView, LayoutHelper.createFrame(24, 24, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 22, 0));
    }

    void setOnChevronClick(Runnable r) {
        onChevronClick = r;
    }

    void setOnThemeToggle(Runnable r) {
        onThemeToggle = r;
    }

    void setOnThemeToggleLongClick(Runnable r) {
        onThemeToggleLongClick = r;
    }

    void setOnProfileClick(Runnable r) {
        onProfileClick = r;
    }

    void setOnStatusClick(Runnable r) {
        onStatusClick = r;
    }

    void setOnProxyClick(Runnable r) {
        onProxyClick = r;
    }

    SimpleTextView getNameView() {
        return nameView;
    }

    RLottieImageView getThemeToggleView() {
        return themeToggleView;
    }

    /** Centre of the day / night switch in window coordinates (where the theme reveal starts). */
    int[] getThemeTogglePosition() {
        int[] pos = new int[2];
        themeToggleBackground.getLocationInWindow(pos);
        pos[0] += themeToggleBackground.getMeasuredWidth() / 2;
        pos[1] += themeToggleBackground.getMeasuredHeight() / 2;
        return pos;
    }

    void updateUserInfo() {
        int account = UserConfig.selectedAccount;
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null) {
            return;
        }
        avatarDrawable.setInfo(account, user);
        avatarView.setRoundRadius(avatarRadius());
        avatarView.getImageReceiver().setCurrentAccount(account);
        avatarView.setForUserOrChat(user, avatarDrawable);
        nameView.setText(ContactsController.formatName(user.first_name, user.last_name));
        statusDrawable.setCurrentAccount(account);

        String username = DialogObject.getPublicUsername(user);
        if (!TextUtils.isEmpty(username)) {
            subtitleView.setText("@" + username);
        } else if (!TextUtils.isEmpty(user.phone)) {
            subtitleView.setText(RawUiConfig.isHidePhone() ? "Номер скрыт" : PhoneFormat.getInstance().format("+" + user.phone));
        } else {
            subtitleView.setText("Номер неизвестен");
        }

        long statusId = DialogObject.getEmojiStatusDocumentId(user.emoji_status);
        boolean premium = MessagesController.getInstance(account).isPremiumUser(user);
        if (statusId != 0) {
            statusDrawable.set(statusId, true);
        } else if (premium) {
            statusDrawable.set(PremiumGradient.getInstance().premiumStarDrawableMini, true);
        } else {
            statusDrawable.set((android.graphics.drawable.Drawable) null, true);
        }
        statusDrawable.setParticles(DialogObject.isEmojiStatusCollectible(user.emoji_status), true);
        statusDrawable.setColor(Theme.getColor(COLOR_KEY_STATUS));
        nameView.setRightDrawable(statusId != 0 || premium ? statusDrawable : null);
        org.telegram.rawgram.RawBadges.Badge badge = org.telegram.rawgram.RawBadges.get(user);
        if (badge != null && badge.documentId != 0) {
            badgeDrawable.setCurrentAccount(account);
            badgeDrawable.set(badge.documentId, true);
            badgeDrawable.setColor(Theme.getColor(COLOR_KEY_STATUS));
            nameView.setRightDrawable2(badgeDrawable);
        } else {
            nameView.setRightDrawable2(null);
        }
        updateProxyStatus();
    }

    /** The proxy pill: hidden without saved proxies, green with the ping while connected through one. */
    void updateProxyStatus() {
        boolean proxyEnabled = SharedConfig.isProxyEnabled();
        int connectionState = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        boolean connected = connectionState == ConnectionsManager.ConnectionStateConnected
                || connectionState == ConnectionsManager.ConnectionStateUpdating;
        boolean active = proxyEnabled && connected && SharedConfig.currentProxy != null;
        long ping = 0;
        int state;
        if (active) {
            ping = Utilities.clamp(SharedConfig.currentProxy.ping, 9999L, 0L);
            state = ping > 0 ? PROXY_WITH_PING : PROXY_IDLE;
        } else {
            state = SharedConfig.proxyList.isEmpty() ? PROXY_HIDDEN : PROXY_IDLE;
        }
        if (state != lastProxyState) {
            if (lastProxyState != -1 && isAttachedToWindow()) {
                TransitionManager.beginDelayedTransition(this, new ChangeBounds().setDuration(150));
            }
            lastProxyState = state;
        }
        if (state == PROXY_HIDDEN) {
            proxyButton.setVisibility(GONE);
            return;
        }
        proxyButton.setVisibility(VISIBLE);
        if (state == PROXY_WITH_PING) {
            proxyTextView.setVisibility(VISIBLE);
            proxyTextView.setText(ping + " мс", true);
        } else {
            proxyTextView.setVisibility(GONE);
        }
        int color = Theme.getColor(active ? COLOR_KEY_PROXY_ON : COLOR_KEY_ICON);
        if (color != lastProxyColor) {
            lastProxyColor = color;
            proxyButton.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.multAlpha(color, 0.075f)));
            proxyIcon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            proxyTextView.setTextColor(color);
        }
        proxyIcon.setImageResource(active ? R.drawable.outline_shield_check : R.drawable.outline_shield_plain_24);
    }

    void setChevronExpanded(boolean expanded) {
        if (chevronExpanded == expanded) {
            return;
        }
        chevronExpanded = expanded;
        chevronView.animate().cancel();
        chevronView.animate().rotation(expanded ? 180 : 0).setDuration(250).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
    }

    /** Plays the sun / moon animation towards {@code toDark}. */
    void animateThemeToggle(boolean toDark) {
        if (sunDrawable.getFramesCount() <= 0) {
            return;
        }
        sunDrawable.setCustomEndFrame(endFrame(toDark));
        themeToggleView.playAnimation();
    }

    void updateColors() {
        nameView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
        subtitleView.setTextColor(Theme.getColor(COLOR_KEY_SUBTITLE));
        chevronView.setColorFilter(colorFilter(COLOR_KEY_SUBTITLE));
        themeToggleBackground.setBackground(Theme.createRoundRectDrawable(dp(18), pillColor(COLOR_KEY_ICON)));
        lastProxyState = -1;
        lastProxyColor = -1;
        updateUserInfo();
        if (!themeToggleView.isPlaying() && !DialogsActivity.switchingTheme) {
            syncThemeToggle();
        }
        if (!DialogsActivity.switchingTheme || Theme.isCurrentThemeDark()) {
            updateThemeToggleColors();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        statusDrawable.attach();
        if (!themeToggleView.isPlaying() && !DialogsActivity.switchingTheme) {
            syncThemeToggle();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        statusDrawable.detach();
    }

    private void updateThemeToggleColors() {
        int color = Theme.getColor(COLOR_KEY_ICON);
        sunDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        sunDrawable.beginApplyLayerColors();
        sunDrawable.setLayerColor("Sunny.**", color);
        sunDrawable.setLayerColor("Path 6.**", color);
        sunDrawable.setLayerColor("Path.**", color);
        sunDrawable.setLayerColor("Path 5.**", color);
        sunDrawable.commitApplyLayerColors();
        themeToggleView.setColorFilter(colorFilter(COLOR_KEY_ICON));
        themeToggleView.invalidate();
    }

    /** Puts the switch on the frame of the current theme without animating. */
    private void syncThemeToggle() {
        if (sunDrawable.getFramesCount() <= 0) {
            return;
        }
        boolean dark = Theme.isCurrentThemeDark();
        if (!isAttachedToWindow()) {
            setThemeToggleStaticState(dark);
            return;
        }
        int frame = currentFrame(dark);
        sunDrawable.setCurrentFrame(frame, false, true);
        sunDrawable.setCustomEndFrame(frame);
        themeToggleView.invalidate();
    }

    private void setThemeToggleStaticState(boolean dark) {
        sunDrawable.setCurrentFrame(currentFrame(dark));
        sunDrawable.setCustomEndFrame(endFrame(dark));
        themeToggleView.invalidate();
    }

    private int currentFrame(boolean dark) {
        return dark ? Math.max(0, sunDrawable.getFramesCount() - 1) : 0;
    }

    private int endFrame(boolean dark) {
        return dark ? sunDrawable.getFramesCount() : 0;
    }

    private static int avatarRadius() {
        return RawUi.avatarR(dp(AVATAR_DP / 2f));
    }

    private static int pillColor(int colorKey) {
        return Theme.multAlpha(Theme.getColor(colorKey), 0.075f);
    }

    private static PorterDuffColorFilter colorFilter(int colorKey) {
        return new PorterDuffColorFilter(Theme.getColor(colorKey), PorterDuff.Mode.SRC_IN);
    }

    private static void run(Runnable r) {
        if (r != null) {
            r.run();
        }
    }
}
