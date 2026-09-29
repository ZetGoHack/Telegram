package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * "Reroll until match": repeats an inline query and looks for a substring / regex
 * in the text, buttons or raw JSON of the selected result indices.
 */
public class RawReroll {

    public static class Options {
        public String pattern = "";
        public boolean regex;
        public boolean caseSensitive;
        // where to look: text group, buttons group, whole JSON
        public boolean inTitle = true;
        public boolean inDescription = true;
        public boolean inMessage = true;
        public boolean inButtonText = true;
        public boolean inButtonUrl = true;
        public boolean inButtonData = true;
        public boolean inJson;

        public boolean anyText() {
            return inTitle || inDescription || inMessage;
        }

        public boolean anyButtons() {
            return inButtonText || inButtonUrl || inButtonData;
        }
        /** Python-style index spec: "" = all, "0", "-1", "0,2", "1:4", "::2", "-3:". */
        public String indices = "";
        public int maxAttempts = 50;
        public int delayMs = 500;
        /** Seconds to wait for one answer before giving up on the attempt; 0 = wait forever. */
        public int timeoutSec = 60;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("pattern", pattern);
            o.put("regex", regex);
            o.put("caseSensitive", caseSensitive);
            o.put("inTitle", inTitle);
            o.put("inDescription", inDescription);
            o.put("inMessage", inMessage);
            o.put("inButtonText", inButtonText);
            o.put("inButtonUrl", inButtonUrl);
            o.put("inButtonData", inButtonData);
            o.put("inJson", inJson);
            o.put("indices", indices);
            o.put("maxAttempts", maxAttempts);
            o.put("delayMs", delayMs);
            o.put("timeoutSec", timeoutSec);
            return o;
        }

        static Options fromJson(JSONObject o) {
            Options opt = new Options();
            opt.pattern = o.optString("pattern", "");
            opt.regex = o.optBoolean("regex", false);
            opt.caseSensitive = o.optBoolean("caseSensitive", false);
            // older saves only had the two group flags
            boolean text = o.optBoolean("inText", true);
            boolean buttons = o.optBoolean("inButtons", true);
            opt.inTitle = o.optBoolean("inTitle", text);
            opt.inDescription = o.optBoolean("inDescription", text);
            opt.inMessage = o.optBoolean("inMessage", text);
            opt.inButtonText = o.optBoolean("inButtonText", buttons);
            opt.inButtonUrl = o.optBoolean("inButtonUrl", buttons);
            opt.inButtonData = o.optBoolean("inButtonData", buttons);
            opt.inJson = o.optBoolean("inJson", false);
            opt.indices = o.optString("indices", "");
            opt.maxAttempts = o.optInt("maxAttempts", 50);
            opt.delayMs = o.optInt("delayMs", 500);
            opt.timeoutSec = o.optInt("timeoutSec", 60);
            return opt;
        }
    }

    // ---- persistence: options are remembered per bot + query ----

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram", Context.MODE_PRIVATE);
    }

    public static String queryKey(TLRPC.TL_messages_getInlineBotResults req) {
        long botId = req.bot instanceof TLRPC.TL_inputUser ? ((TLRPC.TL_inputUser) req.bot).user_id : 0;
        return "reroll_" + botId + "_" + Integer.toHexString(String.valueOf(req.query).hashCode());
    }

    /** Last options used for exactly this query, or defaults. */
    public static Options load(String key) {
        String saved = prefs().getString(key, null);
        if (saved != null) {
            try {
                return Options.fromJson(new JSONObject(saved));
            } catch (Exception ignore) {
            }
        }
        return new Options();
    }

    public static void save(String key, Options options) {
        try {
            prefs().edit().putString(key, options.toJson().toString()).apply();
        } catch (Exception ignore) {
        }
    }

    // ---- python-like index selection ----

    /** Resolves "", "3", "-1", "0,2,5", "1:4", "::2", "-3:" (comma separated) against a list size. */
    public static List<Integer> parseIndices(String spec, int size) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        if (spec == null || spec.trim().isEmpty()) {
            for (int i = 0; i < size; i++) {
                out.add(i);
            }
            return new ArrayList<>(out);
        }
        for (String token : spec.split("[,\\s]+")) {
            token = token.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.contains(":")) {
                String[] parts = token.split(":", -1);
                if (parts.length > 3) {
                    throw new IllegalArgumentException("bad slice: " + token);
                }
                int step = parts.length == 3 && !parts[2].isEmpty() ? Integer.parseInt(parts[2]) : 1;
                if (step == 0) {
                    throw new IllegalArgumentException("slice step cannot be zero");
                }
                int start = sliceBound(parts[0], size, step, true);
                int stop = sliceBound(parts[1], size, step, false);
                for (int i = start; step > 0 ? i < stop : i > stop; i += step) {
                    out.add(i);
                }
            } else {
                int i = Integer.parseInt(token);
                if (i < 0) {
                    i += size;
                }
                if (i >= 0 && i < size) {
                    out.add(i);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Same normalisation as Python's slice.indices(). */
    private static int sliceBound(String text, int size, int step, boolean isStart) {
        if (text.isEmpty()) {
            if (isStart) {
                return step > 0 ? 0 : size - 1;
            }
            return step > 0 ? size : -1;
        }
        int v = Integer.parseInt(text);
        if (v < 0) {
            v += size;
            if (v < 0) {
                v = step > 0 ? 0 : -1;
            }
        } else if (v >= size) {
            v = step > 0 ? size : size - 1;
        }
        return v;
    }

    // ---- matching ----

    public static Pattern compile(Options options) {
        int flags = options.caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (options.regex) {
            return Pattern.compile(options.pattern, flags | Pattern.DOTALL);
        }
        return Pattern.compile(Pattern.quote(options.pattern), flags);
    }

    public static boolean matches(TLRPC.BotInlineResult result, Options options, Pattern pattern) {
        ArrayList<String> haystack = new ArrayList<>();
        if (options.inTitle) {
            add(haystack, result.title);
        }
        if (options.inDescription) {
            add(haystack, result.description);
        }
        if (options.inMessage && result.send_message != null) {
            add(haystack, result.send_message.message);
        }
        if (options.anyButtons() && result.send_message != null && result.send_message.reply_markup != null) {
            collectStrings(result.send_message.reply_markup, null, options, haystack, new IdentityHashMap<>(), 0);
        }
        if (options.inJson) {
            haystack.add(TLDumper.toJson(result));
        }
        for (String s : haystack) {
            if (pattern.matcher(s).find()) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<String> out, String s) {
        if (s != null && !s.isEmpty()) {
            out.add(s);
        }
    }

    /**
     * Strings of a keyboard, split by kind: field "text" = button caption, "url" = link,
     * anything else (callback data, switch query, …, byte[] decoded as UTF-8) = data.
     */
    private static void collectStrings(Object value, String fieldName, Options options, List<String> out, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || depth > 16) {
            return;
        }
        if (value instanceof String || value instanceof byte[]) {
            boolean wanted = "text".equals(fieldName) ? options.inButtonText
                    : "url".equals(fieldName) ? options.inButtonUrl
                    : options.inButtonData;
            if (wanted) {
                add(out, value instanceof String ? (String) value : new String((byte[]) value, StandardCharsets.UTF_8));
            }
            return;
        }
        if (seen.containsKey(value)) {
            return;
        }
        seen.put(value, true);
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                collectStrings(item, fieldName, options, out, seen, depth + 1);
            }
        } else if (value instanceof TLObject) {
            for (Field field : TLDumper.fieldsOf(value.getClass())) {
                try {
                    collectStrings(field.get(value), field.getName(), options, out, seen, depth + 1);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // ---- options dialog ----

    public static void showDialog(Context context, Options initial, Theme.ResourcesProvider resourcesProvider, Utilities.Callback<Options> onStart) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        EditText patternField = field(context, layout, "Искать (подстрока или regex)", initial.pattern, InputType.TYPE_CLASS_TEXT, resourcesProvider);
        TextCheckCell regexCell = check(context, layout, "Regex", initial.regex, resourcesProvider);
        TextCheckCell caseCell = check(context, layout, "Учитывать регистр", initial.caseSensitive, resourcesProvider);
        CheckGroup textGroup = new CheckGroup(context, layout, "В тексте", resourcesProvider,
                new String[]{"title", "description", "message"},
                new boolean[]{initial.inTitle, initial.inDescription, initial.inMessage});
        CheckGroup buttonsGroup = new CheckGroup(context, layout, "В кнопках", resourcesProvider,
                new String[]{"текст кнопки", "url", "callback data и прочее"},
                new boolean[]{initial.inButtonText, initial.inButtonUrl, initial.inButtonData});
        TextCheckCell jsonCell = check(context, layout, "Во всём JSON результата", initial.inJson, resourcesProvider);
        EditText indicesField = field(context, layout, "Индексы результатов (пусто = все; 0 · -1 · 0,2 · 1:4 · ::2)", initial.indices, InputType.TYPE_CLASS_TEXT, resourcesProvider);
        EditText attemptsField = field(context, layout, "Максимум попыток", String.valueOf(initial.maxAttempts), InputType.TYPE_CLASS_NUMBER, resourcesProvider);
        EditText delayField = field(context, layout, "Задержка между попытками, мс", String.valueOf(initial.delayMs), InputType.TYPE_CLASS_NUMBER, resourcesProvider);
        EditText timeoutField = field(context, layout, "Таймаут ответа бота, с (0 = ждать сколько угодно)", String.valueOf(initial.timeoutSec), InputType.TYPE_CLASS_NUMBER, resourcesProvider);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle("Reroll до совпадения");
        builder.setView(scrollView);
        builder.setNegativeButton("Отмена", null);
        builder.setPositiveButton("Старт", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            View start = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (start == null) {
                return;
            }
            start.setOnClickListener(v -> {
                Options o = new Options();
                o.pattern = patternField.getText().toString();
                o.regex = regexCell.isChecked();
                o.caseSensitive = caseCell.isChecked();
                o.inTitle = textGroup.isChecked(0);
                o.inDescription = textGroup.isChecked(1);
                o.inMessage = textGroup.isChecked(2);
                o.inButtonText = buttonsGroup.isChecked(0);
                o.inButtonUrl = buttonsGroup.isChecked(1);
                o.inButtonData = buttonsGroup.isChecked(2);
                o.inJson = jsonCell.isChecked();
                o.indices = indicesField.getText().toString().trim();
                o.maxAttempts = parseIntOr(attemptsField.getText().toString(), 50, 1, 10_000);
                o.delayMs = parseIntOr(delayField.getText().toString(), 500, 0, 600_000);
                o.timeoutSec = parseIntOr(timeoutField.getText().toString(), 60, 0, 3600);
                String problem = validate(o);
                if (problem != null) {
                    patternField.setError(problem);
                    AndroidUtilities.shakeViewSpring(patternField, 5);
                    return;
                }
                dialog.dismiss();
                onStart.run(o);
            });
        });
        dialog.show();
    }

    private static String validate(Options o) {
        if (o.pattern.isEmpty()) {
            return "Пустой шаблон";
        }
        if (!o.anyText() && !o.anyButtons() && !o.inJson) {
            return "Выбери, где искать";
        }
        try {
            compile(o);
        } catch (Exception e) {
            return "Regex: " + e.getMessage();
        }
        try {
            parseIndices(o.indices, 10);
        } catch (Exception e) {
            return "Индексы: " + e.getMessage();
        }
        return null;
    }

    private static int parseIntOr(String s, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(s.trim())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static EditText field(Context context, LinearLayout parent, String label, String value, int inputType, Theme.ResourcesProvider resourcesProvider) {
        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        labelView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        parent.addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 12, 24, 0));

        EditText editText = new EditText(context);
        editText.setText(value);
        editText.setInputType(inputType);
        editText.setSingleLine(true);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        parent.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));
        return editText;
    }

    /**
     * Power Saving style group: a row with the title, an "n/m" counter, an expand arrow and a switch
     * for the whole group; the arrow reveals one checkbox per item.
     */
    private static class CheckGroup {
        private final org.telegram.ui.Components.Switch groupSwitch;
        private final org.telegram.ui.Components.AnimatedTextView counter;
        private final android.widget.ImageView arrow;
        private final LinearLayout items;
        private final org.telegram.ui.Components.CheckBox2[] boxes;
        private boolean expanded;

        private final LinearLayout parent;

        CheckGroup(Context context, LinearLayout parent, String title, Theme.ResourcesProvider rp, String[] names, boolean[] checked) {
            this.parent = parent;
            int textColor = Theme.getColor(Theme.key_dialogTextBlack, rp);

            android.widget.FrameLayout header = new android.widget.FrameLayout(context);
            header.setBackground(Theme.getSelectorDrawable(false));
            parent.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(textColor);
            titleRow.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            counter = new org.telegram.ui.Components.AnimatedTextView(context, false, true, true);
            counter.setAnimationProperties(.35f, 0, 200, org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);
            counter.setTypeface(AndroidUtilities.bold());
            counter.setTextSize(AndroidUtilities.dp(14));
            counter.setTextColor(textColor);
            titleRow.addView(counter, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 20, 0, android.view.Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
            arrow = new android.widget.ImageView(context);
            arrow.setImageResource(org.telegram.messenger.R.drawable.arrow_more);
            arrow.setColorFilter(new android.graphics.PorterDuffColorFilter(textColor, android.graphics.PorterDuff.Mode.MULTIPLY));
            titleRow.addView(arrow, LayoutHelper.createLinear(16, 16, 0, android.view.Gravity.CENTER_VERTICAL, 2, 0, 0, 0));
            header.addView(titleRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL, 22, 0, 70, 0));

            groupSwitch = new org.telegram.ui.Components.Switch(context, rp);
            groupSwitch.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
            header.addView(groupSwitch, LayoutHelper.createFrame(37, 40, android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL, 0, 0, 22, 0));

            items = new LinearLayout(context);
            items.setOrientation(LinearLayout.VERTICAL);
            items.setVisibility(View.GONE);
            parent.addView(items, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            boxes = new org.telegram.ui.Components.CheckBox2[names.length];
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                android.widget.FrameLayout row = new android.widget.FrameLayout(context);
                row.setBackground(Theme.getSelectorDrawable(false));
                org.telegram.ui.Components.CheckBox2 box = new org.telegram.ui.Components.CheckBox2(context, 21, rp);
                box.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
                box.setDrawUnchecked(true);
                box.setDrawBackgroundAsArc(10);
                box.setChecked(checked[i], false);
                boxes[i] = box;
                row.addView(box, LayoutHelper.createFrame(21, 21, android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL, 42, 0, 0, 0));
                TextView name = new TextView(context);
                name.setText(names[i]);
                name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                name.setTextColor(textColor);
                row.addView(name, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL, 78, 0, 22, 0));
                row.setOnClickListener(v -> {
                    boxes[index].setChecked(!boxes[index].isChecked(), true);
                    sync(true);
                });
                items.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
            }

            titleRow.setOnClickListener(v -> toggleExpanded());
            header.setOnClickListener(v -> toggleExpanded());
            groupSwitch.setOnClickListener(v -> {
                boolean on = !groupSwitch.isChecked();
                for (org.telegram.ui.Components.CheckBox2 box : boxes) {
                    box.setChecked(on, true);
                }
                sync(true);
            });
            sync(false);
        }

        private void toggleExpanded() {
            expanded = !expanded;
            RawAnim.layout(parent);
            items.setVisibility(expanded ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(expanded ? 180 : 0).setDuration(240)
                    .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }

        private void sync(boolean animated) {
            int on = 0;
            for (org.telegram.ui.Components.CheckBox2 box : boxes) {
                if (box.isChecked()) {
                    on++;
                }
            }
            counter.setText(on + "/" + boxes.length, animated);
            groupSwitch.setChecked(on > 0, animated);
        }

        boolean isChecked(int index) {
            return boxes[index].isChecked();
        }
    }

    private static TextCheckCell check(Context context, LinearLayout parent, String text, boolean checked, Theme.ResourcesProvider resourcesProvider) {
        TextCheckCell cell = new TextCheckCell(context, resourcesProvider);
        cell.setTextAndCheck(text, checked, false);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> cell.setChecked(!cell.isChecked()));
        parent.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }
}
