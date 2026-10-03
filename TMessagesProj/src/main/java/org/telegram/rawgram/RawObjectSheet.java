package org.telegram.rawgram;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Raw viewer sheet, top to bottom:
 * title/subtitle, optional rendered message preview,
 * a row of object tabs (what is shown), a code block (type label, view toggle,
 * copy in the top-right corner, highlighted dump) and, separately, an actions
 * row for things that actually send requests.
 * <p>
 * Swipe to dismiss: a drag that starts outside the scrolling code body (title, preview, tabs, code
 * header, actions) dismisses as usual. A drag that starts inside a scrollable body never lets the sheet
 * intercept it; the body scrolls on its own and only pulls the sheet down through nested scrolling when
 * the gesture began with the body settled at the very top and the content hasn't moved during the
 * gesture (so an upward scroll, or a fling that just reached the top, never dismisses).
 */
public class RawObjectSheet extends BottomSheet {

    private static final int MAX_TEXT = 300_000;

    public static final int MODE_TREE = 0, MODE_JSON = 1, MODE_FIELDS = 2;
    /** A body that scrolled this recently is still moving (fling) and must not start a pull-to-dismiss. */
    private static final long SETTLE_MS = 300;

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;

    private final TextView titleView;
    private final TextView subtitleView;
    private final FrameLayout previewContainer;
    private final HorizontalScrollView tabsScroll;
    private final LinearLayout tabsLayout;
    private final ArrayList<TextView> tabs = new ArrayList<>();
    private final TextView typeLabel;
    private final ModeSwitch modeSwitch;
    private final FrameLayout bodyFrame;
    private final ScrollView scrollView;
    private final TextView bodyView;
    private final RawTreeView treeView;
    private final LinearLayout actionsSection;
    private final LinearLayout actionsLayout;

    private ChatMessageCell previewCell;
    private Object object;
    private String json;
    private String fields;
    private int mode;

    // swipe-to-dismiss gating, decided at ACTION_DOWN
    private long scrollViewLastScroll;
    private boolean touchInScrollableBody;
    private boolean pullArmed;
    private int pullDownOffset;

    public RawObjectSheet(Context context, int currentAccount, CharSequence title, Object object, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));
        mode = loadMode();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(title);
        root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        subtitleView.setTextIsSelectable(true);
        root.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 8));

        previewContainer = new FrameLayout(context);
        previewContainer.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        previewContainer.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        previewContainer.setVisibility(View.GONE);
        root.addView(previewContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 1. objects: what the code block shows
        tabsScroll = new HorizontalScrollView(context);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsLayout = new LinearLayout(context);
        tabsLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabsLayout.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        tabsScroll.addView(tabsLayout);
        tabsScroll.setVisibility(View.GONE);
        root.addView(tabsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        // 2. code block
        int text = getThemedColor(Theme.key_dialogTextBlack);
        LinearLayout codeBlock = new LinearLayout(context);
        codeBlock.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable codeBg = new GradientDrawable();
        codeBg.setCornerRadius(AndroidUtilities.dp(12));
        codeBg.setColor(Theme.multAlpha(text, 0.06f));
        codeBg.setStroke(AndroidUtilities.dp(1), Theme.multAlpha(text, 0.10f));
        codeBlock.setBackground(codeBg);
        root.addView(codeBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 10, 12, 0));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        codeBlock.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38));

        typeLabel = new TextView(context);
        typeLabel.setTypeface(Typeface.MONOSPACE);
        typeLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        typeLabel.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        typeLabel.setSingleLine(true);
        typeLabel.setEllipsize(TextUtils.TruncateAt.END);
        typeLabel.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(typeLabel, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 12, 0, 8, 0));

        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        SpannableString treeLabel = new SpannableString("Дерево (beta)");
        treeLabel.setSpan(new RelativeSizeSpan(0.8f), 7, treeLabel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        modeSwitch = new ModeSwitch(context, accent, getThemedColor(Theme.key_featuredStickers_buttonText),
                new CharSequence[]{treeLabel, "JSON", "Поля"}, this::switchMode);
        header.addView(modeSwitch, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26));

        ImageView copyButton = new ImageView(context);
        copyButton.setImageResource(R.drawable.msg_copy);
        copyButton.setScaleType(ImageView.ScaleType.CENTER);
        copyButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogTextGray2), PorterDuff.Mode.SRC_IN));
        copyButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        copyButton.setContentDescription("Copy");
        copyButton.setOnClickListener(v -> {
            copy();
            RawMotion.copied(v);
        });
        header.addView(copyButton, LayoutHelper.createLinear(34, 34, 4, 0, 4, 0));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.multAlpha(text, 0.10f));
        codeBlock.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        int maxBodyHeight = (int) (AndroidUtilities.displaySize.y * 0.42f);
        bodyFrame = new FrameLayout(context);
        codeBlock.addView(bodyFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxBodyHeight, MeasureSpec.AT_MOST));
            }

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                if (t != oldt) {
                    scrollViewLastScroll = SystemClock.uptimeMillis();
                }
            }
        };
        // nested scrolling is switched on per gesture, only when a pull-to-dismiss is allowed
        scrollView.setNestedScrollingEnabled(false);
        bodyView = new TextView(context);
        bodyView.setTypeface(Typeface.MONOSPACE);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        bodyView.setTextColor(text);
        bodyView.setTextIsSelectable(true);
        bodyView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        scrollView.addView(bodyView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        bodyFrame.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        treeView = new RawTreeView(context, currentAccount, resourcesProvider);
        treeView.setMaxHeight(maxBodyHeight);
        treeView.setNestedScrollingEnabled(false);
        treeView.setCopyHandler((value, toast) -> {
            AndroidUtilities.addToClipboard(value);
            RawNotify.show(this, R.drawable.msg_copy, toast);
        });
        bodyFrame.addView(treeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 3. actions: things that really talk to the server / chat
        actionsSection = new LinearLayout(context);
        actionsSection.setOrientation(LinearLayout.VERTICAL);
        actionsSection.setVisibility(View.GONE);
        TextView actionsLabel = new TextView(context);
        actionsLabel.setText("Действия");
        actionsLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        actionsLabel.setTypeface(AndroidUtilities.bold());
        actionsLabel.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        actionsSection.addView(actionsLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 6));
        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsLayout = new LinearLayout(context);
        actionsLayout.setOrientation(LinearLayout.HORIZONTAL);
        actionsLayout.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        actionsScroll.addView(actionsLayout);
        actionsSection.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(actionsSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 10));

        setCustomView(root);
        RawMotion.reveal(root);
        // object tabs and actions pop in after their sections arrive
        RawMotion.popRowOnShow(tabsLayout, 200, 35);
        RawMotion.popRowOnShow(actionsLayout, 260, 35);
        modeSwitch.select(mode, false);
        setObject(null, object);
    }

    private TextView createChip(String text, int heightDp) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        chip.setMinHeight(AndroidUtilities.dp(heightDp));
        return chip;
    }

    private void styleTab(TextView tab, boolean selected) {
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        if (selected) {
            tab.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            tab.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), accent, Theme.multAlpha(accent, 0.8f)));
        } else {
            tab.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            int base = getThemedColor(Theme.key_dialogTextBlack);
            tab.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), Theme.multAlpha(base, 0.07f), Theme.multAlpha(base, 0.14f)));
        }
    }

    /** An object the code block can show; the first tab added is selected. */
    public TextView addObjectTab(String text, Runnable onSelect) {
        TextView tab = createChip(text, 32);
        tab.setOnClickListener(v -> {
            selectTab(tab);
            onSelect.run();
        });
        tabs.add(tab);
        styleTab(tab, tabs.size() == 1);
        tabsLayout.addView(tab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 6, 0));
        tabsScroll.setVisibility(View.VISIBLE);
        return tab;
    }

    public void selectTab(TextView selected) {
        for (TextView tab : tabs) {
            styleTab(tab, tab == selected);
        }
        RawAnim.pop(selected);
    }

    public void selectTab(int index) {
        if (index >= 0 && index < tabs.size()) {
            selectTab(tabs.get(index));
        }
    }

    /** A button that performs a real action (network request, sending, reroll). */
    public TextView addAction(String text, View.OnClickListener listener) {
        TextView chip = createChip(text, 38);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        chip.setTextColor(accent);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(19));
        bg.setStroke(AndroidUtilities.dp(1.5f), Theme.multAlpha(accent, 0.6f));
        bg.setColor(Theme.multAlpha(accent, 0.06f));
        chip.setBackground(bg);
        chip.setOnClickListener(listener);
        actionsLayout.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, 0, 0, 8, 0));
        actionsSection.setVisibility(View.VISIBLE);
        return chip;
    }

    public void setSubtitle(CharSequence subtitle) {
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
    }

    public void setTitleText(CharSequence title) {
        titleView.setText(title);
    }

    public Object getObject() {
        return object;
    }

    public void setObject(CharSequence subtitle, Object object) {
        this.object = object;
        String json;
        String fields;
        try {
            json = TLDumper.toJson(object);
            fields = TLDumper.toFields(object);
        } catch (Throwable e) {
            json = fields = "dump failed: " + e;
        }
        this.json = json;
        this.fields = fields;
        setSubtitle(subtitle != null ? subtitle : TLDumper.typeName(object));
        RawAnim.crossfade(bodyFrame, () -> {
            typeLabel.setText(TLDumper.typeName(object));
            try {
                treeView.setObject(object);
            } catch (Throwable e) {
                treeView.setObject("tree failed: " + e);
            }
            applyMode(mode);
        });
    }

    private void applyMode(int value) {
        mode = value;
        boolean tree = value == MODE_TREE;
        treeView.setVisibility(tree ? View.VISIBLE : View.GONE);
        scrollView.setVisibility(tree ? View.GONE : View.VISIBLE);
        if (tree) {
            return;
        }
        boolean showJson = value == MODE_JSON;
        String text = showJson ? json : fields;
        boolean truncated = text != null && text.length() > MAX_TEXT;
        if (truncated) {
            text = text.substring(0, MAX_TEXT);
        }
        CharSequence rendered = text == null ? "" : showJson
                ? RawSyntax.json(text, RawSyntax.Palette.of(Theme.isCurrentThemeDark()))
                : RawSyntax.fields(text, RawSyntax.Palette.of(Theme.isCurrentThemeDark()));
        if (truncated) {
            bodyView.setText(new android.text.SpannableStringBuilder(rendered).append("\n… truncated, Copy копирует полный дамп"));
        } else {
            bodyView.setText(rendered);
        }
    }

    private void switchMode(int value) {
        if (value == mode) {
            return;
        }
        saveMode(value);
        RawAnim.crossfade(bodyFrame, () -> applyMode(value));
    }

    private void copy() {
        boolean asFields = mode == MODE_FIELDS;
        String text = asFields ? fields : json;
        if (text == null) {
            return;
        }
        AndroidUtilities.addToClipboard(text);
        RawNotify.show(this, R.drawable.msg_copy, asFields ? "Поля скопированы" : "JSON скопирован");
    }

    // ---- view mode, remembered across sheets ----

    private static SharedPreferences sheetPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram_object_sheet", Context.MODE_PRIVATE);
    }

    /** Tree lives in this sheet's own prefs; JSON vs Fields stays in {@link RawgramConfig#isRawViewJson()}. */
    private static int loadMode() {
        try {
            if (sheetPrefs().getBoolean("viewTree", false)) {
                return MODE_TREE;
            }
        } catch (Throwable ignore) {
        }
        return RawgramConfig.isRawViewJson() ? MODE_JSON : MODE_FIELDS;
    }

    private static void saveMode(int mode) {
        try {
            sheetPrefs().edit().putBoolean("viewTree", mode == MODE_TREE).apply();
        } catch (Throwable ignore) {
        }
        if (mode != MODE_TREE) {
            RawgramConfig.setRawViewJson(mode == MODE_JSON);
        }
    }

    // ---- swipe to dismiss vs. scrolling the code body ----

    private View activeBody() {
        return mode == MODE_TREE ? treeView : scrollView;
    }

    private int bodyOffset(View body) {
        return body == treeView ? treeView.computeVerticalScrollOffset() : scrollView.getScrollY();
    }

    private static boolean hit(View view, float rawX, float rawY) {
        if (view == null || !view.isShown()) {
            return false;
        }
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX < loc[0] + view.getWidth() && rawY >= loc[1] && rawY < loc[1] + view.getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent ev) {
        // runs before the sheet's container sees the event, so canDismissWithSwipe() is already up to date
        int action = ev.getActionMasked();
        View body = activeBody();
        if (action == MotionEvent.ACTION_DOWN) {
            boolean inBody = hit(body, ev.getRawX(), ev.getRawY());
            touchInScrollableBody = inBody && (body.canScrollVertically(-1) || body.canScrollVertically(1));
            boolean atTop = !body.canScrollVertically(-1);
            long lastScroll = body == treeView ? treeView.getLastScrollTime() : scrollViewLastScroll;
            boolean settled = SystemClock.uptimeMillis() - lastScroll > SETTLE_MS
                    && (body != treeView || treeView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
            pullArmed = touchInScrollableBody && atTop && settled;
            pullDownOffset = bodyOffset(body);
            setBodyPull(body, pullArmed);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (pullArmed && bodyOffset(body) != pullDownOffset) {
                // the content moved during this gesture: from here on it's a plain scroll, reaching the top won't pull the sheet
                pullArmed = false;
                setBodyPull(body, false);
            }
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (pullArmed) {
                pullArmed = false;
                setBodyPull(body, false);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void setBodyPull(View body, boolean enabled) {
        scrollView.setNestedScrollingEnabled(enabled && body == scrollView);
        treeView.setNestedScrollingEnabled(enabled && body == treeView);
    }

    /**
     * The sheet's own drag tracking (which would grab any downward drag past the touch slop) is off for
     * gestures that start inside a scrollable body; those reach the sheet only via nested scrolling.
     */
    @Override
    protected boolean canDismissWithSwipe() {
        return super.canDismissWithSwipe() && !touchInScrollableBody;
    }

    /** Renders the message exactly like a chat cell would; null hides the preview. */
    public void setPreview(MessageObject messageObject) {
        if (messageObject == null) {
            RawAnim.layout(previewContainer);
            previewContainer.setVisibility(View.GONE);
            return;
        }
        if (previewCell != null && previewContainer.getVisibility() == View.VISIBLE) {
            // an update of the shown message: resize smoothly and fade the new content in
            RawAnim.layout(previewContainer);
            previewCell.setAlpha(0f);
            previewCell.setMessageObject(messageObject, null, false, false, false);
            previewCell.requestLayout();
            previewCell.animate().alpha(1f).setDuration(RawAnim.DURATION).start();
            return;
        }
        if (previewCell == null) {
            previewCell = new ChatMessageCell(getContext(), currentAccount, false, null, resourcesProvider);
            previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {});
            previewCell.setFullyDraw(true);
            previewContainer.addView(previewCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        previewCell.setMessageObject(messageObject, null, false, false, false);
        previewCell.requestLayout();
        previewCell.invalidate();
        previewContainer.setVisibility(View.VISIBLE);
    }

    /** Segmented "Дерево (beta) / JSON / Поля" choice with a sliding accent thumb. */
    private static class ModeSwitch extends LinearLayout {

        interface Listener {
            void onSelect(int index);
        }

        private final TextView[] items;
        private final int accent;
        private final int selectedText;
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int selected = -1;
        private float position;
        private ValueAnimator animator;

        ModeSwitch(Context context, int accent, int selectedText, CharSequence[] labels, Listener listener) {
            super(context);
            this.accent = accent;
            this.selectedText = selectedText;
            setOrientation(HORIZONTAL);
            setWillNotDraw(false);
            thumbPaint.setColor(accent);
            trackPaint.setColor(Theme.multAlpha(accent, 0.12f));
            items = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                TextView item = new TextView(context);
                item.setText(labels[i]);
                item.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                item.setTypeface(AndroidUtilities.bold());
                item.setGravity(Gravity.CENTER);
                item.setSingleLine(true);
                item.setPadding(AndroidUtilities.dp(9), 0, AndroidUtilities.dp(9), 0);
                item.setTextColor(accent);
                final int index = i;
                item.setOnClickListener(v -> {
                    if (index != selected) {
                        select(index, true);
                        RawAnim.pop(v);
                        listener.onSelect(index);
                    }
                });
                items[i] = item;
                addView(item, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
            }
        }

        void select(int index, boolean animated) {
            if (index < 0 || index >= items.length) {
                return;
            }
            int from = selected;
            selected = index;
            for (int i = 0; i < items.length; i++) {
                items[i].setTextColor(i == index ? selectedText : accent);
            }
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animated || from < 0 || !RawMotion.active() || !isAttachedToWindow()) {
                position = index;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(position, index);
            animator.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(320);
            animator.setInterpolator(RawMotion.EMPHASIZED);
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, r, r, trackPaint);
            if (selected < 0 || items.length == 0) {
                return;
            }
            int lo = Math.max(0, Math.min(items.length - 1, (int) Math.floor(position)));
            int hi = Math.min(items.length - 1, lo + 1);
            float t = position - lo;
            float left = lerp(items[lo].getLeft(), items[hi].getLeft(), t);
            float right = lerp(items[lo].getRight(), items[hi].getRight(), t);
            float stretch = RawMotion.stretch(t, AndroidUtilities.dp(6));
            rect.set(left - stretch / 2f, 0, right + stretch / 2f, getHeight());
            canvas.drawRoundRect(rect, r, r, thumbPaint);
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }
}
