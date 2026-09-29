package org.telegram.rawgram;

import android.content.Context;
import org.telegram.messenger.R;


import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.MentionsAdapter;

/**
 * Long-press viewer for inline bot results: renders the message the result
 * would send (text, entities, inline keyboard, media) and shows the raw TL.
 */
public class RawInlineResultViewer {

    private static int previewMessageId;

    public static RawObjectSheet show(Context context, int currentAccount, MentionsAdapter adapter, TLRPC.BotInlineResult result, Utilities.Callback<TLRPC.BotInlineResult> onSend, Theme.ResourcesProvider resourcesProvider) {
        return show(context, currentAccount, adapter, result, onSend, resourcesProvider, null);
    }

    public static RawObjectSheet show(Context context, int currentAccount, MentionsAdapter adapter, TLRPC.BotInlineResult result, Utilities.Callback<TLRPC.BotInlineResult> onSend, Theme.ResourcesProvider resourcesProvider, RawRerollController.Host host) {
        final TLRPC.User bot = adapter != null ? adapter.getContextBotUser() : null;
        final TLRPC.BotInlineResult[] current = new TLRPC.BotInlineResult[] { result };
        final TLRPC.messages_BotResults[] response = new TLRPC.messages_BotResults[] { adapter != null ? adapter.rawgramLastResponse : null };

        String title = result.title != null && !result.title.isEmpty() ? result.title : "Inline result";
        RawObjectSheet sheet = new RawObjectSheet(context, currentAccount, title, result, resourcesProvider);
        sheet.setSubtitle(describe(result));
        sheet.setPreview(buildPreview(currentAccount, result, bot));

        sheet.addAction("Result", v -> {
            sheet.setObject(describe(current[0]), current[0]);
            sheet.setPreview(buildPreview(currentAccount, current[0], bot));
        });
        sheet.addAction("send_message", v -> sheet.setObject("send_message: " + TLDumper.typeName(current[0].send_message), current[0].send_message));
        sheet.addAction("Response", v -> {
            if (response[0] == null) {
                RawNotify.show(sheet, R.drawable.msg_info, "Ответ ещё не получен");
                return;
            }
            sheet.setObject("messages.botResults · " + response[0].results.size() + " results · cache_time " + response[0].cache_time
                    + (adapter != null && adapter.rawgramLastFromCache ? " · from local cache" : ""), response[0]);
        });
        if (adapter != null && !adapter.rawgramHiddenResults.isEmpty()) {
            sheet.addAction("Hidden (" + adapter.rawgramHiddenResults.size() + ")", v -> sheet.setObject("results dropped by the client: media_auto without media", adapter.rawgramHiddenResults));
        }
        sheet.addAction("Request", v -> {
            TLRPC.TL_messages_getInlineBotResults req = adapter != null ? adapter.rawgramBuildRequest("") : null;
            if (req == null) {
                RawNotify.show(sheet, R.drawable.msg_warning, "Inline-запрос уже не активен");
                return;
            }
            sheet.setObject("messages.getInlineBotResults", req);
        });
        sheet.addAction("Re-request", v -> {
            TLRPC.TL_messages_getInlineBotResults req = adapter != null ? adapter.rawgramBuildRequest("") : null;
            if (req == null) {
                RawNotify.show(sheet, R.drawable.msg_warning, "Inline-запрос уже не активен");
                return;
            }
            final long start = System.currentTimeMillis();
            sheet.setSubtitle("messages.getInlineBotResults …");
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
                long took = System.currentTimeMillis() - start;
                if (error != null) {
                    sheet.setObject("error " + error.code + " " + error.text + " · " + took + " ms", error);
                    return;
                }
                if (!(res instanceof TLRPC.messages_BotResults)) {
                    sheet.setObject("unexpected response · " + took + " ms", res);
                    return;
                }
                TLRPC.messages_BotResults botResults = (TLRPC.messages_BotResults) res;
                response[0] = botResults;
                TLRPC.BotInlineResult fresh = null;
                for (TLRPC.BotInlineResult r : botResults.results) {
                    r.query_id = botResults.query_id;
                    if (r.id != null && r.id.equals(current[0].id)) {
                        fresh = r;
                    }
                }
                // the inline list shows what the bot answered just now; old reroll highlight goes away
                adapter.rawgramApplyResults(botResults, null);
                if (fresh != null) {
                    current[0] = fresh;
                    sheet.setObject(describe(fresh) + " · refreshed in " + took + " ms", fresh);
                    sheet.setPreview(buildPreview(currentAccount, fresh, bot));
                } else {
                    sheet.setObject("result id " + current[0].id + " is gone · " + botResults.results.size() + " results · " + took + " ms", botResults);
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        });
        if (adapter != null) {
            // the raw sheet folds away while reroll runs; its status lives in the bubble above the results
            sheet.addAction("Reroll…", v -> RawRerollController.showOptionsAndStart(context, currentAccount, adapter, onSend, resourcesProvider, host, sheet::dismiss));
        }
        if (onSend != null) {
            sheet.addAction("Send", v -> {
                sheet.dismiss();
                onSend.run(current[0]);
            });
        }
        sheet.show();
        return sheet;
    }

    private static String describe(TLRPC.BotInlineResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(TLDumper.typeName(result));
        if (result.type != null) {
            sb.append(" · type ").append(result.type);
        }
        sb.append(" · id ").append(result.id);
        if (result.send_message != null) {
            sb.append(" · ").append(TLDumper.typeName(result.send_message));
        }
        return sb.toString();
    }

    /** Builds a local, never-sent message equivalent to what the result would post. */
    public static MessageObject buildPreview(int currentAccount, TLRPC.BotInlineResult result, TLRPC.User bot) {
        TLRPC.BotInlineMessage sendMessage = result.send_message;
        if (sendMessage == null) {
            return null;
        }
        try {
            long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            TLRPC.TL_message message = new TLRPC.TL_message();
            // a fresh id per preview: ChatMessageCell skips the relayout for a message it thinks it already shows
            message.id = ++previewMessageId;
            message.date = (int) (System.currentTimeMillis() / 1000);
            message.dialog_id = selfId;
            message.out = true;
            message.from_id = new TLRPC.TL_peerUser();
            message.from_id.user_id = selfId;
            message.peer_id = new TLRPC.TL_peerUser();
            message.peer_id.user_id = selfId;
            message.flags |= 256;
            if (bot != null) {
                message.via_bot_id = bot.id;
                message.flags |= 2048;
            }
            message.message = sendMessage.message != null ? sendMessage.message : "";
            if (sendMessage.entities != null && !sendMessage.entities.isEmpty()) {
                message.entities = sendMessage.entities;
                message.flags |= 128;
            }
            if (sendMessage.reply_markup != null) {
                message.reply_markup = sendMessage.reply_markup;
                message.flags |= 64;
            }
            message.invert_media = sendMessage.invert_media;
            message.media = buildMedia(result, sendMessage);
            if (!(message.media instanceof TLRPC.TL_messageMediaEmpty)) {
                message.flags |= 512;
            }
            MessageObject messageObject = new MessageObject(currentAccount, message, true, false);
            messageObject.resetLayout();
            messageObject.eventId = 1;
            messageObject.forceUpdate = true;
            return messageObject;
        } catch (Throwable e) {
            return null;
        }
    }

    private static TLRPC.MessageMedia buildMedia(TLRPC.BotInlineResult result, TLRPC.BotInlineMessage sendMessage) {
        if (sendMessage instanceof TLRPC.TL_botInlineMessageMediaAuto) {
            if (result.photo != null) {
                TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
                media.photo = result.photo;
                media.flags |= 1;
                return media;
            } else if (result.document != null) {
                TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
                media.document = result.document;
                media.flags |= 1;
                return media;
            }
        } else if (sendMessage instanceof TLRPC.TL_botInlineMessageMediaVenue) {
            TLRPC.TL_messageMediaVenue media = new TLRPC.TL_messageMediaVenue();
            media.geo = sendMessage.geo;
            media.title = sendMessage.title;
            media.address = sendMessage.address;
            media.provider = sendMessage.provider;
            media.venue_id = sendMessage.venue_id;
            media.venue_type = sendMessage.venue_type;
            return media;
        } else if (sendMessage instanceof TLRPC.TL_botInlineMessageMediaGeo) {
            TLRPC.TL_messageMediaGeo media = new TLRPC.TL_messageMediaGeo();
            media.geo = sendMessage.geo;
            return media;
        } else if (sendMessage instanceof TLRPC.TL_botInlineMessageMediaContact) {
            TLRPC.TL_messageMediaContact media = new TLRPC.TL_messageMediaContact();
            media.phone_number = sendMessage.phone_number;
            media.first_name = sendMessage.first_name;
            media.last_name = sendMessage.last_name;
            media.vcard = sendMessage.vcard;
            return media;
        }
        return new TLRPC.TL_messageMediaEmpty();
    }
}
