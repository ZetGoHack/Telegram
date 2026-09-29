package org.telegram.rawgram;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.MentionsAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * One reroll run: repeats the inline query (bypassing the local cache) until a
 * selected result matches, keeps a per-attempt history and drives the UI
 * (auto spinner in the input, status bubble above the results, status sheet).
 */
public class RawRerollController {

    /** Chat screen hook: refreshes the spinner and the bubble. */
    public interface Host {
        void onRerollStateChanged();
    }

    public static final int STATE_RUNNING = 0;
    public static final int STATE_MATCHED = 1;
    public static final int STATE_NOT_FOUND = 2;
    public static final int STATE_STOPPED = 3;
    public static final int STATE_ERROR = 4;

    public static class Attempt {
        public int number;
        public long tookMs;
        public int results;
        public long queryId;
        public boolean sameQueryId;
        public int matchIndex = -1;
        public int matchCount;
        public String matchTitle;
        public String error;
    }

    private static RawRerollController active;

    public static RawRerollController getActive() {
        return active;
    }

    final Context context;
    final int currentAccount;
    final MentionsAdapter adapter;
    final TLRPC.User bot;
    final Utilities.Callback<TLRPC.BotInlineResult> onSend;
    final Theme.ResourcesProvider resourcesProvider;
    final Host host;

    RawReroll.Options options;
    private Pattern pattern;
    String query;
    private String key;

    int state = STATE_STOPPED;
    int attempt;
    long startTime;
    long finishTime;
    final ArrayList<Attempt> history = new ArrayList<>();
    TLRPC.BotInlineResult matched;
    int matchedIndex = -1;
    TLRPC.messages_BotResults lastResponse;

    private int runId;
    private long lastQueryId;
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    private RawRerollController(Context context, int currentAccount, MentionsAdapter adapter, Utilities.Callback<TLRPC.BotInlineResult> onSend,
                                Theme.ResourcesProvider resourcesProvider, Host host) {
        this.context = context;
        this.currentAccount = currentAccount;
        this.adapter = adapter;
        this.bot = adapter.getContextBotUser();
        this.onSend = onSend;
        this.resourcesProvider = resourcesProvider;
        this.host = host;
    }

    /** Asks for options (prefilled with the last ones for this query), then starts. */
    public static void showOptionsAndStart(Context context, int currentAccount, MentionsAdapter adapter, Utilities.Callback<TLRPC.BotInlineResult> onSend,
                                           Theme.ResourcesProvider resourcesProvider, Host host, Runnable beforeStart) {
        TLRPC.TL_messages_getInlineBotResults probe = adapter != null ? adapter.rawgramBuildRequest("") : null;
        if (probe == null) {
            RawNotify.show(R.drawable.msg_warning, "Inline-запрос уже не активен");
            return;
        }
        final String key = RawReroll.queryKey(probe);
        RawReroll.showDialog(context, RawReroll.load(key), resourcesProvider, opts -> {
            RawReroll.save(key, opts);
            if (beforeStart != null) {
                beforeStart.run();
            }
            if (active != null) {
                active.dismiss();
            }
            RawRerollController controller = new RawRerollController(context, currentAccount, adapter, onSend, resourcesProvider, host);
            controller.key = key;
            active = controller;
            controller.begin(opts, probe.query);
        });
    }

    private void begin(RawReroll.Options opts, String query) {
        this.options = opts;
        this.pattern = RawReroll.compile(opts);
        this.query = query;
        attempt = 0;
        history.clear();
        matched = null;
        matchedIndex = -1;
        startTime = SystemClock.elapsedRealtime();
        finishTime = 0;
        lastQueryId = adapter.rawgramLastResponse != null ? adapter.rawgramLastResponse.query_id : 0;
        // a new reroll clears the highlight left by the previous one
        adapter.rawgramHighlightIds.clear();
        adapter.notifyDataSetChanged();
        state = STATE_RUNNING;
        changed();
        next(++runId);
    }

    public boolean isRunning() {
        return state == STATE_RUNNING;
    }

    public void stop() {
        if (state != STATE_RUNNING) {
            return;
        }
        finish(STATE_STOPPED);
        RawNotify.show(R.drawable.msg_info, "reroll остановлен на попытке " + attempt);
    }

    /** Same options from scratch. */
    public void restart() {
        runId++;
        begin(options, query);
    }

    /** Edit options, then restart with them. */
    public void editOptions() {
        RawReroll.showDialog(context, options, resourcesProvider, opts -> {
            if (key != null) {
                RawReroll.save(key, opts);
            }
            runId++;
            begin(opts, query);
        });
    }

    /** Stops and removes the run: the bubble disappears. */
    public void dismiss() {
        runId++;
        state = STATE_STOPPED;
        if (active == this) {
            active = null;
        }
        changed();
    }

    public void openMatch() {
        if (matched != null) {
            RawInlineResultViewer.show(context, currentAccount, adapter, matched, onSend, resourcesProvider, host);
        }
    }

    public String getPatternLabel() {
        return options.regex ? "/" + options.pattern + "/" : "«" + options.pattern + "»";
    }

    public long getElapsedMs() {
        return (finishTime != 0 ? finishTime : SystemClock.elapsedRealtime()) - startTime;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Any change of the inline query text ends a run bound to the old query. */
    public static void onInlineQueryChanged(MentionsAdapter adapter, String query) {
        if (active != null && active.adapter == adapter && (query == null || !query.equals(active.query))) {
            active.dismiss();
        }
    }

    /** The chat is closing. */
    public static void dismissActive() {
        if (active != null) {
            active.dismiss();
        }
    }

    private void finish(int newState) {
        runId++;
        state = newState;
        finishTime = SystemClock.elapsedRealtime();
        changed();
    }

    private void changed() {
        if (host != null) {
            host.onRerollStateChanged();
        }
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    private void next(final int id) {
        if (id != runId || state != STATE_RUNNING) {
            return;
        }
        TLRPC.TL_messages_getInlineBotResults req = adapter.rawgramBuildRequest("");
        if (req == null || query != null && !query.equals(req.query)) {
            finish(STATE_STOPPED);
            return;
        }
        attempt++;
        changed();
        final long sent = SystemClock.elapsedRealtime();
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (id != runId || state != STATE_RUNNING) {
                return;
            }
            Attempt a = new Attempt();
            a.number = attempt;
            a.tookMs = SystemClock.elapsedRealtime() - sent;
            history.add(a);
            if (error != null || !(res instanceof TLRPC.messages_BotResults)) {
                a.error = error != null ? error.code + " " + error.text : "unexpected " + TLDumper.typeName(res);
                finish(STATE_ERROR);
                RawNotify.show(R.drawable.msg_warning, "reroll: " + a.error + " на попытке " + attempt);
                return;
            }
            TLRPC.messages_BotResults botResults = (TLRPC.messages_BotResults) res;
            lastResponse = botResults;
            a.results = botResults.results.size();
            a.queryId = botResults.query_id;
            a.sameQueryId = botResults.query_id == lastQueryId;
            lastQueryId = botResults.query_id;

            // indices refer to the list the user sees, i.e. without results the client drops
            ArrayList<TLRPC.BotInlineResult> visible = new ArrayList<>();
            for (TLRPC.BotInlineResult r : botResults.results) {
                r.query_id = botResults.query_id;
                if (!MentionsAdapter.rawgramIsHiddenByClient(r)) {
                    visible.add(r);
                }
            }
            HashSet<String> matchedIds = new HashSet<>();
            for (int index : RawReroll.parseIndices(options.indices, visible.size())) {
                TLRPC.BotInlineResult r = visible.get(index);
                if (RawReroll.matches(r, options, pattern)) {
                    if (r.id != null) {
                        matchedIds.add(r.id);
                    }
                    a.matchCount++;
                    if (a.matchIndex < 0) {
                        a.matchIndex = index;
                        a.matchTitle = r.title != null ? r.title : r.id;
                        matched = r;
                        matchedIndex = index;
                    }
                }
            }
            if (a.matchIndex >= 0) {
                adapter.rawgramApplyResults(botResults, matchedIds);
                finish(STATE_MATCHED);
                RawObjectSheet sheet = RawInlineResultViewer.show(context, currentAccount, adapter, matched, onSend, resourcesProvider, host);
                sheet.setSubtitle("✓ совпадение в [" + matchedIndex + "]" + (a.matchCount > 1 ? " (+" + (a.matchCount - 1) + ")" : "")
                        + " на попытке " + attempt + " · " + getPatternLabel());
                return;
            }
            if (attempt >= options.maxAttempts) {
                adapter.rawgramApplyResults(botResults, null);
                finish(STATE_NOT_FOUND);
                RawNotify.show(R.drawable.msg_info, "reroll: " + getPatternLabel() + " не найдено за " + attempt + " попыток");
                return;
            }
            changed();
            AndroidUtilities.runOnUIThread(() -> next(id), options.delayMs);
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }
}
