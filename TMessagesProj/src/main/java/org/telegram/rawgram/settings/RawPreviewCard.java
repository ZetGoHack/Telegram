package org.telegram.rawgram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * A live preview shown the way exteraGram's settings show theirs: inset into the section, on a rounded tinted
 * card with a hairline outline, optionally with a divider under the cell when more rows follow in the section.
 * The preview view keeps its own size; the card clips it to the rounded corners and draws the outline on top.
 * Layout after exteraGram's ChatListPreviewCell / AvatarCornersPreviewCell (exteraSquad, GPL).
 */
@SuppressLint("ViewConstructor")
public class RawPreviewCard extends FrameLayout {

    /** Gap between the card and the section edges. */
    public static final int INSET_DP = 21;

    private final View content;
    private final FrameLayout card;
    private boolean divider;

    public RawPreviewCard(Context context, View content) {
        this(context, content, INSET_DP, INSET_DP);
    }

    public RawPreviewCard(Context context, View content, int topDp, int bottomDp) {
        super(context);
        this.content = content;
        setWillNotDraw(false);

        RawPreviewBackground outline = new RawPreviewBackground(RawPreviewBackground.DEFAULT_RADIUS_DP, true);
        card = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                super.dispatchDraw(canvas);
                // the outline goes over the preview: wallpapers and lists paint opaque backgrounds of their own
                outline.setBounds(0, 0, getWidth(), getHeight());
                outline.draw(canvas);
            }
        };
        card.setBackground(new RawPreviewBackground());
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(RawPreviewBackground.DEFAULT_RADIUS_DP));
            }
        });
        card.setClipToOutline(true);
        card.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, INSET_DP, topDp, INSET_DP, bottomDp));
    }

    public View getContent() {
        return content;
    }

    /** Draws a divider under the cell, for a card followed by more rows in the same section. */
    public RawPreviewCard setDivider(boolean divider) {
        if (this.divider != divider) {
            this.divider = divider;
            invalidate();
        }
        return this;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (card != null) {
            card.invalidate();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (divider && Theme.dividerPaint != null) {
            canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }
}
