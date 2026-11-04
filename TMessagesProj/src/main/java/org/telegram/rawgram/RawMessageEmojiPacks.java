package org.telegram.rawgram;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

import java.util.ArrayList;

/**
 * Extra custom-emoji packs for the message menu's «This message contains emoji from N packs» row: the packs of the
 * message's custom-emoji reactions and of the icons on its inline keyboard buttons. Telegram only counts the emoji in the
 * text and caption; ChatActivity merges these into that list.
 * <p>
 * Ported from exteraGram's {@code MessageEmojiPacks} (exteraGram, GPLv3, https://github.com/exteraSquad/exteraGram).
 */
public final class RawMessageEmojiPacks {

    private RawMessageEmojiPacks() {
    }

    /** Packs of the reactions and inline-button icons of {@code message}, without duplicates; only packs already cached. */
    public static ArrayList<TLRPC.InputStickerSet> collect(int account, MessageObject message) {
        ArrayList<TLRPC.InputStickerSet> sets = new ArrayList<>();
        if (message == null || message.messageOwner == null) {
            return sets;
        }
        TLRPC.Message owner = message.messageOwner;
        if (owner.reactions != null && owner.reactions.results != null) {
            for (TLRPC.ReactionCount count : owner.reactions.results) {
                if (count != null && count.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                    add(sets, AnimatedEmojiDrawable.findStickerSet(account, ((TLRPC.TL_reactionCustomEmoji) count.reaction).document_id));
                }
            }
        }
        if (owner.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            for (TL_keyboard.KeyboardInlineButtonRow row : ((TLRPC.TL_replyInlineMarkup) owner.reply_markup).rows) {
                if (row == null) {
                    continue;
                }
                for (TL_keyboard.KeyboardInlineButton button : row.buttons) {
                    if (button != null && button.style != null && button.style.icon != 0) {
                        add(sets, AnimatedEmojiDrawable.findStickerSet(account, button.style.icon));
                    }
                }
            }
        }
        return sets;
    }

    /** Appends the packs of {@code from} missing in {@code to}. */
    public static void addAll(ArrayList<TLRPC.InputStickerSet> to, ArrayList<TLRPC.InputStickerSet> from) {
        for (int i = 0; i < from.size(); i++) {
            add(to, from.get(i));
        }
    }

    private static void add(ArrayList<TLRPC.InputStickerSet> sets, TLRPC.InputStickerSet set) {
        if (set == null) {
            return;
        }
        for (int i = 0; i < sets.size(); i++) {
            if (sets.get(i).id == set.id) {
                return;
            }
        }
        sets.add(set);
    }
}
