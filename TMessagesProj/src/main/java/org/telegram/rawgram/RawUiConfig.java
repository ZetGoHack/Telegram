package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * rawGram interface options ("Интерфейс"), stored in the "rawgram_ui" shared preferences.
 * Every default reproduces stock Telegram behaviour. Values are cached in statics because
 * some of them are read on hot paths (Theme.getColor, Switch.onDraw, cell binding).
 */
public class RawUiConfig {

    public static final int SWITCH_DEFAULT = 0;
    public static final int SWITCH_MODERN = 1;
    public static final int SWITCH_MD3 = 2;

    public static final int TABS_TITLE_TEXT = 0;
    public static final int TABS_TITLE_ICON = 1;
    public static final int TABS_TITLE_MIX = 2;

    public static final int TITLE_STOCK = 0;
    public static final int TITLE_CUSTOM = 1;
    public static final int TITLE_ACCOUNT = 2;

    public static final int SNOW_BY_DATE = 0;
    public static final int SNOW_ALWAYS = 1;
    public static final int SNOW_OFF = 2;

    public static final int AVATAR_CORNERS_DEFAULT = 100;

    private static volatile boolean loaded;

    private static boolean hideDividers;
    private static boolean systemFont;
    private static int switchStyle;
    private static boolean hidePremiumSection;
    private static boolean hideHelpSection;
    private static boolean mainTabsHideTitles;
    private static boolean mainTabsHideContacts;
    private static boolean hideAllTab;
    private static int tabsTitleType;
    private static boolean hideDialogsSearchField;
    private static boolean disableDialogsFab;
    private static int titleMode;
    private static String customTitle;
    private static boolean folderNameAsTitle;
    private static boolean showSeconds;
    private static int avatarCorners = AVATAR_CORNERS_DEFAULT;
    private static boolean hidePhone;
    private static int snowMode;
    private static int iconPack;
    private static String mainTabsOrder;
    private static boolean mainTabsSearch;
    private static boolean foldersAtBottom;
    private static boolean sideMenu;
    private static boolean hideArchive;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram_ui", Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (RawUiConfig.class) {
            if (loaded || ApplicationLoader.applicationContext == null) {
                return;
            }
            SharedPreferences p = prefs();
            hideDividers = p.getBoolean("hideDividers", false);
            systemFont = p.getBoolean("systemFont", false);
            switchStyle = p.getInt("switchStyle", SWITCH_DEFAULT);
            hidePremiumSection = p.getBoolean("hidePremiumSection", false);
            hideHelpSection = p.getBoolean("hideHelpSection", false);
            mainTabsHideTitles = p.getBoolean("mainTabsHideTitles", false);
            mainTabsHideContacts = p.getBoolean("mainTabsHideContacts", false);
            hideAllTab = p.getBoolean("hideAllTab", false);
            tabsTitleType = p.getInt("tabsTitleType", TABS_TITLE_TEXT);
            hideDialogsSearchField = p.getBoolean("hideDialogsSearchField", false);
            disableDialogsFab = p.getBoolean("disableDialogsFab", false);
            titleMode = p.getInt("titleMode", TITLE_STOCK);
            customTitle = p.getString("customTitle", "");
            folderNameAsTitle = p.getBoolean("folderNameAsTitle", false);
            showSeconds = p.getBoolean("showSeconds", false);
            avatarCorners = Math.max(0, Math.min(100, p.getInt("avatarCorners", AVATAR_CORNERS_DEFAULT)));
            hidePhone = p.getBoolean("hidePhone", false);
            snowMode = p.getInt("snowMode", SNOW_BY_DATE);
            iconPack = p.getInt("iconPack", 0);
            mainTabsOrder = p.getString("mainTabsOrder", RawMainTabs.DEFAULT);
            mainTabsSearch = p.getBoolean("mainTabsSearch", false);
            foldersAtBottom = p.getBoolean("foldersAtBottom", false);
            sideMenu = p.getBoolean("sideMenu", false);
            hideArchive = p.getBoolean("hideArchive", false);
            loaded = true;
        }
    }

    private static void put(String key, Object value) {
        if (ApplicationLoader.applicationContext == null) {
            return;
        }
        SharedPreferences.Editor e = prefs().edit();
        if (value instanceof Boolean) {
            e.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            e.putInt(key, (Integer) value);
        } else {
            e.putString(key, String.valueOf(value));
        }
        e.apply();
    }

    // ---- dividers ----
    public static boolean hideDividers() { ensureLoaded(); return hideDividers; }
    public static void setHideDividers(boolean v) { ensureLoaded(); hideDividers = v; put("hideDividers", v); }

    // ---- system font (applies after restart: typefaces are cached) ----
    public static boolean systemFont() { ensureLoaded(); return systemFont; }
    public static void setSystemFont(boolean v) { ensureLoaded(); systemFont = v; put("systemFont", v); }

    // ---- switch style ----
    public static int switchStyle() { ensureLoaded(); return switchStyle; }
    public static void setSwitchStyle(int v) { ensureLoaded(); switchStyle = v; put("switchStyle", v); }

    // ---- settings sections ----
    public static boolean hidePremiumSection() { ensureLoaded(); return hidePremiumSection; }
    public static void setHidePremiumSection(boolean v) { ensureLoaded(); hidePremiumSection = v; put("hidePremiumSection", v); }
    public static boolean hideHelpSection() { ensureLoaded(); return hideHelpSection; }
    public static void setHideHelpSection(boolean v) { ensureLoaded(); hideHelpSection = v; put("hideHelpSection", v); }

    // ---- bottom tabs (applied when the main screen is created) ----
    public static boolean mainTabsHideTitles() { ensureLoaded(); return mainTabsHideTitles; }
    public static void setMainTabsHideTitles(boolean v) { ensureLoaded(); mainTabsHideTitles = v; put("mainTabsHideTitles", v); }
    public static boolean mainTabsHideContacts() { ensureLoaded(); return mainTabsHideContacts; }
    public static void setMainTabsHideContacts(boolean v) { ensureLoaded(); mainTabsHideContacts = v; put("mainTabsHideContacts", v); }

    public static boolean sideMenu() { ensureLoaded(); return sideMenu; }
    public static void setSideMenu(boolean v) { ensureLoaded(); sideMenu = v; put("sideMenu", v); }
    public static boolean hideArchive() { ensureLoaded(); return hideArchive; }
    public static void setHideArchive(boolean v) { ensureLoaded(); hideArchive = v; put("hideArchive", v); }
    public static boolean foldersAtBottom() { ensureLoaded(); return foldersAtBottom; }
    public static void setFoldersAtBottom(boolean v) { ensureLoaded(); foldersAtBottom = v; put("foldersAtBottom", v); }
    public static boolean mainTabsSearch() { ensureLoaded(); return mainTabsSearch; }
    public static void setMainTabsSearch(boolean v) { ensureLoaded(); mainTabsSearch = v; put("mainTabsSearch", v); }
    public static String mainTabsOrder() { ensureLoaded(); return mainTabsOrder; }
    public static void setMainTabsOrder(String v) { ensureLoaded(); mainTabsOrder = v; put("mainTabsOrder", v); }

    // ---- folder tabs ----
    public static boolean hideAllTab() { ensureLoaded(); return hideAllTab; }
    public static void setHideAllTab(boolean v) { ensureLoaded(); hideAllTab = v; put("hideAllTab", v); }
    public static int tabsTitleType() { ensureLoaded(); return tabsTitleType; }
    public static void setTabsTitleType(int v) { ensureLoaded(); tabsTitleType = v; put("tabsTitleType", v); }

    // ---- chats list ----
    public static boolean hideDialogsSearchField() { ensureLoaded(); return hideDialogsSearchField; }
    public static void setHideDialogsSearchField(boolean v) { ensureLoaded(); hideDialogsSearchField = v; put("hideDialogsSearchField", v); }
    public static boolean disableDialogsFab() { ensureLoaded(); return disableDialogsFab; }
    public static void setDisableDialogsFab(boolean v) { ensureLoaded(); disableDialogsFab = v; put("disableDialogsFab", v); }

    // ---- main screen title ----
    public static int titleMode() { ensureLoaded(); return titleMode; }
    public static void setTitleMode(int v) { ensureLoaded(); titleMode = v; put("titleMode", v); }
    public static String customTitle() { ensureLoaded(); return customTitle == null ? "" : customTitle; }
    public static void setCustomTitle(String v) { ensureLoaded(); customTitle = v == null ? "" : v; put("customTitle", customTitle); }
    public static boolean folderNameAsTitle() { ensureLoaded(); return folderNameAsTitle; }
    public static void setFolderNameAsTitle(boolean v) { ensureLoaded(); folderNameAsTitle = v; put("folderNameAsTitle", v); }

    // ---- seconds in message / list times ----
    public static boolean showSeconds() { ensureLoaded(); return showSeconds; }
    public static void setShowSeconds(boolean v) { ensureLoaded(); showSeconds = v; put("showSeconds", v); }

    // ---- avatar corners: 100 = circle (stock) ----
    public static int avatarCorners() { ensureLoaded(); return avatarCorners; }
    public static void setAvatarCorners(int v) { ensureLoaded(); avatarCorners = Math.max(0, Math.min(100, v)); put("avatarCorners", avatarCorners); }

    // ---- hide own phone number ("Номер скрыт") ----
    public static boolean isHidePhone() { ensureLoaded(); return hidePhone; }
    public static void setHidePhone(boolean v) { ensureLoaded(); hidePhone = v; put("hidePhone", v); }

    // ---- snow on the main screen ----
    public static int snowMode() { ensureLoaded(); return snowMode; }
    public static void setSnowMode(int v) { ensureLoaded(); snowMode = v; put("snowMode", v); }

    // ---- icon pack: 0 = Telegram, 1 = Solar (RawIcons; applies after restart) ----
    public static int getIconPack() { ensureLoaded(); return iconPack; }
    public static void setIconPack(int v) { ensureLoaded(); iconPack = v; put("iconPack", v); }

    /** Snow decision for the main-screen action bar: by date = stock holiday check. */
    public static boolean snow(boolean stockHoliday) {
        int mode = snowMode();
        return mode == SNOW_BY_DATE ? stockHoliday : mode == SNOW_ALWAYS;
    }
}
