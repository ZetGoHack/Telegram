package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.Stars.BotStarsController;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.Stars.StarsIntroActivity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Filter chips for the Stars / TON transaction history ({@code StarsIntroActivity.StarsTransactionsLayout}: the user's
 * Stars, TON and a bot's revenue screen). The chip row (Telegram's gift-filter {@link GiftSheet.Tabs}) sits under the
 * All / Incoming / Outgoing tabs, offers every kind, and filters every tab page client-side by {@link #kindOf transaction kind}.
 * <p>
 * Pagination: rows keep coming from the controllers' normal paging and pass through the same filter. While a filter is
 * on, a page keeps loading on its own until {@link #MAX_EMPTY_PAGES} pages in a row bring no matching rows; then the
 * loading placeholder is replaced by «Искать дальше», which resets the budget.
 * <p>
 * Hooks in StarsIntroActivity: {@link #attach} (layout constructor), {@link #fillPage} (Page.fillItems),
 * {@link #mayLoad} (Page's load runnable).
 */
public class RawStarsFilter implements NotificationCenter.NotificationCenterDelegate {

    public static final int KIND_ALL = -1;
    public static final int KIND_MEDIA = 0;
    public static final int KIND_REACTIONS = 1;
    public static final int KIND_GIFTS = 2;
    public static final int KIND_GIFT_RESALE = 3;
    public static final int KIND_SUBSCRIPTIONS = 4;
    public static final int KIND_PAID_MESSAGES = 5;
    public static final int KIND_BOTS = 6;
    public static final int KIND_GIVEAWAYS = 7;
    public static final int KIND_ADS = 8;
    public static final int KIND_AFFILIATE = 9;
    public static final int KIND_POSTS_SEARCH = 10;
    public static final int KIND_TOPUPS = 11;
    public static final int KIND_OTHER = 12;
    private static final int KINDS_COUNT = 13;

    private static final String[] LABELS = {
        "Покупка медиа",
        "Реакции",
        "Подарки",
        "Перепродажа подарков",
        "Подписки",
        "Платные сообщения",
        "Боты",
        "Розыгрыши",
        "Реклама",
        "Партнёрская программа",
        "Поиск постов",
        "Пополнения и вывод",
        "Другое",
    };

    /** Pages in a row (50 rows each) without a match before auto-loading stops. */
    private static final int MAX_EMPTY_PAGES = 6;
    /** After switching the filter, a page with fewer matches than this asks for the next page right away. */
    private static final int KICK_THRESHOLD = 20;

    private static final int FLAG_GIVEAWAY = 1 << 13;
    private static final int FLAG_STARREF_COMMISSION = 1 << 16;
    private static final int FLAG_STARREF_PEER = 1 << 17;

    public static String label(int kind) {
        return kind >= 0 && kind < LABELS.length ? LABELS[kind] : "Все";
    }

    /**
     * One primary kind per transaction; the first matching rule wins:
     * <ol>
     * <li>gift resale market or a gift offer (buying or selling) → {@link #KIND_GIFT_RESALE}</li>
     * <li>anything about a star gift (sent, received, converted, upgraded, prepaid upgrade, details removed, transfer
     *     of a unique gift, auction bid) or Premium gifted for Stars → {@link #KIND_GIFTS}</li>
     * <li>giveaway prize ({@code giveaway_post_id}) → {@link #KIND_GIVEAWAYS}</li>
     * <li>Stars received as a gift (from a user or Fragment) → {@link #KIND_GIFTS}</li>
     * <li>subscription fee → {@link #KIND_SUBSCRIPTIONS}</li>
     * <li>paid messages, live-story message/reaction fees → {@link #KIND_PAID_MESSAGES}</li>
     * <li>paid reactions → {@link #KIND_REACTIONS}</li>
     * <li>paid media ({@code extended_media}) → {@link #KIND_MEDIA}</li>
     * <li>affiliate commission ({@code starref_*}) → {@link #KIND_AFFILIATE}</li>
     * <li>Ads platform peer or ad revenue period → {@link #KIND_ADS}</li>
     * <li>paid posts search → {@link #KIND_POSTS_SEARCH}</li>
     * <li>App Store / Google Play / Fragment / @PremiumBot peer (top-ups, Fragment withdrawals) → {@link #KIND_TOPUPS}</li>
     * <li>paid broadcasts (floodskip, API peer), business-bot transfers, purchases from a bot (bot peer, or an invoice
     *     with a title / photo / payload) → {@link #KIND_BOTS}</li>
     * <li>everything else → {@link #KIND_OTHER}</li>
     * </ol>
     * Refunded / pending / failed is a status, not a kind: such rows stay in their kind.
     */
    public static int kindOf(int account, TL_stars.StarsTransaction t) {
        if (t == null) return KIND_OTHER;
        if (t.stargift_resale || t.offer) return KIND_GIFT_RESALE;
        if (t.stargift != null || t.stargift_upgrade || t.stargift_prepaid_upgrade || t.stargift_drop_original_details
                || t.stargift_auction_bid || t.premium_gift) {
            return KIND_GIFTS;
        }
        if (t.giveaway_post_id != 0 || (t.flags & FLAG_GIVEAWAY) != 0) return KIND_GIVEAWAYS;
        if (t.gift) return KIND_GIFTS;
        if (t.subscription) return KIND_SUBSCRIPTIONS;
        if (t.paid_message || t.phonegroup_message) return KIND_PAID_MESSAGES;
        if (t.reaction) return KIND_REACTIONS;
        if (t.extended_media != null && !t.extended_media.isEmpty()) return KIND_MEDIA;
        if (t.starref_commission_permille != 0 || t.starref_peer != null
                || (t.flags & (FLAG_STARREF_COMMISSION | FLAG_STARREF_PEER)) != 0) {
            return KIND_AFFILIATE;
        }
        final TL_stars.StarsTransactionPeer peer = t.peer;
        if (peer instanceof TL_stars.TL_starsTransactionPeerAds || t.ads_proceeds_from_date != 0 || t.ads_proceeds_to_date != 0) {
            return KIND_ADS;
        }
        if (t.posts_search) return KIND_POSTS_SEARCH;
        if (peer instanceof TL_stars.TL_starsTransactionPeerAppStore
                || peer instanceof TL_stars.TL_starsTransactionPeerPlayMarket
                || peer instanceof TL_stars.TL_starsTransactionPeerFragment
                || peer instanceof TL_stars.TL_starsTransactionPeerPremiumBot) {
            return KIND_TOPUPS;
        }
        if (t.floodskip || t.business_transfer || peer instanceof TL_stars.TL_starsTransactionPeerAPI) return KIND_BOTS;
        if (peer instanceof TL_stars.TL_starsTransactionPeer) {
            final long did = DialogObject.getPeerDialogId(peer.peer);
            if (did > 0) {
                final TLRPC.User user = MessagesController.getInstance(account).getUser(did);
                if (user != null && user.bot) return KIND_BOTS;
            }
            if (t.title != null || t.photo != null || t.bot_payload != null) return KIND_BOTS;
        }
        return KIND_OTHER;
    }

    // ---- hooks ----

    /** Hook: StarsTransactionsLayout constructor, after the tabs and their divider are added. Adds the chip row. */
    public static void attach(LinearLayout layout, int account, boolean ton, long botId, Theme.ResourcesProvider resourcesProvider) {
        final RawStarsFilter filter = new RawStarsFilter(layout.getContext(), account, ton, botId, resourcesProvider);
        layout.addView(filter.chips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));
        filter.updateChips();
    }

    /**
     * Hook: start of Page.fillItems. Returns true when a filter is on and the items were filled here (the caller
     * returns); false lets Telegram fill the page as usual.
     */
    public static boolean fillPage(View page, int type, ArrayList<UItem> items) {
        final RawStarsFilter filter = find(page);
        if (filter == null) return false;
        final PageState st = filter.state(page);
        st.type = type;
        if (filter.selected == KIND_ALL) return false;
        return filter.fill(page, st, items);
    }

    /** Hook: start of Page's load runnable. False stops auto-loading while a filter finds nothing in new pages. */
    public static boolean mayLoad(View page, int type) {
        final RawStarsFilter filter = find(page);
        if (filter == null || filter.selected == KIND_ALL) return true;
        if (filter.isLoading(type)) return true;
        final PageState st = filter.state(page);
        st.type = type;
        filter.account(st);
        return st.misses < MAX_EMPTY_PAGES;
    }

    // ---- instance ----

    private static class PageState {
        int type;
        int lastTotal = -1;
        int lastMatched;
        int misses;
        WeakReference<View> page;
        LinearLayout footer;
        TextView footerText;
        TextView footerButton;
    }

    private final int account;
    private final boolean ton;
    private final long botId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final GiftSheet.Tabs chips;
    private final Map<View, PageState> pages = new WeakHashMap<>();
    private int selected = KIND_ALL;
    private int[] chipKinds = new int[0];
    private final ArrayList<CharSequence> chipLabels = new ArrayList<>();

    private RawStarsFilter(Context context, int account, boolean ton, long botId, Theme.ResourcesProvider resourcesProvider) {
        this.account = account;
        this.ton = ton;
        this.botId = botId;
        this.resourcesProvider = resourcesProvider;
        chips = new GiftSheet.Tabs(context, false, resourcesProvider);
        chips.setTag(this);
        chips.setPadding(0, 0, 0, dp(5));
        chips.setVisibility(View.GONE);
        chips.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                NotificationCenter.getInstance(RawStarsFilter.this.account).addObserver(RawStarsFilter.this, NotificationCenter.starTransactionsLoaded);
                NotificationCenter.getInstance(RawStarsFilter.this.account).addObserver(RawStarsFilter.this, NotificationCenter.botStarsTransactionsLoaded);
                updateChips();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                NotificationCenter.getInstance(RawStarsFilter.this.account).removeObserver(RawStarsFilter.this, NotificationCenter.starTransactionsLoaded);
                NotificationCenter.getInstance(RawStarsFilter.this.account).removeObserver(RawStarsFilter.this, NotificationCenter.botStarsTransactionsLoaded);
            }
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.botStarsTransactionsLoaded) {
            if (botId == 0 || args.length == 0 || !(args[0] instanceof Long) || (long) args[0] != botId) return;
        } else if (id != NotificationCenter.starTransactionsLoaded || botId != 0) {
            return;
        }
        updateChips();
        // The pages rebuild on the same notification; once they have, keep a filtered page loading if it is short.
        AndroidUtilities.runOnUIThread(this::kickPages, 100);
    }

    private static RawStarsFilter find(View page) {
        // The chip row is a direct child of the transactions layout, and the filter is its tag.
        for (ViewParent p = page.getParent(); p != null; p = p.getParent()) {
            if (!(p instanceof ViewGroup)) break;
            final ViewGroup g = (ViewGroup) p;
            for (int i = 0; i < g.getChildCount(); ++i) {
                final View child = g.getChildAt(i);
                if (child instanceof GiftSheet.Tabs && child.getTag() instanceof RawStarsFilter) {
                    return (RawStarsFilter) child.getTag();
                }
            }
        }
        return null;
    }

    private PageState state(View page) {
        PageState st = pages.get(page);
        if (st == null) {
            st = new PageState();
            st.page = new WeakReference<>(page);
            pages.put(page, st);
        }
        return st;
    }

    private List<TL_stars.StarsTransaction> list(int type) {
        List<TL_stars.StarsTransaction> list;
        if (botId != 0) {
            list = BotStarsController.getInstance(account).getTransactions(botId, type);
        } else {
            final StarsController c = StarsController.getInstance(account, ton);
            list = type >= 0 && type < c.transactions.length ? c.transactions[type] : null;
        }
        return list != null ? list : Collections.emptyList();
    }

    private boolean fullyLoaded(int type) {
        return botId != 0
            ? BotStarsController.getInstance(account).didFullyLoadTransactions(botId, type)
            : StarsController.getInstance(account, ton).didFullyLoadTransactions(type);
    }

    private boolean isLoading(int type) {
        return botId != 0
            ? BotStarsController.getInstance(account).isLoadingTransactions(botId, type)
            : StarsController.getInstance(account, ton).isLoadingTransactions(type);
    }

    private void load(int type) {
        if (botId != 0) {
            BotStarsController.getInstance(account).loadTransactions(botId, type);
        } else {
            StarsController.getInstance(account, ton).loadTransactions(type);
        }
    }

    private int countMatched(int type) {
        int n = 0;
        for (TL_stars.StarsTransaction t : list(type)) {
            if (kindOf(account, t) == selected) n++;
        }
        return n;
    }

    /** Counts a page that arrived without new matches as a miss; a page with matches resets the budget. */
    private void account(PageState st) {
        final int total = list(st.type).size();
        if (total == st.lastTotal) return;
        final int matched = countMatched(st.type);
        if (st.lastTotal >= 0 && total > st.lastTotal) {
            st.misses = matched > st.lastMatched ? 0 : st.misses + 1;
        } else {
            st.misses = 0;
        }
        st.lastTotal = total;
        st.lastMatched = matched;
    }

    private void resetBudget(PageState st) {
        st.misses = 0;
        st.lastTotal = list(st.type).size();
        st.lastMatched = selected == KIND_ALL ? 0 : countMatched(st.type);
    }

    private boolean fill(View page, PageState st, ArrayList<UItem> items) {
        final boolean bot = botId != 0;
        final int type = st.type;
        int matched = 0;
        for (TL_stars.StarsTransaction t : list(type)) {
            if (kindOf(account, t) == selected) {
                items.add(StarsIntroActivity.StarsTransactionView.Factory.asTransaction(t, bot));
                matched++;
            }
        }
        account(st);
        if (!fullyLoaded(type)) {
            if (st.misses < MAX_EMPTY_PAGES) {
                items.add(UItem.asFlicker(items.size(), FlickerLoadingView.DIALOG_CELL_TYPE));
                items.add(UItem.asFlicker(items.size(), FlickerLoadingView.DIALOG_CELL_TYPE));
                items.add(UItem.asFlicker(items.size(), FlickerLoadingView.DIALOG_CELL_TYPE));
            } else {
                items.add(UItem.asCustom(footer(page, st,
                    matched == 0 ? "Среди загруженных операций нет «" + label(selected) + "»." : null, true), LayoutHelper.WRAP_CONTENT));
            }
        } else if (matched == 0) {
            items.add(UItem.asCustom(footer(page, st, "Нет операций «" + label(selected) + "».", false), LayoutHelper.WRAP_CONTENT));
        }
        return true;
    }

    private View footer(View page, PageState st, CharSequence text, boolean button) {
        if (st.footer == null) {
            final Context context = page.getContext();
            st.footer = new LinearLayout(context);
            st.footer.setOrientation(LinearLayout.VERTICAL);
            st.footer.setGravity(Gravity.CENTER_HORIZONTAL);
            st.footer.setPadding(dp(24), dp(20), dp(24), dp(16));

            st.footerText = new TextView(context);
            st.footerText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            st.footerText.setGravity(Gravity.CENTER);
            st.footer.addView(st.footerText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));

            st.footerButton = new TextView(context);
            st.footerButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            st.footerButton.setTypeface(AndroidUtilities.bold());
            st.footerButton.setGravity(Gravity.CENTER);
            st.footerButton.setPadding(dp(14), dp(8), dp(14), dp(8));
            st.footerButton.setText("Искать дальше");
            st.footer.addView(st.footerButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        }
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        final int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
        st.footerText.setTextColor(gray);
        st.footerText.setText(text);
        st.footerText.setVisibility(text != null ? View.VISIBLE : View.GONE);
        st.footerButton.setTextColor(blue);
        st.footerButton.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(blue, .12f), dp(8), dp(8)));
        st.footerButton.setVisibility(button ? View.VISIBLE : View.GONE);
        st.footerButton.setOnClickListener(v -> {
            resetBudget(st);
            final View p = st.page.get();
            if (p != null) refresh(p);
            load(st.type);
        });
        return st.footer;
    }

    // ---- chips ----

    private void updateChips() {
        // every kind is offered from the start: one missing from the loaded pages is searched for further down the
        // history when picked, so the chips don't change while pages load
        final boolean[] present = new boolean[KINDS_COUNT];
        java.util.Arrays.fill(present, true);
        int count = 0;
        for (boolean p : present) if (p) count++;
        final int[] kinds = new int[count];
        final ArrayList<CharSequence> labels = new ArrayList<>(count + 1);
        labels.add(label(KIND_ALL));
        int i = 0;
        for (int k = 0; k < KINDS_COUNT; ++k) {
            if (present[k]) {
                kinds[i++] = k;
                labels.add(LABELS[k]);
            }
        }
        chipKinds = kinds;
        chips.setVisibility(count >= 2 || selected != KIND_ALL ? View.VISIBLE : View.GONE);

        int selectedIndex = 0;
        for (int j = 0; j < kinds.length; ++j) {
            if (kinds[j] == selected) selectedIndex = j + 1;
        }
        // Tabs.set only relabels when the number of chips changes; rebuild it when the labels differ.
        if (!labels.equals(chipLabels) && labels.size() == chipLabels.size()) {
            chips.set(1, new ArrayList<>(), 0, null);
        }
        chipLabels.clear();
        chipLabels.addAll(labels);
        chips.set(1, labels, selectedIndex, this::onChipSelected);
        // Tabs.set only invalidates: chips added later stay outside the measured width until the next layout
        chips.requestLayout();
        for (int c = 0; c < chips.getChildCount(); c++) {
            chips.getChildAt(c).requestLayout();
        }
    }

    private void onChipSelected(int index) {
        final int kind = index <= 0 || index - 1 >= chipKinds.length ? KIND_ALL : chipKinds[index - 1];
        if (kind == selected) return;
        selected = kind;
        updateChips();
        for (Map.Entry<View, PageState> e : new ArrayList<>(pages.entrySet())) {
            final View page = e.getKey();
            if (page == null) continue;
            resetBudget(e.getValue());
            refresh(page);
        }
        AndroidUtilities.runOnUIThread(this::kickPages, 100);
    }

    private static UniversalRecyclerView listOf(View page) {
        if (page instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) page;
            for (int i = 0; i < g.getChildCount(); ++i) {
                if (g.getChildAt(i) instanceof UniversalRecyclerView) return (UniversalRecyclerView) g.getChildAt(i);
            }
        }
        return null;
    }

    private void refresh(View page) {
        final UniversalRecyclerView list = listOf(page);
        if (list != null && list.adapter != null) list.adapter.update(true);
    }

    /** Asks for the next page on filtered pages that show too little (short list or the loading placeholder in view). */
    private void kickPages() {
        if (selected == KIND_ALL) return;
        for (Map.Entry<View, PageState> e : new ArrayList<>(pages.entrySet())) {
            final View page = e.getKey();
            final PageState st = e.getValue();
            if (page == null || !page.isAttachedToWindow() || fullyLoaded(st.type) || isLoading(st.type)) continue;
            final UniversalRecyclerView list = listOf(page);
            boolean needMore = st.lastMatched < KICK_THRESHOLD;
            if (list != null && !needMore) {
                needMore = !list.canScrollVertically(1);
                for (int i = 0; !needMore && i < list.getChildCount(); ++i) {
                    needMore = list.getChildAt(i) instanceof FlickerLoadingView;
                }
            }
            if (needMore && mayLoad(page, st.type)) {
                load(st.type);
            }
        }
    }
}
