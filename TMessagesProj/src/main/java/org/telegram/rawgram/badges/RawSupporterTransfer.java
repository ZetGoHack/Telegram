package org.telegram.rawgram.badges;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.rawgram.RawBadgeEditor;
import org.telegram.rawgram.RawBadges;
import org.telegram.rawgram.RawNotify;
import org.telegram.rawgram.RawUi;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

/**
 * Moving the supporter badge to another account: a menu of your other logged-in accounts without a badge (plus
 * «Другой аккаунт…», which opens exteraGram's bot), a confirmation, then two requests to the bot
 * ({@code transfer_init} → a token, {@code transfer_claim <token>} from the receiving account).
 * <p>
 * Ported from exteraGram's {@code SupporterTransfer} and the account menu of its {@code AccountsUiHelper}
 * (exteraGram, GPLv3).
 */
public final class RawSupporterTransfer {

    private static final String BOT_TRANSFER_URL = "https://t.me/exteraAuthBot?start=transfer";
    private static final String TOKEN_PREFIX = "token:";
    private static final String COOLDOWN_PREFIX = "cooldown:";
    private static final String RESULT_OK = "ok";
    private static final String RESULT_TARGET_NOT_ELIGIBLE = "target_not_eligible";
    private static final long PROGRESS_DELAY = 300;
    private static final int ROW_WIDTH_DP = 230;
    private static final int ROW_HEIGHT_DP = 48;
    private static final int AVATAR_FRAME_DP = 34;
    private static final int AVATAR_DP = 32;

    private RawSupporterTransfer() {
    }

    /** A supporter (not a developer) can give the badge away. */
    public static boolean canTransfer(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user != null && RawSupporters.isSupporter(user.id) && !RawSupporters.isDeveloper(user.id);
    }

    /** The account menu over {@code anchor}; {@code dismiss} closes the sheet before the next step. */
    public static void start(BaseFragment fragment, ViewGroup container, View anchor, Theme.ResourcesProvider resourcesProvider, Runnable dismiss) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        int currentAccount = fragment.getCurrentAccount();
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (a != currentAccount && config.isClientActivated() && config.getCurrentUser() != null && !RawSupporters.isSupporter(config.getClientUserId())) {
                accounts.add(a);
            }
        }
        Collections.sort(accounts, (x, y) -> Integer.compare(UserConfig.getInstance(x).loginTime, UserConfig.getInstance(y).loginTime));
        // the menu grows up from the button, so the oldest account ends up next to it
        Collections.reverse(accounts);

        ItemOptions options = ItemOptions.makeOptions(container, resourcesProvider, anchor, false, true);
        options.add(R.drawable.msg_link2, RawBadgeText.get(R.string.RawSupporterTransferOtherAccount), () -> {
            dismiss.run();
            Browser.openUrl(activity, BOT_TRANSFER_URL);
        });
        if (!accounts.isEmpty()) {
            options.addGap();
            for (int i = 0; i < accounts.size(); i++) {
                final int account = accounts.get(i);
                LinearLayout row = accountRow(activity, resourcesProvider, account);
                row.setOnClickListener(v -> {
                    options.dismiss();
                    dismiss.run();
                    confirm(fragment, currentAccount, account, resourcesProvider);
                });
                options.addView(row, LayoutHelper.createLinear(ROW_WIDTH_DP, ROW_HEIGHT_DP));
            }
        }
        options.setBlur(true).translate(0, -AndroidUtilities.dp(4));
        options.setGravity(Gravity.CENTER_HORIZONTAL);
        options.show();
    }

    private static LinearLayout accountRow(Context context, Theme.ResourcesProvider resourcesProvider, int account) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 0, 0));
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(user);

        FrameLayout avatarFrame = new FrameLayout(context);
        row.addView(avatarFrame, LayoutHelper.createLinear(AVATAR_FRAME_DP, AVATAR_FRAME_DP, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(RawUi.avatarR(AndroidUtilities.dp(AVATAR_DP / 2f)));
        avatar.getImageReceiver().setCurrentAccount(account);
        avatar.setForUserOrChat(user, avatarDrawable);
        avatarFrame.addView(avatar, LayoutHelper.createFrame(AVATAR_DP, AVATAR_DP, Gravity.CENTER));

        TextView name = new TextView(context);
        NotificationCenter.listenEmojiLoading(name);
        name.setTextSize(16);
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        name.setText(Emoji.replaceEmoji(UserObject.getUserName(user), name.getPaint().getFontMetricsInt(), false));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 13, 0, 14, 0));
        return row;
    }

    private static void confirm(BaseFragment fragment, int fromAccount, int toAccount, Theme.ResourcesProvider resourcesProvider) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        TLRPC.User target = UserConfig.getInstance(toAccount).getCurrentUser();
        int number = RawSupporters.getNumber(UserConfig.getInstance(fromAccount).getClientUserId());
        String numberText = number > 0 ? RawSupporters.formatNumber(number) : "";
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider);
        builder.setTitle(RawBadgeText.get(R.string.RawSupporterTransferTitle));
        builder.setMessage(AndroidUtilities.replaceTags(RawBadgeText.format(R.string.RawSupporterTransferConfirm, numberText, UserObject.getUserName(target))));
        builder.setPositiveButton(LocaleController.getString(R.string.Gift2TransferDo), (dialog, which) -> transfer(fragment, fromAccount, toAccount, resourcesProvider));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        }
    }

    private static void transfer(BaseFragment fragment, int fromAccount, int toAccount, Theme.ResourcesProvider resourcesProvider) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, resourcesProvider);
        progress.setCanCancel(false);
        progress.showDelayed(PROGRESS_DELAY);
        RawBadgeEditor.sendBotRequest(fromAccount, "transfer_init", init -> {
            if (init == null || !init.startsWith(TOKEN_PREFIX)) {
                progress.dismiss();
                showResult(fragment, init);
                return;
            }
            RawBadgeEditor.sendBotRequest(toAccount, "transfer_claim " + init.substring(TOKEN_PREFIX.length()), claim -> {
                progress.dismiss();
                if (!RESULT_OK.equals(claim)) {
                    showResult(fragment, claim);
                    return;
                }
                RawBadges.requestSync();
                if (fragment.isFinished) {
                    return;
                }
                RawNotify.show(R.drawable.msg_contacts, AndroidUtilities.replaceTags(RawBadgeText.format(R.string.RawSupporterTransferDone,
                        UserObject.getUserName(UserConfig.getInstance(toAccount).getCurrentUser()))));
            });
        });
    }

    private static void showResult(BaseFragment fragment, String result) {
        if (fragment.isFinished) {
            return;
        }
        long cooldown = result != null && result.startsWith(COOLDOWN_PREFIX) ? Utilities.parseLong(result.substring(COOLDOWN_PREFIX.length())) : 0;
        String text;
        if (cooldown > 0) {
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM, LocaleController.getInstance().getCurrentLocale()).format(new Date(cooldown * 1000));
            text = RawBadgeText.format(R.string.RawSupporterTransferCooldown, date);
        } else if (RESULT_TARGET_NOT_ELIGIBLE.equals(result)) {
            text = RawBadgeText.get(R.string.RawSupporterTransferTargetHasBadge);
        } else {
            text = RawBadgeText.get(R.string.RawSupporterTransferFailed);
        }
        RawNotify.warn(AndroidUtilities.replaceTags(text));
    }
}
