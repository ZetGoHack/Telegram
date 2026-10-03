package org.telegram.rawgram;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;

/**
 * "Настройки rawGram": a hub of category rows (icon + short subtitle, like Telegram's main settings) that open
 * the actual settings screens, plus credits and the version line.
 */
public class RawgramSettingsActivity extends BaseFragment {

    private static final int TYPE_CATEGORY = 0;
    private static final int TYPE_SHADOW = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;

    /** One list row. For TYPE_CATEGORY: icon, icon background, title, subtitle and what a tap opens. */
    private static class Row {
        final int type;
        final int icon;
        final IconBackgroundColors colors;
        final String title;
        final String subtitle;
        final Runnable action;

        Row(int type, int icon, IconBackgroundColors colors, String title, String subtitle, Runnable action) {
            this.type = type;
            this.icon = icon;
            this.colors = colors;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView listView;

    private void category(int icon, IconBackgroundColors colors, String title, String subtitle, Runnable action) {
        rows.add(new Row(TYPE_CATEGORY, icon, colors, title, subtitle, action));
    }

    private void buildRows() {
        rows.clear();
        category(R.drawable.msg_palette, IconBackgroundColors.PURPLE, "Внешний вид",
                "Главный экран, папки, аватарки, иконки", () -> presentFragment(new RawUiSettingsActivity()));
        category(R.drawable.msg_discussion, IconBackgroundColors.BLUE, "Чаты",
                "Вид чата, меню сообщения, стикеры", () -> presentFragment(new RawChatUiSettingsActivity()));
        category(R.drawable.settings_rawgram, IconBackgroundColors.GRAY, "Инструменты разработчика",
                "Raw-данные, журналы, конфиг сервера", () -> presentFragment(new RawDevSettingsActivity()));
        // Плагины (branch rawgram-plugins): add one line here, e.g.
        // category(R.drawable.msg_bots, IconBackgroundColors.GREEN, "Плагины", "Установленные плагины", () -> presentFragment(new RawPluginsActivity()));
        rows.add(new Row(TYPE_SHADOW, 0, null, null, null, null));
        rows.add(new Row(TYPE_VALUE, 0, null, "Авторы и лицензии", null, this::showCredits));
        rows.add(new Row(TYPE_INFO, 0, null, "rawGram на основе Telegram " + BuildVars.BUILD_VERSION_STRING + " · GPLv3", null, null));
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Настройки rawGram");
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
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
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
            return type == TYPE_CATEGORY || type == TYPE_VALUE;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_CATEGORY) {
                view = new CategoryCell(context);
            } else if (viewType == TYPE_SHADOW) {
                view = new ShadowSectionCell(context);
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
                case TYPE_CATEGORY:
                    ((CategoryCell) holder.itemView).set(row);
                    break;
                case TYPE_VALUE:
                    ((TextSettingsCell) holder.itemView).setText(row.title, false);
                    break;
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
            }
        }
    }

    /** Title + subtitle with a white icon on a rounded gradient square, as in Telegram's main settings list. */
    private static class CategoryCell extends LinearLayout {
        private final SettingsActivity.SettingCell.Background iconBackground;
        private final ImageView iconView;
        private final TextView titleView;
        private final TextView subtitleView;

        CategoryCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);

            FrameLayout iconLayout = new FrameLayout(context);
            iconLayout.setBackground(iconBackground = new SettingsActivity.SettingCell.Background());
            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            iconLayout.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

            LinearLayout textLayout = new LinearLayout(context);
            textLayout.setOrientation(VERTICAL);
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            if (LocaleController.isRTL) {
                addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL, 20, 0, 18, 0));
                addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
            } else {
                addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
                addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL, 18, 0, 20, 0));
            }
            iconBackground.setDrawBorder(Theme.isCurrentThemeDark());
        }

        void set(Row row) {
            iconBackground.setColor(row.colors.top, row.colors.bottom);
            iconView.setImageResource(row.icon);
            titleView.setText(row.title);
            subtitleView.setText(row.subtitle);
            subtitleView.setVisibility(TextUtils.isEmpty(row.subtitle) ? GONE : VISIBLE);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(60), MeasureSpec.EXACTLY));
        }
    }
}
