package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.Components.ItemOptions;

/**
 * The «filter by type» button of the chat search bar, next to the calendar and the user buttons: a menu of message types
 * (photos, videos, files, links, ...); the picked one shows as a removable chip in the search field and narrows the
 * search, also when searching messages of one user ({@link RawChatSearchFilter} runs it).
 * <p>
 * Ported from exteraGram's {@code ChatSearchFilterUiHelper} (exteraGram, GPLv3, https://github.com/exteraSquad/exteraGram).
 */
public final class RawChatSearchFilterButton {

    /** Buttons of the search bar: size and step (rawGram's ChatActivity places them 45.33dp apart, from 2.66dp). */
    public static final int BUTTON_SIZE_DP = 44;
    private static final float BUTTON_FIRST_X_DP = 2.66f;
    private static final float BUTTON_STEP_DP = 45.33f;
    /** What the button adds to the left margin of the result counter. */
    private static final int COUNT_TEXT_SHIFT_DP = 48;
    private static final int MENU_ICON_DP = 24;
    private static final int CHIP_ICON_DP = 18;

    private static final class Type {
        final int icon;
        final int titleRes;
        final String title;
        final TLRPC.MessagesFilter filter;
        final boolean groupsOnly;

        Type(int icon, int titleRes, String title, TLRPC.MessagesFilter filter, boolean groupsOnly) {
            this.icon = icon;
            this.titleRes = titleRes;
            this.title = title;
            this.filter = filter;
            this.groupsOnly = groupsOnly;
        }

        String title() {
            return titleRes != 0 ? LocaleController.getString(titleRes) : title;
        }
    }

    private static final Type[] TYPES = {
            new Type(R.drawable.msg_photos, 0, "Фото", new TLRPC.TL_inputMessagesFilterPhotos(), false),
            new Type(R.drawable.msg_videocall, 0, "Видео", new TLRPC.TL_inputMessagesFilterVideo(), false),
            new Type(R.drawable.msg_sendfile, R.string.SharedFilesTab2, null, new TLRPC.TL_inputMessagesFilterDocument(), false),
            new Type(R.drawable.msg_link, R.string.SharedLinksTab2, null, new TLRPC.TL_inputMessagesFilterUrl(), false),
            new Type(R.drawable.msg_voice_headphones, R.string.SharedMusicTab2, null, new TLRPC.TL_inputMessagesFilterMusic(), false),
            new Type(R.drawable.input_mic, R.string.SharedVoiceTab2, null, new TLRPC.TL_inputMessagesFilterVoice(), false),
            new Type(R.drawable.input_video, 0, "Кружки", new TLRPC.TL_inputMessagesFilterRoundVideo(), false),
            new Type(R.drawable.msg_gif, R.string.SharedGIFsTab2, null, new TLRPC.TL_inputMessagesFilterGif(), false),
            new Type(R.drawable.menu_poll_notify, 0, "Опросы", new TLRPC.TL_inputMessagesFilterPoll(), false),
            new Type(R.drawable.msg_location, 0, "Геопозиции", new TLRPC.TL_inputMessagesFilterGeo(), false),
            new Type(R.drawable.msg_pin, 0, "Закреплённые", new TLRPC.TL_inputMessagesFilterPinned(), false),
            new Type(R.drawable.msg_mention, 0, "Упоминания", new TLRPC.TL_inputMessagesFilterMyMentions(), true),
    };

    private final RawChatHooks.Host host;
    private ImageView button;
    private ActionBarMenuItem searchItem;
    private boolean groupChat;
    private Runnable onChanged;
    private FiltersView.MediaFilterData selected;

    RawChatSearchFilterButton(RawChatHooks.Host host) {
        this.host = host;
    }

    /**
     * Creates the button for the search bar. {@code groupChat}: mentions are offered; {@code onChanged} reruns the search
     * after the filter changed.
     */
    public ImageView createButton(Context context, ActionBarMenuItem searchItem, boolean groupChat, Runnable onChanged) {
        this.searchItem = searchItem;
        this.groupChat = groupChat;
        this.onChanged = onChanged;
        setSelected(null);
        Theme.ResourcesProvider resources = host.resources();
        button = new ImageView(context) {
            private int iconColor, selectorColor;

            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                // follows a theme switch: the neighbouring search buttons are recolored by ChatActivity's ThemeDescriptions
                int icon = Theme.getColor(Theme.key_chat_searchPanelIcons, resources);
                if (icon != iconColor) {
                    iconColor = icon;
                    setColorFilter(new PorterDuffColorFilter(icon, PorterDuff.Mode.MULTIPLY));
                }
                int selector = Theme.getColor(Theme.key_actionBarActionModeDefaultSelector, resources);
                if (selector != selectorColor) {
                    selectorColor = selector;
                    setBackground(Theme.createSelectorDrawable(selector, 1));
                }
                super.onDraw(canvas);
            }
        };
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(R.drawable.msg_filter_media);
        button.setContentDescription("Фильтр по типу");
        button.setOnClickListener(v -> showMenu());
        return button;
    }

    /** Layout params for {@link #createButton}'s view inside the search bar. */
    public static ViewGroup.LayoutParams layoutParams() {
        return org.telegram.ui.Components.LayoutHelper.createFrame(BUTTON_SIZE_DP, BUTTON_SIZE_DP, Gravity.LEFT | Gravity.TOP);
    }

    /** From the search bar's measureChildWithMargins: places the button after the visible {@code before} buttons. */
    public void onMeasureChild(View child, View... before) {
        if (child == null || child != button) {
            return;
        }
        int visible = 0;
        for (View view : before) {
            if (view != null && view.getVisibility() != View.GONE) {
                visible++;
            }
        }
        ((ViewGroup.MarginLayoutParams) child.getLayoutParams()).leftMargin = dp(BUTTON_FIRST_X_DP + BUTTON_STEP_DP * visible);
    }

    /** dp the button adds to the result counter's left margin. */
    public int countTextShift() {
        return button != null && button.getVisibility() != View.GONE ? COUNT_TEXT_SHIFT_DP : 0;
    }

    public boolean isSet() {
        return selected != null;
    }

    /** A search chip was removed (by the user or when the search closed); true when it was this filter's. */
    public boolean onFilterCleared(FiltersView.MediaFilterData data) {
        if (data == null || data != selected) {
            return false;
        }
        setSelected(null);
        if (searchItem != null && searchItem.isSearchFieldVisible2() && onChanged != null) {
            onChanged.run();
        }
        return true;
    }

    /** The «from» user was removed from the search: with a type filter still set, search again by type only. */
    public void onSenderCleared() {
        if (selected != null && onChanged != null) {
            onChanged.run();
        }
    }

    /** Hashtag search: no type filter there. */
    public void hideAndClear() {
        if (button != null) {
            button.setVisibility(View.GONE);
        }
        clear();
    }

    /** The search closed: the button is back for the next one. */
    public void show() {
        if (button != null) {
            button.setVisibility(View.VISIBLE);
        }
    }

    public void onDestroy() {
        MediaDataController.getInstance(host.account()).rawSearchFilter.forget(guid());
    }

    private void clear() {
        if (selected != null && searchItem != null) {
            searchItem.removeSearchFilter(selected);
        }
        setSelected(null);
    }

    private int guid() {
        return host.fragment().getClassGuid();
    }

    private void setSelected(FiltersView.MediaFilterData data) {
        selected = data;
        MediaDataController.getInstance(host.account()).rawSearchFilter.select(guid(), data != null ? data.filter : null);
    }

    private void showMenu() {
        BaseFragment fragment = host.fragment();
        if (button == null || fragment.getContext() == null) {
            return;
        }
        int[] location = new int[2];
        button.getLocationInWindow(location);
        int buttonY = location[1];
        ActionBar actionBar = fragment.getActionBar();
        int maxHeight = 0;
        if (actionBar != null) {
            actionBar.getLocationInWindow(location);
            maxHeight = buttonY - location[1] - actionBar.getMeasuredHeight();
        }
        ItemOptions options = ItemOptions.makeOptions(fragment, button)
                .setGravity(Gravity.NO_GRAVITY)
                .forceTop(true)
                .followScrimView()
                .setScrimViewBackground(Theme.createCircleDrawable(dp(BUTTON_SIZE_DP), Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, host.resources())));
        if (maxHeight > 0) {
            options.setMaxHeight(maxHeight);
        }
        for (Type type : TYPES) {
            if (type.groupsOnly && !groupChat) {
                continue;
            }
            boolean checked = selected != null && selected.filter == type.filter;
            options.addChecked(checked, menuIcon(fragment.getContext(), type.icon), type.title(), () -> select(type));
        }
        options.show();
    }

    private static Drawable menuIcon(Context context, int icon) {
        Drawable drawable = ContextCompat.getDrawable(context, icon);
        if (drawable == null) {
            return null;
        }
        // icons of different sizes (input_* are larger) look alike in the menu
        LayerDrawable layer = new LayerDrawable(new Drawable[]{drawable.mutate()});
        layer.setLayerSize(0, dp(MENU_ICON_DP), dp(MENU_ICON_DP));
        return layer;
    }

    private void select(Type type) {
        boolean same = selected != null && selected.filter == type.filter;
        if (selected != null && searchItem != null) {
            searchItem.removeSearchFilter(selected);
        }
        setSelected(null);
        if (!same) {
            FiltersView.MediaFilterData data = new FiltersView.MediaFilterData(type.icon, type.title(), type.filter, FiltersView.FILTER_TYPE_MEDIA);
            data.iconSize = CHIP_ICON_DP;
            setSelected(data);
            if (searchItem != null) {
                searchItem.addSearchFilter(data);
            }
        }
        if (searchItem != null) {
            AndroidUtilities.hideKeyboard(searchItem.getSearchField());
        }
        if (onChanged != null) {
            onChanged.run();
        }
    }
}
