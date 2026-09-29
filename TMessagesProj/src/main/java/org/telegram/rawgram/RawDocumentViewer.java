package org.telegram.rawgram;

import android.content.Context;

import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/** Raw viewer for stickers / GIFs opened from the long-press preview menu. */
public class RawDocumentViewer {

    public static void show(Context context, int currentAccount, TLRPC.Document document, TLRPC.InputStickerSet stickerSet, TLRPC.BotInlineResult inlineResult, Theme.ResourcesProvider resourcesProvider) {
        if (inlineResult != null) {
            RawInlineResultViewer.show(context, currentAccount, null, inlineResult, null, resourcesProvider);
            return;
        }
        if (document == null) {
            return;
        }
        String kind = MessageObject.isStickerDocument(document) || MessageObject.isAnimatedStickerDocument(document, true) ? "Sticker"
                : MessageObject.isGifDocument(document) ? "GIF" : "Document";
        RawObjectSheet sheet = new RawObjectSheet(context, currentAccount, kind, document, resourcesProvider);
        sheet.setSubtitle(TLDumper.typeName(document) + " · id " + document.id + " · dc " + document.dc_id + " · " + document.mime_type + " · " + document.size + " B");
        sheet.addAction("Document", v -> sheet.setObject(TLDumper.typeName(document) + " · id " + document.id, document));
        if (stickerSet != null && !(stickerSet instanceof TLRPC.TL_inputStickerSetEmpty)) {
            sheet.addAction("InputStickerSet", v -> sheet.setObject(TLDumper.typeName(stickerSet), stickerSet));
            TLRPC.TL_messages_stickerSet set = MediaDataController.getInstance(currentAccount).getStickerSet(stickerSet, true);
            if (set != null) {
                sheet.addAction("Sticker set", v -> sheet.setObject("messages.stickerSet · " + (set.set != null ? set.set.short_name : "") + " · " + set.documents.size() + " documents", set));
            }
        }
        sheet.show();
    }
}
