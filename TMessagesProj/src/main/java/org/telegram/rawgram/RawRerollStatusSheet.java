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
import org.telegram.tgnet.TLRPC;
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
    private final LinearLayout historyList;
    private final java.util.ArrayList<TextView> historyRows = new java.util.ArrayList<>();
    private TextView historyEmpty;
    private final LinearLayout actions;
    private final Runnable listener = this::update;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isShowing()) {
                // live clock, FLOOD_WAIT countdown and "N с назад" ages
                if (controller.isRunning()) {
                    updateState();
                    updateStats();
                }
                updateHistoryTexts();
                AndroidUtilities.runOnUIThread(this, controller.isRunning() ? 250 : 1000);
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
        historyList = new LinearLayout(context);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historyList.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        scrollView.addView(historyList, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
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
        updateState();
        updateRest();
    }

    private void updateState() {
        switch (controller.state) {
            case RawRerollController.STATE_RUNNING:
                int wait = controller.getWaitSecondsLeft();
                long waiting = (android.os.SystemClock.elapsedRealtime() - controller.attemptSentAt) / 1000;
                boolean awaiting = controller.awaitingAnswer && waiting >= 2;
                stateView.setText(wait > 0
                        ? "⏳ FLOOD_WAIT: жду " + wait + " с, потом продолжу поиск " + controller.getPatternLabel()
                        : awaiting
                        ? "⟳ попытка " + controller.attempt + ": жду ответ бота " + waiting + " с"
                            + (controller.options.timeoutSec > 0 ? " из " + controller.options.timeoutSec : "")
                        : "⟳ идёт поиск " + controller.getPatternLabel());
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
    }

    private void updateRest() {
        updateStats();

        RawReroll.Options o = controller.options;
        StringBuilder sb = new StringBuilder();
        sb.append(o.regex ? "regex" : "подстрока").append(o.caseSensitive ? " · с учётом регистра" : " · без учёта регистра");
        sb.append(" · где: ");
        StringBuilder where = new StringBuilder();
        String[] names = {"title", "description", "message", "текст кнопок", "url", "data кнопок"};
        boolean[] on = {o.inTitle, o.inDescription, o.inMessage, o.inButtonText, o.inButtonUrl, o.inButtonData};
        for (int i = 0; i < names.length; i++) {
            if (on[i]) where.append(where.length() > 0 ? ", " : "").append(names[i]);
        }
        if (o.inJson) where.append(where.length() > 0 ? ", " : "").append("JSON");
        sb.append(where);
        sb.append(" · индексы: ").append(o.indices.isEmpty() ? "все" : o.indices);
        sb.append(" · задержка ").append(o.delayMs).append(" мс");
        if (controller.floodWaits > 0) {
            sb.append(" · FLOOD_WAIT ×").append(controller.floodWaits);
        }
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

        rebuildHistory();
    }

    private void updateStats() {
        long elapsed = controller.getElapsedMs();
        int max = controller.options != null ? controller.options.maxAttempts : 0;
        statsView.setText(String.format(Locale.US, "попытки: %d / %d · прошло %.1f с", controller.attempt, max, elapsed / 1000f));
    }

    // ---- interactive history: one row per attempt, newest on top ----

    private RawRerollController.Attempt expanded;
    private int expandedSelection = -1;

    private void rebuildHistory() {
        int count = controller.history.size();
        if (count == historyRows.size() && (count > 0 || historyEmpty != null)) {
            updateHistoryTexts();
            return;
        }
        historyList.removeAllViews();
        historyRows.clear();
        historyEmpty = null;
        if (count == 0) {
            historyEmpty = row();
            historyEmpty.setText(controller.isRunning() ? "первая попытка…" : "попыток не было");
            historyList.addView(historyEmpty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));
            return;
        }
        for (int i = count - 1; i >= 0; i--) {
            final RawRerollController.Attempt a = controller.history.get(i);
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            TextView header = row();
            card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            boolean interactive = a.response != null;
            card.setBackground(cardBackground(interactive, a == expanded));
            if (interactive) {
                header.setOnClickListener(v -> {
                    if (expanded == a) {
                        expanded = null;
                    } else {
                        expanded = a;
                        expandedSelection = a.matchIndex >= 0 ? a.matchIndex : 0;
                    }
                    forceRebuildHistory();
                });
                if (a == expanded) {
                    addExpandedControls(card, a);
                }
            }
            historyRows.add(header);
            historyList.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
        }
        updateHistoryTexts();
    }

    private void forceRebuildHistory() {
        historyRows.clear();
        historyEmpty = null;
        rebuildHistory();
    }

    private android.graphics.drawable.Drawable cardBackground(boolean interactive, boolean selected) {
        int base = getThemedColor(Theme.key_dialogTextBlack);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(10));
        if (!interactive) {
            bg.setColor(Theme.multAlpha(base, 0.03f));
        } else if (selected) {
            bg.setColor(Theme.multAlpha(accent, 0.08f));
            bg.setStroke(AndroidUtilities.dp(1.5f), accent);
        } else {
            bg.setColor(Theme.multAlpha(base, 0.04f));
            bg.setStroke(AndroidUtilities.dp(1), Theme.multAlpha(base, 0.18f));
        }
        return bg;
    }

    /** Expanded attempt: its results as chips (the match highlighted), then actions for the selected one. */
    private void addExpandedControls(LinearLayout card, RawRerollController.Attempt a) {
        final java.util.ArrayList<TLRPC.BotInlineResult> visible = visibleResults(a.response);
        if (expandedSelection < 0 || expandedSelection >= visible.size()) {
            expandedSelection = visible.isEmpty() ? -1 : 0;
        }

        LinearLayout results = chipRow(card);
        for (int i = 0; i < visible.size(); i++) {
            final int index = i;
            boolean selected = i == expandedSelection;
            TextView chip = smallChip("[" + i + "] " + ellipsize(title(visible.get(i)), 22) + (i == a.matchIndex ? " ✓" : ""), selected);
            chip.setOnClickListener(v -> {
                expandedSelection = index;
                forceRebuildHistory();
            });
            results.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }

        LinearLayout actionsRow = chipRow(card);
        if (expandedSelection >= 0) {
            final TLRPC.BotInlineResult result = visible.get(expandedSelection);
            addActionChip(actionsRow, "Raw и превью", () -> {
                dismiss();
                RawInlineResultViewer.show(getContext(), controller.currentAccount, controller.adapter, result, controller.onSend, resourcesProvider, controller.host);
            });
            if (controller.onSend != null) {
                addActionChip(actionsRow, "Отправить сюда", () -> {
                    dismiss();
                    controller.sendHere(result);
                });
            }
            addActionChip(actionsRow, "В другой чат…", () -> {
                dismiss();
                controller.sendToOtherChat(result);
            });
        }
        addActionChip(actionsRow, "Весь ответ", () -> {
            dismiss();
            new RawObjectSheet(getContext(), controller.currentAccount, "Ответ · попытка #" + a.number, a.response, resourcesProvider).show();
        });

        TextView hint = new TextView(getContext());
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        hint.setText("Сколько живёт query_id, решает сервер: если он уже забыт, отправка вернёт QUERY_ID_INVALID.");
        card.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 2, 12, 8));
    }

    private LinearLayout chipRow(LinearLayout card) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        scroll.addView(row);
        card.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        return row;
    }

    private TextView smallChip(String text, boolean selected) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        int base = getThemedColor(Theme.key_dialogTextBlack);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        if (selected) {
            chip.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(15), accent, Theme.multAlpha(accent, 0.8f)));
        } else {
            chip.setTextColor(base);
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(15), Theme.multAlpha(base, 0.07f), Theme.multAlpha(base, 0.14f)));
        }
        return chip;
    }

    private void addActionChip(LinearLayout row, String text, Runnable onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        chip.setTextColor(accent);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(16));
        bg.setStroke(AndroidUtilities.dp(1.5f), Theme.multAlpha(accent, 0.6f));
        bg.setColor(Theme.multAlpha(accent, 0.06f));
        chip.setBackground(bg);
        chip.setOnClickListener(v -> onClick.run());
        row.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 6, 0));
    }

    private static String ellipsize(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private TextView row() {
        TextView row = new TextView(getContext());
        row.setTypeface(Typeface.MONOSPACE);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        row.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        return row;
    }

    private void updateHistoryTexts() {
        int count = controller.history.size();
        if (count != historyRows.size()) {
            return;
        }
        for (int r = 0; r < historyRows.size(); r++) {
            RawRerollController.Attempt a = controller.history.get(count - 1 - r);
            historyRows.get(r).setText(describe(a));
        }
    }

    private CharSequence describe(RawRerollController.Attempt a) {
        StringBuilder h = new StringBuilder();
        h.append(String.format(Locale.US, "#%-3d %5d ms  ", a.number, a.tookMs));
        if (a.floodWait > 0) {
            h.append("⏳ ").append(a.error).append(" — пауза ").append(a.floodWait).append(" с");
        } else if (a.error != null) {
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
                h.append(" (тот же query_id)");
            }
            h.append("\n      ").append(age(a)).append("  ›");
        }
        return h;
    }

    private static String age(RawRerollController.Attempt a) {
        long seconds = (android.os.SystemClock.elapsedRealtime() - a.receivedAt) / 1000;
        String ago = seconds < 60 ? seconds + " с назад" : (seconds / 60) + " мин назад";
        int ttl = a.response != null ? a.response.cache_time : 0;
        return ago + (ttl > 0 ? " · cache_time " + ttl + " с" + (seconds > ttl ? " (истёк)" : "") : "");
    }

    private java.util.ArrayList<TLRPC.BotInlineResult> visibleResults(TLRPC.messages_BotResults response) {
        java.util.ArrayList<TLRPC.BotInlineResult> visible = new java.util.ArrayList<>();
        for (TLRPC.BotInlineResult r : response.results) {
            if (!org.telegram.ui.Adapters.MentionsAdapter.rawgramIsHiddenByClient(r)) {
                visible.add(r);
            }
        }
        return visible;
    }

    private static String title(TLRPC.BotInlineResult r) {
        return r.title != null && !r.title.isEmpty() ? r.title : String.valueOf(r.id);
    }

    private static String tail(long queryId) {
        String s = Long.toString(queryId);
        return s.length() > 6 ? s.substring(s.length() - 6) : s;
    }
}
