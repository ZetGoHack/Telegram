package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ProfileActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Breakdown of a message's reactions: every TL_reactionCount (emoji / custom emoji document / paid stars),
 * recent reactors and the paid top. Tap on a reaction copies its id, long press opens its raw.
 */
public class RawReactionsSheet extends BottomSheet {

    private final BaseFragment fragment;
    private final int account;
    private final Theme.ResourcesProvider rp;
    private final LinearLayout list;

    public RawReactionsSheet(BaseFragment fragment, int account, MessageObject message, Theme.ResourcesProvider rp) {
        super(fragment.getParentActivity(), false, rp);
        this.fragment = fragment;
        this.account = account;
        this.rp = rp;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));
        Context context = getContext();
        TLRPC.MessageReactions reactions = message.messageOwner.reactions;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText("Реакции · #" + message.getId());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        subtitle.setText(summary(reactions));
        root.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 8));

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8), 0);
        actionsScroll.addView(actions);
        root.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        chip(actions, "Raw", () -> new RawObjectSheet(getContext(), account, "messageOwner.reactions", reactions, rp).show());

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.6f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (reactions != null) {
            if (reactions.results != null && !reactions.results.isEmpty()) {
                section("Реакции");
                for (TLRPC.ReactionCount rc : reactions.results) {
                    addReaction(rc);
                }
            }
            if (reactions.recent_reactions != null && !reactions.recent_reactions.isEmpty()) {
                section("Недавние (" + reactions.recent_reactions.size() + ")");
                for (TLRPC.MessagePeerReaction r : reactions.recent_reactions) {
                    addRecent(r);
                }
            }
            if (reactions.top_reactors != null && !reactions.top_reactors.isEmpty()) {
                section("Топ по звёздам (" + reactions.top_reactors.size() + ")");
                for (TLRPC.MessageReactor r : reactions.top_reactors) {
                    addReactor(r);
                }
            }
        }
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        hint.setText("Нажатие на реакцию копирует id / эмодзи, долгое — raw. Нажатие на человека открывает профиль, долгое — raw.");
        list.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 0));

        setCustomView(root);
    }

    private static String summary(TLRPC.MessageReactions reactions) {
        if (reactions == null) {
            return "реакций нет";
        }
        int total = 0;
        int kinds = reactions.results != null ? reactions.results.size() : 0;
        if (reactions.results != null) {
            for (TLRPC.ReactionCount rc : reactions.results) {
                total += rc.count;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("всего ").append(total).append(" · видов ").append(kinds);
        if (reactions.min) sb.append(" · min");
        if (reactions.can_see_list) sb.append(" · can_see_list");
        if (reactions.reactions_as_tags) sb.append(" · as_tags");
        sb.append(" · ").append(TLDumper.typeName(reactions));
        return sb.toString();
    }

    // ---- rows ----

    private void addReaction(TLRPC.ReactionCount rc) {
        LinearLayout card = card();
        TextView emoji = emojiView(28);
        card.addView(emoji, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView head = headView();
        TextView details = detailsView();
        texts.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        boolean paid = rc.reaction instanceof TLRPC.TL_reactionPaid;
        String head1 = (paid ? "⭐ " + rc.count + " звёзд" : "×" + rc.count)
                + (rc.chosen ? "  · выбрана мной (chosen_order " + rc.chosen_order + ")" : "");
        head.setText(head1);
        if (rc.chosen) {
            head.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        }
        bindReaction(rc.reaction, emoji, details, true);
        String copyValue = copyValue(rc.reaction);
        card.setOnClickListener(v -> copy(copyValue, "Скопировано: " + copyValue));
        card.setOnLongClickListener(v -> {
            openRaw(rc, rc.reaction);
            return true;
        });
        list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
    }

    private void addRecent(TLRPC.MessagePeerReaction r) {
        LinearLayout card = card();
        TextView emoji = emojiView(22);
        card.addView(emoji, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView head = headView();
        TextView details = detailsView();
        texts.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        RawPeers.Info info = RawPeers.resolve(account, r.peer_id);
        head.setText(info != null ? info.format() : "?");
        boolean mine = r.peer_id != null && DialogObject.getPeerDialogId(r.peer_id) == UserConfig.getInstance(account).getClientUserId();
        StringBuilder sb = new StringBuilder();
        if (r.date != 0) sb.append(date(r.date));
        if (r.big) sb.append(sb.length() > 0 ? " · " : "").append("big");
        if (r.unread) sb.append(sb.length() > 0 ? " · " : "").append("unread");
        if (mine) sb.append(sb.length() > 0 ? " · " : "").append("моя");
        sb.append(sb.length() > 0 ? "\n" : "").append(shortReaction(r.reaction));
        details.setText(sb);
        bindReaction(r.reaction, emoji, null, false);
        card.setOnClickListener(v -> openProfile(info));
        card.setOnLongClickListener(v -> {
            openRaw(r, r.reaction);
            return true;
        });
        list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
    }

    private void addReactor(TLRPC.MessageReactor r) {
        LinearLayout card = card();
        TextView emoji = emojiView(22);
        emoji.setText("⭐");
        card.addView(emoji, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView head = headView();
        TextView details = detailsView();
        texts.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        RawPeers.Info info = r.peer_id != null ? RawPeers.resolve(account, r.peer_id) : null;
        head.setText(info != null ? info.format() : "аноним");
        StringBuilder sb = new StringBuilder();
        sb.append(r.count).append(" звёзд");
        if (r.top) sb.append(" · top");
        if (r.my) sb.append(" · my");
        if (r.anonymous) sb.append(" · anonymous");
        details.setText(sb);
        card.setOnClickListener(v -> {
            if (info != null) {
                openProfile(info);
            } else {
                copy(Integer.toString(r.count), "Скопировано: " + r.count);
            }
        });
        card.setOnLongClickListener(v -> {
            openRaw(r, null);
            return true;
        });
        list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
    }

    /** Emoji visual + (optionally) the detail lines of one reaction; custom emoji documents are fetched if not cached. */
    private void bindReaction(TLRPC.Reaction reaction, TextView emoji, TextView details, boolean full) {
        Paint.FontMetricsInt fm = emoji.getPaint().getFontMetricsInt();
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            String emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
            emoji.setText(Emoji.replaceEmoji(emoticon != null ? emoticon : "?", fm, false));
            if (details != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("emoji ").append(emoticon).append("  ").append(codepoints(emoticon));
                TLRPC.TL_availableReaction available = MediaDataController.getInstance(account).getReactionsMap().get(emoticon);
                if (available != null) {
                    sb.append("\n").append(available.title);
                    if (available.premium) sb.append(" · premium");
                    if (available.inactive) sb.append(" · inactive");
                } else {
                    sb.append("\nнет в availableReactions");
                }
                details.setText(sb);
            }
        } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            long documentId = ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
            TLRPC.Document doc = AnimatedEmojiDrawable.findDocument(account, documentId);
            setCustomEmoji(emoji, documentId, doc);
            if (details != null) {
                details.setText(customEmojiDetails(documentId, doc));
            }
            if (doc == null) {
                // not in the emoji cache yet: ask the fetcher (local db first, then server) and refresh this row
                AnimatedEmojiDrawable.getDocumentFetcher(account).fetchDocument(documentId, loaded -> AndroidUtilities.runOnUIThread(() -> {
                    if (loaded == null) return;
                    setCustomEmoji(emoji, documentId, loaded);
                    if (details != null) {
                        details.setText(customEmojiDetails(documentId, loaded));
                    }
                }));
            }
        } else if (reaction instanceof TLRPC.TL_reactionPaid) {
            emoji.setText("⭐");
            if (details != null) details.setText("paid (TL_reactionPaid) · count = звёзды");
        } else {
            emoji.setText("?");
            if (details != null) details.setText(TLDumper.typeName(reaction));
        }
    }

    private void setCustomEmoji(TextView emoji, long documentId, TLRPC.Document doc) {
        Paint.FontMetricsInt fm = emoji.getPaint().getFontMetricsInt();
        String alt = doc != null ? alt(doc) : null;
        SpannableStringBuilder ssb = new SpannableStringBuilder(TextUtils.isEmpty(alt) ? "x" : alt);
        AnimatedEmojiSpan span = doc != null ? new AnimatedEmojiSpan(doc, 1f, fm) : new AnimatedEmojiSpan(documentId, 1f, fm);
        ssb.setSpan(span, 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        emoji.setText(ssb);
    }

    private String customEmojiDetails(long documentId, TLRPC.Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("custom_emoji ").append(documentId);
        if (doc == null) {
            sb.append("\nдокумент не в кеше, загружаю…");
            return sb.toString();
        }
        String alt = alt(doc);
        if (!TextUtils.isEmpty(alt)) sb.append("\nalt ").append(alt);
        sb.append("\n").append(mediaKind(doc.mime_type));
        if (doc.size > 0) sb.append(" · ").append(AndroidUtilities.formatFileSize(doc.size)).append(" (").append(doc.size).append(" Б)");
        TLRPC.InputStickerSet input = stickerSet(doc);
        if (input != null) {
            TLRPC.TL_messages_stickerSet cached = MediaDataController.getInstance(account).getStickerSet(input, true);
            TLRPC.StickerSet set = cached != null ? cached.set : null;
            String shortName = set != null ? set.short_name : input.short_name;
            long setId = set != null ? set.id : input.id;
            sb.append("\nнабор ");
            if (set != null && !TextUtils.isEmpty(set.title)) sb.append(set.title);
            if (!TextUtils.isEmpty(shortName)) sb.append(set != null && !TextUtils.isEmpty(set.title) ? " · " : "").append(shortName);
            if (setId != 0) sb.append(" · id ").append(setId);
        }
        return sb.toString();
    }

    // ---- helpers ----

    private static String alt(TLRPC.Document doc) {
        for (TLRPC.DocumentAttribute a : doc.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeCustomEmoji || a instanceof TLRPC.TL_documentAttributeSticker) {
                return a.alt;
            }
        }
        return null;
    }

    private static TLRPC.InputStickerSet stickerSet(TLRPC.Document doc) {
        for (TLRPC.DocumentAttribute a : doc.attributes) {
            if ((a instanceof TLRPC.TL_documentAttributeCustomEmoji || a instanceof TLRPC.TL_documentAttributeSticker)
                    && a.stickerset != null && !(a.stickerset instanceof TLRPC.TL_inputStickerSetEmpty)) {
                return a.stickerset;
            }
        }
        return null;
    }

    private static String mediaKind(String mime) {
        if (mime == null) return "mime ?";
        switch (mime) {
            case "application/x-tgsticker":
                return mime + " (tgs, Lottie)";
            case "video/webm":
                return mime + " (webm, видео)";
            case "image/webp":
                return mime + " (webp, статичный)";
            default:
                return mime;
        }
    }

    private static String copyValue(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            return String.valueOf(((TLRPC.TL_reactionEmoji) reaction).emoticon);
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            return Long.toString(((TLRPC.TL_reactionCustomEmoji) reaction).document_id);
        }
        return reaction instanceof TLRPC.TL_reactionPaid ? "paid" : TLDumper.typeName(reaction);
    }

    private static String shortReaction(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            return "emoji " + ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            return "custom_emoji " + ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
        }
        return reaction instanceof TLRPC.TL_reactionPaid ? "paid" : TLDumper.typeName(reaction);
    }

    /** "U+2764 U+FE0F": tells visually identical emoji apart (variation selectors, skin tones). */
    static String codepoints(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "U+%04X", cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private void openRaw(Object object, TLRPC.Reaction reaction) {
        RawObjectSheet sheet = new RawObjectSheet(getContext(), account, "Реакция · raw", object, rp);
        sheet.addObjectTab(TLDumper.typeName(object), () -> sheet.setObject(null, object));
        if (reaction != null && reaction != object) {
            sheet.addObjectTab("Reaction", () -> sheet.setObject(null, reaction));
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            TLRPC.Document doc = AnimatedEmojiDrawable.findDocument(account, ((TLRPC.TL_reactionCustomEmoji) reaction).document_id);
            if (doc != null) {
                sheet.addObjectTab("Document", () -> sheet.setObject(null, doc));
                TLRPC.InputStickerSet input = stickerSet(doc);
                if (input != null) {
                    TLRPC.TL_messages_stickerSet cached = MediaDataController.getInstance(account).getStickerSet(input, true);
                    if (cached != null) {
                        sheet.addObjectTab("StickerSet", () -> sheet.setObject(null, cached.set));
                    }
                    sheet.addAction("Открыть набор", v -> {
                        Activity activity = fragment.getParentActivity();
                        if (activity == null) return;
                        sheet.dismiss();
                        dismiss();
                        ArrayList<TLRPC.InputStickerSet> sets = new ArrayList<>();
                        sets.add(input);
                        fragment.showDialog(new EmojiPacksAlert(fragment, activity, rp, sets));
                    });
                }
            }
        } else if (reaction instanceof TLRPC.TL_reactionEmoji) {
            TLRPC.TL_availableReaction available = MediaDataController.getInstance(account).getReactionsMap().get(((TLRPC.TL_reactionEmoji) reaction).emoticon);
            if (available != null) {
                sheet.addObjectTab("AvailableReaction", () -> sheet.setObject(null, available));
            }
        }
        sheet.show();
    }

    private void openProfile(RawPeers.Info info) {
        if (info == null) {
            return;
        }
        if (!info.isCached) {
            copy(info.id, "Нет в кеше — ID скопирован");
            return;
        }
        dismiss();
        fragment.presentFragment(ProfileActivity.of(info.dialogId));
    }

    private void copy(String value, String toast) {
        AndroidUtilities.addToClipboard(value);
        RawNotify.show(this, R.drawable.msg_copy, toast);
    }

    private static String date(int unix) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date(unix * 1000L));
    }

    // ---- views ----

    private void section(String text) {
        TextView header = new TextView(getContext());
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        list.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 6, 16, 6));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        int base = getThemedColor(Theme.key_dialogTextBlack);
        card.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), Theme.multAlpha(base, 0.04f), Theme.multAlpha(base, 0.12f)));
        return card;
    }

    private TextView emojiView(int sizeDp) {
        AnimatedEmojiSpan.TextViewEmojis view = new AnimatedEmojiSpan.TextViewEmojis(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        return view;
    }

    private TextView headView() {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private TextView detailsView() {
        TextView view = new TextView(getContext());
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        return view;
    }

    private void chip(LinearLayout row, String text, Runnable onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        chip.setTextColor(accent);
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), Theme.multAlpha(accent, 0.12f), Theme.multAlpha(accent, 0.24f)));
        chip.setOnClickListener(v -> onClick.run());
        row.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
    }
}
