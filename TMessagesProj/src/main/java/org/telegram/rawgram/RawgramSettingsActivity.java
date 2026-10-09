package org.telegram.rawgram;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.rawgram.settings.RawPreferencesFragment;
import org.telegram.rawgram.settings.RawSettingsHeaderCell;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * "Настройки rawGram": a hub laid out like exteraGram's preferences: the app icon, name and version on top,
 * then the categories (outline icon, title and a short subtitle) that open the actual settings screens,
 * the backup section, credits and the version line.
 */
public class RawgramSettingsActivity extends RawPreferencesFragment {

    private static final int TYPE_CATEGORY = 0;
    private static final int TYPE_SHADOW = 1;
    private static final int TYPE_BUTTON = 2;
    private static final int TYPE_INFO = 3;
    private static final int TYPE_HEADER = 4;
    private static final int TYPE_APP_HEADER = 5;

    private static final int CATEGORY_HEIGHT_DP = 60;

    /** One list row. For TYPE_CATEGORY / TYPE_BUTTON: icon, title, subtitle and what a tap opens. */
    private static class Row {
        final int type;
        final int icon;
        final String title;
        final String subtitle;
        final Runnable action;
        Runnable longAction;

        Row(int type, int icon, String title, String subtitle, Runnable action) {
            this.type = type;
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();

    private void category(int icon, String title, String subtitle, Runnable action) {
        rows.add(new Row(TYPE_CATEGORY, icon, title, subtitle, action));
    }

    private Row button(int icon, String title, Runnable action) {
        Row row = new Row(TYPE_BUTTON, icon, title, null, action);
        rows.add(row);
        return row;
    }

    private void buildRows() {
        rows.clear();
        rows.add(new Row(TYPE_APP_HEADER, 0, null, null, null));
        rows.add(new Row(TYPE_HEADER, 0, "Категории", null, null));
        category(R.drawable.msg_discussion, "Чаты",
                "Вид чата, сообщения, меню, стикеры", () -> presentFragment(new RawChatUiSettingsActivity()));
        category(R.drawable.msg_palette, "Внешний вид",
                "Главный экран, папки, аватарки, иконки", () -> presentFragment(new RawUiSettingsActivity()));
        // Плагины (branch rawgram-plugins): add one line here, e.g.
        // category(R.drawable.msg_bots, "Плагины", "Установленные плагины", () -> presentFragment(new RawPluginsActivity()));
        category(R.drawable.settings_rawgram, "Инструменты разработчика",
                "Raw-данные, ID, журналы, сервер", () -> presentFragment(new RawDevSettingsActivity()));
        rows.add(new Row(TYPE_SHADOW, 0, null, null, null));
        rows.add(new Row(TYPE_HEADER, 0, "Резервная копия", null, null));
        button(R.drawable.msg_settings_old, "Бекап настроек", () -> RawBackup.backupSettings(this));
        button(R.drawable.msg_archive, "Полный бекап", () -> RawBackup.backupData(this)).longAction = () -> RawBackup.backupStorage(this);
        button(R.drawable.msg_retry, "Восстановить из файла", () -> RawBackup.pickRestore(this));
        rows.add(new Row(TYPE_INFO, 0, "Полный бекап — настройки и данные rawGram: отчёты о сбоях и диагностика. "
                + "Удерживай его, чтобы сохранить всё приложение вместе с сессиями в зашифрованный файл.", null, null));
        button(R.drawable.msg_info, "Авторы и лицензии", this::showCredits);
        rows.add(new Row(TYPE_INFO, 0, "rawGram на основе Telegram " + BuildVars.BUILD_VERSION_STRING + " · GPLv3", null, null));
    }

    @Override
    protected String getTitle() {
        return "Настройки rawGram";
    }

    @Override
    protected boolean hideTitleAtTop() {
        return true;
    }

    @Override
    protected int listTopPadding(int topInset) {
        // the app header gives the room under the bar itself, as in exteraGram
        return topInset + org.telegram.messenger.AndroidUtilities.dp(12);
    }

    @Override
    protected RecyclerListView createListView(Context context) {
        buildRows();

        RecyclerListView listView = new RecyclerListView(context);
        listView.setAdapter(new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            if (row.action != null) {
                row.action.run();
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= rows.size() || rows.get(position).longAction == null) {
                return false;
            }
            rows.get(position).longAction.run();
            return true;
        });
        return listView;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        RawBackup.onActivityResult(this, requestCode, resultCode, data);
    }

    /** "12.10.6 (7112)": the version and build number, as exteraGram shows them under the app name. */
    private static String versionLine() {
        StringBuilder text = new StringBuilder(BuildVars.BUILD_VERSION_STRING);
        try {
            Context context = ApplicationLoader.applicationContext;
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            text.append(" (").append(info.versionCode).append(")");
        } catch (Exception ignore) {
        }
        return text.toString();
    }

    private void showCredits() {
        if (getParentActivity() == null) {
            return;
        }
        String text = "rawGram распространяется по лицензии GNU GPL v3.\n\n"
                + "Основано на коде и идеях:\n"
                + "• Telegram для Android — Telegram FZ-LLC (GPLv2+)\n"
                + "• Nagram — NextAlone (GPLv3)\n"
                + "• NekoX / Nekogram (GPLv3)\n"
                + "• exteraGram — exteraSquad (GPLv2+)\n"
                + "• AyuGram — Radolyn Labs (GPLv2+)\n"
                + "• Иконки Solar — 480 Design (CC BY 4.0)\n\n"
                + "Неофициальный клиент, не связан с Telegram.";
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle("Авторы и лицензии")
                .setMessage(text)
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show();
    }

    private boolean needDivider(int position) {
        if (position + 1 >= rows.size()) {
            return false;
        }
        int next = rows.get(position + 1).type;
        return next == TYPE_CATEGORY || next == TYPE_BUTTON;
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
            return type == TYPE_CATEGORY || type == TYPE_BUTTON;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_CATEGORY || viewType == TYPE_BUTTON) {
                TextCell cell = new TextCell(context);
                if (viewType == TYPE_CATEGORY) {
                    cell.heightDp = CATEGORY_HEIGHT_DP;
                }
                view = cell;
            } else if (viewType == TYPE_APP_HEADER) {
                RawSettingsHeaderCell header = new RawSettingsHeaderCell(context);
                header.setTexts("rawGram", versionLine());
                view = header;
            } else if (viewType == TYPE_SHADOW) {
                view = new ShadowSectionCell(context);
            } else if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
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
                case TYPE_CATEGORY:
                case TYPE_BUTTON: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndIcon(row.title, row.icon, needDivider(position));
                    cell.setSubtitle(row.subtitle);
                    break;
                }
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.title);
                    break;
                case TYPE_APP_HEADER:
                    ((RawSettingsHeaderCell) holder.itemView).updateColors();
                    break;
            }
        }
    }
}
