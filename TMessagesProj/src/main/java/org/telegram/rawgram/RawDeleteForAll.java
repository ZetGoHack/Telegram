package org.telegram.rawgram;

import org.telegram.ui.Cells.CheckBoxCell;

/**
 * «Также удалить для …» in private chats (deleting messages, clearing / deleting the chat, deleting several chats):
 * Telegram always starts it unchecked; with «Запоминать «Удалить для собеседника»» it starts in the position it was
 * last left in. Group / channel "delete for all" checkboxes are not touched.
 */
public final class RawDeleteForAll {

    private RawDeleteForAll() {
    }

    /** Sets the starting state of a just-built checkbox; {@code applies} = a private-chat "for the other side" box. */
    public static void apply(CheckBoxCell cell, boolean[] state, boolean applies) {
        if (cell == null || !applies || !RawChatUiConfig.rememberDeleteForAll.get()) {
            return;
        }
        state[0] = RawChatUiConfig.lastDeleteForAll.get();
        cell.setChecked(state[0], false);
    }

    /** Called from the checkbox's click listener after the toggle. */
    public static void remember(boolean applies, boolean value) {
        if (applies && RawChatUiConfig.rememberDeleteForAll.get()) {
            RawChatUiConfig.lastDeleteForAll.set(value);
        }
    }
}
