package org.telegram.rawgram;

import android.view.Menu;

import org.telegram.messenger.R;

/**
 * Which items the text formatting menu (selection in the input field) shows — Nagram's ShowText* switches.
 * Stored as a bitmask of hidden items in RawChatUiConfig.formatHidden.
 */
public final class RawFormatMenu {

    private RawFormatMenu() {
    }

    public static final int[] IDS = {
            R.id.menu_bold, R.id.menu_italic, R.id.menu_mono, R.id.menu_strike, R.id.menu_underline,
            R.id.menu_spoiler, R.id.menu_quote, R.id.menu_link, R.id.menu_date, R.id.menu_regular
    };
    public static final String[] NAMES = {
            "Жирный", "Курсив", "Моноширинный", "Зачёркнутый", "Подчёркнутый",
            "Спойлер", "Цитата", "Ссылка", "Дата", "Обычный"
    };

    public static boolean shown(int index) {
        return (RawChatUiConfig.formatHidden.get() & (1 << index)) == 0;
    }

    public static void toggle(int index) {
        RawChatUiConfig.formatHidden.set(RawChatUiConfig.formatHidden.get() ^ (1 << index));
    }

    /** End of ChatActivity.fillActionModeMenu. */
    public static void filter(Menu menu) {
        int hidden = RawChatUiConfig.formatHidden.get();
        if (hidden == 0 || menu == null) {
            return;
        }
        for (int i = 0; i < IDS.length; i++) {
            if ((hidden & (1 << i)) != 0) {
                menu.removeItem(IDS[i]);
            }
        }
    }
}
