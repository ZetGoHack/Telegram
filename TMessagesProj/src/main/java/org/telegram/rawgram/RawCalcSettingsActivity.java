package org.telegram.rawgram;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.rawgram.settings.RawPreferencesFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * "Калькулятор": the inline calculator of the chat input (type «2+2=», the answer shows grey, Space inserts it).
 * The local parser is a port of exteraGram's inline results; Calcmula is an optional online provider.
 */
public class RawCalcSettingsActivity extends RawPreferencesFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    private static final int ENABLED = 1;
    private static final int MATH = 2;
    private static final int UNITS = 3;
    private static final int CURRENCY = 4;
    private static final int ONLINE_RATES = 5;
    private static final int TIME_ZONES = 6;
    private static final int DEGREES = 7;
    private static final int GROUPING = 8;
    private static final int CALCMULA = 9;
    private static final int PROVIDER = 20;

    private static final String[] PROVIDER_NAMES = {"Локальный", "Calcmula", "Локальный, затем Calcmula"};

    private static class Row {
        final int type;
        final String text;
        final int id;

        Row(int type, String text, int id) {
            this.type = type;
            this.text = text;
            this.id = id;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private ListAdapter adapter;

    private void header(String text) {
        rows.add(new Row(TYPE_HEADER, text, 0));
    }

    private void check(String text, int id) {
        rows.add(new Row(TYPE_CHECK, text, id));
    }

    private void value(String text, int id) {
        rows.add(new Row(TYPE_VALUE, text, id));
    }

    private void info(String text) {
        rows.add(new Row(TYPE_INFO, text, 0));
    }

    private void buildRows() {
        rows.clear();
        header("Калькулятор в поле ввода");
        check("Считать в поле ввода", ENABLED);
        info("Напиши выражение и «=», например «12% от 1200 =» или «5 кг в фунтах =»: результат появится серым после курсора. "
                + "Пробел вставляет его, Backspace сразу после вставки возвращает как было.");

        header("Что считать");
        check("Арифметика и функции", MATH);
        check("Единицы измерения", UNITS);
        check("Валюты", CURRENCY);
        check("Часовые пояса", TIME_ZONES);
        check("Тригонометрия в градусах", DEGREES);
        check("Пробел между разрядами", GROUPING);
        info("Звёзды пересчитываются без интернета. «6pm est =» и «now in tokyo =» — часовые пояса.");

        header("Курсы валют");
        check("Курсы из интернета", ONLINE_RATES);
        info("Курсы фиата и криптовалют загружаются с Coinbase и обновляются раз в 5 минут. Выкл: только звёзды и доллар.");

        header("Источник");
        value("Источник ответа", PROVIDER);
        check("Разрешить Calcmula", CALCMULA);
        info("Calcmula — сторонний сервис calcmula.app: введённое выражение уходит на его сервер. "
                + "Выкл: ничего не отправляется. Запрос идёт после паузы в наборе и не отправляется из секретных чатов.");
    }

    private static boolean isOn(int id) {
        switch (id) {
            case ENABLED: return RawCalcConfig.enabled.get();
            case MATH: return RawCalcConfig.math.get();
            case UNITS: return RawCalcConfig.units.get();
            case CURRENCY: return RawCalcConfig.currency.get();
            case ONLINE_RATES: return RawCalcConfig.onlineRates.get();
            case TIME_ZONES: return RawCalcConfig.timeZones.get();
            case DEGREES: return RawCalcConfig.degrees.get();
            case GROUPING: return RawCalcConfig.grouping.get();
            case CALCMULA: return RawCalcConfig.calcmula.get();
            default: return false;
        }
    }

    private static void set(int id, boolean value) {
        switch (id) {
            case ENABLED: RawCalcConfig.enabled.set(value); break;
            case MATH: RawCalcConfig.math.set(value); break;
            case UNITS: RawCalcConfig.units.set(value); break;
            case CURRENCY: RawCalcConfig.currency.set(value); break;
            case ONLINE_RATES: RawCalcConfig.onlineRates.set(value); break;
            case TIME_ZONES: RawCalcConfig.timeZones.set(value); break;
            case DEGREES: RawCalcConfig.degrees.set(value); break;
            case GROUPING: RawCalcConfig.grouping.set(value); break;
            case CALCMULA: RawCalcConfig.calcmula.set(value); break;
        }
    }

    @Override
    protected String getTitle() {
        return "Калькулятор";
    }

    @Override
    protected RecyclerListView createListView(Context context) {
        buildRows();
        RecyclerListView listView = new RecyclerListView(context);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            if (row.type == TYPE_CHECK) {
                boolean value = !isOn(row.id);
                set(row.id, value);
                ((TextCheckCell) view).setChecked(value);
            } else if (row.type == TYPE_VALUE && row.id == PROVIDER && getParentActivity() != null) {
                new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle("Источник ответа")
                        .setItems(PROVIDER_NAMES, (d, which) -> {
                            RawCalcConfig.provider.set(which);
                            adapter.notifyItemChanged(position);
                        })
                        .show();
            }
        });
        return listView;
    }

    private boolean needDivider(int position) {
        return position + 1 < rows.size() && rows.get(position + 1).type != TYPE_INFO && rows.get(position + 1).type != TYPE_HEADER;
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
            int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_VALUE;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
                RawUi.wrapTitle((TextCheckCell) view);
            } else if (viewType == TYPE_VALUE) {
                view = new TextSettingsCell(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (row.type) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.text);
                    break;
                case TYPE_CHECK:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(row.text, isOn(row.id), needDivider(position));
                    break;
                case TYPE_VALUE:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(row.text, PROVIDER_NAMES[RawCalcConfig.provider.get()], needDivider(position));
                    break;
                default:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
            }
        }
    }
}
