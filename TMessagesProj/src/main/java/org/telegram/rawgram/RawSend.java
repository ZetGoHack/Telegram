package org.telegram.rawgram;

import android.content.Context;
import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

/** Sending inline results: "where to?" chooser, chat picker and raw messages.sendInlineBotResult. */
public class RawSend {

    /**
     * Asks where to send: this chat (the chat's normal path, when available) or any other chat.
     * @param sendHere null when the current chat can't send (e.g. opened outside a chat)
     */
    public static void ask(Context context, int currentAccount, TLRPC.BotInlineResult result, Utilities.Callback<TLRPC.BotInlineResult> sendHere,
                           Theme.ResourcesProvider resourcesProvider, Runnable beforeSend) {
        CharSequence[] items = sendHere != null
                ? new CharSequence[]{"Сюда, в этот чат", "В другой чат…"}
                : new CharSequence[]{"В другой чат…"};
        new AlertDialog.Builder(context, resourcesProvider)
                .setTitle("Куда отправить?")
                .setItems(items, (d, which) -> {
                    if (beforeSend != null) {
                        beforeSend.run();
                    }
                    if (sendHere != null && which == 0) {
                        sendHere.run(result);
                    } else {
                        toOtherChat(currentAccount, result);
                    }
                })
                .show();
    }

    /** Picks any chat and sends the result there with a raw messages.sendInlineBotResult. */
    public static void toOtherChat(int currentAccount, TLRPC.BotInlineResult result) {
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null) {
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            fragment.finishFragment();
            for (MessagesStorage.TopicKey key : dids) {
                raw(currentAccount, result, key.dialogId, notify);
            }
            return true;
        });
        last.presentFragment(picker);
    }

    public static void raw(int currentAccount, TLRPC.BotInlineResult result, long dialogId, boolean notify) {
        TLRPC.TL_messages_sendInlineBotResult req = new TLRPC.TL_messages_sendInlineBotResult();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.random_id = Utilities.random.nextLong();
        req.query_id = result.query_id;
        req.id = result.id;
        req.silent = !notify;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.Updates) {
                MessagesController.getInstance(currentAccount).processUpdates((TLRPC.Updates) res, false);
                RawNotify.show(R.drawable.msg_info, "Отправлено: " + (result.title != null ? result.title : result.id));
            } else {
                String text = error != null ? error.text : "unexpected " + TLDumper.typeName(res);
                RawNotify.show(R.drawable.msg_warning, "Не отправлено: " + text
                        + (text.contains("QUERY_ID_INVALID") || text.contains("RESULT_ID_INVALID") ? " — выдача устарела" : ""));
            }
        }));
    }
}
