package org.telegram.rawgram;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
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
import org.telegram.ui.Components.SeekBarView;

/** "Интерфейс": rawGram look-and-feel options. Every option off / default = stock Telegram. */
public class RawUiSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;
    private static final int TYPE_SLIDER = 4;

    private int rowCount;

    private int lookHeaderRow;
    private int hideDividersRow;
    private int systemFontRow;
    private int switchStyleRow;
    private int lookInfoRow;

    private int avatarHeaderRow;
    private int avatarSliderRow;
    private int avatarInfoRow;

    private int settingsHeaderRow;
    private int hidePremiumRow;
    private int hideHelpRow;
    private int settingsInfoRow;

    private int bottomTabsHeaderRow;
    private int tabsHideTitlesRow;
    private int tabsHideContactsRow;
    private int bottomTabsInfoRow;

    private int foldersHeaderRow;
    private int hideAllTabRow;
    private int tabsTitleTypeRow;
    private int foldersInfoRow;

    private int chatsHeaderRow;
    private int hideSearchRow;
    private int hideFabRow;
    private int showSecondsRow;
    private int chatsInfoRow;

    private int titleHeaderRow;
    private int titleModeRow;
    private int customTitleRow;
    private int folderTitleRow;
    private int snowRow;
    private int titleInfoRow;

    private int creditsRow;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private boolean needRebuild;

    private void updateRows() {
        rowCount = 0;
        lookHeaderRow = rowCount++;
        hideDividersRow = rowCount++;
        systemFontRow = rowCount++;
        switchStyleRow = rowCount++;
        lookInfoRow = rowCount++;

        avatarHeaderRow = rowCount++;
        avatarSliderRow = rowCount++;
        avatarInfoRow = rowCount++;

        settingsHeaderRow = rowCount++;
        hidePremiumRow = rowCount++;
        hideHelpRow = rowCount++;
        settingsInfoRow = rowCount++;

        bottomTabsHeaderRow = rowCount++;
        tabsHideTitlesRow = rowCount++;
        tabsHideContactsRow = rowCount++;
        bottomTabsInfoRow = rowCount++;

        foldersHeaderRow = rowCount++;
        hideAllTabRow = rowCount++;
        tabsTitleTypeRow = rowCount++;
        foldersInfoRow = rowCount++;

        chatsHeaderRow = rowCount++;
        hideSearchRow = rowCount++;
        hideFabRow = rowCount++;
        showSecondsRow = rowCount++;
        chatsInfoRow = rowCount++;

        titleHeaderRow = rowCount++;
        titleModeRow = rowCount++;
        customTitleRow = RawUiConfig.titleMode() == RawUiConfig.TITLE_CUSTOM ? rowCount++ : -1;
        folderTitleRow = rowCount++;
        snowRow = rowCount++;
        titleInfoRow = rowCount++;

        creditsRow = rowCount++;
    }

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (needRebuild && parentLayout != null) {
            // re-create the chats list, settings and tabs so the changed options show up
            final org.telegram.ui.ActionBar.INavigationLayout layout = parentLayout;
            AndroidUtilities.runOnUIThread(() -> layout.rebuildAllFragmentViews(false, false));
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Интерфейс");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener(this::onRowClick);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void onRowClick(View view, int position) {
        if (position == hideDividersRow) {
            boolean v = !RawUiConfig.hideDividers();
            RawUiConfig.setHideDividers(v);
            Theme.applyCommonTheme();
            toggled(view, v);
        } else if (position == systemFontRow) {
            boolean v = !RawUiConfig.systemFont();
            RawUiConfig.setSystemFont(v);
            toggled(view, v);
        } else if (position == hidePremiumRow) {
            boolean v = !RawUiConfig.hidePremiumSection();
            RawUiConfig.setHidePremiumSection(v);
            toggled(view, v);
        } else if (position == hideHelpRow) {
            boolean v = !RawUiConfig.hideHelpSection();
            RawUiConfig.setHideHelpSection(v);
            toggled(view, v);
        } else if (position == tabsHideTitlesRow) {
            boolean v = !RawUiConfig.mainTabsHideTitles();
            RawUiConfig.setMainTabsHideTitles(v);
            toggled(view, v);
        } else if (position == tabsHideContactsRow) {
            boolean v = !RawUiConfig.mainTabsHideContacts();
            RawUiConfig.setMainTabsHideContacts(v);
            toggled(view, v);
        } else if (position == hideAllTabRow) {
            boolean v = !RawUiConfig.hideAllTab();
            RawUiConfig.setHideAllTab(v);
            toggled(view, v);
        } else if (position == hideSearchRow) {
            boolean v = !RawUiConfig.hideDialogsSearchField();
            RawUiConfig.setHideDialogsSearchField(v);
            toggled(view, v);
        } else if (position == hideFabRow) {
            boolean v = !RawUiConfig.disableDialogsFab();
            RawUiConfig.setDisableDialogsFab(v);
            toggled(view, v);
        } else if (position == showSecondsRow) {
            boolean v = !RawUiConfig.showSeconds();
            RawUiConfig.setShowSeconds(v);
            LocaleController.getInstance().recreateFormatters();
            toggled(view, v);
        } else if (position == folderTitleRow) {
            boolean v = !RawUiConfig.folderNameAsTitle();
            RawUiConfig.setFolderNameAsTitle(v);
            toggled(view, v);
        } else if (position == switchStyleRow) {
            choose("Стиль переключателей", new CharSequence[]{"Стандартный", "Современный", "Material 3"}, which -> {
                RawUiConfig.setSwitchStyle(which);
                listView.invalidateViews();
                adapter.notifyDataSetChanged();
            });
        } else if (position == tabsTitleTypeRow) {
            choose("Вкладки папок", new CharSequence[]{"Текст", "Иконки", "Иконки и текст"}, RawUiConfig::setTabsTitleType);
        } else if (position == titleModeRow) {
            choose("Заголовок", new CharSequence[]{"Telegram (логотип)", "Свой текст", "Имя аккаунта"}, which -> {
                RawUiConfig.setTitleMode(which);
                if (which == RawUiConfig.TITLE_CUSTOM && RawUiConfig.customTitle().trim().isEmpty()) {
                    editCustomTitle();
                }
            });
        } else if (position == customTitleRow) {
            editCustomTitle();
        } else if (position == snowRow) {
            choose("Снег на главном экране", new CharSequence[]{"По дате (как в Telegram)", "Всегда", "Выключен"}, RawUiConfig::setSnowMode);
        }
    }

    private void toggled(View view, boolean value) {
        needRebuild = true;
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }

    private void choose(String title, CharSequence[] names, Utilities.Callback<Integer> onChosen) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(title)
                .setItems(names, (d, which) -> {
                    onChosen.run(which);
                    needRebuild = true;
                    updateRows();
                    adapter.notifyDataSetChanged();
                })
                .show();
    }

    private void editCustomTitle() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHint("rawGram");
        editText.setText(RawUiConfig.customTitle());
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        editText.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));

        FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 0));

        AlertDialog dialog = new AlertDialog.Builder(context, getResourceProvider())
                .setTitle("Свой заголовок")
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
                    RawUiConfig.setCustomTitle(editText.getText().toString());
                    needRebuild = true;
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            editText.setSelection(editText.length());
            AndroidUtilities.showKeyboard(editText);
        }, 150));
        showDialog(dialog);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_CHECK || type == TYPE_VALUE;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == lookHeaderRow || position == avatarHeaderRow || position == settingsHeaderRow || position == bottomTabsHeaderRow
                    || position == foldersHeaderRow || position == chatsHeaderRow || position == titleHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == switchStyleRow || position == tabsTitleTypeRow || position == titleModeRow || position == customTitleRow || position == snowRow) {
                return TYPE_VALUE;
            }
            if (position == avatarSliderRow) {
                return TYPE_SLIDER;
            }
            if (position == lookInfoRow || position == avatarInfoRow || position == settingsInfoRow || position == bottomTabsInfoRow
                    || position == foldersInfoRow || position == chatsInfoRow || position == titleInfoRow || position == creditsRow) {
                return TYPE_INFO;
            }
            return TYPE_CHECK;
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
            } else if (viewType == TYPE_SLIDER) {
                view = new SliderCell(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    String text;
                    if (position == lookHeaderRow) text = "Оформление";
                    else if (position == avatarHeaderRow) text = "Скругление аватарок";
                    else if (position == settingsHeaderRow) text = "Экран настроек";
                    else if (position == bottomTabsHeaderRow) text = "Нижние вкладки";
                    else if (position == foldersHeaderRow) text = "Папки";
                    else if (position == chatsHeaderRow) text = "Список чатов";
                    else text = "Главный экран";
                    ((HeaderCell) holder.itemView).setText(text);
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == hideDividersRow) {
                        cell.setTextAndCheck("Скрыть разделители", RawUiConfig.hideDividers(), true);
                    } else if (position == systemFontRow) {
                        cell.setTextAndCheck("Системный шрифт", RawUiConfig.systemFont(), true);
                    } else if (position == hidePremiumRow) {
                        cell.setTextAndCheck("Скрыть раздел Premium", RawUiConfig.hidePremiumSection(), true);
                    } else if (position == hideHelpRow) {
                        cell.setTextAndCheck("Скрыть раздел «Помощь»", RawUiConfig.hideHelpSection(), false);
                    } else if (position == tabsHideTitlesRow) {
                        cell.setTextAndCheck("Скрыть подписи вкладок", RawUiConfig.mainTabsHideTitles(), true);
                    } else if (position == tabsHideContactsRow) {
                        cell.setTextAndCheck("Скрыть вкладку «Контакты»", RawUiConfig.mainTabsHideContacts(), false);
                    } else if (position == hideAllTabRow) {
                        cell.setTextAndCheck("Скрыть вкладку «Все чаты»", RawUiConfig.hideAllTab(), true);
                    } else if (position == hideSearchRow) {
                        cell.setTextAndCheck("Скрыть поле поиска", RawUiConfig.hideDialogsSearchField(), true);
                    } else if (position == hideFabRow) {
                        cell.setTextAndCheck("Скрыть плавающую кнопку", RawUiConfig.disableDialogsFab(), true);
                    } else if (position == showSecondsRow) {
                        cell.setTextAndCheck("Секунды во времени сообщений", RawUiConfig.showSeconds(), false);
                    } else if (position == folderTitleRow) {
                        cell.setTextAndCheck("Название папки вместо заголовка", RawUiConfig.folderNameAsTitle(), true);
                    }
                    break;
                }
                case TYPE_VALUE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == switchStyleRow) {
                        int s = RawUiConfig.switchStyle();
                        cell.setTextAndValue("Стиль переключателей", s == RawUiConfig.SWITCH_MODERN ? "Современный" : s == RawUiConfig.SWITCH_MD3 ? "Material 3" : "Стандартный", false);
                    } else if (position == tabsTitleTypeRow) {
                        int t = RawUiConfig.tabsTitleType();
                        cell.setTextAndValue("Вкладки папок", t == RawUiConfig.TABS_TITLE_ICON ? "Иконки" : t == RawUiConfig.TABS_TITLE_MIX ? "Иконки и текст" : "Текст", false);
                    } else if (position == titleModeRow) {
                        int m = RawUiConfig.titleMode();
                        cell.setTextAndValue("Заголовок", m == RawUiConfig.TITLE_CUSTOM ? "Свой текст" : m == RawUiConfig.TITLE_ACCOUNT ? "Имя аккаунта" : "Telegram", true);
                    } else if (position == customTitleRow) {
                        String t = RawUiConfig.customTitle().trim();
                        cell.setTextAndValue("Текст заголовка", t.isEmpty() ? "не задан" : t, true);
                    } else if (position == snowRow) {
                        int s = RawUiConfig.snowMode();
                        cell.setTextAndValue("Снег", s == RawUiConfig.SNOW_ALWAYS ? "Всегда" : s == RawUiConfig.SNOW_OFF ? "Выключен" : "По дате", false);
                    }
                    break;
                }
                case TYPE_SLIDER: {
                    ((SliderCell) holder.itemView).bind(0, 100, 5, RawUiConfig.avatarCorners(), "%", value -> {
                        RawUiConfig.setAvatarCorners(value);
                        needRebuild = true;
                    });
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == lookInfoRow) {
                        cell.setText("Скрыть разделители — убирает линии между пунктами списков и тени под разделами. "
                                + "Системный шрифт — шрифты прошивки вместо встроенного Roboto; применяется после перезапуска приложения.");
                    } else if (position == avatarInfoRow) {
                        cell.setText("100% — круглые аватарки, как в Telegram; меньше — скруглённые квадраты. "
                                + "Действует в списке чатов, в сообщениях групп и в шапке чата.");
                    } else if (position == settingsInfoRow) {
                        cell.setText("Убирает из Настроек блок Premium (Звёзды, TON, Business, подарки) и/или блок «Помощь» "
                                + "(вопрос, FAQ, возможности, политика). Пункт «Настройки rawGram» остаётся первым.");
                    } else if (position == bottomTabsInfoRow) {
                        cell.setText("Без подписей остаются только значки. Скрытие вкладки «Контакты» применяется после перезапуска приложения.");
                    } else if (position == foldersInfoRow) {
                        cell.setText("Без вкладки «Все чаты» список открывается на первой папке. "
                                + "Иконки — значки по эмодзи папки (как в Nagram); эмодзи папок загружаются с сервера.");
                    } else if (position == chatsInfoRow) {
                        cell.setText("Без поля поиска поиск открывается кнопкой-лупой в шапке. "
                                + "Без плавающей кнопки нет кнопок «Новое сообщение» и «История». "
                                + "Секунды — в сообщениях и в списке чатов; уже открытые чаты обновятся при повторном открытии.");
                    } else if (position == titleInfoRow) {
                        cell.setText("Заголовок вместо логотипа Telegram: свой текст или имя аккаунта. "
                                + "Название папки — показывает открытую папку, на «Всех чатах» — обычный заголовок. "
                                + "Снег идёт в шапке главного экрана.");
                    } else if (position == creditsRow) {
                        cell.setText("Часть настроек перенесена из Nagram, NekoX и Nekogram (GPLv3); "
                                + "идея скругления аватарок — из exteraGram.");
                    }
                    break;
                }
            }
        }
    }

    /** Seek bar with the current value drawn on the right. */
    private static class SliderCell extends FrameLayout {
        private final SeekBarView seekBar;
        private final TextView valueView;
        private int min, max, step;
        private String suffix;
        private Utilities.Callback<Integer> onChange;

        SliderCell(Context context) {
            super(context);
            seekBar = new SeekBarView(context);
            seekBar.setReportChanges(true);
            seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
                @Override
                public void onSeekBarDrag(boolean stop, float progress) {
                    int value = valueFor(progress);
                    valueView.setText(value + suffix);
                    if (onChange != null) {
                        onChange.run(value);
                    }
                }

                @Override
                public int getStepsCount() {
                    return (max - min) / step;
                }
            });
            addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.CENTER_VERTICAL, 5, 5, 72, 5));

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            valueView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            addView(valueView, LayoutHelper.createFrame(72, LayoutHelper.MATCH_PARENT, Gravity.RIGHT, 0, 0, 16, 0));
        }

        private int valueFor(float progress) {
            int steps = (max - min) / step;
            return min + Math.round(progress * steps) * step;
        }

        void bind(int min, int max, int step, int value, String suffix, Utilities.Callback<Integer> onChange) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.suffix = suffix;
            this.onChange = onChange;
            seekBar.setSeparatorsCount((max - min) / step + 1);
            seekBar.setProgress((value - min) / (float) (max - min));
            valueView.setText(value + suffix);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY));
        }
    }
}
