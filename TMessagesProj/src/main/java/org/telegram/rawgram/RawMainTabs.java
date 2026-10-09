package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Order of the bottom tabs (Nagram's MainTabsOrder). Slots: 0 chats, 1 contacts, 2 calls / settings, 3 profile; the
 * order is a string of slot digits in RawUiConfig, read once when MainTabsActivity loads (applies after a restart).
 */
public final class RawMainTabs {

    public static final String DEFAULT = "0123";
    private static final String[] NAMES = {"Чаты", "Контакты", "Звонки / Настройки", "Профиль"};

    private RawMainTabs() {
    }

    /** Valid order string: each slot once. */
    public static String order() {
        String o = RawUiConfig.mainTabsOrder();
        if (o == null || o.length() != 4 || !o.contains("0") || !o.contains("1") || !o.contains("2") || !o.contains("3")) {
            return DEFAULT;
        }
        return o;
    }

    /** Pager position of each slot (-1 for hidden contacts). */
    public static int[] positions(boolean hideContacts) {
        int[] pos = {-1, -1, -1, -1};
        String o = order();
        int p = 0;
        for (int i = 0; i < 4; i++) {
            int slot = o.charAt(i) - '0';
            if (slot == 1 && hideContacts) {
                continue;
            }
            pos[slot] = p++;
        }
        return pos;
    }

    public static int count(boolean hideContacts) {
        return hideContacts ? 3 : 4;
    }

    public static String summary() {
        String o = order();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (sb.length() > 0) sb.append(", ");
            String name = NAMES[o.charAt(i) - '0'];
            sb.append(name.contains(" / ") ? "Звонки" : name);
        }
        return sb.toString();
    }

    /** A dialog with the four tabs and up / down arrows. */
    public static void showEditor(Context context, Runnable onChanged) {
        final StringBuilder order = new StringBuilder(order());
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(4));
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < 4; i++) {
                final int index = i;
                FrameLayout row = new FrameLayout(context);
                TextView name = new TextView(context);
                name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                name.setText((i + 1) + ". " + NAMES[order.charAt(i) - '0']);
                row.addView(name, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 24, 0, 100, 0));
                row.addView(arrow(context, R.drawable.msg_go_up, i > 0, () -> {
                    swap(order, index, index - 1);
                    rebuild[0].run();
                }), LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 60, 0));
                row.addView(arrow(context, R.drawable.msg_go_down, i < 3, () -> {
                    swap(order, index, index + 1);
                    rebuild[0].run();
                }), LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
                list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            }
        };
        rebuild[0].run();
        new AlertDialog.Builder(context)
                .setTitle("Порядок вкладок")
                .setView(list)
                .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                    RawUiConfig.setMainTabsOrder(order.toString());
                    if (onChanged != null) onChanged.run();
                })
                .setNeutralButton("Сбросить", (d, w) -> {
                    RawUiConfig.setMainTabsOrder(DEFAULT);
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static ImageView arrow(Context context, int icon, boolean enabled, Runnable action) {
        ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setImageResource(icon);
        v.setColorFilter(Theme.getColor(enabled ? Theme.key_dialogIcon : Theme.key_dialogTextGray4));
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
        v.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private static void swap(StringBuilder s, int a, int b) {
        char c = s.charAt(a);
        s.setCharAt(a, s.charAt(b));
        s.setCharAt(b, c);
    }
}
