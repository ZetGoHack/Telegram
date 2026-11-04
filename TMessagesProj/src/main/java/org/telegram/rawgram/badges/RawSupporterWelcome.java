package org.telegram.rawgram.badges;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.rawgram.RawgramConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Shows the one-time «Спасибо за поддержку!» sheet when one of your accounts becomes a supporter. Whether it was
 * shown is kept per account locally and in the cloud storage of exteraGram's API bot (key {@code supporter_welcome}),
 * so it is not shown again after it was seen in exteraGram or on another device. Retries every few seconds while
 * the app is busy (passcode, another sheet open, another account selected).
 * <p>
 * Ported from exteraGram's {@code SupporterWelcomeController} and {@code BotCloudStorage} (exteraGram, GPLv3).
 */
public final class RawSupporterWelcome implements NotificationCenter.NotificationCenterDelegate {

    private static final String PREFS = "rawgram_badges";
    private static final String KEY_SHOWN = "supporterWelcomeShown";
    private static final String KEY_CLOUD_PENDING = "supporterWelcomeCloudPending";
    private static final String CLOUD_KEY = "supporter_welcome";
    private static final long API_BOT_ID = 8083294286L;
    private static final String API_BOT_USERNAME = "exteraAuthBot";
    private static final long RETRY_DELAY = 5000;

    private static final RawSupporterWelcome INSTANCE = new RawSupporterWelcome();
    private static final HashSet<Long> requested = new HashSet<>();
    private static final HashSet<Long> ready = new HashSet<>();
    private static boolean started;
    private static boolean retryScheduled;
    private static final Runnable retry = () -> {
        retryScheduled = false;
        tryShow();
    };

    private RawSupporterWelcome() {
    }

    /** After the badge list changes: asks the cloud about every supporter account that has not seen the welcome. */
    public static void check() {
        if (!RawgramConfig.isExteraBadges()) {
            return;
        }
        start();
        Set<Long> shown = readSet(KEY_SHOWN);
        Set<Long> cloudPending = readSet(KEY_CLOUD_PENDING);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated()) {
                continue;
            }
            final long userId = config.clientUserId;
            if (!RawSupporters.isSupporter(userId)) {
                continue;
            }
            if (cloudPending.contains(userId)) {
                writeCloud(a, userId);
            }
            if (shown.contains(userId) || requested.contains(userId) || ready.contains(userId)) {
                continue;
            }
            requested.add(userId);
            cloudGet(a, values -> {
                requested.remove(userId);
                if (values == null) {
                    return;
                }
                if (values.has(CLOUD_KEY) && values.optString(CLOUD_KEY).length() > 0) {
                    addToSet(KEY_SHOWN, userId);
                } else if (RawSupporters.isSupporter(userId)) {
                    ready.add(userId);
                    tryShow();
                }
            });
        }
    }

    private static void tryShow() {
        if (ready.isEmpty()) {
            return;
        }
        int account = UserConfig.selectedAccount;
        long userId = UserConfig.getInstance(account).clientUserId;
        if (!ready.contains(userId)) {
            return;
        }
        if (!RawSupporters.isSupporter(userId)) {
            ready.remove(userId);
            return;
        }
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (ApplicationLoader.mainInterfacePaused || SharedConfig.isWaitingForPasscodeEnter || fragment == null
                || fragment.getParentActivity() == null || fragment.getCurrentAccount() != account || fragment.visibleDialog != null
                || fragment.hasShownSheet() || PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
            scheduleRetry();
            return;
        }
        ready.remove(userId);
        if (RawSupporterWelcomeSheet.show(fragment, RawSupporterWelcomeSheet.MODE_WELCOME) != null) {
            markShown(account, userId);
        }
    }

    private static void start() {
        if (started) {
            return;
        }
        started = true;
        NotificationCenter.getGlobalInstance().addObserver(INSTANCE, NotificationCenter.activeAccountChanged);
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null) {
            detector.addListener(new ForegroundDetector.Listener() {
                @Override
                public void onBecameForeground() {
                    scheduleRetry();
                }

                @Override
                public void onBecameBackground() {
                }
            });
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            scheduleRetry();
        }
    }

    private static void scheduleRetry() {
        if (retryScheduled || ready.isEmpty()) {
            return;
        }
        retryScheduled = true;
        AndroidUtilities.runOnUIThread(retry, RETRY_DELAY);
    }

    private static void markShown(int account, long userId) {
        addToSet(KEY_SHOWN, userId);
        addToSet(KEY_CLOUD_PENDING, userId);
        writeCloud(account, userId);
    }

    private static void writeCloud(int account, long userId) {
        int number = RawSupporters.getNumber(userId);
        cloudSet(account, CLOUD_KEY, number > 0 ? String.valueOf(number) : "1", ok -> {
            if (ok) {
                removeFromSet(KEY_CLOUD_PENDING, userId);
            }
        });
    }

    // ---------------------------------------------------------------- bot cloud storage

    private static void cloudGet(int account, Utilities.Callback<JSONObject> callback) {
        try {
            invoke(account, "getStorageValues", new JSONObject().put("keys", new JSONArray().put(CLOUD_KEY)), data -> {
                if (data == null) {
                    callback.run(null);
                    return;
                }
                try {
                    callback.run(new JSONObject(data));
                } catch (Exception e) {
                    FileLog.e(e);
                    callback.run(null);
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
            callback.run(null);
        }
    }

    private static void cloudSet(int account, String key, String value, Utilities.Callback<Boolean> callback) {
        try {
            invoke(account, "saveStorageValue", new JSONObject().put("key", key).put("value", value), data -> callback.run(data != null));
        } catch (Exception e) {
            FileLog.e(e);
            callback.run(false);
        }
    }

    /** {@code bots.invokeWebViewCustomMethod} on the API bot; {@code callback} gets the JSON answer or null (UI thread). */
    private static void invoke(int account, String method, JSONObject params, Utilities.Callback<String> callback) {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.User bot = controller.getUser(API_BOT_ID);
        if (bot != null) {
            send(account, bot, method, params, callback);
            return;
        }
        controller.getUserNameResolver().resolve(API_BOT_USERNAME, peerId -> {
            TLRPC.User resolved = peerId != null && peerId == API_BOT_ID ? controller.getUser(API_BOT_ID) : null;
            if (resolved == null) {
                callback.run(null);
            } else {
                send(account, resolved, method, params, callback);
            }
        });
    }

    private static void send(int account, TLRPC.User bot, String method, JSONObject params, Utilities.Callback<String> callback) {
        TL_bots.invokeWebViewCustomMethod req = new TL_bots.invokeWebViewCustomMethod();
        req.bot = MessagesController.getInstance(account).getInputUser(bot);
        req.custom_method = method;
        TLRPC.TL_dataJSON json = new TLRPC.TL_dataJSON();
        json.data = params.toString();
        req.params = json;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_dataJSON) {
                callback.run(((TLRPC.TL_dataJSON) response).data);
                return;
            }
            if (error != null) {
                FileLog.e("rawSupporterWelcome " + method + ": " + error.code + " " + error.text);
            }
            callback.run(null);
        }));
    }

    // ---------------------------------------------------------------- local sets

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<Long> readSet(String key) {
        HashSet<Long> result = new HashSet<>();
        Set<String> stored = prefs().getStringSet(key, null);
        if (stored != null) {
            for (Iterator<String> it = stored.iterator(); it.hasNext(); ) {
                try {
                    result.add(Long.parseLong(it.next()));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return result;
    }

    private static void addToSet(String key, long userId) {
        Set<String> stored = prefs().getStringSet(key, null);
        HashSet<String> set = stored == null ? new HashSet<>() : new HashSet<>(stored);
        if (set.add(String.valueOf(userId))) {
            prefs().edit().putStringSet(key, set).apply();
        }
    }

    private static void removeFromSet(String key, long userId) {
        Set<String> stored = prefs().getStringSet(key, null);
        HashSet<String> set = stored == null ? new HashSet<>() : new HashSet<>(stored);
        if (set.remove(String.valueOf(userId))) {
            prefs().edit().putStringSet(key, set).apply();
        }
    }
}
