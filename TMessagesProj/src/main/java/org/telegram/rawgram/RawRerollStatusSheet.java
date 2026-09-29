package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;

/** Live status of the active reroll: state, counters, options, controls and per-attempt history. */
public class RawRerollStatusSheet extends BottomSheet {

    private final RawRerollController controller;
    private final TextView stateView;
    private final TextView statsView;
    private final TextView optionsView;
    private final TextView historyView;
    private final LinearLayout actions;
    private final Runnable listener = this::update;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isShowing() && controller.isRunning()) {
                updateStats();
                AndroidUtilities.runOnUIThread(this, 250);
            }
        }
    };

    public RawRerollStatusSheet(Context context, RawRerollController controller, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        this.controller = controller;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        String botName = controller.bot != null && controller.bot.username != null ? "@" + controller.bot.username : "inline";
        title.setText("Reroll · " + botName + " «" + controller.query + "»");
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        stateView = new TextView(context);
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        stateView.setTypeface(AndroidUtilities.bold());
        root.addView(stateView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        statsView = new TextView(context);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statsView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        root.addView(statsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 0));

        optionsView = new TextView(context);
        optionsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        optionsView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        root.addView(optionsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 8));

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8), 0);
        actionsScroll.addView(actions);
        root.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 10));

        TextView historyHeader = new TextView(context);
        historyHeader.setText("История попыток");
        historyHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        historyHeader.setTypeface(AndroidUtilities.bold());
        historyHeader.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        root.addView(historyHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 4));

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.4f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        historyView = new TextView(context);
        historyView.setTypeface(Typeface.MONOSPACE);
        historyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        historyView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        historyView.setTextIsSelectable(true);
        historyView.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        scrollView.addView(historyView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(root);
        controller.addListener(listener);
        setOnDismissListener(d -> controller.removeListener(listener));
        update();
    }

    @Override
    public void show() {
        super.show();
        AndroidUtilities.runOnUIThread(ticker, 250);
    }

    private TextView chip(String text, boolean primary, Runnable onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        if (primary) {
            chip.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), accent, Theme.multAlpha(accent, 0.8f)));
        } else {
            chip.setTextColor(accent);
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), Theme.multAlpha(accent, 0.12f), Theme.multAlpha(accent, 0.24f)));
        }
        chip.setOnClickListener(v -> onClick.run());
        actions.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        return chip;
    }

    private void update() {
        if (!isShowing() && getWindow() == null) {
            return;
        }
        switch (controller.state) {
            case RawRerollController.STATE_RUNNING:
                stateView.setText("⟳ идёт поиск " + controller.getPatternLabel());
                stateView.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
                break;
            case RawRerollController.STATE_MATCHED:
                stateView.setText("✓ совпадение в [" + controller.matchedIndex + "] на попытке " + controller.attempt
                        + (controller.matched != null && controller.matched.title != null ? " · " + controller.matched.title : ""));
                stateView.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
                break;
            case RawRerollController.STATE_NOT_FOUND:
                stateView.setText("✗ не найдено за " + controller.attempt + " попыток");
                stateView.setTextColor(getThemedColor(Theme.key_text_RedBold));
                break;
            case RawRerollController.STATE_ERROR:
                stateView.setText("! ошибка на попытке " + controller.attempt);
                stateView.setTextColor(getThemedColor(Theme.key_text_RedBold));
                break;
            default:
                stateView.setText("❚❚ остановлен на попытке " + controller.attempt);
                stateView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
                break;
        }
        updateStats();

        RawReroll.Options o = controller.options;
        StringBuilder sb = new StringBuilder();
        sb.append(o.regex ? "regex" : "подстрока").append(o.caseSensitive ? " · с учётом регистра" : " · без учёта регистра");
        sb.append(" · где: ");
        StringBuilder where = new StringBuilder();
        if (o.inText) where.append("текст");
        if (o.inButtons) where.append(where.length() > 0 ? ", " : "").append("кнопки");
        if (o.inJson) where.append(where.length() > 0 ? ", " : "").append("JSON");
        sb.append(where);
        sb.append(" · индексы: ").append(o.indices.isEmpty() ? "все" : o.indices);
        sb.append(" · задержка ").append(o.delayMs).append(" мс");
        optionsView.setText(sb);

        actions.removeAllViews();
        if (controller.isRunning()) {
            chip("Стоп", true, controller::stop);
        } else {
            chip("Заново", true, controller::restart);
        }
        chip("Параметры…", false, () -> {
            dismiss();
            controller.editOptions();
        });
        if (controller.matched != null) {
            chip("Открыть совпадение", false, () -> {
                dismiss();
                controller.openMatch();
            });
        }
        chip("Закрыть", false, () -> {
            dismiss();
            controller.dismiss();
        });

        StringBuilder h = new StringBuilder();
        if (controller.history.isEmpty()) {
            h.append(controller.isRunning() ? "первая попытка…" : "попыток не было");
        }
        for (int i = controller.history.size() - 1; i >= 0; i--) {
            RawRerollController.Attempt a = controller.history.get(i);
            h.append(String.format(Locale.US, "#%-3d %5d ms  ", a.number, a.tookMs));
            if (a.error != null) {
                h.append("! ").append(a.error);
            } else {
                h.append(a.results).append(" res  ");
                if (a.matchIndex >= 0) {
                    h.append("✓ [").append(a.matchIndex).append("] ").append(a.matchTitle);
                    if (a.matchCount > 1) {
                        h.append(" (+").append(a.matchCount - 1).append(")");
                    }
                } else {
                    h.append("✗");
                }
                h.append("  q…").append(tail(a.queryId));
                if (a.sameQueryId) {
                    h.append(" (тот же query_id — кэш сервера?)");
                }
            }
            h.append('\n');
        }
        historyView.setText(h);
    }

    private void updateStats() {
        long elapsed = controller.getElapsedMs();
        int max = controller.options != null ? controller.options.maxAttempts : 0;
        statsView.setText(String.format(Locale.US, "попытки: %d / %d · прошло %.1f с", controller.attempt, max, elapsed / 1000f));
    }

    private static String tail(long queryId) {
        String s = Long.toString(queryId);
        return s.length() > 6 ? s.substring(s.length() - 6) : s;
    }
}
