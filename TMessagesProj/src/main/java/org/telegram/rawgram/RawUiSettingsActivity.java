package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
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
    private static final int TYPE_PREVIEW = 5;

    private int rowCount;

    private int previewRow;
    private int previewInfoRow;

    private int lookHeaderRow;
    private int hideDividersRow;
    private int systemFontRow;
    private int switchStyleRow;
    private int iconPackRow;
    private int lookInfoRow;

    private int avatarHeaderRow;
    private int avatarSliderRow;
    private int avatarInfoRow;

    private int settingsHeaderRow;
    private int hidePremiumRow;
    private int hideHelpRow;
    private int hidePhoneRow;
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
    private UiPreviewCell previewCell;

    private void updateRows() {
        rowCount = 0;
        previewRow = rowCount++;
        previewInfoRow = rowCount++;

        lookHeaderRow = rowCount++;
        hideDividersRow = rowCount++;
        systemFontRow = rowCount++;
        switchStyleRow = rowCount++;
        iconPackRow = rowCount++;
        lookInfoRow = rowCount++;

        avatarHeaderRow = rowCount++;
        avatarSliderRow = rowCount++;
        avatarInfoRow = rowCount++;

        settingsHeaderRow = rowCount++;
        hidePremiumRow = rowCount++;
        hideHelpRow = rowCount++;
        hidePhoneRow = rowCount++;
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
            showRestartNotice(this);
        } else if (position == hidePremiumRow) {
            boolean v = !RawUiConfig.hidePremiumSection();
            RawUiConfig.setHidePremiumSection(v);
            toggled(view, v);
        } else if (position == hideHelpRow) {
            boolean v = !RawUiConfig.hideHelpSection();
            RawUiConfig.setHideHelpSection(v);
            toggled(view, v);
        } else if (position == hidePhoneRow) {
            boolean v = !RawUiConfig.isHidePhone();
            RawUiConfig.setHidePhone(v);
            toggled(view, v);
        } else if (position == tabsHideTitlesRow) {
            boolean v = !RawUiConfig.mainTabsHideTitles();
            RawUiConfig.setMainTabsHideTitles(v);
            toggled(view, v);
        } else if (position == tabsHideContactsRow) {
            boolean v = !RawUiConfig.mainTabsHideContacts();
            RawUiConfig.setMainTabsHideContacts(v);
            toggled(view, v);
            showRestartNotice(this);
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
                invalidateTree(listView);
            });
        } else if (position == iconPackRow) {
            choose("Иконки", new CharSequence[]{"Telegram", "Solar"}, which -> {
                if (which != RawUiConfig.getIconPack()) {
                    RawUiConfig.setIconPack(which);
                    showRestartNotice(this);
                }
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
        updatePreview();
    }

    private void updatePreview() {
        if (previewCell != null) {
            previewCell.bind();
        }
    }

    private static void invalidateTree(View view) {
        if (view == null) {
            return;
        }
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                invalidateTree(group.getChildAt(i));
            }
        }
    }

    // ---- restart notice ----

    /** Bottom bulletin for options that only apply after a restart, with a «Перезапустить» button. */
    static void showRestartNotice(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "Изменение применится после перезапуска",
                "Перезапустить", Bulletin.DURATION_PROLONG, () -> restartApp(fragment.getParentActivity())).show();
    }

    /** Restarts the app: flushes rawGram settings, relaunches the launcher activity in a fresh task and ends the process. */
    static void restartApp(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            // apply() writes asynchronously; an empty commit() returns only after the queued writes are on disk
            for (String name : new String[]{"rawgram_ui", "rawgram_chat_ui", "rawgram_classic"}) {
                ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit();
            }
        } catch (Throwable ignore) {}
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (launch == null || launch.getComponent() == null) {
            return;
        }
        // same sequence as Telegram's debug-menu restart (SettingsActivity, "tablet mode")
        activity.startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
        activity.finishAffinity();
        Runtime.getRuntime().exit(0);
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
                    updatePreview();
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
                    updatePreview();
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
            if (position == previewRow) {
                return TYPE_PREVIEW;
            }
            if (position == lookHeaderRow || position == avatarHeaderRow || position == settingsHeaderRow || position == bottomTabsHeaderRow
                    || position == foldersHeaderRow || position == chatsHeaderRow || position == titleHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == switchStyleRow || position == iconPackRow || position == tabsTitleTypeRow || position == titleModeRow || position == customTitleRow || position == snowRow) {
                return TYPE_VALUE;
            }
            if (position == avatarSliderRow) {
                return TYPE_SLIDER;
            }
            if (position == previewInfoRow || position == lookInfoRow || position == avatarInfoRow || position == settingsInfoRow || position == bottomTabsInfoRow
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
            } else if (viewType == TYPE_PREVIEW) {
                view = previewCell = new UiPreviewCell(context);
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_PREVIEW:
                    ((UiPreviewCell) holder.itemView).bind();
                    break;
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
                        cell.setTextAndCheck("Скрыть раздел «Помощь»", RawUiConfig.hideHelpSection(), true);
                    } else if (position == hidePhoneRow) {
                        cell.setTextAndCheck("Скрыть номер телефона", RawUiConfig.isHidePhone(), false);
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
                        cell.setTextAndValue("Стиль переключателей", s == RawUiConfig.SWITCH_MODERN ? "Современный" : s == RawUiConfig.SWITCH_MD3 ? "Material 3" : "Стандартный", true);
                    } else if (position == iconPackRow) {
                        cell.setTextAndValue("Иконки", RawUiConfig.getIconPack() == RawIcons.PACK_SOLAR ? "Solar" : "Telegram", false);
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
                        updatePreview();
                    });
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == previewInfoRow) {
                        cell.setText("Пример обновляется сразу: заголовок, вкладки папок, поле поиска, аватарки, разделители, "
                                + "время с секундами, плавающая кнопка и стиль переключателя (его можно нажать). "
                                + "Системный шрифт и скрытие вкладки «Контакты» применяются после перезапуска.");
                    } else if (position == lookInfoRow) {
                        cell.setText("Скрыть разделители — убирает линии между пунктами списков и тени под разделами. "
                                + "Системный шрифт — шрифты прошивки вместо встроенного Roboto; применяется после перезапуска приложения. "
                                + "Иконки Solar (480 Design, CC BY 4.0, через exteraGram/Nagram) — альтернативный набор иконок; применяется после перезапуска.");
                    } else if (position == avatarInfoRow) {
                        cell.setText("100% — круглые аватарки, как в Telegram; меньше — скруглённые квадраты. "
                                + "Действует в списке чатов, в сообщениях групп и в шапке чата.");
                    } else if (position == settingsInfoRow) {
                        cell.setText("Убирает из Настроек блок Premium (Звёзды, TON, Business, подарки) и/или блок «Помощь» "
                                + "(вопрос, FAQ, возможности, политика). Пункт «Настройки rawGram» остаётся первым.\n\n"
                                + "Скрыть номер телефона — вместо своего номера показывается «Номер скрыт» (удобно для скриншотов и стримов).");
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

    // ---- live preview ----

    /**
     * Example of the main screen built from real cells: a header strip (title, folder tabs, search field),
     * three {@link DialogCell}s from fake {@link DialogCell.CustomDialog}s (as ThemePreviewActivity does) with the
     * floating button, and a sample switch. Nothing here touches the network or real chats.
     */
    private class UiPreviewCell extends LinearLayout {
        private final HeaderPreview header;
        private final DialogCell[] cells = new DialogCell[3];
        private final DialogCell.CustomDialog[] dialogs = new DialogCell.CustomDialog[3];
        private final ImageView fab;
        private final TextCheckCell switchCell;
        private boolean sampleChecked = true;

        UiPreviewCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            header = new HeaderPreview(context);
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            // the fake list must not react to touches (DialogCell has swipe / long press logic)
            FrameLayout list = new FrameLayout(context) {
                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    return true;
                }

                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    return true;
                }
            };
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            int now = (int) (System.currentTimeMillis() / 1000);
            dialogs[0] = dialog(0, "Анна", "Скинь, пожалуйста, фото с выходных 🙂", 0, true, false, 0, now - 95, DialogCell.SENT_STATE_NOTHING);
            dialogs[1] = dialog(1, "Рабочий чат", "Созвон перенесли на 15:00", 3, false, false, 0, now - 47 * 60 - 13, DialogCell.SENT_STATE_NOTHING);
            dialogs[2] = dialog(2, "Новости rawGram", "Готово, отправил", 0, false, true, 1, now - 2 * 3600 - 31, DialogCell.SENT_STATE_READ);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = new DialogCell(null, context, false, false);
                column.addView(cells[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            list.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            fab = new ImageView(context);
            fab.setScaleType(ImageView.ScaleType.CENTER);
            fab.setImageResource(R.drawable.floating_pencil);
            fab.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.MULTIPLY));
            fab.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(52), Theme.getColor(Theme.key_chats_actionBackground)));
            list.addView(fab, LayoutHelper.createFrame(52, 52, Gravity.BOTTOM | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 14, 0, 14, 10));
            addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            switchCell = new TextCheckCell(context);
            switchCell.setBackground(Theme.getSelectorDrawable(false));
            switchCell.setOnClickListener(v -> {
                sampleChecked = !sampleChecked;
                switchCell.setChecked(sampleChecked);
            });
            addView(switchCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        private DialogCell.CustomDialog dialog(int id, String name, String message, int unread, boolean pinned, boolean muted, int type, int date, int sent) {
            DialogCell.CustomDialog d = new DialogCell.CustomDialog();
            d.id = id;
            d.name = name;
            d.message = message;
            d.unread_count = unread;
            d.pinned = pinned;
            d.muted = muted;
            d.type = type;
            d.date = date;
            d.sent = sent;
            return d;
        }

        /** Re-applies every option: called on bind and after each change. */
        void bind() {
            header.requestLayout();
            header.invalidate();
            for (int i = 0; i < cells.length; i++) {
                DialogCell cell = cells[i];
                cell.useSeparator = i < cells.length - 1;
                cell.setDialog(dialogs[i]); // re-formats the time (seconds) and re-reads the divider paint
                // custom dialogs get a fixed circle; apply the avatar corners option like real chats do
                cell.avatarImage.setRoundRadius(RawUi.avatarR(AndroidUtilities.dp(26)));
                cell.requestLayout();
                cell.invalidate();
            }
            fab.setVisibility(RawUiConfig.disableDialogsFab() ? View.GONE : View.VISIBLE);
            switchCell.setTextAndCheck("Пример переключателя", sampleChecked, false);
            invalidateTree(switchCell);
        }
    }

    /** Main-screen header: title (logo text / custom / account / folder), folder tabs and the search field. */
    private class HeaderPreview extends View {
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tabPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        HeaderPreview(Context context) {
            super(context);
            titlePaint.setTypeface(AndroidUtilities.bold());
            titlePaint.setTextSize(AndroidUtilities.dp(20));
            tabPaint.setTypeface(AndroidUtilities.bold());
            tabPaint.setTextSize(AndroidUtilities.dp(14));
            hintPaint.setTextSize(AndroidUtilities.dp(15));
        }

        private boolean searchShown() {
            return !RawUiConfig.hideDialogsSearchField();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int h = AndroidUtilities.dp(52 + 40) + (searchShown() ? AndroidUtilities.dp(48) : 0);
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h);
        }

        private String[] tabs() {
            boolean all = !RawUiConfig.hideAllTab();
            String[] names = all ? new String[]{"Все чаты", "Личные", "Работа"} : new String[]{"Личные", "Работа"};
            String[] icons = all ? new String[]{"💬", "👤", "💼"} : new String[]{"👤", "💼"};
            int type = RawUiConfig.tabsTitleType();
            String[] out = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                out[i] = type == RawUiConfig.TABS_TITLE_ICON ? icons[i] : type == RawUiConfig.TABS_TITLE_MIX ? icons[i] + " " + names[i] : names[i];
            }
            return out;
        }

        /** What the main screen title would show with the first folder tab open. */
        private String title() {
            if (RawUiConfig.folderNameAsTitle() && RawUiConfig.hideAllTab()) {
                return "Личные"; // the first folder is open when «Все чаты» is hidden
            }
            int mode = RawUiConfig.titleMode();
            if (mode == RawUiConfig.TITLE_CUSTOM) {
                String t = RawUiConfig.customTitle().trim();
                return t.isEmpty() ? "Telegram" : t;
            } else if (mode == RawUiConfig.TITLE_ACCOUNT) {
                TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
                String name = self != null ? UserObject.getFirstName(self) : null;
                return TextUtils.isEmpty(name) ? "Telegram" : name;
            }
            return "Telegram";
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            canvas.drawColor(Theme.getColor(Theme.key_actionBarDefault));
            float side = AndroidUtilities.dp(18);

            titlePaint.setColor(Theme.getColor(Theme.key_actionBarDefaultTitle));
            CharSequence title = TextUtils.ellipsize(title(), titlePaint, w - side * 2, TextUtils.TruncateAt.END);
            canvas.drawText(title, 0, title.length(), side, AndroidUtilities.dp(34), titlePaint);

            // folder tabs, the first one active
            String[] tabs = tabs();
            float x = AndroidUtilities.dp(16);
            float baseline = AndroidUtilities.dp(52 + 25);
            for (int i = 0; i < tabs.length; i++) {
                tabPaint.setColor(Theme.getColor(i == 0 ? Theme.key_actionBarTabActiveText : Theme.key_actionBarTabUnactiveText));
                float tw = tabPaint.measureText(tabs[i]);
                canvas.drawText(tabs[i], x, baseline, tabPaint);
                if (i == 0) {
                    paint.setColor(Theme.getColor(Theme.key_actionBarTabLine));
                    rect.set(x - AndroidUtilities.dp(2), AndroidUtilities.dp(52 + 37), x + tw + AndroidUtilities.dp(2), AndroidUtilities.dp(52 + 40));
                    canvas.drawRoundRect(rect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), paint);
                }
                x += tw + AndroidUtilities.dp(24);
            }

            if (searchShown()) {
                float top = AndroidUtilities.dp(52 + 40 + 6);
                rect.set(AndroidUtilities.dp(10), top, w - AndroidUtilities.dp(10), top + AndroidUtilities.dp(36));
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
                canvas.drawRoundRect(rect, AndroidUtilities.dp(18), AndroidUtilities.dp(18), paint);
                hintPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
                canvas.drawText("🔍  Поиск", AndroidUtilities.dp(24), top + AndroidUtilities.dp(23), hintPaint);
            }
        }
    }
}
