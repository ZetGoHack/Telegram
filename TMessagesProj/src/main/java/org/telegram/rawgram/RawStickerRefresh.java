package org.telegram.rawgram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BottomSheet;

/**
 * «Обновить набор» in the ⋮ menu of StickersAlert / EmojiPacksAlert: refetches the set from the server ignoring the
 * cached hash, so stickers / emoji added to it show up without removing the set and restarting the app. The fresh set
 * replaces the cached one (memory + stickersets2), and for an installed set the installed list is reloaded too, which
 * rebuilds the sticker panel, emoji suggestions and the stickers_v2 cache.
 */
public final class RawStickerRefresh {

    public static final int MENU_REFRESH = 102;

    private RawStickerRefresh() {
    }

    public static void addItem(ActionBarMenuItem options) {
        if (options != null) {
            options.addSubItem(MENU_REFRESH, R.drawable.msg_retry, "Обновить набор");
        }
    }

    /** {@code onLoaded} gets the fresh set on the UI thread; the result is reported with a bulletin over {@code sheet}. */
    public static void refresh(int account, TLRPC.TL_messages_stickerSet current, BottomSheet sheet,
                               Utilities.Callback<TLRPC.TL_messages_stickerSet> onLoaded) {
        if (current == null || current.set == null) {
            return;
        }
        TLRPC.TL_messages_getStickerSet req = new TLRPC.TL_messages_getStickerSet();
        req.stickerset = MediaDataController.getInputStickerSet(current.set);
        req.hash = 0;
        int oldCount = current.documents == null ? 0 : current.documents.size();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_messages_stickerSet) || ((TLRPC.TL_messages_stickerSet) response).set == null) {
                RawNotify.show(sheet, R.drawable.msg_info, "Не удалось обновить набор" + (error != null ? ": " + error.text : ""));
                return;
            }
            TLRPC.TL_messages_stickerSet set = (TLRPC.TL_messages_stickerSet) response;
            MediaDataController data = MediaDataController.getInstance(account);
            data.putStickerSet(set);
            if (data.isStickerPackInstalled(set.set.id)) {
                int type = set.set.masks ? MediaDataController.TYPE_MASK
                        : set.set.emojis ? MediaDataController.TYPE_EMOJIPACKS : MediaDataController.TYPE_IMAGE;
                data.loadStickers(type, false, true, true);
            }
            if (onLoaded != null) {
                onLoaded.run(set);
            }
            int diff = set.documents.size() - oldCount;
            String what = set.set.emojis ? "эмодзи" : "стикеров";
            String text = diff > 0 ? "Набор обновлён: +" + diff + " " + what
                    : diff < 0 ? "Набор обновлён: " + diff + " " + what
                    : "Набор обновлён, " + what + ": " + set.documents.size();
            RawNotify.show(sheet, R.drawable.msg_retry, text);
        }));
    }
}
