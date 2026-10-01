package org.telegram.rawgram;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * Floating reroll status button drawn above the inline results: spinning "reroll"
 * arrows with an attempt progress ring while running, an outcome glyph after, and
 * an attempt counter badge.
 *
 * <p>With {@link RawMotion} on: the press sinks and eases back, the ring glides to each new attempt instead of
 * jumping, the outcome glyph swaps with a pop (the check draws itself), and the counter badge bumps on every
 * attempt. Off: the exact stock look, state changes snap.
 */
public class RawRerollBubble {

    public static final float SIZE_DP = 44;

    private static final long GLYPH_OUT = 140, GLYPH_IN = 380, BUMP = 280;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path segment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF();
    public final RectF bounds = new RectF();
    private final long startTime = SystemClock.elapsedRealtime();
    private boolean pressed;

    // motion state
    private long lastFrame;
    private float press;            // 0 … 1, eased toward pressed
    private float ring = -1;        // shown ring progress
    private int glyphState = -1, previousGlyph = -1;
    private long glyphChange;
    private int shownAttempt = -1;
    private long bumpStart;

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
        final boolean motion = RawMotion.active();
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 0 : Math.min(64, now - lastFrame);
        lastFrame = now;
        boolean animating = false;

        float radius = AndroidUtilities.dp(SIZE_DP / 2);
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        float scale;
        if (motion) {
            // sink fast, come back up a bit slower
            float target = pressed ? 1f : 0f;
            float speed = pressed ? 0.018f : 0.009f;
            press += (target - press) * Math.min(1f, dt * speed);
            if (Math.abs(target - press) < 0.002f) {
                press = target;
            } else {
                animating = true;
            }
            scale = 1f - 0.1f * press;
        } else {
            press = pressed ? 1f : 0f;
            scale = pressed ? 0.92f : 1f;
        }
        canvas.save();
        canvas.scale(scale, scale, cx, cy);

        bgPaint.setColor(accent);
        canvas.drawCircle(cx, cy, radius, bgPaint);

        long t = now - startTime;
        int state = controller.state;
        if (state != glyphState) {
            previousGlyph = glyphState;
            glyphState = state;
            glyphChange = motion && previousGlyph != -1 ? now : 0;
        }
        if (state == RawRerollController.STATE_RUNNING) {
            // progress ring: attempts done out of the limit
            float progress = controller.options != null && controller.options.maxAttempts > 0
                    ? Math.min(1f, controller.attempt / (float) controller.options.maxAttempts) : 0;
            if (motion && ring >= 0) {
                ring += (progress - ring) * Math.min(1f, dt * 0.012f);
                if (Math.abs(progress - ring) < 0.001f) {
                    ring = progress;
                }
            } else {
                ring = progress;
            }
            ringPaint.setColor(0x55FFFFFF);
            float rr = radius - AndroidUtilities.dp(3);
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
            canvas.drawArc(rect, 0, 360, false, ringPaint);
            ringPaint.setColor(0xFFFFFFFF);
            canvas.drawArc(rect, -90, Math.max(6, 360 * ring), false, ringPaint);
            parent.invalidate();
        } else {
            ring = -1;
        }

        // outcome glyph, swapping with a pop when the state changes
        float in = 1f;
        if (glyphChange != 0) {
            long since = now - glyphChange;
            if (since < GLYPH_OUT && previousGlyph >= 0) {
                float out = CubicBezierInterpolator.EASE_IN.getInterpolation(since / (float) GLYPH_OUT);
                drawGlyph(canvas, previousGlyph, controller, cx, cy, t, 1f - out, 1f, 1f - out);
                animating = true;
                in = 0f;
            } else if (since < GLYPH_OUT + GLYPH_IN) {
                in = (since - GLYPH_OUT) / (float) GLYPH_IN;
                animating = true;
            } else {
                glyphChange = 0;
            }
        }
        if (in > 0f) {
            float pop = in >= 1f ? 1f : RawMotion.SOFT_BACK.getInterpolation(in);
            float draw = in >= 1f ? 1f : CubicBezierInterpolator.EASE_OUT.getInterpolation(Math.min(1f, in * 1.4f));
            drawGlyph(canvas, state, controller, cx, cy, t, 0.5f + 0.5f * pop, draw, Math.min(1f, in * 3f));
        }

        // attempt counter badge, bumps on every new attempt
        int attempt = controller.attempt;
        if (attempt != shownAttempt) {
            bumpStart = motion && shownAttempt >= 0 ? now : 0;
            shownAttempt = attempt;
        }
        float bump = 1f;
        if (bumpStart != 0) {
            float b = (now - bumpStart) / (float) BUMP;
            if (b >= 1f) {
                bumpStart = 0;
            } else {
                bump = 1f + 0.28f * (float) Math.sin(Math.PI * b);
                animating = true;
            }
        }
        String count = String.valueOf(attempt);
        float bw = Math.max(AndroidUtilities.dp(16), badgeTextPaint.measureText(count) + AndroidUtilities.dp(8));
        float bh = AndroidUtilities.dp(16);
        float bx = cx + radius * 0.55f;
        float by = cy + radius * 0.62f;
        if (bump != 1f) {
            canvas.save();
            canvas.scale(bump, bump, bx, by);
        }
        rect.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2);
        badgePaint.setColor(badgeBg);
        canvas.drawRoundRect(rect, bh / 2, bh / 2, badgePaint);
        badgeTextPaint.setColor(badgeText);
        canvas.drawText(count, bx, by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2, badgeTextPaint);
        if (bump != 1f) {
            canvas.restore();
        }

        canvas.restore();
        if (animating) {
            parent.invalidate();
        } else if (state != RawRerollController.STATE_RUNNING) {
            // idle: the next change starts from a fresh frame instead of one long step
            lastFrame = 0;
        }
    }

    /**
     * One state's glyph. {@code scale} around the center, {@code draw} 0…1 how much of the check stroke is drawn,
     * {@code alpha} 0…1.
     */
    private void drawGlyph(Canvas canvas, int state, RawRerollController controller, float cx, float cy, long t, float scale, float draw, float alpha) {
        if (alpha <= 0f || scale <= 0f) {
            return;
        }
        boolean transformed = scale != 1f;
        if (transformed) {
            canvas.save();
            canvas.scale(scale, scale, cx, cy);
        }
        iconPaint.setAlpha((int) (255 * alpha));
        if (state == RawRerollController.STATE_RUNNING) {
            int wait = controller.getWaitSecondsLeft();
            if (wait > 0) {
                // FLOOD_WAIT: show the seconds left instead of the arrows
                badgeTextPaint.setColor(0xFFFFFFFF);
                badgeTextPaint.setAlpha((int) (255 * alpha));
                badgeTextPaint.setTextSize(AndroidUtilities.dp(12));
                canvas.drawText(wait + "s", cx, cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2, badgeTextPaint);
                badgeTextPaint.setTextSize(AndroidUtilities.dp(10));
                badgeTextPaint.setAlpha(255);
            } else {
                // two chasing arrows = "reroll"
                float rotation = (t % 1400) / 1400f * 360f;
                drawRerollArrows(canvas, cx, cy, AndroidUtilities.dp(8.5f), rotation);
            }
        } else if (state == RawRerollController.STATE_MATCHED) {
            path.reset();
            path.moveTo(cx - AndroidUtilities.dp(8), cy);
            path.lineTo(cx - AndroidUtilities.dp(2.5f), cy + AndroidUtilities.dp(5.5f));
            path.lineTo(cx + AndroidUtilities.dp(8.5f), cy - AndroidUtilities.dp(6));
            if (draw >= 1f) {
                canvas.drawPath(path, iconPaint);
            } else if (draw > 0f) {
                measure.setPath(path, false);
                segment.rewind();
                measure.getSegment(0, measure.getLength() * draw, segment, true);
                canvas.drawPath(segment, iconPaint);
            }
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
        iconPaint.setAlpha(255);
        if (transformed) {
            canvas.restore();
        }
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
