package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * "Компактное меню сообщения": reply / delete / copy / edit leave the message menu list and become a row of
 * icon-only buttons at the bottom of the menu (long press shows the label). Everything else stays a list.
 * Off (default) — the stock menu.
 */
public final class RawMessageMenu {

    /** The quick row, in this order. */
    private static final int[] QUICK = {
            ChatActivity.OPTION_REPLY,
            ChatActivity.OPTION_DELETE,
            ChatActivity.OPTION_COPY,
            ChatActivity.OPTION_EDIT,
    };

    /** Actions pulled out of the menu lists for the bottom row. */
    public static final class Quick {
        final ArrayList<Integer> options = new ArrayList<>();
        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayList<Integer> icons = new ArrayList<>();
    }

    private RawMessageMenu() {
    }

    /**
     * Called from ChatActivity.createMenu right before the list items are created. When the setting is on and at
     * least two quick actions are present, removes them from {@code options/items/icons} and returns them;
     * otherwise leaves the lists untouched and returns null (stock menu).
     * {@code welcomeRevert}: the menu shows "revert welcome message" instead of delete — delete stays stock then.
     */
    public static Quick extract(MessageObject selected, ArrayList<Integer> options, ArrayList<CharSequence> items,
                                ArrayList<Integer> icons, boolean welcomeRevert) {
        if (!RawChatUiConfig.menuCompact.get() || options == null || items == null || icons == null
                || options.size() != items.size() || options.size() != icons.size()) {
            return null;
        }
        // delete with a subtext (auto-delete countdown, paid post lock) keeps its full list item
        boolean keepDelete = welcomeRevert || selected != null && selected.messageOwner != null
                && (selected.messageOwner.ttl_period != 0 || selected.isPaidSuggestedPostProtected());
        Quick quick = new Quick();
        for (int option : QUICK) {
            if (option == ChatActivity.OPTION_DELETE && keepDelete) {
                continue;
            }
            int at = options.indexOf(option);
            if (at >= 0) {
                quick.options.add(option);
                quick.labels.add(items.get(at));
                quick.icons.add(icons.get(at));
            }
        }
        if (quick.options.size() < 2) {
            return null;
        }
        for (Integer option : quick.options) {
            int at = options.indexOf(option);
            options.remove(at);
            items.remove(at);
            icons.remove(at);
        }
        return quick;
    }

    /**
     * Adds the icon row at the bottom of the menu (with a gap above it when something is above).
     * {@code onClick} runs the option exactly like a list item does (ChatActivity.processSelectedOption).
     */
    public static void addRow(ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout, Quick quick,
                              Theme.ResourcesProvider resources, Utilities.Callback<Integer> onClick) {
        if (popupLayout == null || quick == null || quick.options.isEmpty()) {
            return;
        }
        Context context = popupLayout.getContext();
        int count = popupLayout.getViewsCount();
        View last = count > 0 ? popupLayout.getItemAt(count - 1) : null;
        boolean hasAbove = count > 0;
        if (hasAbove && !(last instanceof ActionBarPopupWindow.GapView)) {
            popupLayout.addView(new ActionBarPopupWindow.GapView(context, resources), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int iconColor = Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resources);
        int selector = Theme.getColor(Theme.key_listSelector, resources);
        int n = quick.options.size();
        int rad = 6;
        for (int i = 0; i < n; i++) {
            final int option = quick.options.get(i);
            final CharSequence label = quick.labels.get(i);
            ImageView button = new ImageView(context);
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setImageResource(quick.icons.get(i));
            button.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
            button.setMinimumWidth(dp(48));
            // round only the outer corners of the row (it sits at the bottom of the menu, or alone)
            boolean top = !hasAbove;
            int tl = top && i == 0 ? rad : 0;
            int tr = top && i == n - 1 ? rad : 0;
            int bl = i == 0 ? rad : 0;
            int br = i == n - 1 ? rad : 0;
            button.setBackground(Theme.createRadSelectorDrawable(selector, tl, tr, br, bl));
            if (!TextUtils.isEmpty(label)) {
                button.setContentDescription(label);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    button.setTooltipText(label);
                } else {
                    button.setOnLongClickListener(v -> {
                        try {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        } catch (Exception ignore) {}
                        Toast toast = Toast.makeText(v.getContext(), label, Toast.LENGTH_SHORT);
                        toast.setGravity(Gravity.TOP | Gravity.START, Math.max(0, locationX(v)), Math.max(0, locationY(v) - dp(48)));
                        toast.show();
                        return true;
                    });
                }
            }
            button.setOnClickListener(v -> {
                if (onClick != null) {
                    onClick.run(option);
                }
            });
            RawMotion.pressable(button, 0.86f);
            row.addView(button, LayoutHelper.createLinear(0, 48, 1f));
        }
        popupLayout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        // the icons pop in left to right as the menu finishes unfolding
        RawMotion.popRowOnShow(row, 120, 40);
    }

    private static int locationX(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[0];
    }

    private static int locationY(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[1];
    }
}
