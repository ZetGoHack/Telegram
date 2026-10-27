package org.telegram.rawgram;

import android.app.Activity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_payments;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_wallet;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Confirmation before sensitive account / payment / chat-management requests that are sent by code from outside
 * Telegram (plugins, hooks of other modules). Ported from exteraGram's security/SensitiveRequestConfirmation: the same
 * list of requests, but only the Java-level gate (exteraGram also holds requests in libtmessages, not ported).
 * <p>
 * ConnectionsManager.sendRequest calls {@link #hold} for every request; with the switch off that is one cached flag
 * read. Requests sent by Telegram's own screens are never stopped: the call stack of the sender is checked and only
 * frames of plugin / hooking frameworks make a request «foreign». Declined requests complete with an error.
 */
public class RawSensitive {

    private static final String[] FOREIGN_PREFIXES = {
            "com.chaquo.", "com.exteragram.", "dev.exterahook.", "de.robv.xposed.", "org.lsposed.", "io.github.libxposed.",
            "io.github.lsposed.", "me.weishu.", "top.canyie.", "com.swift.sandhook.", "com.elderdrivers.", "lab.galaxy.yahfa.",
            "LSPHooker_", "com.lody.virtual.", "org.luckypray."
    };

    private static HashMap<Class<?>, String> labels;

    private static final class Held {
        final TLObject object;
        final RequestDelegate onComplete;
        final RequestDelegateTimestamp onCompleteTimestamp;
        final Runnable send;

        Held(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, Runnable send) {
            this.object = object;
            this.onComplete = onComplete;
            this.onCompleteTimestamp = onCompleteTimestamp;
            this.send = send;
        }
    }

    private static final class Batch {
        final String category;
        final String name;
        final ArrayList<Held> items = new ArrayList<>();

        Batch(String category, String name) {
            this.category = category;
            this.name = name;
        }
    }

    // UI thread only
    private static final ArrayList<Batch> queue = new ArrayList<>();
    private static boolean showing;

    /**
     * @param send posts the request to the network (the original body of sendRequest)
     * @return true when the request was taken over (it is sent or failed later)
     */
    public static boolean hold(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, Runnable send) {
        if (!RawChatUiConfig.confirmSensitive.get()) {
            return false;
        }
        String category = category(object);
        if (category == null || !foreignCaller(new Throwable().getStackTrace())) {
            return false;
        }
        String name = readableName(object);
        Held held = new Held(object, onComplete, onCompleteTimestamp, send);
        AndroidUtilities.runOnUIThread(() -> enqueue(category, name, held));
        return true;
    }

    private static synchronized String category(TLObject object) {
        if (labels == null) {
            labels = buildLabels();
        }
        String category = labels.get(object.getClass());
        if (category != null && object instanceof TLRPC.TL_messages_deleteHistory && !((TLRPC.TL_messages_deleteHistory) object).revoke) {
            return null;
        }
        return category;
    }

    private static boolean foreignCaller(StackTraceElement[] stack) {
        for (StackTraceElement element : stack) {
            String cls = element.getClassName();
            for (String prefix : FOREIGN_PREFIXES) {
                if (cls.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String readableName(TLObject object) {
        String name = object.getClass().getName();
        name = name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
        if (name.startsWith("TL_")) {
            name = name.substring(3);
        }
        int us = name.indexOf('_');
        if (us > 0 && name.indexOf('.') < 0) {
            name = name.substring(0, us) + "." + name.substring(us + 1);
        }
        return name;
    }

    private static void enqueue(String category, String name, Held held) {
        for (Batch batch : queue) {
            if (batch.name.equals(name)) {
                batch.items.add(held);
                return;
            }
        }
        Batch batch = new Batch(category, name);
        batch.items.add(held);
        queue.add(batch);
        showNext();
    }

    private static void showNext() {
        if (showing || queue.isEmpty()) {
            return;
        }
        Batch batch = queue.get(0);
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null || activity.isFinishing()) {
            finish(queue.remove(0), false);
            showNext();
            return;
        }
        showing = true;
        boolean[] answered = new boolean[1];
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle("Подтверждение запроса");
        int count = batch.items.size();
        builder.setMessage("Код вне Telegram (расширение или хук) отправляет запрос.\n\n" + batch.category + "\n" + batch.name
                + (count > 1 ? " ×" + count : "") + "\n\nРазрешить?");
        builder.setPositiveButton("Разрешить", (d, w) -> {
            answered[0] = true;
            done(batch, true);
        });
        builder.setNegativeButton("Отклонить", (d, w) -> {
            answered[0] = true;
            done(batch, false);
        });
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            if (!answered[0]) {
                done(batch, false);
            }
        });
        try {
            dialog.show();
        } catch (Throwable t) {
            FileLog.e(t);
            answered[0] = true;
            done(batch, false);
        }
    }

    private static void done(Batch batch, boolean allow) {
        if (!showing || queue.isEmpty() || queue.get(0) != batch) {
            return;
        }
        showing = false;
        queue.remove(0);
        finish(batch, allow);
        showNext();
    }

    private static void finish(Batch batch, boolean allow) {
        for (Held held : batch.items) {
            if (allow) {
                Utilities.stageQueue.postRunnable(held.send);
            } else {
                TLRPC.TL_error error = new TLRPC.TL_error();
                error.code = 403;
                error.text = "RAWGRAM_REQUEST_DECLINED";
                Utilities.stageQueue.postRunnable(() -> {
                    if (held.onComplete != null) {
                        held.onComplete.run(null, error);
                    } else if (held.onCompleteTimestamp != null) {
                        held.onCompleteTimestamp.run(null, error, System.currentTimeMillis());
                    }
                });
            }
        }
        batch.items.clear();
    }

    private static HashMap<Class<?>, String> buildLabels() {
        HashMap<Class<?>, String> map = new HashMap<>();
        String gifts = "Подарки";
        map.put(TL_stars.transferStarGift.class, gifts);
        map.put(TL_stars.updateStarGiftPrice.class, gifts);
        map.put(TL_stars.convertStarGift.class, gifts);
        map.put(TL_stars.upgradeStarGift.class, gifts);
        map.put(TL_stars.getStarGiftWithdrawalUrl.class, gifts);
        map.put(TL_stars.withdrawStarGift.class, gifts);
        map.put(TL_stars.deleteStarGift.class, gifts);
        map.put(TL_stars.craftStarGift.class, gifts);
        map.put(TL_payments.TL_sendStarGiftOffer.class, gifts);

        String payments = "Платежи";
        map.put(TL_stars.TL_payments_sendStarsForm.class, payments);
        map.put(TLRPC.TL_payments_sendPaymentForm.class, payments);
        map.put(TLRPC.TL_messages_sendPaidReaction.class, payments);
        map.put(TL_stars.TL_fulfillStarsSubscription.class, payments);
        map.put(TL_stars.TL_changeStarsSubscription.class, payments);
        map.put(TLRPC.TL_channels_searchPosts.class, payments);
        map.put(TLRPC.TL_payments_getStarsRevenueWithdrawalUrl.class, payments);

        String security = "Безопасность аккаунта";
        map.put(TLRPC.TL_auth_acceptLoginToken.class, security);
        map.put(TLRPC.TL_auth_exportAuthorization.class, security);
        map.put(TLRPC.TL_auth_resetAuthorizations.class, security);
        map.put(TL_account.resetAuthorization.class, security);
        map.put(TL_account.resetWebAuthorization.class, security);
        map.put(TL_account.resetWebAuthorizations.class, security);
        map.put(TL_account.deleteAccount.class, security);
        map.put(TL_account.updatePasswordSettings.class, security);
        map.put(TL_account.resetPassword.class, security);
        map.put(TL_account.changePhone.class, security);
        map.put(TL_account.updateConnectedBot.class, security);
        map.put(TL_account.acceptAuthorization.class, security);
        map.put(TL_account.updateUsername.class, security);
        map.put(TL_account.toggleUsername.class, security);
        map.put(TL_account.setAuthorizationTTL.class, security);
        map.put(TL_account.changeAuthorizationSettings.class, security);

        String chat = "Управление чатом и каналом";
        map.put(TLRPC.TL_channels_editCreator.class, chat);
        map.put(TLRPC.TL_channels_editAdmin.class, chat);
        map.put(TLRPC.TL_channels_deleteChannel.class, chat);
        map.put(TLRPC.TL_channels_updateUsername.class, chat);
        map.put(TLRPC.TL_channels_toggleUsername.class, chat);

        String wallet = "Кошелёк";
        map.put(TL_wallet.sendTransfer.class, wallet);

        String messages = "Отправка сообщений";
        map.put(TLRPC.TL_messages_sendMessage.class, messages);
        map.put(TLRPC.TL_messages_sendMedia.class, messages);
        map.put(TLRPC.TL_messages_forwardMessages.class, messages);
        map.put(TLRPC.TL_messages_sendMultiMedia.class, messages);
        map.put(TLRPC.TL_messages_sendInlineBotResult.class, messages);
        map.put(TL_phone.sendGroupCallMessage.class, messages);

        String data = "Данные аккаунта";
        map.put(TLRPC.TL_messages_deleteHistory.class, data); // only with revoke (checked in category())
        map.put(TLRPC.TL_contacts_resetSaved.class, data);
        return map;
    }
}
