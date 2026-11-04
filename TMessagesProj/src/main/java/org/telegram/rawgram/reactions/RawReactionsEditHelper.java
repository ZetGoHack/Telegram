package org.telegram.rawgram.reactions;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Spanned;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AnimatedEmojiSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clipboard and backup helpers for {@link RawChannelReactionsEditActivity}: copies a reaction list as text
 * (emoji and {@code tg://emoji?id=} links, ⭐ for paid reactions), parses it back, and keeps the last saved
 * list of a channel so it comes back when reactions are turned on again.
 * <p>
 * Ported from exteraGram (com.exteragram.messenger.reactions.ReactionsEditHelper), GPLv3.
 */
public final class RawReactionsEditHelper {

    /** documentId of the paid ⭐ reaction in the editor. */
    public static final long PAID_ID = -1L;

    private static final Pattern EMOJI_LINK = Pattern.compile("tg://emoji\\?id=(\\d+)");
    private static final String STAR = "⭐";
    private static final String VS16 = "️";

    private final int account;
    private final long chatId;
    private final List<TLRPC.TL_availableReaction> availableReactions;

    public RawReactionsEditHelper(int account, long chatId, List<TLRPC.TL_availableReaction> availableReactions) {
        this.account = account;
        this.chatId = chatId;
        this.availableReactions = availableReactions;
    }

    public static final class Backup {
        public final List<Long> reactionIds;
        public final boolean paid;

        Backup(List<Long> reactionIds, boolean paid) {
            this.reactionIds = reactionIds;
            this.paid = paid;
        }
    }

    private String backupKey() {
        return "chat_reactions_backup_" + chatId;
    }

    private static ClipboardManager clipboard() {
        Object service = ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
        return service instanceof ClipboardManager ? (ClipboardManager) service : null;
    }

    public boolean hasClipboard() {
        ClipboardManager clipboard = clipboard();
        return clipboard != null && clipboard.hasPrimaryClip();
    }

    public TLRPC.TL_availableReaction findAvailableReaction(long documentId) {
        for (TLRPC.TL_availableReaction reaction : availableReactions) {
            if (reaction.activate_animation != null && reaction.activate_animation.id == documentId) {
                return reaction;
            }
        }
        return null;
    }

    public void saveBackup(List<Long> selectedIds, boolean paid) {
        ArrayList<Long> ids = new ArrayList<>();
        for (Long id : selectedIds) {
            if (id != PAID_ID) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        if (paid) {
            ids.add(0, PAID_ID);
        }
        MessagesController.getMainSettings(account).edit().putString(backupKey(), TextUtils.join(",", ids)).apply();
    }

    public Backup loadBackup(int limit) {
        String value = MessagesController.getMainSettings(account).getString(backupKey(), null);
        if (value == null) {
            return null;
        }
        ArrayList<Long> all = new ArrayList<>();
        for (String part : value.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id != 0) {
                    all.add(id);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        ArrayList<Long> ids = new ArrayList<>();
        for (Long id : all) {
            if (id != PAID_ID) {
                ids.add(id);
            }
        }
        return new Backup(ids.size() > limit ? new ArrayList<>(ids.subList(0, limit)) : ids, all.contains(PAID_ID));
    }

    /** Copies the reactions inside the selection, in the order they appear. */
    public void copyReactions(Spanned text, int selectionStart, int selectionEnd) {
        if (text == null || selectionStart == selectionEnd) {
            return;
        }
        AnimatedEmojiSpan[] spans = text.getSpans(Math.min(selectionStart, selectionEnd), Math.max(selectionStart, selectionEnd), AnimatedEmojiSpan.class);
        if (spans.length == 0) {
            return;
        }
        Arrays.sort(spans, (a, b) -> Integer.compare(text.getSpanStart(a), text.getSpanStart(b)));
        StringBuilder sb = new StringBuilder();
        for (AnimatedEmojiSpan span : spans) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(reactionToText(span.documentId));
        }
        ClipboardManager clipboard = clipboard();
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("reactions", sb.toString()));
        }
    }

    private String reactionToText(long documentId) {
        if (documentId == PAID_ID) {
            return STAR;
        }
        TLRPC.TL_availableReaction reaction = findAvailableReaction(documentId);
        if (reaction != null && reaction.reaction != null) {
            return reaction.reaction;
        }
        return "tg://emoji?id=" + documentId;
    }

    /**
     * Reactions found in the clipboard, in text order: custom emoji spans, {@code tg://emoji?id=} links,
     * plain emoji that are standard reactions, and ⭐ (documentId {@link #PAID_ID}).
     */
    public List<AnimatedEmojiSpan> parseClipboard() {
        ClipboardManager clipboard = clipboard();
        ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(ApplicationLoader.applicationContext) : null;
        if (TextUtils.isEmpty(text)) {
            return new ArrayList<>();
        }
        TreeMap<Integer, AnimatedEmojiSpan> result = new TreeMap<>();
        TreeMap<Integer, Integer> spanRanges = new TreeMap<>();
        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            for (AnimatedEmojiSpan span : spanned.getSpans(0, spanned.length(), AnimatedEmojiSpan.class)) {
                int start = spanned.getSpanStart(span);
                spanRanges.put(start, spanned.getSpanEnd(span));
                result.put(start, new AnimatedEmojiSpan(span.documentId, null));
            }
        }
        Matcher matcher = EMOJI_LINK.matcher(text);
        while (matcher.find()) {
            try {
                long id = Long.parseLong(matcher.group(1));
                if (id != 0) {
                    result.put(matcher.start(), new AnimatedEmojiSpan(id, null));
                }
            } catch (NumberFormatException ignore) {
            }
        }
        for (Emoji.EmojiSpanRange range : Emoji.parseEmojis(text)) {
            Map.Entry<Integer, Integer> covering = spanRanges.floorEntry(range.start);
            if (covering != null && range.start < covering.getValue()) {
                continue;
            }
            String code = range.code.toString().replace(VS16, "");
            if (STAR.equals(code)) {
                result.put(range.start, new AnimatedEmojiSpan(PAID_ID, null));
                continue;
            }
            for (TLRPC.TL_availableReaction reaction : availableReactions) {
                if (reaction.reaction != null && reaction.activate_animation != null && reaction.reaction.replace(VS16, "").equals(code)) {
                    result.put(range.start, new AnimatedEmojiSpan(reaction.activate_animation, null));
                    break;
                }
            }
        }
        return new ArrayList<>(result.values());
    }
}
