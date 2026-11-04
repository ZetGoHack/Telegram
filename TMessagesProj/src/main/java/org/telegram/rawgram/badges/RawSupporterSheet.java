package org.telegram.rawgram.badges;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * Base of the supporter sheets: the 3D arrow header, a centred title and subtitle, custom rows (perks, number pill,
 * caption bubble), and a bottom bar with the main button and optional secondary buttons over a fade.
 * When opened from a tapped badge ({@link #setEnterFrom}), the badge flies from its place into the header.
 * <p>
 * Ported from exteraGram's {@code SupporterSheet} (exteraGram, GPLv3).
 */
public abstract class RawSupporterSheet extends BottomSheetWithRecyclerListView {

    private static final int HEADER_HEIGHT_DP = 190;
    private static final int BUTTON_HEIGHT_DP = 48;
    private static final int BOTTOM_FADE_DP = 16;
    private static final long ENTER_DURATION = 600;
    /** The arrow is drawn a bit smaller than its view; a custom badge fills it. */
    private static final float ARROW_CONTENT_SCALE = 0.8f;

    protected final BaseFragment fragment;
    protected final ButtonWithCounterView button;
    protected final LinearLayout bottomContainer;
    private final ArrowHeader header;
    private final int[] location;
    // assigned while the super constructor builds the list, so no initializers here
    private UniversalAdapter adapter;
    private ArrayList<View> views;
    private CharSequence title;
    private TextView titleView;
    private TextView subtitleView;
    private LinearLayout secondaryRow;
    private boolean touched;
    private RawSupporterEnterFrom enterFrom;
    private ValueAnimator enterAnimator;
    private float enterProgress;

    public RawSupporterSheet(BaseFragment fragment, Theme.ResourcesProvider resourcesProvider) {
        this(fragment, false, resourcesProvider);
    }

    public RawSupporterSheet(BaseFragment fragment, boolean needFocus, Theme.ResourcesProvider resourcesProvider) {
        super(fragment.getParentActivity(), fragment, needFocus, false, false, true, ActionBarType.FADING, resourcesProvider);
        enterProgress = 1f;
        location = new int[2];
        this.fragment = fragment;
        currentAccount = fragment.getCurrentAccount();
        Context context = fragment.getParentActivity();
        occupyNavigationBar = !needFocus;
        smoothKeyboardAnimationEnabled = needFocus;
        smoothKeyboardByBottom = needFocus;
        setApplyBottomPadding(false);
        topPadding = 0.2f;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));

        recyclerListView.setClipToPadding(false);
        recyclerListView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);
        recyclerListView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touched = true;
                }
                return false;
            }
        });
        // keep the header in view while the list settles, until the user scrolls it
        recyclerListView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!touched && recyclerListView.canScrollVertically(-1)) {
                layoutManager.scrollToPositionWithOffset(0, 0);
            }
        });

        views = new ArrayList<>();
        header = new ArrowHeader(context, resourcesProvider);

        button = new ButtonWithCounterView(context, true, resourcesProvider);
        button.setRound();
        bottomContainer = new LinearLayout(context);
        bottomContainer.setOrientation(LinearLayout.VERTICAL);
        bottomContainer.setBackground(createBottomBackdrop(getThemedColor(Theme.key_dialogBackground)));
        bottomContainer.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, needFocus ? 0 : AndroidUtilities.navigationBarHeight);
        bottomContainer.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, BUTTON_HEIGHT_DP, 0, 12, 16, 12, 12));
        bottomContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int height = b - t;
            if (recyclerListView.getPaddingBottom() != height) {
                recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, height);
            }
        });
        containerView.addView(bottomContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private static Drawable createBottomBackdrop(int color) {
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ColorUtils.setAlphaComponent(color, 0), ColorUtils.setAlphaComponent(color, 60), color});
        LayerDrawable drawable = new LayerDrawable(new Drawable[]{fade, new ColorDrawable(color)});
        drawable.setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        drawable.setLayerHeight(0, AndroidUtilities.dp(BOTTOM_FADE_DP));
        drawable.setLayerInsetTop(1, AndroidUtilities.dp(BOTTOM_FADE_DP));
        return drawable;
    }

    /** Title (bold, centred) and an optional subtitle under the header; resets the custom rows. */
    public void setContent(CharSequence title, CharSequence subtitle) {
        this.title = title;
        updateTitle();
        views.clear();
        views.add(header);
        Context context = getContext();

        titleView = new TextView(context);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextSize(20);
        titleView.setText(Emoji.replaceEmoji(title, titleView.getPaint().getFontMetricsInt(), false));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setPadding(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), 0);
        views.add(titleView);

        if (subtitle == null) {
            subtitleView = null;
            return;
        }
        LinkSpanDrawable.LinksTextView subtitleText = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
        subtitleText.setLinkTextColor(getThemedColor(Theme.key_chat_messageLinkIn));
        subtitleView = subtitleText;
        subtitleView.setText(subtitle);
        subtitleView.setTypeface(android.graphics.Typeface.DEFAULT);
        subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitleView.setTextSize(14);
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitleView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(6), AndroidUtilities.dp(22), AndroidUtilities.dp(14));
        views.add(subtitleView);
    }

    public void addView(View view) {
        views.add(view);
    }

    /** Adds a view right under the header. */
    public void addHeaderAccessory(View view) {
        views.add(views.indexOf(header) + 1, view);
    }

    /** The «Бейдж #N» pill under the header, in the arrow's gradient. */
    public void addNumberPill(int number) {
        if (number <= 0) {
            return;
        }
        RawSupporterArrowView arrow = header.arrowView;
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{arrow.color1, arrow.color2});
        background.setCornerRadius(AndroidUtilities.dp(12));
        TextView pill = new TextView(getContext());
        pill.setBackground(background);
        pill.setTypeface(AndroidUtilities.bold());
        pill.setTextSize(13);
        pill.setTextColor(RawSupporterHint.resolveTextColor(ColorUtils.blendARGB(arrow.color1, arrow.color2, 0.5f)));
        pill.setText(RawBadgeText.format(R.string.RawSupporterBadgeNameCustom, RawSupporters.formatNumber(number)));
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        FrameLayout frame = new FrameLayout(getContext());
        frame.addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 24, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
        addHeaderAccessory(frame);
    }

    /** The badge caption in a message-like bubble under the title. */
    public TextView addCaptionBubble(CharSequence text) {
        LinkSpanDrawable.LinksTextView textView = new LinkSpanDrawable.LinksTextView(getContext(), resourcesProvider);
        textView.setLinkTextColor(getThemedColor(Theme.key_chat_messageLinkIn));
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(15);
        textView.setTypeface(android.graphics.Typeface.DEFAULT);
        textView.setText(text);
        MessageDrawable bubble = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, false, resourcesProvider);
        bubble.shadow = false;
        bubble.overrideColor = getThemedColor(Theme.key_windowBackgroundGray);
        textView.setBackground(bubble);
        textView.setPadding(AndroidUtilities.dp(19), AndroidUtilities.dp(9), AndroidUtilities.dp(15), AndroidUtilities.dp(8));
        FrameLayout frame = new FrameLayout(getContext());
        frame.addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 10, 32, 4));
        views.add(views.indexOf(titleView) + 1, frame);
        return textView;
    }

    public RawSupporterArrowView getArrowView() {
        return header.arrowView;
    }

    /** A grey-filled button in a row under the main one. */
    public ButtonWithCounterView addSecondaryButton(CharSequence text, View.OnClickListener onClick) {
        ButtonWithCounterView secondary = new ButtonWithCounterView(getContext(), true, resourcesProvider).setNeutral();
        secondary.setRound();
        secondary.setText(text, false);
        secondary.setOnClickListener(onClick);
        if (secondaryRow == null) {
            ((LinearLayout.LayoutParams) button.getLayoutParams()).bottomMargin = AndroidUtilities.dp(8);
            secondaryRow = new LinearLayout(getContext());
            secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
            bottomContainer.addView(secondaryRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, BUTTON_HEIGHT_DP, 0, 12, 0, 12, 12));
        }
        secondaryRow.addView(secondary, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, secondaryRow.getChildCount() > 0 ? 8 : 0, 0, 0, 0));
        return secondary;
    }

    public void setBadge(long documentId) {
        header.arrowView.setBadge(currentAccount, documentId);
    }

    /** Applies the rows set so far. */
    public void commit() {
        if (adapter != null) {
            adapter.update(false);
        }
        layoutManager.scrollToPositionWithOffset(0, 0);
    }

    @Override
    protected CharSequence getTitle() {
        return title != null ? title : "";
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        adapter = new UniversalAdapter(listView, getContext(), currentAccount, 0, true, this::fillItems, resourcesProvider);
        return adapter;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (views == null) {
            return;
        }
        for (View view : views) {
            UItem item = UItem.asCustom(view);
            item.transparent = true;
            items.add(item);
        }
        items.add(UItem.asSpace(AndroidUtilities.dp(8)));
    }

    public void setEnterFrom(RawSupporterEnterFrom from) {
        enterFrom = from;
    }

    @Override
    protected boolean onCustomOpenAnimation() {
        final RawSupporterEnterFrom from = enterFrom;
        if (from == null) {
            return false;
        }
        enterProgress = 0f;
        header.arrowView.setIconHidden(true);
        from.drawable.setAlpha(0);
        from.invalidate();
        if (!header.arrowView.hasBadge()) {
            header.arrowView.iconView.startEnterAnimation(-360, 100);
        }
        enterAnimator = ValueAnimator.ofFloat(0f, 1f);
        enterAnimator.addUpdateListener(a -> {
            enterProgress = (float) a.getAnimatedValue();
            container.invalidate();
        });
        enterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                enterProgress = 1f;
                header.arrowView.setIconHidden(false);
                container.postOnAnimation(() -> {
                    enterFrom = null;
                    container.invalidate();
                });
                // the badge at its place fades back in
                ValueAnimator restore = ValueAnimator.ofInt(0, 255);
                restore.addUpdateListener(a -> {
                    from.drawable.setAlpha((int) a.getAnimatedValue());
                    from.invalidate();
                });
                restore.start();
            }
        });
        enterAnimator.setDuration(ENTER_DURATION);
        enterAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        enterAnimator.start();
        return super.onCustomOpenAnimation();
    }

    @Override
    protected void mainContainerDispatchDraw(Canvas canvas) {
        super.mainContainerDispatchDraw(canvas);
        RawSupporterEnterFrom from = enterFrom;
        if (from == null) {
            return;
        }
        View iconView = header.arrowView.getIconView();
        container.getLocationOnScreen(location);
        int containerX = location[0];
        int containerY = location[1];
        float fromX = from.screenX - containerX;
        float fromY = from.screenY - containerY;
        iconView.getLocationOnScreen(location);
        float toX = location[0] - containerX + iconView.getWidth() / 2f;
        float toY = location[1] - containerY + iconView.getHeight() / 2f - containerView.getTranslationY();

        Drawable drawable = from.drawable;
        if (drawable instanceof AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable && ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) drawable).getDrawable() != null) {
            drawable = ((AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable) drawable).getDrawable();
        }
        ColorFilter colorFilter = drawable != from.drawable ? from.drawable.getColorFilter() : null;
        float fromSize = from.scale * drawable.getIntrinsicWidth();
        float toSize = iconView.getHeight() * (header.arrowView.hasBadge() ? 1f : ARROW_CONTENT_SCALE);
        if (fromSize <= 0 || toSize <= 0) {
            return;
        }
        float x = AndroidUtilities.lerp(fromX, toX, CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(enterProgress));
        float y = AndroidUtilities.lerp(fromY, toY, enterProgress);

        // the tapped badge, growing and fading out
        canvas.save();
        float scale = AndroidUtilities.lerp(from.scale, toSize / fromSize * from.scale, enterProgress);
        canvas.scale(scale, scale, x, y);
        int cx = (int) x;
        int cy = (int) y;
        drawable.setBounds(cx - drawable.getIntrinsicWidth() / 2, cy - drawable.getIntrinsicHeight() / 2, cx + drawable.getIntrinsicWidth() / 2, cy + drawable.getIntrinsicHeight() / 2);
        drawable.setAlpha((int) ((1f - Utilities.clamp(enterProgress, 1f, 0f)) * 255));
        if (colorFilter != null) {
            drawable.setColorFilter(colorFilter);
        }
        drawable.draw(canvas);
        if (colorFilter != null) {
            drawable.setColorFilter(null);
        }
        drawable.setAlpha(drawable == from.drawable ? 0 : 255);
        canvas.restore();

        // the header icon, growing from the badge's size into place
        canvas.save();
        float iconScale = AndroidUtilities.lerp(fromSize / toSize, 1f, enterProgress);
        canvas.scale(iconScale, iconScale, x, y);
        canvas.translate(x - iconView.getWidth() / 2f, y - iconView.getHeight() / 2f);
        iconView.draw(canvas);
        canvas.restore();
    }

    @Override
    public void dismiss() {
        if (enterAnimator != null) {
            enterAnimator.cancel();
        }
        super.dismiss();
    }

    /** The header row: the arrow, 190dp high, with room for the particles above. */
    public static class ArrowHeader extends FrameLayout {
        final RawSupporterArrowView arrowView;

        public ArrowHeader(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            setClipChildren(false);
            arrowView = new RawSupporterArrowView(context, resourcesProvider);
            addView(arrowView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, HEADER_HEIGHT_DP, Gravity.TOP, 0, 10, 0, 0));
        }
    }
}
