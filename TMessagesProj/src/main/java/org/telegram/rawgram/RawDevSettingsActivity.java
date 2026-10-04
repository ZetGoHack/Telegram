package org.telegram.rawgram;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * "Инструменты разработчика": rawGram's raw / debug features (each off = stock Telegram behavior there),
 * the ID format in profiles, the request and crash logs and the server config viewer.
 */
public class RawDevSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    // switches
    private static final int DETAILS = 1;
    private static final int OBJECT_RAW = 2;
    private static final int PREVIEW_RAW = 3;
    private static final int INLINE_RAW = 4;
    private static final int TRAY = 5;
    private static final int BOT_BUTTONS = 6;
    private static final int BOT_ANSWERS = 7;
    private static final int WEBAPP = 8;
    private static final int REQUEST_LOG = 9;
    private static final int ID_SEARCH = 10;
    private static final int NUMBER_IDS = 11;
    // values / actions
    private static final int ID_FORMAT = 20;
    private static final int OPEN_REQUEST_LOG = 21;
    private static final int CRASH_LOG = 22;
    private static final int SERVER_CONFIG = 23;

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
    private RecyclerListView listView;
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
        header("Raw-данные");
        check("«Подробности» в меню сообщения", DETAILS);
        check("Raw в профилях, наборах и диалогах", OBJECT_RAW);
        check("Raw в превью стикеров и эмодзи", PREVIEW_RAW);
        value("ID в профилях", ID_FORMAT);
        info("Выключенная функция не показывается, и Telegram ведёт себя как обычно. ID в профиле копируется нажатием.");

        header("Поиск по ID");
        check("Поиск по ID в кеше", ID_SEARCH);
        check("Числа в сообщениях: искать пользователя по ID", NUMBER_IDS);
        info("Запрос вида 123456789, -1001234567890, -123456 или id123456 в поиске чатов показывает пользователей и чаты с этим ID из локального кеша первыми. "
                + "В меню выделенного номера в сообщении — профиль с таким ID, если он есть в кеше. Сеть не используется: чего нет в кеше, найти нельзя.");

        header("Инлайн-боты");
        check("Raw результата по долгому нажатию", INLINE_RAW);
        check("Лоток инлайн-выдачи", TRAY);
        info("Отложенные в лоток результаты сохраняются, даже если лоток выключен.");

        header("Боты и веб-приложения");
        check("Данные кнопок по долгому нажатию", BOT_BUTTONS);
        check("Журнал ответов на кнопки", BOT_ANSWERS);
        check("Данные веб-приложений", WEBAPP);
        info("Журнал ответов — callback-ответы бота и уведомление, если бот ответил молча или не ответил.");

        header("Журналы");
        check("Журнал запросов MTProto", REQUEST_LOG);
        value("Открыть журнал запросов", OPEN_REQUEST_LOG);
        value("Журнал крашей", CRASH_LOG);
        info("Последние " + RawRequestLog.CAPACITY + " RPC-вызовов всех аккаунтов: метод, время, ответ или ошибка; "
                + "полные объекты — для последних " + RawRequestLog.KEEP_OBJECTS + ". Выключенный журнал ничего не стоит.");

        header("Сервер");
        value("Конфиг сервера", SERVER_CONFIG);
        info("help.getConfig и help.getAppConfig текущего аккаунта: лимиты (обычные и Premium), DC, флаги клиента.");
    }

    private static boolean isOn(int id) {
        switch (id) {
            case DETAILS: return RawgramConfig.isMessageDetails();
            case OBJECT_RAW: return RawgramConfig.isObjectRaw();
            case PREVIEW_RAW: return RawgramConfig.isPreviewRaw();
            case INLINE_RAW: return RawgramConfig.isInlineRaw();
            case TRAY: return RawgramConfig.isInlineTray();
            case BOT_BUTTONS: return RawgramConfig.isBotButtonDebug();
            case BOT_ANSWERS: return RawgramConfig.isBotAnswerLog();
            case WEBAPP: return RawgramConfig.isWebAppData();
            case REQUEST_LOG: return RawRequestLog.enabled;
            case ID_SEARCH: return RawgramConfig.isIdSearch();
            case NUMBER_IDS: return RawgramConfig.isNumberIds();
            default: return false;
        }
    }

    private static void set(int id, boolean value) {
        switch (id) {
            case DETAILS: RawgramConfig.setMessageDetails(value); break;
            case OBJECT_RAW: RawgramConfig.setObjectRaw(value); break;
            case PREVIEW_RAW: RawgramConfig.setPreviewRaw(value); break;
            case INLINE_RAW: RawgramConfig.setInlineRaw(value); break;
            case TRAY: RawgramConfig.setInlineTray(value); break;
            case BOT_BUTTONS: RawgramConfig.setBotButtonDebug(value); break;
            case BOT_ANSWERS: RawgramConfig.setBotAnswerLog(value); break;
            case WEBAPP: RawgramConfig.setWebAppData(value); break;
            case REQUEST_LOG: RawRequestLog.setEnabled(value); break;
            case ID_SEARCH: RawgramConfig.setIdSearch(value); break;
            case NUMBER_IDS: RawgramConfig.setNumberIds(value); break;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Инструменты разработчика");
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
            if (row.type == TYPE_CHECK) {
                boolean value = !isOn(row.id);
                set(row.id, value);
                ((TextCheckCell) view).setChecked(value);
            } else if (row.type == TYPE_VALUE) {
                onValueClick(row.id, position);
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void onValueClick(int id, int position) {
        switch (id) {
            case ID_FORMAT:
                if (getParentActivity() == null) {
                    return;
                }
                new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle("ID в профилях")
                        .setItems(new CharSequence[]{"Не показывать", "MTProto (как в API)", "Bot API (-100… для каналов)"}, (d, which) -> {
                            RawgramConfig.setIdFormat(which);
                            adapter.notifyItemChanged(position);
                        })
                        .show();
                break;
            case OPEN_REQUEST_LOG:
                presentFragment(new RawRequestLogActivity());
                break;
            case CRASH_LOG:
                RawCrashLog.openViewer(this);
                break;
            case SERVER_CONFIG:
                RawServerConfig.show(getParentActivity(), currentAccount, getResourceProvider());
                break;
        }
    }

    private static String idFormatName() {
        int format = RawgramConfig.getIdFormat();
        return format == RawgramConfig.ID_OFF ? "Выкл" : format == RawgramConfig.ID_MTPROTO ? "MTProto" : "Bot API";
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
                    if (row.id == ID_FORMAT) {
                        ((TextSettingsCell) holder.itemView).setTextAndValue(row.text, idFormatName(), needDivider(position));
                    } else {
                        ((TextSettingsCell) holder.itemView).setText(row.text, needDivider(position));
                    }
                    break;
                default:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
            }
        }
    }
}
