package org.telegram.rawgram;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * "Чаты: вид и поведение": chat look and behaviour switches (RawChatUiConfig). Every option is off by default,
 * which is stock Telegram. Option set ported from Nagram / NekoX / exteraGram settings.
 */
public class RawChatUiSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    /** One list row: a header, a switch bound to a flag, a value (picker) or an info line. */
    private static class Row {
        final int type;
        final String text;
        final RawChatUiConfig.Flag flag;
        final int id;

        Row(int type, String text, RawChatUiConfig.Flag flag, int id) {
            this.type = type;
            this.text = text;
            this.flag = flag;
            this.id = id;
        }
    }

    private static final int VALUE_EDITED_MODE = 1;
    private static final int VALUE_EDITED_TEXT = 2;
    private static final int VALUE_SNOW = 3;
    private static final int VALUE_TAP_IN = 4;
    private static final int VALUE_TAP_OUT = 5;

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;

    private void header(String text) {
        rows.add(new Row(TYPE_HEADER, text, null, 0));
    }

    private void check(String text, RawChatUiConfig.Flag flag) {
        rows.add(new Row(TYPE_CHECK, text, flag, 0));
    }

    private void value(String text, int id) {
        rows.add(new Row(TYPE_VALUE, text, null, id));
    }

    private void info(String text) {
        rows.add(new Row(TYPE_INFO, text, null, 0));
    }

    private void buildRows() {
        rows.clear();
        header("Заголовок и сообщения");
        check("Заголовок чата по центру", RawChatUiConfig.centerTitle);
        check("Скрыть время у стикеров", RawChatUiConfig.hideStickerTime);
        check("ID сообщения в пузыре", RawChatUiConfig.showMessageId);
        value("Метка «изменено»", VALUE_EDITED_MODE);
        if (RawChatUiConfig.editedMode.get() == RawChatUiConfig.EDITED_CUSTOM) {
            value("Текст метки", VALUE_EDITED_TEXT);
        }
        check("Скрыть «Поделиться» у постов каналов", RawChatUiConfig.hideChannelShare);
        value("Снег в чате", VALUE_SNOW);
        info("Время стикера видно, пока сообщение выделено. ID сообщения добавляется к времени: «12:30 | 4821». "
                + "Изменения видны при следующем открытии чата.");

        header("Поведение");
        check("Без свайпа к следующему каналу", RawChatUiConfig.noSwipeNextChannel);
        check("Без свайпа к следующей теме", RawChatUiConfig.noSwipeNextTopic);
        value("Двойное нажатие: входящие", VALUE_TAP_IN);
        value("Двойное нажатие: свои", VALUE_TAP_OUT);
        info("«Реакция» — как в Telegram (быстрая реакция). Действие, которое к сообщению неприменимо "
                + "(например, копирование в чате с запретом копирования), просто не срабатывает.");

        header("Ярлыки администратора в меню чата");
        check("Разрешения / чёрный список", RawChatUiConfig.shortcutPermissions);
        check("Администраторы", RawChatUiConfig.shortcutAdmins);
        check("Участники / подписчики", RawChatUiConfig.shortcutMembers);
        check("Недавние действия", RawChatUiConfig.shortcutRecentActions);
        info("Пункты появляются в меню «⋮» групп и каналов, где у тебя есть права администратора.");

        header("Скрыть из меню сообщения");
        check("Перевести", RawChatUiConfig.menuHideTranslate);
        check("Пожаловаться", RawChatUiConfig.menuHideReport);
        check("Закрепить / открепить", RawChatUiConfig.menuHidePin);
        check("Сохранить (галерея, загрузки, музыка, GIF)", RawChatUiConfig.menuHideSave);
        check("Поделиться файлом", RawChatUiConfig.menuHideShare);
        check("Копировать ссылку", RawChatUiConfig.menuHideCopyLink);
        check("Статистика", RawChatUiConfig.menuHideStatistics);
        check("Факт-чек", RawChatUiConfig.menuHideFactCheck);

        header("Добавить в меню сообщения");
        check("Повторить (отправить копию сюда же)", RawChatUiConfig.menuRepeat);
        check("В Избранное", RawChatUiConfig.menuSaveToSaved);
        info("«Подробности» rawGram настраиваются в основных настройках.\n\n"
                + "Идеи и часть кода — из Nagram, NekoX и exteraGram (GPLv3).");
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Чаты: вид и поведение");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        buildRows();

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            if (row.type == TYPE_CHECK && row.flag != null) {
                row.flag.toggle();
                ((TextCheckCell) view).setChecked(row.flag.get());
            } else if (row.type == TYPE_VALUE) {
                onValueClick(row.id, position);
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void pick(String title, CharSequence[] names, Runnable done, PickCallback callback) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setItems(names, (d, which) -> {
                    callback.picked(which);
                    done.run();
                })
                .show();
    }

    private interface PickCallback {
        void picked(int which);
    }

    private void onValueClick(int id, int position) {
        Runnable refresh = () -> adapter.notifyItemChanged(position);
        switch (id) {
            case VALUE_EDITED_MODE:
                pick("Метка «изменено»", new CharSequence[]{"Стандартная", "Карандаш ✎", "Свой текст"}, () -> {
                    buildRows();
                    adapter.notifyDataSetChanged();
                }, which -> {
                    RawChatUiConfig.editedMode.set(which);
                    if (which == RawChatUiConfig.EDITED_CUSTOM && RawChatUiConfig.getEditedText().isEmpty()) {
                        AndroidUtilities.runOnUIThread(this::editCustomText);
                    }
                });
                break;
            case VALUE_EDITED_TEXT:
                editCustomText();
                break;
            case VALUE_SNOW:
                pick("Снег в чате", new CharSequence[]{"По дате (как в Telegram)", "Всегда", "Никогда"}, refresh,
                        RawChatUiConfig.chatSnow::set);
                break;
            case VALUE_TAP_IN:
            case VALUE_TAP_OUT: {
                boolean out = id == VALUE_TAP_OUT;
                // editing applies to own messages only
                int[] actions = out
                        ? new int[]{RawChatUiConfig.TAP_REACTION, RawChatUiConfig.TAP_REPLY, RawChatUiConfig.TAP_COPY, RawChatUiConfig.TAP_FORWARD, RawChatUiConfig.TAP_EDIT, RawChatUiConfig.TAP_SAVE, RawChatUiConfig.TAP_NONE}
                        : new int[]{RawChatUiConfig.TAP_REACTION, RawChatUiConfig.TAP_REPLY, RawChatUiConfig.TAP_COPY, RawChatUiConfig.TAP_FORWARD, RawChatUiConfig.TAP_SAVE, RawChatUiConfig.TAP_NONE};
                CharSequence[] names = new CharSequence[actions.length];
                for (int i = 0; i < actions.length; i++) {
                    names[i] = RawChatUiConfig.doubleTapName(actions[i]);
                }
                pick(out ? "Двойное нажатие: свои" : "Двойное нажатие: входящие", names, refresh,
                        which -> (out ? RawChatUiConfig.doubleTapOut : RawChatUiConfig.doubleTapIn).set(actions[which]));
                break;
            }
        }
    }

    private void editCustomText() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint(LocaleController.getString(R.string.EditedMessage));
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setText(RawChatUiConfig.getEditedText());
        editText.setSelection(editText.length());
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 4, 24, 0));
        new AlertDialog.Builder(context)
                .setTitle("Текст метки «изменено»")
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
                    RawChatUiConfig.setEditedText(editText.getText() == null ? "" : editText.getText().toString());
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 200);
    }

    private String valueText(int id) {
        switch (id) {
            case VALUE_EDITED_MODE: {
                int mode = RawChatUiConfig.editedMode.get();
                return mode == RawChatUiConfig.EDITED_PENCIL ? "Карандаш ✎" : mode == RawChatUiConfig.EDITED_CUSTOM ? "Свой текст" : "Стандартная";
            }
            case VALUE_EDITED_TEXT: {
                String text = RawChatUiConfig.getEditedText();
                return text.isEmpty() ? LocaleController.getString(R.string.EditedMessage) : text;
            }
            case VALUE_SNOW: {
                int snow = RawChatUiConfig.chatSnow.get();
                return snow == RawChatUiConfig.SNOW_ALWAYS ? "Всегда" : snow == RawChatUiConfig.SNOW_NEVER ? "Никогда" : "По дате";
            }
            case VALUE_TAP_IN:
                return RawChatUiConfig.doubleTapName(RawChatUiConfig.doubleTapIn.get());
            case VALUE_TAP_OUT:
                return RawChatUiConfig.doubleTapName(RawChatUiConfig.doubleTapOut.get());
            default:
                return "";
        }
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
                    ((TextCheckCell) holder.itemView).setTextAndCheck(row.text, row.flag != null && row.flag.get(), needDivider(position));
                    break;
                case TYPE_VALUE:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(row.text, valueText(row.id), needDivider(position));
                    break;
                default:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
            }
        }
    }
}
