package org.telegram.rawgram.drawer;

import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.rawgram.RawgramSettingsActivity;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.WebAppDisclaimerAlert;
import org.telegram.ui.bots.BotWebViewSheet;

import java.util.ArrayList;

/**
 * The rows of the side menu (rawGram's stand-in for exteraGram's MainMenuHelper / main menu layout): new group,
 * new channel, contacts, calls, saved messages, archive (with its unread counter), the bots that ask for a place in
 * the side menu, then settings and rawGram. A {@code null} entry is a divider.
 */
final class RawDrawerItems {

    /** What the rows need from the drawer. */
    interface Host {
        /** Closes the menu with its animation. */
        void close();

        /** Closes the menu and opens {@code fragment} over the current screen. */
        void present(org.telegram.ui.ActionBar.BaseFragment fragment);

        LaunchActivity activity();
    }

    /** Unread counter of a row for an account; 0 hides the badge. */
    interface Counter {
        int get(int account);
    }

    static final class Item {
        final int icon;
        final CharSequence text;
        final Runnable onClick;
        final Runnable onLongClick;
        final Counter counter;

        Item(int icon, CharSequence text, Runnable onClick, Runnable onLongClick, Counter counter) {
            this.icon = icon;
            this.text = text;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
            this.counter = counter;
        }

        Item(int icon, CharSequence text, Runnable onClick) {
            this(icon, text, onClick, null, null);
        }
    }

    private RawDrawerItems() {
    }

    static ArrayList<Item> build(Host host, int account) {
        ArrayList<Item> items = new ArrayList<>();
        items.add(new Item(R.drawable.msg_groups, "Новая группа", () -> host.present(new GroupCreateActivity(new Bundle()))));
        items.add(new Item(R.drawable.msg_channel, "Новый канал", () -> {
            Bundle args = new Bundle();
            args.putInt("step", 0);
            host.present(new ChannelCreateActivity(args));
        }));
        items.add(new Item(R.drawable.msg_contacts, "Контакты", () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            host.present(new ContactsActivity(args));
        }));
        items.add(new Item(R.drawable.msg_calls, "Звонки", () -> host.present(new CallLogActivity())));
        items.add(new Item(R.drawable.msg_saved, "Избранное", () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
            host.present(new ChatActivity(args));
        }));
        items.add(new Item(R.drawable.msg_archive, "Архив", () -> {
            Bundle args = new Bundle();
            args.putInt("folderId", 1);
            host.present(new DialogsActivity(args));
        }, null, a -> MessagesStorage.getInstance(a).getArchiveUnreadCount()));
        addSideMenuBots(items, host, account);
        items.add(null);
        items.add(new Item(R.drawable.msg_settings_old, "Настройки", () -> host.present(new SettingsActivity())));
        items.add(new Item(R.drawable.msg_settings, "rawGram", () -> host.present(new RawgramSettingsActivity())));
        return items;
    }

    /** Mini apps that asked for a place in the side menu; a long press removes one (as in Telegram's own menu). */
    private static void addSideMenuBots(ArrayList<Item> items, Host host, int account) {
        TLRPC.TL_attachMenuBots bots = MediaDataController.getInstance(account).getAttachMenuBots();
        if (bots == null || bots.bots == null) {
            return;
        }
        for (TLRPC.TL_attachMenuBot bot : bots.bots) {
            if (!bot.show_in_side_menu) {
                continue;
            }
            items.add(new Item(R.drawable.msg_bot, bot.short_name, () -> {
                host.close();
                openSideMenuBot(host.activity(), account, bot);
            }, () -> BotWebViewSheet.deleteBot(account, bot.bot_id, null), null));
        }
    }

    private static void openSideMenuBot(LaunchActivity activity, int account, TLRPC.TL_attachMenuBot bot) {
        if (!bot.inactive && !bot.side_menu_disclaimer_needed) {
            LaunchActivity.showAttachMenuBot(activity, account, bot, null, true);
            return;
        }
        WebAppDisclaimerAlert.show(activity, allowSendMessage -> {
            TLRPC.TL_messages_toggleBotInAttachMenu request = new TLRPC.TL_messages_toggleBotInAttachMenu();
            request.bot = MessagesController.getInstance(account).getInputUser(bot.bot_id);
            request.enabled = true;
            request.write_allowed = true;
            ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                bot.inactive = bot.side_menu_disclaimer_needed = false;
                LaunchActivity.showAttachMenuBot(activity, account, bot, null, true);
                MediaDataController.getInstance(account).updateAttachMenuBotsInCache();
            }), ConnectionsManager.RequestFlagInvokeAfter | ConnectionsManager.RequestFlagFailOnServerErrors);
        }, null, null);
    }
}
