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
import org.telegram.tgnet.TLRPC;
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

/**
 * Viewer for {@link RawUpdatesLog}: newest first; each row is one push — the container type and its inner updates
 * («updateNewMessage, updateReadHistoryInbox ×2»). Filter by any type name, hide noise (statuses, typing), pause,
 * clear, change the limit. A tap opens the push: its updates, the whole object and a summary.
 */
public class RawUpdatesLogActivity extends BaseFragment {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_PAUSE = 2;
    private static final int MENU_NOISE = 3;
    private static final int MENU_CLEAR = 4;
    private static final int MENU_ENABLE = 5;
    private static final int MENU_LIMIT = 6;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final ArrayList<RawUpdatesLog.Entry> items = new ArrayList<>();
    private final Runnable listener = this::reload;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private ActionBarMenuSubItem pauseItem, noiseItem, enableItem;
    private String query;
    private boolean showNoise;

    @Override
    public boolean onFragmentCreate() {
        RawUpdatesLog.addListener(listener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        RawUpdatesLog.removeListener(listener);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Журнал апдейтов");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_PAUSE) {
                    RawUpdatesLog.paused = !RawUpdatesLog.paused;
                    updateMenu();
                    reload();
                } else if (id == MENU_NOISE) {
                    showNoise = !showNoise;
                    updateMenu();
                    reload();
                } else if (id == MENU_CLEAR) {
                    RawUpdatesLog.clear();
                } else if (id == MENU_ENABLE) {
                    RawUpdatesLog.setEnabled(!RawUpdatesLog.enabled);
                    updateMenu();
                    reload();
                } else if (id == MENU_LIMIT) {
                    RawLogLimit.ask(RawUpdatesLogActivity.this, "Лимит журнала апдейтов", RawUpdatesLog.limit(), RawUpdatesLog::setLimit);
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
        searchItem.setSearchFieldHint("Тип, например updateNewMessage");
        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        pauseItem = other.addSubItem(MENU_PAUSE, R.drawable.msg_round_pause_m, "Пауза");
        noiseItem = other.addSubItem(MENU_NOISE, R.drawable.msg_mute, "Показать шумные");
        other.addSubItem(MENU_LIMIT, R.drawable.msg_settings, "Лимит записей");
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
            RawUpdatesLog.Entry e = items.get(position);
            AndroidUtilities.addToClipboard(e.summary.isEmpty() ? e.type : e.summary);
            BulletinFactory.of(this).createCopyBulletin("Типы скопированы").show();
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
            pauseItem.setTextAndIcon(RawUpdatesLog.paused ? "Продолжить" : "Пауза",
                    RawUpdatesLog.paused ? R.drawable.msg_round_play_m : R.drawable.msg_round_pause_m);
        }
        if (noiseItem != null) {
            noiseItem.setTextAndIcon(showNoise ? "Скрыть шумные" : "Показать шумные", showNoise ? R.drawable.msg_mute : R.drawable.msg_unmute);
        }
        if (enableItem != null) {
            enableItem.setTextAndIcon(RawUpdatesLog.enabled ? "Выключить журнал" : "Включить журнал", R.drawable.msg_log);
        }
    }

    private void reload() {
        if (adapter == null) {
            return;
        }
        ArrayList<RawUpdatesLog.Entry> all = RawUpdatesLog.snapshot();
        String q = TextUtils.isEmpty(query) ? null : query.toLowerCase(Locale.ROOT);
        ArrayList<Long> oldKeys = new ArrayList<>(items.size());
        for (RawUpdatesLog.Entry e : items) {
            oldKeys.add(e.seq);
        }
        boolean atTop = !listView.canScrollVertically(-1);
        items.clear();
        int hidden = 0;
        for (RawUpdatesLog.Entry e : all) {
            if (!showNoise && e.noise) {
                hidden++;
                continue;
            }
            if (q != null && !e.type.toLowerCase(Locale.ROOT).contains(q) && !e.summary.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            items.add(e);
        }
        ArrayList<Long> newKeys = new ArrayList<>(items.size());
        for (RawUpdatesLog.Entry e : items) {
            newKeys.add(e.seq);
        }
        RawMotion.dispatch(adapter, oldKeys, newKeys);
        if (atTop && !items.isEmpty()) {
            listView.scrollToPosition(0);
        }

        String state = !RawUpdatesLog.enabled ? " · выключен" : RawUpdatesLog.paused ? " · пауза" : "";
        actionBar.setSubtitle(all.size() + " / " + RawLogBuffer.limitText(RawUpdatesLog.limit())
                + (hidden > 0 ? " · шумных скрыто " + hidden : "") + state);

        if (items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            if (!RawUpdatesLog.enabled) {
                emptyView.setText("Журнал выключен.\nВключи его в меню ⋮ или в «Инструментах разработчика» — запись начнётся с новых апдейтов.");
            } else if (!all.isEmpty()) {
                emptyView.setText("Ничего не подходит под фильтр");
            } else {
                emptyView.setText(RawUpdatesLog.paused ? "На паузе — новые апдейты не записываются" : "Пока пусто — апдейты от сервера появятся здесь");
            }
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void openEntry(RawUpdatesLog.Entry e) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        String subtitle = metaLine(e);
        Object whole = e.object != null ? e.object : note("конструктор " + String.format("0x%08x", e.constructor) + " клиенту неизвестен — объект не разобран");
        ArrayList<TLRPC.Update> updates = e.updates();
        Object updatesObject = updates.isEmpty() ? whole : updates.size() == 1 ? updates.get(0) : updates;

        RawObjectSheet sheet = new RawObjectSheet(context, e.account, e.summary.isEmpty() ? e.type : e.summary, updatesObject, getResourceProvider());
        sheet.addObjectTab("Апдейты", () -> sheet.setObject(subtitle, updatesObject));
        sheet.addObjectTab("Целиком", () -> sheet.setObject(subtitle, whole));
        sheet.addObjectTab("Сводка", () -> sheet.setObject(subtitle, summary(e)));
        sheet.setSubtitle(subtitle);
        showDialog(sheet);
    }

    private Object summary(RawUpdatesLog.Entry e) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("type", e.type);
        map.put("updates", e.inner);
        map.put("account", e.account);
        map.put("received", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(e.time)));
        map.put("msg_id", e.messageId);
        if (e.object instanceof TLRPC.Updates) {
            TLRPC.Updates u = (TLRPC.Updates) e.object;
            map.put("date", u.date);
            map.put("seq", u.seq);
            if (u.users != null && !u.users.isEmpty()) {
                map.put("users", u.users.size());
            }
            if (u.chats != null && !u.chats.isEmpty()) {
                map.put("chats", u.chats.size());
            }
        }
        map.put("log_seq", e.seq);
        return map;
    }

    private static Object note(String text) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("note", text);
        return map;
    }

    private String metaLine(RawUpdatesLog.Entry e) {
        StringBuilder sb = new StringBuilder(timeFormat.format(new Date(e.time)));
        if (UserConfig.getActivatedAccountsCount() > 1) {
            sb.append(" · #").append(e.account);
        }
        sb.append(" · ").append(e.type);
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

    /** Inner update types on top (monospace), «time · container» below; unknown constructors in red. */
    private class EntryCell extends LinearLayout {
        private final TextView typesView;
        private final TextView metaView;
        private boolean divider;

        EntryCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
            setBackground(Theme.getSelectorDrawable(false));

            typesView = new TextView(context);
            typesView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            typesView.setTypeface(Typeface.MONOSPACE);
            typesView.setMaxLines(2);
            typesView.setEllipsize(TextUtils.TruncateAt.END);
            addView(typesView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            metaView = new TextView(context);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(RawUpdatesLog.Entry e, boolean divider) {
            this.divider = divider;
            boolean unknown = e.object == null;
            typesView.setText(e.summary.isEmpty() ? e.type : e.summary);
            typesView.setTextColor(Theme.getColor(unknown ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
            metaView.setText(metaLine(e));
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
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
