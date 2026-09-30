package org.telegram.rawgram;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/** "Raw" item of the sticker / emoji set ⋮ menu (StickersAlert, EmojiPacksAlert). */
public class RawStickerSetRaw {

    public static void show(Context context, int account, TLRPC.TL_messages_stickerSet stickerSet, Theme.ResourcesProvider resourcesProvider) {
        if (context == null || stickerSet == null) {
            return;
        }
        TLRPC.StickerSet set = stickerSet.set;
        String name = set != null ? set.short_name : "?";
        RawObjectSheet sheet = new RawObjectSheet(context, account, "Raw · " + name, stickerSet, resourcesProvider);
        String summary = "messages.stickerSet · " + (set != null ? "id " + set.id + " · access_hash " + set.access_hash + " · " : "")
                + stickerSet.documents.size() + " documents · " + stickerSet.packs.size() + " packs";
        sheet.setObject(summary, stickerSet);
        sheet.addObjectTab("messages.stickerSet", () -> sheet.setObject(summary, stickerSet));
        if (set != null) {
            sheet.addObjectTab("StickerSet", () -> sheet.setObject(TLDumper.typeName(set) + " · hash " + set.hash, set));
        }
        ArrayList<String> ids = documentIds(stickerSet);
        sheet.addObjectTab("Document ids (" + ids.size() + ")", () -> sheet.setObject("id · alt · эмодзи из packs", ids));
        if (!stickerSet.packs.isEmpty()) {
            sheet.addObjectTab("packs", () -> sheet.setObject("эмодзи → document ids", packsMap(stickerSet)));
        }
        if (!stickerSet.keywords.isEmpty()) {
            sheet.addObjectTab("keywords", () -> sheet.setObject("document → keywords", stickerSet.keywords));
        }
        sheet.show();
    }

    /** "id alt [pack emoticons]" per document, in set order. */
    private static ArrayList<String> documentIds(TLRPC.TL_messages_stickerSet stickerSet) {
        HashMap<Long, StringBuilder> emoticons = new HashMap<>();
        for (TLRPC.TL_stickerPack pack : stickerSet.packs) {
            if (pack == null || pack.documents == null) {
                continue;
            }
            for (Long id : pack.documents) {
                StringBuilder sb = emoticons.get(id);
                if (sb == null) {
                    emoticons.put(id, sb = new StringBuilder());
                }
                sb.append(pack.emoticon);
            }
        }
        ArrayList<String> out = new ArrayList<>(stickerSet.documents.size());
        for (TLRPC.Document document : stickerSet.documents) {
            if (document == null) {
                continue;
            }
            String alt = altOf(document);
            StringBuilder line = new StringBuilder().append(document.id);
            if (!TextUtils.isEmpty(alt)) {
                line.append(' ').append(alt);
            }
            StringBuilder packEmoji = emoticons.get(document.id);
            if (packEmoji != null && packEmoji.length() > 0 && !TextUtils.equals(packEmoji, alt)) {
                line.append("  [").append(packEmoji).append(']');
            }
            out.add(line.toString());
        }
        return out;
    }

    private static LinkedHashMap<String, Object> packsMap(TLRPC.TL_messages_stickerSet stickerSet) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (TLRPC.TL_stickerPack pack : stickerSet.packs) {
            if (pack != null) {
                out.put(pack.emoticon, pack.documents);
            }
        }
        return out;
    }

    private static String altOf(TLRPC.Document document) {
        if (document.attributes == null) {
            return null;
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if ((attribute instanceof TLRPC.TL_documentAttributeSticker || attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) && !TextUtils.isEmpty(attribute.alt)) {
                return attribute.alt;
            }
        }
        return null;
    }
}
