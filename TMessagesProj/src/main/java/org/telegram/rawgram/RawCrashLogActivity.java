package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * {@link RawCrashLog} viewer. Without a file: list of reports (newest first) + "capture logcat now".
 * With a file: the report split into collapsible section cards (summary, stack, threads, logcat with level
 * filter, exit history, device), rendered one row per line so big sections stay fast.
 */
public class RawCrashLogActivity extends BaseFragment {

    private static final int MENU_SEND_ALL = 1;
    private static final int MENU_CLEAR = 2;
    private static final int MENU_FORWARD = 3;
    private static final int MENU_SHARE = 4;
    private static final int MENU_DELETE = 5;
    private static final int MENU_OTHER = 6;
    private static final int MENU_SAVE = 7;
    private static final int MENU_SUMMARY = 8;
    private static final int MENU_TEST_CRASH = 9;

    private static final int VIEW_ACTION = 0;
    private static final int VIEW_ENTRY = 1;
    private static final int VIEW_INFO = 2;

    private static final int ROW_SECTION = 0;
    private static final int ROW_LINE = 1;
    private static final int ROW_ITEM = 2;
    private static final int ROW_CHIPS = 3;
    private static final int ROW_MORE = 4;
    private static final int ROW_GAP = 5;

    private static final int READ_LIMIT = 1024 * 1024;
    private static final int PAGE = 300;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_E = 1;
    private static final int FILTER_W = 2;
    private static final int FILTER_APP = 3;
    private static final String[] FILTER_NAMES = {"Все", "E", "W", "только org.telegram"};

    private final File reportFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US);
    private final ArrayList<RawCrashLog.Report> items = new ArrayList<>();

    private RecyclerListView listView;
    private ListAdapter adapter;
    private boolean capturing;

    // single report
    private final ArrayList<SectionState> sections = new ArrayList<>();
    private final ArrayList<Row> rows = new ArrayList<>();
    private ReportAdapter reportAdapter;
    private boolean loaded;

    public RawCrashLogActivity() {
        this(null);
    }

    public RawCrashLogActivity(File reportFile) {
        this.reportFile = reportFile;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        RawCrashLog.markSeen();
        if (reportFile != null) {
            return createReportView(context);
        }

        actionBar.setTitle("Падения и логи");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SEND_ALL) {
                    RawReportExport.sendAll(RawCrashLogActivity.this);
                } else if (id == MENU_CLEAR) {
                    confirmClear();
                } else if (id == MENU_TEST_CRASH) {
                    testCrash();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        other.addSubItem(MENU_SEND_ALL, R.drawable.msg_share, "Отправить все (zip)");
        other.addSubItem(MENU_CLEAR, R.drawable.msg_delete, "Очистить");
        if (isDebugBuild()) {
            other.addSubItem(MENU_TEST_CRASH, R.drawable.msg_warning, "Тестовый краш (Java)");
        }

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(true);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position == 0) {
                captureLogcat();
                return;
            }
            RawCrashLog.Report r = entryAt(position);
            if (r != null) {
                presentFragment(new RawCrashLogActivity(r.file));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            RawCrashLog.Report r = entryAt(position);
            if (r == null) {
                return false;
            }
            RawReportExport.showReportActions(this, r.file, () -> {
                r.file.delete();
                reload();
            });
            return true;
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        reload();
        // native crashes / ANRs from the system exit history
        new Thread(() -> {
            if (RawExitReports.importNow() > 0) {
                AndroidUtilities.runOnUIThread(() -> {
                    RawCrashLog.markSeen();
                    reload();
                });
            }
        }, "rawgram-exit-import").start();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (reportFile == null) {
            reload();
        }
    }

    private static boolean isDebugBuild() {
        return BuildVars.DEBUG_PRIVATE_VERSION || BuildVars.DEBUG_VERSION;
    }

    private static void testCrash() {
        RawNotify.show(R.drawable.msg_warning, "Тестовый краш через секунду…");
        AndroidUtilities.runOnUIThread(() -> {
            throw new IllegalStateException("rawGram test crash (Java, UI thread)");
        }, 1200);
    }

    // region list

    private RawCrashLog.Report entryAt(int position) {
        int i = position - 1;
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    private void reload() {
        if (adapter == null) {
            return;
        }
        items.clear();
        items.addAll(RawCrashLog.list());
        int crashes = 0;
        for (RawCrashLog.Report r : items) {
            if (r.isCrash()) {
                crashes++;
            }
        }
        adapter.notifyDataSetChanged();
        actionBar.setSubtitle(items.isEmpty() ? "пусто" : "падений " + crashes + " · всего " + items.size() + " / " + RawCrashLog.KEEP);
    }

    private void captureLogcat() {
        if (capturing) {
            return;
        }
        capturing = true;
        adapter.notifyItemChanged(0);
        new Thread(() -> {
            File file = RawCrashLog.captureSnapshot();
            AndroidUtilities.runOnUIThread(() -> {
                capturing = false;
                reload();
                if (file == null) {
                    BulletinFactory.of(this).createErrorBulletin("Не удалось снять logcat").show();
                } else if (!isFinished) {
                    presentFragment(new RawCrashLogActivity(file));
                }
            });
        }, "rawgram-logcat-snapshot").start();
    }

    private void confirmClear() {
        if (getParentActivity() == null || items.isEmpty()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle("Очистить");
        builder.setMessage("Удалить все отчёты (" + items.size() + ")?");
        builder.setPositiveButton("Удалить", (dialog, which) -> {
            for (File f : RawCrashLog.listFiles()) {
                f.delete();
            }
            reload();
        });
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private String metaLine(RawCrashLog.Report r) {
        StringBuilder sb = new StringBuilder(dateFormat.format(new Date(r.time)));
        sb.append(" · ").append(RawCrashLog.typeLabel(r.type));
        if (!r.isCrash()) {
            sb.append(" · ").append(AndroidUtilities.formatFileSize(r.file.length()));
        } else if (!TextUtils.isEmpty(r.at)) {
            sb.append(" · ").append(r.at.replace("org.telegram.", ""));
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
            return 1 + items.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_ACTION;
            }
            return position == getItemCount() - 1 ? VIEW_INFO : VIEW_ENTRY;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_INFO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_ACTION) {
                TextCell cell = new TextCell(context);
                cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                view = cell;
            } else if (viewType == VIEW_INFO) {
                view = new TextInfoPrivacyCell(context);
            } else {
                view = new EntryCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == VIEW_ACTION) {
                ((TextCell) holder.itemView).setTextAndIcon(capturing ? "Снимаю logcat…" : "Снять logcat и историю завершений", R.drawable.msg_log, !items.isEmpty());
            } else if (type == VIEW_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(items.isEmpty()
                        ? "Падений пока не было. При падении rawGram сохраняет стек, сведения об устройстве и лог, а при следующем запуске сообщает об этом. На Android 11+ сюда попадают и нативные падения и зависания (ANR).\n\n«Снять logcat» сохраняет текущий лог и состояние уведомлений и энергосбережения — пригодится, например, если уведомления приходят с задержкой."
                        : "Хранятся последние " + RawCrashLog.KEEP + " отчётов, только на устройстве. Удерживай отчёт, чтобы переслать, поделиться или сохранить его.");
            } else {
                RawCrashLog.Report r = entryAt(position);
                if (r != null) {
                    ((EntryCell) holder.itemView).bind(r, position < items.size());
                }
            }
        }
    }

    /** Exception on top (red for crashes), "date · kind · first app frame" below. */
    private class EntryCell extends LinearLayout {
        private final TextView titleView;
        private final TextView metaView;
        private boolean divider;

        EntryCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
            setBackground(Theme.getSelectorDrawable(false));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(Typeface.MONOSPACE);
            titleView.setMaxLines(2);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            metaView = new TextView(context);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(RawCrashLog.Report r, boolean divider) {
            this.divider = divider;
            boolean crash = r.isCrash();
            titleView.setText(crash ? RawCrashLog.shortException(r.exception) : "logcat snapshot");
            titleView.setTextColor(Theme.getColor(crash ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
            metaView.setText(metaLine(r));
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

    // endregion

    // region single report: model

    private static class SectionState {
        final RawCrashLog.Section section;
        boolean expanded;
        int shown = PAGE;
        int filter = FILTER_ALL;
        final HashSet<RawCrashLog.Item> open = new HashSet<>();
        final HashMap<RawCrashLog.Item, Integer> itemShown = new HashMap<>();

        SectionState(RawCrashLog.Section section, boolean expanded) {
            this.section = section;
            this.expanded = expanded;
        }

        boolean isLogcat() {
            return RawCrashLog.SEC_LOGCAT.equals(section.id);
        }
    }

    private static class Row {
        final int type;
        SectionState state;
        RawCrashLog.Item item;
        String text;
        int colorKey;
        boolean indent;
        int remaining;

        Row(int type) {
            this.type = type;
        }
    }

    private static int order(String id) {
        switch (id) {
            case RawCrashLog.SEC_SUMMARY: return 0;
            case RawCrashLog.SEC_STACK: return 1;
            case RawCrashLog.SEC_THREADS: return 2;
            case RawCrashLog.SEC_LOGCAT: return 3;
            case RawCrashLog.SEC_EXITS: return 4;
            case RawCrashLog.SEC_DEVICE: return 5;
            default: return 6;
        }
    }

    private static String displayTitle(RawCrashLog.Section s) {
        switch (s.id) {
            case RawCrashLog.SEC_SUMMARY: return "Сводка";
            case RawCrashLog.SEC_STACK: return "Стек";
            case RawCrashLog.SEC_THREADS: return "Другие потоки";
            case RawCrashLog.SEC_LOGCAT: return "logcat";
            case RawCrashLog.SEC_EXITS: return "История завершений";
            case RawCrashLog.SEC_DEVICE: return "Устройство и диагностика";
            default: return s.title;
        }
    }

    private void setParsed(RawCrashLog.Parsed parsed) {
        sections.clear();
        RawCrashLog.Section summary = new RawCrashLog.Section(RawCrashLog.SEC_SUMMARY, "Сводка");
        for (String l : parsed.header) {
            if (!l.startsWith("timestamp: ")) {
                summary.lines.add(l);
            }
        }
        ArrayList<RawCrashLog.Section> all = new ArrayList<>();
        all.add(summary);
        all.addAll(parsed.sections);
        // stable sort by preferred order
        ArrayList<RawCrashLog.Section> sorted = new ArrayList<>();
        for (int o = 0; o <= 6; o++) {
            for (RawCrashLog.Section s : all) {
                if (order(s.id) == o) {
                    sorted.add(s);
                }
            }
        }
        for (RawCrashLog.Section s : sorted) {
            boolean expanded = RawCrashLog.SEC_SUMMARY.equals(s.id) || RawCrashLog.SEC_STACK.equals(s.id);
            sections.add(new SectionState(s, expanded));
        }
        loaded = true;
        buildRows();
    }

    private void buildRows() {
        rows.clear();
        for (SectionState st : sections) {
            rows.add(new Row(ROW_GAP));
            Row header = new Row(ROW_SECTION);
            header.state = st;
            rows.add(header);
            if (!st.expanded) {
                continue;
            }
            if (st.isLogcat()) {
                Row chips = new Row(ROW_CHIPS);
                chips.state = st;
                rows.add(chips);
            }
            List<String> lines = st.isLogcat() ? filtered(st) : st.section.lines;
            if (lines.isEmpty() && st.section.items.isEmpty()) {
                addLine(st, null, st.isLogcat() && st.filter != FILTER_ALL ? "(нет строк под фильтр)" : "(пусто)", false);
            }
            addLines(st, null, lines, st.shown);
            for (RawCrashLog.Item item : st.section.items) {
                Row r = new Row(ROW_ITEM);
                r.state = st;
                r.item = item;
                rows.add(r);
                if (st.open.contains(item)) {
                    Integer shown = st.itemShown.get(item);
                    addLines(st, item, item.lines, shown != null ? shown : PAGE);
                }
            }
        }
        rows.add(new Row(ROW_GAP));
        ArrayList<String> keys = rowKeys();
        if (reportAdapter != null) {
            // rows that stay keep their views and slide, new ones fade in (stock: a plain refresh)
            RawMotion.dispatch(reportAdapter, shownKeys, keys);
        }
        shownKeys = keys;
    }

    private ArrayList<String> shownKeys;

    /** A key per row that survives rebuilds: kind, section, item and the row's ordinal among its kind there. */
    private ArrayList<String> rowKeys() {
        ArrayList<String> keys = new ArrayList<>(rows.size());
        HashMap<String, Integer> counters = new HashMap<>();
        for (Row r : rows) {
            String base = r.type + "|" + (r.state != null ? r.state.section.id : "") + "|" + (r.item != null ? System.identityHashCode(r.item) : 0);
            Integer n = counters.get(base);
            n = n == null ? 0 : n + 1;
            counters.put(base, n);
            keys.add(base + "|" + n);
        }
        return keys;
    }

    private void addLines(SectionState st, RawCrashLog.Item item, List<String> lines, int shown) {
        int n = Math.min(lines.size(), shown);
        for (int i = 0; i < n; i++) {
            addLine(st, item, lines.get(i), i == 0);
        }
        if (lines.size() > n) {
            Row more = new Row(ROW_MORE);
            more.state = st;
            more.item = item;
            more.remaining = lines.size() - n;
            rows.add(more);
        }
    }

    private void addLine(SectionState st, RawCrashLog.Item item, String text, boolean first) {
        Row r = new Row(ROW_LINE);
        r.state = st;
        r.item = item;
        r.text = text;
        r.indent = item != null;
        r.colorKey = lineColor(st, text, first);
        rows.add(r);
    }

    private static int lineColor(SectionState st, String line, boolean first) {
        if (st.isLogcat()) {
            switch (RawCrashLog.logLevel(line)) {
                case 'E':
                case 'F':
                case 'A':
                    return Theme.key_text_RedRegular;
                case 'W':
                    return Theme.key_color_orange;
                case 'I':
                    return Theme.key_windowBackgroundWhiteBlackText;
                default:
                    return Theme.key_windowBackgroundWhiteGrayText;
            }
        }
        String id = st.section.id;
        if (RawCrashLog.SEC_SUMMARY.equals(id)) {
            return line.startsWith("exception: ") ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText;
        }
        if (line.startsWith("Caused by") || line.startsWith("signal ") || line.startsWith("Cause: ") || line.startsWith("Abort message")
                || first && RawCrashLog.SEC_STACK.equals(id) && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("pid: ") && !line.startsWith("\"")) {
            return Theme.key_text_RedRegular;
        }
        if (line.contains("org.telegram.") || line.contains("libtmessages")) {
            return Theme.key_windowBackgroundWhiteBlueText4;
        }
        return Theme.key_windowBackgroundWhiteBlackText;
    }

    private static List<String> filtered(SectionState st) {
        if (st.filter == FILTER_ALL) {
            return st.section.lines;
        }
        ArrayList<String> out = new ArrayList<>();
        for (String l : st.section.lines) {
            if (st.filter == FILTER_APP) {
                if (l.contains("org.telegram") || l.contains(" tmessages") || l.contains("rawgram") || l.contains("rawGram")) {
                    out.add(l);
                }
                continue;
            }
            char lv = RawCrashLog.logLevel(l);
            boolean error = lv == 'E' || lv == 'F' || lv == 'A';
            if (error || st.filter == FILTER_W && lv == 'W') {
                out.add(l);
            }
        }
        return out;
    }

    private String sectionText(SectionState st) {
        return st.section.toText();
    }

    private String sectionMeta(SectionState st) {
        RawCrashLog.Section s = st.section;
        if (RawCrashLog.SEC_THREADS.equals(s.id)) {
            return "потоков: " + s.items.size();
        }
        if (RawCrashLog.SEC_EXITS.equals(s.id)) {
            return "записей: " + s.items.size();
        }
        if (!s.items.isEmpty()) {
            return "строк: " + s.lines.size() + " · блоков: " + s.items.size();
        }
        return "строк: " + s.lines.size();
    }

    // endregion

    // region single report: view

    private View createReportView(Context context) {
        RawCrashLog.Report header = RawCrashLog.readHeader(reportFile);
        String type = header.type;
        actionBar.setTitle(RawCrashLog.TYPE_LOGCAT.equals(type) ? "logcat"
                : RawCrashLog.TYPE_ANR.equals(type) ? "Зависание (ANR)"
                : RawCrashLog.TYPE_KILLED.equals(type) ? "Убит системой"
                : "Падение (" + RawCrashLog.typeLabel(type) + ")");
        actionBar.setSubtitle(dateFormat.format(new Date(header.time)) + " · " + AndroidUtilities.formatFileSize(reportFile.length()));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_FORWARD) {
                    RawReportExport.forwardReport(RawCrashLogActivity.this, reportFile);
                } else if (id == MENU_SHARE) {
                    RawReportExport.shareReport(RawCrashLogActivity.this, reportFile);
                } else if (id == MENU_SAVE) {
                    RawReportExport.saveReport(RawCrashLogActivity.this, reportFile);
                } else if (id == MENU_SUMMARY) {
                    RawReportExport.copySummary(reportFile);
                } else if (id == MENU_DELETE) {
                    reportFile.delete();
                    finishFragment();
                } else if (id == MENU_TEST_CRASH) {
                    testCrash();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_FORWARD, R.drawable.msg_forward);
        menu.addItem(MENU_SHARE, R.drawable.msg_share);
        ActionBarMenuItem other = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        other.addSubItem(MENU_SAVE, R.drawable.msg_download, "Сохранить в загрузки");
        other.addSubItem(MENU_SUMMARY, R.drawable.msg_copy, "Копировать сводку");
        other.addSubItem(MENU_DELETE, R.drawable.msg_delete, "Удалить");
        if (isDebugBuild()) {
            other.addSubItem(MENU_TEST_CRASH, R.drawable.msg_warning, "Тестовый краш (Java)");
        }

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(true);
        listView.setItemAnimator(RawMotion.active() ? RawMotion.listAnimator() : null);
        listView.setAdapter(reportAdapter = new ReportAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row r = rows.get(position);
            if (r.type == ROW_SECTION) {
                r.state.expanded = !r.state.expanded;
            } else if (r.type == ROW_ITEM) {
                if (!r.state.open.remove(r.item)) {
                    r.state.open.add(r.item);
                }
            } else if (r.type == ROW_MORE) {
                if (r.item != null) {
                    Integer shown = r.state.itemShown.get(r.item);
                    r.state.itemShown.put(r.item, (shown != null ? shown : PAGE) + PAGE);
                } else {
                    r.state.shown += PAGE;
                }
            } else {
                return;
            }
            buildRows();
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final File file = reportFile;
        new Thread(() -> {
            RawCrashLog.Parsed parsed = RawCrashLog.parse(file, READ_LIMIT);
            AndroidUtilities.runOnUIThread(() -> {
                if (!isFinished) {
                    setParsed(parsed);
                }
            });
        }, "rawgram-crash-read").start();
        return fragmentView;
    }

    private class ReportAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ReportAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return loaded ? rows.size() : 0;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int t = holder.getItemViewType();
            return t == ROW_SECTION || t == ROW_ITEM || t == ROW_MORE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case ROW_SECTION:
                    view = new SectionCell(context);
                    break;
                case ROW_ITEM:
                    view = new ItemCell(context);
                    break;
                case ROW_CHIPS:
                    view = new ChipsCell(context);
                    break;
                case ROW_MORE:
                    view = new MoreCell(context);
                    break;
                case ROW_GAP:
                    view = new View(context);
                    view.setMinimumHeight(AndroidUtilities.dp(10));
                    break;
                default:
                    view = new LineCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row r = rows.get(position);
            switch (r.type) {
                case ROW_SECTION:
                    ((SectionCell) holder.itemView).bind(r.state);
                    break;
                case ROW_ITEM:
                    ((ItemCell) holder.itemView).bind(r.item, r.state.open.contains(r.item));
                    break;
                case ROW_CHIPS:
                    ((ChipsCell) holder.itemView).bind(r.state);
                    break;
                case ROW_MORE:
                    ((MoreCell) holder.itemView).bind(r.remaining);
                    break;
                case ROW_LINE:
                    ((LineCell) holder.itemView).bind(r);
                    break;
            }
        }
    }

    private ImageView iconButton(Context context, int icon) {
        ImageView iv = new ImageView(context);
        iv.setImageResource(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        iv.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        return iv;
    }

    /** Card header: chevron, title + size, copy and share of this section. */
    private class SectionCell extends LinearLayout {
        private final ImageView arrow;
        private final TextView titleView;
        private final TextView metaView;
        private final ImageView copyView;
        private final ImageView shareView;
        private SectionState state;

        SectionCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(Theme.getSelectorDrawable(true));
            setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(6), 0);
            setMinimumHeight(AndroidUtilities.dp(52));

            arrow = new ImageView(context);
            arrow.setImageResource(R.drawable.arrow_more);
            arrow.setScaleType(ImageView.ScaleType.CENTER);
            arrow.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            addView(arrow, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            metaView = new TextView(context);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            metaView.setSingleLine(true);
            texts.addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 6, 0, 6));

            copyView = iconButton(context, R.drawable.msg_copy);
            copyView.setOnClickListener(v -> {
                if (state != null) {
                    AndroidUtilities.addToClipboard(RawCrashLog.forClipboard(sectionText(state)));
                    BulletinFactory.of(RawCrashLogActivity.this).createCopyBulletin("Раздел «" + displayTitle(state.section) + "» скопирован").show();
                    RawMotion.copied(v);
                }
            });
            addView(copyView, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
            shareView = iconButton(context, R.drawable.msg_share);
            shareView.setOnClickListener(v -> {
                if (state != null) {
                    RawReportExport.shareSection(RawCrashLogActivity.this, reportFile, state.section.id, sectionText(state));
                }
            });
            addView(shareView, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        }

        void bind(SectionState state) {
            boolean same = this.state == state;
            this.state = state;
            titleView.setText(displayTitle(state.section));
            metaView.setText(sectionMeta(state));
            float rotation = state.expanded ? 90 : 0;
            if (same && isAttachedToWindow() && RawMotion.active()) {
                // the same card toggled: turn the chevron instead of snapping it
                if (arrow.getRotation() != rotation) {
                    arrow.animate().rotation(rotation).setDuration(300).setInterpolator(RawMotion.EMPHASIZED).start();
                }
            } else {
                arrow.animate().cancel();
                arrow.setRotation(rotation);
            }
        }
    }

    /** A thread / exit inside a section; tap expands just this block. */
    private class ItemCell extends FrameLayout {
        private final TextView textView;

        ItemCell(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(true));
            textView = new TextView(context);
            textView.setTypeface(Typeface.MONOSPACE);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(RawCrashLog.Item item, boolean open) {
            textView.setText((open ? "▾ " : "▸ ") + item.title + (open ? "" : "  · " + item.lines.size()));
            textView.setTextColor(Theme.getColor(item.title.contains("CRASH") || item.title.contains("ANR")
                    ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
        }
    }

    /** One monospace line, wrapped; long press copies it. */
    private class LineCell extends FrameLayout {
        private final TextView textView;
        private String text;

        LineCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            textView = new TextView(context);
            textView.setTypeface(Typeface.MONOSPACE);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            setOnLongClickListener(v -> {
                if (text != null) {
                    AndroidUtilities.addToClipboard(text);
                    BulletinFactory.of(RawCrashLogActivity.this).createCopyBulletin("Строка скопирована").show();
                    return true;
                }
                return false;
            });
        }

        void bind(Row r) {
            text = r.text;
            textView.setText(r.text.isEmpty() ? " " : r.text);
            textView.setTextColor(Theme.getColor(r.colorKey));
            int left = AndroidUtilities.dp(r.indent ? 26 : 14);
            textView.setPadding(left, AndroidUtilities.dp(1), AndroidUtilities.dp(12), AndroidUtilities.dp(1));
        }
    }

    private class MoreCell extends FrameLayout {
        private final TextView textView;

        MoreCell(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(true));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
            textView.setGravity(Gravity.CENTER);
            textView.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(int remaining) {
            textView.setText("Показать ещё " + Math.min(PAGE, remaining) + " (осталось " + remaining + ")");
        }
    }

    /** logcat level filter: Все / E / W / только org.telegram. */
    private class ChipsCell extends LinearLayout {
        private final TextView[] chips = new TextView[FILTER_NAMES.length];
        private SectionState state;

        ChipsCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
            for (int i = 0; i < chips.length; i++) {
                final int filter = i;
                TextView chip = new TextView(context);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                chip.setText(FILTER_NAMES[i]);
                chip.setSingleLine(true);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(5), AndroidUtilities.dp(12), AndroidUtilities.dp(5));
                chip.setOnClickListener(v -> {
                    if (state != null && state.filter != filter) {
                        state.filter = filter;
                        state.shown = PAGE;
                        buildRows();
                    }
                });
                chips[i] = chip;
                addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
            }
        }

        void bind(SectionState state) {
            this.state = state;
            for (int i = 0; i < chips.length; i++) {
                boolean selected = state.filter == i;
                int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
                chips[i].setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14),
                        selected ? accent : Theme.getColor(Theme.key_windowBackgroundGray)));
                chips[i].setTextColor(selected ? 0xffffffff : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
        }
    }

    // endregion

    @Override
    public void onFragmentDestroy() {
        reportAdapter = null;
        super.onFragmentDestroy();
    }
}
