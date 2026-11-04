package org.telegram.rawgram.badges;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The badge number of the welcome sheet, rolled in digit by digit like a slot machine (each digit spins past
 * {@link #EXTRA_SPINS} extra numbers and lands with a bounce and a haptic tick).
 * <p>
 * Ported from exteraGram's {@code SupporterNumberView} (exteraGram, GPLv3).
 */
public class RawSupporterNumberView extends View {

    private static final int HEIGHT_DP = 50;
    private static final int TEXT_SIZE_DP = 34;
    /** Time one digit spins, and the delay between neighbouring digits. */
    private static final float SPIN_MS = 900f;
    private static final long STAGGER_MS = 140;
    /** Total time after the last digit started: spin plus the landing bounce. */
    private static final long TAIL_MS = 1140;
    private static final float BOUNCE_MS = 240f;
    private static final float BOUNCE_SCALE = 0.14f;
    private static final float FADE_IN_MS = 200f;
    private static final int EXTRA_SPINS = 20;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    private int[] digits = new int[0];
    private int digitCount;
    private float digitWidth;
    private int color1;
    private int color2;
    private int shaderWidth = -1;
    private float time = -1f;
    private int landedCount;
    private Runnable onLanded;
    private ValueAnimator animator;

    public RawSupporterNumberView(Context context) {
        super(context);
        paint.setTypeface(AndroidUtilities.bold());
        paint.setTextSize(AndroidUtilities.dp(TEXT_SIZE_DP));
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 9; i++) {
            digitWidth = Math.max(digitWidth, paint.measureText(String.valueOf(i)));
        }
    }

    public void setNumber(String number) {
        text = number;
        digits = new int[number.length()];
        digitCount = 0;
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            digits[i] = Character.isDigit(c) ? Character.digit(c, 10) : -1;
            if (digits[i] >= 0) {
                digitCount++;
            }
        }
        setContentDescription(number);
        invalidate();
    }

    public void setColors(int color1, int color2) {
        this.color1 = color1;
        this.color2 = color2;
        shaderWidth = -1;
        invalidate();
    }

    /** Starts the roll; {@code onLanded} runs when the last digit lands. */
    public void start(Runnable onLanded) {
        this.onLanded = onLanded;
        if (animator != null) {
            animator.cancel();
        }
        long duration = Math.max(0, digitCount - 1) * STAGGER_MS + TAIL_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            time = duration;
            landedCount = digitCount;
            invalidate();
            if (onLanded != null) {
                onLanded.run();
            }
            return;
        }
        time = 0f;
        landedCount = 0;
        animator = ValueAnimator.ofFloat(0f, duration);
        animator.setDuration(duration);
        animator.setInterpolator(null);
        animator.addUpdateListener(a -> {
            time = (float) a.getAnimatedValue();
            checkLanded();
            invalidate();
        });
        animator.start();
    }

    private void checkLanded() {
        int landed = time < SPIN_MS ? 0 : Math.min(digitCount, (int) ((time - SPIN_MS) / STAGGER_MS) + 1);
        while (landedCount < landed) {
            landedCount++;
            if (landedCount < digitCount) {
                try {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } catch (Exception ignore) {
                }
            } else if (onLanded != null) {
                onLanded.run();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    private float charWidth(int index) {
        return digits[index] >= 0 ? digitWidth : paint.measureText(text, index, index + 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (time < 0f || text.isEmpty()) {
            return;
        }
        float totalWidth = 0f;
        for (int i = 0; i < text.length(); i++) {
            totalWidth += charWidth(i);
        }
        if (shaderWidth != (int) totalWidth) {
            shaderWidth = (int) totalWidth;
            float left = (getWidth() - totalWidth) / 2f;
            paint.setShader(color1 == color2 ? null : new LinearGradient(left, 0, left + totalWidth, 0, color1, color2, Shader.TileMode.CLAMP));
            paint.setColor(color1);
        }
        float lineHeight = paint.getTextSize() * 1.1f;
        float baseline = getHeight() / 2f - (paint.descent() + paint.ascent()) / 2f;
        float fadeIn = Utilities.clamp(time / FADE_IN_MS, 1f, 0f);

        canvas.save();
        float clip = 0.6f * lineHeight;
        canvas.clipRect(0, getHeight() / 2f - clip, getWidth(), getHeight() / 2f + clip);
        int digitIndex = 0;
        float x = (getWidth() - totalWidth) / 2f;
        for (int i = 0; i < text.length(); i++) {
            float width = charWidth(i);
            float cx = x + width / 2f;
            if (digits[i] < 0) {
                paint.setAlpha((int) (255 * fadeIn));
                canvas.drawText(text, i, i + 1, cx, baseline, paint);
            } else {
                float progress = (time - digitIndex * STAGGER_MS) / SPIN_MS;
                float position = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Utilities.clamp(progress, 1f, 0f)) * (digits[i] + EXTRA_SPINS);
                int whole = (int) Math.floor(position);
                float fraction = position - whole;
                float bounce = (float) Math.sin(Utilities.clamp((progress - 1f) * SPIN_MS / BOUNCE_MS, 1f, 0f) * Math.PI) * BOUNCE_SCALE + 1f;
                canvas.save();
                canvas.scale(bounce, bounce, cx, getHeight() / 2f);
                drawDigit(canvas, whole % 10, cx, baseline - fraction * lineHeight, fraction, fadeIn);
                if (fraction > 0f) {
                    float rest = 1f - fraction;
                    drawDigit(canvas, (whole + 1) % 10, cx, baseline + rest * lineHeight, rest, fadeIn);
                }
                canvas.restore();
                digitIndex++;
            }
            x += width;
        }
        canvas.restore();
    }

    private void drawDigit(Canvas canvas, int digit, float x, float y, float offset, float alpha) {
        paint.setAlpha((int) (alpha * 255 * (1f - offset)));
        canvas.drawText(String.valueOf(digit), x, y, paint);
    }
}
