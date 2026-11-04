package org.telegram.rawgram.reactions;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.LayoutTransition;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AdjustPanLayoutHelper;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.SlideIntChooseView;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.BackSpaceButtonView;
import org.telegram.ui.Components.Reactions.ChatCustomReactionsEditActivity;
import org.telegram.ui.Components.Reactions.CustomReactionEditText;
import org.telegram.ui.Components.Reactions.ReactionsUtils;
import org.telegram.ui.Components.Reactions.UpdateReactionsButton;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.SelectAnimatedEmojiDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Channel reactions editor: the stock editor (ChatCustomReactionsEditActivity) plus copy / cut / paste of the
 * reaction list as text, a per-channel backup of the last saved list that comes back when reactions are turned
 * on again, and an apply button that only shows when something changed.
 * <p>
 * Ported from exteraGram (com.exteragram.messenger.reactions.ChannelReactionsEditActivity), GPLv3.
 * <p>
 * Turning reactions off is a change like any other: it is saved with the apply button or the unsaved-changes
 * dialog (the stock editor saved it silently on close).
 */
public class RawChannelReactionsEditActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int SELECT_TYPE_ALL = ChatCustomReactionsEditActivity.SELECT_TYPE_ALL;
    private static final int SELECT_TYPE_SOME = ChatCustomReactionsEditActivity.SELECT_TYPE_SOME;
    private static final int SELECT_TYPE_NONE = ChatCustomReactionsEditActivity.SELECT_TYPE_NONE;
    private static final long PAID_ID = RawReactionsEditHelper.PAID_ID;

    /** The editor that opens from the channel's «Реакции» row. */
    public static BaseFragment create(long chatId, TLRPC.ChatFull info) {
        return new RawChannelReactionsEditActivity(chatId, info);
    }

    /** Both this editor and the stock one (boost flow in LimitReachedBottomSheet). */
    public static boolean isEditor(BaseFragment fragment) {
        return fragment instanceof RawChannelReactionsEditActivity || fragment instanceof ChatCustomReactionsEditActivity;
    }

    private final long chatId;
    private final TLRPC.ChatFull info;
    private final ArrayList<TLRPC.TL_availableReaction> allAvailableReactions = new ArrayList<>();
    private final LinkedHashMap<Long, AnimatedEmojiSpan> selectedEmojisMap = new LinkedHashMap<>();
    private final ArrayList<Long> selectedEmojisIds = new ArrayList<>();
    private final RawReactionsEditHelper helper;
    private final int maxReactionsCount;
    private final Runnable checkAfterFastDeleteRunnable = () -> checkMaxCustomReactions(false);

    private UpdateReactionsButton actionButton;
    private FrameLayout actionButtonContainer;
    private boolean actionButtonShown = true;
    private boolean backupPaid;
    private TL_stories.TL_premium_boostsStatus boostsStatus;
    private FrameLayout bottomDialogLayout;
    private CustomReactionEditText editText;
    private boolean emojiKeyboardVisible;
    private TextCheckCell enableReactionsCell;
    private boolean initialPaid;
    private int initialReactionsCount;
    private Set<Long> initialSelectedIds = new HashSet<>();
    private int initialType = SELECT_TYPE_NONE;
    private boolean paid;
    private TextCheckCell paidCheckCell;
    private boolean paused;
    private int reactionsCount;
    private SectionsScrollView scrollView;
    private SelectAnimatedEmojiDialog selectAnimatedEmojiDialog;
    private int selectedCustomReactions;
    private int selectedType = -1;
    private LinearLayout switchLayout;

    public RawChannelReactionsEditActivity(long chatId, TLRPC.ChatFull info) {
        super();
        this.chatId = chatId;
        this.info = info;
        this.helper = new RawReactionsEditHelper(currentAccount, chatId, allAvailableReactions);
        this.maxReactionsCount = getMessagesController().boostsChannelLevelMax;
    }

    private boolean isStarEnabled() {
        return paidCheckCell != null && paidCheckCell.isChecked();
    }

    @Override
    public boolean onFragmentCreate() {
        if (info == null) {
            return false;
        }
        if (getMessagesController().getChat(chatId) == null) {
            TLRPC.Chat chat = getMessagesStorage().getChatSync(chatId);
            if (chat == null) {
                return false;
            }
            getMessagesController().putChat(chat, true);
        }
        getMessagesController().getBoostsController().getBoostsStats(-chatId, status -> {
            boostsStatus = status;
            if (!selectedEmojisMap.keySet().equals(initialSelectedIds)) {
                checkMaxCustomReactions(false);
            }
        });
        allAvailableReactions.addAll(getMediaDataController().getEnabledReactionsList());
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
        getNotificationCenter().addObserver(this, NotificationCenter.dialogDeleted);
        return super.onFragmentCreate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.Reactions));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && !checkChangesBeforeExit(true)) {
                    finishFragment();
                }
            }
        });

        SectionsScrollView.SectionsLinearLayout contentLayout = new SectionsScrollView.SectionsLinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView = new SectionsScrollView(context, contentLayout, resourceProvider);
        scrollView.setFillViewport(true);
        scrollView.addView(contentLayout);
        actionBar.setAdaptiveBackground(scrollView);

        enableReactionsCell = new TextCheckCell(context);
        enableReactionsCell.setHeight(56);
        enableReactionsCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundUnchecked));
        enableReactionsCell.setTypeface(AndroidUtilities.bold());
        enableReactionsCell.setColors(Theme.key_windowBackgroundCheckText, Theme.key_switchTrackBlue, Theme.key_switchTrackBlueChecked, Theme.key_switchTrackBlueThumb, Theme.key_switchTrackBlueThumbChecked);
        enableReactionsCell.setOnClickListener(v -> onEnableReactionsClick());
        contentLayout.addView(enableReactionsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        contentLayout.addView(createInfoCell(context, LocaleController.getString(R.string.ReactionAddEmojiFromAnyPack), 16), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        switchLayout = new LinearLayout(context);
        switchLayout.setOrientation(LinearLayout.VERTICAL);
        LayoutTransition layoutTransition = new LayoutTransition();
        layoutTransition.setDuration(200);
        layoutTransition.enableTransitionType(LayoutTransition.CHANGING);
        switchLayout.setLayoutTransition(layoutTransition);
        contentLayout.addView(switchLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fillSwitchLayout(context);

        FrameLayout rootLayout = createRootLayout(context);
        rootLayout.addView(scrollView);

        actionButtonContainer = new FrameLayout(context);
        ImageView gradient = new ImageView(context);
        gradient.setImageResource(R.drawable.gradient_bottom);
        gradient.setScaleType(ImageView.ScaleType.FIT_XY);
        gradient.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider), PorterDuff.Mode.SRC_ATOP));
        actionButtonContainer.addView(gradient, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionButton = new UpdateReactionsButton(context, resourceProvider);
        actionButton.setRound();
        actionButton.setDefaultState();
        actionButton.setOnClickListener(v -> applyChanges());
        actionButtonContainer.addView(actionButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 13, 13, 13, 13));
        rootLayout.addView(actionButtonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 13 + 48 + 13, Gravity.BOTTOM, 0, 0, 0, 0));

        bottomDialogLayout = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (emojiKeyboardVisible && changed) {
                    // screen rotation
                    actionButtonContainer.setTranslationY(-getMeasuredHeight());
                    updateScrollViewMarginBottom(getMeasuredHeight());
                }
            }
        };
        bottomDialogLayout.setVisibility(View.INVISIBLE);
        rootLayout.addView(bottomDialogLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        fillInitialReactions();
        enableReactionsCell.setTextAndCheck(LocaleController.getString(R.string.EnableReactions), selectedType != SELECT_TYPE_NONE || paid, false);
        editText.addReactionsSpan();
        if (info.paid_media_allowed && info.paid_reactions_available) {
            enableStars(false);
        }
        initialSelectedIds = new HashSet<>(selectedEmojisMap.keySet());
        initialPaid = paid;
        initialType = selectedType;
        updateActionButton(false);

        fragmentView = rootLayout;
        return rootLayout;
    }

    private FrameLayout createRootLayout(Context context) {
        FrameLayout rootLayout = new FrameLayout(context) {
            private final AdjustPanLayoutHelper adjustPanLayoutHelper = new AdjustPanLayoutHelper(this) {
                @Override
                protected void onTransitionStart(boolean keyboardVisible, int contentHeight) {
                    if (!actionButtonShown) {
                        return;
                    }
                    actionButtonContainer.setVisibility(View.VISIBLE);
                    actionButtonContainer.animate().alpha(keyboardVisible ? 0f : 1f).withEndAction(() -> {
                        if (keyboardVisible) {
                            actionButtonContainer.setVisibility(View.INVISIBLE);
                        }
                    }).start();
                }

                @Override
                protected void onTransitionEnd() {
                }

                @Override
                protected void onPanTranslationUpdate(float y, float progress, boolean keyboardVisible) {
                }

                @Override
                protected boolean applyTranslation() {
                    return false;
                }

                @Override
                protected boolean heightAnimationEnabled() {
                    INavigationLayout layout = getParentLayout();
                    return !(inPreviewMode || inBubbleMode || AndroidUtilities.isTablet() || AndroidUtilities.isInMultiwindow || layout == null);
                }
            };

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                adjustPanLayoutHelper.onAttach();
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                adjustPanLayoutHelper.onDetach();
            }
        };
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        return rootLayout;
    }

    private TextInfoPrivacyCell createInfoCell(Context context, CharSequence text, int bottomPadding) {
        TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context, 12, resourceProvider);
        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        cell.setTopPadding(12);
        cell.setBottomPadding(bottomPadding);
        cell.setText(text);
        return cell;
    }

    private void fillSwitchLayout(Context context) {
        HeaderCell headerCell = new HeaderCell(context);
        headerCell.setText(LocaleController.getString(R.string.AvailableReactions));
        headerCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        headerCell.setTextSize(15);
        headerCell.setTopMargin(14);
        switchLayout.addView(headerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        editText = createEditText(context);
        switchLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell packInfo = new TextInfoPrivacyCell(context, 12, resourceProvider);
        packInfo.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        packInfo.setText(AndroidUtilities.replaceSingleTag(
                LocaleController.getString(R.string.ReactionCreateOwnPack),
                Theme.key_chat_messageLinkIn, 0,
                () -> Browser.openUrl(context, "https://t.me/stickers"),
                resourceProvider
        ));
        switchLayout.addView(packInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell maxHeader = new HeaderCell(context, resourceProvider);
        maxHeader.setText(LocaleController.getString(R.string.MaximumReactionsHeader));
        switchLayout.addView(maxHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        boolean hasLimit = info instanceof TLRPC.TL_chatFull ? (info.flags & 1048576) != 0 : (info.flags2 & 8192) != 0;
        reactionsCount = initialReactionsCount = hasLimit ? info.reactions_limit : getMessagesController().reactionsUniqMax;
        SlideIntChooseView slideView = new SlideIntChooseView(context, resourceProvider);
        slideView.set(reactionsCount, SlideIntChooseView.Options.make(0, "MaximumReactionsValue", 1, getMessagesController().reactionsUniqMax), value -> {
            reactionsCount = value;
            updateActionButton(true);
        });
        switchLayout.addView(slideView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell maxInfo = new TextInfoPrivacyCell(context, 12, resourceProvider);
        maxInfo.setTopPadding(12);
        maxInfo.setBottomPadding(info.paid_media_allowed ? 16 : 70);
        maxInfo.setText(LocaleController.getString(R.string.MaximumReactionsInfo));
        switchLayout.addView(maxInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (info.paid_media_allowed) {
            paidCheckCell = new TextCheckCell(context);
            paidCheckCell.setTextAndCheck(LocaleController.getString(R.string.ChannelEnablePaidReactions), false, false);
            paidCheckCell.setOnClickListener(v -> toggleStarsEnabled());
            switchLayout.addView(paidCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            CharSequence paidInfo = AndroidUtilities.withLearnMore(LocaleController.getString(R.string.ChannelEnablePaidReactionsInfo),
                    () -> Browser.openUrl(context, LocaleController.getString(R.string.ChannelEnablePaidReactionsInfoLink)));
            switchLayout.addView(createInfoCell(context, paidInfo, 70), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private CustomReactionEditText createEditText(Context context) {
        CustomReactionEditText field = new CustomReactionEditText(context, resourceProvider, maxReactionsCount) {
            @Override
            protected void onLineCountChanged(int oldLineCount, int newLineCount) {
                if (newLineCount > oldLineCount) {
                    scrollView.smoothScrollBy(0, dp(30));
                }
            }

            @Override
            protected void extendActionMode(ActionMode actionMode, Menu menu) {
                super.extendActionMode(actionMode, menu);
                if (hasSelection()) {
                    menu.add(0, android.R.id.cut, 1, android.R.string.cut);
                    menu.add(0, android.R.id.copy, 2, android.R.string.copy);
                }
                if (helper.hasClipboard()) {
                    menu.add(0, android.R.id.paste, 3, android.R.string.paste);
                }
            }

            @Override
            public boolean onTextContextMenuItem(int id) {
                if (id == R.id.menu_delete) {
                    return deleteSelectedEmojis();
                }
                if (id == android.R.id.cut) {
                    helper.copyReactions(getText(), getEditTextSelectionStart(), getEditTextSelectionEnd());
                    return deleteSelectedEmojis();
                }
                if (id == android.R.id.copy) {
                    helper.copyReactions(getText(), getEditTextSelectionStart(), getEditTextSelectionEnd());
                    setSelection(getEditTextSelectionEnd());
                    return true;
                }
                if (id == android.R.id.paste) {
                    pasteReactionsFromClipboard();
                    return true;
                }
                return super.onTextContextMenuItem(id);
            }
        };
        field.setOnFocused(this::showKeyboard);
        return field;
    }

    private void fillInitialReactions() {
        TLRPC.ChatReactions reactions = info.available_reactions;
        if (reactions instanceof TLRPC.TL_chatReactionsAll) {
            appendDefaultReactions();
            setCheckedEnableReactionCell(SELECT_TYPE_ALL, paid, false);
        } else if (reactions instanceof TLRPC.TL_chatReactionsSome) {
            appendChatReactions(((TLRPC.TL_chatReactionsSome) reactions).reactions);
            setCheckedEnableReactionCell(SELECT_TYPE_SOME, paid, false);
        } else if (reactions instanceof TLRPC.TL_chatReactionsNone) {
            boolean paidOnly = info.paid_media_allowed && info.paid_reactions_available;
            if (!restoreReactionsBackup() && !paidOnly) {
                appendDefaultReactions();
            }
            setCheckedEnableReactionCell(SELECT_TYPE_NONE, paid, false);
        }
    }

    private void appendReaction(SpannableStringBuilder builder, TLRPC.TL_availableReaction reaction) {
        ReactionsUtils.addReactionToEditText(reaction, selectedEmojisMap, selectedEmojisIds, builder, selectAnimatedEmojiDialog, editText.getFontMetricsInt());
    }

    private void appendCustomReaction(SpannableStringBuilder builder, TLRPC.TL_reactionCustomEmoji reaction) {
        ReactionsUtils.addReactionToEditText(reaction, selectedEmojisMap, selectedEmojisIds, builder, selectAnimatedEmojiDialog, editText.getFontMetricsInt());
    }

    private void appendReaction(SpannableStringBuilder builder, long documentId) {
        TLRPC.TL_availableReaction reaction = helper.findAvailableReaction(documentId);
        if (reaction != null) {
            appendReaction(builder, reaction);
            return;
        }
        TLRPC.TL_reactionCustomEmoji custom = new TLRPC.TL_reactionCustomEmoji();
        custom.document_id = documentId;
        appendCustomReaction(builder, custom);
    }

    private void appendDefaultReactions() {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int i = 0; i < allAvailableReactions.size() && i < maxReactionsCount; i++) {
            appendReaction(builder, allAvailableReactions.get(i));
        }
        editText.append(builder);
    }

    private void appendChatReactions(List<TLRPC.Reaction> reactions) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        int count = 0;
        for (TLRPC.Reaction reaction : reactions) {
            if (count >= maxReactionsCount) {
                break;
            }
            if (reaction instanceof TLRPC.TL_reactionEmoji) {
                TLRPC.TL_availableReaction available = getMediaDataController().getReactionsMap().get(((TLRPC.TL_reactionEmoji) reaction).emoticon);
                if (available != null) {
                    appendReaction(builder, available);
                    count++;
                }
            } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                appendCustomReaction(builder, (TLRPC.TL_reactionCustomEmoji) reaction);
                count++;
            }
        }
        editText.append(builder);
    }

    private void onEnableReactionsClick() {
        if (enableReactionsCell.isChecked()) {
            backupPaid = isStarEnabled();
            if (isStarEnabled()) {
                toggleStarsEnabled();
            }
            setCheckedEnableReactionCell(SELECT_TYPE_NONE, false, true);
            return;
        }
        setCheckedEnableReactionCell(SELECT_TYPE_SOME, paid, true);
        if (backupPaid && paidCheckCell != null && !isStarEnabled()) {
            backupPaid = false;
            enableStars(true);
        }
    }

    private void applyChanges() {
        if (actionButton.isLoading()) {
            return;
        }
        if (selectedType != SELECT_TYPE_NONE && boostsStatus != null && boostsStatus.level < selectedCustomReactions) {
            ReactionsUtils.showLimitReachedDialogForReactions(-chatId, selectedCustomReactions, boostsStatus);
            return;
        }
        Boolean allowPaid = paidCheckCell != null ? paidCheckCell.isChecked() : null;
        saveReactionsBackup();
        actionButton.setLoading(true);
        getMessagesController().setCustomChatReactions(chatId, selectedType, grabReactions(false), reactionsCount, allowPaid, this::onApplyError, this::finishFragment);
    }

    private void onApplyError(TLRPC.TL_error error) {
        if (isFinishing()) {
            return;
        }
        actionButton.setLoading(false);
        if ("CHAT_NOT_MODIFIED".equals(error.text)) {
            finishFragment();
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (boostsStatus != null && "BOOSTS_REQUIRED".equals(error.text)) {
                ReactionsUtils.showLimitReachedDialogForReactions(-chatId, selectedCustomReactions, boostsStatus);
            } else {
                String text = "REACTIONS_TOO_MANY".equals(error.text) ? LocaleController.formatPluralString("ReactionMaxCountError", maxReactionsCount) : error.text;
                BulletinFactory.of(this).createErrorBulletin(text).show();
            }
        }, boostsStatus == null ? 200 : 0);
    }

    private void saveReactionsBackup() {
        if (selectedType != SELECT_TYPE_NONE || isStarEnabled()) {
            backupPaid = isStarEnabled();
        }
        helper.saveBackup(selectedEmojisIds, backupPaid);
    }

    private boolean restoreReactionsBackup() {
        RawReactionsEditHelper.Backup backup = helper.loadBackup(maxReactionsCount);
        if (backup == null) {
            return false;
        }
        if (backup.paid) {
            backupPaid = true;
        }
        if (backup.reactionIds.isEmpty()) {
            return false;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (Long id : backup.reactionIds) {
            appendReaction(builder, id);
        }
        editText.append(builder);
        return true;
    }

    private void pasteReactionsFromClipboard() {
        SelectAnimatedEmojiDialog dialog = selectAnimatedEmojiDialog;
        if (dialog == null) {
            return;
        }
        List<AnimatedEmojiSpan> spans = helper.parseClipboard();
        boolean hadStar = false;
        for (int i = spans.size() - 1; i >= 0; i--) {
            if (spans.get(i).documentId == PAID_ID) {
                spans.remove(i);
                hadStar = true;
            }
        }
        if (hadStar && paidCheckCell != null && !isStarEnabled()) {
            enableStars(true);
        }
        for (AnimatedEmojiSpan span : spans) {
            if (selectedEmojisMap.containsKey(span.documentId)) {
                continue;
            }
            int size = selectedEmojisMap.size();
            dialog.onEmojiClick(null, span);
            if (selectedEmojisMap.size() == size) {
                return; // limit reached
            }
        }
    }

    private void initSelectAnimatedEmojiDialog() {
        if (selectAnimatedEmojiDialog != null) {
            return;
        }
        int accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider);
        SelectAnimatedEmojiDialog dialog = new SelectAnimatedEmojiDialog(this, getContext(), false, null, SelectAnimatedEmojiDialog.TYPE_CHAT_REACTIONS, false, resourceProvider, 16, accentColor) {
            private boolean firstLayout = true;

            {
                setDrawBackground(false);
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (firstLayout) {
                    firstLayout = false;
                    onShow(null);
                }
            }

            @Override
            protected void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                if (documentId == null) {
                    return;
                }
                if (selectedEmojisMap.containsKey(documentId)) {
                    removeReaction(documentId);
                } else {
                    addReaction(documentId, document);
                }
            }
        };
        dialog.setAnimationsEnabled(false);
        dialog.setClipChildren(false);
        dialog.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        selectAnimatedEmojiDialog = dialog;
        bottomDialogLayout.addView(dialog, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        BackSpaceButtonView backSpaceButtonView = new BackSpaceButtonView(getContext(), resourceProvider);
        backSpaceButtonView.setOnBackspace(this::onBackspace);
        bottomDialogLayout.addView(backSpaceButtonView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 8, 8));
        for (Long id : selectedEmojisIds) {
            dialog.setMultiSelected(id, false);
        }
    }

    private void addReaction(long documentId, TLRPC.Document document) {
        if (selectedEmojisMap.size() - (selectedEmojisMap.containsKey(PAID_ID) ? 1 : 0) >= maxReactionsCount) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.formatPluralString("ReactionMaxCountError", maxReactionsCount)).show();
            return;
        }
        try {
            int selectionEnd = editText.getEditTextSelectionEnd();
            AnimatedEmojiSpan span = ReactionsUtils.createAnimatedEmojiSpan(document, documentId, editText.getFontMetricsInt());
            span.cacheType = AnimatedEmojiDrawable.getCacheTypeForEnterView();
            span.setAdded();
            selectedEmojisIds.add(MathUtils.clamp(selectionEnd, 0, selectedEmojisIds.size()), documentId);
            selectedEmojisMap.put(documentId, span);
            insertSpan(span, selectionEnd);
            editText.setSelection(selectionEnd + 1);
            if (selectAnimatedEmojiDialog != null) {
                selectAnimatedEmojiDialog.setMultiSelected(documentId, true);
            }
            checkMaxCustomReactions(true);
            animateChangesInNextRows(span);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void removeReaction(long documentId) {
        selectedEmojisIds.remove(documentId);
        AnimatedEmojiSpan span = selectedEmojisMap.remove(documentId);
        if (span == null) {
            return;
        }
        removeSpanAnimated(span);
        animateChangesInNextRows(span);
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setMultiSelected(documentId, true);
        }
        checkMaxCustomReactions(false);
    }

    /** Drops a reaction whose span the edit field deletes itself. */
    private void forgetReaction(long documentId) {
        selectedEmojisMap.remove(documentId);
        selectedEmojisIds.remove(documentId);
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.unselect(documentId);
        }
        if (documentId == PAID_ID) {
            onStarRemoved();
        }
    }

    private void onBackspace(boolean isFast) {
        if (deleteSelectedEmojis()) {
            return;
        }
        Editable text = editText.getText();
        if (text == null) {
            return;
        }
        int selectionEnd = editText.getEditTextSelectionEnd();
        AnimatedEmojiSpan target = null;
        for (AnimatedEmojiSpan span : text.getSpans(0, text.length(), AnimatedEmojiSpan.class)) {
            if (text.getSpanEnd(span) == selectionEnd) {
                target = span;
                break;
            }
        }
        if (target == null) {
            return;
        }
        forgetReaction(target.documentId);
        if (isFast) {
            editText.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
            AndroidUtilities.cancelRunOnUIThread(checkAfterFastDeleteRunnable);
            AndroidUtilities.runOnUIThread(checkAfterFastDeleteRunnable, 350);
            return;
        }
        removeSpanAnimated(target);
        animateChangesInNextRows(target);
        checkMaxCustomReactions(false);
    }

    private boolean deleteSelectedEmojis() {
        if (!editText.hasSelection()) {
            return false;
        }
        Editable text = editText.getText();
        if (text != null) {
            AnimatedEmojiSpan[] spans = text.getSpans(editText.getEditTextSelectionStart(), editText.getEditTextSelectionEnd(), AnimatedEmojiSpan.class);
            for (AnimatedEmojiSpan span : spans) {
                forgetReaction(span.documentId);
            }
        }
        editText.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        checkMaxCustomReactions(false);
        return true;
    }

    private void insertSpan(AnimatedEmojiSpan span, int position) {
        SpannableString spannable = new SpannableString("b");
        spannable.setSpan(span, 0, spannable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Editable text = editText.getText();
        if (text != null) {
            text.insert(position, spannable);
        }
    }

    private void removeSpanAnimated(AnimatedEmojiSpan span) {
        span.setRemoved(() -> {
            Editable text = editText.getText();
            if (text == null) {
                return;
            }
            int spanStart = text.getSpanStart(span);
            int spanEnd = text.getSpanEnd(span);
            if (spanStart == -1 || spanEnd == -1) {
                return;
            }
            int selectionEnd = editText.getEditTextSelectionEnd();
            text.delete(spanStart, spanEnd);
            editText.setSelection(spanEnd <= selectionEnd ? selectionEnd - (spanEnd - spanStart) : selectionEnd);
        });
    }

    private void animateChangesInNextRows(AnimatedEmojiSpan span) {
        Editable text = editText.getText();
        Layout layout = editText.getLayout();
        if (text == null || layout == null) {
            return;
        }
        int nextLine = layout.getLineForOffset(text.getSpanStart(span)) + 1;
        if (nextLine < layout.getLineCount()) {
            for (AnimatedEmojiSpan next : text.getSpans(layout.getLineStart(nextLine), text.length(), AnimatedEmojiSpan.class)) {
                next.setAnimateChanges();
            }
        }
    }

    private void toggleStarsEnabled() {
        if (isStarEnabled()) {
            disableStars();
        } else {
            enableStars(true);
        }
    }

    private void enableStars(boolean withToast) {
        if (paidCheckCell == null) {
            return;
        }
        paidCheckCell.setChecked(true);
        editText.setMaxLength(maxReactionsCount + 1);
        AnimatedEmojiSpan span = createStarSpan();
        span.cacheType = AnimatedEmojiDrawable.getCacheTypeForEnterView();
        span.setAdded();
        selectedEmojisIds.add(0, PAID_ID);
        selectedEmojisMap.put(PAID_ID, span);
        insertSpan(span, 0);
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setMultiSelected(PAID_ID, true);
        }
        animateChangesInNextRows(span);
        setCheckedEnableReactionCell(selectedType, true, true);
        checkMaxCustomReactions(withToast);
        editText.updateAnimatedEmoji(true);
    }

    private void disableStars() {
        selectedEmojisIds.remove(PAID_ID);
        AnimatedEmojiSpan span = selectedEmojisMap.remove(PAID_ID);
        if (span != null) {
            removeSpanAnimated(span);
            animateChangesInNextRows(span);
        }
        if (selectAnimatedEmojiDialog != null) {
            selectAnimatedEmojiDialog.setMultiSelected(PAID_ID, true);
        }
        onStarRemoved();
        checkMaxCustomReactions(false);
        editText.updateAnimatedEmoji(true);
    }

    private void onStarRemoved() {
        if (paidCheckCell != null) {
            paidCheckCell.setChecked(false);
        }
        editText.setMaxLength(maxReactionsCount);
        if (selectedType == SELECT_TYPE_NONE) {
            setCheckedEnableReactionCell(SELECT_TYPE_NONE, false, true);
        } else {
            paid = false;
        }
    }

    private AnimatedEmojiSpan createStarSpan() {
        return new AnimatedEmojiSpan(PAID_ID, null) {
            private final int iconSize = dp(24);
            private final Bitmap bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);

            {
                Drawable drawable = ContextCompat.getDrawable(getContext(), R.drawable.star_small_inner);
                if (drawable != null) {
                    drawable = drawable.mutate();
                    drawable.setBounds(0, 0, iconSize, iconSize);
                    drawable.draw(new Canvas(bitmap));
                }
            }

            @Override
            public void draw(@NonNull Canvas canvas, CharSequence charSequence, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
                super.draw(canvas, charSequence, start, end, x, top, y, bottom, paint);
                int save = canvas.save();
                try {
                    canvas.translate(x, (top + bottom) / 2f - iconSize / 2f);
                    canvas.scale(extraScale, extraScale, x + iconSize / 2f, iconSize / 2f);
                    canvas.drawBitmap(bitmap, 0, 0, null);
                } finally {
                    canvas.restoreToCount(save);
                }
            }

            @Override
            public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
                return super.getSize(paint, text, start, end, fm) + dp(5);
            }
        };
    }

    private void setCheckedEnableReactionCell(int selectType, boolean paid, boolean animated) {
        if (selectedType == selectType && this.paid == paid) {
            return;
        }
        this.paid = paid;
        this.selectedType = selectType;
        boolean checked = selectType != SELECT_TYPE_NONE || paid;
        boolean wasChecked = enableReactionsCell.isChecked();
        enableReactionsCell.setChecked(checked);
        if (animated) {
            checkMaxCustomReactions(false);
        }
        if (animated && checked == wasChecked) {
            return;
        }
        int color = Theme.getColor(checked ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked);
        if (!animated) {
            enableReactionsCell.setBackgroundColor(color);
        } else if (checked) {
            enableReactionsCell.setBackgroundColorAnimated(true, color);
        } else {
            enableReactionsCell.setBackgroundColorAnimatedReverse(color);
        }
        if (checked) {
            showReactionsSettings(animated);
        } else {
            hideReactionsSettings(animated);
        }
    }

    private void showReactionsSettings(boolean animated) {
        switchLayout.setVisibility(View.VISIBLE);
        if (!animated) {
            return;
        }
        switchLayout.animate().setListener(null).cancel();
        switchLayout.animate().alpha(1f).setDuration(350).setInterpolator(CubicBezierInterpolator.DEFAULT)
                .withEndAction(() -> editText.setFocusableInTouchMode(true)).start();
        if (selectedEmojisMap.isEmpty()) {
            if (selectAnimatedEmojiDialog != null) {
                selectAnimatedEmojiDialog.clearSelectedDocuments();
            }
            editText.setText("");
            appendDefaultReactions();
            editText.addReactionsSpan();
            if (selectAnimatedEmojiDialog != null) {
                selectAnimatedEmojiDialog.notifyDataSetChanged();
            }
            checkMaxCustomReactions(false);
        }
    }

    private void hideReactionsSettings(boolean animated) {
        if (!animated) {
            switchLayout.setVisibility(View.INVISIBLE);
            return;
        }
        closeKeyboard();
        switchLayout.animate().setListener(null).cancel();
        switchLayout.animate().alpha(0f).setDuration(350).setInterpolator(CubicBezierInterpolator.DEFAULT).withEndAction(() -> {
            editText.setFocusableInTouchMode(false);
            switchLayout.setVisibility(View.INVISIBLE);
        }).start();
    }

    private boolean hasChanges() {
        return !selectedEmojisMap.keySet().equals(initialSelectedIds)
                || initialPaid != paid
                || (selectedType == SELECT_TYPE_NONE) != (initialType == SELECT_TYPE_NONE)
                || reactionsCount != initialReactionsCount;
    }

    private void updateActionButton(boolean animated) {
        boolean show = hasChanges();
        if (show == actionButtonShown) {
            return;
        }
        actionButtonShown = show;
        actionButtonContainer.animate().setListener(null).cancel();
        if (!animated) {
            actionButtonContainer.setAlpha(show ? 1f : 0f);
            actionButtonContainer.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        actionButtonContainer.setVisibility(View.VISIBLE);
        actionButtonContainer.animate().alpha(show ? 1f : 0f).setDuration(350).setInterpolator(CubicBezierInterpolator.DEFAULT).withEndAction(() -> {
            if (!show) {
                actionButtonContainer.setVisibility(View.INVISIBLE);
            }
        }).start();
    }

    private boolean checkChangesBeforeExit(boolean invoked) {
        if (!hasChanges()) {
            return false;
        }
        if (!invoked) {
            return true;
        }
        new AlertDialog.Builder(getContext(), resourceProvider)
                .setTitle(LocaleController.getString(R.string.UnsavedChanges))
                .setMessage(LocaleController.getString(R.string.ReactionApplyChangesDialog))
                .setPositiveButton(LocaleController.getString(R.string.ApplyTheme), (dialog, which) -> actionButton.performClick())
                .setNegativeButton(LocaleController.getString(R.string.Discard), (dialog, which) -> finishFragment())
                .show();
        return true;
    }

    private void checkMaxCustomReactions(boolean withToast) {
        if (actionButtonContainer != null) {
            updateActionButton(true);
        }
        if (boostsStatus == null || actionButton == null) {
            return;
        }
        if (selectedType == SELECT_TYPE_NONE) {
            actionButton.removeLvlRequiredState();
            return;
        }
        if (selectedType == SELECT_TYPE_ALL) {
            selectedType = SELECT_TYPE_SOME;
        }
        selectedCustomReactions = grabReactions(true).size();
        if (boostsStatus.level >= selectedCustomReactions) {
            actionButton.removeLvlRequiredState();
        } else {
            if (withToast) {
                CharSequence text = AndroidUtilities.replaceTags(LocaleController.formatPluralString("ReactionReachLvlForReactionShort", selectedCustomReactions, selectedCustomReactions));
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, text).show();
            }
            actionButton.setLvlRequiredState(selectedCustomReactions);
        }
    }

    private List<TLRPC.Reaction> grabReactions(boolean onlyCustom) {
        ArrayList<TLRPC.Reaction> result = new ArrayList<>();
        for (Long documentId : selectedEmojisIds) {
            if (documentId == PAID_ID) {
                continue;
            }
            TLRPC.TL_availableReaction available = helper.findAvailableReaction(documentId);
            if (available == null) {
                TLRPC.TL_reactionCustomEmoji custom = new TLRPC.TL_reactionCustomEmoji();
                custom.document_id = documentId;
                result.add(custom);
            } else if (!onlyCustom) {
                TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
                emoji.emoticon = available.reaction;
                result.add(emoji);
            }
        }
        return result;
    }

    private void showKeyboard() {
        if (emojiKeyboardVisible) {
            return;
        }
        emojiKeyboardVisible = true;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
        int height = bottomDialogLayout.getMeasuredHeight();
        updateScrollViewMarginBottom(height);
        bottomDialogLayout.setVisibility(View.VISIBLE);
        bottomDialogLayout.setTranslationY(height);
        bottomDialogLayout.animate().setListener(null).cancel();
        bottomDialogLayout.animate().translationY(0).withLayer().setDuration(350).setInterpolator(CubicBezierInterpolator.DEFAULT)
                .setUpdateListener(animation -> actionButtonContainer.setTranslationY(-(float) animation.getAnimatedValue() * bottomDialogLayout.getMeasuredHeight()))
                .withEndAction(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512))
                .start();
    }

    private void closeKeyboard() {
        if (!emojiKeyboardVisible) {
            return;
        }
        emojiKeyboardVisible = false;
        if (isClearFocusNotWorking()) {
            switchLayout.setFocusableInTouchMode(true);
            switchLayout.requestFocus();
        } else {
            editText.clearFocus();
        }
        updateScrollViewMarginBottom(0);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512);
        bottomDialogLayout.animate().setListener(null).cancel();
        bottomDialogLayout.animate().translationY(bottomDialogLayout.getMeasuredHeight()).setDuration(350).withLayer().setInterpolator(CubicBezierInterpolator.DEFAULT)
                .setUpdateListener(animation -> actionButtonContainer.setTranslationY(-(1f - (float) animation.getAnimatedValue()) * bottomDialogLayout.getMeasuredHeight()))
                .withEndAction(() -> {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512);
                    bottomDialogLayout.setVisibility(View.INVISIBLE);
                    if (isClearFocusNotWorking()) {
                        switchLayout.setFocusableInTouchMode(false);
                    }
                })
                .start();
    }

    private boolean isClearFocusNotWorking() {
        return Build.MODEL.toLowerCase(Locale.ROOT).startsWith("zte") && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    private void updateScrollViewMarginBottom(int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) scrollView.getLayoutParams();
        params.bottomMargin = margin;
        scrollView.setLayoutParams(params);
    }

    @Override
    public boolean canBeginSlide() {
        return !checkChangesBeforeExit(true) && super.canBeginSlide();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && selectedType != SELECT_TYPE_NONE) {
            editText.setFocusableInTouchMode(true);
        }
        if (isOpen && !backward) {
            initSelectAnimatedEmojiDialog();
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512), 200);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (paused) {
            paused = false;
            editText.setFocusable(true);
            editText.setFocusableInTouchMode(true);
            if (emojiKeyboardVisible) {
                editText.removeReactionsSpan(false);
                AndroidUtilities.runOnUIThread(() -> editText.requestFocus(), 250);
            }
        }
    }

    @Override
    public void onPause() {
        paused = true;
        if (editText != null) {
            editText.setFocusable(false);
        }
        super.onPause();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (emojiKeyboardVisible) {
            if (invoked) {
                closeKeyboard();
            }
            return false;
        }
        return !checkChangesBeforeExit(invoked) && super.onBackPressed(invoked);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(checkAfterFastDeleteRunnable);
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogDeleted);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogDeleted && (long) args[0] == -chatId) {
            if (parentLayout != null && parentLayout.getLastFragment() == this) {
                finishFragment();
            } else {
                removeSelfFromStack();
            }
        }
    }
}
