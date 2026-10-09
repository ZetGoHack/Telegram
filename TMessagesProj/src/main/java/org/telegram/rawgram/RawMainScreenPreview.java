package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Copy of the top of the main screen built from the same views DialogsActivity uses: its {@link ActionBar}
 * (logo / custom title, emoji status, snow, search button, account switcher), the idle
 * {@link FragmentSearchField}, the folder tabs ({@link RawFolderTabsPreview}), three {@link DialogCell}s
 * made from {@link DialogCell.CustomDialog}s (as ThemePreviewActivity does) and the {@link FragmentFloatingButton}.
 * Nothing here is interactive or touches real chats.
 */
@SuppressLint("ViewConstructor")
public class RawMainScreenPreview extends LinearLayout {

    private final int account;
    private final ActionBar actionBar;
    private final ActionBarMenuItem searchItem;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusDrawable;
    private Drawable premiumStar;
    private final FragmentSearchField searchField;
    private final RawFolderTabsPreview tabs;
    private final DialogCell[] cells = new DialogCell[3];
    private final DialogCell.CustomDialog[] dialogs = new DialogCell.CustomDialog[3];
    private final FragmentFloatingButton fab;
    private BackupImageView switchAvatar;

    public RawMainScreenPreview(Context context, int account) {
        super(context);
        this.account = account;
        setOrientation(VERTICAL);

        // rounded like the preview card it sits in (RawPreviewCard)
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(org.telegram.rawgram.settings.RawPreviewBackground.DEFAULT_RADIUS_DP));
            }
        });
        setClipToOutline(true);

        // ---- action bar: DialogsActivity.createActionBar + the title it sets in createView ----
        actionBar = new ActionBar(context);
        actionBar.setOccupyStatusBar(false);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSelector), false);
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setSupportsHolidayImage(true);
        ActionBarMenu menu = actionBar.createMenu();
        menu.setTranslationX(-dp(5));
        searchItem = menu.addItem(0, R.drawable.outline_header_search);
        if (UserConfig.getActivatedAccountsCount() > 1) {
            ActionBarMenuItem switchItem = menu.addItemWithWidth(11, 0, dp(56));
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setTextSize(dp(12));
            BackupImageView imageView = switchAvatar = new BackupImageView(context);
            switchItem.addView(imageView, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
            TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            avatarDrawable.setInfo(account, user);
            imageView.getImageReceiver().setCurrentAccount(account);
            imageView.setForUserOrChat(user, avatarDrawable);
        }
        statusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(null, dp(26));
        statusDrawable.center = true;
        addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- idle search field ----
        searchField = new FragmentSearchField(context, null);
        searchField.setPadding(dp(4), dp(4), dp(4), dp(4));
        searchField.editText.setHint(LocaleController.getString(R.string.SearchChats));
        searchField.editText.setFocusable(false);
        searchField.editText.setFocusableInTouchMode(false);
        searchField.editText.setCursorVisible(false);
        searchField.setBlurredBackgroundVisibility(0f);
        addView(searchField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 7, 0, 7, 0));

        // ---- folder tabs ----
        tabs = new RawFolderTabsPreview(context, account);
        addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, RawFolderTabsPreview.HEIGHT_DP));

        // ---- chats + floating button ----
        FrameLayout list = new FrameLayout(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(VERTICAL);
        int now = (int) (System.currentTimeMillis() / 1000);
        dialogs[0] = dialog(0, "Анна", "Скинь, пожалуйста, фото с выходных 🙂", 0, true, false, 0, now - 95, DialogCell.SENT_STATE_NOTHING);
        dialogs[1] = dialog(1, "Рабочий чат", "Созвон перенесли на 15:00", 3, false, false, 0, now - 47 * 60 - 13, DialogCell.SENT_STATE_NOTHING);
        dialogs[2] = dialog(2, "Новости rawGram", "Готово, отправил", 0, false, true, 1, now - 2 * 3600 - 31, DialogCell.SENT_STATE_READ);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new DialogCell(null, context, false, false);
            column.addView(cells[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        list.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fab = new FragmentFloatingButton(context, null);
        fab.setImageResource(R.drawable.filled_fab_compose_32);
        list.addView(fab, FragmentFloatingButton.createDefaultLayoutParams());
        addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private static DialogCell.CustomDialog dialog(int id, String name, String message, int unread, boolean pinned, boolean muted, int type, int date, int sent) {
        DialogCell.CustomDialog d = new DialogCell.CustomDialog();
        d.id = id;
        d.name = name;
        d.message = message;
        d.unread_count = unread;
        d.pinned = pinned;
        d.muted = muted;
        d.type = type;
        d.date = date;
        d.sent = sent;
        return d;
    }

    /** The stock Telegram logo title (DialogsActivity.createView). */
    private CharSequence logoTitle() {
        Drawable logo = getContext().getResources().getDrawable(R.drawable.telegram_logo_2).mutate();
        logo.setBounds(0, dp(2), logo.getIntrinsicWidth(), dp(2) + logo.getIntrinsicHeight());
        logo.setColorFilter(Theme.getColor(Theme.key_telegram_color_dialogsLogo), PorterDuff.Mode.MULTIPLY);
        SpannableStringBuilder ssb = new SpannableStringBuilder(LocaleController.getString(R.string.AppName));
        ssb.setSpan(new ImageSpan(logo), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ssb;
    }

    /** What RawUi.mainTitle would show with the preview's first folder open. */
    private CharSequence title() {
        if (RawUiConfig.folderNameAsTitle()) {
            String folder = tabs.openFolderName();
            if (!TextUtils.isEmpty(folder)) {
                return folder;
            }
        }
        int mode = RawUiConfig.titleMode();
        if (mode == RawUiConfig.TITLE_CUSTOM) {
            String t = RawUiConfig.customTitle().trim();
            if (!t.isEmpty()) {
                return t;
            }
        } else if (mode == RawUiConfig.TITLE_ACCOUNT) {
            TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
            String name = self != null ? UserObject.getFirstName(self) : null;
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
        }
        return logoTitle();
    }

    private Drawable status() {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        Long emojiStatusId = UserObject.getEmojiStatusDocumentId(user);
        if (emojiStatusId != null) {
            statusDrawable.set(emojiStatusId, false);
            return statusDrawable;
        }
        if (user != null && MessagesController.getInstance(account).isPremiumUser(user)) {
            if (premiumStar == null) {
                Drawable star = getContext().getResources().getDrawable(R.drawable.msg_premium_liststar).mutate();
                premiumStar = new AnimatedEmojiDrawable.WrapSizeDrawable(star, dp(18), dp(18)) {
                    @Override
                    public void draw(@NonNull Canvas canvas) {
                        canvas.save();
                        canvas.translate(dp(-2), dp(1));
                        super.draw(canvas);
                        canvas.restore();
                    }
                };
            }
            premiumStar.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
            statusDrawable.set(premiumStar, false);
            return statusDrawable;
        }
        return null;
    }

    /** Re-applies every option: called on bind and after each change. */
    public void bind() {
        tabs.bind();

        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        actionBar.setTitleColor(Theme.getColor(Theme.key_telegram_color_dialogsLogo));
        actionBar.setTitle(title(), status());
        searchItem.setVisibility(RawUiConfig.hideDialogsSearchField() ? View.VISIBLE : View.GONE);
        if (switchAvatar != null) {
            switchAvatar.setRoundRadius(RawUi.avatarR(dp(18)));
        }
        actionBar.requestLayout();
        actionBar.invalidate();

        searchField.setVisibility(RawUiConfig.hideDialogsSearchField() ? View.GONE : View.VISIBLE);
        searchField.updateColors();

        for (int i = 0; i < cells.length; i++) {
            DialogCell cell = cells[i];
            cell.useSeparator = i < cells.length - 1;
            cell.setDialog(dialogs[i]); // re-formats the time (seconds) and re-reads the divider paint
            // custom dialogs get a fixed circle; apply the avatar corners option like real chats do
            cell.avatarImage.setRoundRadius(RawUi.avatarR(dp(26)));
            cell.requestLayout();
            cell.invalidate();
        }
        fab.updateColors();
        fab.setVisibility(RawUiConfig.disableDialogsFab() ? View.GONE : View.VISIBLE);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        statusDrawable.attach();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        statusDrawable.detach();
    }

    // the copy must not react to touches (DialogCell has swipe / long press logic, the search field would focus)
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }
}
