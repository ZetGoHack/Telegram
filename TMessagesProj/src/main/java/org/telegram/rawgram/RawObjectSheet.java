package org.telegram.rawgram;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
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
 */
public class RawObjectSheet extends BottomSheet {

    private static final int MAX_TEXT = 300_000;

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;

    private final TextView titleView;
    private final TextView subtitleView;
    private final FrameLayout previewContainer;
    private final HorizontalScrollView tabsScroll;
    private final LinearLayout tabsLayout;
    private final ArrayList<TextView> tabs = new ArrayList<>();
    private final TextView typeLabel;
    private final TextView viewToggle;
    private final TextView bodyView;
    private final LinearLayout actionsSection;
    private final LinearLayout actionsLayout;

    private ChatMessageCell previewCell;
    private Object object;
    private String json;
    private String fields;
    private boolean showJson;

    public RawObjectSheet(Context context, int currentAccount, CharSequence title, Object object, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));
        showJson = RawgramConfig.isRawViewJson();

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

        FrameLayout header = new FrameLayout(context);
        codeBlock.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38));

        typeLabel = new TextView(context);
        typeLabel.setTypeface(Typeface.MONOSPACE);
        typeLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        typeLabel.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        typeLabel.setSingleLine(true);
        typeLabel.setEllipsize(TextUtils.TruncateAt.END);
        typeLabel.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(typeLabel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 12, 0, 132, 0));

        LinearLayout headerRight = new LinearLayout(context);
        headerRight.setOrientation(LinearLayout.HORIZONTAL);
        headerRight.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(headerRight, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.RIGHT, 0, 0, 4, 0));

        viewToggle = new TextView(context);
        viewToggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        viewToggle.setTypeface(AndroidUtilities.bold());
        viewToggle.setGravity(Gravity.CENTER);
        viewToggle.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        viewToggle.setTextColor(accent);
        viewToggle.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), Theme.multAlpha(accent, 0.12f), Theme.multAlpha(accent, 0.24f)));
        viewToggle.setOnClickListener(v -> {
            setShowJson(!showJson);
            RawgramConfig.setRawViewJson(showJson);
        });
        headerRight.addView(viewToggle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26));

        ImageView copyButton = new ImageView(context);
        copyButton.setImageResource(R.drawable.msg_copy);
        copyButton.setScaleType(ImageView.ScaleType.CENTER);
        copyButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogTextGray2), PorterDuff.Mode.SRC_IN));
        copyButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        copyButton.setContentDescription("Copy");
        copyButton.setOnClickListener(v -> copy());
        headerRight.addView(copyButton, LayoutHelper.createLinear(34, 34, 4, 0, 0, 0));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.multAlpha(text, 0.10f));
        codeBlock.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.42f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        bodyView = new TextView(context);
        bodyView.setTypeface(Typeface.MONOSPACE);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        bodyView.setTextColor(text);
        bodyView.setTextIsSelectable(true);
        bodyView.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        scrollView.addView(bodyView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        codeBlock.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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
        typeLabel.setText(TLDumper.typeName(object));
        setSubtitle(subtitle != null ? subtitle : TLDumper.typeName(object));
        setShowJson(showJson);
    }

    private void setShowJson(boolean value) {
        showJson = value;
        viewToggle.setText(value ? "JSON ⇄" : "Fields ⇄");
        String text = value ? json : fields;
        boolean truncated = text != null && text.length() > MAX_TEXT;
        if (truncated) {
            text = text.substring(0, MAX_TEXT);
        }
        CharSequence rendered = text == null ? "" : value
                ? RawSyntax.json(text, RawSyntax.Palette.of(Theme.isCurrentThemeDark()))
                : RawSyntax.fields(text, RawSyntax.Palette.of(Theme.isCurrentThemeDark()));
        if (truncated) {
            bodyView.setText(new android.text.SpannableStringBuilder(rendered).append("\n… truncated, Copy копирует полный дамп"));
        } else {
            bodyView.setText(rendered);
        }
    }

    private void copy() {
        String text = showJson ? json : fields;
        if (text == null) {
            return;
        }
        AndroidUtilities.addToClipboard(text);
        RawNotify.show(this, R.drawable.msg_copy, showJson ? "JSON скопирован" : "Поля скопированы");
    }

    /** Renders the message exactly like a chat cell would; null hides the preview. */
    public void setPreview(MessageObject messageObject) {
        if (messageObject == null) {
            previewContainer.setVisibility(View.GONE);
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
}
