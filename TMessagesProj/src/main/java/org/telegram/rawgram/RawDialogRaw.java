package org.telegram.rawgram;

import android.app.Activity;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

/** "Raw" item in the dialog long-press preview menu: TL Dialog, peer, user/chat, top message. */
public class RawDialogRaw {

    public static View menuItem(BaseFragment fragment, long dialogId) {
        ActionBarMenuSubItem item = new ActionBarMenuSubItem(fragment.getParentActivity(), false, false);
        item.setTextAndIcon("Raw", R.drawable.msg_info);
        item.setMinimumWidth(160);
        item.setOnClickListener(v -> {
            fragment.finishPreviewFragment();
            // let the preview close before the sheet is attached
            AndroidUtilities.runOnUIThread(() -> show(fragment, dialogId), 250);
        });
        return item;
    }

    public static void show(BaseFragment fragment, long dialogId) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        int account = fragment.getCurrentAccount();
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
        TLRPC.Peer peer = controller.getPeer(dialogId);
        TLRPC.InputPeer inputPeer = controller.getInputPeer(dialogId);
        Object entity = DialogObject.isUserDialog(dialogId) ? controller.getUser(dialogId) : controller.getChat(-dialogId);

        RawObjectSheet sheet = new RawObjectSheet(activity, account, "Raw · dialog " + dialogId, dialog, fragment.getResourceProvider());
        String dialogSubtitle = dialog != null
                ? TLDumper.typeName(dialog) + " · top " + dialog.top_message + " · unread " + dialog.unread_count + " · pts " + dialog.pts + " · folder " + dialog.folder_id
                : "диалога нет в dialogs_dict";
        sheet.setObject(dialogSubtitle, dialog);
        sheet.addObjectTab("Dialog", () -> sheet.setObject(dialogSubtitle, dialog));
        sheet.addObjectTab("Peer", () -> sheet.setObject("peer + inputPeer", peerMap(peer, inputPeer)));
        if (entity != null) {
            sheet.addObjectTab(DialogObject.isUserDialog(dialogId) ? "User" : "Chat", () -> sheet.setObject(TLDumper.typeName(entity), entity));
        }
        ArrayList<MessageObject> messages = controller.dialogMessage.get(dialogId);
        if (messages != null && !messages.isEmpty() && messages.get(0).messageOwner != null) {
            TLRPC.Message top = messages.get(0).messageOwner;
            sheet.addObjectTab("Top message", () -> sheet.setObject(TLDumper.typeName(top) + " · id " + top.id, top));
        }
        sheet.show();
    }

    private static java.util.LinkedHashMap<String, Object> peerMap(TLRPC.Peer peer, TLRPC.InputPeer inputPeer) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("peer", peer);
        out.put("inputPeer", inputPeer);
        return out;
    }
}
