package org.telegram.rawgram;

import android.app.Activity;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ProfileActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Breakdown of a message's reactions: every TL_reactionCount (emoji / custom emoji document / paid stars),
 * recent reactors and the paid top. The button on a reaction opens everyone who put it (messages.getMessageReactionsList,
 * paged) in a second sheet of this class. Tap on a reaction copies its id, long press opens its raw.
 */
public class RawReactionsSheet extends BottomSheet {

    private final BaseFragment fragment;
    private final int account;
    private final Theme.ResourcesProvider rp;
    private final LinearLayout list;
    private final MessageObject message;

    /** Set on the per-reaction sheet: dismissed together with it when a profile opens. */
    private RawReactionsSheet parent;

    /** Who put one reaction: rows go into allList, pages of the server answer are kept for «Raw списка». */
    private static final int ALL_PAGE = 100;
    private TLRPC.Reaction filter;
    private String query = "";
    private TextView allEmpty;
    private LinearLayout allList;
    private TextView allHeader, allMore;
    private final ArrayList<TLRPC.TL_messages_messageReactionsList> allPages = new ArrayList<>();
    private String allOffset;
    private boolean allLoading;
    private int allShown;

    public RawReactionsSheet(BaseFragment fragment, int account, MessageObject message, Theme.ResourcesProvider rp) {
        super(fragment.getParentActivity(), false, rp);
        this.fragment = fragment;
        this.account = account;
        this.rp = rp;
        this.message = message;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));
        Context context = getContext();
        TLRPC.MessageReactions reactions = message.messageOwner.reactions;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText("Реакции · #" + message.getId());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        subtitle.setText(summary(reactions));
        root.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 8));

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8), 0);
        actionsScroll.addView(actions);
        root.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        chip(actions, "Raw", () -> new RawObjectSheet(getContext(), account, "messageOwner.reactions", reactions, rp).show());

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.6f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (reactions != null) {
            if (reactions.results != null && !reactions.results.isEmpty()) {
                section("Реакции");
                for (TLRPC.ReactionCount rc : reactions.results) {
                    addReaction(rc);
                }
            }
            if (reactions.recent_reactions != null && !reactions.recent_reactions.isEmpty()) {
                section("Недавние (" + reactions.recent_reactions.size() + ")");
                for (TLRPC.MessagePeerReaction r : reactions.recent_reactions) {
                    addRecent(list, r);
                }
            }
            if (reactions.top_reactors != null && !reactions.top_reactors.isEmpty()) {
                section("Топ по звёздам (" + reactions.top_reactors.size() + ")");
                for (TLRPC.MessageReactor r : reactions.top_reactors) {
                    addReactor(r);
                }
            }
        }
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        hint.setText("Нажатие на реакцию копирует id / эмодзи, долгое — raw, кнопка справа — кто её поставил. Нажатие на человека открывает профиль, долгое — raw.");
        list.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 0));

        setCustomView(root);
        RawMotion.reveal(root);
    }

    /** Everyone who put {@code rc.reaction} on the message, loaded page by page. */
    private RawReactionsSheet(RawReactionsSheet parent, TLRPC.ReactionCount rc) {
        super(parent.fragment.getParentActivity(), false, parent.rp);
        this.parent = parent;
        this.fragment = parent.fragment;
        this.account = parent.account;
        this.rp = parent.rp;
        this.message = parent.message;
        this.filter = rc.reaction;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));
        Context context = getContext();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView emoji = emojiView(20);
        bindReaction(rc.reaction, emoji, null, false);
        titleRow.addView(emoji, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText("Поставили · #" + message.getId());
        titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        root.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 10, 16, 0));

        allHeader = new TextView(context);
        allHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        allHeader.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        allHeader.setText(shortReaction(rc.reaction) + " · ×" + rc.count);
        root.addView(allHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 8));

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8), 0);
        actionsScroll.addView(actions);
        root.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        chip(actions, "Raw списка", this::openAllRaw);
        chip(actions, "Raw реакции", () -> openRaw(rc, rc.reaction));

        FrameLayout search = new FrameLayout(context);
        search.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18), getThemedColor(Theme.key_dialogSearchBackground)));
        ImageView searchIcon = new ImageView(context);
        searchIcon.setScaleType(ImageView.ScaleType.CENTER);
        searchIcon.setImageResource(R.drawable.smiles_inputsearch);
        searchIcon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogSearchIcon), PorterDuff.Mode.SRC_IN));
        search.addView(searchIcon, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        EditTextBoldCursor searchEdit = new EditTextBoldCursor(context);
        searchEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        searchEdit.setHintTextColor(getThemedColor(Theme.key_dialogSearchHint));
        searchEdit.setTextColor(getThemedColor(Theme.key_dialogSearchText));
        searchEdit.setCursorColor(getThemedColor(Theme.key_featuredStickers_addButton));
        searchEdit.setCursorSize(AndroidUtilities.dp(20));
        searchEdit.setCursorWidth(1.5f);
        searchEdit.setBackground(null);
        searchEdit.setPadding(0, 0, 0, 0);
        searchEdit.setSingleLine(true);
        searchEdit.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchEdit.setHint("Имя или юзернейм");
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applySearch(s.toString());
            }
        });
        search.addView(searchEdit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.CENTER_VERTICAL, 36, 0, 12, 0));
        root.addView(search, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 14, 0, 14, 8));

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.6f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        allList = new LinearLayout(context);
        allList.setOrientation(LinearLayout.VERTICAL);
        list.addView(allList, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        allEmpty = new TextView(context);
        allEmpty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        allEmpty.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        allEmpty.setGravity(Gravity.CENTER);
        allEmpty.setText("Среди загруженных никого");
        allEmpty.setVisibility(View.GONE);
        list.addView(allEmpty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 6, 16, 0));
        allMore = new TextView(context);
        allMore.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        allMore.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        allMore.setGravity(Gravity.CENTER);
        allMore.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(10));
        allMore.setOnClickListener(v -> loadAll());
        list.addView(allMore, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        hint.setText("Нажатие открывает профиль, долгое — raw реакции.");
        list.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 0));

        setCustomView(root);
        RawMotion.reveal(root);
        TLRPC.MessageReactions reactions = message.messageOwner.reactions;
        if (reactions == null || reactions.can_see_list) {
            loadAll();
        } else {
            // channels hide the list; the request is still there to try by hand
            allMore.setText("Список скрыт (can_see_list = false). Нажми, чтобы всё равно запросить");
        }
    }

    private static String summary(TLRPC.MessageReactions reactions) {
        if (reactions == null) {
            return "реакций нет";
        }
        int total = 0;
        int kinds = reactions.results != null ? reactions.results.size() : 0;
        if (reactions.results != null) {
            for (TLRPC.ReactionCount rc : reactions.results) {
                total += rc.count;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("всего ").append(total).append(" · видов ").append(kinds);
        if (reactions.min) sb.append(" · min");
        if (reactions.can_see_list) sb.append(" · can_see_list");
        if (reactions.reactions_as_tags) sb.append(" · as_tags");
        sb.append(" · ").append(TLDumper.typeName(reactions));
        return sb.toString();
    }

    // ---- rows ----

    private void addReaction(TLRPC.ReactionCount rc) {
        LinearLayout card = card();
        TextView emoji = emojiView(28);
        card.addView(emoji, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView head = headView();
        TextView details = detailsView();
        texts.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        boolean paid = rc.reaction instanceof TLRPC.TL_reactionPaid;
        if (!paid) {
            // who put this reaction (paid ones are listed in «Топ по звёздам»)
            ImageView who = new ImageView(getContext());
            who.setImageResource(R.drawable.msg_groups);
            who.setScaleType(ImageView.ScaleType.FIT_CENTER);
            who.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));
            int accent = getThemedColor(Theme.key_featuredStickers_addButton);
            who.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            who.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(15), Theme.multAlpha(accent, 0.12f), Theme.multAlpha(accent, 0.24f)));
            who.setContentDescription("Кто поставил");
            who.setOnClickListener(v -> new RawReactionsSheet(this, rc).show());
            card.addView(who, LayoutHelper.createLinear(30, 30, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }
        String head1 = (paid ? "⭐ " + rc.count + " звёзд" : "×" + rc.count)
                + (rc.chosen ? "  · выбрана мной (chosen_order " + rc.chosen_order + ")" : "");
        head.setText(head1);
        if (rc.chosen) {
            head.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        }
        bindReaction(rc.reaction, emoji, details, true);
        String copyValue = copyValue(rc.reaction);
        card.setOnClickListener(v -> {
            copy(copyValue, "Скопировано: " + copyValue);
            RawMotion.copied(v);
        });
        card.setOnLongClickListener(v -> {
            openRaw(rc, rc.reaction);
            return true;
        });
        list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
    }

    private void addRecent(LinearLayout parent, TLRPC.MessagePeerReaction r) {
        LinearLayout card = card();
        RawPeers.Info info = RawPeers.resolve(account, r.peer_id);
        TextView emoji = null;
        if (filter != null) {
            BackupImageView avatar = new BackupImageView(getContext());
            avatar.setRoundRadius(AndroidUtilities.dp(20));
            TLObject peer = peerObject(r.peer_id);
            AvatarDrawable drawable = new AvatarDrawable();
            if (peer != null) {
                drawable.setInfo(account, peer);
                avatar.setForUserOrChat(peer, drawable);
            } else {
                avatar.setImageDrawable(drawable);
            }
            card.addView(avatar, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        } else {
            emoji = emojiView(22);
            card.addView(emoji, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        }
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView head = headView();
        TextView details = detailsView();
        texts.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        head.setText(info != null ? info.format() : "?");
        boolean mine = r.peer_id != null && DialogObject.getPeerDialogId(r.peer_id) == UserConfig.getInstance(account).getClientUserId();
        StringBuilder sb = new StringBuilder();
        if (r.date != 0) sb.append(date(r.date));
        if (r.big) sb.append(sb.length() > 0 ? " · " : "").append("big");
        if (r.unread) sb.append(sb.length() > 0 ? " · " : "").append("unread");
        if (mine) sb.append(sb.length() > 0 ? " · " : "").append("моя");
        if (emoji != null) {
            sb.append(sb.length() > 0 ? "\n" : "").append(shortReaction(r.reaction));
            bindReaction(r.reaction, emoji, null, false);
        }
        details.setText(sb);
        card.setOnClickListener(v -> openProfile(info));
        card.setOnLongClickListener(v -> {
            openRaw(r, r.reaction);
            return true;
        });
        parent.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
        if (parent == allList) {
            String key = info == null ? "" : (info.name + " " + info.username + " @" + info.username + " " + info.id);
            card.setTag(key.toLowerCase(Locale.ROOT));
            card.setVisibility(matches(card) ? View.VISIBLE : View.GONE);
        }
    }

    private TLObject peerObject(TLRPC.Peer peer) {
        if (peer == null) {
            return null;
        }
        long id = DialogObject.getPeerDialogId(peer);
        MessagesController mc = MessagesController.getInstance(account);
        return id >= 0 ? mc.getUser(id) : mc.getChat(-id);
    }

    private boolean matches(View card) {
        return query.isEmpty() || String.valueOf(card.getTag()).contains(query);
    }

    /** Hides the loaded rows that don't match the name / @username / id typed into the search field. */
    private void applySearch(String text) {
        query = text.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (int i = 0; i < allList.getChildCount(); i++) {
            View card = allList.getChildAt(i);
            boolean visible = matches(card);
            card.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) shown++;
        }
        allEmpty.setVisibility(!query.isEmpty() && shown == 0 && allList.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void addReactor(TLRPC.MessageReactor r) {
        LinearLayout card = card();
        TextView emoji = emojiView(22);
        emoji.setText("⭐");
        card.addView(emoji, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView head = headView();
        TextView details = detailsView();
        texts.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        texts.addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        RawPeers.Info info = r.peer_id != null ? RawPeers.resolve(account, r.peer_id) : null;
        head.setText(info != null ? info.format() : "аноним");
        StringBuilder sb = new StringBuilder();
        sb.append(r.count).append(" звёзд");
        if (r.top) sb.append(" · top");
        if (r.my) sb.append(" · my");
        if (r.anonymous) sb.append(" · anonymous");
        details.setText(sb);
        card.setOnClickListener(v -> {
            if (info != null) {
                openProfile(info);
            } else {
                copy(Integer.toString(r.count), "Скопировано: " + r.count);
                RawMotion.copied(v);
            }
        });
        card.setOnLongClickListener(v -> {
            openRaw(r, null);
            return true;
        });
        list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 6));
    }

    // ---- who put one reaction ----

    private void loadAll() {
        if (allLoading || (!allPages.isEmpty() && allOffset == null)) {
            return;
        }
        allLoading = true;
        allMore.setText("Загрузка…");
        TLRPC.TL_messages_getMessageReactionsList req = new TLRPC.TL_messages_getMessageReactionsList();
        req.peer = MessagesController.getInstance(account).getInputPeer(message.getDialogId());
        req.id = message.getId();
        req.limit = ALL_PAGE;
        req.reaction = filter;
        req.flags |= 1;
        if (allOffset != null) {
            req.offset = allOffset;
            req.flags |= 2;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            allLoading = false;
            if (!(response instanceof TLRPC.TL_messages_messageReactionsList)) {
                allMore.setText("Ошибка: " + (error != null ? error.code + " " + error.text : "пустой ответ") + ". Нажми, чтобы повторить");
                allMore.setTextColor(getThemedColor(Theme.key_text_RedRegular));
                return;
            }
            allMore.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
            TLRPC.TL_messages_messageReactionsList res = (TLRPC.TL_messages_messageReactionsList) response;
            MessagesController.getInstance(account).putUsers(res.users, false);
            MessagesController.getInstance(account).putChats(res.chats, false);
            allPages.add(res);
            allOffset = TextUtils.isEmpty(res.next_offset) ? null : res.next_offset;
            for (TLRPC.MessagePeerReaction r : res.reactions) {
                addRecent(allList, r);
            }
            allShown += res.reactions.size();
            applySearch(query);
            allHeader.setText(shortReaction(filter) + " · загружено " + allShown + " из " + res.count);
            allMore.setText(allOffset != null ? "Загрузить ещё" : allShown == 0 ? "Никого" : "Это все");
        }));
    }

    /** Every loaded page of messages.getMessageReactionsList as it came from the server. */
    private void openAllRaw() {
        if (allPages.isEmpty()) {
            RawNotify.show(this, R.drawable.msg_info, allLoading ? "Список ещё загружается" : "Список не загружен");
            return;
        }
        Object object = allPages.size() == 1 ? allPages.get(0) : new ArrayList<>(allPages);
        new RawObjectSheet(getContext(), account, "messages.getMessageReactionsList", object, rp).show();
    }

    /** Emoji visual + (optionally) the detail lines of one reaction; custom emoji documents are fetched if not cached. */
    private void bindReaction(TLRPC.Reaction reaction, TextView emoji, TextView details, boolean full) {
        Paint.FontMetricsInt fm = emoji.getPaint().getFontMetricsInt();
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            String emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
            emoji.setText(Emoji.replaceEmoji(emoticon != null ? emoticon : "?", fm, false));
            if (details != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("emoji ").append(emoticon).append("  ").append(codepoints(emoticon));
                TLRPC.TL_availableReaction available = MediaDataController.getInstance(account).getReactionsMap().get(emoticon);
                if (available != null) {
                    sb.append("\n").append(available.title);
                    if (available.premium) sb.append(" · premium");
                    if (available.inactive) sb.append(" · inactive");
                } else {
                    sb.append("\nнет в availableReactions");
                }
                details.setText(sb);
            }
        } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            long documentId = ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
            TLRPC.Document doc = AnimatedEmojiDrawable.findDocument(account, documentId);
            setCustomEmoji(emoji, documentId, doc);
            if (details != null) {
                details.setText(customEmojiDetails(documentId, doc));
            }
            if (doc == null) {
                // not in the emoji cache yet: ask the fetcher (local db first, then server) and refresh this row
                AnimatedEmojiDrawable.getDocumentFetcher(account).fetchDocument(documentId, loaded -> AndroidUtilities.runOnUIThread(() -> {
                    if (loaded == null) return;
                    setCustomEmoji(emoji, documentId, loaded);
                    if (details != null) {
                        details.setText(customEmojiDetails(documentId, loaded));
                    }
                }));
            }
        } else if (reaction instanceof TLRPC.TL_reactionPaid) {
            emoji.setText("⭐");
            if (details != null) details.setText("paid (TL_reactionPaid) · count = звёзды");
        } else {
            emoji.setText("?");
            if (details != null) details.setText(TLDumper.typeName(reaction));
        }
    }

    private void setCustomEmoji(TextView emoji, long documentId, TLRPC.Document doc) {
        Paint.FontMetricsInt fm = emoji.getPaint().getFontMetricsInt();
        String alt = doc != null ? alt(doc) : null;
        SpannableStringBuilder ssb = new SpannableStringBuilder(TextUtils.isEmpty(alt) ? "x" : alt);
        AnimatedEmojiSpan span = doc != null ? new AnimatedEmojiSpan(doc, 1f, fm) : new AnimatedEmojiSpan(documentId, 1f, fm);
        ssb.setSpan(span, 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        emoji.setText(ssb);
    }

    private String customEmojiDetails(long documentId, TLRPC.Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("custom_emoji ").append(documentId);
        if (doc == null) {
            sb.append("\nдокумент не в кеше, загружаю…");
            return sb.toString();
        }
        String alt = alt(doc);
        if (!TextUtils.isEmpty(alt)) sb.append("\nalt ").append(alt);
        sb.append("\n").append(mediaKind(doc.mime_type));
        if (doc.size > 0) sb.append(" · ").append(AndroidUtilities.formatFileSize(doc.size)).append(" (").append(doc.size).append(" Б)");
        TLRPC.InputStickerSet input = stickerSet(doc);
        if (input != null) {
            TLRPC.TL_messages_stickerSet cached = MediaDataController.getInstance(account).getStickerSet(input, true);
            TLRPC.StickerSet set = cached != null ? cached.set : null;
            String shortName = set != null ? set.short_name : input.short_name;
            long setId = set != null ? set.id : input.id;
            sb.append("\nнабор ");
            if (set != null && !TextUtils.isEmpty(set.title)) sb.append(set.title);
            if (!TextUtils.isEmpty(shortName)) sb.append(set != null && !TextUtils.isEmpty(set.title) ? " · " : "").append(shortName);
            if (setId != 0) sb.append(" · id ").append(setId);
        }
        return sb.toString();
    }

    // ---- helpers ----

    private static String alt(TLRPC.Document doc) {
        for (TLRPC.DocumentAttribute a : doc.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeCustomEmoji || a instanceof TLRPC.TL_documentAttributeSticker) {
                return a.alt;
            }
        }
        return null;
    }

    private static TLRPC.InputStickerSet stickerSet(TLRPC.Document doc) {
        for (TLRPC.DocumentAttribute a : doc.attributes) {
            if ((a instanceof TLRPC.TL_documentAttributeCustomEmoji || a instanceof TLRPC.TL_documentAttributeSticker)
                    && a.stickerset != null && !(a.stickerset instanceof TLRPC.TL_inputStickerSetEmpty)) {
                return a.stickerset;
            }
        }
        return null;
    }

    private static String mediaKind(String mime) {
        if (mime == null) return "mime ?";
        switch (mime) {
            case "application/x-tgsticker":
                return mime + " (tgs, Lottie)";
            case "video/webm":
                return mime + " (webm, видео)";
            case "image/webp":
                return mime + " (webp, статичный)";
            default:
                return mime;
        }
    }

    private static String copyValue(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            return String.valueOf(((TLRPC.TL_reactionEmoji) reaction).emoticon);
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            return Long.toString(((TLRPC.TL_reactionCustomEmoji) reaction).document_id);
        }
        return reaction instanceof TLRPC.TL_reactionPaid ? "paid" : TLDumper.typeName(reaction);
    }

    private static String shortReaction(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            return "emoji " + ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            return "custom_emoji " + ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
        }
        return reaction instanceof TLRPC.TL_reactionPaid ? "paid" : TLDumper.typeName(reaction);
    }

    /** "U+2764 U+FE0F": tells visually identical emoji apart (variation selectors, skin tones). */
    static String codepoints(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "U+%04X", cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private void openRaw(Object object, TLRPC.Reaction reaction) {
        RawObjectSheet sheet = new RawObjectSheet(getContext(), account, "Реакция · raw", object, rp);
        sheet.addObjectTab(TLDumper.typeName(object), () -> sheet.setObject(null, object));
        if (reaction != null && reaction != object) {
            sheet.addObjectTab("Reaction", () -> sheet.setObject(null, reaction));
        }
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            TLRPC.Document doc = AnimatedEmojiDrawable.findDocument(account, ((TLRPC.TL_reactionCustomEmoji) reaction).document_id);
            if (doc != null) {
                sheet.addObjectTab("Document", () -> sheet.setObject(null, doc));
                TLRPC.InputStickerSet input = stickerSet(doc);
                if (input != null) {
                    TLRPC.TL_messages_stickerSet cached = MediaDataController.getInstance(account).getStickerSet(input, true);
                    if (cached != null) {
                        sheet.addObjectTab("StickerSet", () -> sheet.setObject(null, cached.set));
                    }
                    sheet.addAction("Открыть набор", v -> {
                        Activity activity = fragment.getParentActivity();
                        if (activity == null) return;
                        sheet.dismiss();
                        dismiss();
                        ArrayList<TLRPC.InputStickerSet> sets = new ArrayList<>();
                        sets.add(input);
                        fragment.showDialog(new EmojiPacksAlert(fragment, activity, rp, sets));
                    });
                }
            }
        } else if (reaction instanceof TLRPC.TL_reactionEmoji) {
            TLRPC.TL_availableReaction available = MediaDataController.getInstance(account).getReactionsMap().get(((TLRPC.TL_reactionEmoji) reaction).emoticon);
            if (available != null) {
                sheet.addObjectTab("AvailableReaction", () -> sheet.setObject(null, available));
            }
        }
        sheet.show();
    }

    private void openProfile(RawPeers.Info info) {
        if (info == null) {
            return;
        }
        if (!info.isCached) {
            copy(info.id, "Нет в кеше — ID скопирован");
            return;
        }
        dismiss();
        if (parent != null) {
            parent.dismiss();
        }
        fragment.presentFragment(ProfileActivity.of(info.dialogId));
    }

    private void copy(String value, String toast) {
        AndroidUtilities.addToClipboard(value);
        RawNotify.show(this, R.drawable.msg_copy, toast);
    }

    private static String date(int unix) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date(unix * 1000L));
    }

    // ---- views ----

    private void section(String text) {
        TextView header = new TextView(getContext());
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        list.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 6, 16, 6));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        int base = getThemedColor(Theme.key_dialogTextBlack);
        card.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), Theme.multAlpha(base, 0.04f), Theme.multAlpha(base, 0.12f)));
        RawMotion.pressable(card, 0.97f);
        return card;
    }

    private TextView emojiView(int sizeDp) {
        AnimatedEmojiSpan.TextViewEmojis view = new AnimatedEmojiSpan.TextViewEmojis(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        return view;
    }

    private TextView headView() {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private TextView detailsView() {
        TextView view = new TextView(getContext());
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        return view;
    }

    private void chip(LinearLayout row, String text, Runnable onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        chip.setTextColor(accent);
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), Theme.multAlpha(accent, 0.12f), Theme.multAlpha(accent, 0.24f)));
        chip.setOnClickListener(v -> onClick.run());
        row.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
    }
}
