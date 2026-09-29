package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.R;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Bottom sheet showing any object as a rendered message preview (optional),
 * a readable field list and raw JSON.
 */
public class RawObjectSheet extends BottomSheet {

    private static final int MAX_TEXT = 300_000;

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;

    private final TextView titleView;
    private final TextView subtitleView;
    private final FrameLayout previewContainer;
    private final TextView fieldsTab;
    private final TextView jsonTab;
    private final TextView bodyView;
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

        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        fieldsTab = createChip(context, "Fields");
        fieldsTab.setOnClickListener(v -> setShowJson(false));
        jsonTab = createChip(context, "JSON");
        jsonTab.setOnClickListener(v -> setShowJson(true));
        tabs.addView(fieldsTab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 8, 0));
        tabs.addView(jsonTab, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 8, 0));
        TextView copyChip = createChip(context, "Copy");
        styleChip(copyChip, false);
        copyChip.setOnClickListener(v -> copy());
        tabs.addView(copyChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        root.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 10, 16, 6));

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = (int) (AndroidUtilities.displaySize.y * 0.45f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        bodyView = new TextView(context);
        bodyView.setTypeface(Typeface.MONOSPACE);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        bodyView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        bodyView.setTextIsSelectable(true);
        bodyView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        scrollView.addView(bodyView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsLayout = new LinearLayout(context);
        actionsLayout.setOrientation(LinearLayout.HORIZONTAL);
        actionsLayout.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8), 0);
        actionsScroll.addView(actionsLayout);
        root.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 8));

        setCustomView(root);
        setObject(null, object);
    }

    private TextView createChip(Context context, String text) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        return chip;
    }

    private void styleChip(TextView chip, boolean active) {
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        if (active) {
            chip.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), accent, Theme.multAlpha(accent, 0.8f)));
        } else {
            chip.setTextColor(accent);
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), Theme.multAlpha(accent, 0.12f), Theme.multAlpha(accent, 0.24f)));
        }
    }

    /** Adds a button to the bottom action row. */
    public TextView addAction(String text, View.OnClickListener listener) {
        TextView chip = createChip(getContext(), text);
        styleChip(chip, false);
        chip.setOnClickListener(listener);
        actionsLayout.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
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
        setShowJson(showJson);
    }

    private void setShowJson(boolean value) {
        showJson = value;
        styleChip(fieldsTab, !value);
        styleChip(jsonTab, value);
        String text = value ? json : fields;
        if (text != null && text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT) + "\n… truncated, use Copy for the full dump";
        }
        bodyView.setText(text);
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
        previewContainer.setVisibility(View.VISIBLE);
    }
}
