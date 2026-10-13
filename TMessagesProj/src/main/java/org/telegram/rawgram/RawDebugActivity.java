package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.settings.RawPreferencesFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Hidden debug menu (long press on the «rawGram» header in the settings): every notification style, a gallery of real
 * settings views, on-device self-checks ({@link RawDebugChecks}) and live memory figures.
 */
public class RawDebugActivity extends RawPreferencesFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VALUE = 1;
    private static final int TYPE_INFO = 2;
    private static final long TICK_MS = 1000;

    private static class Row {
        final int type;
        final String text;
        final Runnable action;
        final Supplier<String> value;

        Row(int type, String text, Runnable action, Supplier<String> value) {
            this.type = type;
            this.text = text;
            this.action = action;
            this.value = value;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private ListAdapter adapter;
    private List<RawDebugChecks.Result> results;
    private boolean running;
    private boolean dumping;
    private int heapRow = -1;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (adapter != null && heapRow >= 0) {
                adapter.notifyItemRangeChanged(heapRow, 2);
            }
            AndroidUtilities.runOnUIThread(this, TICK_MS);
        }
    };

    private void header(String text) {
        rows.add(new Row(TYPE_HEADER, text, null, null));
    }

    private void button(String text, Runnable action) {
        rows.add(new Row(TYPE_VALUE, text, action, null));
    }

    private void info(String text) {
        rows.add(new Row(TYPE_INFO, text, null, null));
    }

    private void buildRows() {
        rows.clear();
        header("Уведомления");
        button("Обычное", () -> RawNotify.show(R.drawable.msg_info, "Так выглядит обычное уведомление"));
        button("С нажатием", () -> RawNotify.show(R.drawable.msg_info, "Нажми на плашку", () -> RawNotify.show(R.drawable.msg_saved, "Нажатие сработало")));
        button("Предупреждение", () -> RawNotify.warn("Так выглядит сбой: красный цвет и дольше на экране"));
        button("Результат: успех", () -> RawNotify.result(true, R.drawable.msg_saved, "Готово", "Не удалось"));
        button("Результат: сбой", () -> RawNotify.result(false, R.drawable.msg_saved, "Готово", "Не удалось выполнить"));
        button("В шторке", this::showSheetSample);
        button("Предупреждение о памяти", () -> RawMemWatch.showWarning(480L << 20, 512L << 20, null));
        info("Все уведомления rawGram рисуются одним стилем. Предупреждение о памяти — такое, как при почти полной куче.");

        header("Интерфейс");
        button("Галерея компонентов", () -> presentFragment(new RawDebugGalleryActivity()));
        button("Raw-окно с примером", () -> {
            if (getParentActivity() != null) {
                new RawObjectSheet(getParentActivity(), currentAccount, "Пример", RawDebugChecks.sampleUser(), getResourceProvider()).show();
            }
        });

        header("Проверки");
        button(running ? "Идёт проверка…" : "Запустить проверки", this::runChecks);
        if (results != null) {
            for (RawDebugChecks.Result result : results) {
                String value = (result.ok ? "ОК · " : "Сбой · ") + result.millis + " мс";
                rows.add(new Row(TYPE_VALUE, result.name, () -> showDetail(result), () -> value));
            }
        }
        info("Короткие проверки на устройстве: разбор объектов TL, чтение настроек, ответ UI-потока. Нажатие на строку показывает подробности.");

        header("Память");
        heapRow = rows.size();
        rows.add(new Row(TYPE_VALUE, "Куча Java", null, RawMemWatch::heapLine));
        rows.add(new Row(TYPE_VALUE, "Native", null, RawMemWatch::nativeLine));
        button("Собрать мусор", this::collectGarbage);
        button(dumping ? "Снимается дамп…" : "Снять дамп кучи", this::dumpHeap);
        info("Дамп кучи — сотни мегабайт, приложение замирает на 10–20 секунд. Файл лежит в files/rawgram_diag/, прежний дамп заменяется.");
    }

    private void showSheetSample() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, getResourceProvider());
        builder.setCustomView(box);
        BottomSheet sheet = builder.create();
        box.addView(sheetButton(context, "Обычное в шторке", () -> RawNotify.show(sheet, R.drawable.msg_info, "Уведомление поверх шторки")));
        box.addView(sheetButton(context, "Предупреждение в шторке", () -> RawNotify.warn(sheet, "Сбой поверх шторки")));
        sheet.show();
    }

    private TextView sheetButton(Context context, String text, Runnable action) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(24), 0, dp(24), 0);
        view.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        view.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void runChecks() {
        if (running) {
            return;
        }
        running = true;
        refresh();
        Utilities.globalQueue.postRunnable(() -> {
            List<RawDebugChecks.Result> done = RawDebugChecks.runAll();
            AndroidUtilities.runOnUIThread(() -> {
                running = false;
                results = done;
                refresh();
                int failed = 0;
                for (RawDebugChecks.Result result : done) {
                    failed += result.ok ? 0 : 1;
                }
                RawNotify.result(failed == 0, R.drawable.msg_info, "Проверки пройдены: " + done.size(), "Проверки не пройдены: " + failed + " из " + done.size());
            });
        });
    }

    private void showDetail(RawDebugChecks.Result result) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(result.name)
                .setMessage(result.detail == null ? "Без подробностей" : result.detail)
                .setPositiveButton("OK", null)
                .show();
    }

    private void collectGarbage() {
        String before = RawMemWatch.heapLine();
        System.gc();
        Runtime.getRuntime().runFinalization();
        System.gc();
        refresh();
        RawNotify.show(R.drawable.msg_info, "Куча: " + before + " → " + RawMemWatch.heapLine());
    }

    private void dumpHeap() {
        if (dumping) {
            return;
        }
        dumping = true;
        refresh();
        RawNotify.show(R.drawable.msg_info, "Снимаю дамп кучи, приложение замрёт");
        Context context = getParentActivity() != null ? getParentActivity().getApplicationContext() : null;
        if (context == null) {
            dumping = false;
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File dump = null;
            try {
                dump = RawMemWatch.dumpNow(context);
            } catch (Throwable ignore) {
            }
            File result = dump;
            AndroidUtilities.runOnUIThread(() -> {
                dumping = false;
                refresh();
                RawNotify.result(result != null, R.drawable.msg_saved, "Дамп снят: " + (result != null ? result.length() >> 20 : 0) + " МБ", "Не удалось снять дамп кучи");
            });
        }, 600);
    }

    private void refresh() {
        buildRows();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected String getTitle() {
        return "Отладка";
    }

    @Override
    protected RecyclerListView createListView(Context context) {
        buildRows();
        RecyclerListView listView = new RecyclerListView(context);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rows.size() && rows.get(position).action != null) {
                rows.get(position).action.run();
            }
        });
        return listView;
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.cancelRunOnUIThread(tick);
        AndroidUtilities.runOnUIThread(tick, TICK_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(tick);
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(tick);
        super.onFragmentDestroy();
    }

    private boolean needDivider(int position) {
        return position + 1 < rows.size() && rows.get(position + 1).type == TYPE_VALUE;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return holder.getItemViewType() == TYPE_VALUE && position >= 0 && position < rows.size() && rows.get(position).action != null;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = viewType == TYPE_HEADER ? new HeaderCell(context) : viewType == TYPE_VALUE ? new TextSettingsCell(context) : new TextInfoPrivacyCell(context);
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.type == TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(row.text);
            } else if (row.type == TYPE_VALUE) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (row.value != null) {
                    cell.setTextAndValue(row.text, row.value.get(), needDivider(position));
                } else {
                    cell.setText(row.text, needDivider(position));
                }
            } else {
                ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
            }
        }
    }
}
