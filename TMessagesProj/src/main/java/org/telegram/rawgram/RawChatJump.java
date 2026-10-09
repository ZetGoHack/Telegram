package org.telegram.rawgram;

import android.app.DatePickerDialog;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Chat ⋮ menu items from Nagram: «Перейти к сообщению» (ChatMenuItemGoToMessage: by ID, or by date) and
 * «Удалить свои сообщения» (ChatMenuItemDeleteOwnMessages: every own message of the group, found by search).
 */
final class RawChatJump {

    private RawChatJump() {
    }

    static void goToMessage(ChatActivity chat) {
        Context context = chat.getParentActivity();
        if (context == null) {
            return;
        }
        Theme.ResourcesProvider rp = chat.getResourceProvider();
        EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, rp));
        edit.setHintText("ID сообщения");
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        edit.setBackground(null);
        edit.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, rp),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, rp),
                Theme.getColor(Theme.key_text_RedRegular, rp));
        edit.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        edit.setCursorSize(AndroidUtilities.dp(20));
        edit.setCursorWidth(1.5f);
        FrameLayout box = new FrameLayout(context);
        box.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP, 24, 6, 24, 0));

        AlertDialog dialog = new AlertDialog.Builder(context, rp)
                .setTitle("Перейти к сообщению")
                .setView(box)
                .setPositiveButton("Перейти", (d, w) -> {
                    try {
                        int id = Integer.parseInt(edit.getText().toString().trim());
                        if (id > 0) {
                            chat.scrollToMessageId(id, 0, true, 0, true, 0);
                        }
                    } catch (NumberFormatException ignore) {
                    }
                })
                .setNeutralButton("По дате", (d, w) -> pickDate(chat))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.setOnShowListener(d -> {
            edit.requestFocus();
            AndroidUtilities.showKeyboard(edit);
        });
        chat.showDialog(dialog);
    }

    private static void pickDate(ChatActivity chat) {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(chat.getParentActivity(), (view, year, month, day) -> {
            Calendar c = Calendar.getInstance();
            c.set(year, month, day, 0, 0, 0);
            chat.jumpToDate((int) (c.getTimeInMillis() / 1000));
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    static boolean canDeleteOwn(ChatActivity chat) {
        TLRPC.Chat current = chat.getCurrentChat();
        return RawChatUiConfig.menuDeleteOwn.get() && current != null && chat.getChatMode() == 0
                && (current.megagroup || !org.telegram.messenger.ChatObject.isChannel(current));
    }

    static void deleteOwn(ChatActivity chat) {
        Context context = chat.getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(context, chat.getResourceProvider())
                .setTitle("Удалить свои сообщения")
                .setMessage("Все твои сообщения в этом чате будут удалены у всех участников.")
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> search(chat, new ArrayList<>(), 0))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        chat.showDialog(dialog);
        android.widget.TextView button = (android.widget.TextView) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold, chat.getResourceProvider()));
        }
    }

    /** messages.search from self, page by page, then one bulletin with undo before the deletion. */
    private static void search(ChatActivity chat, ArrayList<Integer> ids, int offsetId) {
        int account = chat.getCurrentAccount();
        long dialogId = chat.getDialogId();
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        req.from_id = MessagesController.getInputPeer(UserConfig.getInstance(account).getCurrentUser());
        req.flags |= 1;
        req.q = "";
        req.limit = 100;
        req.offset_id = offsetId;
        req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Messages) || response instanceof TLRPC.TL_messages_messagesNotModified) {
                if (error != null) {
                    RawNotify.show(R.drawable.msg_info, "Не удалось найти сообщения: " + error.text);
                    return;
                }
                finish(chat, ids);
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            int next = offsetId;
            for (TLRPC.Message m : res.messages) {
                next = next == 0 ? m.id : Math.min(next, m.id);
                if (m.out && !m.post && !(m.action instanceof TLRPC.TL_messageActionChatCreate)) {
                    ids.add(m.id);
                }
            }
            if (res.messages.size() < req.limit || next == offsetId) {
                finish(chat, ids);
            } else {
                search(chat, ids, next);
            }
        }));
    }

    private static void finish(ChatActivity chat, ArrayList<Integer> ids) {
        int account = chat.getCurrentAccount();
        long dialogId = chat.getDialogId();
        if (ids.isEmpty()) {
            RawNotify.show(R.drawable.msg_info, "Своих сообщений здесь нет");
            return;
        }
        Runnable delete = () -> {
            for (int i = 0; i < ids.size(); i += 100) {
                ArrayList<Integer> part = new ArrayList<>(ids.subList(i, Math.min(ids.size(), i + 100)));
                MessagesController.getInstance(account).deleteMessages(part, null, null, dialogId, 0, true, 0);
            }
        };
        if (chat.getParentActivity() == null) {
            delete.run();
            return;
        }
        Bulletin.TwoLineLottieLayout layout = new Bulletin.TwoLineLottieLayout(chat.getParentActivity(), chat.getResourceProvider());
        layout.setAnimation(R.raw.ic_delete, 28, 28);
        layout.titleTextView.setText("Свои сообщения удаляются");
        layout.subtitleTextView.setText(LocaleController.formatPluralString("messages", ids.size()));
        layout.setTimer();
        layout.setButton(new Bulletin.UndoButton(chat.getParentActivity(), true, chat.getResourceProvider()).setDelayedAction(delete));
        Bulletin.make(chat, layout, Bulletin.DURATION_PROLONG).show();
    }
}
