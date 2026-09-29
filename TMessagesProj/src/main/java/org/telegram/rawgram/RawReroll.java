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
        public boolean inText = true;
        public boolean inButtons = true;
        public boolean inJson;
        /** Python-style index spec: "" = all, "0", "-1", "0,2", "1:4", "::2", "-3:". */
        public String indices = "";
        public int maxAttempts = 50;
        public int delayMs = 500;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("pattern", pattern);
            o.put("regex", regex);
            o.put("caseSensitive", caseSensitive);
            o.put("inText", inText);
            o.put("inButtons", inButtons);
            o.put("inJson", inJson);
            o.put("indices", indices);
            o.put("maxAttempts", maxAttempts);
            o.put("delayMs", delayMs);
            return o;
        }

        static Options fromJson(JSONObject o) {
            Options opt = new Options();
            opt.pattern = o.optString("pattern", "");
            opt.regex = o.optBoolean("regex", false);
            opt.caseSensitive = o.optBoolean("caseSensitive", false);
            opt.inText = o.optBoolean("inText", true);
            opt.inButtons = o.optBoolean("inButtons", true);
            opt.inJson = o.optBoolean("inJson", false);
            opt.indices = o.optString("indices", "");
            opt.maxAttempts = o.optInt("maxAttempts", 50);
            opt.delayMs = o.optInt("delayMs", 500);
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
        if (options.inText) {
            add(haystack, result.title);
            add(haystack, result.description);
            if (result.send_message != null) {
                add(haystack, result.send_message.message);
            }
        }
        if (options.inButtons && result.send_message != null && result.send_message.reply_markup != null) {
            collectStrings(result.send_message.reply_markup, haystack, new IdentityHashMap<>(), 0);
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

    /** Every string (and UTF-8 decoded byte[], e.g. callback data) inside a TL object. */
    private static void collectStrings(Object value, List<String> out, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || depth > 16) {
            return;
        }
        if (value instanceof String) {
            add(out, (String) value);
            return;
        }
        if (value instanceof byte[]) {
            add(out, new String((byte[]) value, StandardCharsets.UTF_8));
            return;
        }
        if (seen.containsKey(value)) {
            return;
        }
        seen.put(value, true);
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                collectStrings(item, out, seen, depth + 1);
            }
        } else if (value instanceof TLObject) {
            for (Field field : TLDumper.fieldsOf(value.getClass())) {
                try {
                    collectStrings(field.get(value), out, seen, depth + 1);
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
        TextCheckCell textCell = check(context, layout, "В тексте (title, description, message)", initial.inText, resourcesProvider);
        TextCheckCell buttonsCell = check(context, layout, "В кнопках (текст, url, callback data)", initial.inButtons, resourcesProvider);
        TextCheckCell jsonCell = check(context, layout, "Во всём JSON результата", initial.inJson, resourcesProvider);
        EditText indicesField = field(context, layout, "Индексы результатов (пусто = все; 0 · -1 · 0,2 · 1:4 · ::2)", initial.indices, InputType.TYPE_CLASS_TEXT, resourcesProvider);
        EditText attemptsField = field(context, layout, "Максимум попыток", String.valueOf(initial.maxAttempts), InputType.TYPE_CLASS_NUMBER, resourcesProvider);
        EditText delayField = field(context, layout, "Задержка между попытками, мс", String.valueOf(initial.delayMs), InputType.TYPE_CLASS_NUMBER, resourcesProvider);

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
                o.inText = textCell.isChecked();
                o.inButtons = buttonsCell.isChecked();
                o.inJson = jsonCell.isChecked();
                o.indices = indicesField.getText().toString().trim();
                o.maxAttempts = parseIntOr(attemptsField.getText().toString(), 50, 1, 10_000);
                o.delayMs = parseIntOr(delayField.getText().toString(), 500, 0, 600_000);
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
        if (!o.inText && !o.inButtons && !o.inJson) {
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

    private static TextCheckCell check(Context context, LinearLayout parent, String text, boolean checked, Theme.ResourcesProvider resourcesProvider) {
        TextCheckCell cell = new TextCheckCell(context, resourcesProvider);
        cell.setTextAndCheck(text, checked, false);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> cell.setChecked(!cell.isChecked()));
        parent.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }
}
