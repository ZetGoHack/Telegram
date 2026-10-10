package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.settings.RawPreferencesFragment;
import org.telegram.rawgram.settings.RawPreviewCard;
import org.telegram.rawgram.settings.RawSeekBarCell;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;

/**
 * "Интерфейс": rawGram look-and-feel options. Every option off / default = stock Telegram.
 * Laid out like exteraGram appearance settings (RawPreferencesFragment, preview cards, titled sliders): a copy
 * of the main screen on top, and sections with their own live preview right above the related options
 * (avatars, folder tabs, icon pack tiles, switch style tiles).
 */
public class RawUiSettingsActivity extends RawPreferencesFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_INFO = 3;
    private static final int TYPE_SLIDER = 4;
    private static final int TYPE_PREVIEW = 5;
    private static final int TYPE_AVATARS = 6;
    private static final int TYPE_FOLDERS = 7;
    private static final int TYPE_ICON_TILES = 8;
    private static final int TYPE_SWITCH_TILES = 9;

    // icons shown on the icon pack tiles: sticker, link, pin, image (Solar ids come from RawIcons' map)
    private static final int[] SAMPLE_ICONS = {R.drawable.msg_sticker, R.drawable.msg_link, R.drawable.msg_pin, R.drawable.msg_gallery};

    private int rowCount;

    private int previewRow;
    private int previewInfoRow;

    private int avatarPreviewRow;
    private int avatarSliderRow;
    private int avatarInfoRow;

    private int foldersHeaderRow;
    private int foldersPreviewRow;
    private int tabsTitleTypeRow;
    private int hideAllTabRow;
    private int tabStrokeRow;
    private int foldersBottomRow;
    private int noTabCountersRow;
    private int archivePullRow;
    private int noUnarchiveRow;
    private int downloadsRow;
    private int tabsOrderRow;
    private int tabsSearchRow;
    private int sideMenuRow;
    private int hideArchiveRow;
    private int previewAvatarsRow;
    private int foldersInfoRow;

    private int titleHeaderRow;
    private int titleModeRow;
    private int customTitleRow;
    private int folderTitleRow;
    private int snowRow;
    private int titleInfoRow;

    private int chatsHeaderRow;
    private int hideSearchRow;
    private int hideFabRow;
    private int hidePremiumHintsRow;
    private int hideBirthdaysRow;
    private int hideStoriesHeaderRow;
    private int disableStoriesRow;
    private int chatsInfoRow;

    private int iconHeaderRow;
    private int iconTilesRow;
    private int iconInfoRow;

    private int switchHeaderRow;
    private int switchTilesRow;
    private int switchInfoRow;

    private int lookHeaderRow;
    private int hideDividersRow;
    private int systemFontRow;
    private int fontFileRow;
    private int fontBoldRow;
    private int motionRow;
    private int badgesRow;
    private int lookInfoRow;

    private int settingsHeaderRow;
    private int hidePremiumRow;
    private int hideHelpRow;
    private int hidePhoneRow;
    private int settingsInfoRow;

    private int bottomTabsHeaderRow;
    private int tabsHideTitlesRow;
    private int tabsHideContactsRow;
    private int bottomTabsInfoRow;

    private ListAdapter adapter;
    private boolean needRebuild;

    private void updateRows() {
        rowCount = 0;
        previewRow = rowCount++;
        previewInfoRow = rowCount++;

        titleHeaderRow = rowCount++;
        titleModeRow = rowCount++;
        customTitleRow = RawUiConfig.titleMode() == RawUiConfig.TITLE_CUSTOM ? rowCount++ : -1;
        folderTitleRow = rowCount++;
        snowRow = rowCount++;
        titleInfoRow = rowCount++;

        foldersHeaderRow = rowCount++;
        foldersPreviewRow = rowCount++;
        tabsTitleTypeRow = rowCount++;
        hideAllTabRow = rowCount++;
        tabStrokeRow = rowCount++;
        foldersBottomRow = rowCount++;
        noTabCountersRow = rowCount++;
        foldersInfoRow = rowCount++;

        chatsHeaderRow = rowCount++;
        hideSearchRow = rowCount++;
        hideFabRow = rowCount++;
        hidePremiumHintsRow = rowCount++;
        hideBirthdaysRow = rowCount++;
        hideStoriesHeaderRow = rowCount++;
        disableStoriesRow = rowCount++;
        hideArchiveRow = rowCount++;
        archivePullRow = rowCount++;
        noUnarchiveRow = rowCount++;
        downloadsRow = rowCount++;
        previewAvatarsRow = rowCount++;
        chatsInfoRow = rowCount++;

        bottomTabsHeaderRow = rowCount++;
        tabsHideTitlesRow = rowCount++;
        tabsHideContactsRow = rowCount++;
        tabsOrderRow = rowCount++;
        tabsSearchRow = rowCount++;
        sideMenuRow = rowCount++;
        bottomTabsInfoRow = rowCount++;

        // exteraGram's avatar corners card: the titled slider first, the live preview under it
        avatarSliderRow = rowCount++;
        avatarPreviewRow = rowCount++;
        avatarInfoRow = rowCount++;

        iconHeaderRow = rowCount++;
        iconTilesRow = rowCount++;
        iconInfoRow = rowCount++;

        switchHeaderRow = rowCount++;
        switchTilesRow = rowCount++;
        switchInfoRow = rowCount++;

        lookHeaderRow = rowCount++;
        systemFontRow = rowCount++;
        fontFileRow = RawUiConfig.fontMode() == RawUiConfig.FONT_CUSTOM ? rowCount++ : -1;
        fontBoldRow = RawUiConfig.fontMode() == RawUiConfig.FONT_CUSTOM ? rowCount++ : -1;
        hideDividersRow = rowCount++;
        motionRow = rowCount++;
        badgesRow = rowCount++;
        lookInfoRow = rowCount++;

        settingsHeaderRow = rowCount++;
        hidePremiumRow = rowCount++;
        hideHelpRow = rowCount++;
        hidePhoneRow = rowCount++;
        settingsInfoRow = rowCount++;
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
    protected String getTitle() {
        return "Внешний вид";
    }

    @Override
    protected RecyclerListView createListView(Context context) {
        RecyclerListView listView = new RecyclerListView(context);
        listView.setItemAnimator(null);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener(this::onRowClick);
        return listView;
    }

    private void onRowClick(View view, int position) {
        if (position == hideDividersRow) {
            boolean v = !RawUiConfig.hideDividers();
            RawUiConfig.setHideDividers(v);
            Theme.applyCommonTheme();
            toggled(view, v);
        } else if (position == systemFontRow) {
            choose("Шрифт", new CharSequence[]{"Telegram (Roboto)", "Системный", "Свой файл"}, which -> {
                RawUiConfig.setFontMode(which);
                updateRows();
                if (adapter != null) adapter.notifyDataSetChanged();
                if (which == RawUiConfig.FONT_CUSTOM && !RawCustomFont.regularFile().exists()) {
                    RawCustomFont.pick(this, RawCustomFont.REQUEST_REGULAR);
                } else {
                    showRestartNotice(this);
                }
            });
        } else if (position == fontFileRow) {
            RawCustomFont.pick(this, RawCustomFont.REQUEST_REGULAR);
        } else if (position == fontBoldRow) {
            if (RawCustomFont.boldFile().exists()) {
                choose("Жирное начертание", new CharSequence[]{"Выбрать другой файл", "Убрать"}, which -> {
                    if (which == 0) {
                        RawCustomFont.pick(this, RawCustomFont.REQUEST_BOLD);
                    } else {
                        RawCustomFont.removeBold();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        showRestartNotice(this);
                    }
                });
            } else {
                RawCustomFont.pick(this, RawCustomFont.REQUEST_BOLD);
            }
        } else if (position == badgesRow) {
            boolean v = !RawgramConfig.isExteraBadges();
            RawgramConfig.setExteraBadges(v);
            ((TextCheckCell) view).setChecked(v);
        } else if (position == motionRow) {
            // affects only rawGram's own sheets and menus: no rebuild of other screens needed
            boolean v = !RawMotion.isEnabled();
            RawMotion.setEnabled(v);
            ((TextCheckCell) view).setChecked(v);
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
        } else if (position == foldersBottomRow) {
            boolean v = !RawUiConfig.foldersAtBottom();
            RawUiConfig.setFoldersAtBottom(v);
            toggled(view, v);
            showRestartNotice(this);
        } else if (position == tabStrokeRow) {
            RawChatUiConfig.tabStroke.toggle();
            toggled(view, RawChatUiConfig.tabStroke.get());
        } else if (position == noTabCountersRow) {
            RawChatUiConfig.noTabCounters.toggle();
            toggled(view, RawChatUiConfig.noTabCounters.get());
        } else if (position == archivePullRow) {
            RawChatUiConfig.archiveOnPull.toggle();
            toggled(view, RawChatUiConfig.archiveOnPull.get());
        } else if (position == noUnarchiveRow) {
            RawChatUiConfig.noUnarchiveSwipe.toggle();
            toggled(view, RawChatUiConfig.noUnarchiveSwipe.get());
        } else if (position == downloadsRow) {
            RawChatUiConfig.alwaysDownloads.toggle();
            toggled(view, RawChatUiConfig.alwaysDownloads.get());
        } else if (position == sideMenuRow) {
            boolean v = !RawUiConfig.sideMenu();
            RawUiConfig.setSideMenu(v);
            toggled(view, v);
            showRestartNotice(this);
        } else if (position == hideArchiveRow) {
            boolean v = !RawUiConfig.hideArchive();
            RawUiConfig.setHideArchive(v);
            toggled(view, v);
            if (!v) showRestartNotice(this);
        } else if (position == tabsSearchRow) {
            boolean v = !RawUiConfig.mainTabsSearch();
            RawUiConfig.setMainTabsSearch(v);
            toggled(view, v);
            showRestartNotice(this);
        } else if (position == previewAvatarsRow) {
            RawChatUiConfig.previewAvatars.toggle();
            toggled(view, RawChatUiConfig.previewAvatars.get());
        } else if (position == tabsOrderRow) {
            RawMainTabs.showEditor(getParentActivity(), () -> {
                if (adapter != null) adapter.notifyItemChanged(tabsOrderRow);
                showRestartNotice(this);
            });
        } else if (position == hidePremiumHintsRow) {
            RawChatUiConfig.hidePremiumHints.toggle();
            toggled(view, RawChatUiConfig.hidePremiumHints.get());
        } else if (position == hideBirthdaysRow) {
            RawChatUiConfig.hideBirthdays.toggle();
            toggled(view, RawChatUiConfig.hideBirthdays.get());
        } else if (position == hideStoriesHeaderRow) {
            RawChatUiConfig.hideStoriesHeader.toggle();
            toggled(view, RawChatUiConfig.hideStoriesHeader.get());
            showRestartNotice(this);
        } else if (position == disableStoriesRow) {
            RawChatUiConfig.disableStories.toggle();
            toggled(view, RawChatUiConfig.disableStories.get());
            showRestartNotice(this);
        } else if (position == folderTitleRow) {
            boolean v = !RawUiConfig.folderNameAsTitle();
            RawUiConfig.setFolderNameAsTitle(v);
            toggled(view, v);
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

    /** Re-binds every live preview on screen (top copy of the main screen and the section previews). */
    private void updatePreview() {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof RawPreviewCard) {
                child = ((RawPreviewCard) child).getContent();
            } else if (child instanceof FolderTabsHolder) {
                child = ((FolderTabsHolder) child).tabs;
            }
            if (child instanceof RawMainScreenPreview) {
                ((RawMainScreenPreview) child).bind();
            } else if (child instanceof RawFolderTabsPreview) {
                ((RawFolderTabsPreview) child).bind();
            } else if (child instanceof RawAvatarsPreviewCell) {
                ((RawAvatarsPreviewCell) child).bind();
            }
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

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        RawCustomFont.onActivityResult(requestCode, resultCode, data, () -> {
            if (adapter != null) adapter.notifyDataSetChanged();
            showRestartNotice(this);
        });
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

    // ---- tiles ----

    private static final String[] SWITCH_NAMES = {"Стандартный", "Современный", "Material 3"};

    private RawTilesCell createIconTiles(Context context) {
        RawTilesCell cell = new RawTilesCell(context);
        String[] names = {"Telegram", "Solar"};
        for (int pack = 0; pack < names.length; pack++) {
            LinearLayout icons = new LinearLayout(context);
            icons.setOrientation(LinearLayout.HORIZONTAL);
            icons.setGravity(Gravity.CENTER);
            for (int id : SAMPLE_ICONS) {
                int res = pack == RawIcons.PACK_SOLAR ? RawIcons.solarId(id) : id;
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                // application resources are never pack-substituted, so the stock and Solar ids load as they are
                Drawable drawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, res);
                if (drawable != null) {
                    image.setImageDrawable(drawable.mutate());
                }
                icons.addView(image, LayoutHelper.createLinear(22, 22, Gravity.CENTER, 3, 0, 3, 0));
            }
            cell.addTile(icons, 64, names[pack], (index, tile) -> {
                if (index != RawUiConfig.getIconPack()) {
                    RawUiConfig.setIconPack(index);
                    cell.setSelected(index, true);
                    bindIconTiles(cell);
                    showRestartNotice(this);
                }
            });
        }
        return cell;
    }

    private void bindIconTiles(RawTilesCell cell) {
        cell.setSelected(RawUiConfig.getIconPack(), cell.getSelected() >= 0);
        for (int i = 0; i < cell.getTilesCount(); i++) {
            RawTilesCell.Tile tile = cell.getTile(i);
            int color = Theme.getColor(tile.isChecked() ? Theme.key_windowBackgroundWhiteBlueHeader : Theme.key_windowBackgroundWhiteGrayIcon);
            tintIcons(tile.getCard(), color);
        }
    }

    private static void tintIcons(View view, int color) {
        if (view instanceof ImageView) {
            ((ImageView) view).setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintIcons(group.getChildAt(i), color);
            }
        }
    }

    private RawTilesCell createSwitchTiles(Context context) {
        RawTilesCell cell = new RawTilesCell(context);
        for (int style = 0; style < SWITCH_NAMES.length; style++) {
            FrameLayout content = new FrameLayout(context);
            PreviewSwitch sw = new PreviewSwitch(context, style);
            sw.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
            sw.setChecked(true, false);
            sw.setScaleX(1.25f);
            sw.setScaleY(1.25f);
            sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            content.addView(sw, LayoutHelper.createFrame(37, 20, Gravity.CENTER));
            cell.addTile(content, 64, SWITCH_NAMES[style], (index, tile) -> {
                // every tap flips the sample, so the style can be seen in both states
                sw.setChecked(!sw.isChecked(), true);
                if (index != RawUiConfig.switchStyle()) {
                    RawUiConfig.setSwitchStyle(index);
                    needRebuild = true;
                    cell.setSelected(index, true);
                    invalidateTree(listView);
                }
            });
        }
        return cell;
    }

    /** A real {@link Switch} that always draws in one given style, whatever style is configured. */
    private static class PreviewSwitch extends Switch {
        private final int style;

        PreviewSwitch(Context context, int style) {
            super(context);
            this.style = style;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int prev = RawSwitchStyle.previewStyle;
            RawSwitchStyle.previewStyle = style;
            try {
                super.onDraw(canvas);
            } finally {
                RawSwitchStyle.previewStyle = prev;
            }
        }
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
            if (position == avatarPreviewRow) {
                return TYPE_AVATARS;
            }
            if (position == foldersPreviewRow) {
                return TYPE_FOLDERS;
            }
            if (position == iconTilesRow) {
                return TYPE_ICON_TILES;
            }
            if (position == switchTilesRow) {
                return TYPE_SWITCH_TILES;
            }
            if (position == lookHeaderRow || position == settingsHeaderRow || position == bottomTabsHeaderRow
                    || position == foldersHeaderRow || position == chatsHeaderRow || position == titleHeaderRow
                    || position == iconHeaderRow || position == switchHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == systemFontRow || position == fontFileRow || position == fontBoldRow || position == tabsOrderRow || position == tabsTitleTypeRow || position == titleModeRow || position == customTitleRow || position == snowRow) {
                return TYPE_VALUE;
            }
            if (position == avatarSliderRow) {
                return TYPE_SLIDER;
            }
            if (position == previewInfoRow || position == lookInfoRow || position == avatarInfoRow || position == settingsInfoRow || position == bottomTabsInfoRow
                    || position == foldersInfoRow || position == chatsInfoRow || position == titleInfoRow || position == iconInfoRow
                    || position == switchInfoRow) {
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
                RawUi.wrapTitle((TextCheckCell) view);
            } else if (viewType == TYPE_VALUE) {
                view = new TextSettingsCell(context);
            } else if (viewType == TYPE_SLIDER) {
                view = new RawSeekBarCell(context);
            } else if (viewType == TYPE_PREVIEW) {
                view = new RawPreviewCard(context, new RawMainScreenPreview(context, currentAccount));
            } else if (viewType == TYPE_AVATARS) {
                RawAvatarsPreviewCell avatars = new RawAvatarsPreviewCell(context, currentAccount);
                avatars.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
                // the slider sits right above the card, as in exteraGram's avatar corners cell
                view = new RawPreviewCard(context, avatars, 0, RawPreviewCard.INSET_DP);
            } else if (viewType == TYPE_FOLDERS) {
                view = new FolderTabsHolder(context, new RawFolderTabsPreview(context, currentAccount));
            } else if (viewType == TYPE_ICON_TILES) {
                view = createIconTiles(context);
            } else if (viewType == TYPE_SWITCH_TILES) {
                view = createSwitchTiles(context);
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
                    ((RawMainScreenPreview) ((RawPreviewCard) holder.itemView).getContent()).bind();
                    break;
                case TYPE_AVATARS:
                    ((RawAvatarsPreviewCell) ((RawPreviewCard) holder.itemView).getContent()).bind();
                    break;
                case TYPE_FOLDERS:
                    ((FolderTabsHolder) holder.itemView).tabs.bind();
                    break;
                case TYPE_ICON_TILES:
                    bindIconTiles((RawTilesCell) holder.itemView);
                    break;
                case TYPE_SWITCH_TILES: {
                    RawTilesCell cell = (RawTilesCell) holder.itemView;
                    cell.setSelected(RawUiConfig.switchStyle(), cell.getSelected() >= 0);
                    break;
                }
                case TYPE_HEADER: {
                    String text;
                    if (position == lookHeaderRow) text = "Оформление";
                    else if (position == settingsHeaderRow) text = "Экран настроек";
                    else if (position == bottomTabsHeaderRow) text = "Нижние вкладки";
                    else if (position == foldersHeaderRow) text = "Папки";
                    else if (position == chatsHeaderRow) text = "Список чатов";
                    else if (position == iconHeaderRow) text = "Набор иконок";
                    else if (position == switchHeaderRow) text = "Переключатели";
                    else text = "Главный экран";
                    ((HeaderCell) holder.itemView).setText(text);
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == hideDividersRow) {
                        cell.setTextAndCheck("Скрыть разделители", RawUiConfig.hideDividers(), true);
                    } else if (position == motionRow) {
                        cell.setTextAndCheck("Анимации rawGram", RawMotion.isEnabled(), true);
                    } else if (position == badgesRow) {
                        cell.setTextAndCheck("Бейджи exteraGram", RawgramConfig.isExteraBadges(), false);
                    } else if (position == hidePremiumRow) {
                        cell.setTextAndCheck("Скрыть раздел Premium", RawUiConfig.hidePremiumSection(), true);
                    } else if (position == hideHelpRow) {
                        cell.setTextAndCheck("Скрыть раздел «Помощь»", RawUiConfig.hideHelpSection(), true);
                    } else if (position == hidePhoneRow) {
                        cell.setTextAndCheck("Скрыть номер телефона", RawUiConfig.isHidePhone(), false);
                    } else if (position == tabsHideTitlesRow) {
                        cell.setTextAndCheck("Скрыть подписи вкладок", RawUiConfig.mainTabsHideTitles(), true);
                    } else if (position == tabsHideContactsRow) {
                        cell.setTextAndCheck("Скрыть вкладку «Контакты»", RawUiConfig.mainTabsHideContacts(), true);
                    } else if (position == hideAllTabRow) {
                        cell.setTextAndCheck("Скрыть вкладку «Все чаты»", RawUiConfig.hideAllTab(), true);
                    } else if (position == hideSearchRow) {
                        cell.setTextAndCheck("Скрыть поле поиска", RawUiConfig.hideDialogsSearchField(), true);
                    } else if (position == hideFabRow) {
                        cell.setTextAndCheck("Скрыть плавающие кнопки", RawUiConfig.disableDialogsFab(), true);
                    } else if (position == hidePremiumHintsRow) {
                        cell.setTextAndCheck("Скрыть рекламу Premium", RawChatUiConfig.hidePremiumHints.get(), true);
                    } else if (position == hideBirthdaysRow) {
                        cell.setTextAndCheck("Скрыть дни рождения", RawChatUiConfig.hideBirthdays.get(), true);
                    } else if (position == hideStoriesHeaderRow) {
                        cell.setTextAndCheck("Скрыть истории над чатами", RawChatUiConfig.hideStoriesHeader.get(), true);
                    } else if (position == disableStoriesRow) {
                        cell.setTextAndCheck("Отключить истории", RawChatUiConfig.disableStories.get(), true);
                    } else if (position == archivePullRow) {
                        cell.setTextAndCheck("Открывать архив потягиванием", RawChatUiConfig.archiveOnPull.get(), true);
                    } else if (position == noUnarchiveRow) {
                        cell.setTextAndCheck("Не разархивировать свайпом", RawChatUiConfig.noUnarchiveSwipe.get(), true);
                    } else if (position == downloadsRow) {
                        cell.setTextAndCheck("Всегда показывать загрузки", RawChatUiConfig.alwaysDownloads.get(), true);
                    } else if (position == previewAvatarsRow) {
                        cell.setTextAndCheck("Аватарки отправителей", RawChatUiConfig.previewAvatars.get(), false);
                    } else if (position == tabsSearchRow) {
                        cell.setTextAndCheck("Кнопка поиска", RawUiConfig.mainTabsSearch(), true);
                    } else if (position == sideMenuRow) {
                        cell.setTextAndCheck("Боковое меню", RawUiConfig.sideMenu(), false);
                    } else if (position == hideArchiveRow) {
                        cell.setTextAndCheck("Скрыть архив", RawUiConfig.hideArchive(), true);
                    } else if (position == foldersBottomRow) {
                        cell.setTextAndCheck("Папки внизу", RawUiConfig.foldersAtBottom(), true);
                    } else if (position == tabStrokeRow) {
                        cell.setTextAndCheck("Обводка выбранной папки", RawChatUiConfig.tabStroke.get(), true);
                    } else if (position == noTabCountersRow) {
                        cell.setTextAndCheck("Без счётчиков на папках", RawChatUiConfig.noTabCounters.get(), false);
                    } else if (position == folderTitleRow) {
                        cell.setTextAndCheck("Название папки вместо заголовка", RawUiConfig.folderNameAsTitle(), true);
                    }
                    break;
                }
                case TYPE_VALUE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == tabsTitleTypeRow) {
                        int t = RawUiConfig.tabsTitleType();
                        cell.setTextAndValue("Вкладки папок", t == RawUiConfig.TABS_TITLE_ICON ? "Иконки" : t == RawUiConfig.TABS_TITLE_MIX ? "Иконки и текст" : "Текст", true);
                    } else if (position == titleModeRow) {
                        int m = RawUiConfig.titleMode();
                        cell.setTextAndValue("Заголовок", m == RawUiConfig.TITLE_CUSTOM ? "Свой текст" : m == RawUiConfig.TITLE_ACCOUNT ? "Имя аккаунта" : "Telegram", true);
                    } else if (position == customTitleRow) {
                        String t = RawUiConfig.customTitle().trim();
                        cell.setTextAndValue("Текст заголовка", t.isEmpty() ? "не задан" : t, true);
                    } else if (position == systemFontRow) {
                        int f = RawUiConfig.fontMode();
                        cell.setTextAndValue("Шрифт", f == RawUiConfig.FONT_CUSTOM ? "Свой файл" : f == RawUiConfig.FONT_SYSTEM ? "Системный" : "Telegram", true);
                    } else if (position == fontFileRow) {
                        String regularName = RawCustomFont.fontName(RawCustomFont.regularFile());
                        cell.setTextAndValue("Файл шрифта", !RawCustomFont.regularFile().exists() ? "не выбран" : regularName != null ? regularName : "выбран", true);
                    } else if (position == fontBoldRow) {
                        String boldName = RawCustomFont.fontName(RawCustomFont.boldFile());
                        cell.setTextAndValue("Жирное начертание", !RawCustomFont.boldFile().exists() ? "из основного" : boldName != null ? boldName : "отдельный файл", true);
                    } else if (position == tabsOrderRow) {
                        cell.setTextAndValue("Порядок вкладок", RawMainTabs.summary(), true);
                    } else if (position == snowRow) {
                        int s = RawUiConfig.snowMode();
                        cell.setTextAndValue("Снег", s == RawUiConfig.SNOW_ALWAYS ? "Всегда" : s == RawUiConfig.SNOW_OFF ? "Выключен" : "По дате", false);
                    }
                    break;
                }
                case TYPE_SLIDER: {
                    ((RawSeekBarCell) holder.itemView).bind("Аватарки", 0, 100, 5, RawUiConfig.avatarCorners(), "%", value -> {
                        RawUiConfig.setAvatarCorners(value);
                        needRebuild = true;
                        updatePreview();
                    });
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == previewInfoRow) {
                        cell.setText("Пример обновляется сразу.");
                    } else if (position == titleInfoRow) {
                        cell.setText("Название папки показывается вместо заголовка, пока открыта папка.");
                    } else if (position == foldersInfoRow) {
                        cell.setText("Без «Все чаты» список открывается на первой папке. Иконки подбираются по эмодзи папки. "
                                + "Обводка — выбранная папка выделена рамкой вместо заливки. Папки внизу — над нижними вкладками, применяется после перезапуска.");
                    } else if (position == chatsInfoRow) {
                        cell.setText("Без поля поиска поиск открывается лупой в шапке. Плавающие кнопки — «Новое сообщение» и «История». "
                                + "Реклама Premium и дни рождения контактов — плашки над списком чатов. "
                                + "Без историй пропадают их кольца на аватарках, строка над чатами и вкладка в профиле. Истории — после перезапуска. "
                                + "Скрытый архив открывается из бокового меню. Архив открывается, если потянуть список вниз до конца. Свайп в архиве не возвращает чат в общий список. "
                                + "Значок загрузок виден в шапке, даже когда ничего не скачивается. "
                                + "Аватарки отправителей — маленькое фото автора последнего сообщения в группах, перед его именем.");
                    } else if (position == bottomTabsInfoRow) {
                        cell.setText("Кнопка поиска — круглая кнопка справа от вкладок, открывает поиск по чатам. "
                                + "Боковое меню — аккаунты, ночной режим и быстрые пункты; открывается кнопкой ☰ в списке чатов или свайпом от левого края. "
                                + "Скрытие «Контактов», порядок вкладок, кнопка поиска и боковое меню применяются после перезапуска.");
                    } else if (position == avatarInfoRow) {
                        cell.setText("100% — круглые, как в Telegram. Действует в списке чатов, группах и шапке чата.");
                    } else if (position == iconInfoRow) {
                        cell.setText("Solar — альтернативные иконки меню, настроек и поля ввода. Нужен перезапуск.");
                    } else if (position == switchInfoRow) {
                        cell.setText("Нажми на образец, чтобы увидеть оба положения.");
                    } else if (position == lookInfoRow) {
                        String fontStatus = RawUiConfig.fontMode() == RawUiConfig.FONT_CUSTOM && !RawCustomFont.status().isEmpty()
                                ? " Обычный текст (проверка): " + RawCustomFont.status() + "." : "";
                        cell.setText("Шрифт применяется после перезапуска. Системный — шрифт телефона с его начертаниями. "
                                + "Свой файл — .ttf или .otf; жирное начертание берётся из отдельного файла, из самого шрифта, если он переменный, или рисуется полужирным." + fontStatus + " Разделители — линии между пунктами и тени под разделами. "
                                + "Анимации — плавные переходы в окнах и меню rawGram; в режиме энергосбережения они выключены. "
                                + "Бейджи exteraGram — значки поддержавших exteraGram и его разработчиков рядом с именами в чатах, списках и профилях; "
                                + "список загружается с сервера exteraGram раз в несколько часов.");
                    } else if (position == settingsInfoRow) {
                        cell.setText("Premium — Звёзды, TON, Business, подарки. Вместо номера будет «Номер скрыт» — удобно для скриншотов.");
                    }
                    break;
                }
            }
        }
    }

    /**
     * The folder tabs preview as exteraGram's FilterTabsPreviewCell shows it: the tab strip centred in a
     * 74dp row, 12dp from the section edges, with a divider under it before the folder options.
     */
    private static class FolderTabsHolder extends FrameLayout {
        private static final int HEIGHT_DP = 74;

        final RawFolderTabsPreview tabs;

        FolderTabsHolder(Context context, RawFolderTabsPreview tabs) {
            super(context);
            this.tabs = tabs;
            setWillNotDraw(false);
            addView(tabs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, RawFolderTabsPreview.HEIGHT_DP, Gravity.CENTER, 12, 0, 12, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(HEIGHT_DP), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }
}
