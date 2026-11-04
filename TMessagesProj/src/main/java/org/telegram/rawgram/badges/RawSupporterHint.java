package org.telegram.rawgram.badges;

import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.rawgram.RawBadges;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PeerColorActivity;
import org.telegram.ui.Stories.recorder.HintView2;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * The «Бейдж-стрелка #N» tag above the badge in the profile header, styled like Telegram's collectible-gift tag
 * (which it replaces for numbered supporters). Shown for 6 seconds when the profile opens; tapping it opens the
 * supporter sheet. One per profile screen, kept here so ProfileActivity only needs one-line calls.
 * <p>
 * Ported from exteraGram's {@code SupporterBadgeHint} (exteraGram, GPLv3).
 */
public final class RawSupporterHint {

    private static final long SHOW_DURATION = 6000;
    private static final long FADE_DURATION = 200;
    private static final int HINT_HEIGHT_DP = 24;
    private static final float BRIGHT_BACKGROUND = 0.721f;
    /** Background at full expansion: Telegram's collectible tag uses the same dark veil. */
    private static final int EXPANDED_VEIL = 0x50000000;

    // values must not reference the fragment (the key), or it never leaves the map
    private static final WeakHashMap<BaseFragment, RawSupporterHint> hints = new WeakHashMap<>();

    private final Runnable hideHint = this::hide;
    // weak: the tag sits in the profile's views, which lead back to the fragment
    private WeakReference<HintView2> hintRef;
    private Boolean hintVisible;
    private long userId;
    private int number;
    private boolean customBadge;
    private int backgroundColor;
    private int textColor;
    private int appliedTextColor;
    private boolean held;

    private static RawSupporterHint of(BaseFragment fragment) {
        RawSupporterHint hint = hints.get(fragment);
        if (hint == null) {
            hint = new RawSupporterHint();
            hints.put(fragment, hint);
        }
        return hint;
    }

    static RawSupporterHint peek(BaseFragment fragment) {
        return hints.get(fragment);
    }

    /** A numbered supporter: the profile shows this tag instead of the collectible-gift one. */
    public static boolean hasNumber(TLRPC.User user) {
        return user != null && RawBadges.get(user) != null && RawSupporters.getNumber(user) > 0;
    }

    /** ProfileActivity.updateProfileData (users): creates, updates or removes the tag in {@code container}. */
    public static void set(BaseFragment fragment, FrameLayout container, int account, TLRPC.User user) {
        if (fragment == null) {
            return;
        }
        if (!hasNumber(user) && hints.get(fragment) == null) {
            return;
        }
        final WeakReference<BaseFragment> fragmentRef = new WeakReference<>(fragment);
        Runnable onClick = null;
        if (user != null) {
            onClick = () -> {
                BaseFragment f = fragmentRef.get();
                if (f != null) {
                    RawSupporters.openProfileBadge(f, user, f.getResourceProvider(), null);
                }
            };
        }
        of(fragment).set(container, account, user, onClick);
    }

    /** ProfileActivity.updateCollectibleHint: follows the badge in the name as the header expands. */
    public static void update(BaseFragment fragment, SimpleTextView name, float expanded, boolean visible) {
        RawSupporterHint hint = hints.get(fragment);
        if (hint != null) {
            hint.update(name, RawBadges.profileBadgeDrawable(name), expanded, visible);
        }
    }

    /** ProfileActivity.onTransitionAnimationStart, closing: hides the tag with the header. */
    public static void hide(BaseFragment fragment) {
        RawSupporterHint hint = hints.get(fragment);
        if (hint != null) {
            hint.hide();
        }
    }

    private HintView2 hint() {
        return hintRef == null ? null : hintRef.get();
    }

    private void set(FrameLayout container, int account, TLRPC.User user, Runnable onClick) {
        long id = user != null ? user.id : 0;
        int newNumber = hasNumber(user) ? RawSupporters.getNumber(user) : 0;
        boolean custom = RawSupporters.getCustomBadgeId(user) != 0;
        int background = resolveBackgroundColor(account, user);
        if (id == userId && custom == customBadge && background == backgroundColor && newNumber == number) {
            return;
        }
        userId = id;
        number = newNumber;
        customBadge = custom;
        backgroundColor = background;
        final HintView2 old = hint();
        if (old != null) {
            old.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(old));
            old.hide();
            hintRef = null;
        }
        if (container == null || newNumber <= 0) {
            return;
        }
        textColor = resolveTextColor(backgroundColor);
        hintVisible = null;
        HintView2 hint = new HintView2(container.getContext(), HintView2.DIRECTION_BOTTOM);
        hintRef = new WeakReference<>(hint);
        hint.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), AndroidUtilities.dp(2));
        hint.setFlicker(0.66f, Theme.multAlpha(textColor, 0.5f));
        container.addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, HINT_HEIGHT_DP));
        hint.setTextSize(9.33f);
        hint.setTextTypeface(AndroidUtilities.bold());
        appliedTextColor = textColor;
        hint.setTextColor(textColor);
        hint.setText(RawBadgeText.format(customBadge ? R.string.RawSupporterBadgeNameCustom : R.string.RawSupporterBadgeName,
                RawSupporters.formatNumber(newNumber)));
        hint.setDuration(-1);
        hint.setInnerPadding(5.66f, 2.66f, 5.66f, 2.66f);
        hint.setArrowSize(4, 2.66f);
        hint.setRoundingWithCornerEffect(false);
        hint.setRounding(16);
        hint.setBgColor(backgroundColor);
        if (onClick != null) {
            hint.setOnClickListener(v -> onClick.run());
        }
        if (!held) {
            showHint(hint);
        }
    }

    private void showHint(HintView2 view) {
        view.show();
        AndroidUtilities.cancelRunOnUIThread(hideHint);
        AndroidUtilities.runOnUIThread(hideHint, SHOW_DURATION);
    }

    /** Keeps the tag hidden while the badge flies into the profile. */
    void hold() {
        held = true;
        AndroidUtilities.cancelRunOnUIThread(hideHint);
        HintView2 hint = hint();
        if (hint != null) {
            hint.hide(false);
        }
    }

    void release() {
        if (held) {
            held = false;
            HintView2 hint = hint();
            if (hint != null) {
                showHint(hint);
            }
        }
    }

    private void update(SimpleTextView name, Drawable badge, float expanded, boolean visible) {
        HintView2 hint = hint();
        if (hint == null || name == null || badge == null) {
            return;
        }
        int x;
        if (name.getRightDrawable() == badge) {
            x = name.getRightDrawableX();
        } else if (name.getRightDrawable2() == badge) {
            x = badge.getBounds().centerX();
        } else {
            x = 0;
            visible = false;
        }
        visible &= x >= 0 && x <= name.getWidth();
        hint.setJointPx(0, -hint.getPaddingLeft() + name.getX() + x * name.getScaleX());
        hint.setTranslationY(-hint.getPaddingBottom() + name.getY() - AndroidUtilities.dp(24) + AndroidUtilities.lerp(AndroidUtilities.dp(6), -AndroidUtilities.dp(12), expanded));
        hint.setBgColor(ColorUtils.blendARGB(backgroundColor, EXPANDED_VEIL, expanded));
        int color = ColorUtils.blendARGB(textColor, 0xFFFFFFFF, expanded);
        if (appliedTextColor != color) {
            appliedTextColor = color;
            hint.setTextColor(color);
            hint.invalidate();
        }
        if (hintVisible == null || hintVisible != visible) {
            hintVisible = visible;
            hint.animate().alpha(visible ? 1f : 0f).setInterpolator(CubicBezierInterpolator.EASE_OUT).setDuration(FADE_DURATION).start();
        }
    }

    private void hide() {
        HintView2 hint = hint();
        if (hint != null) {
            hint.hide(true);
        }
    }

    public static int resolveTextColor(int background) {
        return AndroidUtilities.computePerceivedBrightness(background) > BRIGHT_BACKGROUND ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** The tag colour: from the collectible status, else the profile colour, else the action bar. */
    public static int resolveBackgroundColor(int account, TLRPC.User user) {
        if (user != null && user.emoji_status instanceof TLRPC.TL_emojiStatusCollectible) {
            TLRPC.TL_emojiStatusCollectible status = (TLRPC.TL_emojiStatusCollectible) user.emoji_status;
            return Theme.blendOver(status.center_color | 0xFF000000, Theme.multAlpha(status.pattern_color | 0xFF000000, 0.5f));
        }
        MessagesController.PeerColors colors = MessagesController.getInstance(account).profilePeerColors;
        MessagesController.PeerColor color = colors == null ? null : colors.getColor(UserObject.getProfileColorId(user));
        if (color != null) {
            int background = color.getBgColor1(Theme.isCurrentThemeDark());
            int pattern = color.patternColor;
            if (pattern == 0) {
                pattern = PeerColorActivity.adaptProfileEmojiColor(background);
            }
            return Theme.blendOver(background, Theme.multAlpha(pattern | 0xFF000000, 0.5f));
        }
        return Theme.blendOver(Theme.getColor(Theme.key_actionBarDefault), Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultTitle), 0.15f));
    }
}
