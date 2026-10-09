package org.telegram.rawgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.URLSpanNoUnderline;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * exteraGram profile badges: a custom emoji (plus an optional caption) that exteraGram supporters and developers
 * show next to their name. Ported from exteraGram's {@code BadgesController} / {@code ApiBadgeSource} /
 * {@code ApiController} (exteraGram, GPLv3, https://github.com/exteraSquad/exteraGram).
 * <p>
 * Only the bulk endpoints are used ({@code GET profiles} and {@code GET profiles/updates?since=}), both with
 * {@code If-None-Match}, so the server never learns which profiles are viewed. The list is cached in the app files
 * dir and refreshed at most every {@link #SYNC_INTERVAL_MS}. Everything is off when
 * {@link RawgramConfig#isExteraBadges()} is false: no requests, nothing drawn.
 * <p>
 * Display: the badge takes the emoji-status slot when there is no emoji status (replacing the premium star), and
 * the second name slot in the profile header when there is one — the same arrangement exteraGram uses.
 */
public final class RawBadges {

    private static final String BASE_URL = "https://api.exteragram.app/api/v1/";
    private static final String HEADER_SYNC_TIMESTAMP = "X-Sync-Timestamp";
    private static final long SYNC_INTERVAL_MS = 4 * 60 * 60 * 1000L;
    private static final long FULL_SYNC_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long FAILURE_BACKOFF_MS = 30 * 60 * 1000L;
    private static final long CHECK_THROTTLE_MS = 60 * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final String CACHE_FILE = "rawgram_badges.json";
    private static final String PREFS = "rawgram_badges";
    private static final String KEY_LAST_SYNC = "lastSync";
    private static final String KEY_LAST_FULL = "lastFull";
    private static final String KEY_LAST_FAILURE = "lastFailure";

    /** exteraGram's default badges (developer / supporter), used when a badge is reset. */
    public static final long DEV_BADGE_ID = 5359407509327085568L;
    public static final long SUPPORTER_BADGE_ID = 5391059537102927631L;

    public static final int STATUS_DEFAULT = 0;
    public static final int STATUS_DEVELOPER = 1;
    public static final int STATUS_SUPPORTER = 2;

    public static final int TYPE_USER = 0;
    public static final int TYPE_CHAT = 1;

    /** A badge: custom emoji document id and an optional caption. */
    public static final class Badge {
        public final long documentId;
        public final String text;

        public Badge(long documentId, String text) {
            this.documentId = documentId;
            this.text = TextUtils.isEmpty(text) ? null : text;
        }
    }

    /** One entry of the profile list. */
    static final class Entry {
        final int type;
        final int status;
        final Badge badge;
        final boolean canChangeBadge;

        Entry(int type, int status, Badge badge, boolean canChangeBadge) {
            this.type = type;
            this.status = status;
            this.badge = badge;
            this.canChangeBadge = canChangeBadge;
        }
    }

    private static final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private static volatile boolean initStarted;
    private static volatile boolean syncing;
    private static volatile long lastCheck;
    private static String etag;
    private static String since;
    private static DispatchQueue queue;

    private RawBadges() {
    }

    // ---------------------------------------------------------------- lookup

    private static boolean enabled() {
        return RawgramConfig.isExteraBadges();
    }

    private static Entry entry(long id, int type) {
        if (!enabled() || id == 0) {
            return null;
        }
        maybeSync();
        Entry e = entries.get(id);
        return e != null && e.type == type ? e : null;
    }

    /** Badge of a user, or null (also null when the switch is off). */
    public static Badge get(TLRPC.User user) {
        Entry e = user == null ? null : entry(user.id, TYPE_USER);
        return e == null ? null : e.badge;
    }

    /** Badge of a channel / chat, or null. */
    public static Badge get(TLRPC.Chat chat) {
        Entry e = chat == null ? null : entry(chat.id, TYPE_CHAT);
        return e == null ? null : e.badge;
    }

    /** Badge of a user or chat. */
    public static Badge get(TLObject peer) {
        if (peer instanceof TLRPC.User) {
            return get((TLRPC.User) peer);
        } else if (peer instanceof TLRPC.Chat) {
            return get((TLRPC.Chat) peer);
        }
        return null;
    }

    /** Badge by user id (side menu header etc.). */
    public static Badge get(long userId) {
        Entry e = entry(userId, TYPE_USER);
        return e == null ? null : e.badge;
    }

    public static boolean isDeveloper(long id, int type) {
        Entry e = entry(id, type);
        return e != null && e.status == STATUS_DEVELOPER;
    }

    /** Whether this user may set their own badge (exteraGram: canChangeBadge from the list, or a developer). */
    public static boolean canChangeBadge(long userId) {
        Entry e = entry(userId, TYPE_USER);
        return e != null && (e.canChangeBadge || e.status == STATUS_DEVELOPER);
    }

    /** The badge a user falls back to when they reset theirs. */
    public static long defaultBadgeId(long userId) {
        return isDeveloper(userId, TYPE_USER) ? DEV_BADGE_ID : SUPPORTER_BADGE_ID;
    }

    /**
     * exteraGram's "secondary slot": users who picked their own badge (or developers) keep it visible even when an
     * emoji status takes the main slot.
     */
    private static boolean useSecondarySlot(TLRPC.User user, Badge badge) {
        return user != null && badge != null && canChangeBadge(user.id)
                && (badge.documentId != defaultBadgeId(user.id) || isDeveloper(user.id, TYPE_USER));
    }

    private static boolean hasEmojiStatus(TLRPC.User user) {
        return user != null && UserObject.getEmojiStatusDocumentId(user) != null;
    }

    // ---------------------------------------------------------------- display hooks

    /** Chat bubble sender name: the badge when the sender has no emoji status, else null. */
    public static Long chatStatus(TLRPC.User user) {
        if (user == null || hasEmojiStatus(user)) {
            return null;
        }
        Badge badge = get(user);
        return badge == null ? null : badge.documentId;
    }

    /** Chat bubble sender name, left slot: the badge of a customised badge holder whose emoji status is shown. */
    public static long secondaryDocumentId(TLRPC.User user) {
        if (!hasEmojiStatus(user)) {
            return 0;
        }
        Badge badge = get(user);
        return useSecondarySlot(user, badge) ? badge.documentId : 0;
    }

    /** Chats list: puts the badge into the status slot when there is no emoji status. Returns true if it did. */
    public static boolean applyDialogStatus(int account, TLRPC.User user, AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status, boolean animated) {
        if (status == null || user == null || user.scam || user.fake || user.id == UserConfig.getInstance(account).getClientUserId() || hasEmojiStatus(user)) {
            return false;
        }
        Badge badge = get(user);
        if (badge == null) {
            return false;
        }
        status.center = org.telegram.messenger.LocaleController.isRTL;
        status.set(badge.documentId, animated);
        status.setParticles(false, animated);
        return true;
    }

    /** Search / profile lists: badge into the status drawable when there is no emoji status. */
    public static boolean setStatus(AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status, TLRPC.User user, TLRPC.Chat chat, boolean animated) {
        Badge badge;
        if (user != null) {
            badge = hasEmojiStatus(user) ? null : get(user);
        } else if (chat != null) {
            badge = DialogObject.getEmojiStatusDocumentId(chat.emoji_status) != 0 ? null : get(chat);
        } else {
            badge = null;
        }
        if (badge == null) {
            return false;
        }
        status.set(badge.documentId, animated);
        return true;
    }

    /** User list cells: badge as the right drawable of the name when there is no emoji status. */
    public static void applyUserCell(SimpleTextView name, AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status, TLRPC.User user, Theme.ResourcesProvider resourcesProvider) {
        if (name == null || status == null || user == null || hasEmojiStatus(user)) {
            return;
        }
        Badge badge = get(user);
        if (badge == null) {
            return;
        }
        status.set(badge.documentId, false);
        status.setColor(Theme.getColor(Theme.key_chats_verifiedBackground, resourcesProvider));
        name.setRightDrawable(status);
        name.setRightDrawableTopPadding(-AndroidUtilities.dp(0.5f));
    }

    /** Chat bubble status tap: shows the badge info instead of the premium sheet. Returns true if handled. */
    private static boolean bubbleBadgeDown;

    /**
     * ChatMessageCell: a tap on the badge drawn in the slot left of the sender name (where a bot verification mark goes;
     * used when the sender has an emoji status) shows the badge bulletin. {@code slot} is that drawable, its bounds
     * set while drawing, in the cell's coordinates.
     */
    public static boolean onBubbleBadgeTouch(android.view.MotionEvent ev, float x, float y, android.graphics.drawable.Drawable slot, TLRPC.User user, long slotId) {
        if (slot == null || user == null || slotId == 0 || slotId != secondaryDocumentId(user)) {
            bubbleBadgeDown = false;
            return false;
        }
        android.graphics.Rect b = slot.getBounds();
        int pad = org.telegram.messenger.AndroidUtilities.dp(6);
        boolean inside = !b.isEmpty() && x >= b.left - pad && x <= b.right + pad && y >= b.top - pad && y <= b.bottom + pad;
        switch (ev.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                bubbleBadgeDown = inside;
                return inside;
            case android.view.MotionEvent.ACTION_UP:
                if (bubbleBadgeDown) {
                    bubbleBadgeDown = false;
                    if (inside) {
                        showInfo(org.telegram.ui.LaunchActivity.getLastFragment(), user);
                    }
                    return true;
                }
                return false;
            case android.view.MotionEvent.ACTION_CANCEL:
                boolean was = bubbleBadgeDown;
                bubbleBadgeDown = false;
                return was;
            default:
                return bubbleBadgeDown;
        }
    }

    public static boolean onChatStatusPressed(BaseFragment fragment, TLRPC.User user) {
        if (chatStatus(user) == null) {
            return false;
        }
        showInfo(fragment, user);
        return true;
    }

    // ---------------------------------------------------------------- profile header

    private static final WeakHashMap<SimpleTextView, AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable> nameDrawables = new WeakHashMap<>();
    private static final WeakHashMap<SimpleTextView, View.OnClickListener> drawable2Clicks = new WeakHashMap<>();

    private static AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable nameDrawable(SimpleTextView view, int index) {
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable drawable = nameDrawables.get(view);
        if (drawable == null) {
            drawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(view, AndroidUtilities.dp(24),
                    index == 0 ? AnimatedEmojiDrawable.CACHE_TYPE_EMOJI_STATUS : AnimatedEmojiDrawable.CACHE_TYPE_KEYBOARD);
            final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable d = drawable;
            if (view.isAttachedToWindow()) {
                d.attach();
            }
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    d.attach();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    d.detach();
                }
            });
            nameDrawables.put(view, drawable);
        }
        return drawable;
    }

    /** The badge drawable of a profile name view, if one was created (for the editor's fly-in animation). */
    static AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable profileDrawable(SimpleTextView view) {
        return nameDrawables.get(view);
    }

    /**
     * Profile header name ({@code index} 0 = collapsed, 1 = expanded): with an emoji status the badge goes into the
     * second slot, otherwise it takes the status slot (instead of the premium star). Tapping it shows the caption,
     * or opens the editor on your own profile.
     */
    public static void onProfileName(BaseFragment fragment, SimpleTextView view, TLObject peer, int index, boolean copied) {
        if (view == null) {
            return;
        }
        drawable2Clicks.remove(view);
        if (copied) {
            return;
        }
        Badge badge = get(peer);
        if (badge == null) {
            return;
        }
        boolean hasStatus;
        if (peer instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) peer;
            hasStatus = !MessagesController.isSupportUser(user) && hasEmojiStatus(user);
        } else {
            hasStatus = DialogObject.getEmojiStatusDocumentId(((TLRPC.Chat) peer).emoji_status) != 0;
        }
        boolean animated = index == 1;
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable drawable = nameDrawable(view, index);
        drawable.set(badge.documentId, animated);
        drawable.setParticles(true, animated);
        View.OnClickListener click = v -> onBadgeTap(fragment, view, peer);
        if (hasStatus) {
            view.setRightDrawable2(drawable);
            drawable2Clicks.put(view, click);
        } else {
            if (view.getRightDrawable2() != null && view.getRightDrawable2() == fragment.getThemedDrawable(Theme.key_drawable_muteIconDrawable)) {
                view.setRightDrawable2(null);
            }
            view.setRightDrawable(drawable);
            view.setRightDrawableOutside(true);
            view.setRightDrawableOnClick(click);
        }
    }

    /** Colour of the profile badge, following the name colour like the bot verification mark. */
    public static void setProfileBadgeColor(SimpleTextView view, int color) {
        AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable drawable = view == null ? null : nameDrawables.get(view);
        if (drawable != null) {
            drawable.setColor(color);
        }
    }

    private static void onBadgeTap(BaseFragment fragment, SimpleTextView view, TLObject peer) {
        if (peer instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) peer;
            if (UserObject.isUserSelf(user) && canChangeBadge(user.id)) {
                RawBadgeEditor.show(fragment, view);
                return;
            }
        }
        showInfo(fragment, peer);
    }

    private static SimpleTextView touchView;
    private static float touchX, touchY;

    /** Taps on the badge in a name's second drawable slot ({@link SimpleTextView#onTouchEvent} hook). */
    public static boolean onNameTouch(SimpleTextView view, MotionEvent event) {
        View.OnClickListener click = drawable2Clicks.get(view);
        if (click == null || view.getRightDrawable2() == null) {
            if (touchView == view) {
                touchView = null;
            }
            return false;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            android.graphics.Rect b = view.getRightDrawable2().getBounds();
            int r = AndroidUtilities.dp(16);
            if (b.isEmpty() || Math.abs(event.getX() - b.centerX()) > r || Math.abs(event.getY() - b.centerY()) > r) {
                return false;
            }
            touchView = view;
            touchX = event.getX();
            touchY = event.getY();
            if (view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
        if (touchView != view) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(event.getX() - touchX) >= AndroidUtilities.touchSlop || Math.abs(event.getY() - touchY) >= AndroidUtilities.touchSlop) {
                touchView = null;
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchView = null;
            if (view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (action == MotionEvent.ACTION_UP) {
                click.onClick(view);
            }
            return true;
        }
        return true;
    }

    // ---------------------------------------------------------------- info bulletin

    /** Shows the badge with its caption (or a default line) in a bulletin. */
    public static void showInfo(BaseFragment fragment, TLObject peer) {
        Badge badge = get(peer);
        if (fragment == null || badge == null) {
            return;
        }
        CharSequence fallback;
        if (peer instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) peer;
            String name = UserObject.getFirstName(user);
            fallback = isDeveloper(user.id, TYPE_USER) ? name + " — разработчик exteraGram" : name + " поддерживает exteraGram";
        } else {
            TLRPC.Chat chat = (TLRPC.Chat) peer;
            fallback = isDeveloper(chat.id, TYPE_CHAT) ? chat.title + " — официальный канал exteraGram" : chat.title + " поддерживает exteraGram";
        }
        CharSequence text = badge.text != null ? linkUsernames(badge.text, fragment) : fallback;
        TLRPC.Document document = AnimatedEmojiDrawable.findDocument(fragment.getCurrentAccount(), badge.documentId);
        Bulletin bulletin;
        if (document != null) {
            bulletin = BulletinFactory.of(fragment).createEmojiBulletin(document, text);
        } else {
            bulletin = BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.info, text);
        }
        bulletin.wrapContent();
        bulletin.show();
    }

    /** Makes {@code @username} mentions in a caption clickable (exteraGram's LocaleUtils.formatWithUsernames). */
    static CharSequence linkUsernames(CharSequence text, BaseFragment fragment) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '@') {
                start = i;
                continue;
            }
            if (start == -1) {
                continue;
            }
            int end = i + 1;
            boolean last = end == text.length() || !(Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_');
            if (last) {
                if (end - start > 1) {
                    final String username = text.subSequence(start + 1, end).toString();
                    sb.setSpan(new URLSpanNoUnderline("@" + username) {
                        @Override
                        public void onClick(View widget) {
                            if (fragment != null) {
                                fragment.getMessagesController().openByUserName(username, fragment, 1);
                            }
                        }
                    }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                start = -1;
            }
        }
        return sb;
    }

    // ---------------------------------------------------------------- local edits

    /** After a successful edit: the own entry gets the new badge right away. */
    static void updateLocal(long userId, Badge badge) {
        Entry old = entries.get(userId);
        if (old == null) {
            return;
        }
        entries.put(userId, new Entry(old.type, old.status, badge, old.canChangeBadge));
        queue().postRunnable(RawBadges::saveCache);
        notifyChanged();
    }

    // ---------------------------------------------------------------- sync

    /** Called when the switch changes: redraw, and fetch if it was turned on. */
    public static void onSwitchChanged() {
        lastCheck = 0;
        if (enabled()) {
            maybeSync();
        }
        notifyChanged();
    }

    private static DispatchQueue queue() {
        if (queue == null) {
            synchronized (RawBadges.class) {
                if (queue == null) {
                    queue = new DispatchQueue("rawBadges");
                }
            }
        }
        return queue;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void maybeSync() {
        long now = SystemClock.elapsedRealtime();
        if (initStarted && now - lastCheck < CHECK_THROTTLE_MS) {
            return;
        }
        lastCheck = now;
        if (ApplicationLoader.applicationContext == null) {
            return;
        }
        final boolean first = !initStarted;
        initStarted = true;
        queue().postRunnable(() -> {
            if (first) {
                loadCache();
            }
            syncIfDue();
        });
    }

    private static void syncIfDue() {
        if (syncing || !enabled()) {
            return;
        }
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        long lastFailure = p.getLong(KEY_LAST_FAILURE, 0);
        if (Math.abs(now - lastFailure) < FAILURE_BACKOFF_MS) {
            return;
        }
        if (Math.abs(now - p.getLong(KEY_LAST_SYNC, 0)) < SYNC_INTERVAL_MS) {
            return;
        }
        syncing = true;
        try {
            boolean full = since == null || entries.isEmpty() || Math.abs(now - p.getLong(KEY_LAST_FULL, 0)) >= FULL_SYNC_INTERVAL_MS;
            boolean ok = fetch(full);
            SharedPreferences.Editor e = p.edit();
            if (ok) {
                e.putLong(KEY_LAST_SYNC, now).remove(KEY_LAST_FAILURE);
                if (full) {
                    e.putLong(KEY_LAST_FULL, now);
                }
            } else {
                e.putLong(KEY_LAST_FAILURE, now);
            }
            e.apply();
        } finally {
            syncing = false;
        }
    }

    /** One request. Returns false on a network / server error. */
    private static boolean fetch(boolean full) {
        HttpURLConnection connection = null;
        if (full && entries.isEmpty()) {
            etag = null;
        }
        try {
            String url = full ? BASE_URL + "profiles" : BASE_URL + "profiles/updates?since=" + URLEncoder.encode(since, "UTF-8");
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (etag != null) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                since = watermark(connection);
                saveCache();
                return true;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return false;
            }
            JSONArray array = new JSONArray(readBody(connection.getInputStream()));
            if (full) {
                ConcurrentHashMap<Long, Entry> fresh = new ConcurrentHashMap<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o != null && !o.optBoolean("deleted", false)) {
                        long id = readLong(o, "id");
                        if (id != 0) {
                            fresh.put(id, parseEntry(o));
                        }
                    }
                }
                entries.keySet().retainAll(fresh.keySet());
                entries.putAll(fresh);
            } else {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    long id = readLong(o, "id");
                    if (id == 0) {
                        continue;
                    }
                    if (o.optBoolean("deleted", false)) {
                        entries.remove(id);
                    } else {
                        entries.put(id, parseEntry(o));
                    }
                }
            }
            String newEtag = connection.getHeaderField("ETag");
            etag = newEtag;
            since = watermark(connection);
            saveCache();
            notifyChanged();
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Next {@code since}: the server's sync timestamp, else now (ISO-8601 UTC). */
    private static String watermark(HttpURLConnection connection) {
        String header = connection.getHeaderField(HEADER_SYNC_TIMESTAMP);
        if (!TextUtils.isEmpty(header)) {
            return header.trim();
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String readBody(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("badges response too large");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static long readLong(JSONObject o, String key) {
        Object value = o.opt(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignore) {
            }
        }
        return 0;
    }

    private static Entry parseEntry(JSONObject o) {
        int type = "CHAT".equalsIgnoreCase(o.optString("type")) ? TYPE_CHAT : TYPE_USER;
        String statusName = o.optString("status");
        int status = "DEVELOPER".equalsIgnoreCase(statusName) ? STATUS_DEVELOPER
                : "SUPPORTER".equalsIgnoreCase(statusName) ? STATUS_SUPPORTER : STATUS_DEFAULT;
        Badge badge = null;
        JSONObject b = o.optJSONObject("badge");
        if (b != null) {
            long documentId = readLong(b, "documentId");
            if (documentId != 0) {
                badge = new Badge(documentId, b.isNull("text") ? null : b.optString("text", null));
            }
        }
        return new Entry(type, status, badge, o.optBoolean("canChangeBadge", false));
    }

    // ---------------------------------------------------------------- cache file

    private static File cacheFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), CACHE_FILE);
    }

    private static void loadCache() {
        File file = cacheFile();
        if (!file.exists()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(readBody(new FileInputStream(file)));
            etag = root.isNull("etag") ? null : root.optString("etag", null);
            since = root.isNull("since") ? null : root.optString("since", null);
            JSONArray list = root.optJSONArray("profiles");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    long id = readLong(o, "id");
                    long documentId = readLong(o, "d");
                    Badge badge = documentId == 0 ? null : new Badge(documentId, o.isNull("x") ? null : o.optString("x", null));
                    entries.put(id, new Entry(o.optInt("t"), o.optInt("s"), badge, o.optBoolean("c")));
                }
            }
            if (!entries.isEmpty()) {
                notifyChanged();
            }
        } catch (Throwable t) {
            FileLog.e(t);
            entries.clear();
            etag = null;
            since = null;
        }
    }

    private static void saveCache() {
        try {
            JSONObject root = new JSONObject();
            root.put("etag", etag == null ? JSONObject.NULL : etag);
            root.put("since", since == null ? JSONObject.NULL : since);
            JSONArray list = new JSONArray();
            for (Map.Entry<Long, Entry> item : entries.entrySet()) {
                Entry e = item.getValue();
                JSONObject o = new JSONObject();
                o.put("id", (long) item.getKey());
                o.put("t", e.type);
                o.put("s", e.status);
                if (e.badge != null) {
                    o.put("d", e.badge.documentId);
                    if (e.badge.text != null) {
                        o.put("x", e.badge.text);
                    }
                }
                if (e.canChangeBadge) {
                    o.put("c", true);
                }
                list.put(o);
            }
            root.put("profiles", list);
            File file = cacheFile();
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Redraws names everywhere (chats list, bubbles, profiles listen to the emoji-status mask). */
    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
                }
            }
        });
    }
}
