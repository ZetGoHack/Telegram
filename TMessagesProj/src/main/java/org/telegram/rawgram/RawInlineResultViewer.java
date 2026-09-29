package org.telegram.rawgram;

import android.content.Context;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Pattern;

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

    public static RawObjectSheet show(Context context, int currentAccount, MentionsAdapter adapter, TLRPC.BotInlineResult result, Utilities.Callback<TLRPC.BotInlineResult> onSend, Theme.ResourcesProvider resourcesProvider) {
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
                Toast.makeText(context, "No response captured yet", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(context, "Inline query is no longer active", Toast.LENGTH_SHORT).show();
                return;
            }
            sheet.setObject("messages.getInlineBotResults", req);
        });
        sheet.addAction("Re-request", v -> {
            TLRPC.TL_messages_getInlineBotResults req = adapter != null ? adapter.rawgramBuildRequest("") : null;
            if (req == null) {
                Toast.makeText(context, "Inline query is no longer active", Toast.LENGTH_SHORT).show();
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
                if (fresh != null) {
                    current[0] = fresh;
                    sheet.setObject(describe(fresh) + " · refreshed in " + took + " ms", fresh);
                    sheet.setPreview(buildPreview(currentAccount, fresh, bot));
                } else {
                    sheet.setObject("result id " + current[0].id + " is gone · " + botResults.results.size() + " results · " + took + " ms", botResults);
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        });
        final RerollSession reroll = new RerollSession(context, currentAccount, adapter, sheet, bot, current, response, resourcesProvider);
        reroll.button = sheet.addAction("Reroll…", v -> reroll.toggle());
        if (onSend != null) {
            sheet.addAction("Send", v -> {
                sheet.dismiss();
                onSend.run(current[0]);
            });
        }
        sheet.show();
        return sheet;
    }

    /** Repeats the inline query (bypassing the local cache) until a selected result matches. */
    private static class RerollSession {
        private final Context context;
        private final int currentAccount;
        private final MentionsAdapter adapter;
        private final RawObjectSheet sheet;
        private final TLRPC.User bot;
        private final TLRPC.BotInlineResult[] current;
        private final TLRPC.messages_BotResults[] response;
        private final Theme.ResourcesProvider resourcesProvider;
        TextView button;

        private int runId;
        private boolean running;
        private RawReroll.Options options;
        private Pattern pattern;
        private int attempt;
        private long lastQueryId;

        RerollSession(Context context, int currentAccount, MentionsAdapter adapter, RawObjectSheet sheet, TLRPC.User bot,
                      TLRPC.BotInlineResult[] current, TLRPC.messages_BotResults[] response, Theme.ResourcesProvider resourcesProvider) {
            this.context = context;
            this.currentAccount = currentAccount;
            this.adapter = adapter;
            this.sheet = sheet;
            this.bot = bot;
            this.current = current;
            this.response = response;
            this.resourcesProvider = resourcesProvider;
        }

        void toggle() {
            if (running) {
                stop("reroll остановлен на попытке " + attempt);
                return;
            }
            TLRPC.TL_messages_getInlineBotResults probe = adapter != null ? adapter.rawgramBuildRequest("") : null;
            if (probe == null) {
                Toast.makeText(context, "Inline query is no longer active", Toast.LENGTH_SHORT).show();
                return;
            }
            final String key = RawReroll.queryKey(probe);
            RawReroll.showDialog(context, RawReroll.load(key), resourcesProvider, opts -> {
                RawReroll.save(key, opts);
                options = opts;
                pattern = RawReroll.compile(opts);
                attempt = 0;
                lastQueryId = response[0] != null ? response[0].query_id : 0;
                running = true;
                next(++runId);
            });
        }

        private void stop(String status) {
            running = false;
            runId++;
            button.setText("Reroll…");
            if (status != null) {
                sheet.setSubtitle(status);
            }
        }

        private void next(final int id) {
            if (id != runId || !running) {
                return;
            }
            if (!sheet.isShowing()) {
                running = false;
                return;
            }
            TLRPC.TL_messages_getInlineBotResults req = adapter.rawgramBuildRequest("");
            if (req == null) {
                stop("reroll: inline query больше не активен");
                return;
            }
            attempt++;
            button.setText("Stop " + attempt + "/" + options.maxAttempts);
            sheet.setSubtitle("reroll " + attempt + "/" + options.maxAttempts + " · ищу " + (options.regex ? "/" + options.pattern + "/" : "«" + options.pattern + "»") + " …");
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (id != runId || !running) {
                    return;
                }
                if (error != null) {
                    stop(null);
                    sheet.setObject("reroll: error " + error.code + " " + error.text + " на попытке " + attempt, error);
                    return;
                }
                if (!(res instanceof TLRPC.messages_BotResults)) {
                    stop(null);
                    sheet.setObject("reroll: unexpected response на попытке " + attempt, res);
                    return;
                }
                TLRPC.messages_BotResults botResults = (TLRPC.messages_BotResults) res;
                response[0] = botResults;
                boolean sameQueryId = botResults.query_id == lastQueryId;
                lastQueryId = botResults.query_id;
                for (TLRPC.BotInlineResult r : botResults.results) {
                    r.query_id = botResults.query_id;
                }
                for (int index : RawReroll.parseIndices(options.indices, botResults.results.size())) {
                    TLRPC.BotInlineResult r = botResults.results.get(index);
                    if (RawReroll.matches(r, options, pattern)) {
                        current[0] = r;
                        stop(null);
                        sheet.setObject("✓ совпадение в [" + index + "] на попытке " + attempt + " · " + describe(r), r);
                        sheet.setPreview(buildPreview(currentAccount, r, bot));
                        return;
                    }
                }
                if (attempt >= options.maxAttempts) {
                    stop(null);
                    sheet.setObject("✗ не найдено за " + attempt + " попыток · последний ответ: " + botResults.results.size() + " results · cache_time " + botResults.cache_time, botResults);
                    return;
                }
                if (sameQueryId) {
                    sheet.setSubtitle("reroll " + attempt + "/" + options.maxAttempts + " · тот же query_id — возможно, сервер отдаёт кэш (cache_time " + botResults.cache_time + ")");
                }
                AndroidUtilities.runOnUIThread(() -> next(id), options.delayMs);
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        }
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
            message.id = 1;
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
