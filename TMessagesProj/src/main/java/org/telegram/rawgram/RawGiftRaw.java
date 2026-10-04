package org.telegram.rawgram;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.StickersAlert;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Star gift viewer (StarGiftSheet): «Raw» with a readable «Оформление» tab (model / symbol / backdrop with
 * rarity and #RRGGBB colors), and shortcuts to the sticker sets of the model and the symbol of a unique gift.
 * Unique gifts get these in the sheet ⋮; plain gifts have no ⋮, so they get the «Raw» pill in the top view.
 */
public class RawGiftRaw {

    private static final String PILL_TAG = "rawgram_gift_pill";
    public static final String MODEL_SET_TITLE = "Набор моделей";
    public static final String SYMBOL_SET_TITLE = "Набор символов";

    public static void show(Context context, int account, TL_stars.StarGift gift, TLObject savedGift, MessageObject messageObject, Theme.ResourcesProvider resourcesProvider) {
        TLRPC.Message message = messageObject != null ? messageObject.messageOwner : null;
        String title = "Raw · gift" + (gift != null ? " " + gift.id : "");
        LinkedHashMap<String, Object> appearance = gift != null ? appearance(account, gift) : null;
        RawObjectSheet sheet = RawShortcuts.objectsSheet(context, account, resourcesProvider, title,
                gift instanceof TL_stars.TL_starGiftUnique ? "Оформление" : "Подарок", appearance,
                "StarGift", gift,
                "attributes", gift != null && !gift.attributes.isEmpty() ? gift.attributes : null,
                "SavedStarGift", savedGift,
                "action", message != null ? message.action : null,
                "Message", message);
        if (sheet != null) {
            sheet.show();
        }
    }

    /** Readable summary; *_color values are "#RRGGBB" so the tree view draws a swatch and copies the hex on long press. */
    static LinkedHashMap<String, Object> appearance(int account, TL_stars.StarGift gift) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (!TextUtils.isEmpty(gift.title)) out.put("title", gift.num > 0 ? gift.title + " #" + gift.num : gift.title);
        out.put("id", gift.id);
        if (gift.gift_id != 0) out.put("gift_id", gift.gift_id);
        if (!TextUtils.isEmpty(gift.slug)) {
            out.put("slug", gift.slug);
            out.put("link", "https://t.me/nft/" + gift.slug);
        }
        if (gift.owner_id != null) {
            out.put("owner", RawShortcuts.peerLabel(account, DialogObject.getPeerDialogId(gift.owner_id)));
        } else if (!TextUtils.isEmpty(gift.owner_name)) {
            out.put("owner", gift.owner_name);
        }
        if (!TextUtils.isEmpty(gift.owner_address)) out.put("owner_address", gift.owner_address);
        if (gift.stars != 0) out.put("stars", gift.stars);
        if (gift.availability_total > 0) {
            out.put("availability", (gift.availability_issued > 0 ? gift.availability_issued : gift.availability_total - gift.availability_remains) + " / " + gift.availability_total);
        }
        if (gift.sticker != null) {
            out.put("sticker", documentInfo(gift.sticker));
        }
        for (TL_stars.StarGiftAttribute attribute : gift.attributes) {
            if (attribute instanceof TL_stars.starGiftAttributeModel) {
                LinkedHashMap<String, Object> model = attributeBase(attribute);
                model.putAll(documentInfo(((TL_stars.starGiftAttributeModel) attribute).document));
                out.put("Модель", model);
            } else if (attribute instanceof TL_stars.starGiftAttributePattern) {
                LinkedHashMap<String, Object> pattern = attributeBase(attribute);
                pattern.putAll(documentInfo(((TL_stars.starGiftAttributePattern) attribute).document));
                out.put("Символ", pattern);
            } else if (attribute instanceof TL_stars.starGiftAttributeBackdrop) {
                TL_stars.starGiftAttributeBackdrop b = (TL_stars.starGiftAttributeBackdrop) attribute;
                LinkedHashMap<String, Object> backdrop = attributeBase(attribute);
                backdrop.put("backdrop_id", b.backdrop_id);
                backdrop.put("center_color", TLDumper.colorHex(b.center_color));
                backdrop.put("edge_color", TLDumper.colorHex(b.edge_color));
                backdrop.put("pattern_color", TLDumper.colorHex(b.pattern_color));
                backdrop.put("text_color", TLDumper.colorHex(b.text_color));
                out.put("Фон", backdrop);
            }
        }
        return out;
    }

    private static LinkedHashMap<String, Object> attributeBase(TL_stars.StarGiftAttribute attribute) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (!TextUtils.isEmpty(attribute.name)) out.put("name", attribute.name);
        String rarity = rarity(attribute);
        if (rarity != null) out.put("rarity", rarity);
        if (attribute.crafted) out.put("crafted", true);
        return out;
    }

    /** "1.5% (15‰)" for a permille rarity, the tier name (Rare, Epic, …) otherwise. */
    static String rarity(TL_stars.StarGiftAttribute attribute) {
        if (attribute.rarity == null) {
            return null;
        }
        if (attribute.rarity instanceof TL_stars.TL_starGiftAttributeRarity) {
            int permille = attribute.getRarityPermille();
            return String.format(Locale.US, "%.1f%% (%d‰)", permille / 10f, permille);
        }
        String name = TLDumper.typeName(attribute.rarity);
        String prefix = "TL_starGiftAttributeRarity";
        return name.startsWith(prefix) && name.length() > prefix.length() ? name.substring(prefix.length()) : name;
    }

    private static LinkedHashMap<String, Object> documentInfo(TLRPC.Document document) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (document == null) {
            return out;
        }
        out.put("document_id", document.id);
        TLRPC.InputStickerSet set = setOf(document);
        if (set != null) {
            out.put("set", !TextUtils.isEmpty(set.short_name) ? set.short_name : String.valueOf(set.id));
            if (isEmojiDocument(document)) out.put("set_type", "custom emoji");
        }
        return out;
    }

    // ---- model / symbol set shortcuts ----

    /** Document of the model ({@code pattern == false}) or the symbol ({@code pattern == true}) of a unique gift. */
    public static TLRPC.Document attributeDocument(TL_stars.StarGift gift, boolean pattern) {
        if (!(gift instanceof TL_stars.TL_starGiftUnique)) {
            return null;
        }
        for (TL_stars.StarGiftAttribute attribute : gift.attributes) {
            if (!pattern && attribute instanceof TL_stars.starGiftAttributeModel) {
                return ((TL_stars.starGiftAttributeModel) attribute).document;
            }
            if (pattern && attribute instanceof TL_stars.starGiftAttributePattern) {
                return ((TL_stars.starGiftAttributePattern) attribute).document;
            }
        }
        return null;
    }

    /** ⋮ item condition: object raw is on and the attribute document points to a set. */
    public static boolean hasSet(TL_stars.StarGift gift, boolean pattern) {
        return RawgramConfig.isObjectRaw() && setOf(attributeDocument(gift, pattern)) != null;
    }

    /** «Набор моделей» / «Набор символов»: custom emoji sets open in EmojiPacksAlert, sticker sets in StickersAlert. */
    public static void openSet(Context context, int account, TL_stars.StarGift gift, boolean pattern, Theme.ResourcesProvider resourcesProvider) {
        TLRPC.Document document = attributeDocument(gift, pattern);
        TLRPC.InputStickerSet set = setOf(document);
        if (context == null || set == null) {
            return;
        }
        if (isEmojiDocument(document)) {
            ArrayList<TLRPC.InputStickerSet> sets = new ArrayList<>();
            sets.add(set);
            new EmojiPacksAlert(LaunchActivity.getSafeLastFragment(), context, resourcesProvider, sets).show();
        } else {
            new StickersAlert(context, LaunchActivity.getSafeLastFragment(), set, null, null, resourcesProvider, false).show();
        }
    }

    static TLRPC.InputStickerSet setOf(TLRPC.Document document) {
        if (document == null || document.attributes == null) {
            return null;
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if ((attribute instanceof TLRPC.TL_documentAttributeSticker || attribute instanceof TLRPC.TL_documentAttributeCustomEmoji)
                    && attribute.stickerset != null && !(attribute.stickerset instanceof TLRPC.TL_inputStickerSetEmpty)) {
                return attribute.stickerset;
            }
        }
        return null;
    }

    private static boolean isEmojiDocument(TLRPC.Document document) {
        if (document == null || document.attributes == null) {
            return false;
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                return true;
            }
        }
        return false;
    }

    // ---- «Raw» pill for gifts without the ⋮ (plain gifts) ----

    /** Adds the (hidden) pill to the StarGiftSheet top view; {@link #syncPill} decides when it shows. */
    public static void attachPill(ViewGroup topView, Theme.ResourcesProvider resourcesProvider, Runnable onClick) {
        if (topView == null || !RawgramConfig.isObjectRaw()) {
            return;
        }
        TextView pill = RawShortcuts.rawPill(topView.getContext(), resourcesProvider);
        pill.setTag(PILL_TAG);
        pill.setVisibility(View.GONE);
        pill.setOnClickListener(v -> onClick.run());
        topView.addView(pill, RawShortcuts.pillLayout());
    }

    /** Shown on the info page when the corner is free (no ⋮, no close button). */
    public static void syncPill(ViewGroup topView, boolean show) {
        View pill = topView != null ? topView.findViewWithTag(PILL_TAG) : null;
        if (pill != null) {
            pill.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}
