package org.telegram.rawgram;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Peers by id, from Telegram's local cache only (memory, then the local database; never the network:
 * without an access_hash nothing can be fetched anyway, and with one the peer is already cached).
 * <p>
 * 1. Chats search: an id-like query ({@code 123456789}, {@code -1001234567890}, {@code -123456}, {@code id123456})
 *    puts the cached peers with that id at the top of the local results.
 * 2. Numbers in messages: at the bottom of the phone menu of a highlighted number, next to the stock profile row
 *    of the phone match, the same profile rows for cached peers with that id, or a "find by id" item that also
 *    looks into the local database. A phone match plus a different id match gets the RawEasterEggs celebration.
 */
public class RawIdLookup {

    private static final Pattern ID_QUERY = Pattern.compile("^(-100|-)?\\d{5,}$|^id\\d+$");

    // ---- candidates ----

    /** Candidate ids parsed from a query: user ids and chat ids (chats and channels share MessagesController.chats). */
    private static final class Candidates {
        final ArrayList<Long> users = new ArrayList<>();
        final ArrayList<Long> chats = new ArrayList<>();

        void user(long id) {
            if (id > 0 && !users.contains(id)) {
                users.add(id);
            }
        }

        void chat(long id) {
            if (id > 0 && !chats.contains(id)) {
                chats.add(id);
            }
        }

        boolean isEmpty() {
            return users.isEmpty() && chats.isEmpty();
        }
    }

    public static boolean isIdQuery(String query) {
        return query != null && ID_QUERY.matcher(query.trim().toLowerCase()).matches();
    }

    private static long parse(String digits) {
        if (TextUtils.isEmpty(digits) || digits.length() > 18) {
            return 0;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Candidates of a search query; empty when it doesn't look like an id. */
    private static Candidates forQuery(String query) {
        Candidates c = new Candidates();
        if (query == null) {
            return c;
        }
        String q = query.trim().toLowerCase();
        if (!ID_QUERY.matcher(q).matches()) {
            return c;
        }
        if (q.startsWith("id")) {
            // raw MTProto id: could be a user or a chat
            long id = parse(q.substring(2));
            c.user(id);
            c.chat(id);
        } else if (q.startsWith("-100")) {
            c.chat(parse(q.substring(4))); // Bot API channel / supergroup
            c.chat(parse(q.substring(1))); // or a basic group whose id happens to start with 100
        } else if (q.startsWith("-")) {
            c.chat(parse(q.substring(1))); // Bot API basic group
        } else {
            long id = parse(q);
            c.user(id);
            c.chat(id); // MTProto chat / channel id
        }
        return c;
    }

    /** Candidates of a number highlighted in a message (digits only, the "-" of "-100…" isn't part of the link). */
    private static Candidates forNumber(String digits) {
        Candidates c = new Candidates();
        if (digits == null || digits.length() < 5 || digits.length() > 19) {
            return c;
        }
        long id = parse(digits);
        c.user(id);
        c.chat(id);
        if (digits.startsWith("100") && digits.length() >= 13) {
            c.chat(parse(digits.substring(3)));
        }
        return c;
    }

    private static void fromMemory(int account, Candidates c, ArrayList<TLObject> out) {
        MessagesController mc = MessagesController.getInstance(account);
        for (long id : c.users) {
            TLRPC.User user = mc.getUser(id);
            if (user != null) {
                out.add(user);
            }
        }
        for (long id : c.chats) {
            TLRPC.Chat chat = mc.getChat(id);
            if (chat != null) {
                out.add(chat);
            }
        }
    }

    /** Memory, then the local database for whatever memory didn't have. Must run on the storage queue. */
    private static void fromMemoryAndDb(int account, Candidates c, ArrayList<TLObject> out) {
        MessagesController mc = MessagesController.getInstance(account);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        for (long id : c.users) {
            TLRPC.User user = mc.getUser(id);
            if (user == null) {
                user = storage.getUser(id);
            }
            if (user != null) {
                out.add(user);
            }
        }
        for (long id : c.chats) {
            TLRPC.Chat chat = mc.getChat(id);
            if (chat == null) {
                chat = storage.getChat(id);
            }
            if (chat != null) {
                out.add(chat);
            }
        }
    }

    private static boolean sameObject(Object a, TLObject b) {
        if (a instanceof TLRPC.User && b instanceof TLRPC.User) {
            return ((TLRPC.User) a).id == ((TLRPC.User) b).id;
        }
        if (a instanceof TLRPC.Chat && b instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) a).id == ((TLRPC.Chat) b).id;
        }
        return false;
    }

    // ---- 1. chats search ----

    private static boolean searchAllowed(int dialogsType) {
        return dialogsType == DialogsActivity.DIALOGS_TYPE_DEFAULT || dialogsType == DialogsActivity.DIALOGS_TYPE_FORWARD;
    }

    /**
     * Called by DialogsSearchAdapter.searchDialogsInternal on the storage queue, right after the local search:
     * puts the peers with the queried id at the top of the local results (dropping their duplicates below).
     * Users / chats loaded from the database reach MessagesController in updateSearchResults (putUser / putChat).
     */
    public static void prependSearchResults(int account, int dialogsType, String query, ArrayList<Object> result, ArrayList<CharSequence> names) {
        if (!RawgramConfig.isIdSearch() || !searchAllowed(dialogsType) || result == null || names == null) {
            return;
        }
        try {
            Candidates c = forQuery(query);
            if (c.isEmpty()) {
                return;
            }
            ArrayList<TLObject> found = new ArrayList<>();
            fromMemoryAndDb(account, c, found);
            if (found.isEmpty()) {
                return;
            }
            for (int i = result.size() - 1; i >= 0; i--) {
                for (TLObject obj : found) {
                    if (sameObject(result.get(i), obj)) {
                        result.remove(i);
                        if (i < names.size()) {
                            names.remove(i);
                        }
                        break;
                    }
                }
            }
            while (names.size() < result.size()) {
                names.add(null);
            }
            for (int i = found.size() - 1; i >= 0; i--) {
                result.add(0, found.get(i));
                names.add(0, null); // null: the cell shows the peer's own name
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Subtitle of a search row whose peer matches the id query: "ID -100…" before the stock subtitle. */
    public static CharSequence searchSubtitle(String query, Object obj, CharSequence subtitle) {
        if (!RawgramConfig.isIdSearch() || !(obj instanceof TLRPC.User || obj instanceof TLRPC.Chat) || !isIdQuery(query)) {
            return subtitle;
        }
        Candidates c = forQuery(query);
        String id = null;
        if (obj instanceof TLRPC.User && c.users.contains(((TLRPC.User) obj).id)) {
            id = RawIds.forUser((TLRPC.User) obj, idFormat());
        } else if (obj instanceof TLRPC.Chat && c.chats.contains(((TLRPC.Chat) obj).id)) {
            id = RawIds.forChat((TLRPC.Chat) obj, idFormat());
        }
        if (id == null) {
            return subtitle;
        }
        return TextUtils.isEmpty(subtitle) ? "ID " + id : TextUtils.concat("ID " + id + ", ", subtitle);
    }

    // ---- 2. numbers in messages ----

    private static final String LABEL_BY_PHONE = "по номеру";
    private static final String LABEL_BY_ID = "по ID";

    /** Cached peers whose id is the highlighted number, without the peer the phone lookup already found. */
    private static ArrayList<TLObject> idMatches(int account, String phone, TLRPC.User phoneUser) {
        ArrayList<TLObject> found = new ArrayList<>();
        if (!RawgramConfig.isNumberIds() || phone == null) {
            return found;
        }
        Candidates c = forNumber(phone.replaceAll("[^0-9]", ""));
        if (c.isEmpty()) {
            return found;
        }
        fromMemory(account, c, found);
        if (phoneUser != null) {
            for (int i = found.size() - 1; i >= 0; i--) {
                if (sameObject(phoneUser, found.get(i))) {
                    found.remove(i); // the same peer both ways: shown once, as the stock phone row
                }
            }
        }
        return found;
    }

    /**
     * Subtitle of the stock "View profile" row of the phone menu: gets "· по номеру" when the number is also
     * the id of a different cached peer (both rows are shown then, each labeled).
     */
    public static CharSequence phoneProfileSubtitle(BaseFragment fragment, String phone, TLRPC.User phoneUser, CharSequence subtitle) {
        try {
            if (fragment != null && phoneUser != null && !idMatches(fragment.getCurrentAccount(), phone, phoneUser).isEmpty()) {
                return subtitle + " · " + LABEL_BY_PHONE;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return subtitle;
    }

    /**
     * Called by ChatActivity.didPressPhoneNumber after the stock items, so these sit at the bottom next to the stock
     * profile row of the phone match: profile rows (the same ItemOptions.addProfile button) for cached peers whose id
     * is the number, labeled "по ID"; or, with nothing in memory, a "find by id" item that also checks the local DB.
     * A phone match and a different id match at once is rare enough to be celebrated.
     */
    public static void addNumberItems(BaseFragment fragment, ItemOptions options, String phone, TLRPC.User phoneUser) {
        if (!RawgramConfig.isNumberIds() || fragment == null || options == null || phone == null) {
            return;
        }
        try {
            String digits = phone.replaceAll("[^0-9]", "");
            if (TEST_NUMBER.equals(digits)) {
                addTestMatch(fragment, options);
                return;
            }
            Candidates c = forNumber(digits);
            if (c.isEmpty()) {
                return;
            }
            int account = fragment.getCurrentAccount();
            // the stock phone-match profile row is the last item when the phone lookup found someone
            View phoneRow = phoneUser != null ? options.getLastView() : null;
            ArrayList<TLObject> found = idMatches(account, phone, phoneUser);
            if (!found.isEmpty()) {
                if (phoneUser == null) {
                    options.addGap();
                }
                ArrayList<View> rows = new ArrayList<>();
                int shown = 0;
                for (TLObject obj : found) {
                    if (shown++ >= 3) {
                        break;
                    }
                    options.addProfile(obj, openLabel(obj) + " · " + LABEL_BY_ID, () -> open(fragment, obj));
                    View row = options.getLastView();
                    if (row != null) {
                        rows.add(row);
                    }
                }
                if (phoneUser != null && phoneRow != null && !rows.isEmpty()) {
                    // two different real profiles behind one number: phone match and id match
                    options.addText("Редкое совпадение!", 12);
                    View caption = options.getLastView();
                    if (caption instanceof TextView) {
                        ((TextView) caption).setTextColor(RawEasterEggs.GOLD_DEEP);
                        ((TextView) caption).setGravity(Gravity.CENTER_HORIZONTAL);
                    }
                    rows.add(0, phoneRow);
                    RawEasterEggs.celebrate(rows.toArray(new View[0]));
                }
            } else if (!phone.trim().startsWith("+") && digits.length() >= 6) {
                options.addGap();
                options.add(R.drawable.msg_search, "Найти пользователя с ID " + digits, () -> findInDb(fragment, account, c, digits));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- test number: "6767" in any message is highlighted and always plays the rare-match easter egg ----

    public static final String TEST_NUMBER = "6767";
    private static final java.util.regex.Pattern TEST_NUMBER_PATTERN = java.util.regex.Pattern.compile("(?<![0-9+])6767(?![0-9])");

    /** MessageObject.addLinks hook: highlights the test number like a phone number. */
    public static void addTestNumberLinks(CharSequence text) {
        if (!(text instanceof android.text.Spannable) || !RawgramConfig.isNumberIds() || text.length() < 4
                || android.text.TextUtils.indexOf(text, TEST_NUMBER) < 0) {
            return;
        }
        android.text.Spannable spannable = (android.text.Spannable) text;
        java.util.regex.Matcher m = TEST_NUMBER_PATTERN.matcher(text);
        while (m.find()) {
            if (spannable.getSpans(m.start(), m.end(), android.text.style.URLSpan.class).length == 0) {
                spannable.setSpan(new org.telegram.ui.Components.URLSpanNoUnderline("tel:" + TEST_NUMBER), m.start(), m.end(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /** Own profile "by phone" and "by id", the caption and the full celebration. */
    private static void addTestMatch(BaseFragment fragment, ItemOptions options) {
        TLRPC.User self = fragment.getUserConfig().getCurrentUser();
        if (self == null) {
            return;
        }
        options.addGap();
        ArrayList<View> rows = new ArrayList<>();
        options.addProfile(self, "Открыть профиль · " + LABEL_BY_PHONE, () -> open(fragment, self));
        if (options.getLastView() != null) rows.add(options.getLastView());
        options.addProfile(self, "Открыть профиль · " + LABEL_BY_ID, () -> open(fragment, self));
        if (options.getLastView() != null) rows.add(options.getLastView());
        options.addText("Редкое совпадение! (тестовое число " + TEST_NUMBER + ")", 12);
        View caption = options.getLastView();
        if (caption instanceof TextView) {
            ((TextView) caption).setTextColor(RawEasterEggs.GOLD_DEEP);
            ((TextView) caption).setGravity(Gravity.CENTER_HORIZONTAL);
        }
        RawEasterEggs.celebrate(rows.toArray(new View[0]));
    }

    private static String openLabel(TLObject obj) {
        if (obj instanceof TLRPC.Chat) {
            return ChatObject.isChannelAndNotMegaGroup((TLRPC.Chat) obj) ? "Открыть канал" : "Открыть группу";
        }
        return LocaleController.getString(R.string.ViewProfile);
    }

    private static void findInDb(BaseFragment fragment, int account, Candidates c, String digits) {
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            ArrayList<TLObject> found = new ArrayList<>();
            try {
                fromMemoryAndDb(account, c, found);
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (found.isEmpty()) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, "Нет в кеше: ID " + digits).show();
                    return;
                }
                MessagesController mc = MessagesController.getInstance(account);
                for (TLObject obj : found) {
                    if (obj instanceof TLRPC.User) {
                        mc.putUser((TLRPC.User) obj, true);
                    } else if (obj instanceof TLRPC.Chat) {
                        mc.putChat((TLRPC.Chat) obj, true);
                    }
                }
                if (found.size() == 1) {
                    open(fragment, found.get(0));
                    return;
                }
                // a user and a chat with the same number: let the user pick
                CharSequence[] items = new CharSequence[found.size()];
                for (int i = 0; i < items.length; i++) {
                    items[i] = title(found.get(i)) + "\n" + describe(found.get(i));
                }
                fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                        .setTitle("ID " + digits)
                        .setItems(items, (d, which) -> open(fragment, found.get(which)))
                        .create());
            });
        });
    }

    private static String title(TLObject obj) {
        if (obj instanceof TLRPC.User) {
            return UserObject.getUserName((TLRPC.User) obj);
        } else if (obj instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) obj).title;
        }
        return "";
    }

    private static String describe(TLObject obj) {
        String username = null;
        String id = null;
        if (obj instanceof TLRPC.User) {
            username = UserObject.getPublicUsername((TLRPC.User) obj);
            id = RawIds.forUser((TLRPC.User) obj, idFormat());
        } else if (obj instanceof TLRPC.Chat) {
            username = ChatObject.getPublicUsername((TLRPC.Chat) obj);
            id = RawIds.forChat((TLRPC.Chat) obj, idFormat());
        }
        return (TextUtils.isEmpty(username) ? "" : "@" + username + " · ") + id;
    }

    private static void open(BaseFragment fragment, TLObject obj) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (obj instanceof TLRPC.User) {
            fragment.presentFragment(ProfileActivity.of(((TLRPC.User) obj).id));
        } else if (obj instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) obj;
            boolean canOpenChat = !(chat instanceof TLRPC.TL_chatForbidden || chat instanceof TLRPC.TL_channelForbidden)
                    && (!ChatObject.isNotInChat(chat) || ChatObject.isPublic(chat));
            fragment.presentFragment(canOpenChat ? ChatActivity.of(-chat.id) : ProfileActivity.of(-chat.id));
        }
    }

    private static int idFormat() {
        int format = RawgramConfig.getIdFormat();
        return format == RawgramConfig.ID_OFF ? RawgramConfig.ID_BOTAPI : format;
    }
}
