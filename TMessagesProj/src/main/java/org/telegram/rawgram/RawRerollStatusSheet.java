package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
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
    private final ScrollView scrollView;
    private final TextView newPill;
    private boolean newPillShown;
    private int unseenNew;
    // once the sheet has seen a running reroll the history area keeps its full height, so the sheet never grows per attempt
    private boolean reserveHistory;
    // card whose on-screen position must survive the next layout (new cards inserted above it)
    private View scrollAnchor;
    private int scrollAnchorTop;
    private final java.util.ArrayList<TextView> historyRows = new java.util.ArrayList<>();
    private TextView historyEmpty;
    private final LinearLayout actions;
    private final Runnable listener = this::update;
    private String actionsKey;
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
        // fixed one-line height: the text changes several times a second and must never re-wrap the sheet
        stateView.setSingleLine(true);
        stateView.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(stateView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        statsView = new TextView(context);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statsView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        statsView.setSingleLine(true);
        statsView.setEllipsize(TextUtils.TruncateAt.END);
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

        reserveHistory = controller.isRunning();
        scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.4f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, reserveHistory ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                if (scrollAnchor != null) {
                    int delta = scrollAnchor.getTop() - scrollAnchorTop;
                    scrollAnchor = null;
                    if (delta != 0) {
                        // same frame as the insertion, so what the user looks at never moves
                        scrollTo(0, getScrollY() + delta);
                        // a running fling works in absolute positions and would undo the shift: stop it
                        smoothScrollBy(0, 0);
                    }
                }
            }

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                if (t <= AndroidUtilities.dp(8)) {
                    hideNewPill();
                }
            }
        };
        historyList = new LinearLayout(context);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historyList.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        scrollView.addView(historyList, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        FrameLayout historyFrame = new FrameLayout(context);
        historyFrame.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // "N новых ↑": attempts that arrived above while the user was reading further down
        newPill = new TextView(context);
        newPill.setGravity(Gravity.CENTER);
        newPill.setSingleLine(true);
        newPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        newPill.setTypeface(AndroidUtilities.bold());
        newPill.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        newPill.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        newPill.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14), accent, Theme.multAlpha(accent, 0.8f)));
        newPill.setVisibility(View.GONE);
        newPill.setOnClickListener(v -> {
            scrollView.smoothScrollTo(0, 0);
            hideNewPill();
        });
        historyFrame.addView(newPill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        root.addView(historyFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(root);
        RawMotion.reveal(root, historyList);
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
        if (controller.isRunning() && !reserveHistory) {
            reserveHistory = true;
            scrollView.requestLayout();
        }
        updateState();
        updateRest();
    }

    private void updateState() {
        switch (controller.state) {
            case RawRerollController.STATE_RUNNING:
                int wait = controller.getWaitSecondsLeft();
                long waiting = (android.os.SystemClock.elapsedRealtime() - controller.attemptSentAt) / 1000;
                // the live part goes first, the pattern last: a long pattern gets ellipsized, never the timer
                stateView.setText(wait > 0
                        ? "⏳ FLOOD_WAIT: жду " + wait + " с · " + controller.getPatternLabel()
                        : controller.awaitingAnswer
                        ? "⟳ попытка " + controller.attempt + ": жду ответ " + waiting + " с"
                            + (controller.options.timeoutSec > 0 ? " из " + controller.options.timeoutSec : "")
                            + " · " + controller.getPatternLabel()
                        : "⟳ попытка " + controller.attempt + ": идёт поиск · " + controller.getPatternLabel());
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
        // nothing here changes during a run (the FLOOD_WAIT counter lives in the stats line), so it never re-wraps
        optionsView.setText(sb);

        // the chips only change with the state; rebuilding them on every attempt made the row blink
        String actionsKey = controller.isRunning() + "/" + (controller.matched != null);
        if (actionsKey.equals(this.actionsKey)) {
            rebuildHistory();
            return;
        }
        this.actionsKey = actionsKey;
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
        statsView.setText(String.format(Locale.US, "попытки: %d / %d · прошло %.1f с", controller.attempt, max, elapsed / 1000f)
                + (controller.floodWaits > 0 ? " · FLOOD_WAIT ×" + controller.floodWaits : ""));
    }

    // ---- interactive history: one row per attempt, newest on top ----

    private RawRerollController.Attempt expanded;
    private int expandedSelection = -1;

    private void rebuildHistory() {
        int count = controller.history.size();
        int shown = historyRows.size();
        if (count == shown && (count > 0 || historyEmpty != null)) {
            updateHistoryTexts();
            return;
        }
        if (count > shown && (shown > 0 || historyEmpty != null)) {
            // new attempts: slide their cards in on top, the rest of the list stays as it is
            if (historyEmpty != null) {
                historyList.removeView(historyEmpty);
                historyEmpty = null;
            }
            boolean attached = historyList.isAttachedToWindow();
            // reading further down or looking at an expanded card: keep that content still and just count the new ones
            boolean hold = attached && shown > 0
                    && (scrollView.getScrollY() > AndroidUtilities.dp(8) || expanded != null && cards.containsKey(expanded));
            if (hold && scrollAnchor == null) {
                scrollAnchor = historyList.getChildAt(0);
                scrollAnchorTop = scrollAnchor.getTop();
            }
            for (int i = shown; i < count; i++) {
                LinearLayout card = createCard(controller.history.get(i), 0);
                if (attached && !hold) {
                    card.setVisibility(View.GONE);
                    RawAnim.expand(card, true);
                    RawMotion.settle(card);
                }
            }
            if (hold) {
                showNewPill(count - shown);
            }
            updateHistoryTexts();
            return;
        }
        // the history was reset (restart): build it from scratch
        historyList.removeAllViews();
        historyRows.clear();
        historyEmpty = null;
        cards.clear();
        controlsOf.clear();
        scrollAnchor = null;
        hideNewPill();
        scrollView.scrollTo(0, 0);
        if (count == 0) {
            historyEmpty = row();
            historyEmpty.setText(controller.isRunning() ? "первая попытка…" : "попыток не было");
            historyList.addView(historyEmpty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));
            return;
        }
        for (int i = count - 1; i >= 0; i--) {
            createCard(controller.history.get(i), -1);
        }
        updateHistoryTexts();
    }

    private void showNewPill(int added) {
        unseenNew += added;
        newPill.setText(unseenNew + " " + (unseenNew % 10 == 1 && unseenNew % 100 != 11 ? "новая" : "новых") + " ↑");
        if (newPillShown) {
            return;
        }
        newPillShown = true;
        newPill.animate().cancel();
        newPill.setVisibility(View.VISIBLE);
        newPill.setAlpha(0f);
        newPill.setTranslationY(-AndroidUtilities.dp(8));
        newPill.animate().alpha(1f).translationY(0).setDuration(200)
                .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    private void hideNewPill() {
        unseenNew = 0;
        if (newPill == null || !newPillShown) {
            return;
        }
        newPillShown = false;
        newPill.animate().cancel();
        newPill.animate().alpha(0f).translationY(-AndroidUtilities.dp(8)).setDuration(150)
                .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT)
                .withEndAction(() -> newPill.setVisibility(View.GONE)).start();
    }

    /** Adds the card of one attempt at {@code index} of the list (-1 = at the end); rows are kept newest first. */
    private LinearLayout createCard(RawRerollController.Attempt a, int index) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        TextView header = row();
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        boolean interactive = a.response != null;
        card.setBackground(cardBackground(interactive, a == expanded));
        if (interactive) {
            LinearLayout controls = new LinearLayout(getContext());
            controls.setOrientation(LinearLayout.VERTICAL);
            controls.setVisibility(a == expanded ? View.VISIBLE : View.GONE);
            card.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            controlsOf.put(a, controls);
            cards.put(a, card);
            if (a == expanded) {
                addExpandedControls(controls, a);
            }
            header.setOnClickListener(v -> toggle(a));
        }
        if (index == 0) {
            historyRows.add(0, header);
        } else {
            historyRows.add(header);
        }
        historyList.addView(card, index, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
        return card;
    }

    private final java.util.HashMap<RawRerollController.Attempt, LinearLayout> cards = new java.util.HashMap<>();
    private final java.util.HashMap<RawRerollController.Attempt, LinearLayout> controlsOf = new java.util.HashMap<>();

    /** Expands one card in place (collapsing the previous one) with an animated layout change. */
    private void toggle(RawRerollController.Attempt a) {
        if (expanded != null && controlsOf.containsKey(expanded)) {
            RawAnim.expand(controlsOf.get(expanded), false);
            RawMotion.swapBackground(cards.get(expanded), cardBackground(true, false));
        }
        if (expanded == a) {
            expanded = null;
            return;
        }
        expanded = a;
        expandedSelection = a.matchIndex >= 0 ? a.matchIndex : 0;
        LinearLayout controls = controlsOf.get(a);
        controls.removeAllViews();
        addExpandedControls(controls, a);
        RawAnim.expand(controls, true);
        // rows settle in as the card opens, the result chips pop in one by one
        RawMotion.settle(controls);
        if (controls.getChildAt(0) instanceof android.view.ViewGroup && ((android.view.ViewGroup) controls.getChildAt(0)).getChildAt(0) instanceof android.view.ViewGroup) {
            RawMotion.popRow((android.view.ViewGroup) ((android.view.ViewGroup) controls.getChildAt(0)).getChildAt(0), 80, 30);
        }
        RawMotion.swapBackground(cards.get(a), cardBackground(true, true));
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
        final java.util.ArrayList<TextView> chips = new java.util.ArrayList<>();
        final LinearLayout actionsRow = new LinearLayout(getContext());
        for (int i = 0; i < visible.size(); i++) {
            final int index = i;
            boolean selected = i == expandedSelection;
            TextView chip = smallChip("[" + i + "] " + ellipsize(title(visible.get(i)), 22) + (i == a.matchIndex ? " ✓" : ""), selected);
            chip.setOnClickListener(v -> {
                if (expandedSelection == index) {
                    return;
                }
                // restyle in place: the results row keeps its scroll position
                expandedSelection = index;
                for (int c = 0; c < chips.size(); c++) {
                    styleSmallChip(chips.get(c), c == index);
                }
                RawAnim.pop(chip);
                fillActions(actionsRow, a, visible);
            });
            chips.add(chip);
            results.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }

        HorizontalScrollView actionsScroll = new HorizontalScrollView(getContext());
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        actionsScroll.addView(actionsRow);
        card.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        fillActions(actionsRow, a, visible);

        TextView hint = new TextView(getContext());
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        hint.setText("Сервер хранит выдачу бота ограниченное время: устаревший результат отправить не получится.");
        card.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 2, 12, 8));
    }

    /** Actions for the currently selected result of an expanded attempt. */
    private void fillActions(LinearLayout actionsRow, RawRerollController.Attempt a, java.util.ArrayList<TLRPC.BotInlineResult> visible) {
        actionsRow.removeAllViews();
        if (expandedSelection >= 0 && expandedSelection < visible.size()) {
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
        styleSmallChip(chip, selected);
        return chip;
    }

    private void styleSmallChip(TextView chip, boolean selected) {
        int base = getThemedColor(Theme.key_dialogTextBlack);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        if (selected) {
            chip.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(15), accent, Theme.multAlpha(accent, 0.8f)));
        } else {
            chip.setTextColor(base);
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(15), Theme.multAlpha(base, 0.07f), Theme.multAlpha(base, 0.14f)));
        }
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
