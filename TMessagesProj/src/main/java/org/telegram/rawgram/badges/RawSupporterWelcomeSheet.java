package org.telegram.rawgram.badges;

import android.app.Activity;
import android.os.Build;
import android.view.HapticFeedbackConstants;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FireworksOverlay;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ProfileActivity;

/**
 * The badge owner's sheet: the arrow with your number engraved, «Твои бонусы» / «Бейдж #N — вот что ты получаешь:»,
 * the perks, and «Передать другому аккаунту». {@link #MODE_WELCOME} is the one-time thank-you shown when the badge
 * arrives: the number rolls in under the arrow and fireworks go off.
 * <p>
 * Ported from exteraGram's {@code SupporterWelcomeSheet} (exteraGram, GPLv3). The «Канал ранних сборок» button and
 * row lead to a link exteraGram takes from its remote config; rawGram has no such link, so they stay hidden, as in
 * exteraGram when the link is empty.
 */
public class RawSupporterWelcomeSheet extends RawSupporterSheet {

    public static final int MODE_WELCOME = 0;
    public static final int MODE_PERKS = 1;

    private static final long NUMBER_DELAY = 250;
    private static final long FIREWORKS_DELAY = 200;
    /** The arrow's content size relative to its view, for the badge's flight into the profile. */
    private static final float LANDING_CONTENT_SCALE = 0.8f;

    private RawSupporterNumberView numberView;
    private FireworksOverlay fireworksOverlay;
    private final Runnable startNumber = this::startNumber;
    private final Runnable startFireworks = this::startFireworks;

    public RawSupporterWelcomeSheet(BaseFragment fragment, int mode, Theme.ResourcesProvider resourcesProvider) {
        super(fragment, resourcesProvider);
        Activity activity = fragment.getParentActivity();
        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        int number = RawSupporters.getNumber(selfId);
        boolean developer = RawSupporters.isDeveloper(selfId);
        boolean rollNumber = mode == MODE_WELCOME && number > 0;
        if (number > 0) {
            getArrowView().setNumber(RawSupporters.formatNumber(number));
        }
        String title = RawBadgeText.get(mode != MODE_WELCOME || developer ? R.string.RawSupporterPerksTitle : R.string.RawSupporterWelcomeTitle);
        CharSequence subtitle = number > 0 && !rollNumber
                ? AndroidUtilities.replaceTags(RawBadgeText.format(R.string.RawSupporterWelcomeInfo, RawSupporters.formatNumber(number)))
                : RawBadgeText.get(R.string.RawSupporterWelcomeInfoNoNumber);
        setContent(title, subtitle);
        if (rollNumber) {
            numberView = new RawSupporterNumberView(activity);
            numberView.setNumber(RawSupporters.formatNumber(number));
            numberView.setColors(getArrowView().color1, getArrowView().color2);
            addHeaderAccessory(numberView);
        }

        RawSupporterPerks.addTo(activity, this::addView, resourcesProvider,
                () -> openOwnProfile(fragment, selfId, mode),
                org.telegram.rawgram.nowplaying.NowPlayingController.isEnabled() ? nowPlayingAction(fragment) : null,
                null);

        button.setText(LocaleController.getString(R.string.GotIt), false);
        button.setOnClickListener(v -> dismiss());

        if (mode == MODE_PERKS && RawSupporterTransfer.canTransfer(currentAccount)) {
            addSecondaryButton(RawBadgeText.get(R.string.RawSupporterTransfer),
                    v -> RawSupporterTransfer.start(fragment, container, v, resourcesProvider, this::dismiss));
        }
        if (mode == MODE_WELCOME) {
            fireworksOverlay = new FireworksOverlay(activity);
            container.addView(fireworksOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        commit();
    }

    /** «Номерной бейдж»: to your profile, with the arrow flying into the name on the first welcome. */
    private void openOwnProfile(BaseFragment fragment, long selfId, int mode) {
        if (fragment instanceof ProfileActivity && ((ProfileActivity) fragment).getDialogId() == selfId) {
            dismiss();
            return;
        }
        if (mode == MODE_WELCOME && !getArrowView().hasBadge() && RawSupporterLanding.prepare(selfId, getArrowView().getIconView(), LANDING_CONTENT_SCALE)) {
            getArrowView().setIconHidden(true);
        }
        dismiss();
        fragment.presentFragment(ProfileActivity.of(selfId));
    }

    /** «Сейчас играет» opens the Now Playing setup. */
    private Runnable nowPlayingAction(BaseFragment fragment) {
        return () -> {
            dismiss();
            fragment.presentFragment(new org.telegram.rawgram.nowplaying.SetupNowPlayingActivity());
        };
    }

    @Override
    public void show() {
        super.show();
        if (numberView != null) {
            if (!getArrowView().hasBadge()) {
                getArrowView().iconView.startEnterAnimation(-360, NUMBER_DELAY);
            }
            AndroidUtilities.runOnUIThread(startNumber, NUMBER_DELAY);
        } else if (fireworksOverlay != null) {
            AndroidUtilities.runOnUIThread(startFireworks, FIREWORKS_DELAY);
        }
    }

    private void startNumber() {
        if (isDismissed()) {
            return;
        }
        numberView.start(() -> {
            try {
                container.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
            } catch (Exception ignore) {
            }
            fireworksOverlay.start(true);
        });
    }

    private void startFireworks() {
        if (isDismissed()) {
            return;
        }
        try {
            container.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignore) {
        }
        fireworksOverlay.start(true);
    }

    @Override
    public void dismiss() {
        AndroidUtilities.cancelRunOnUIThread(startNumber);
        AndroidUtilities.cancelRunOnUIThread(startFireworks);
        super.dismiss();
    }

    public static RawSupporterWelcomeSheet show(BaseFragment fragment, int mode) {
        return show(fragment, mode, fragment == null ? null : fragment.getResourceProvider(), null, 0);
    }

    public static RawSupporterWelcomeSheet show(BaseFragment fragment, int mode, Theme.ResourcesProvider resourcesProvider, RawSupporterEnterFrom enterFrom, long badgeId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        RawSupporterWelcomeSheet sheet = new RawSupporterWelcomeSheet(fragment, mode, resourcesProvider);
        sheet.setEnterFrom(enterFrom);
        if (badgeId != 0) {
            sheet.setBadge(badgeId);
        }
        return fragment.showDialog(sheet) != null ? sheet : null;
    }
}
