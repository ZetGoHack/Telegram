package org.telegram.rawgram;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

/**
 * Floating reroll status button drawn above the inline results: spinning "reroll"
 * arrows with an attempt progress ring while running, an outcome glyph after, and
 * an attempt counter badge.
 */
public class RawRerollBubble {

    public static final float SIZE_DP = 44;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    public final RectF bounds = new RectF();
    private final long startTime = SystemClock.elapsedRealtime();
    private boolean pressed;

    public RawRerollBubble() {
        bgPaint.setShadowLayer(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(1), 0x33000000);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setStrokeWidth(AndroidUtilities.dp(2.2f));
        iconPaint.setColor(0xFFFFFFFF);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStrokeWidth(AndroidUtilities.dp(2.5f));
        badgeTextPaint.setTypeface(AndroidUtilities.bold());
        badgeTextPaint.setTextSize(AndroidUtilities.dp(10));
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setPressed(boolean value) {
        pressed = value;
    }

    /** Draws centered at (cx, cy); keeps animating the parent while the run is live. */
    public void draw(Canvas canvas, View parent, float cx, float cy, RawRerollController controller, int accent, int badgeBg, int badgeText) {
        float radius = AndroidUtilities.dp(SIZE_DP / 2);
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        float scale = pressed ? 0.92f : 1f;
        canvas.save();
        canvas.scale(scale, scale, cx, cy);

        bgPaint.setColor(accent);
        canvas.drawCircle(cx, cy, radius, bgPaint);

        long t = SystemClock.elapsedRealtime() - startTime;
        int state = controller.state;
        if (state == RawRerollController.STATE_RUNNING) {
            // progress ring: attempts done out of the limit
            float progress = controller.options != null && controller.options.maxAttempts > 0
                    ? Math.min(1f, controller.attempt / (float) controller.options.maxAttempts) : 0;
            ringPaint.setColor(0x55FFFFFF);
            float rr = radius - AndroidUtilities.dp(3);
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
            canvas.drawArc(rect, 0, 360, false, ringPaint);
            ringPaint.setColor(0xFFFFFFFF);
            canvas.drawArc(rect, -90, Math.max(6, 360 * progress), false, ringPaint);
            // two chasing arrows = "reroll"
            float rotation = (t % 1400) / 1400f * 360f;
            drawRerollArrows(canvas, cx, cy, AndroidUtilities.dp(8.5f), rotation);
            parent.invalidate();
        } else if (state == RawRerollController.STATE_MATCHED) {
            path.reset();
            path.moveTo(cx - AndroidUtilities.dp(8), cy);
            path.lineTo(cx - AndroidUtilities.dp(2.5f), cy + AndroidUtilities.dp(5.5f));
            path.lineTo(cx + AndroidUtilities.dp(8.5f), cy - AndroidUtilities.dp(6));
            canvas.drawPath(path, iconPaint);
        } else if (state == RawRerollController.STATE_STOPPED) {
            float h = AndroidUtilities.dp(7);
            canvas.drawLine(cx - AndroidUtilities.dp(4), cy - h, cx - AndroidUtilities.dp(4), cy + h, iconPaint);
            canvas.drawLine(cx + AndroidUtilities.dp(4), cy - h, cx + AndroidUtilities.dp(4), cy + h, iconPaint);
        } else if (state == RawRerollController.STATE_ERROR) {
            canvas.drawLine(cx, cy - AndroidUtilities.dp(8), cx, cy + AndroidUtilities.dp(2), iconPaint);
            canvas.drawPoint(cx, cy + AndroidUtilities.dp(7), iconPaint);
        } else {
            float d = AndroidUtilities.dp(6.5f);
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, iconPaint);
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, iconPaint);
        }

        // attempt counter badge
        String count = String.valueOf(controller.attempt);
        float bw = Math.max(AndroidUtilities.dp(16), badgeTextPaint.measureText(count) + AndroidUtilities.dp(8));
        float bh = AndroidUtilities.dp(16);
        float bx = cx + radius * 0.55f;
        float by = cy + radius * 0.62f;
        rect.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2);
        badgePaint.setColor(badgeBg);
        canvas.drawRoundRect(rect, bh / 2, bh / 2, badgePaint);
        badgeTextPaint.setColor(badgeText);
        canvas.drawText(count, bx, by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2, badgeTextPaint);

        canvas.restore();
    }

    private void drawRerollArrows(Canvas canvas, float cx, float cy, float r, float rotation) {
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        rect.set(cx - r, cy - r, cx + r, cy + r);
        for (int i = 0; i < 2; i++) {
            float start = i * 180 + 20;
            float sweep = 120;
            canvas.drawArc(rect, start, sweep, false, iconPaint);
            double end = Math.toRadians(start + sweep);
            float ex = cx + (float) (r * Math.cos(end));
            float ey = cy + (float) (r * Math.sin(end));
            // arrowhead pointing along the arc direction
            double tangent = end + Math.PI / 2;
            float head = AndroidUtilities.dp(3.5f);
            double a1 = tangent + Math.PI * 0.8;
            double a2 = tangent - Math.PI * 0.8;
            canvas.drawLine(ex, ey, ex + (float) (head * Math.cos(a1)), ey + (float) (head * Math.sin(a1)), iconPaint);
            canvas.drawLine(ex, ey, ex + (float) (head * Math.cos(a2)), ey + (float) (head * Math.sin(a2)), iconPaint);
        }
        canvas.restore();
    }
}
