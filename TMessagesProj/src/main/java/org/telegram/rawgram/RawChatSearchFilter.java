package org.telegram.rawgram;

import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;

/**
 * Media-type filter of the in-chat message search ({@link MediaDataController#searchMessagesInChat}), one per account.
 * <p>
 * The type filter (photos, files, links, ...) picked with the chat search bar's filter button
 * ({@link RawChatSearchFilterButton}) goes to the server as {@code messages.search filter}. Combined with a sender
 * ({@code from_id}) the server doesn't filter by both, so first both counts are requested (type only, sender only),
 * the narrower one stays on the server and the other is applied locally: each page is scanned and refilled from the
 * following pages until it has enough matches. Offsets and "end reached" then follow the raw server pages, and the
 * total count is only an estimate ({@link MediaDataController#isSearchCountApproximate}).
 * <p>
 * Ported from exteraGram's {@code ChatSearchLocalFilter} (exteraGram, GPLv3, https://github.com/exteraSquad/exteraGram).
 */
public final class RawChatSearchFilter {

    /** Index of the searched dialog in {@code MediaDataController}'s two-element arrays: the chat and the merged one. */
    public static final int SOURCE_MAIN = 0;
    public static final int SOURCE_MERGED = 1;

    /** A full page of the hybrid search; the request limit while it's active. */
    private static final int SCAN_PAGE_LIMIT = 100;
    /** A scan stops refilling once it has this many matches... */
    private static final int SCAN_ENOUGH = 10;
    /** ...or after this many server pages. */
    private static final int SCAN_MAX_REQUESTS = 5;
    private static final long SCAN_PAGE_DELAY_MS = 300;
    /** Longer FLOOD_WAITs are reported as an error instead of waited out. */
    private static final int MAX_FLOOD_WAIT_SECONDS = 60;
    private static final int COUNT_UNKNOWN = -1;

    private final int currentAccount;

    // the filter picked in the chat search bar, by the chat's classGuid
    private final SparseArray<TLRPC.MessagesFilter> selected = new SparseArray<>();
    // the filter of the current search, fixed when it starts
    private TLRPC.MessagesFilter currentFilter;

    private boolean active;
    private boolean decided;
    private String key;
    /** True: the server filters by type, the sender is matched locally; false: the other way round. */
    private boolean localSender;
    private long senderId;
    private TLRPC.MessagesFilter typeFilter;
    private volatile int token;
    private volatile int scanRequestId;
    private final int[] rawOffsetId = new int[2];
    private final boolean[] rawEndReached = new boolean[2];
    private final int[] discarded = new int[2];

    private static final class Scan {
        final int token;
        final int sourceIndex;
        final boolean localSender;
        final long senderId;
        final TLRPC.MessagesFilter typeFilter;
        final RequestDelegate delegate;
        final int flags;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        int offsetId;
        int requests;
        int discarded;
        boolean endReached;

        Scan(int token, int sourceIndex, boolean localSender, long senderId, TLRPC.MessagesFilter typeFilter, RequestDelegate delegate, int flags) {
            this.token = token;
            this.sourceIndex = sourceIndex;
            this.localSender = localSender;
            this.senderId = senderId;
            this.typeFilter = typeFilter;
            this.delegate = delegate;
            this.flags = flags;
        }
    }

    public RawChatSearchFilter(int account) {
        currentAccount = account;
    }

    // ---- the filter picked in the UI ----

    /** The chat with {@code guid} picked {@code filter} (null: none) for its next searches. */
    public void select(int guid, TLRPC.MessagesFilter filter) {
        if (filter != null) {
            selected.put(guid, filter);
        } else {
            selected.remove(guid);
        }
    }

    /** The chat with {@code guid} is gone: forget its filter. */
    public void forget(int guid) {
        selected.remove(guid);
    }

    /**
     * The type filter of a search call: a new query takes the one picked by the chat with {@code guid}; paging and the
     * internal follow-up calls keep the one of the search they continue.
     */
    public TLRPC.MessagesFilter begin(int guid, boolean newQuery) {
        if (newQuery) {
            currentFilter = selected.get(guid);
        }
        return currentFilter;
    }

    /** Puts {@code filter} (or the empty one) on a search request. */
    public static void putFilter(TLRPC.TL_messages_search req, TLRPC.MessagesFilter filter) {
        req.filter = filter != null ? filter : new TLRPC.TL_inputMessagesFilterEmpty();
    }

    // ---- the hybrid search (type + sender) ----

    /**
     * Called for a new query. With both a sender and a type filter it decides (once per query) which one the server
     * applies; until both counts arrive it returns true, and {@code retry} restarts the search afterwards.
     */
    public boolean prepare(String query, long dialogId, long replyMessageId, TLRPC.User user, TLRPC.Chat chat, ReactionsLayoutInBubble.VisibleReaction reaction, TLRPC.MessagesFilter filter, Runnable retry) {
        rawOffsetId[0] = rawOffsetId[1] = 0;
        rawEndReached[0] = rawEndReached[1] = false;
        discarded[0] = discarded[1] = 0;
        long sender = user != null ? user.id : chat != null ? -chat.id : 0;
        if (sender == 0 || filter == null) {
            reset();
            return false;
        }
        String newKey = dialogId + "_" + replyMessageId + "_" + sender + "_" + filter.getClass().getName() + "_" + query + "_"
                + (reaction != null ? reaction.emojicon + reaction.documentId : "");
        if (decided && newKey.equals(key)) {
            active = true;
            return false;
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        TLRPC.InputPeer peer = messagesController.getInputPeer(dialogId);
        TLRPC.InputPeer from = user != null ? MessagesController.getInputPeer(user) : MessagesController.getInputPeer(chat);
        if (peer == null || from == null) {
            reset();
            return false;
        }
        key = newKey;
        decided = false;
        active = false;
        senderId = sender;
        typeFilter = filter;
        final int requestToken = ++token;

        TLRPC.TL_messages_search byType = createCountRequest(peer, query, dialogId, replyMessageId, reaction);
        byType.filter = filter;
        TLRPC.TL_messages_search bySender = createCountRequest(peer, query, dialogId, replyMessageId, reaction);
        bySender.from_id = from;
        bySender.flags |= 1;
        bySender.filter = new TLRPC.TL_inputMessagesFilterEmpty();

        final int[] counts = {COUNT_UNKNOWN, COUNT_UNKNOWN};
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
        connectionsManager.sendRequest(byType, (response, error) -> AndroidUtilities.runOnUIThread(() -> onCount(requestToken, 0, response, counts, retry)));
        connectionsManager.sendRequest(bySender, (response, error) -> AndroidUtilities.runOnUIThread(() -> onCount(requestToken, 1, response, counts, retry)));
        return true;
    }

    /** Adjusts a request of the hybrid search: drops the part applied locally; a page request gets a larger limit. */
    public void applyTo(TLRPC.TL_messages_search req, boolean page) {
        if (!active) {
            return;
        }
        if (localSender) {
            req.from_id = null;
            req.flags &= ~1;
        } else {
            req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        }
        if (page) {
            req.limit = SCAN_PAGE_LIMIT;
        }
    }

    /** Sends a page request; while the hybrid search is active, the page is filtered and refilled before it's delivered. */
    public int sendRequest(int sourceIndex, TLRPC.TL_messages_search req, RequestDelegate delegate, int flags) {
        if (!active) {
            return ConnectionsManager.getInstance(currentAccount).sendRequest(req, delegate, flags);
        }
        Scan scan = new Scan(token, sourceIndex, localSender, senderId, typeFilter, delegate, flags);
        scan.offsetId = req.offset_id;
        return sendPage(scan, req);
    }

    public boolean isActive() {
        return active;
    }

    public boolean isEndReached(int sourceIndex, boolean endReached) {
        return active ? rawEndReached[sourceIndex] : endReached;
    }

    /** The offset for the next page: the last scanned message, not the last match. */
    public int getOffsetId(int sourceIndex, int offsetId) {
        return active ? rawOffsetId[sourceIndex] : offsetId;
    }

    public void cancel() {
        token++;
        if (scanRequestId != 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(scanRequestId, true);
            scanRequestId = 0;
        }
    }

    /** Drops the hybrid search state (the picked filter stays). */
    public void reset() {
        cancel();
        key = null;
        decided = false;
        active = false;
    }

    /** Search results came from elsewhere (saved-messages tags): no type filter on them. */
    public void resetAll() {
        reset();
        currentFilter = null;
    }

    private TLRPC.TL_messages_search createCountRequest(TLRPC.InputPeer peer, String query, long dialogId, long replyMessageId, ReactionsLayoutInBubble.VisibleReaction reaction) {
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = peer;
        req.q = query;
        req.limit = 1;
        if (replyMessageId != 0) {
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            if (dialogId == UserConfig.getInstance(currentAccount).getClientUserId() || messagesController.isMonoForum(dialogId)) {
                req.saved_peer_id = messagesController.getInputPeer(replyMessageId);
                req.flags |= 4;
            } else {
                req.top_msg_id = (int) replyMessageId;
                req.flags |= 2;
            }
        }
        if (reaction != null) {
            req.saved_reaction.add(reaction.toTLReaction());
            req.flags |= 8;
        }
        return req;
    }

    private void onCount(int requestToken, int index, TLObject response, int[] counts, Runnable retry) {
        if (requestToken != token) {
            return;
        }
        counts[index] = response instanceof TLRPC.messages_Messages ? getCount((TLRPC.messages_Messages) response) : Integer.MAX_VALUE;
        if (counts[0] < 0 || counts[1] < 0) {
            return;
        }
        localSender = counts[0] <= counts[1];
        decided = true;
        active = true;
        retry.run();
    }

    private int sendPage(Scan scan, TLRPC.TL_messages_search req) {
        scan.requests++;
        int requestId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> onPage(scan, req, response, error), scan.flags);
        scanRequestId = requestId;
        return requestId;
    }

    private void onPage(Scan scan, TLRPC.TL_messages_search req, TLObject response, TLRPC.TL_error error) {
        if (scan.token != token) {
            return;
        }
        if (!(response instanceof TLRPC.messages_Messages)) {
            int wait = error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_") ? Utilities.parseInt(error.text) : 0;
            if (wait > 0 && wait <= MAX_FLOOD_WAIT_SECONDS) {
                Utilities.stageQueue.postRunnable(() -> {
                    if (scan.token == token) {
                        sendPage(scan, req);
                    }
                }, wait * 1000L);
            } else {
                scanRequestId = 0;
                scan.delegate.run(response, error);
            }
            return;
        }
        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
        int wanted = req.limit - 1;
        int available = Math.min(res.messages.size(), wanted);
        int scanned = 0;
        while (scanned < available && scan.messages.size() < wanted) {
            TLRPC.Message message = res.messages.get(scanned++);
            boolean matches = scan.localSender ? MessageObject.getPeerId(message.from_id) == scan.senderId : matchesType(message, scan.typeFilter);
            if (matches) {
                scan.messages.add(message);
            } else {
                scan.discarded++;
            }
        }
        scan.users.addAll(res.users);
        scan.chats.addAll(res.chats);
        if (scanned > 0) {
            scan.offsetId = res.messages.get(scanned - 1).id;
        }
        scan.endReached = scanned == available && res.messages.size() < req.limit;
        int found = scan.messages.size();
        if (!scan.endReached && (found == 0 || found < SCAN_ENOUGH && scan.requests < SCAN_MAX_REQUESTS)) {
            TLRPC.TL_messages_search next = copy(req);
            next.offset_id = scan.offsetId;
            Utilities.stageQueue.postRunnable(() -> {
                if (scan.token == token) {
                    sendPage(scan, next);
                }
            }, SCAN_PAGE_DELAY_MS);
            return;
        }
        scanRequestId = 0;
        rawOffsetId[scan.sourceIndex] = scan.offsetId;
        rawEndReached[scan.sourceIndex] = scan.endReached;
        discarded[scan.sourceIndex] += scan.discarded;
        TLRPC.TL_messages_messagesSlice slice = new TLRPC.TL_messages_messagesSlice();
        slice.messages = scan.messages;
        slice.users = scan.users;
        slice.chats = scan.chats;
        slice.count = Math.max(getCount(res) - discarded[scan.sourceIndex], found);
        scan.delegate.run(slice, null);
    }

    private static int getCount(TLRPC.messages_Messages res) {
        return res instanceof TLRPC.TL_messages_messagesSlice || res instanceof TLRPC.TL_messages_channelMessages ? res.count : res.messages.size();
    }

    private static TLRPC.TL_messages_search copy(TLRPC.TL_messages_search req) {
        TLRPC.TL_messages_search copy = new TLRPC.TL_messages_search();
        copy.flags = req.flags;
        copy.peer = req.peer;
        copy.q = req.q;
        copy.from_id = req.from_id;
        copy.saved_peer_id = req.saved_peer_id;
        copy.saved_reaction = req.saved_reaction;
        copy.top_msg_id = req.top_msg_id;
        copy.filter = req.filter;
        copy.min_date = req.min_date;
        copy.max_date = req.max_date;
        copy.offset_id = req.offset_id;
        copy.add_offset = req.add_offset;
        copy.limit = req.limit;
        copy.max_id = req.max_id;
        copy.min_id = req.min_id;
        copy.hash = req.hash;
        return copy;
    }

    /** Local equivalent of the server-side {@code messages.search} type filters offered in the search bar. */
    private static boolean matchesType(TLRPC.Message message, TLRPC.MessagesFilter filter) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        TLRPC.Document document = media != null ? media.document : null;
        if (filter instanceof TLRPC.TL_inputMessagesFilterPhotos) {
            return media instanceof TLRPC.TL_messageMediaPhoto;
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterVideo) {
            return MessageObject.isVideoDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterDocument) {
            return document != null && !MessageObject.isVideoDocument(document) && !MessageObject.isRoundVideoDocument(document)
                    && !MessageObject.isVoiceDocument(document) && !MessageObject.isMusicDocument(document) && !MessageObject.isGifDocument(document)
                    && !MessageObject.isStickerDocument(document) && !MessageObject.isAnimatedStickerDocument(document, true);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterUrl) {
            return media instanceof TLRPC.TL_messageMediaWebPage || hasUrlEntity(message);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterMusic) {
            return MessageObject.isMusicDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterVoice) {
            return MessageObject.isVoiceDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterRoundVideo) {
            return MessageObject.isRoundVideoDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterGif) {
            return MessageObject.isGifDocument(document);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterPoll) {
            return media instanceof TLRPC.TL_messageMediaPoll;
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterGeo) {
            return MessageObject.isLocationMessage(message);
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterPinned) {
            return message.pinned;
        } else if (filter instanceof TLRPC.TL_inputMessagesFilterMyMentions) {
            return message.mentioned;
        }
        return true;
    }

    private static boolean hasUrlEntity(TLRPC.Message message) {
        if (message.entities == null) {
            return false;
        }
        for (int i = 0; i < message.entities.size(); i++) {
            TLRPC.MessageEntity entity = message.entities.get(i);
            if (entity instanceof TLRPC.TL_messageEntityUrl || entity instanceof TLRPC.TL_messageEntityTextUrl) {
                return true;
            }
        }
        return false;
    }

    // ---- the result counter in the search bar ----

    private static final String APPROXIMATE = "≈";

    /** "≈" before the result count while it's only an estimate. */
    public static String approximatePrefix(MediaDataController controller) {
        return controller.isSearchCountApproximate() ? APPROXIMATE : "";
    }

    /** {@code ofText} ("3 of 120") with "≈" before the total while it's only an estimate. */
    public static String approximateOf(MediaDataController controller, String ofText, int count) {
        if (ofText == null || !controller.isSearchCountApproximate()) {
            return ofText;
        }
        int at = ofText.lastIndexOf(String.valueOf(count));
        return at < 0 ? ofText : ofText.substring(0, at) + APPROXIMATE + ofText.substring(at);
    }
}
