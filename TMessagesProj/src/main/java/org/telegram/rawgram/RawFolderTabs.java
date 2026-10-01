package org.telegram.rawgram;

/*
 * Folder tab icons and the hidden "All chats" tab.
 * The emoticon -> icon table, getEmoticonFromFlags and the filter_* icons are ported from
 * Nagram / NekoX (tw.nekomimi.nekogram.folder.FolderIconHelper), GPLv3.
 */

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.FilterTabsView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

public class RawFolderTabs {

    private static final String ALL_CHATS_EMOTICON = "💬";

    private static final LinkedHashMap<String, Integer> folderIcons = new LinkedHashMap<>();

    static {
        folderIcons.put("🐱", R.drawable.filter_cat);
        folderIcons.put("📕", R.drawable.filter_book);
        folderIcons.put("💰", R.drawable.filter_money);
        folderIcons.put("🎮", R.drawable.filter_game);
        folderIcons.put("💡", R.drawable.filter_light);
        folderIcons.put("👌", R.drawable.filter_like);
        folderIcons.put("🎵", R.drawable.filter_note);
        folderIcons.put("🎨", R.drawable.filter_palette);
        folderIcons.put("✈", R.drawable.filter_travel);
        folderIcons.put("⚽", R.drawable.filter_sport);
        folderIcons.put("⭐", R.drawable.filter_favorite);
        folderIcons.put("🎓", R.drawable.filter_study);
        folderIcons.put("🛫", R.drawable.filter_airplane);
        folderIcons.put("👤", R.drawable.filter_private);
        folderIcons.put("👥", R.drawable.filter_group);
        folderIcons.put("💬", R.drawable.filter_all);
        folderIcons.put("✅", R.drawable.filter_unread);
        folderIcons.put("🤖", R.drawable.filter_bots);
        folderIcons.put("👑", R.drawable.filter_crown);
        folderIcons.put("🌹", R.drawable.filter_flower);
        folderIcons.put("🏠", R.drawable.filter_home);
        folderIcons.put("❤", R.drawable.filter_love);
        folderIcons.put("🎭", R.drawable.filter_mask);
        folderIcons.put("🍸", R.drawable.filter_party);
        folderIcons.put("📈", R.drawable.filter_trade);
        folderIcons.put("💼", R.drawable.filter_work);
        folderIcons.put("🔔", R.drawable.filter_unmuted);
        folderIcons.put("📢", R.drawable.filter_channels);
        folderIcons.put("📁", R.drawable.filter_custom);
        folderIcons.put("📋", R.drawable.filter_setup);
    }

    /** Tab titles are not plain text (icons or icon + text). */
    public static boolean customTitles() {
        return RawUiConfig.tabsTitleType() != RawUiConfig.TABS_TITLE_TEXT;
    }

    /** Anything differs from stock folder tabs. */
    public static boolean customTabs() {
        return RawUiConfig.hideAllTab() || customTitles();
    }

    /**
     * Adds the tab for filter {@code index} if rawGram options change it; returns false to let the
     * stock code add it. A skipped "All chats" tab also returns true.
     */
    public static boolean addTab(FilterTabsView view, int index, MessagesController.DialogFilter filter, int account) {
        if (!customTabs()) {
            return false;
        }
        if (filter.isDefault()) {
            if (RawUiConfig.hideAllTab()) {
                return true;
            }
            if (!customTitles()) {
                return false;
            }
            view.addTab(index, 0, decorate(LocaleController.getString(R.string.FilterAllChats), ALL_CHATS_EMOTICON), false, true, filter.locked);
            return true;
        }
        if (!customTitles()) {
            return false;
        }
        CharSequence text = view.text(filter.name, filter.entities);
        view.addTab(index, filter.localId, decorate(text, emoticonFor(account, filter)), filter.title_noanimate, false, filter.locked);
        return true;
    }

    private static CharSequence decorate(CharSequence title, String emoticon) {
        int type = RawUiConfig.tabsTitleType();
        SpannableStringBuilder sb = new SpannableStringBuilder("i");
        ColoredImageSpan span = new ColoredImageSpan(iconFor(emoticon));
        span.setSize(AndroidUtilities.dp(22));
        sb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (type == RawUiConfig.TABS_TITLE_MIX) {
            sb.append(' ');
            sb.append(title);
        }
        return sb;
    }

    private static int iconFor(String emoticon) {
        if (emoticon != null) {
            Integer res = folderIcons.get(emoticon);
            if (res == null) {
                res = folderIcons.get(emoticon.replace("️", ""));
            }
            if (res != null) {
                return res;
            }
        }
        return R.drawable.filter_custom;
    }

    // ---- emoticons: the local DialogFilter does not keep them, so fetch them once per session ----

    private static final HashMap<Integer, String>[] emoticons = new HashMap[16];
    private static final long[] lastRequest = new long[16];

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram_ui", Context.MODE_PRIVATE);
    }

    private static HashMap<Integer, String> map(int account) {
        HashMap<Integer, String> m = emoticons[account];
        if (m == null) {
            m = new HashMap<>();
            String saved = prefs().getString("folderEmoticons" + account, "");
            for (String entry : saved.split("\n")) {
                int sep = entry.indexOf('=');
                if (sep > 0) {
                    try {
                        m.put(Integer.parseInt(entry.substring(0, sep)), entry.substring(sep + 1));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            emoticons[account] = m;
        }
        return m;
    }

    private static String emoticonFor(int account, MessagesController.DialogFilter filter) {
        if (account < 0 || account >= emoticons.length) {
            return emoticonFromFlags(filter.flags);
        }
        HashMap<Integer, String> m = map(account);
        String e = m.get(filter.id);
        if (e == null) {
            requestEmoticons(account);
        }
        if (e == null || e.isEmpty()) {
            e = emoticonFromFlags(filter.flags);
        }
        return e;
    }

    private static void requestEmoticons(int account) {
        long now = System.currentTimeMillis();
        if (now - lastRequest[account] < 60_000) {
            return;
        }
        lastRequest[account] = now;
        TLRPC.TL_messages_getDialogFilters req = new TLRPC.TL_messages_getDialogFilters();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            ArrayList<TLRPC.DialogFilter> filters = null;
            if (response instanceof TLRPC.TL_messages_dialogFilters) {
                filters = ((TLRPC.TL_messages_dialogFilters) response).filters;
            } else if (response instanceof Vector) {
                filters = new ArrayList<>();
                for (Object o : ((Vector) response).objects) {
                    if (o instanceof TLRPC.DialogFilter) {
                        filters.add((TLRPC.DialogFilter) o);
                    }
                }
            }
            if (filters == null) {
                return;
            }
            HashMap<Integer, String> fresh = new HashMap<>();
            StringBuilder saved = new StringBuilder();
            for (TLRPC.DialogFilter f : filters) {
                String e = f.emoticon == null ? "" : f.emoticon;
                fresh.put(f.id, e);
                saved.append(f.id).append('=').append(e).append('\n');
            }
            AndroidUtilities.runOnUIThread(() -> {
                boolean changed = !fresh.equals(emoticons[account]);
                emoticons[account] = fresh;
                prefs().edit().putString("folderEmoticons" + account, saved.toString()).apply();
                if (changed) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);
                }
            });
        });
    }

    /** Default emoticon Telegram suggests for a folder made from chat types (Nagram FolderIconHelper). */
    private static String emoticonFromFlags(int newFilterFlags) {
        int flags = newFilterFlags & MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        if ((flags & MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS) == MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS) {
            if ((newFilterFlags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) != 0) {
                return "✅";
            } else if ((newFilterFlags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) != 0) {
                return "🔔";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_CONTACTS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_CONTACTS;
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
            if (flags == 0) {
                return "👤";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
            if (flags == 0) {
                return "👤";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_GROUPS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_GROUPS;
            if (flags == 0) {
                return "👥";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_BOTS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_BOTS;
            if (flags == 0) {
                return "🤖";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_CHANNELS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_CHANNELS;
            if (flags == 0) {
                return "📢";
            }
        }
        return "📁";
    }

    /** Folder reorder: tab positions skip the hidden "All chats" filter. */
    public static int tabToFilterIndex(int tabPosition, ArrayList<MessagesController.DialogFilter> filters) {
        if (!RawUiConfig.hideAllTab()) {
            return tabPosition;
        }
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i).isDefault()) {
                return tabPosition >= i ? tabPosition + 1 : tabPosition;
            }
        }
        return tabPosition;
    }
}
