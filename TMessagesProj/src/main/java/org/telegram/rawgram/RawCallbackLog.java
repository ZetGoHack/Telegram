package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.utils.tlutils.TLKeyboardHelper;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_ephemeral;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * Recent answers to callback (and game) buttons: SendMessagesHelper.sendCallback wraps its request delegate
 * here, so every messages.getBotCallbackAnswer is recorded with its timing, answer or error.
 */
public class RawCallbackLog {

    /** A small notice when the answer changes nothing on screen (no text, no url) or fails silently. */
    public static boolean showSilentAnswers = true;

    private static final int MAX = 64;
    private static final ArrayList<Entry> entries = new ArrayList<>();

    public static class Entry {
        public int account;
        public long dialogId;
        public int msgId;
        public boolean ephemeral;
        public boolean game;
        public boolean requiresPassword;
        public boolean fromCache;
        public TL_keyboard.KeyboardButtonProto button;
        public byte[] data;
        /** System.currentTimeMillis() when the answer came. */
        public long time;
        public long duration;
        public TLObject response;
        public TLRPC.TL_error error;

        public TLRPC.TL_messages_botCallbackAnswer answer() {
            return response instanceof TLRPC.TL_messages_botCallbackAnswer ? (TLRPC.TL_messages_botCallbackAnswer) response : null;
        }

        /** The answer showed nothing to the user: no text, no url. */
        public boolean isSilent() {
            TLRPC.TL_messages_botCallbackAnswer answer = answer();
            return answer != null && TextUtils.isEmpty(answer.message) && TextUtils.isEmpty(answer.url);
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (error != null) {
                sb.append("ошибка ").append(error.code).append(' ').append(error.text);
            } else {
                TLRPC.TL_messages_botCallbackAnswer answer = answer();
                if (answer == null) {
                    sb.append(TLDumper.typeName(response));
                } else if (!TextUtils.isEmpty(answer.message)) {
                    sb.append(answer.alert ? "alert: «" : "toast: «").append(answer.message).append('»');
                } else if (!TextUtils.isEmpty(answer.url)) {
                    sb.append("url: ").append(answer.url);
                } else {
                    sb.append("пустой ответ");
                }
                if (answer != null && answer.cache_time != 0) {
                    sb.append(" · cache_time ").append(answer.cache_time);
                }
            }
            sb.append(" · ").append(fromCache ? "из кэша" : duration + " мс");
            return sb.toString();
        }

        /** Request (rebuilt, the SRP password is not kept) and answer, for RawObjectSheet. */
        public Object raw() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("request", buildRequest());
            if (error != null) {
                map.put("error", error);
            } else {
                map.put("response", response);
            }
            map.put("duration_ms", duration);
            map.put("from_cache", fromCache);
            map.put("time", time);
            return map;
        }

        private TLObject buildRequest() {
            TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(dialogId);
            if (ephemeral) {
                TL_ephemeral.TL_getCallbackAnswer req = new TL_ephemeral.TL_getCallbackAnswer();
                req.peer = peer;
                req.id = msgId;
                req.data = data;
                return req;
            }
            TLRPC.TL_messages_getBotCallbackAnswer req = new TLRPC.TL_messages_getBotCallbackAnswer();
            req.peer = peer;
            req.msg_id = msgId;
            req.game = game;
            if (data != null) {
                req.flags |= 1;
                req.data = data;
            }
            if (requiresPassword) {
                // the real request carried an InputCheckPasswordSRP; it's not kept
                req.flags |= 4;
                req.password = new TLRPC.TL_inputCheckPasswordEmpty();
            }
            return req;
        }

        boolean matches(int account, long dialogId, int msgId, byte[] data) {
            return this.account == account && this.dialogId == dialogId && this.msgId == msgId && Arrays.equals(this.data, data);
        }
    }

    /**
     * Wraps sendCallback's delegate: runs it unchanged, then records the answer on the UI thread
     * (after Telegram's own handling, which also posts there). Other button types pass through.
     */
    public static RequestDelegate wrap(int account, MessageObject messageObject, TL_keyboard.KeyboardButtonProto button, boolean fromCache, RequestDelegate delegate) {
        if (messageObject == null || button == null || delegate == null || !RawgramConfig.isBotAnswerLog()) {
            return delegate;
        }
        TL_keyboard.TL_inlineButtonTypeCallback callback = TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeCallback.class);
        boolean game = TLKeyboardHelper.isType(button, TL_keyboard.TL_inlineButtonTypeGame.class);
        if (callback == null && !game) {
            return delegate;
        }
        final long start = SystemClock.elapsedRealtime();
        final long dialogId = messageObject.getDialogId();
        final boolean ephemeral = messageObject.isEphemeral();
        final int msgId = ephemeral ? messageObject.getEphemeralId() : messageObject.getId();
        return (response, error) -> {
            long duration = SystemClock.elapsedRealtime() - start;
            delegate.run(response, error);
            if (fromCache && response == null) {
                // cache miss: sendCallback asks the bot next, with its own wrapped delegate
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                Entry entry = new Entry();
                entry.account = account;
                entry.dialogId = dialogId;
                entry.msgId = msgId;
                entry.ephemeral = ephemeral;
                entry.game = game;
                entry.requiresPassword = callback != null && callback.requires_password;
                entry.fromCache = fromCache;
                entry.button = button;
                entry.data = button.getData();
                entry.time = System.currentTimeMillis();
                entry.duration = duration;
                entry.response = response;
                entry.error = error;
                add(entry);
                maybeNotify(entry);
            });
        };
    }

    private static void add(Entry entry) {
        synchronized (entries) {
            entries.add(entry);
            while (entries.size() > MAX) {
                entries.remove(0);
            }
        }
    }

    /** The latest answer for this button of this message, or null. */
    public static Entry last(int account, MessageObject messageObject, TL_keyboard.KeyboardButtonProto button) {
        if (messageObject == null || button == null) {
            return null;
        }
        long dialogId = messageObject.getDialogId();
        int msgId = messageObject.isEphemeral() ? messageObject.getEphemeralId() : messageObject.getId();
        byte[] data = button.getData();
        synchronized (entries) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry entry = entries.get(i);
                if (entry.matches(account, dialogId, msgId, data)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /** All recorded answers, oldest first. */
    public static ArrayList<Entry> all() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    // errors Telegram answers with its own dialogs (2FA for ownership transfer)
    private static boolean isHandledError(TLRPC.TL_error error) {
        String text = error.text;
        return text != null && (text.startsWith("PASSWORD_") || text.startsWith("SRP_") || text.startsWith("SESSION_TOO_FRESH_"));
    }

    private static void maybeNotify(Entry entry) {
        if (!showSilentAnswers || !RawgramConfig.isBotAnswerLog()) {
            return;
        }
        boolean silentError = entry.error != null && !isHandledError(entry.error);
        if (!silentError && !entry.isSilent()) {
            return;
        }
        // only in the chat the button was pressed in
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (!(fragment instanceof ChatActivity) || ((ChatActivity) fragment).getDialogId() != entry.dialogId
                || fragment.getCurrentAccount() != entry.account || fragment.getParentActivity() == null) {
            return;
        }
        String text = silentError
                ? "callback: ошибка " + entry.error.text + " · " + entry.duration + " мс"
                : "callback: пустой ответ " + (entry.fromCache ? "из кэша" : "за " + entry.duration + " мс");
        BulletinFactory.of(fragment).createSimpleBulletin(silentError ? R.raw.error : R.raw.info, text, "Raw", () -> showRaw(fragment, entry)).show();
    }

    public static void showRaw(BaseFragment fragment, Entry entry) {
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || entry == null) {
            return;
        }
        fragment.showDialog(createRawSheet(activity, entry, fragment.getResourceProvider()));
    }

    public static RawObjectSheet createRawSheet(Context context, Entry entry, Theme.ResourcesProvider resourcesProvider) {
        RawObjectSheet sheet = new RawObjectSheet(context, entry.account, "Callback-ответ", entry.raw(), resourcesProvider);
        sheet.setSubtitle(entry.summary());
        return sheet;
    }
}
