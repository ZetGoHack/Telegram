package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.rawgram.settings.RawPreviewBackground;
import org.telegram.rawgram.settings.RawPreviewCard;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * A row of selectable tiles (exteraGram appearance style, after its FabShapeCell): every tile is a rounded
 * preview card with a live sample inside and a label under it; the selected one gets the thick accent outline
 * and an accent label.
 */
public class RawTilesCell extends LinearLayout {

    /** Half of the gap between two tiles. */
    private static final int TILE_MARGIN_DP = 8;

    public interface OnTileClick {
        void onClick(int index, Tile tile);
    }

    private final ArrayList<Tile> tiles = new ArrayList<>();
    private int selected = -1;

    public RawTilesCell(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        // the outer tiles line up with the preview cards: RawPreviewCard.INSET_DP from the section edges
        setPadding(dp(RawPreviewCard.INSET_DP - TILE_MARGIN_DP), dp(15), dp(RawPreviewCard.INSET_DP - TILE_MARGIN_DP), dp(16));
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
        addView(tile, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.TOP, TILE_MARGIN_DP, 0, TILE_MARGIN_DP, 0));
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
            card.setForeground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), (int) RawPreviewBackground.DEFAULT_RADIUS_DP, (int) RawPreviewBackground.DEFAULT_RADIUS_DP));
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
            labelView.setTypeface(checked ? AndroidUtilities.bold() : RawCustomFont.regularOrNull());
        }
    }

    /** Tile card: exteraGram's preview background, its hairline outline growing into the accent one when selected. */
    private static class Card extends FrameLayout {
        float target;
        final AnimatedFloat selection = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final RawPreviewBackground background = new RawPreviewBackground();

        Card(Context context) {
            super(context);
            setPadding(dp(4), dp(4), dp(4), dp(4));
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            background.setSelectionProgress(selection.set(target));
            background.setBounds(0, 0, getWidth(), getHeight());
            background.draw(canvas);
            super.dispatchDraw(canvas);
        }
    }
}
