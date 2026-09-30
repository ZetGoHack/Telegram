package org.telegram.rawgram;

import android.content.Context;

import org.telegram.messenger.MediaDataController;
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
        String kind = RawDocInfo.kind(document);
        // custom emoji carry their set in TL_documentAttributeCustomEmoji, which the preview doesn't pick up
        final TLRPC.InputStickerSet inputSet = stickerSet == null || stickerSet instanceof TLRPC.TL_inputStickerSetEmpty ? RawDocInfo.stickerSetOf(document) : stickerSet;
        RawObjectSheet sheet = new RawObjectSheet(context, currentAccount, kind, document, resourcesProvider);
        final String[] info = {RawDocInfo.summary(currentAccount, document)};
        sheet.setSubtitle(info[0]);
        sheet.addObjectTab("Document", () -> {
            sheet.setObject(TLDumper.typeName(document) + " · id " + document.id, document);
            sheet.setSubtitle(info[0]);
        });
        RawDocInfo.loadTgsInfo(currentAccount, document, line -> {
            info[0] = info[0] + '\n' + line;
            if (sheet.getObject() == document) {
                sheet.setSubtitle(info[0]);
            }
        });
        if (inputSet != null && !(inputSet instanceof TLRPC.TL_inputStickerSetEmpty)) {
            sheet.addObjectTab("InputStickerSet", () -> sheet.setObject(TLDumper.typeName(inputSet), inputSet));
            TLRPC.TL_messages_stickerSet set = MediaDataController.getInstance(currentAccount).getStickerSet(inputSet, true);
            if (set != null) {
                sheet.addObjectTab("Sticker set", () -> sheet.setObject("messages.stickerSet · " + (set.set != null ? set.set.short_name : "") + " · " + set.documents.size() + " documents", set));
            }
        }
        sheet.show();
    }
}
