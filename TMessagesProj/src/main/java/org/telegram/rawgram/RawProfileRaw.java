package org.telegram.rawgram;

import android.app.Activity;

import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

/** "Raw" item of the profile ⋮ menu: User/Chat, UserFull/ChatFull and bot_info. */
public class RawProfileRaw {

    public static void show(BaseFragment fragment, int account, TLRPC.User user, TLRPC.Chat chat, TLObject full) {
        Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || user == null && chat == null) {
            return;
        }
        MessagesController controller = MessagesController.getInstance(account);
        if (full == null) {
            // the fragment may not have received the full object yet, fall back to the cache
            full = user != null ? controller.getUserFull(user.id) : controller.getChatFull(chat.id);
        }
        final TLObject fullObject = full;
        final TLObject base = user != null ? user : chat;
        String title = user != null ? "Raw · user " + user.id : "Raw · chat " + chat.id;
        RawObjectSheet sheet = new RawObjectSheet(activity, account, title, base, fragment.getResourceProvider());

        String baseSubtitle = user != null
                ? TLDumper.typeName(user) + " · id " + user.id + (user.bot ? " · bot" : "") + " · access_hash " + user.access_hash
                : TLDumper.typeName(chat) + " · id " + chat.id + " · access_hash " + chat.access_hash;
        sheet.setObject(baseSubtitle, base);
        sheet.addObjectTab(user != null ? "User" : "Chat", () -> sheet.setObject(baseSubtitle, base));
        if (fullObject != null) {
            sheet.addObjectTab(user != null ? "UserFull" : "ChatFull", () -> sheet.setObject(TLDumper.typeName(fullObject), fullObject));
        } else {
            sheet.addObjectTab(user != null ? "UserFull" : "ChatFull", () -> sheet.setObject("full-объект ещё не загружен", null));
        }
        if (user != null && user.bot && fullObject instanceof TLRPC.UserFull && ((TLRPC.UserFull) fullObject).bot_info != null) {
            TLObject botInfo = ((TLRPC.UserFull) fullObject).bot_info;
            sheet.addObjectTab("bot_info", () -> sheet.setObject(TLDumper.typeName(botInfo), botInfo));
        } else if (chat != null && fullObject instanceof TLRPC.ChatFull && ((TLRPC.ChatFull) fullObject).bot_info != null && !((TLRPC.ChatFull) fullObject).bot_info.isEmpty()) {
            TLRPC.ChatFull chatFull = (TLRPC.ChatFull) fullObject;
            sheet.addObjectTab("bot_info (" + chatFull.bot_info.size() + ")", () -> sheet.setObject("боты в чате", chatFull.bot_info));
        }
        sheet.show();
    }
}
