package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rawGram releases from the @rwgrm chat: posts in topic {@link #TOPIC} carry the tag «rwgrm&lt;build&gt;» (e.g. rwgrm127), «Telegram
 * &lt;version&gt;» and list the changes on the next lines (with the APK or a link to it). The newest post with a build
 * number above {@link RawVersion#build()} is offered as an update.
 * <p>
 * A post with the APK attached becomes Telegram's own pending update ({@link SharedConfig#pendingAppUpdate}, a
 * {@code help.appUpdate} built from the post), so Telegram's standalone update sheet and update bar (copied into the
 * app flavour) show it. SharedConfig keeps it across restarts and asks {@link #isAvailable} instead of comparing
 * versionCodes (the same for all rawGram builds). The APK is downloaded, found and installed through the post itself,
 * like a document in the chat ({@link #load}, {@link #file}, {@link #install}): a copy already downloaded in the chat
 * counts, and the file reference is refreshed the usual way for messages. The post and its chat are kept here for that.
 * A post without the APK only offers to open it in the chat.
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
    /** The release tag «rwgrm127» anywhere in the post: the build number right after «rwgrm» (the chat's rule). */
    private static final Pattern HEADER = Pattern.compile("(?<![\\w@/])rwgrm(\\d+)", Pattern.CASE_INSENSITIVE);
    /** The tag (with an optional «#») on its own line or at the end of a line, with the spaces and a separator before it. */
    private static final Pattern TAG_LINE = Pattern.compile("[ \\t]*(?:[·|—–-][ \\t]*)?#?(?<![\\w@/])rwgrm\\d+[ \\t]*(?=\\n|$)", Pattern.CASE_INSENSITIVE);
    /** Keys of the earlier own copy of the pending update and of the check interval. */
    private static final String[] OLD_KEYS = {"pendingBuild", "pendingAccount", "pendingMessage", "pendingChat", "lastCheck"};
    private static final String KEY_POST = "post";
    private static final String KEY_CHAT = "postChat";

    /** The release post of the pending update and its chat, kept for the download (the post is its parent). */
    private static TLRPC.Message post;
    private static TLRPC.Chat postChat;
    private static boolean postLoaded;
    private static MessageObject postObject;

    private static boolean checking;
    /** The check at start runs once per process: LaunchActivity can be recreated. */
    private static boolean checkedAtStart;

    /** Whether this build takes part (a numbered plugins build). */
    public static boolean supported() {
        return RawVersion.plugins() && RawVersion.build() > 0;
    }

    /** At start (LaunchActivity): a quiet check once per process; the sheet of a newer build shows once per build. */
    public static void checkOnStart(Activity activity) {
        if (!supported() || !RawgramConfig.isUpdateCheck() || checkedAtStart) {
            return;
        }
        checkedAtStart = true;
        dropOldKeys();
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
        fetchPosts(account, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            checking = false;
            if (!(res instanceof TLRPC.messages_Messages)) {
                if (manual) {
                    RawNotify.warn("Не удалось проверить обновления");
                }
                return;
            }
            TLRPC.messages_Messages posts = (TLRPC.messages_Messages) res;
            MessagesController.getInstance(account).putUsers(posts.users, false);
            MessagesController.getInstance(account).putChats(posts.chats, false);
            onPosts(activity, account, posts, manual);
        }));
    }

    /** Reads the recent posts of the releases topic; {@code done} runs on the network thread with messages_Messages or null. */
    private static void fetchPosts(int account, RequestDelegate done) {
        TLRPC.TL_contacts_resolveUsername resolve = new TLRPC.TL_contacts_resolveUsername();
        resolve.username = CHAT;
        ConnectionsManager.getInstance(account).sendRequest(resolve, (res, err) -> {
            TLRPC.InputPeer peer = res instanceof TLRPC.TL_contacts_resolvedPeer ? inputPeerOf((TLRPC.TL_contacts_resolvedPeer) res) : null;
            if (peer == null) {
                done.run(null, err);
                return;
            }
            TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
            req.peer = peer;
            req.msg_id = TOPIC;
            req.limit = LIMIT;
            ConnectionsManager.getInstance(account).sendRequest(req, done);
        });
    }

    private static TLRPC.InputPeer inputPeerOf(TLRPC.TL_contacts_resolvedPeer resolved) {
        if (resolved.peer == null || resolved.peer.channel_id == 0) {
            return null;
        }
        for (TLRPC.Chat chat : resolved.chats) {
            if (chat.id == resolved.peer.channel_id) {
                TLRPC.TL_inputPeerChannel peer = new TLRPC.TL_inputPeerChannel();
                peer.channel_id = chat.id;
                peer.access_hash = chat.access_hash;
                return peer;
            }
        }
        return null;
    }

    private static void onPosts(Activity activity, int account, TLRPC.messages_Messages posts, boolean manual) {
        TLRPC.Message newest = newestRelease(posts.messages, false);
        int newestBuild = newest == null ? 0 : buildOf(newest.message);
        if (newest == null || newestBuild <= RawVersion.build()) {
            clearPending();
            if (manual) {
                RawNotify.show(R.drawable.msg_select, newest == null ? "Выпусков пока нет" : "У тебя последняя версия");
            }
            return;
        }
        TLRPC.Document apk = apkOf(newest);
        TLRPC.TL_help_appUpdate update = null;
        if (apk != null) {
            TLRPC.TL_help_appUpdate current = SharedConfig.pendingAppUpdate;
            if (isSame(current, newestBuild, apk) && loadPost() != null) {
                // the same release: the update, its post and a download in progress stay untouched
                update = current;
            } else {
                update = updateOf(newestBuild, newest);
                setPending(account, update, newest);
            }
        } else {
            clearPending();
        }
        if (!manual) {
            // the sheet once per release, not on every start
            if (prefs().getInt("shownBuild", 0) >= newestBuild) {
                return;
            }
            prefs().edit().putInt("shownBuild", newestBuild).apply();
        }
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (update != null) {
            ApplicationLoader.applicationLoaderInstance.showUpdateAppPopup(activity, update, account);
        } else {
            showLink(activity, newestBuild, newest.message, newest.id);
        }
    }

    /** The post with the highest build tag (only those with the APK when {@code withApk}), or null. */
    private static TLRPC.Message newestRelease(ArrayList<TLRPC.Message> messages, boolean withApk) {
        TLRPC.Message newest = null;
        int newestBuild = 0;
        for (TLRPC.Message message : messages) {
            int build = buildOf(message.message);
            if (build > newestBuild && (!withApk || apkOf(message) != null)) {
                newestBuild = build;
                newest = message;
            }
        }
        return newest;
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

    /** Telegram's update object for a release post: id = the build, version «build N», the post's text and APK. */
    private static TLRPC.TL_help_appUpdate updateOf(int build, TLRPC.Message message) {
        TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
        update.id = build;
        update.version = "build " + build;
        update.text = message.message == null ? "" : message.message;
        if (message.entities != null) {
            update.entities.addAll(message.entities);
        }
        update.document = apkOf(message);
        update.flags |= 2; // document
        return update;
    }

    private static boolean isSame(TLRPC.TL_help_appUpdate update, int build, TLRPC.Document apk) {
        return update != null && update.id == build && update.document != null && update.document.id == apk.id;
    }

    private static void setPending(int account, TLRPC.TL_help_appUpdate update, TLRPC.Message message) {
        post = message;
        postChat = message.peer_id != null && message.peer_id.channel_id != 0 ? MessagesController.getInstance(account).getChat(message.peer_id.channel_id) : null;
        postLoaded = true;
        postObject = null;
        SharedPreferences.Editor editor = prefs().edit().putString(KEY_POST, encode(message));
        if (postChat != null) {
            editor.putString(KEY_CHAT, encode(postChat));
        } else {
            editor.remove(KEY_CHAT);
        }
        editor.apply();
        SharedConfig.pendingAppUpdate = update;
        SharedConfig.saveConfig();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    private static void clearPending() {
        dropPost();
        if (SharedConfig.pendingAppUpdate == null) {
            return;
        }
        SharedConfig.pendingAppUpdate = null;
        SharedConfig.saveConfig();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    private static void dropPost() {
        post = null;
        postChat = null;
        postObject = null;
        postLoaded = true;
        if (prefs().contains(KEY_POST)) {
            prefs().edit().remove(KEY_POST).remove(KEY_CHAT).apply();
        }
    }

    /** The stored release post, or null. */
    private static TLRPC.Message loadPost() {
        if (postLoaded) {
            return post;
        }
        postLoaded = true;
        try {
            SharedPreferences prefs = prefs();
            String postData = prefs.getString(KEY_POST, null);
            if (postData == null) {
                return null;
            }
            SerializedData data = new SerializedData(Base64.decode(postData, Base64.DEFAULT));
            post = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            data.cleanup();
            String chatData = prefs.getString(KEY_CHAT, null);
            if (chatData != null) {
                SerializedData chat = new SerializedData(Base64.decode(chatData, Base64.DEFAULT));
                postChat = TLRPC.Chat.TLdeserialize(chat, chat.readInt32(false), false);
                chat.cleanup();
            }
        } catch (Exception e) {
            FileLog.e(e);
            post = null;
        }
        return post;
    }

    /** The post as a MessageObject of {@code account}, with its chat known there (the file reference refresh needs it). */
    private static MessageObject postObject(int account, TLRPC.Document document) {
        TLRPC.Message message = loadPost();
        TLRPC.Document apk = message == null ? null : apkOf(message);
        if (apk == null || document == null || apk.id != document.id) {
            return null;
        }
        if (postObject == null || postObject.currentAccount != account) {
            MessagesController controller = MessagesController.getInstance(account);
            if (postChat != null && controller.getChat(postChat.id) == null) {
                controller.putChat(postChat, true);
            }
            postObject = new MessageObject(account, message, false, false);
        }
        return postObject;
    }

    private static String encode(TLObject object) {
        SerializedData data = new SerializedData(object.getObjectSize());
        object.serializeToStream(data);
        String result = Base64.encodeToString(data.toByteArray(), Base64.DEFAULT);
        data.cleanup();
        return result;
    }

    // ---- download, file and install for the stock update bar and sheet ----

    /**
     * The downloaded APK: the post's own file (downloaded in the chat: documents folder or cache), else the cache copy
     * of Telegram's update download. Never null, like getPathToAttach: check exists().
     */
    public static File file(int account, TLRPC.Document document) {
        FileLoader loader = FileLoader.getInstance(account);
        MessageObject parent = postObject(account, document);
        if (parent != null) {
            TLRPC.Message message = parent.messageOwner;
            if (!TextUtils.isEmpty(message.attachPath)) {
                File attach = new File(message.attachPath);
                if (attach.exists()) {
                    return attach;
                }
            }
            File chatFile = loader.getPathToMessage(message);
            if (chatFile != null && chatFile.exists()) {
                return chatFile;
            }
        }
        File plain = loader.getPathToAttach(document, false);
        if (plain != null && plain.exists()) {
            return plain;
        }
        return loader.getPathToAttach(document, true);
    }

    /** Starts the download the way the chat does for a document: the post as the parent, the documents folder. */
    public static void load(int account, TLRPC.Document document) {
        MessageObject parent = postObject(account, document);
        if (parent != null) {
            parent.putInDownloadsStore = true;
            FileLoader.getInstance(account).loadFile(document, parent, FileLoader.PRIORITY_NORMAL_UP, 0);
        } else {
            FileLoader.getInstance(account).loadFile(document, "update", FileLoader.PRIORITY_NORMAL, 1);
        }
    }

    public static void cancel(int account, TLRPC.Document document) {
        FileLoader.getInstance(account).cancelLoadFile(document);
    }

    /** Opens the downloaded APK in the system installer. */
    public static void install(int account, TLRPC.Document document, Activity activity) {
        File apk = file(account, document);
        if (apk.exists()) {
            AndroidUtilities.openForView(apk, FileLoader.getAttachFileName(document), document.mime_type, activity, null, false);
        }
    }

    // ---- hooks ----

    /** SharedConfig.isAppUpdateAvailable: a release with the APK and a build above this one. */
    public static boolean isAvailable(TLRPC.TL_help_appUpdate update) {
        return update != null && update.document != null && update.id > RawVersion.build();
    }

    /** SharedConfig.loadConfig: drops the saved release once this build has reached it. */
    public static void onConfigLoaded() {
        if (!supported() || SharedConfig.pendingAppUpdate == null) {
            return;
        }
        TLRPC.TL_help_appUpdate update = SharedConfig.pendingAppUpdate;
        TLRPC.Message message = loadPost();
        TLRPC.Document apk = message == null ? null : apkOf(message);
        if (!isAvailable(update) || apk == null || apk.id != update.document.id) {
            // reached by this build, or saved without its post
            SharedConfig.pendingAppUpdate = null;
            AndroidUtilities.runOnUIThread(() -> {
                dropPost();
                SharedConfig.saveConfig();
            });
        }
    }

    /** UpdateAppAlertDialog: the changelog with emoji and custom emoji, without the release tag. */
    public static CharSequence changelog(SpannableStringBuilder text, ArrayList<TLRPC.MessageEntity> entities, Paint paint) {
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        CharSequence withEmoji = Emoji.replaceEmoji(text, metrics, false);
        SpannableStringBuilder builder = withEmoji instanceof SpannableStringBuilder ? (SpannableStringBuilder) withEmoji : new SpannableStringBuilder(withEmoji);
        MessageObject.replaceAnimatedEmoji(builder, entities, metrics);
        stripTag(builder);
        return builder;
    }

    // ---- a post without the APK ----

    /** A release post without the APK: its text and «Открыть» to open it in the chat. */
    private static void showLink(Activity activity, int build, String text, int msgId) {
        String shown = text == null ? "" : TAG_LINE.matcher(text).replaceAll("").trim();
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

    /** Removes the release tag from the shown changelog (on its own line or at a line end), keeping the spans. */
    static void stripTag(SpannableStringBuilder text) {
        ArrayList<int[]> ranges = new ArrayList<>();
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

    private static void dropOldKeys() {
        SharedPreferences prefs = prefs();
        SharedPreferences.Editor editor = null;
        for (String key : OLD_KEYS) {
            if (prefs.contains(key)) {
                if (editor == null) {
                    editor = prefs.edit();
                }
                editor.remove(key);
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram_updates", Context.MODE_PRIVATE);
    }
}
