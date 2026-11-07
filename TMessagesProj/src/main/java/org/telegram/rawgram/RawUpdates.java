package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rawGram releases from the @rwgrm chat: posts in topic {@link #TOPIC} carry the tag «rwgrm&lt;build&gt;» (e.g. rwgrm127), «Telegram
 * &lt;version&gt;» and list the changes on the next lines (with the APK or a link to it). The newest post with a build
 * number above {@link RawVersion#build()} is offered as an update.
 * <p>
 * A post with the APK attached becomes the pending update: Telegram's own update sheet ({@link RawUpdateSheet}) and the
 * update bar over the bottom tabs ({@link RawUpdateLayout}) download and install it. The pending post is kept in the
 * preferences, so the bar survives a restart, and is dropped once this build reaches it. A post without the APK only
 * offers to open it in the chat.
 * <p>
 * Only plugins builds are released, so builds of the plain branch (their own numbering) don't check.
 */
public final class RawUpdates {

    private RawUpdates() {
    }

    private static final String CHAT = "rwgrm";
    private static final int TOPIC = 2;
    /** How many recent topic posts are read: enough to skip a few chat messages after the release. */
    private static final int LIMIT = 20;
    /** The check at start runs at most this often. */
    private static final long AUTO_INTERVAL = 6 * 60 * 60 * 1000L;
    /** The release tag «rwgrm127» anywhere in the post: the build number right after «rwgrm» (the chat's rule). */
    private static final Pattern HEADER = Pattern.compile("(?<![\\w@/])rwgrm(\\d+)", Pattern.CASE_INSENSITIVE);
    /** The tag (with an optional «#») on its own line or at the end of a line, with the spaces and a separator before it. */
    private static final Pattern TAG_LINE = Pattern.compile("[ \\t]*(?:[·|—–-][ \\t]*)?#?(?<![\\w@/])rwgrm\\d+[ \\t]*(?=\\n|$)", Pattern.CASE_INSENSITIVE);

    private static final String KEY_BUILD = "pendingBuild";
    private static final String KEY_ACCOUNT = "pendingAccount";
    private static final String KEY_MESSAGE = "pendingMessage";
    private static final String KEY_CHAT = "pendingChat";

    private static boolean checking;

    private static boolean pendingLoaded;
    private static int pendingBuild;
    private static int pendingAccount;
    private static TLRPC.Message pendingMessage;
    private static TLRPC.Chat pendingChat;
    private static TLRPC.TL_help_appUpdate pendingUpdate;
    private static MessageObject pendingParent;

    /** Whether this build takes part (a numbered plugins build). */
    public static boolean supported() {
        return RawVersion.plugins() && RawVersion.build() > 0;
    }

    /** At start (LaunchActivity): a quiet check, at most every {@link #AUTO_INTERVAL}; a newer build shows the sheet once. */
    public static void checkOnStart(Activity activity) {
        if (!supported() || !RawgramConfig.isUpdateCheck()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - prefs().getLong("lastCheck", 0) < AUTO_INTERVAL) {
            return;
        }
        // after the first frame and the accounts' connection, not on the start path
        AndroidUtilities.runOnUIThread(() -> check(activity, false), 5000);
    }

    /** «Проверить обновления»: always asks and reports the result. */
    public static void checkNow(Activity activity) {
        check(activity, true);
    }

    private static void check(Activity activity, boolean manual) {
        int account = UserConfig.selectedAccount;
        if (checking || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        checking = true;
        TLRPC.TL_contacts_resolveUsername resolve = new TLRPC.TL_contacts_resolveUsername();
        resolve.username = CHAT;
        ConnectionsManager.getInstance(account).sendRequest(resolve, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(res instanceof TLRPC.TL_contacts_resolvedPeer)) {
                done(manual, "Не удалось открыть чат обновлений");
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) res;
            MessagesController.getInstance(account).putUsers(resolved.users, false);
            MessagesController.getInstance(account).putChats(resolved.chats, false);
            TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
            req.peer = MessagesController.getInstance(account).getInputPeer(resolved.peer);
            req.msg_id = TOPIC;
            req.limit = LIMIT;
            ConnectionsManager.getInstance(account).sendRequest(req, (res2, err2) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(res2 instanceof TLRPC.messages_Messages)) {
                    done(manual, "Не удалось проверить обновления");
                    return;
                }
                checking = false;
                prefs().edit().putLong("lastCheck", System.currentTimeMillis()).apply();
                TLRPC.messages_Messages posts = (TLRPC.messages_Messages) res2;
                MessagesController.getInstance(account).putChats(posts.chats, false);
                onPosts(activity, account, posts, manual);
            }));
        }));
    }

    private static void done(boolean manual, String failure) {
        checking = false;
        if (manual) {
            RawNotify.warn(failure);
        }
    }

    private static void onPosts(Activity activity, int account, TLRPC.messages_Messages posts, boolean manual) {
        TLRPC.Message newest = null;
        int newestBuild = 0;
        for (TLRPC.Message message : posts.messages) {
            int build = buildOf(message.message);
            if (build > newestBuild) {
                newestBuild = build;
                newest = message;
            }
        }
        if (newest == null || newestBuild <= RawVersion.build()) {
            clearPending(true);
            if (manual) {
                RawNotify.show(R.drawable.msg_select, newest == null ? "Выпусков пока нет" : "У тебя последняя версия");
            }
            return;
        }
        TLRPC.Document apk = apkOf(newest);
        if (apk != null) {
            setPending(account, newestBuild, newest, chatOf(account, newest));
        } else {
            clearPending(true);
        }
        if (!manual) {
            // the sheet once per release, not on every start
            if (prefs().getInt("shownBuild", 0) >= newestBuild) {
                return;
            }
            prefs().edit().putInt("shownBuild", newestBuild).apply();
        }
        if (apk != null) {
            showSheet(activity);
        } else {
            showLink(activity, newestBuild, newest.message, newest.id);
        }
    }

    /** The build number of a release post, or 0 when the text is not one. */
    static int buildOf(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        Matcher m = HEADER.matcher(text);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The APK attached to a release post, or null. */
    private static TLRPC.Document apkOf(TLRPC.Message message) {
        if (!(message.media instanceof TLRPC.TL_messageMediaDocument)) {
            return null;
        }
        TLRPC.Document document = message.media.document;
        if (!(document instanceof TLRPC.TL_document)) {
            return null;
        }
        String name = FileLoader.getDocumentFileName(document);
        boolean apk = "application/vnd.android.package-archive".equals(document.mime_type)
                || name != null && name.toLowerCase().endsWith(".apk");
        return apk ? document : null;
    }

    private static TLRPC.Chat chatOf(int account, TLRPC.Message message) {
        if (message.peer_id == null || message.peer_id.channel_id == 0) {
            return null;
        }
        return MessagesController.getInstance(account).getChat(message.peer_id.channel_id);
    }

    /** The sheet of the pending update: Telegram's update sheet with the post's text. */
    public static void showSheet(Activity activity) {
        TLRPC.TL_help_appUpdate update = pendingUpdate();
        if (update == null || activity == null || activity.isFinishing()) {
            return;
        }
        try {
            new RawUpdateSheet(activity, update).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** A release post without the APK: its text and «Открыть» to open it in the chat. */
    private static void showLink(Activity activity, int build, String text, int msgId) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        String shown = stripTag(text);
        int lineEnd = shown.indexOf('\n');
        String title = lineEnd < 0 ? shown.trim() : shown.substring(0, lineEnd).trim();
        String changes = lineEnd < 0 ? "" : shown.substring(lineEnd + 1).trim();
        if (TextUtils.isEmpty(title)) {
            title = "rawGram build " + build;
        }
        String current = "Сейчас: rawGram " + RawVersion.line();
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(TextUtils.isEmpty(changes) ? current : changes + "\n\n" + current)
                .setPositiveButton("Открыть", (d, w) -> open(activity, msgId))
                .setNegativeButton("Позже", null)
                .show();
    }

    private static void open(Context context, int msgId) {
        Browser.openUrl(context, "https://t.me/" + CHAT + "/" + TOPIC + "/" + msgId);
    }

    private static String stripTag(String text) {
        return text == null ? "" : TAG_LINE.matcher(text).replaceAll("").trim();
    }

    /** Removes the release tag from the shown changelog (on its own line or at a line end), keeping the spans. */
    static void stripTag(android.text.SpannableStringBuilder text) {
        java.util.ArrayList<int[]> ranges = new java.util.ArrayList<>();
        Matcher m = TAG_LINE.matcher(text.toString());
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            // a tag on its own line takes its line break too
            if ((start == 0 || text.charAt(start - 1) == '\n') && end < text.length() && text.charAt(end) == '\n') {
                end++;
            }
            ranges.add(new int[]{start, end});
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            text.delete(ranges.get(i)[0], ranges.get(i)[1]);
        }
        while (text.length() > 0 && Character.isWhitespace(text.charAt(0))) {
            text.delete(0, 1);
        }
        while (text.length() > 0 && Character.isWhitespace(text.charAt(text.length() - 1))) {
            text.delete(text.length() - 1, text.length());
        }
    }

    // ---- pending update ----

    /** The release offered for download, or null; built from the post (version «build N», its text and APK). */
    public static TLRPC.TL_help_appUpdate pendingUpdate() {
        loadPending();
        if (pendingUpdate != null && (!supported() || RawVersion.build() >= pendingBuild)) {
            // this build reached it: nothing showed it in this process yet, so no notification
            clearPending(false);
        }
        return pendingUpdate;
    }

    /** The account that found the pending update (its access to the chat refreshes the file reference). */
    public static int pendingAccount() {
        loadPending();
        if (pendingUpdate != null && UserConfig.getInstance(pendingAccount).isClientActivated()) {
            return pendingAccount;
        }
        return UserConfig.selectedAccount;
    }

    /** The post of the pending update, the parent object of the download. */
    private static MessageObject parent() {
        if (pendingParent == null && pendingMessage != null) {
            int account = pendingAccount();
            MessagesController controller = MessagesController.getInstance(account);
            if (pendingChat != null && controller.getChat(pendingChat.id) == null) {
                controller.putChat(pendingChat, true);
            }
            pendingParent = new MessageObject(account, pendingMessage, false, false);
        }
        return pendingParent;
    }

    public static File pendingFile() {
        TLRPC.TL_help_appUpdate update = pendingUpdate();
        return update == null ? null : FileLoader.getInstance(pendingAccount()).getPathToAttach(update.document, true);
    }

    public static boolean isDownloaded() {
        File file = pendingFile();
        return file != null && file.exists();
    }

    public static boolean isDownloading() {
        TLRPC.TL_help_appUpdate update = pendingUpdate();
        return update != null && FileLoader.getInstance(pendingAccount()).isLoadingFile(FileLoader.getAttachFileName(update.document));
    }

    public static void download() {
        TLRPC.TL_help_appUpdate update = pendingUpdate();
        if (update == null) {
            return;
        }
        FileLoader.getInstance(pendingAccount()).loadFile(update.document, parent(), FileLoader.PRIORITY_NORMAL, 1);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
    }

    public static void cancelDownload() {
        TLRPC.TL_help_appUpdate update = pendingUpdate();
        if (update == null) {
            return;
        }
        FileLoader.getInstance(pendingAccount()).cancelLoadFile(update.document);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
    }

    /** Opens the downloaded APK in the system installer, as Telegram does. */
    public static void install(Activity activity) {
        TLRPC.TL_help_appUpdate update = pendingUpdate();
        if (update != null && activity != null) {
            AndroidUtilities.openForView(update.document, true, activity);
        }
    }

    private static void setPending(int account, int build, TLRPC.Message message, TLRPC.Chat chat) {
        loadPending();
        boolean same = pendingUpdate != null && pendingBuild == build && pendingMessage != null && pendingMessage.id == message.id;
        pendingBuild = build;
        pendingAccount = account;
        pendingMessage = message;
        if (chat != null || !same) {
            pendingChat = chat;
        }
        pendingUpdate = updateOf(build, message);
        pendingParent = null;
        SharedPreferences.Editor editor = prefs().edit()
                .putInt(KEY_BUILD, build)
                .putInt(KEY_ACCOUNT, account)
                .putString(KEY_MESSAGE, encode(message));
        if (pendingChat != null) {
            editor.putString(KEY_CHAT, encode(pendingChat));
        } else {
            editor.remove(KEY_CHAT);
        }
        editor.apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    private static void clearPending(boolean notify) {
        loadPending();
        if (pendingUpdate == null && !prefs().contains(KEY_MESSAGE)) {
            return;
        }
        pendingBuild = 0;
        pendingMessage = null;
        pendingChat = null;
        pendingUpdate = null;
        pendingParent = null;
        prefs().edit().remove(KEY_BUILD).remove(KEY_ACCOUNT).remove(KEY_MESSAGE).remove(KEY_CHAT).apply();
        if (notify) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
        }
    }

    private static void loadPending() {
        if (pendingLoaded) {
            return;
        }
        pendingLoaded = true;
        SharedPreferences prefs = prefs();
        int build = prefs.getInt(KEY_BUILD, 0);
        if (build <= 0) {
            return;
        }
        try {
            byte[] bytes = Base64.decode(prefs.getString(KEY_MESSAGE, ""), Base64.DEFAULT);
            if (bytes == null || bytes.length == 0) {
                return;
            }
            SerializedData data = new SerializedData(bytes);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            data.cleanup();
            if (message == null || apkOf(message) == null) {
                return;
            }
            String chat = prefs.getString(KEY_CHAT, null);
            if (chat != null) {
                SerializedData chatData = new SerializedData(Base64.decode(chat, Base64.DEFAULT));
                pendingChat = TLRPC.Chat.TLdeserialize(chatData, chatData.readInt32(false), false);
                chatData.cleanup();
            }
            pendingBuild = build;
            pendingAccount = prefs.getInt(KEY_ACCOUNT, UserConfig.selectedAccount);
            pendingMessage = message;
            pendingUpdate = updateOf(build, message);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static TLRPC.TL_help_appUpdate updateOf(int build, TLRPC.Message message) {
        TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
        update.id = build;
        update.version = "build " + build;
        update.text = message.message == null ? "" : message.message;
        if (message.entities != null) {
            update.entities.addAll(message.entities);
        }
        update.document = apkOf(message);
        update.flags |= 2;
        return update;
    }

    private static String encode(TLObject object) {
        SerializedData data = new SerializedData(object.getObjectSize());
        object.serializeToStream(data);
        String result = Base64.encodeToString(data.toByteArray(), Base64.DEFAULT);
        data.cleanup();
        return result;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram_updates", Context.MODE_PRIVATE);
    }
}
