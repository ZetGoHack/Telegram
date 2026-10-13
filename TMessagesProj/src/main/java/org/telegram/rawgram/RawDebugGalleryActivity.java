package org.telegram.rawgram;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.rawgram.settings.RawPreferencesFragment;
import org.telegram.rawgram.settings.RawSettingsHeaderCell;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Debug menu: the real cells rawGram's settings are made of, one of each, in the current theme. Switching the theme in
 * Telegram's settings and coming back shows how every cell follows it.
 */
public class RawDebugGalleryActivity extends RawPreferencesFragment {

    private interface Factory {
        View create(Context context);
    }

    private final ArrayList<Factory> items = new ArrayList<>();

    private void buildItems() {
        items.clear();
        items.add(c -> {
            RawSettingsHeaderCell cell = new RawSettingsHeaderCell(c);
            cell.setTexts("rawGram", "Шапка настроек");
            return cell;
        });
        items.add(c -> {
            HeaderCell cell = new HeaderCell(c);
            cell.setText("Тема: " + (Theme.isCurrentThemeDark() ? "тёмная" : "светлая"));
            return cell;
        });
        items.add(c -> {
            TextCell cell = new TextCell(c);
            cell.heightDp = 60;
            cell.setTextAndIcon("Категория", R.drawable.msg_palette, true);
            cell.setSubtitle("Подпись категории");
            return cell;
        });
        items.add(c -> {
            TextCell cell = new TextCell(c);
            cell.setTextAndIcon("Кнопка с иконкой", R.drawable.msg_info, false);
            return cell;
        });
        items.add(c -> new ShadowSectionCell(c));
        items.add(c -> {
            TextCheckCell cell = new TextCheckCell(c);
            RawUi.wrapTitle(cell);
            cell.setTextAndCheck("Переключатель включён", true, true);
            return cell;
        });
        items.add(c -> {
            TextCheckCell cell = new TextCheckCell(c);
            RawUi.wrapTitle(cell);
            cell.setTextAndCheck("Переключатель выключен", false, true);
            return cell;
        });
        items.add(c -> {
            TextSettingsCell cell = new TextSettingsCell(c);
            cell.setTextAndValue("Строка со значением", "Значение", false);
            return cell;
        });
        items.add(c -> {
            TextInfoPrivacyCell cell = new TextInfoPrivacyCell(c);
            cell.setText("Пояснение под разделом: одно-два предложения о том, что делает настройка и где это видно.");
            return cell;
        });
    }

    @Override
    protected String getTitle() {
        return "Галерея";
    }

    @Override
    protected RecyclerListView createListView(Context context) {
        buildItems();
        RecyclerListView listView = new RecyclerListView(context);
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public int getItemCount() {
                return items.size();
            }

            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return false;
            }

            @Override
            public int getItemViewType(int position) {
                return position;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view = items.get(viewType).create(context);
                view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(view);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            }
        });
        return listView;
    }
}
