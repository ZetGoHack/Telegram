package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.StickersAlert;
import org.telegram.ui.Components.StickersDialogs;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * «Добавить в…»: copies a sticker or custom emoji into one of the user's own sets (or a new one) with
 * stickers.addStickerToSet / createStickerSet and the existing document, the same calls the stock sticker editor uses
 * for freshly made stickers. Stickers go to sticker sets, masks to mask sets, emoji to emoji sets (the server rejects
 * mixed types).
 *
 * The item opens a swipe-back page of the menu it sits in (like «Подробности»): the user's sets with a preview sticker,
 * taken from the installed sets the client keeps (own = {@code set.creator}) right away, then completed in the
 * background with messages.getMyStickers (own sets that are archived or not installed). Entry points: the sticker /
 * emoji preview menu (ContentPreviewViewer) and the message menu of a sticker. The bulletin after adding opens the set.
 */
public final class RawAddToPack {

    public static final String TITLE = "Добавить в…";
    private static final String FALLBACK_EMOJI = "😀";

    private RawAddToPack() {
    }

    public static boolean isEmoji(TLRPC.Document document) {
        if (document != null) {
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean canAdd(TLRPC.Document document) {
        return RawChatUiConfig.addToPack.get() && document != null && document.id != 0
                && (isEmoji(document) || MessageObject.isStickerDocument(document)
                || MessageObject.isAnimatedStickerDocument(document, true) || MessageObject.isVideoStickerDocument(document));
    }

    /** Message menu: the sticker document of a sticker message, or null. */
    public static TLRPC.Document stickerOf(MessageObject message) {
        if (message == null || message.getId() <= 0 || !(message.isSticker() || message.isAnimatedSticker())) {
            return null;
        }
        TLRPC.Document document = message.getDocument();
        return canAdd(document) ? document : null;
    }

    // ---- the swipe-back page ----

    /**
     * Turns {@code item} into the entry of a swipe-back page listing the sets; {@code closeMenu} closes the whole menu
     * (and the preview) before the request goes out.
     */
    public static void attachSubmenu(ActionBarPopupWindow.ActionBarPopupWindowLayout menu, ActionBarMenuSubItem item,
                                     int account, TLRPC.Document document, Object parent,
                                     Theme.ResourcesProvider rp, Runnable closeMenu) {
        if (menu == null || item == null || menu.getSwipeBack() == null || document == null) {
            return;
        }
        Context context = menu.getContext();
        boolean emoji = isEmoji(document);
        boolean mask = !emoji && MessageObject.isMaskDocument(document);

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        ActionBarMenuSubItem back = new ActionBarMenuSubItem(context, true, false, rp);
        back.setItemHeight(44);
        back.setTextAndIcon(LocaleController.getString(R.string.Back), R.drawable.msg_arrow_back);
        back.setOnClickListener(v -> menu.getSwipeBack().closeForeground());
        page.addView(back);
        page.addView(new ActionBarPopupWindow.GapView(context, rp), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48 * 6 + 24), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows);
        page.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rows.addView(row(context, rp, null, null, emoji ? "Новый набор эмодзи…" : "Новый набор…", null, v -> {
            closeMenu.run();
            AndroidUtilities.runOnUIThread(() -> createSet(account, document, emoji, mask), 200);
        }));
        HashSet<Long> shown = new HashSet<>();
        int type = emoji ? MediaDataController.TYPE_EMOJIPACKS : mask ? MediaDataController.TYPE_MASK : MediaDataController.TYPE_IMAGE;
        for (TLRPC.TL_messages_stickerSet cached : MediaDataController.getInstance(account).getStickerSets(type)) {
            if (cached == null || cached.set == null || !cached.set.creator || !shown.add(cached.set.id)) {
                continue;
            }
            TLRPC.Document cover = cached.documents.isEmpty() ? null : cached.documents.get(0);
            rows.addView(setRow(context, rp, account, cached.set, cover, document, parent, closeMenu));
        }
        // own sets the client doesn't keep (archived / removed) come from the server
        loadMySets(account, 0, new ArrayList<>(), covered -> {
            if (covered == null) {
                return;
            }
            for (TLRPC.StickerSetCovered c : covered) {
                if (c.set == null || c.set.emojis != emoji || c.set.masks != mask || !shown.add(c.set.id)) {
                    continue;
                }
                TLRPC.Document cover = c.cover != null ? c.cover : c.covers != null && !c.covers.isEmpty() ? c.covers.get(0) : null;
                rows.addView(setRow(context, rp, account, c.set, cover, document, parent, closeMenu));
            }
        });

        int index = menu.addViewToSwipeBack(page);
        item.setRightIcon(R.drawable.msg_arrowright);
        item.setOnClickListener(v -> {
            menu.getSwipeBack().openForeground(index);
            RawMotion.cascadeFromRight(page);
        });
    }

    private static View setRow(Context context, Theme.ResourcesProvider rp, int account, TLRPC.StickerSet set,
                               TLRPC.Document cover, TLRPC.Document document, Object parent, Runnable closeMenu) {
        return row(context, rp, set, cover, set.title, String.valueOf(set.count), v -> {
            closeMenu.run();
            addToSet(account, set, document, parent, true);
        });
    }

    /** A menu-height row: preview (or the «+» icon), title, count on the right. */
    private static View row(Context context, Theme.ResourcesProvider rp, TLRPC.StickerSet set, TLRPC.Document cover,
                            String title, String count, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumWidth(AndroidUtilities.dp(220));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), 2));
        BackupImageView image = new BackupImageView(context);
        if (set == null) {
            image.setImageResource(R.drawable.msg_add);
            image.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, rp), android.graphics.PorterDuff.Mode.SRC_IN));
        } else if (cover != null) {
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(cover.thumbs, 90);
            SvgHelper.SvgDrawable svg = DocumentObject.getSvgThumb(cover, Theme.key_windowBackgroundGray, 1.0f, 1f, rp);
            if (thumb != null) {
                image.setImage(ImageLocation.getForDocument(thumb, cover), null, "webp", svg, set);
            } else {
                image.setImage(ImageLocation.getForDocument(cover), "30_30", svg, 0, set);
            }
        }
        row.addView(image, LayoutHelper.createLinear(set == null ? 24 : 28, set == null ? 24 : 28, Gravity.CENTER_VERTICAL, set == null ? 16 : 14, 0, set == null ? 18 : 16, 0));
        TextView name = new TextView(context);
        name.setText(title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, rp));
        row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        if (count != null) {
            TextView counter = new TextView(context);
            counter.setText(count);
            counter.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            counter.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            row.addView(counter, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 16, 0));
        }
        row.setOnClickListener(onClick);
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));
        return row;
    }

    private static void loadMySets(int account, long offsetId, ArrayList<TLRPC.StickerSetCovered> into,
                                   org.telegram.messenger.Utilities.Callback<ArrayList<TLRPC.StickerSetCovered>> done) {
        TLRPC.TL_messages_getMyStickers req = new TLRPC.TL_messages_getMyStickers();
        req.offset_id = offsetId;
        req.limit = 100;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_messages_myStickers)) {
                done.run(into.isEmpty() ? null : into);
                return;
            }
            TLRPC.TL_messages_myStickers res = (TLRPC.TL_messages_myStickers) response;
            into.addAll(res.sets);
            TLRPC.StickerSetCovered last = into.isEmpty() ? null : into.get(into.size() - 1);
            if (res.sets.size() >= req.limit && into.size() < res.count && last != null && last.set != null) {
                loadMySets(account, last.set.id, into, done);
            } else {
                done.run(into);
            }
        }));
    }

    // ---- requests ----

    private static String emojiOf(int account, TLRPC.Document document) {
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if ((attribute instanceof TLRPC.TL_documentAttributeSticker || attribute instanceof TLRPC.TL_documentAttributeCustomEmoji)
                    && !TextUtils.isEmpty(attribute.alt)) {
                return attribute.alt;
            }
        }
        String found = MessageObject.findAnimatedEmojiEmoticon(document, FALLBACK_EMOJI, account);
        return TextUtils.isEmpty(found) ? FALLBACK_EMOJI : found;
    }

    private static void addToSet(int account, TLRPC.StickerSet set, TLRPC.Document document, Object parent, boolean retryOnFileRef) {
        TLRPC.TL_stickers_addStickerToSet req = new TLRPC.TL_stickers_addStickerToSet();
        req.stickerset = MediaDataController.getInputStickerSet(set);
        req.sticker = MediaDataController.getInputStickerSetItem(document, emojiOf(account, document));
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_messages_stickerSet) {
                done(account, (TLRPC.TL_messages_stickerSet) response);
            } else if (error != null && retryOnFileRef && FileRefController.isFileRefError(error.text)) {
                withFreshDocument(account, document, fresh -> addToSet(account, set, fresh, parent, false));
            } else {
                fail(error);
            }
        }));
    }

    private static void createSet(int account, TLRPC.Document document, boolean emoji, boolean mask) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        StickersDialogs.showNameEditorDialog(null, fragment.getResourceProvider(), activity, (title, whenDone) -> {
            TLRPC.TL_stickers_createStickerSet req = new TLRPC.TL_stickers_createStickerSet();
            req.user_id = new TLRPC.TL_inputUserSelf();
            req.title = title.toString();
            req.short_name = "";
            req.emojis = emoji;
            req.masks = mask;
            req.stickers.add(MediaDataController.getInputStickerSetItem(document, emojiOf(account, document)));
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                boolean ok = response instanceof TLRPC.TL_messages_stickerSet;
                if (whenDone != null) {
                    whenDone.run(ok);
                }
                if (ok) {
                    done(account, (TLRPC.TL_messages_stickerSet) response);
                } else {
                    fail(error);
                }
            }));
        });
    }

    /** The document from a fresh copy of its own set (cached documents can carry an expired file_reference). */
    private static void withFreshDocument(int account, TLRPC.Document document,
                                          org.telegram.messenger.Utilities.Callback<TLRPC.Document> then) {
        TLRPC.InputStickerSet input = MediaDataController.getInputStickerSet(document);
        if (input == null) {
            fail(null);
            return;
        }
        TLRPC.TL_messages_getStickerSet req = new TLRPC.TL_messages_getStickerSet();
        req.stickerset = input;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_messages_stickerSet) {
                for (TLRPC.Document fresh : ((TLRPC.TL_messages_stickerSet) response).documents) {
                    if (fresh.id == document.id) {
                        then.run(fresh);
                        return;
                    }
                }
            }
            fail(error);
        }));
    }

    private static void done(int account, TLRPC.TL_messages_stickerSet set) {
        MediaDataController data = MediaDataController.getInstance(account);
        data.putStickerSet(set);
        if (!data.isStickerPackInstalled(set.set.id)) {
            data.toggleStickerSet(null, set, 2, null, false, false);
        }
        RawNotify.show(R.drawable.msg_add, "Добавлено в «" + set.set.title + "» — нажми, чтобы открыть", () -> openSet(set));
    }

    private static void openSet(TLRPC.TL_messages_stickerSet set) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || set == null || set.set == null) {
            return;
        }
        TLRPC.InputStickerSet input = MediaDataController.getInputStickerSet(set.set);
        if (set.set.emojis) {
            ArrayList<TLRPC.InputStickerSet> sets = new ArrayList<>();
            sets.add(input);
            fragment.showDialog(new EmojiPacksAlert(fragment, activity, fragment.getResourceProvider(), sets));
        } else {
            fragment.showDialog(new StickersAlert(activity, fragment, input, set, null, fragment.getResourceProvider(), false));
        }
    }

    private static void fail(TLRPC.TL_error error) {
        RawNotify.show(R.drawable.msg_info, "Не удалось добавить" + (error != null ? ": " + error.text : ""));
    }
}
