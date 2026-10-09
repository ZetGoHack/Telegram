package org.telegram.rawgram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * Background of a preview card in the settings: a faint tint of the text colour with a hairline outline that
 * turns into a thick accent outline as {@link #setSelectionProgress} goes to 1 (used by selectable tiles).
 * Ported from exteraGram's PreviewBackgroundDrawable / PreviewColors (exteraSquad, GPL).
 */
public class RawPreviewBackground extends Drawable {

    public static final float DEFAULT_RADIUS_DP = 12f;

    private static final float STROKE_IDLE_DP = 0.5f;
    private static final float STROKE_SELECTED_DP = 2f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final boolean strokeOnly;
    private float selectionProgress;

    public RawPreviewBackground() {
        this(DEFAULT_RADIUS_DP, false);
    }

    public RawPreviewBackground(float radiusDp) {
        this(radiusDp, false);
    }

    /** @param strokeOnly draws only the outline: laid over content that paints its own opaque background */
    public RawPreviewBackground(float radiusDp, boolean strokeOnly) {
        this.radius = dp(radiusDp);
        this.strokeOnly = strokeOnly;
        strokePaint.setStyle(Paint.Style.STROKE);
    }

    /** Fill of a preview card: the text colour at a few percent. */
    public static int backgroundColor() {
        return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), Theme.isCurrentThemeDark() ? 0.05f : 0.035f);
    }

    /** Hairline outline of a preview card. */
    public static int outlineColor() {
        return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), (Theme.isCurrentThemeDark() ? 0.05f : 0.035f) + 0.085f);
    }

    /** Placeholder bars and circles drawn inside mock previews. */
    public static int mockColor(boolean strong) {
        return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), strong ? 0.4f : 0.2f);
    }

    public float getRadius() {
        return radius;
    }

    public void setSelectionProgress(float progress) {
        if (selectionProgress != progress) {
            selectionProgress = progress;
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        strokePaint.setColor(ColorUtils.blendARGB(outlineColor(), Theme.getColor(Theme.key_windowBackgroundWhiteValueText), selectionProgress));
        strokePaint.setStrokeWidth(dp(AndroidUtilities.lerp(STROKE_IDLE_DP, STROKE_SELECTED_DP, selectionProgress)));
        float inset = strokePaint.getStrokeWidth() / 2f;
        rect.set(getBounds().left + inset, getBounds().top + inset, getBounds().right - inset, getBounds().bottom - inset);
        if (!strokeOnly) {
            fillPaint.setColor(backgroundColor());
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        strokePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        strokePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
