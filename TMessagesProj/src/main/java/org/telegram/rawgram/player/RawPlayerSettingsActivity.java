package org.telegram.rawgram.player;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.rawgram.RawUi;
import org.telegram.rawgram.settings.RawPreferencesFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * "Плеер": switches of the rawGram music player (the bottom sheet ported from exteraGram). The same options are in
 * the sheet's own settings.
 */
public class RawPlayerSettingsActivity extends RawPreferencesFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    private static final int NEW_PLAYER = 1;
    private static final int COVER_COLORS = 2;
    private static final int SPEED_PITCH = 3;
    private static final int RHYTHM = 4;
    private static final int PARTICLES = 5;
    private static final int LYRICS_ONLINE = 6;
    private static final int LYRICS_PROGRESS = 20;

    private static final String[] PROGRESS_NAMES = {"Выкл.", "Слева направо", "Сверху вниз"};

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
        header("Плеер");
        check("Новый плеер", NEW_PLAYER);
        info("Окно музыки как в exteraGram: обложка, очередь, текст песни и волнистая шкала. Выкл: обычный плеер Telegram.");

        header("Вид");
        check("Цвета из обложки", COVER_COLORS);
        check("Фон в ритм", RHYTHM);
        check("Частицы", PARTICLES);
        info("Фон и частицы реагируют на ритм музыки. Расходуют заряд, не работают на планшетах.");

        header("Звук");
        check("Менять тон со скоростью", SPEED_PITCH);
        info("Вкл: при ускорении голос становится выше, как на плёнке. Выкл: тон сохраняется.");

        header("Тексты песен");
        check("Тексты из интернета", LYRICS_ONLINE);
        info("Текст ищется на lrclib.net, если его нет в тегах файла: туда уходят название, исполнитель и длительность. "
                + "Выкл: ничего не отправляется, берутся только теги и сохранённые тексты.");
        value("Заполнение строки", LYRICS_PROGRESS);
    }

    private static boolean isOn(int id) {
        switch (id) {
            case NEW_PLAYER: return RawPlayerConfig.getNewPlayerStyle();
            case COVER_COLORS: return RawPlayerConfig.getPlayerCoverColors();
            case SPEED_PITCH: return RawPlayerConfig.getPlayerSpeedPitch();
            case RHYTHM: return RawPlayerConfig.getPlayerRhythmBackground();
            case PARTICLES: return RawPlayerConfig.getPlayerParticles();
            case LYRICS_ONLINE: return RawPlayerConfig.getLyricsOnline();
            default: return false;
        }
    }

    private static void set(int id, boolean value) {
        switch (id) {
            case NEW_PLAYER: RawPlayerConfig.setNewPlayerStyle(value); break;
            case COVER_COLORS: RawPlayerConfig.setPlayerCoverColors(value); RawPlayerConfig.setPlayerMiniCoverColors(value); break;
            case SPEED_PITCH:
                RawPlayerConfig.setPlayerSpeedPitch(value);
                org.telegram.messenger.MediaController.getInstance().restoreMusicPlaybackSpeed();
                break;
            case RHYTHM: RawPlayerConfig.setPlayerRhythmBackground(value); break;
            case PARTICLES: RawPlayerConfig.setPlayerParticles(value); break;
            case LYRICS_ONLINE: RawPlayerConfig.setLyricsOnline(value); break;
        }
    }

    @Override
    protected String getTitle() {
        return "Плеер";
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
            } else if (row.type == TYPE_VALUE && row.id == LYRICS_PROGRESS && getParentActivity() != null) {
                new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle("Заполнение строки")
                        .setItems(PROGRESS_NAMES, (d, which) -> {
                            RawPlayerConfig.setLyricsProgress(which);
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
                    ((TextSettingsCell) holder.itemView).setTextAndValue(row.text, PROGRESS_NAMES[Math.max(0, Math.min(2, RawPlayerConfig.getLyricsProgress()))], needDivider(position));
                    break;
                default:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
            }
        }
    }
}
