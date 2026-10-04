package org.telegram.rawgram;

import android.app.Dialog;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;

/**
 * Owner / peer shortcuts and «Raw» entries for Telegram sheets and ⋮ menus:
 * sticker/emoji set owner (StickersAlert, EmojiPacksAlert, ContentPreviewViewer), story ⋮ (Raw + story id),
 * and a small «Raw» pill for sheets that have no menu (Stars transaction / subscription / boost).
 * Everything is gated by {@link RawgramConfig#isObjectRaw()} (preview menus by isPreviewRaw at the call site).
 */
public class RawShortcuts {

    public static final int MENU_SET_OWNER = 101;
    public static final String SET_OWNER_TITLE = "Владелец набора";

    // ---- sticker / emoji set owner ----

    /** Owner user id of a non-official set, 0 when unknown or official. */
    public static long setOwner(TLRPC.StickerSet set) {
        if (set == null || set.official || set.id == 0) {
            return 0;
        }
        return RawPeers.stickerSetOwner(set.id);
    }

    /** Owner of the set a previewed sticker / emoji belongs to (cache only), 0 when none. */
    public static long setOwner(int account, TLRPC.InputStickerSet input, TLRPC.Document document) {
        if ((input == null || input instanceof TLRPC.TL_inputStickerSetEmpty) && document != null) {
            input = MessageObject.getInputStickerSet(document);
        }
        if (input == null || input instanceof TLRPC.TL_inputStickerSetEmpty) {
            return 0;
        }
        TLRPC.TL_messages_stickerSet cached = MediaDataController.getInstance(account).getStickerSet(input, true);
        if (cached != null && cached.set != null) {
            return setOwner(cached.set);
        }
        // not cached: official sets can't be told apart, same as the message details
        return input.id != 0 ? RawPeers.stickerSetOwner(input.id) : 0;
    }

    /** Adds the (hidden until {@link #updateSetOwnerItem}) «Владелец набора» item to a set ⋮ menu. */
    public static void addSetOwnerItem(ActionBarMenuItem options) {
        if (options == null || !RawgramConfig.isObjectRaw()) {
            return;
        }
        options.addSubItem(MENU_SET_OWNER, R.drawable.msg_openprofile, SET_OWNER_TITLE);
        options.hideSubItem(MENU_SET_OWNER);
    }

    /** Before the menu opens: shows the item for user sets with the owner name / id as subtitle. */
    public static void updateSetOwnerItem(ActionBarMenuItem options, int account, TLRPC.TL_messages_stickerSet stickerSet) {
        if (options == null || !options.hasSubItem(MENU_SET_OWNER)) {
            return;
        }
        long ownerId = stickerSet != null ? setOwner(stickerSet.set) : 0;
        options.setSubItemShown(MENU_SET_OWNER, ownerId > 0);
        if (ownerId <= 0 || !(options.getSubItem(MENU_SET_OWNER) instanceof ActionBarMenuSubItem)) {
            return;
        }
        ActionBarMenuSubItem item = (ActionBarMenuSubItem) options.getSubItem(MENU_SET_OWNER);
        item.setSubtext(peerLabel(account, ownerId));
        if (!RawPeers.user(account, ownerId).isCached) {
            loadPeer(account, ownerId, found -> {
                if (found) {
                    item.setSubtext(peerLabel(account, ownerId));
                }
            });
        }
    }

    /** Tap on «Владелец набора» in a set alert: profile when the owner is known locally, otherwise copy the id. */
    public static void openSetOwner(int account, TLRPC.TL_messages_stickerSet stickerSet, BaseFragment fragment, Runnable dismissAlert) {
        long ownerId = stickerSet != null ? setOwner(stickerSet.set) : 0;
        if (ownerId > 0) {
            openPeer(account, ownerId, fragment, dismissAlert, false, "ID владельца скопирован");
        }
    }

    /** Preview menu variant: the preview is closed first in any case (a bulletin under it would not be seen). */
    public static void openPreviewSetOwner(int account, TLRPC.InputStickerSet input, TLRPC.Document document, Runnable closePreview) {
        long ownerId = setOwner(account, input, document);
        if (ownerId <= 0) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> openPeer(account, ownerId, null, closePreview, true, "ID владельца скопирован"), 200);
    }

    // ---- generic peer shortcut ----

    /** "Name (@username)" when the peer is cached, "ID … · нет в кеше" otherwise. */
    public static String peerLabel(int account, long dialogId) {
        RawPeers.Info info = RawPeers.resolve(account, dialogId);
        if (!info.isCached) {
            return info.id + " · нет в кеше";
        }
        if (TextUtils.isEmpty(info.name)) {
            return TextUtils.isEmpty(info.username) ? info.id : "@" + info.username;
        }
        return TextUtils.isEmpty(info.username) ? info.name : info.name + " (@" + info.username + ")";
    }

    /** Loads a user/chat from the local database into MessagesController (no network). */
    public static void loadPeer(int account, long dialogId, Utilities.Callback<Boolean> done) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            TLObject object = dialogId >= 0 ? storage.getUser(dialogId) : storage.getChat(-dialogId);
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController controller = MessagesController.getInstance(account);
                if (object instanceof TLRPC.User) {
                    controller.putUser((TLRPC.User) object, true);
                } else if (object instanceof TLRPC.Chat) {
                    controller.putChat((TLRPC.Chat) object, true);
                }
                if (done != null) {
                    done.run(object != null);
                }
            });
        });
    }

    /**
     * Opens the peer's profile (after {@code dismiss}) when it is in the memory cache or the local database;
     * otherwise copies its id. {@code dismissOnCopy}: also dismiss when only copying.
     */
    public static void openPeer(int account, long dialogId, BaseFragment fragment, Runnable dismiss, boolean dismissOnCopy, String copiedText) {
        if (RawPeers.resolve(account, dialogId).isCached) {
            present(dialogId, fragment, dismiss);
            return;
        }
        loadPeer(account, dialogId, found -> {
            if (found) {
                present(dialogId, fragment, dismiss);
                return;
            }
            if (dismissOnCopy && dismiss != null) {
                dismiss.run();
            }
            AndroidUtilities.addToClipboard(RawPeers.resolve(account, dialogId).id);
            RawNotify.show(R.drawable.msg_copy, copiedText);
        });
    }

    private static void present(long dialogId, BaseFragment fragment, Runnable dismiss) {
        if (dismiss != null) {
            dismiss.run();
        }
        BaseFragment target = fragment != null && fragment.getParentActivity() != null ? fragment : LaunchActivity.getSafeLastFragment();
        if (target != null) {
            target.presentFragment(ProfileActivity.of(dialogId));
        }
    }

    // ---- story viewer ⋮ ----

    /** «Raw» (story + peer) and «Скопировать ID истории» at the end of the story ⋮ menu. */
    public static void addStoryItems(ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout, int account, long dialogId,
                                     TL_stories.StoryItem storyItem, Theme.ResourcesProvider resourcesProvider,
                                     Runnable dismissMenu, Utilities.Callback<Dialog> showDialog) {
        if (popupLayout == null || storyItem == null || !RawgramConfig.isObjectRaw()) {
            return;
        }
        ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_info, "Raw", false, resourcesProvider).setOnClickListener(v -> {
            if (dismissMenu != null) {
                dismissMenu.run();
            }
            MessagesController controller = MessagesController.getInstance(account);
            Object peer = dialogId >= 0 ? controller.getUser(dialogId) : controller.getChat(-dialogId);
            RawObjectSheet sheet = objectsSheet(popupLayout.getContext(), account, resourcesProvider,
                    "Raw · story " + storyItem.id + " · " + dialogId,
                    "StoryItem", storyItem,
                    DialogObject.isUserDialog(dialogId) ? "User" : "Chat", peer,
                    "media", storyItem.media,
                    "views", storyItem.views,
                    "fwd_from", storyItem.fwd_from,
                    "media_areas", storyItem.media_areas == null || storyItem.media_areas.isEmpty() ? null : storyItem.media_areas);
            if (sheet == null) {
                return;
            }
            if (showDialog != null) {
                showDialog.run(sheet);
            } else {
                sheet.show();
            }
        });
        ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_copy, "Скопировать ID истории", false, resourcesProvider).setOnClickListener(v -> {
            if (dismissMenu != null) {
                dismissMenu.run();
            }
            AndroidUtilities.addToClipboard(String.valueOf(storyItem.id));
            // a bulletin would land under the story viewer window; Android 13+ shows its own clipboard confirmation
            if (android.os.Build.VERSION.SDK_INT < 33) {
                android.widget.Toast.makeText(popupLayout.getContext(), "ID истории скопирован: " + storyItem.id, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- sheets without a menu: «Raw» pill in the top-right corner ----

    /**
     * Wraps a sheet's custom view so a small «Raw» pill sits in its top-right corner; returns {@code content}
     * unchanged when the object raw switch is off. {@code tabs}: alternating tab name / object, null objects skipped.
     */
    public static View withRawPill(View content, int account, Theme.ResourcesProvider resourcesProvider, String title, Object... tabs) {
        if (content == null || !RawgramConfig.isObjectRaw()) {
            return content;
        }
        Context context = content.getContext();
        FrameLayout frame = new FrameLayout(context);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        frame.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView pill = rawPill(context, resourcesProvider);
        pill.setOnClickListener(v -> {
            RawObjectSheet sheet = objectsSheet(context, account, resourcesProvider, title, tabs);
            if (sheet != null) {
                sheet.show();
            }
        });
        frame.addView(pill, pillLayout());
        return frame;
    }

    /** The «Raw» pill itself: small bold gray text on a faint rounded background. */
    public static TextView rawPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        TextView pill = new TextView(context);
        int color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
        pill.setText("Raw");
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        pill.setTypeface(AndroidUtilities.bold());
        pill.setTextColor(color);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(AndroidUtilities.dp(9), 0, AndroidUtilities.dp(9), 0);
        pill.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(11), Theme.multAlpha(color, 0.12f), Theme.multAlpha(color, 0.3f)));
        pill.setContentDescription("Raw");
        return pill;
    }

    /** Top-right corner, where Telegram sheets put their close button. */
    public static FrameLayout.LayoutParams pillLayout() {
        return LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 22, Gravity.TOP | Gravity.RIGHT, 0, 12, 12, 0);
    }

    /** RawObjectSheet with one tab per non-null object; null when there is nothing to show. */
    public static RawObjectSheet objectsSheet(Context context, int account, Theme.ResourcesProvider resourcesProvider, String title, Object... tabs) {
        if (context == null || tabs == null) {
            return null;
        }
        RawObjectSheet sheet = null;
        for (int i = 0; i + 1 < tabs.length; i += 2) {
            String name = String.valueOf(tabs[i]);
            Object object = tabs[i + 1];
            if (object == null) {
                continue;
            }
            String subtitle = object instanceof TLObject ? name + " · " + TLDumper.typeName(object) : name;
            if (sheet == null) {
                sheet = new RawObjectSheet(context, account, title, object, resourcesProvider);
                sheet.setObject(subtitle, object);
            }
            final RawObjectSheet target = sheet;
            sheet.addObjectTab(name, () -> target.setObject(subtitle, object));
        }
        return sheet;
    }
}
