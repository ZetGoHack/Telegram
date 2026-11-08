package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

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
 * app flavour) download and install it. SharedConfig keeps it across restarts and asks {@link #isAvailable} instead of
 * comparing versionCodes (the same for all rawGram builds); the file reference is refreshed by re-reading the post
 * ({@link #refreshReference}). A post without the APK only offers to open it in the chat.
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
        TLRPC.TL_help_appUpdate update = apkOf(newest) != null ? updateOf(newestBuild, newest) : null;
        if (update != null) {
            SharedConfig.pendingAppUpdate = update;
            SharedConfig.saveConfig();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
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

    private static void clearPending() {
        if (SharedConfig.pendingAppUpdate == null) {
            return;
        }
        SharedConfig.pendingAppUpdate = null;
        SharedConfig.saveConfig();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    // ---- hooks ----

    /** SharedConfig.isAppUpdateAvailable: a release with the APK and a build above this one. */
    public static boolean isAvailable(TLRPC.TL_help_appUpdate update) {
        return update != null && update.document != null && update.id > RawVersion.build();
    }

    /** SharedConfig.loadConfig: drops the saved release once this build has reached it. */
    public static void onConfigLoaded() {
        if (supported() && SharedConfig.pendingAppUpdate != null && !isAvailable(SharedConfig.pendingAppUpdate)) {
            SharedConfig.pendingAppUpdate = null;
            AndroidUtilities.runOnUIThread(SharedConfig::saveConfig);
        }
    }

    /**
     * FileRefController, parent «update»: instead of help.getAppUpdate (which knows nothing of rawGram builds) re-reads
     * the release post and hands its fresh update object to {@code done}, the same path as Telegram's answer.
     * Returns false when Telegram's own request should run.
     */
    public static boolean refreshReference(int account, RequestDelegate done) {
        if (!supported()) {
            return false;
        }
        TLRPC.TL_help_appUpdate current = SharedConfig.pendingAppUpdate;
        fetchPosts(account, (res, err) -> {
            TLRPC.Message post = null;
            if (res instanceof TLRPC.messages_Messages) {
                ArrayList<TLRPC.Message> messages = ((TLRPC.messages_Messages) res).messages;
                if (current != null && current.document != null) {
                    for (TLRPC.Message message : messages) {
                        TLRPC.Document apk = apkOf(message);
                        if (apk != null && apk.id == current.document.id) {
                            post = message;
                            break;
                        }
                    }
                }
                if (post == null) {
                    post = newestRelease(messages, true);
                }
            }
            if (post != null && buildOf(post.message) > 0) {
                done.run(updateOf(buildOf(post.message), post), null);
            } else {
                TLRPC.TL_error error = err;
                if (error == null) {
                    error = new TLRPC.TL_error();
                    error.text = "RAWGRAM_RELEASE_NOT_FOUND";
                }
                done.run(null, error);
            }
        });
        return true;
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
