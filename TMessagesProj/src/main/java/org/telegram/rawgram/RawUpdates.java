package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rawGram releases from the @rwgrm chat: posts in topic {@link #TOPIC} start with «rawGram &lt;build&gt; · Telegram
 * &lt;version&gt;» and list the changes on the next lines (with the APK or a link to it). The newest post with a build
 * number above {@link RawVersion#build()} is offered as an update; «Открыть» opens that post in the chat.
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
    /** «rawGram 127», «rawGram v127», «rawGram build 127» at the start of the post's first line. */
    private static final Pattern HEADER = Pattern.compile("^\\s*rawgram\\s*(?:v|build\\s*)?(\\d+)", Pattern.CASE_INSENSITIVE);

    private static boolean checking;

    /** Whether this build takes part (a numbered plugins build). */
    public static boolean supported() {
        return RawVersion.plugins() && RawVersion.build() > 0;
    }

    /** At start (LaunchActivity): a quiet check, at most every {@link #AUTO_INTERVAL}; a newer build shows a bulletin. */
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
                onPosts(activity, ((TLRPC.messages_Messages) res2), manual);
            }));
        }));
    }

    private static void done(boolean manual, String failure) {
        checking = false;
        if (manual) {
            RawNotify.warn(failure);
        }
    }

    private static void onPosts(Activity activity, TLRPC.messages_Messages posts, boolean manual) {
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
            if (manual) {
                RawNotify.show(R.drawable.msg_select, newest == null ? "Выпусков пока нет" : "У тебя последняя версия");
            }
            return;
        }
        int msgId = newest.id;
        String text = newest.message;
        if (!manual) {
            // one bulletin per release, not on every start
            if (prefs().getInt("shownBuild", 0) >= newestBuild) {
                return;
            }
            prefs().edit().putInt("shownBuild", newestBuild).apply();
            int build = newestBuild;
            RawNotify.show(R.drawable.msg_download, "Вышла сборка rawGram " + build, () -> showUpdate(activity, build, text, msgId));
            return;
        }
        showUpdate(activity, newestBuild, text, msgId);
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

    private static void showUpdate(Activity activity, int build, String text, int msgId) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        int lineEnd = text.indexOf('\n');
        String title = lineEnd < 0 ? text.trim() : text.substring(0, lineEnd).trim();
        String changes = lineEnd < 0 ? "" : text.substring(lineEnd + 1).trim();
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

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram_updates", Context.MODE_PRIVATE);
    }
}
