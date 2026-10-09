package org.telegram.rawgram;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextCaption;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.SelectAnimatedEmojiDialog;

/**
 * Editing your own exteraGram badge: an emoji picker (Telegram's status picker) with a caption field at the bottom,
 * ported from exteraGram's {@code ProfileActivity.showBadgeSelect} / {@code SelectAnimatedEmojiDialog} badge mode
 * and {@code BadgesController.updateBadge} (exteraGram, GPLv3).
 * <p>
 * The change is sent the way exteraGram does it: an inline query {@code "badge <documentId> [<text>]"} to the
 * exteraGram API bot; the bot answers with a result whose message text is {@code "ok"} on success.
 */
public final class RawBadgeEditor {

    private static final long API_BOT_ID = 8083294286L;
    private static final String API_BOT_USERNAME = "exteraAuthBot";
    private static final String RESULT_OK = "ok";
    private static final int POPUP_MAX_WIDTH_DP = 324;
    private static final int CAPTION_HEIGHT_DP = 48;
    /** Selection that matches no emoji: re-tapping the current badge must not reset it. */
    private static final long NO_SELECTION = -1L;

    private static SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow window;

    private RawBadgeEditor() {
    }

    /** Opens the picker anchored at the badge of {@code nameView} (the profile header name). */
    public static void show(BaseFragment fragment, SimpleTextView nameView) {
        if (window != null || fragment == null || fragment.getFragmentView() == null || nameView == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final RawBadges.Badge current = RawBadges.get(selfId);
        final long defaultId = RawBadges.defaultBadgeId(selfId);
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }

        AnimatedBadgeAnchor anchor = AnimatedBadgeAnchor.of(nameView);
        int topMarginDp = nameView.getScaleX() < 1.5f ? 16 : 32;
        int popupWidth = (int) Math.min(AndroidUtilities.dp(POPUP_MAX_WIDTH_DP), AndroidUtilities.displaySize.x * .95f);
        int xoff = Utilities.clamp(anchor.centerX - popupWidth / 2, AndroidUtilities.displaySize.x - popupWidth, 0);
        int yoff = anchor.centerY - AndroidUtilities.dp(topMarginDp);

        final SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] popup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        final EditTextCaption[] caption = new EditTextCaption[1];
        SelectAnimatedEmojiDialog picker = new SelectAnimatedEmojiDialog(fragment, context, true, Math.max(0, anchor.centerX - xoff),
                SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS, true, resourcesProvider, topMarginDp) {
            @Override
            protected void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                long id = documentId == null || documentId == 0 ? defaultId : documentId;
                save(fragment, account, id, caption[0].getText().toString());
                if (popup[0] != null) {
                    popup[0].dismiss();
                }
            }
        };
        if (current == null || current.documentId == defaultId) {
            picker.setSelected(0L);
        } else {
            picker.setSelected(NO_SELECTION);
        }
        SimpleTextView scrimParent = nameView;
        if (RawBadges.profileDrawable(nameView) != null) {
            picker.setScrimDrawable(RawBadges.profileDrawable(nameView), scrimParent);
        }

        caption[0] = addCaption(picker, context, resourcesProvider, current, () -> {
            long id = current != null ? current.documentId : defaultId;
            save(fragment, account, id, caption[0].getText().toString());
            if (popup[0] != null) {
                popup[0].dismiss();
            }
        });

        popup[0] = window = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(picker, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() {
                super.dismiss();
                window = null;
            }
        };
        // showAsDropDown, as ProfileActivity does for the emoji status: only it runs the picker's open animation
        // (showAtLocation leaves just the collapsed bubble). Offsets are relative to the fragment view's bottom-left.
        View fragmentView = fragment.getFragmentView();
        int[] fv = new int[2];
        fragmentView.getLocationInWindow(fv);
        popup[0].showAsDropDown(fragmentView, xoff - fv[0], yoff - (fv[1] + fragmentView.getHeight()), Gravity.TOP | Gravity.LEFT);
        popup[0].dimBehind();
    }

    /** Caption row at the bottom of the picker, like exteraGram's badge mode, with a button to save the text only. */
    private static EditTextCaption addCaption(SelectAnimatedEmojiDialog picker, Context context, Theme.ResourcesProvider resourcesProvider,
                                              RawBadges.Badge current, Runnable onDone) {
        if (picker.gridViewContainer != null && picker.gridViewContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) picker.gridViewContainer.getLayoutParams();
            lp.bottomMargin = AndroidUtilities.dp(CAPTION_HEIGHT_DP);
            picker.gridViewContainer.setLayoutParams(lp);
        }
        FrameLayout row = new FrameLayout(context);
        row.setBackgroundColor(Theme.getColor(Theme.key_chat_emojiPanelBackground, resourcesProvider));

        EditTextCaption edit = new EditTextCaption(context, resourcesProvider);
        edit.setHint("Подпись к бейджу");
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        edit.setHintTextColor(Theme.getColor(Theme.key_chat_emojiSearchIcon, resourcesProvider));
        edit.setCursorColor(Theme.getColor(Theme.key_featuredStickers_addedIcon, resourcesProvider));
        edit.setCursorWidth(1.5f);
        edit.setCursorSize(AndroidUtilities.dp(20));
        edit.setBackground(null);
        edit.setSingleLine(true);
        edit.setLines(1);
        edit.setMaxLines(1);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onDone.run();
                return true;
            }
            return false;
        });
        if (current != null && current.text != null) {
            edit.setText(current.text);
        }
        row.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 16, 0, 48, 0));

        ImageView done = new ImageView(context);
        done.setScaleType(ImageView.ScaleType.CENTER);
        done.setImageResource(R.drawable.ic_ab_done);
        done.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addedIcon, resourcesProvider), PorterDuff.Mode.SRC_IN));
        done.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
        done.setContentDescription("Сохранить подпись");
        done.setOnClickListener(v -> onDone.run());
        row.addView(done, LayoutHelper.createFrame(CAPTION_HEIGHT_DP, CAPTION_HEIGHT_DP, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        picker.contentView.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, CAPTION_HEIGHT_DP, Gravity.BOTTOM));
        return edit;
    }

    private static void save(BaseFragment fragment, int account, long documentId, String text) {
        final String caption = text == null ? null : text.trim();
        final RawBadges.Badge badge = new RawBadges.Badge(documentId, caption);
        updateBadge(account, badge, result -> {
            if (RESULT_OK.equals(result)) {
                RawBadges.updateLocal(UserConfig.getInstance(account).getClientUserId(), badge);
                if (fragment.getParentActivity() != null) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, "Бейдж обновлён").show();
                }
            } else if (fragment.getParentActivity() != null) {
                BulletinFactory.of(fragment).createErrorBulletin("Не удалось обновить бейдж").show();
            }
        });
    }

    /** exteraGram's {@code BadgesController.updateBadge}: {@code callback} gets the bot's answer (UI thread), or null. */
    public static void updateBadge(int account, RawBadges.Badge badge, Utilities.Callback<String> callback) {
        String query = "badge " + badge.documentId + (badge.text == null ? "" : " " + badge.text);
        sendBotRequest(account, query, callback);
    }

    private static void sendBotRequest(int account, String query, Utilities.Callback<String> callback) {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.User bot = controller.getUser(API_BOT_ID);
        if (bot != null) {
            sendInline(account, bot, query, callback);
            return;
        }
        controller.getUserNameResolver().resolve(API_BOT_USERNAME, peerId -> {
            TLRPC.User resolved = peerId != null && peerId == API_BOT_ID ? controller.getUser(API_BOT_ID) : null;
            if (resolved == null) {
                callback.run(null);
            } else {
                sendInline(account, resolved, query, callback);
            }
        });
    }

    private static void sendInline(int account, TLRPC.User bot, String query, Utilities.Callback<String> callback) {
        TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
        req.query = query;
        req.bot = MessagesController.getInstance(account).getInputUser(bot);
        req.offset = "";
        req.peer = new TLRPC.TL_inputPeerEmpty();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            String answer = null;
            if (response instanceof TLRPC.messages_BotResults) {
                TLRPC.messages_BotResults results = (TLRPC.messages_BotResults) response;
                if (!results.results.isEmpty() && results.results.get(0).send_message != null) {
                    answer = results.results.get(0).send_message.message;
                }
            }
            callback.run(answer);
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /** Screen position of the badge in a (possibly scaled) name view. */
    private static final class AnimatedBadgeAnchor {
        int centerX;
        int centerY;

        static AnimatedBadgeAnchor of(SimpleTextView view) {
            AnimatedBadgeAnchor anchor = new AnimatedBadgeAnchor();
            int[] loc = new int[2];
            view.getLocationInWindow(loc);
            android.graphics.drawable.Drawable drawable = RawBadges.profileDrawable(view);
            Rect bounds = drawable != null && (view.getRightDrawable() == drawable || view.getRightDrawable2() == drawable)
                    ? drawable.getBounds() : null;
            if (bounds == null || bounds.isEmpty()) {
                anchor.centerX = loc[0] + (int) (view.getWidth() * view.getScaleX());
                anchor.centerY = loc[1] + (int) (view.getHeight() / 2f * view.getScaleY());
            } else {
                anchor.centerX = loc[0] + (int) (bounds.centerX() * view.getScaleX());
                anchor.centerY = loc[1] + (int) (bounds.centerY() * view.getScaleY());
            }
            return anchor;
        }
    }
}
