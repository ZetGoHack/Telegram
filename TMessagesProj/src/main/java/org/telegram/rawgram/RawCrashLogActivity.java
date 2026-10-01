package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
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
import java.util.Locale;

/**
 * {@link RawCrashLog} viewer. Without a file: list of reports (newest first) + "capture logcat now".
 * With a file: the full report in a monospace scroll view with copy / share / delete.
 */
public class RawCrashLogActivity extends BaseFragment {

    private static final int MENU_COPY_ALL = 1;
    private static final int MENU_CLEAR = 2;
    private static final int MENU_COPY = 3;
    private static final int MENU_SHARE = 4;
    private static final int MENU_DELETE = 5;
    private static final int MENU_OTHER = 6;

    private static final int VIEW_ACTION = 0;
    private static final int VIEW_ENTRY = 1;
    private static final int VIEW_INFO = 2;

    // TextView with a few hundred KB is still fine; beyond that use share
    private static final int DISPLAY_LIMIT = 400 * 1024;

    private final File reportFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US);
    private final ArrayList<RawCrashLog.Report> items = new ArrayList<>();

    private RecyclerListView listView;
    private ListAdapter adapter;
    private boolean capturing;
    private TextView reportView;

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
                } else if (id == MENU_COPY_ALL) {
                    copyAll();
                } else if (id == MENU_CLEAR) {
                    confirmClear();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem other = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        other.addSubItem(MENU_COPY_ALL, R.drawable.msg_copy, "Копировать всё");
        other.addSubItem(MENU_CLEAR, R.drawable.msg_delete, "Очистить");

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
            AndroidUtilities.addToClipboard(RawCrashLog.forClipboard(RawCrashLog.read(r.file, RawCrashLog.CLIPBOARD_LIMIT)));
            BulletinFactory.of(this).createCopyBulletin("Отчёт скопирован").show();
            return true;
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        reload();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (reportFile == null) {
            reload();
        }
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

    private void copyAll() {
        if (items.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin("Нечего копировать").show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (RawCrashLog.Report r : items) {
            if (sb.length() >= RawCrashLog.CLIPBOARD_LIMIT) {
                break;
            }
            sb.append("===== ").append(r.file.getName()).append(" =====\n");
            sb.append(RawCrashLog.read(r.file, RawCrashLog.CLIPBOARD_LIMIT)).append("\n\n");
        }
        AndroidUtilities.addToClipboard(RawCrashLog.forClipboard(sb.toString()));
        BulletinFactory.of(this).createCopyBulletin("Скопировано отчётов: " + items.size()).show();
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
        if (!r.isCrash()) {
            sb.append(" · logcat · ").append(AndroidUtilities.formatFileSize(r.file.length()));
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
                        ? "Падений пока не было. При падении rawGram сохраняет стек, сведения об устройстве и хвост logcat; при следующем запуске покажет уведомление.\n\n«Снять logcat» сохраняет текущий лог процесса и состояние пушей/энергосбережения — пригодится, если, например, уведомления приходят с задержкой."
                        : "Хранятся последние " + RawCrashLog.KEEP + " отчётов, только на устройстве. Долгое нажатие — скопировать отчёт.");
            } else {
                RawCrashLog.Report r = entryAt(position);
                if (r != null) {
                    ((EntryCell) holder.itemView).bind(r, position < items.size());
                }
            }
        }
    }

    /** Exception on top (red for crashes), "date · first app frame" below. */
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

    // region single report

    private View createReportView(Context context) {
        RawCrashLog.Report header = RawCrashLog.readHeader(reportFile);
        actionBar.setTitle(header.isCrash() ? "Падение" : "logcat");
        actionBar.setSubtitle(dateFormat.format(new Date(header.time)) + " · " + AndroidUtilities.formatFileSize(reportFile.length()));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_COPY) {
                    AndroidUtilities.addToClipboard(RawCrashLog.forClipboard(RawCrashLog.read(reportFile, RawCrashLog.CLIPBOARD_LIMIT)));
                    BulletinFactory.of(RawCrashLogActivity.this).createCopyBulletin("Отчёт скопирован").show();
                } else if (id == MENU_SHARE) {
                    RawCrashLog.share(RawCrashLogActivity.this, reportFile);
                } else if (id == MENU_DELETE) {
                    reportFile.delete();
                    finishFragment();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_COPY, R.drawable.msg_copy);
        menu.addItem(MENU_SHARE, R.drawable.msg_share);
        ActionBarMenuItem other = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        other.addSubItem(MENU_DELETE, R.drawable.msg_delete, "Удалить");

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(true);
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        reportView = new TextView(context);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        reportView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        reportView.setGravity(Gravity.START | Gravity.TOP);
        reportView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(16));
        reportView.setText("…");
        scrollView.addView(reportView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final File file = reportFile;
        new Thread(() -> {
            String text = RawCrashLog.read(file, DISPLAY_LIMIT);
            AndroidUtilities.runOnUIThread(() -> {
                if (reportView != null) {
                    reportView.setText(text);
                    reportView.setTextIsSelectable(true);
                }
            });
        }, "rawgram-crash-read").start();
        return fragmentView;
    }

    // endregion

    @Override
    public void onFragmentDestroy() {
        reportView = null;
        super.onFragmentDestroy();
    }
}
