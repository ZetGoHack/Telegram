package org.telegram.rawgram;

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Stories.DialogStoriesCell;

import java.util.ArrayList;

/** Small static entry points for the "Интерфейс" options, called from Telegram classes. */
public class RawUi {

    // ---- avatar corners ----

    /** Round radius for an avatar whose stock (circle) radius is {@code circleRadius} px. */
    public static int avatarR(int circleRadius) {
        int pct = RawUiConfig.avatarCorners();
        if (pct >= 100) {
            return circleRadius;
        }
        // keep at least 1px: a zero radius makes AvatarDrawable fall back to a circle
        return Math.max(1, Math.round(circleRadius * pct / 100f));
    }

    /** Fills a {@code w}x{@code h} avatar overlay with the avatar's round radius (a circle when it covers half the size). */
    public static void drawAvatarShape(android.graphics.Canvas canvas, int w, int h, int radius, android.graphics.Paint paint) {
        float half = Math.min(w, h) / 2f;
        if (radius <= 0 || radius >= half) {
            canvas.drawCircle(w / 2f, h / 2f, w / 2f, paint);
            return;
        }
        AndroidUtilities.rectTmp.set(0, 0, w, h);
        canvas.drawRoundRect(AndroidUtilities.rectTmp, radius, radius, paint);
    }

    // ---- hide phone number ----

    /** Whether the phone of {@code user} must be shown as {@link #PHONE_HIDDEN}: the option is on and it is the own account (Nagram's scope). */
    public static boolean hidePhone(TLRPC.User user) {
        return RawUiConfig.isHidePhone() && user != null && (user.self || user.id == UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId());
    }

    public static final String PHONE_HIDDEN = "Номер скрыт";
    public static final String COPY_PHONE = "Скопировать номер";

    /** Copies the real (hidden) number of {@code user} and shows the stock "Phone copied" bulletin. */
    public static void copyHiddenPhone(org.telegram.ui.ActionBar.BaseFragment fragment, TLRPC.User user) {
        if (user == null || TextUtils.isEmpty(user.phone)) {
            return;
        }
        AndroidUtilities.addToClipboard("+" + user.phone);
        if (fragment != null) {
            org.telegram.ui.Components.BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.PhoneCopied)).show();
        }
    }

    /** Menu for a tap / long press on a hidden phone row: «Скопировать номер» (the row keeps showing {@link #PHONE_HIDDEN}). */
    public static void showHiddenPhoneMenu(org.telegram.ui.ActionBar.BaseFragment fragment, android.view.View anchor, TLRPC.User user) {
        if (fragment == null || anchor == null || user == null || TextUtils.isEmpty(user.phone)) {
            return;
        }
        org.telegram.ui.Components.ItemOptions.makeOptions(fragment, anchor)
                .setLongPressSelectionEnabled(false)
                .add(R.drawable.msg_copy, COPY_PHONE, () -> copyHiddenPhone(fragment, user))
                .show();
    }

    // ---- chats list search field ----

    /** Height of the idle search field above the chats list (0 when hidden). */
    public static int searchFieldHeight() {
        return RawUiConfig.hideDialogsSearchField() ? 0 : AndroidUtilities.dp(48);
    }

    // ---- settings: hide Premium / Help sections ----

    /**
     * Trims the Settings list built by SettingsActivity.fillItems. {@code premiumFrom} is the index
     * of the shadow that opens the Premium section, {@code helpFrom} the index of the Help header.
     */
    public static void trimSettings(ArrayList<UItem> items, int premiumFrom, int helpFrom) {
        boolean hidePremium = RawUiConfig.hidePremiumSection();
        boolean hideHelp = RawUiConfig.hideHelpSection();
        if (!hidePremium && !hideHelp) {
            return;
        }
        if (premiumFrom < 0 || helpFrom < premiumFrom || helpFrom > items.size()) {
            return;
        }
        if (hidePremium && hideHelp) {
            removeRange(items, premiumFrom, items.size());
            return;
        }
        if (hideHelp) {
            removeRange(items, helpFrom, items.size());
            if (!items.isEmpty() && items.get(items.size() - 1).viewType == UniversalAdapter.VIEW_TYPE_SHADOW) {
                items.remove(items.size() - 1);
            }
        } else {
            // keep the opening shadow, drop premium rows and their closing shadow
            removeRange(items, premiumFrom + 1, helpFrom);
        }
    }

    private static void removeRange(ArrayList<UItem> items, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            items.remove(i);
        }
    }

    // ---- main screen title ----

    private static String folderTitle;

    /** Whether any title option is active. */
    public static boolean customTitle() {
        return RawUiConfig.titleMode() != RawUiConfig.TITLE_STOCK || RawUiConfig.folderNameAsTitle();
    }

    /** Text title for the main screen, or null to keep the Telegram logo. */
    public static CharSequence titleText(int account) {
        if (RawUiConfig.folderNameAsTitle() && !TextUtils.isEmpty(folderTitle)) {
            return folderTitle;
        }
        int mode = RawUiConfig.titleMode();
        if (mode == RawUiConfig.TITLE_CUSTOM) {
            String t = RawUiConfig.customTitle().trim();
            return t.isEmpty() ? null : t;
        } else if (mode == RawUiConfig.TITLE_ACCOUNT) {
            TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
            String name = self != null ? UserObject.getFirstName(self) : null;
            return TextUtils.isEmpty(name) ? null : name;
        }
        return null;
    }

    /** Action bar title of the chats list: {@code stock} (the logo) unless an option replaces it. */
    public static CharSequence mainTitle(int account, CharSequence stock) {
        if (!customTitle()) {
            return stock;
        }
        CharSequence text = titleText(account);
        return text != null ? text : stock;
    }

    /** Title the collapsed stories header shows instead of the logo, or null for the logo. */
    public static CharSequence storiesCellTitle(int account) {
        if (!customTitle()) {
            return null;
        }
        return titleText(account);
    }

    /** Called when the chats list switches folders. */
    public static void onFolderSelected(int account, ActionBar actionBar, DialogStoriesCell storiesCell, MessagesController.DialogFilter filter) {
        if (!RawUiConfig.folderNameAsTitle()) {
            return;
        }
        String name = filter == null || filter.isDefault() ? null : filter.name;
        if (TextUtils.equals(name, folderTitle)) {
            return;
        }
        folderTitle = name;
        if (actionBar != null) {
            SimpleTextView titleView = actionBar.getTitleTextView();
            if (titleView != null) {
                titleView.setText(mainTitle(account, logoTitle()));
            }
        }
        if (storiesCell != null) {
            storiesCell.updateItems(true, false);
        }
    }

    /** The stock Telegram logo title of the chats list. */
    private static CharSequence logoTitle() {
        Drawable logo = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.telegram_logo_2).mutate();
        logo.setBounds(0, AndroidUtilities.dp(2), logo.getIntrinsicWidth(), AndroidUtilities.dp(2) + logo.getIntrinsicHeight());
        logo.setColorFilter(Theme.getColor(Theme.key_telegram_color_dialogsLogo), PorterDuff.Mode.MULTIPLY);
        SpannableStringBuilder ssb = new SpannableStringBuilder(LocaleController.getString(R.string.AppName));
        ssb.setSpan(new ImageSpan(logo), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ssb;
    }

    /** Settings switches: a long title wraps to a second line instead of ending with «…» (the cell's height fits two lines). */
    public static void wrapTitle(org.telegram.ui.Cells.TextCheckCell cell) {
        android.view.View first = cell.getChildCount() > 0 ? cell.getChildAt(0) : null;
        if (first instanceof android.widget.TextView) {
            android.widget.TextView title = (android.widget.TextView) first;
            title.setSingleLine(false);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLineSpacing(0, 0.95f);
        }
    }
}