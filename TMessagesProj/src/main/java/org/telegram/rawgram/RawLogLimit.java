package org.telegram.rawgram;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** Picker for a log's size limit: presets, «без лимита» (0) or any number. */
public final class RawLogLimit {

    private static final int[] PRESETS = {100, 300, 1000, 5000};

    private RawLogLimit() {
    }

    public static void ask(BaseFragment fragment, String title, int current, Utilities.Callback<Integer> onChosen) {
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        CharSequence[] items = new CharSequence[PRESETS.length + 2];
        for (int i = 0; i < PRESETS.length; i++) {
            items[i] = (PRESETS[i] == current ? "● " : "") + PRESETS[i];
        }
        items[PRESETS.length] = (current <= 0 ? "● " : "") + "Без лимита";
        items[PRESETS.length + 1] = "Своё число…";
        fragment.showDialog(new AlertDialog.Builder(activity, fragment.getResourceProvider())
                .setTitle(title)
                .setItems(items, (d, which) -> {
                    if (which < PRESETS.length) {
                        onChosen.run(PRESETS[which]);
                    } else if (which == PRESETS.length) {
                        onChosen.run(0);
                    } else {
                        askNumber(fragment, title, current, onChosen);
                    }
                })
                .create());
    }

    private static void askNumber(BaseFragment fragment, String title, int current, Utilities.Callback<Integer> onChosen) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("0 — без лимита");
        if (current > 0) {
            input.setText(String.valueOf(current));
            input.setSelection(input.length());
        }
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, fragment.getResourceProvider()));
        LinearLayout layout = new LinearLayout(activity);
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        fragment.showDialog(new AlertDialog.Builder(activity, fragment.getResourceProvider())
                .setTitle(title)
                .setMessage("Сколько последних записей хранить. Большой лимит занимает больше памяти.")
                .setView(layout)
                .setPositiveButton("Готово", (d, w) -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        value = current;
                    }
                    onChosen.run(Math.max(0, value));
                })
                .setNegativeButton("Отмена", null)
                .create());
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }
}
