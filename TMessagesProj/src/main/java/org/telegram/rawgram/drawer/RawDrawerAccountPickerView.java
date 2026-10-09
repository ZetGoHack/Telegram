package org.telegram.rawgram.drawer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.rawgram.RawUi;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumGradient;

import java.util.ArrayList;
import java.util.Collections;

/**
 * The account list under the header: a rounded grey card that expands and collapses (remembered like Telegram's
 * own «accountsShown»), scrolls with soft fades after five and a half rows, marks the current account with a ring
 * around a smaller avatar, shows each account's unread counter and ends with «Добавить аккаунт». A long press
 * drags an account to reorder the list. Ported from exteraGram's drawer (exteraSquad, GPL).
 */
final class RawDrawerAccountPickerView extends FrameLayout {

    private static final int ROW_HEIGHT_DP = 44;
    private static final int SLOT_HEIGHT_DP = 48;
    private static final int LIST_PADDING_DP = 4;
    private static final int FADE_HEIGHT_DP = 16;
    private static final float MAX_VISIBLE_ROWS = 5.5f;
    private static final int TYPE_ACCOUNT = 0, TYPE_ADD = 1;

    private static final int COLOR_KEY_BACKGROUND = Theme.key_windowBackgroundGray;
    private static final int COLOR_KEY_SELECTOR = Theme.key_listSelector;
    private static final int COLOR_KEY_SURFACE = Theme.key_windowBackgroundWhite;
    private static final int COLOR_KEY_TEXT = Theme.key_windowBackgroundWhiteBlackText;
    private static final int COLOR_KEY_STATUS = Theme.key_profile_verifiedBackground;
    private static final int COLOR_KEY_ACCENT = Theme.key_featuredStickers_addButton;
    private static final int COLOR_KEY_ADD_ICON = Theme.key_featuredStickers_buttonText;

    /** What the account rows need from the drawer. */
    interface Callback {
        void onAccountSelected(int account);

        void onAddAccount();

        /** Long press on another account: its chats as a preview (exteraGram's account preview). */
        void onAccountPreview(int account, View row);
    }

    private final ArrayList<Integer> accounts = new ArrayList<>();
    private final AccountAdapter adapter = new AccountAdapter();
    private final FrameLayout clipWrapper;
    private final RecyclerView recyclerView;
    private final ItemTouchHelper itemTouchHelper;
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clipMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topFadePaint = new Paint();
    private final Paint bottomFadePaint = new Paint();
    private final RectF backgroundRect = new RectF();
    private final float cornerRadius = dp(16);
    private int fadeHeight = -1;
    private int animatedHeight = -1;
    private ValueAnimator expandAnimator;
    private boolean expanded;
    private View draggingItemView;
    private Callback callback;

    RawDrawerAccountPickerView(Context context) {
        super(context);
        expanded = MessagesController.getGlobalMainSettings().getBoolean("accountsShown", true);

        clipMaskPaint.setStyle(Paint.Style.FILL);
        clipMaskPaint.setColor(0xff000000);
        clipMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        topFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        bottomFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));

        clipWrapper = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxHeight = maxListHeight();
                int width = MeasureSpec.getSize(widthMeasureSpec);
                measureChildren(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY));
                if (animatedHeight >= 0) {
                    setMeasuredDimension(width, animatedHeight);
                    return;
                }
                int height = MeasureSpec.getSize(heightMeasureSpec);
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || height > maxHeight) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                backgroundPaint.setColor(Theme.getColor(COLOR_KEY_BACKGROUND));
                backgroundRect.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint);
                int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
                super.dispatchDraw(canvas);
                drawScrollFades(canvas, getWidth(), getHeight());
                canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, clipMaskPaint);
                canvas.restoreToCount(layer);
            }
        };
        clipWrapper.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        clipWrapper.setClipToOutline(true);
        addView(clipWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.TOP, 12, 0, 12, 0));

        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(adapter);
        int padding = dp(LIST_PADDING_DP);
        recyclerView.setPadding(padding, padding, padding, padding);
        recyclerView.setClipToPadding(false);
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position >= 0 && position < state.getItemCount() - 1) {
                    outRect.bottom = padding;
                }
            }
        });
        recyclerView.setOverScrollMode(OVER_SCROLL_NEVER);
        recyclerView.setVerticalScrollBarEnabled(false);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                clipWrapper.invalidate();
            }
        });
        clipWrapper.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // the dragged row is drawn above the others
        RecyclerView.ChildDrawingOrderCallback dragOnTop = (childCount, i) -> {
            int dragged = draggingItemView != null ? recyclerView.indexOfChild(draggingItemView) : -1;
            if (dragged < 0) {
                return i;
            }
            if (i == childCount - 1) {
                return dragged;
            }
            return i >= dragged ? i + 1 : i;
        };
        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder holder) {
                if (holder.getAdapterPosition() >= accounts.size()) {
                    return 0;
                }
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                int a = from.getAdapterPosition(), b = to.getAdapterPosition();
                if (a >= accounts.size() || b >= accounts.size()) {
                    return false;
                }
                adapter.swapElements(a, b);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
                if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || holder == null) {
                    return;
                }
                draggingItemView = holder.itemView;
                draggingItemView.setPressed(false);
                draggingItemView.jumpDrawablesToCurrentState();
                recyclerView.setChildDrawingOrderCallback(dragOnTop);
                recyclerView.invalidate();
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder holder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                holder.itemView.setTranslationX(dX);
                holder.itemView.setTranslationY(dY);
            }

            @Override
            public void clearView(@NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder holder) {
                holder.itemView.setTranslationX(0);
                holder.itemView.setTranslationY(0);
                holder.itemView.setPressed(false);
                if (draggingItemView == holder.itemView) {
                    draggingItemView = null;
                }
                view.setChildDrawingOrderCallback(null);
                view.invalidate();
            }
        });
        itemTouchHelper.attachToRecyclerView(recyclerView);

        if (expanded) {
            loadAccounts();
            setVisibility(VISIBLE);
            ViewGroup.LayoutParams lp = clipWrapper.getLayoutParams();
            lp.height = LayoutHelper.WRAP_CONTENT;
            clipWrapper.setLayoutParams(lp);
        } else {
            setVisibility(GONE);
        }
    }

    void setCallback(Callback callback) {
        this.callback = callback;
    }

    @SuppressLint("NotifyDataSetChanged")
    void loadAccounts() {
        accounts.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a);
            }
        }
        Collections.sort(accounts, (a, b) -> Long.compare(UserConfig.getInstance(a).loginTime, UserConfig.getInstance(b).loginTime));
        adapter.notifyDataSetChanged();
    }

    boolean isExpanded() {
        return expanded;
    }

    void toggleExpand() {
        setExpanded(!expanded);
    }

    void setExpanded(boolean value) {
        if (expanded == value) {
            return;
        }
        expanded = value;
        MessagesController.getGlobalMainSettings().edit().putBoolean("accountsShown", value).apply();
        if (value) {
            loadAccounts();
            setVisibility(VISIBLE);
        }
        if (expandAnimator != null) {
            expandAnimator.cancel();
        }
        int from = animatedHeight;
        if (from < 0) {
            from = clipWrapper.getLayoutParams().height;
            if (from < 0) {
                from = value ? 0 : clipWrapper.getMeasuredHeight();
            }
        }
        animatedHeight = -1;
        int parentWidth = getParent() instanceof View ? ((View) getParent()).getMeasuredWidth() : getMeasuredWidth();
        clipWrapper.measure(MeasureSpec.makeMeasureSpec(Math.max(0, parentWidth - dp(24)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxListHeight(), MeasureSpec.AT_MOST));
        int to = value ? clipWrapper.getMeasuredHeight() : 0;
        animatedHeight = from;
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        expandAnimator = animator;
        animator.setDuration(250);
        animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        animator.addUpdateListener(a -> {
            animatedHeight = (int) a.getAnimatedValue();
            clipWrapper.requestLayout();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled || expandAnimator != animation) {
                    return;
                }
                expandAnimator = null;
                animatedHeight = -1;
                if (!expanded) {
                    setVisibility(GONE);
                    return;
                }
                ViewGroup.LayoutParams lp = clipWrapper.getLayoutParams();
                lp.height = LayoutHelper.WRAP_CONTENT;
                clipWrapper.setLayoutParams(lp);
            }
        });
        animator.start();
    }

    @SuppressLint("NotifyDataSetChanged")
    void updateColors() {
        invalidate();
        clipWrapper.invalidate();
        adapter.notifyDataSetChanged();
    }

    void updateUnreadCounters() {
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            if (child instanceof AccountRowView) {
                ((AccountRowView) child).updateUnreadCounter();
            }
        }
    }

    void dispose() {
        if (expandAnimator != null) {
            expandAnimator.cancel();
            expandAnimator = null;
        }
        draggingItemView = null;
        recyclerView.setChildDrawingOrderCallback(null);
        recyclerView.stopScroll();
    }

    private int maxListHeight() {
        int count = adapter.getItemCount();
        return (int) (dp(SLOT_HEIGHT_DP) * (count <= 6 ? count : MAX_VISIBLE_ROWS) + dp(LIST_PADDING_DP) * 2);
    }

    /** Fades the list out at the edge it can still scroll to. */
    private void drawScrollFades(Canvas canvas, int width, int height) {
        int fade = dp(FADE_HEIGHT_DP);
        if (fadeHeight != height) {
            fadeHeight = height;
            topFadePaint.setShader(new LinearGradient(0, 0, 0, fade, new int[]{0xff000000, 0}, null, Shader.TileMode.CLAMP));
            bottomFadePaint.setShader(new LinearGradient(0, height, 0, height - fade, new int[]{0xff000000, 0}, null, Shader.TileMode.CLAMP));
        }
        int offset = recyclerView.computeVerticalScrollOffset();
        int remaining = Math.max(0, recyclerView.computeVerticalScrollRange() - recyclerView.computeVerticalScrollExtent() - offset);
        float top = Math.min(1f, Math.max(0f, offset / (float) fade));
        float bottom = Math.min(1f, remaining / (float) fade);
        if (top > 0) {
            topFadePaint.setAlpha((int) (top * 255));
            canvas.drawRect(0, 0, width, fade, topFadePaint);
        }
        if (bottom > 0) {
            bottomFadePaint.setAlpha((int) (bottom * 255));
            canvas.drawRect(0, height - fade, width, height, bottomFadePaint);
        }
    }

    private boolean canAddAccount() {
        return UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT;
    }

    private static Drawable createRowRipple() {
        return Theme.createRadSelectorDrawable(Theme.getColor(COLOR_KEY_SELECTOR), 12, 12);
    }

    private static Drawable createSelectedRowBackground() {
        return Theme.createSimpleSelectorRoundRectDrawable(dp(12), Theme.getColor(COLOR_KEY_SURFACE), Theme.getColor(COLOR_KEY_SELECTOR));
    }

    private static int avatarRadius() {
        return RawUi.avatarR(dp(AccountRowView.AVATAR_DP / 2f));
    }

    private final class AccountAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return position < accounts.size() ? TYPE_ACCOUNT : TYPE_ADD;
        }

        @Override
        public int getItemCount() {
            return accounts.size() + (canAddAccount() ? 1 : 0);
        }

        /** Moves an account in the list; the order is Telegram's login time order, so the two times are swapped. */
        void swapElements(int from, int to) {
            if (from < 0 || to < 0 || from >= accounts.size() || to >= accounts.size()) {
                return;
            }
            UserConfig a = UserConfig.getInstance(accounts.get(from));
            UserConfig b = UserConfig.getInstance(accounts.get(to));
            int time = a.loginTime;
            a.loginTime = b.loginTime;
            b.loginTime = time;
            a.saveConfig(false);
            b.saveConfig(false);
            Collections.swap(accounts, from, to);
            notifyItemMoved(from, to);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = viewType == TYPE_ADD ? new AddAccountView(parent.getContext()) : new AccountRowView(parent.getContext());
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof AccountRowView) {
                int account = accounts.get(position);
                AccountRowView row = (AccountRowView) holder.itemView;
                row.bind(account);
                row.setOnClickListener(v -> {
                    if (account != UserConfig.selectedAccount && callback != null) {
                        callback.onAccountSelected(account);
                    }
                });
                row.setOnLongClickListener(v -> {
                    // the current account is dragged to reorder, another one opens as a preview (as exteraGram)
                    if (account == UserConfig.selectedAccount) {
                        itemTouchHelper.startDrag(holder);
                    } else if (callback != null) {
                        callback.onAccountPreview(account, v);
                    }
                    return true;
                });
            } else if (holder.itemView instanceof AddAccountView) {
                ((AddAccountView) holder.itemView).updateColors();
                holder.itemView.setOnClickListener(v -> {
                    if (callback != null) {
                        callback.onAddAccount();
                    }
                });
            }
        }
    }

    /** An account: avatar, name with the emoji status and the unread counter. */
    static final class AccountRowView extends FrameLayout {

        static final int AVATAR_DP = 34;
        private static final float SELECTED_AVATAR_SCALE = 0.785f;

        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final BackupImageView avatarView;
        private final SimpleTextView nameView;
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusDrawable;
        private final RawDrawerAccountUnreadBadge unreadBadge = new RawDrawerAccountUnreadBadge();
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF ringRect = new RectF();
        private boolean selected;

        AccountRowView(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, dp(ROW_HEIGHT_DP)));
            setBackground(createRowRipple());

            avatarDrawable.setTextSize(dp(20));
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(avatarRadius());
            addView(avatarView, LayoutHelper.createFrame(AVATAR_DP, AVATAR_DP, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            nameView = new SimpleTextView(context);
            nameView.setTextSize(15);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
            nameView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            nameView.setEllipsizeByGradient(true);
            nameView.setCanHideRightDrawable(false);
            nameView.setRightDrawableOutside(true);
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 54, 0, 12, 0));
            statusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(nameView, dp(18));

            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1.67f));
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        void bind(int account) {
            TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            if (user == null) {
                return;
            }
            avatarView.setRoundRadius(avatarRadius());
            avatarDrawable.setInfo(account, user);
            nameView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
            nameView.setText(ContactsController.formatName(user.first_name, user.last_name));
            avatarView.getImageReceiver().setCurrentAccount(account);
            avatarView.setForUserOrChat(user, avatarDrawable);
            statusDrawable.setCurrentAccount(account);

            long statusId = DialogObject.getEmojiStatusDocumentId(user.emoji_status);
            Drawable status = null;
            if (statusId != 0) {
                statusDrawable.set(statusId, false);
                status = statusDrawable;
            } else if (MessagesController.getInstance(account).isPremiumUser(user)) {
                statusDrawable.set(PremiumGradient.getInstance().premiumStarDrawableMini, false);
                status = statusDrawable;
            } else {
                statusDrawable.set((Drawable) null, false);
            }
            statusDrawable.setColor(Theme.getColor(COLOR_KEY_STATUS));
            statusDrawable.setParticles(DialogObject.isEmojiStatusCollectible(user.emoji_status), false);
            nameView.setRightDrawable(status);
            unreadBadge.bind(account, nameView);

            ringPaint.setColor(Theme.getColor(COLOR_KEY_ACCENT));
            selected = account == UserConfig.selectedAccount;
            float scale = selected ? SELECTED_AVATAR_SCALE : 1f;
            avatarView.setScaleX(scale);
            avatarView.setScaleY(scale);
            setBackground(selected ? createSelectedRowBackground() : createRowRipple());
            invalidate();
        }

        void updateUnreadCounter() {
            unreadBadge.update(nameView);
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            statusDrawable.attach();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            statusDrawable.detach();
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            super.dispatchDraw(canvas);
            unreadBadge.draw(this, canvas);
            if (selected) {
                float inset = ringPaint.getStrokeWidth() / 2f;
                ringRect.set(avatarView.getLeft() + inset, avatarView.getTop() + inset, avatarView.getRight() - inset, avatarView.getBottom() - inset);
                float radius = Math.min(avatarRadius(), ringRect.width() / 2f);
                canvas.drawRoundRect(ringRect, radius, radius, ringPaint);
            }
        }
    }

    /** «Добавить аккаунт» with the round accent plus. */
    static final class AddAccountView extends LinearLayout {

        private final Drawable circleDrawable;
        private final Drawable plusDrawable;
        private final SimpleTextView textView;

        AddAccountView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, dp(ROW_HEIGHT_DP)));

            ImageView imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.CENTER);
            Drawable circle = ResourcesCompat.getDrawable(context.getResources(), R.drawable.poll_add_circle, null);
            Drawable plus = ResourcesCompat.getDrawable(context.getResources(), R.drawable.poll_add_plus, null);
            circleDrawable = circle != null ? circle.mutate() : null;
            plusDrawable = plus != null ? plus.mutate() : null;
            CombinedDrawable combined = new CombinedDrawable(circleDrawable, plusDrawable) {
                @Override
                public void setColorFilter(ColorFilter colorFilter) {
                    // the two layers keep their own colours
                }
            };
            combined.setCustomSize(dp(24), dp(24));
            imageView.setImageDrawable(combined);
            addView(imageView, LayoutHelper.createLinear(AccountRowView.AVATAR_DP, AccountRowView.AVATAR_DP, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            textView = new SimpleTextView(context);
            textView.setTextSize(15);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            textView.setText("Добавить аккаунт");
            addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
            updateColors();
        }

        void updateColors() {
            setBackground(createRowRipple());
            if (circleDrawable != null) {
                circleDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(COLOR_KEY_ACCENT), PorterDuff.Mode.SRC_IN));
            }
            if (plusDrawable != null) {
                plusDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(COLOR_KEY_ADD_ICON), PorterDuff.Mode.SRC_IN));
            }
            textView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
        }
    }
}
