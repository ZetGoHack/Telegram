package org.telegram.rawgram.drawer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * The scrolling list of menu rows under the header, with thin dividers between the groups and a fade at the top
 * once it is scrolled. Ported from exteraGram's drawer (exteraSquad, GPL).
 */
final class RawDrawerMenuView extends ScrollView {

    private static final int COLOR_KEY_BACKGROUND = Theme.key_windowBackgroundWhite;
    private static final int FADE_HEIGHT_DP = 16;

    private final LinearLayout container;
    private final Paint topFadePaint = new Paint();
    private int topFadeColor;
    private boolean topFadeReady;
    private Runnable onItemClick;

    RawDrawerMenuView(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(8) + AndroidUtilities.navigationBarHeight);
        addView(container, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateTopFade();
    }

    /** Runs before a row's own action (the drawer closes itself). */
    void setOnItemClick(Runnable onItemClick) {
        this.onItemClick = onItemClick;
    }

    void clearMenu() {
        container.removeAllViews();
    }

    void rebuildMenu(ArrayList<RawDrawerItems.Item> items, int account) {
        clearMenu();
        container.setPadding(0, dp(8), 0, dp(8) + AndroidUtilities.navigationBarHeight);
        boolean hasRows = false;
        boolean dividerPending = false;
        for (RawDrawerItems.Item item : items) {
            if (item == null) {
                dividerPending = hasRows;
                continue;
            }
            if (dividerPending) {
                View divider = new View(getContext());
                divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
                container.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density,
                        Gravity.FILL_HORIZONTAL | Gravity.BOTTOM, 12, 8, 12, 8));
                dividerPending = false;
            }
            RawDrawerMenuItemView row = new RawDrawerMenuItemView(getContext());
            row.setItem(item, account);
            row.setOnClickListener(v -> {
                if (onItemClick != null) {
                    onItemClick.run();
                }
                if (item.onClick != null) {
                    item.onClick.run();
                }
            });
            if (item.onLongClick != null) {
                row.setOnLongClickListener(v -> {
                    if (onItemClick != null) {
                        onItemClick.run();
                    }
                    item.onLongClick.run();
                    return true;
                });
            }
            container.addView(row, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, dp(RawDrawerMenuItemView.HEIGHT_DP)));
            hasRows = true;
        }
    }

    void updateUnreadCounters(int account) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof RawDrawerMenuItemView) {
                ((RawDrawerMenuItemView) child).updateUnreadCounter(account);
            }
        }
    }

    void updateColors() {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof RawDrawerMenuItemView) {
                ((RawDrawerMenuItemView) child).updateColors();
            } else {
                child.setBackgroundColor(Theme.getColor(Theme.key_divider));
            }
        }
        updateTopFade();
    }

    private void updateTopFade() {
        int color = Theme.getColor(COLOR_KEY_BACKGROUND);
        if (topFadeReady && color == topFadeColor) {
            return;
        }
        topFadeReady = true;
        topFadeColor = color;
        topFadePaint.setShader(new LinearGradient(0, 0, 0, dp(FADE_HEIGHT_DP), new int[]{color, color & 0x00ffffff}, null, Shader.TileMode.CLAMP));
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        if (getScrollY() > 0) {
            canvas.save();
            canvas.translate(0, getScrollY());
            canvas.drawRect(0, 0, getWidth(), dp(FADE_HEIGHT_DP), topFadePaint);
            canvas.restore();
        }
    }
}
