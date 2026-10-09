package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.tlutils.TLKeyboardHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.ContextLinkCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MentionsContainerView;
import org.telegram.ui.Components.URLSpanUserMention;
import org.telegram.ui.Components.chat.layouts.ChatActivitySideControlsButtonsLayout;
import org.telegram.ui.ContentPreviewViewer;

import java.util.ArrayList;

/**
 * Everything rawGram adds to a chat screen, behind a small host interface: the inline results tray
 * (park results in a side button, bring them back in any chat), the reroll spinner/bubble, the long press
 * raw viewer for inline results, the "Подробности" message submenu, the inline keyboard button sheet
 * and hiding the keyboard on scroll.
 * ChatActivity only calls these hooks, so rawGram changes don't touch (or recompile) the chat screen.
 */
public class RawChatHooks {

    /** What a chat screen gives rawGram access to. Views may be null before createView / after destroy. */
    public interface Host {
        BaseFragment fragment();

        int account();

        Theme.ResourcesProvider resources();

        ViewGroup contentView();

        MentionsContainerView mentions();

        ChatActivitySideControlsButtonsLayout sideButtons();

        ChatActivityEnterView enterView();

        ArrayList<MessageObject> messages();

        /** Glass color of the floating buttons. */
        int glassColor();

        /** False in secret chats and where stickers/inline results can't be sent. */
        boolean canSendInline();

        /** Sends an inline result without clearing the query; null where sending isn't possible. */
        Utilities.Callback<TLRPC.BotInlineResult> inlineSender();

        /** The soft keyboard is up and the chat isn't in text-selection or search mode. */
        boolean isTypingInChat();

        void closeMenu();

        /** Sends {@code result} of {@code bot} (not the inline bot in the field) through the chat's normal path, then runs onSent; null where sending isn't possible. */
        Utilities.Callback3<TLRPC.BotInlineResult, TLRPC.User, Runnable> foreignInlineSender();

        /** Runs a message menu option (ChatActivity.OPTION_*) for {@code message}, as if picked from its menu. */
        void runMessageOption(MessageObject message, int option);

        /** Opens the chat search filtered to messages from {@code user} or {@code chat} (as "search_from_user_id"). */
        void searchFrom(TLRPC.User user, TLRPC.Chat chat);
    }

    private final Host host;

    public RawChatHooks(Host host) {
        this.host = host;
    }

    // "Чаты: вид и поведение": double tap, message menu items, admin shortcuts (RawChatUiActions)
    private RawChatUiActions ui;

    public RawChatUiActions ui() {
        if (ui == null) {
            ui = new RawChatUiActions(host);
        }
        return ui;
    }

    /** A tap on a row of the mentions list; true when rawGram handled it. */
    public boolean onMentionClick(Object item) {
        if (item instanceof RawUserInfo.Hint) {
            RawUserInfo.lookup(host, (RawUserInfo.Hint) item);
            return true;
        }
        return false;
    }

    /**
     * Long press on a user in the @-suggestions: inserts the user's name as a mention (like Telegram does for users
     * without a username) instead of @username.
     */
    public boolean onMentionLongPress(View view, int position, boolean searchingForUser) {
        MentionsContainerView mentions = host.mentions();
        ChatActivityEnterView enterView = host.enterView();
        if (searchingForUser || mentions == null || enterView == null || position <= 0 || mentions.getAdapter().isBannedInline()) {
            return false;
        }
        Object item = mentions.getAdapter().getItem(position - 1);
        if (!(item instanceof TLRPC.User) || item instanceof RawUserInfo.Hint) {
            return false;
        }
        TLRPC.User user = (TLRPC.User) item;
        String name = UserObject.getFirstName(user, false);
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        Spannable spannable = new SpannableString(name + " ");
        spannable.setSpan(new URLSpanUserMention("" + user.id, 3), 0, spannable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        enterView.replaceWithText(mentions.getAdapter().getResultStartPosition(), mentions.getAdapter().getResultLength(), spannable, false);
        try {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignore) {
        }
        return true;
    }

    // ---- lifecycle ----

    /** After the inline results view is set up. */
    public void onMentionsCreated() {
        updateHideButton();
        RawInlineStash.addListener(stashListener);
        AndroidUtilities.runOnUIThread(() -> updateStashButton(false));
    }

    private RawBackBadge backBadge;

    public void onResume() {
        updateHideButton();
        updateStashButton(false);
        if (backBadge == null) {
            backBadge = new RawBackBadge(host.fragment(), host.account());
        }
        backBadge.onResume();
    }

    // the tray switch may change while this chat is in the back stack
    private void updateHideButton() {
        MentionsContainerView mentions = host.mentions();
        if (mentions != null) {
            mentions.rawgramSetOnHide(RawgramConfig.isInlineTray() ? this::hideInline : null);
        }
    }

    public void onDestroy() {
        if (backBadge != null) {
            backBadge.onDestroy();
        }
        RawRerollController.dismissActive();
        RawInlineStash.removeListener(stashListener);
    }

    // ---- chat list ----

    /** The user started dragging the message list. */
    public void onChatListDragged() {
        ChatActivityEnterView enterView = host.enterView();
        if (!RawgramConfig.isHideKeyboardOnScroll() || enterView == null) {
            return;
        }
        if (enterView.rawgramIsEmojiPanelShowing()) {
            // emoji / sticker / GIF panel: fold it the same way as the keyboard (the bot keyboard stays)
            enterView.hidePopup(true); // same path as the back button: animated, no "wait for the keyboard"
        } else if (!enterView.isPopupShowing() && enterView.getEditField() != null && enterView.getEditField().isFocused()
                && host.isTypingInChat()) {
            enterView.closeKeyboard();
        }
    }

    // ---- reroll: the spinner in the input and the status bubble above the results ----

    private RawRerollController.Host rerollHost;

    public RawRerollController.Host rerollHost() {
        if (rerollHost == null) {
            rerollHost = () -> {
                RawRerollController active = RawRerollController.getActive();
                RawRerollController reroll = active != null && !active.isDetached() ? active : null;
                ChatActivityEnterView enterView = host.enterView();
                if (enterView != null) {
                    enterView.rawgramSetAuto(reroll != null && reroll.isRunning(), reroll != null ? reroll::stop : null);
                }
                MentionsContainerView mentions = host.mentions();
                if (mentions != null) {
                    mentions.rawgramSetBubble(reroll, reroll == null ? null : () -> {
                        Activity activity = host.fragment().getParentActivity();
                        if (activity != null) {
                            new RawRerollStatusSheet(activity, reroll, host.resources()).show();
                        }
                    });
                }
            };
        }
        return rerollHost;
    }

    /** Long press on an inline result: the raw viewer with a rendered preview. Returns true if handled. */
    public boolean onInlineResultLongPress(View view, int position) {
        MentionsContainerView mentions = host.mentions();
        Activity activity = host.fragment().getParentActivity();
        if (!RawgramConfig.isInlineRaw() || mentions == null || activity == null) {
            return false;
        }
        // stickers and GIFs keep Telegram's own preview (which has its own raw item)
        boolean previewHandled = view instanceof ContextLinkCell && (((ContextLinkCell) view).isSticker() || ((ContextLinkCell) view).isGif());
        if (position == 0 || previewHandled || mentions.getAdapter().isBannedInline() || ContentPreviewViewer.getInstance().isVisible()) {
            return false;
        }
        Object item = mentions.getAdapter().getItem(position - 1);
        if (!(item instanceof TLRPC.BotInlineResult)) {
            return false;
        }
        RawInlineResultViewer.show(activity, host.account(), mentions.getAdapter(), (TLRPC.BotInlineResult) item,
                host.inlineSender(), host.resources(), rerollHost());
        try {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignore) {}
        return true;
    }

    // ---- "Подробности" submenu of the message menu ----

    public static boolean hasDetails(MessageObject message) {
        return RawgramConfig.isMessageDetails() && message != null && !message.isSponsored();
    }

    /** Adds the "Подробности" item (and its swipe-back page) to the message menu. */
    public void addDetails(ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout, MessageObject message) {
        Activity activity = host.fragment().getParentActivity();
        if (!hasDetails(message) || popupLayout.getSwipeBack() == null || activity == null) {
            return;
        }
        int account = host.account();
        LinearLayout details = RawMessageDetails.build(host.fragment(), account, message, host.resources(),
                () -> popupLayout.getSwipeBack().closeForeground(),
                () -> {
                    host.closeMenu();
                    new RawObjectSheet(activity, account, "Сообщение #" + message.getId(), message.messageOwner, host.resources()).show();
                },
                host::closeMenu);
        final int detailsIndex = popupLayout.addViewToSwipeBack(details);
        ActionBarMenuSubItem detailsCell = new ActionBarMenuSubItem(activity, true, true, host.resources());
        detailsCell.setTextAndIcon("Подробности", R.drawable.msg_info);
        detailsCell.setRightIcon(R.drawable.msg_arrowright);
        popupLayout.addView(detailsCell);
        detailsCell.setOnClickListener(v -> {
            popupLayout.getSwipeBack().openForeground(detailsIndex);
            RawMotion.cascadeFromRight(details);
        });
        popupLayout.addView(new ActionBarPopupWindow.GapView(activity, host.resources()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
    }

    // ---- inline keyboard buttons: long press shows what the button carries ----

    // set while Telegram's own long press runs from the sheet, so the hook doesn't catch it again
    private boolean botButtonBypass;

    /**
     * Long press on an inline keyboard button. {@code press} is the normal tap, {@code original} re-enters
     * Telegram's long press (it is offered for url buttons, whose menu has open/copy). Returns true if handled.
     */
    public boolean onBotButtonLongPress(ChatMessageCell cell, TL_keyboard.KeyboardButtonProto button, Runnable press, Runnable original) {
        Activity activity = host.fragment().getParentActivity();
        MessageObject message = cell != null ? cell.getMessageObject() : null;
        if (botButtonBypass || !RawBotButtonSheet.enabled || !RawgramConfig.isBotButtonDebug() || activity == null || button == null || message == null) {
            return false;
        }
        Runnable safePress = press == null ? null : () -> {
            // the cell may show another message by now (the list scrolled under the sheet)
            ChatActivityEnterView enterView = host.enterView();
            if (cell.getMessageObject() == message) {
                press.run();
            } else if (enterView != null) {
                enterView.didPressedBotButton(button, message, message, null);
            }
        };
        Runnable menu = original == null || !TLKeyboardHelper.isType(button, TL_keyboard.TL_inlineButtonTypeUrl.class) ? null : () -> {
            botButtonBypass = true;
            try {
                original.run();
            } finally {
                botButtonBypass = false;
            }
        };
        RawBotButtonSheet.show(activity, host.account(), message, button, host.resources(), safePress, menu);
        try {
            cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        } catch (Exception ignore) {}
        return true;
    }

    // ---- inline results tray: park the shown results in a side button, bring them back in any chat ----

    private final Runnable stashListener = () -> updateStashButton(true);
    private boolean hiding;
    // while the results are being parked, the mentions adapter doesn't see field changes (it would drop the list)
    private boolean holdMentions;
    private Runnable resetMentionAlpha;

    /** ChatActivity skips feeding the field text to the mentions adapter while this is true. */
    public boolean holdsMentions() {
        return holdMentions;
    }

    private void updateStashButton(boolean animated) {
        ChatActivitySideControlsButtonsLayout sideButtons = host.sideButtons();
        if (sideButtons != null) {
            // tray off: parked results stay stored, just hidden
            boolean show = RawgramConfig.isInlineTray() && RawInlineStash.has(host.account());
            sideButtons.showButton(ChatActivitySideControlsButtonsLayout.BUTTON_RAWGRAM_STASH, show, animated);
        }
    }

    private void hideInline() {
        MentionsContainerView mentions = host.mentions();
        ChatActivitySideControlsButtonsLayout sideButtons = host.sideButtons();
        if (hiding || mentions == null || host.enterView() == null || sideButtons == null) {
            return;
        }
        RawInlineStash.Entry entry = mentions.getAdapter().rawgramSnapshot();
        if (entry == null) {
            return;
        }
        hiding = true;
        // 1. the stash button comes in first, above the results, while the hide/reroll buttons leave
        sideButtons.setTranslationZ(dp(2));
        mentions.rawgramSlideOutButtons();
        RawInlineStash.save(host.account(), entry);
        AndroidUtilities.runOnUIThread(() -> {
            MentionsContainerView m = host.mentions();
            if (m == null) {
                finishHide();
                return;
            }
            // 2. the results scroll back to the height they opened with
            int collapse = m.rawgramCollapseToDefault();
            // 3. and soak into the button
            AndroidUtilities.runOnUIThread(this::absorbInline, collapse > 0 ? collapse + 40 : 0);
        }, 440);
    }

    private void absorbInline() {
        ChatActivitySideControlsButtonsLayout sideButtons = host.sideButtons();
        View button = sideButtons != null ? sideButtons.getButtonView(ChatActivitySideControlsButtonsLayout.BUTTON_RAWGRAM_STASH) : null;
        MentionsContainerView container = host.mentions();
        ChatActivityEnterView enterView = host.enterView();
        if (container == null || button == null || enterView == null || !container.isOpen()) {
            finishHide();
            return;
        }
        // the field empties right away; the results keep what they show until they are inside the button
        holdMentions = true;
        enterView.setFieldText("");
        RawAbsorb.start(host.contentView(), container, container.rawgramPanelBounds(), button,
                host.glassColor(), container::rawgramDrawButtons, this::releaseMentions, this::finishHide);
    }

    private void finishHide() {
        hiding = false;
        ChatActivitySideControlsButtonsLayout sideButtons = host.sideButtons();
        if (sideButtons != null) {
            sideButtons.setTranslationZ(0);
        }
        releaseMentions();
    }

    /** Lets the adapter catch up with the (now empty) field, which closes the already invisible panel. */
    private void releaseMentions() {
        MentionsContainerView mentions = host.mentions();
        ChatActivityEnterView enterView = host.enterView();
        if (holdMentions) {
            holdMentions = false;
            if (mentions != null && mentions.getAdapter() != null && enterView != null) {
                mentions.getAdapter().searchUsernameOrHashtag(enterView.getFieldText(), enterView.getCursorPosition(), host.messages(), false, false);
            }
        }
        // the panel closes while invisible; bring its alpha back once it is gone
        if (mentions != null && mentions.getAlpha() < 1f) {
            if (resetMentionAlpha != null) {
                AndroidUtilities.cancelRunOnUIThread(resetMentionAlpha);
            }
            AndroidUtilities.runOnUIThread(resetMentionAlpha = () -> {
                resetMentionAlpha = null;
                mentions.setAlpha(1f);
            }, 500);
        }
    }

    private void restoreInline() {
        int account = host.account();
        RawInlineStash.Entry entry = RawInlineStash.get(account);
        MentionsContainerView container = host.mentions();
        ChatActivityEnterView enterView = host.enterView();
        if (entry == null || enterView == null || container == null) {
            return;
        }
        if (!host.canSendInline()) {
            BulletinFactory.of(host.fragment()).createSimpleBulletin(R.raw.error, "В этом чате запрещена отправка инлайн-результатов").show();
            return;
        }
        if (entry.bot == null || TextUtils.isEmpty(UserObject.getPublicUsername(entry.bot))) {
            BulletinFactory.of(host.fragment()).createSimpleBulletin(R.raw.error, "Не удалось восстановить: у бота нет юзернейма").show();
            return;
        }
        if (hiding) {
            return;
        }
        if (resetMentionAlpha != null) {
            AndroidUtilities.cancelRunOnUIThread(resetMentionAlpha);
            resetMentionAlpha = null;
        }
        MessagesController.getInstance(account).putUser(entry.bot, true);
        RawInlineStash.beginRestore(account, entry);
        RawInlineStash.clear(account);
        enterView.setFieldText("@" + UserObject.getPublicUsername(entry.bot) + " " + entry.query);
        // grow back out of the corner the results were parked in
        container.animate().cancel();
        container.setPivotX(container.getWidth() - dp(40));
        container.setPivotY(container.getHeight());
        container.setScaleX(0.3f);
        container.setScaleY(0.3f);
        container.setAlpha(0f);
        // with rawGram motion it lands with a soft overshoot, like something let out of the button
        if (RawMotion.active()) {
            RawMotion.popIn(container, 0.3f, 0f, 380, 420);
        } else {
            container.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(380).setDuration(300)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
    }

    /** Side button tap; returns true if it was rawGram's. */
    public boolean onSideButtonClick(int buttonId) {
        if (buttonId != ChatActivitySideControlsButtonsLayout.BUTTON_RAWGRAM_STASH) {
            return false;
        }
        restoreInline();
        return true;
    }

    /** Side button long press; returns true if it was rawGram's. */
    public boolean onSideButtonLongClick(int buttonId) {
        if (buttonId != ChatActivitySideControlsButtonsLayout.BUTTON_RAWGRAM_STASH) {
            return false;
        }
        int account = host.account();
        RawInlineStash.Entry entry = RawInlineStash.get(account);
        Activity activity = host.fragment().getParentActivity();
        if (entry != null && activity != null) {
            String bot = entry.bot != null ? "@" + UserObject.getPublicUsername(entry.bot) : "бот";
            new AlertDialog.Builder(activity, host.resources())
                    .setTitle("Сохранённые результаты")
                    .setMessage(bot + " «" + entry.query + "» · " + entry.response.results.size() + " результатов")
                    .setPositiveButton("Восстановить", (d, w) -> restoreInline())
                    .setNegativeButton("Забыть", (d, w) -> RawInlineStash.clear(account))
                    .show();
        }
        return true;
    }
}
