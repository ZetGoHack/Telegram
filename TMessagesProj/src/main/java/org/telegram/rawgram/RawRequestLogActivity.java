package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Viewer for {@link RawRequestLog}: newest first, substring filter on the method, pause / errors only / clear. */
public class RawRequestLogActivity extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_PAUSE = 2;
    private static final int MENU_ERRORS = 3;
    private static final int MENU_CLEAR = 4;
    private static final int MENU_ENABLE = 5;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final ArrayList<RawRequestLog.Entry> items = new ArrayList<>();
    private final Runnable listener = this::reload;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private ActionBarMenuSubItem pauseItem;
    private ActionBarMenuSubItem errorsItem;
    private ActionBarMenuSubItem enableItem;
    private String query;
    private boolean errorsOnly;

    @Override
    public boolean onFragmentCreate() {
        RawRequestLog.addListener(listener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        RawRequestLog.removeListener(listener);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Журнал запросов");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_PAUSE) {
                    RawRequestLog.paused = !RawRequestLog.paused;
                    updateMenu();
                    reload();
                } else if (id == MENU_ERRORS) {
                    errorsOnly = !errorsOnly;
                    updateMenu();
                    reload();
                } else if (id == MENU_CLEAR) {
                    RawRequestLog.clear();
                } else if (id == MENU_ENABLE) {
                    RawRequestLog.setEnabled(!RawRequestLog.enabled);
                    updateMenu();
                    reload();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchCollapse() {
                query = null;
                reload();
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString().trim();
                reload();
            }
        });
        searchItem.setSearchFieldHint("Метод, например getHistory");
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        pauseItem = other.addSubItem(MENU_PAUSE, R.drawable.msg_round_pause_m, "Пауза");
        errorsItem = other.addSubItem(MENU_ERRORS, R.drawable.msg_report, "Только ошибки");
        other.addSubItem(MENU_CLEAR, R.drawable.msg_delete, "Очистить");
        enableItem = other.addSubItem(MENU_ENABLE, R.drawable.msg_log, "Выключить журнал");
        updateMenu();

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(true);
        if (RawMotion.active()) {
            listView.setItemAnimator(RawMotion.listAnimator());
        }
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < items.size()) {
                openEntry(items.get(position));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return false;
            }
            AndroidUtilities.addToClipboard(items.get(position).method);
            BulletinFactory.of(this).createCopyBulletin("Метод скопирован").show();
            return true;
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        reload();
        return fragmentView;
    }

    private void updateMenu() {
        if (pauseItem != null) {
            pauseItem.setTextAndIcon(RawRequestLog.paused ? "Продолжить" : "Пауза",
                    RawRequestLog.paused ? R.drawable.msg_round_play_m : R.drawable.msg_round_pause_m);
        }
        if (errorsItem != null) {
            errorsItem.setTextAndIcon(errorsOnly ? "Все запросы" : "Только ошибки", R.drawable.msg_report);
        }
        if (enableItem != null) {
            enableItem.setTextAndIcon(RawRequestLog.enabled ? "Выключить журнал" : "Включить журнал", R.drawable.msg_log);
        }
    }

    private void reload() {
        if (adapter == null) {
            return;
        }
        ArrayList<RawRequestLog.Entry> all = RawRequestLog.snapshot();
        String q = TextUtils.isEmpty(query) ? null : query.toLowerCase(Locale.ROOT);
        ArrayList<Long> oldKeys = new ArrayList<>(items.size());
        for (RawRequestLog.Entry e : items) {
            oldKeys.add(e.seq);
        }
        boolean atTop = !listView.canScrollVertically(-1);
        items.clear();
        int errors = 0;
        for (RawRequestLog.Entry e : all) {
            if (e.isError()) {
                errors++;
            }
            if (errorsOnly && !e.isError()) {
                continue;
            }
            if (q != null && !e.method.toLowerCase(Locale.ROOT).contains(q)
                    && (e.errorText == null || !e.errorText.toLowerCase(Locale.ROOT).contains(q))) {
                continue;
            }
            items.add(e);
        }
        ArrayList<Long> newKeys = new ArrayList<>(items.size());
        for (RawRequestLog.Entry e : items) {
            newKeys.add(e.seq);
        }
        // new requests slide in on top, filtered-out ones fold away (stock: a plain refresh)
        RawMotion.dispatch(adapter, oldKeys, newKeys);
        if (atTop && !items.isEmpty()) {
            listView.scrollToPosition(0);
        }

        String state = !RawRequestLog.enabled ? " · выключен" : RawRequestLog.paused ? " · пауза" : "";
        actionBar.setSubtitle(all.size() + " / " + RawRequestLog.CAPACITY + (errors > 0 ? " · ошибок " + errors : "") + state);

        if (items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            if (!RawRequestLog.enabled) {
                emptyView.setText("Журнал выключен.\nВключи его в меню ⋮ или в настройках rawGram — запись начнётся с новых запросов.");
            } else if (!all.isEmpty()) {
                emptyView.setText("Ничего не подходит под фильтр");
            } else {
                emptyView.setText(RawRequestLog.paused ? "На паузе — новые запросы не записываются" : "Пока пусто — запросы появятся здесь");
            }
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void openEntry(RawRequestLog.Entry e) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final Object request = e.hasObjects() && e.getRequest() != null ? e.getRequest() : note(droppedNote(e));
        final Object response;
        if (e.isPending()) {
            response = note("ответ ещё не пришёл");
        } else if (e.getResponse() != null) {
            response = e.getResponse();
        } else if (e.getError() != null) {
            response = e.getError();
        } else if (e.isError()) {
            response = note(e.errorCode + " " + e.errorText + " (объект ошибки не сохранён)");
        } else if (e.isFileConnection() && e.hasObjects()) {
            response = note(e.responseType + ": файловые ответы не сохраняются (буфер освобождается сразу после доставки)");
        } else {
            response = note(droppedNote(e));
        }
        final Object summary = summary(e);
        final String subtitle = resultLine(e);

        RawObjectSheet sheet = new RawObjectSheet(context, e.account, e.method, request, getResourceProvider());
        sheet.addObjectTab("Запрос", () -> sheet.setObject(subtitle, request));
        sheet.addObjectTab("Ответ", () -> sheet.setObject(subtitle, response));
        sheet.addObjectTab("Сводка", () -> sheet.setObject(subtitle, summary));
        sheet.setSubtitle(subtitle);
        showDialog(sheet);
    }

    private static String droppedNote(RawRequestLog.Entry e) {
        return "объект не сохранён: полные объекты хранятся только для последних " + RawRequestLog.KEEP_OBJECTS + " запросов";
    }

    private static Object note(String text) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("note", text);
        return map;
    }

    private Object summary(RawRequestLog.Entry e) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("method", e.method);
        map.put("account", e.account);
        map.put("sent", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(e.sendTime)));
        map.put("duration_ms", e.isPending() ? "pending" : (Object) e.durationMs);
        map.put("result", e.isPending() ? "pending" : e.isError() ? e.errorCode + " " + e.errorText : e.responseType);
        if (e.responseSize > 0) {
            map.put("response_bytes", e.responseSize);
        }
        TLObject request = e.getRequest();
        if (request != null) {
            // serialized size: computed only here, on open
            try {
                map.put("request_bytes", request.getObjectSize());
            } catch (Throwable ignore) {
            }
        }
        map.put("request_token", e.token);
        map.put("datacenter", e.datacenterId == ConnectionsManager.DEFAULT_DATACENTER_ID ? "default" : (Object) e.datacenterId);
        map.put("connection", connectionName(e.connectionType));
        map.put("seq", e.seq);
        return map;
    }

    private static String connectionName(int type) {
        if ((type & ConnectionsManager.ConnectionTypeDownload) != 0) {
            return "download (" + type + ")";
        } else if ((type & ConnectionsManager.ConnectionTypeUpload) != 0) {
            return "upload (" + type + ")";
        } else if ((type & ConnectionsManager.ConnectionTypePush) != 0) {
            return "push (" + type + ")";
        }
        return "generic (" + type + ")";
    }

    private String resultLine(RawRequestLog.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(timeFormat.format(new Date(e.sendTime)));
        if (UserConfig.getActivatedAccountsCount() > 1) {
            sb.append(" · #").append(e.account);
        }
        if (e.isPending()) {
            sb.append(" · ожидание…");
            return sb.toString();
        }
        sb.append(" · ").append(e.durationMs).append(" мс · ");
        if (e.isError()) {
            sb.append(e.errorCode).append(' ').append(e.errorText);
        } else {
            sb.append(e.responseType != null ? e.responseType : "ok");
        }
        if (e.responseSize > 0) {
            sb.append(" · ").append(AndroidUtilities.formatFileSize(e.responseSize));
        }
        return sb.toString();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            EntryCell cell = new EntryCell(context);
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((EntryCell) holder.itemView).bind(items.get(position), position != items.size() - 1);
        }
    }

    /** "method" on top, "time · 123 мс · result · size" below; errors in red. */
    private class EntryCell extends LinearLayout {
        private final TextView methodView;
        private final TextView metaView;
        private boolean divider;

        EntryCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
            setBackground(Theme.getSelectorDrawable(false));

            methodView = new TextView(context);
            methodView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            methodView.setTypeface(Typeface.MONOSPACE);
            methodView.setSingleLine(true);
            methodView.setEllipsize(TextUtils.TruncateAt.END);
            addView(methodView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            metaView = new TextView(context);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(RawRequestLog.Entry e, boolean divider) {
            this.divider = divider;
            boolean error = e.isError();
            methodView.setText(e.method);
            methodView.setTextColor(Theme.getColor(error ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
            metaView.setText(resultLine(e));
            metaView.setTextColor(Theme.getColor(error ? Theme.key_text_RedRegular
                    : e.isPending() ? Theme.key_windowBackgroundWhiteValueText : Theme.key_windowBackgroundWhiteGrayText));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (divider && Theme.dividerPaint != null) {
                canvas.drawLine(AndroidUtilities.dp(16), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
