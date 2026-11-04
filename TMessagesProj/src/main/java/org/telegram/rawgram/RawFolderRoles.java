package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GroupCreateUserCell;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * «Моя роль» folder filter: a folder that includes groups / channels by type keeps only those where the
 * user is the owner or an admin. Applied on the client on top of the server filter.
 * <p>
 * Ported from exteraGram (com.exteragram.messenger.utils.chats.FolderRoles and
 * utils.ui.FolderRolesUiHelper), GPLv3. exteraGram syncs the modes through its API bot's cloud storage;
 * rawGram keeps them locally, per account and folder id.
 * <p>
 * The folder editor hands the mode to the chat picker ({@code UsersSelectActivity}) inside the filter
 * flags (bit 29 owner, bit 30 admin), the same way exteraGram does; the bits never reach the server.
 */
public final class RawFolderRoles {

    public static final int MODE_NONE = 0;
    public static final int MODE_OWNER = 1;
    public static final int MODE_ADMIN = 2;

    public static final String TYPE_OWNER = "folder_role_owner";
    public static final String TYPE_ADMIN = "folder_role_admin";

    /** Span uids in the picker; stock uses Long.MIN_VALUE + 0..9 for chat types. */
    public static final long SPAN_UID_OWNER = Long.MIN_VALUE + 10;
    public static final long SPAN_UID_ADMIN = Long.MIN_VALUE + 11;

    public static final int PICKER_FLAG_OWNER = 1 << 29;
    public static final int PICKER_FLAG_ADMIN = 1 << 30;
    private static final int PICKER_FLAGS = PICKER_FLAG_OWNER | PICKER_FLAG_ADMIN;

    public static final int AVATAR_TYPE_OWNER = 1001;
    public static final int AVATAR_TYPE_ADMIN = 1002;

    /** Rows the include picker gets: the «Моя роль» header and the two role rows (positions 6..8). */
    public static final int PICKER_ROWS = 3;
    public static final int PICKER_HEADER_ROW = 6;
    public static final int PICKER_FIRST_ROW = 7;

    private static final String PREFS = "rawgram_folder_roles";

    private static final Object lock = new Object();
    /** Per account, copy-on-write: filterId -> mode. Read from the dialog filter check. */
    private static final AtomicReferenceArray<SparseIntArray> modes = new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);

    private RawFolderRoles() {
    }

    // ---- storage ----

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String keyPrefix(int account) {
        return "a" + account + "_";
    }

    private static SparseIntArray modesOf(int account) {
        SparseIntArray map = modes.get(account);
        if (map != null) {
            return map;
        }
        synchronized (lock) {
            map = modes.get(account);
            if (map == null) {
                map = new SparseIntArray();
                String prefix = keyPrefix(account);
                for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                    if (!e.getKey().startsWith(prefix) || !(e.getValue() instanceof Integer)) {
                        continue;
                    }
                    int mode = (Integer) e.getValue();
                    if (mode != MODE_OWNER && mode != MODE_ADMIN) {
                        continue;
                    }
                    try {
                        map.put(Integer.parseInt(e.getKey().substring(prefix.length())), mode);
                    } catch (NumberFormatException ignore) {
                    }
                }
                modes.set(account, map);
            }
            return map;
        }
    }

    public static int getMode(int account, int filterId) {
        return modesOf(account).get(filterId, MODE_NONE);
    }

    /** Some folder of this account has a role (the folder counters' fast path). */
    public static boolean hasAny(int account) {
        return modesOf(account).size() > 0;
    }

    /** The dialog filter check: false hides a chat the folder includes by type. */
    public static boolean allows(int account, int filterId, TLRPC.Chat chat) {
        int mode = getMode(account, filterId);
        if (mode == MODE_OWNER) {
            return chat.creator;
        }
        if (mode != MODE_ADMIN) {
            return true;
        }
        return ChatObject.hasAdminRights(chat);
    }

    public static void setMode(int account, int filterId, int filterFlags, int requestedMode) {
        int mode = effectiveMode(filterFlags, requestedMode);
        if (getMode(account, filterId) == mode) {
            return;
        }
        synchronized (lock) {
            SparseIntArray map = modesOf(account).clone();
            if (mode == MODE_NONE) {
                map.delete(filterId);
            } else {
                map.put(filterId, mode);
            }
            modes.set(account, map);
        }
        SharedPreferences.Editor editor = prefs().edit();
        if (mode == MODE_NONE) {
            editor.remove(keyPrefix(account) + filterId);
        } else {
            editor.putInt(keyPrefix(account) + filterId, mode);
        }
        editor.apply();
        refresh(account);
    }

    /** Drops the modes of deleted folders once the folder list is known. */
    public static void onFiltersChanged(int account) {
        UserConfig userConfig = UserConfig.getInstance(account);
        if (!userConfig.isClientActivated() || !userConfig.filtersLoaded) {
            return;
        }
        SparseIntArray current = modesOf(account);
        if (current.size() == 0) {
            return;
        }
        SparseArray<MessagesController.DialogFilter> filters = MessagesController.getInstance(account).dialogFiltersById;
        SparseIntArray kept = new SparseIntArray();
        SharedPreferences.Editor editor = null;
        for (int i = 0; i < current.size(); i++) {
            int filterId = current.keyAt(i);
            if (filters.get(filterId) != null) {
                kept.put(filterId, current.valueAt(i));
            } else {
                if (editor == null) {
                    editor = prefs().edit();
                }
                editor.remove(keyPrefix(account) + filterId);
            }
        }
        if (editor == null) {
            return;
        }
        editor.apply();
        synchronized (lock) {
            modes.set(account, kept);
        }
        refresh(account);
    }

    /** Logout: forget this account's modes. */
    public static void clear(int account) {
        synchronized (lock) {
            modes.set(account, new SparseIntArray());
        }
        String prefix = keyPrefix(account);
        SharedPreferences.Editor editor = prefs().edit();
        for (String key : prefs().getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static void refresh(int account) {
        MessagesController.getInstance(account).sortDialogs(null);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> storage.resetAllUnreadCounters(false));
    }

    // ---- flags / types ----

    public static boolean supportsFlags(int filterFlags) {
        return (filterFlags & (MessagesController.DIALOG_FILTER_FLAG_GROUPS | MessagesController.DIALOG_FILTER_FLAG_CHANNELS)) != 0;
    }

    public static int effectiveMode(int filterFlags, int mode) {
        return supportsFlags(filterFlags) ? mode : MODE_NONE;
    }

    /** The editor's unsaved mode differs from the stored one. */
    public static boolean changed(int account, int filterId, int filterFlags, int mode) {
        return effectiveMode(filterFlags, mode) != getMode(account, filterId);
    }

    public static String chatTypeOf(int mode) {
        if (mode == MODE_OWNER) {
            return TYPE_OWNER;
        }
        return mode == MODE_ADMIN ? TYPE_ADMIN : null;
    }

    public static int modeOf(String chatType) {
        if (TYPE_OWNER.equals(chatType)) {
            return MODE_OWNER;
        }
        return TYPE_ADMIN.equals(chatType) ? MODE_ADMIN : MODE_NONE;
    }

    public static long spanUidOf(int mode) {
        return mode == MODE_OWNER ? SPAN_UID_OWNER : SPAN_UID_ADMIN;
    }

    public static boolean isRoleSpanUid(long uid) {
        return uid == SPAN_UID_OWNER || uid == SPAN_UID_ADMIN;
    }

    public static int toPickerFlags(int mode) {
        if (mode == MODE_OWNER) {
            return PICKER_FLAG_OWNER;
        }
        return mode == MODE_ADMIN ? PICKER_FLAG_ADMIN : 0;
    }

    public static int fromPickerFlags(int flags) {
        if ((flags & PICKER_FLAG_OWNER) != 0) {
            return MODE_OWNER;
        }
        return (flags & PICKER_FLAG_ADMIN) != 0 ? MODE_ADMIN : MODE_NONE;
    }

    public static int withPickerMode(int flags, int mode) {
        return toPickerFlags(mode) | (flags & ~PICKER_FLAGS);
    }

    /** Flags for the chat picker: the include picker also carries the role. */
    public static int pickerFlags(boolean include, int filterFlags, int mode) {
        return include ? withPickerMode(filterFlags, mode) : filterFlags;
    }

    // ---- UI ----

    public static boolean isRoleAvatar(int avatarType) {
        return avatarType == AVATAR_TYPE_OWNER || avatarType == AVATAR_TYPE_ADMIN;
    }

    /** Avatar type for a role chat type, or -1 for anything else. */
    public static int avatarTypeOf(String chatType) {
        int mode = modeOf(chatType);
        if (mode == MODE_OWNER) {
            return AVATAR_TYPE_OWNER;
        }
        return mode == MODE_ADMIN ? AVATAR_TYPE_ADMIN : -1;
    }

    /** Index into Theme.keys_avatar_background for the role avatars. */
    public static int avatarColorIndex(int avatarType) {
        return avatarType == AVATAR_TYPE_OWNER ? 2 : 4;
    }

    private static Drawable ownerIcon;
    private static Drawable adminIcon;
    private static int iconKey;

    public static Drawable getAvatarIcon(int avatarType) {
        Context context = ApplicationLoader.applicationContext;
        int color = Theme.getColor(Theme.key_avatar_text);
        int key = color * 31 + System.identityHashCode(context.getResources());
        if (ownerIcon == null || adminIcon == null || iconKey != key) {
            PorterDuffColorFilter filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            Drawable owner = ContextCompat.getDrawable(context, R.drawable.filled_owner);
            Drawable admin = ContextCompat.getDrawable(context, R.drawable.filled_admins);
            ownerIcon = owner != null ? owner.mutate() : null;
            adminIcon = admin != null ? admin.mutate() : null;
            if (ownerIcon != null) {
                ownerIcon.setColorFilter(filter);
            }
            if (adminIcon != null) {
                adminIcon.setColorFilter(filter);
            }
            iconKey = key;
        }
        return avatarType == AVATAR_TYPE_OWNER ? ownerIcon : adminIcon;
    }

    public static String getName(String chatType) {
        return modeOf(chatType) == MODE_OWNER ? LocaleController.getString(R.string.ChannelCreator) : res(R.string.FolderRolesAdmin);
    }

    public static String getChipText(int mode) {
        return res(mode == MODE_OWNER ? R.string.FolderRolesOwnerOnly : R.string.FolderRolesAdminOnly);
    }

    public static String getPickerHeader() {
        return res(R.string.FolderRolesHeader);
    }

    /** Binds role row {@code row} (0 owner, 1 admin) of the include picker. */
    public static void bindPickerRow(GroupCreateUserCell cell, int row, int pickerFlags) {
        int mode = row == 0 ? MODE_OWNER : MODE_ADMIN;
        String chatType = chatTypeOf(mode);
        cell.setAlpha(supportsFlags(pickerFlags) ? 1f : 0.5f);
        cell.setObject(chatType, getName(chatType), null);
        cell.setChecked(fromPickerFlags(pickerFlags) == mode, false);
        cell.setCheckBoxEnabled(true);
    }

    /** strings_raw_folders.xml is not in LocaleController's table (it would show LOC_ERR), so read it from resources. */
    private static String res(int id) {
        return org.telegram.messenger.ApplicationLoader.applicationContext.getString(id);
    }
}
