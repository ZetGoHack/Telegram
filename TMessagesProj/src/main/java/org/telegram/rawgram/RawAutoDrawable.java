package org.telegram.rawgram;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;

/** Spinning ring with an "auto" label: shown instead of the inline cancel button while reroll runs. */
public class RawAutoDrawable extends Drawable {

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final long startTime = SystemClock.elapsedRealtime();
    private int color;

    public RawAutoDrawable() {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeWidth(AndroidUtilities.dp(2.2f));
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(AndroidUtilities.dp(2.2f));
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(AndroidUtilities.dp(8.5f));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setColor(int color) {
        this.color = color;
    }

    @Override
    public void draw(Canvas canvas) {
        long t = SystemClock.elapsedRealtime() - startTime;
        float rotation = (t % 1100) / 1100f * 360f;
        // sweep breathes between 60 and 270 degrees
        float phase = (t % 1600) / 1600f;
        float sweep = 60 + 210 * (0.5f - 0.5f * (float) Math.cos(phase * 2 * Math.PI));

        arcPaint.setColor(color);
        trackPaint.setColor((color & 0x00FFFFFF) | 0x33000000);
        textPaint.setColor(color);

        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        float radius = AndroidUtilities.dp(12.5f);
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawOval(rect, trackPaint);
        canvas.drawArc(rect, rotation - 90, sweep, false, arcPaint);
        canvas.drawText("auto", cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint);
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(30);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(30);
    }
}
