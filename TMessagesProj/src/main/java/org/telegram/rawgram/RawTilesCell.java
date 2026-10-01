package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * A row of selectable tiles (Nagram / exteraGram appearance style): every tile is a rounded card with a
 * live sample inside and a label under it; the selected one gets an accent outline and an accent label.
 */
public class RawTilesCell extends LinearLayout {

    public interface OnTileClick {
        void onClick(int index, Tile tile);
    }

    private final ArrayList<Tile> tiles = new ArrayList<>();
    private int selected = -1;

    public RawTilesCell(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setPadding(dp(12), dp(14), dp(12), dp(12));
    }

    /** Adds a tile showing {@code content} in a card {@code cardHeightDp} high. */
    public Tile addTile(View content, int cardHeightDp, String label, OnTileClick onClick) {
        final int index = tiles.size();
        Tile tile = new Tile(getContext(), content, cardHeightDp, label);
        tile.setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onClick(index, tile);
            }
        });
        tiles.add(tile);
        addView(tile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.TOP, index == 0 ? 0 : 5, 0, 5, 0));
        // keep the outer gaps even: the first tile has no left margin, the last no right margin
        for (int i = 0; i < tiles.size(); i++) {
            LayoutParams lp = (LayoutParams) tiles.get(i).getLayoutParams();
            lp.leftMargin = i == 0 ? 0 : dp(5);
            lp.rightMargin = i == tiles.size() - 1 ? 0 : dp(5);
        }
        return tile;
    }

    public void setSelected(int index, boolean animated) {
        selected = index;
        for (int i = 0; i < tiles.size(); i++) {
            tiles.get(i).setChecked(i == index, animated);
        }
    }

    public int getSelected() {
        return selected;
    }

    public Tile getTile(int index) {
        return tiles.get(index);
    }

    public int getTilesCount() {
        return tiles.size();
    }

    public static class Tile extends LinearLayout {
        private final Card card;
        private final TextView labelView;
        private boolean checked;

        Tile(Context context, View content, int cardHeightDp, String label) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);

            card = new Card(context);
            // the tile is the clickable view; the card shows its pressed state as a rounded ripple
            card.setDuplicateParentStateEnabled(true);
            card.setForeground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 12, 12));
            card.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, cardHeightDp));

            labelView = new TextView(context);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            labelView.setGravity(Gravity.CENTER);
            labelView.setSingleLine(true);
            labelView.setEllipsize(TextUtils.TruncateAt.END);
            labelView.setText(label);
            addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            updateLabel();
        }

        public View getCard() {
            return card;
        }

        public boolean isChecked() {
            return checked;
        }

        void setChecked(boolean value, boolean animated) {
            checked = value;
            card.target = value ? 1f : 0f;
            if (!animated) {
                card.selection.set(card.target, true);
            }
            card.invalidate();
            updateLabel();
        }

        private void updateLabel() {
            labelView.setTextColor(Theme.getColor(checked ? Theme.key_windowBackgroundWhiteBlueHeader : Theme.key_windowBackgroundWhiteGrayText2));
            labelView.setTypeface(checked ? AndroidUtilities.bold() : null);
        }
    }

    private static class Card extends FrameLayout {
        float target;
        final AnimatedFloat selection = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Card(Context context) {
            super(context);
            strokePaint.setStyle(Paint.Style.STROKE);
            setPadding(dp(4), dp(4), dp(4), dp(4));
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            float s = selection.set(target);
            float stroke = dp(2);
            rect.set(stroke / 2f, stroke / 2f, getWidth() - stroke / 2f, getHeight() - stroke / 2f);

            int white = Theme.getColor(Theme.key_windowBackgroundWhite);
            int gray = Theme.getColor(Theme.key_windowBackgroundGray);
            // the gray of the settings background, a bit lighter so the tile reads as a card on the white section
            fillPaint.setColor(ColorUtils.blendARGB(gray, white, Theme.isCurrentThemeDark() ? 0.25f : 0.1f));
            canvas.drawRoundRect(rect, dp(12), dp(12), fillPaint);

            int idle = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.08f);
            int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader);
            strokePaint.setStrokeWidth(AndroidUtilities.lerp(dp(1), stroke, s));
            strokePaint.setColor(ColorUtils.blendARGB(idle, accent, s));
            canvas.drawRoundRect(rect, dp(12), dp(12), strokePaint);

            super.dispatchDraw(canvas);
        }
    }
}
