package org.telegram.rawgram.player.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.widget.SeekBar;
import androidx.dynamicanimation.animation.SpringAnimation;
import org.telegram.rawgram.player.util.Material3Tokens;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

/**
 * Ported from exteraGram (exteraSquad, GPL-2.0-or-later): WavySeekBar.
 */
public class WavySeekBar extends View {
    private float amplitude;
    private final SpringAnimation amplitudeSpring;
    private float bubble;
    private int bubbleColor;
    private final SpringAnimation bubbleSpring;
    private int bubbleTextColor;
    private final TextPaint bubbleTextPaint;
    private int bufferColor;
    private float bufferedProgress;
    private Delegate delegate;
    private float downX;
    private float dragProgress;
    private boolean dragging;
    private final Paint fillPaint;
    private final SpringAnimation handleSpring;
    private float handleWidth;
    private long lastFrameTime;
    private final Paint linePaint;
    private boolean loading;
    private float loadingProgress;
    private boolean movedWhileDragging;
    private float phase;
    private float playbackSpeed;
    private boolean playing;
    private float progress;
    private final RectF rect;
    /** Animated offset from {@link #progress} after a jump (a seek, the next track). */
    private float jumpOffset;
    private final SpringAnimation jumpSpring;
    private float lineWidthDp = 4.0f;
    private float amplitudeDp = 3.0f;
    private float wavelength = 40.0f;
    private float phaseSpeedDp = 24.0f;
    private float handleWidthDp = 4.0f;
    private float handleHeightDp = 18.0f;
    /** 0..1: the wave flattens and the line thins to {@link #collapsedLineWidth} (the photo viewer's mini line). */
    private float collapse;
    private float collapsedLineWidth;
    private final int touchSlop;
    private int trackColor;
    private int waveColor;
    private final Path wavePath;

    public interface Delegate {
        CharSequence getAccessibilityValue();

        CharSequence getTimeLabel(float f);

        void onDragStateChanged(boolean z);

        void onSeek(float f);
    }

    public WavySeekBar(Context context) {
        super(context);
        Paint paint = new Paint(1);
        this.linePaint = paint;
        this.fillPaint = new Paint(1);
        TextPaint textPaint = new TextPaint(1);
        this.bubbleTextPaint = textPaint;
        this.wavePath = new Path();
        this.rect = new RectF();
        this.playbackSpeed = 1.0f;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(AndroidUtilities.dpf2(this.lineWidthDp));
        textPaint.setTextSize(AndroidUtilities.dp(13.0f));
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextAlign(Paint.Align.CENTER);
        this.amplitudeSpring = createSpring(1.0f, 260.0f, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                WavySeekBar.this.lambda$new$0((Float) obj);
            }
        });
        this.handleSpring = createSpring(0.6f, 800.0f, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                WavySeekBar.this.lambda$new$1((Float) obj);
            }
        });
        this.bubbleSpring = createSpring(0.6f, 800.0f, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                WavySeekBar.this.lambda$new$2((Float) obj);
            }
        });
        SpringAnimation springAnimationCreateSpring = createSpring(1.0f, 300.0f, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                WavySeekBar.this.lambda$new$3((Float) obj);
            }
        });
        this.jumpSpring = springAnimationCreateSpring;
        springAnimationCreateSpring.setMinimumVisibleChange(0.001f);
        this.handleWidth = AndroidUtilities.dpf2(this.handleWidthDp);
        setImportantForAccessibility(1);
        setFocusable(true);
    }

    private void lambda$new$0(Float f) {
        this.amplitude = f.floatValue();
    }

    private void lambda$new$1(Float f) {
        this.handleWidth = f.floatValue();
    }

    private void lambda$new$2(Float f) {
        this.bubble = f.floatValue();
    }

    private void lambda$new$3(Float f) {
        this.jumpOffset = f.floatValue();
    }

    private SpringAnimation createSpring(float f, float f2, final Utilities.Callback<Float> callback) {
        return Material3Tokens.createSpring(f, f2, 0.01f, new Utilities.Callback() {
            @Override
            public final void run(Object obj) {
                WavySeekBar.this.lambda$createSpring$4(callback, (Float) obj);
            }
        });
    }

    private void lambda$createSpring$4(Utilities.Callback callback, Float f) {
        callback.run(f);
        invalidate();
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    /** Sizes in dp, except the wavelength factor; the defaults are the player sheet's (4, 3, 40, 24, 4, 18). */
    public void setWaveStyle(float lineWidthDp, float amplitudeDp, float wavelength, float phaseSpeedDp, float handleWidthDp, float handleHeightDp) {
        this.lineWidthDp = lineWidthDp;
        this.amplitudeDp = amplitudeDp;
        this.wavelength = wavelength;
        this.phaseSpeedDp = phaseSpeedDp;
        this.handleWidthDp = handleWidthDp;
        this.handleHeightDp = handleHeightDp;
        this.handleSpring.cancel();
        this.handleWidth = AndroidUtilities.dpf2(this.dragging ? 2.0f : handleWidthDp);
        this.amplitudeSpring.cancel();
        this.amplitude = this.playing ? AndroidUtilities.dpf2(amplitudeDp) : 0.0f;
        invalidate();
    }

    public void setCollapse(float progress, float collapsedLineWidth) {
        float value = Utilities.clamp01(progress);
        if (this.collapse == value && this.collapsedLineWidth == collapsedLineWidth) {
            return;
        }
        this.collapse = value;
        this.collapsedLineWidth = collapsedLineWidth;
        invalidate();
    }

    public void setColors(int i, int i2, int i3, int i4, int i5) {
        if (this.waveColor == i && this.trackColor == i2 && this.bufferColor == i3 && this.bubbleColor == i4 && this.bubbleTextColor == i5) {
            return;
        }
        this.waveColor = i;
        this.trackColor = i2;
        this.bufferColor = i3;
        this.bubbleColor = i4;
        this.bubbleTextColor = i5;
        invalidate();
    }

    public boolean isDragging() {
        return this.dragging;
    }

    public float getProgress() {
        return this.dragging ? this.dragProgress : this.progress;
    }

    public void setProgress(float f) {
        setProgress(f, true);
    }

    /** {@code animate} false: no jump animation (for owners that set the progress on every draw). */
    public void setProgress(float f, boolean animate) {
        float fClamp01 = Utilities.clamp01(f);
        if (!animate) {
            cancelJump();
        }
        if (this.progress != fClamp01) {
            if (animate && !this.dragging && isAttachedToWindow() && Math.abs(this.progress - fClamp01) >= 0.05f) {
                Material3Tokens.animate(this.jumpSpring, (this.progress + this.jumpOffset) - fClamp01, 0.0f);
            }
            this.progress = fClamp01;
            if (this.dragging) {
                return;
            }
            invalidate();
        }
    }

    public void setBufferedProgress(float f) {
        float fClamp01 = Utilities.clamp01(f);
        if (this.bufferedProgress != fClamp01) {
            this.bufferedProgress = fClamp01;
            invalidate();
        }
    }

    public void setPlaying(boolean z, float f, boolean z2) {
        this.playbackSpeed = Math.max(0.1f, f);
        if (this.playing == z) {
            if (z2) {
                return;
            }
            if (this.amplitude == (z ? AndroidUtilities.dpf2(this.amplitudeDp) : 0.0f)) {
                return;
            }
        }
        this.playing = z;
        float fDpf2 = z ? AndroidUtilities.dpf2(this.amplitudeDp) : 0.0f;
        if (z2 && isAttachedToWindow()) {
            Material3Tokens.animate(this.amplitudeSpring, this.amplitude, fDpf2);
        } else {
            this.amplitudeSpring.cancel();
            this.amplitude = fDpf2;
        }
        this.lastFrameTime = 0L;
        invalidate();
    }

    public void setLoading(boolean z, float f) {
        if (this.loading == z && this.loadingProgress == f) {
            return;
        }
        this.loading = z;
        this.loadingProgress = Utilities.clamp01(f);
        if (z && this.dragging) {
            cancelDrag();
        }
        invalidate();
    }

    private float getLineStart() {
        return AndroidUtilities.dpf2(2.0f);
    }

    private float getLineEnd() {
        return getWidth() - AndroidUtilities.dpf2(2.0f);
    }

    private float getCenterY() {
        return getHeight() - AndroidUtilities.dpf2(16.0f);
    }

    private float progressAt(float f) {
        float lineStart = getLineStart();
        float lineEnd = getLineEnd();
        if (lineEnd <= lineStart) {
            return 0.0f;
        }
        return Utilities.clamp01((f - lineStart) / (lineEnd - lineStart));
    }

    @Override
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent motionEvent) {
        if (this.loading || !isEnabled()) {
            return false;
        }
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0) {
            if (Math.abs(motionEvent.getY() - getCenterY()) > AndroidUtilities.dpf2(24.0f)) {
                return false;
            }
            this.downX = motionEvent.getX();
            this.movedWhileDragging = false;
            this.dragging = true;
            this.dragProgress = progressAt(motionEvent.getX());
            cancelJump();
            getParent().requestDisallowInterceptTouchEvent(true);
            Material3Tokens.animate(this.handleSpring, this.handleWidth, AndroidUtilities.dpf2(2.0f));
            Material3Tokens.animate(this.bubbleSpring, this.bubble, 1.0f);
            Delegate delegate = this.delegate;
            if (delegate != null) {
                delegate.onDragStateChanged(true);
            }
            invalidate();
            return true;
        }
        if (actionMasked == 1) {
            if (!this.dragging) {
                return false;
            }
            this.dragProgress = progressAt(motionEvent.getX());
            finishDrag(true);
            return true;
        }
        if (actionMasked != 2) {
            if (actionMasked == 3) {
                if (this.dragging) {
                    finishDrag(this.movedWhileDragging);
                }
                return true;
            }
            return super.onTouchEvent(motionEvent);
        }
        if (!this.dragging) {
            return false;
        }
        if (Math.abs(motionEvent.getX() - this.downX) > this.touchSlop) {
            this.movedWhileDragging = true;
        }
        this.dragProgress = progressAt(motionEvent.getX());
        invalidate();
        return true;
    }

    private void finishDrag(boolean z) {
        this.dragging = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        Material3Tokens.animate(this.handleSpring, this.handleWidth, AndroidUtilities.dpf2(this.handleWidthDp));
        Material3Tokens.animate(this.bubbleSpring, this.bubble, 0.0f);
        if (z) {
            float f = this.dragProgress;
            this.progress = f;
            Delegate delegate = this.delegate;
            if (delegate != null) {
                delegate.onSeek(f);
            }
            sendAccessibilityEvent(4);
        }
        Delegate delegate2 = this.delegate;
        if (delegate2 != null) {
            delegate2.onDragStateChanged(false);
        }
        invalidate();
    }

    private void cancelDrag() {
        this.dragging = false;
        this.handleSpring.cancel();
        this.bubbleSpring.cancel();
        this.handleWidth = AndroidUtilities.dpf2(this.handleWidthDp);
        this.bubble = 0.0f;
        Delegate delegate = this.delegate;
        if (delegate != null) {
            delegate.onDragStateChanged(false);
        }
    }

    private void cancelJump() {
        this.jumpSpring.cancel();
        this.jumpOffset = 0.0f;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.amplitudeSpring.cancel();
        this.handleSpring.cancel();
        this.bubbleSpring.cancel();
        cancelJump();
        this.amplitude = this.playing ? AndroidUtilities.dpf2(this.amplitudeDp) : 0.0f;
        this.handleWidth = AndroidUtilities.dpf2(this.handleWidthDp);
        this.bubble = 0.0f;
        if (this.dragging) {
            cancelDrag();
        }
        this.lastFrameTime = 0L;
    }

    @Override
    public void onDraw(Canvas canvas) {
        if (getWidth() <= 0) {
            return;
        }
        float centerY = getCenterY();
        float lineStart = getLineStart();
        float lineEnd = getLineEnd();
        if (this.loading) {
            drawLoading(canvas, lineStart, lineEnd, centerY);
            return;
        }
        float fMax = this.dragging ? this.dragProgress : Utilities.clamp01(this.progress + this.jumpOffset);
        float fLerp = AndroidUtilities.lerp(lineStart, lineEnd, fMax);
        drawWave(canvas, lineStart, lineEnd, centerY, fMax, fLerp);
        drawHandle(canvas, fLerp, centerY);
        drawBubble(canvas, fLerp, fMax);
        if (needsFrames()) {
            postInvalidateOnAnimation();
        } else {
            this.lastFrameTime = 0L;
        }
    }

    private boolean needsFrames() {
        return this.playing && this.amplitude > 0.0f && this.collapse < 1.0f && isShown() && getAlpha() > 0.0f;
    }

    private void advancePhase() {
        long jCurrentAnimationTimeMillis = AnimationUtils.currentAnimationTimeMillis();
        long j = this.lastFrameTime;
        if (j != 0 && this.playing) {
            float fMin = this.phase + (((((Math.min(0.05f, (jCurrentAnimationTimeMillis - j) / 1000.0f) * 2.0f) * 3.1415927f) * this.phaseSpeedDp) * Math.min(this.playbackSpeed, 2.0f)) / this.wavelength);
            this.phase = fMin;
            if (fMin > 6283.185307179586d) {
                this.phase = fMin - 6283.1855f;
            }
        }
        this.lastFrameTime = jCurrentAnimationTimeMillis;
    }

    private void drawWave(Canvas canvas, float f, float f2, float f3, float f4, float f5) {
        Canvas canvas2;
        float f6;
        float f7;
        advancePhase();
        float lineWidth = getLineWidth();
        float fDpf2 = AndroidUtilities.lerp((this.handleWidth / 2.0f) + AndroidUtilities.dpf2(4.0f), lineWidth / 2.0f, this.collapse);
        float fMin = Math.min(f2, f5 + fDpf2);
        this.linePaint.setStrokeWidth(lineWidth);
        if (fMin < f2) {
            float fMin2 = Math.min(f2, AndroidUtilities.lerp(f, f2, Math.max(this.bufferedProgress, f4)));
            if (f2 - fMin2 >= 0.5f) {
                this.linePaint.setColor(this.trackColor);
                canvas2 = canvas;
                f6 = f3;
                canvas2.drawLine(fMin, f6, f2, f3, this.linePaint);
            } else {
                canvas2 = canvas;
                f6 = f3;
            }
            if (fMin2 > fMin) {
                this.linePaint.setColor(this.bufferColor);
                canvas2.drawLine(fMin, f6, fMin2, f6, this.linePaint);
            }
        } else {
            canvas2 = canvas;
            f6 = f3;
        }
        float f8 = f5 - fDpf2;
        if (f8 <= f) {
            return;
        }
        if (f4 < 0.1f) {
            f7 = f4 / 0.1f;
        } else {
            f7 = f4 > 0.9f ? (1.0f - f4) / 0.1f : 1.0f;
        }
        float fClamp01 = this.amplitude * Utilities.clamp01(f7) * (1.0f - this.collapse);
        this.linePaint.setColor(this.waveColor);
        if (fClamp01 < 0.25f) {
            canvas2.drawLine(f, f6, f8, f6, this.linePaint);
            return;
        }
        float fDpf3 = 6.2831855f / AndroidUtilities.dpf2(this.wavelength);
        float fDpf4 = AndroidUtilities.dpf2(1.5f);
        this.wavePath.rewind();
        this.wavePath.moveTo(f, (((float) Math.sin((f * fDpf3) + this.phase)) * fClamp01) + f6);
        while (true) {
            f += fDpf4;
            Path path = this.wavePath;
            if (f < f8) {
                path.lineTo(f, (((float) Math.sin((f * fDpf3) + this.phase)) * fClamp01) + f6);
            } else {
                path.lineTo(f8, f6 + (fClamp01 * ((float) Math.sin((f8 * fDpf3) + this.phase))));
                canvas2.drawPath(this.wavePath, this.linePaint);
                return;
            }
        }
    }

    private float getLineWidth() {
        return AndroidUtilities.lerp(AndroidUtilities.dpf2(this.lineWidthDp), this.collapsedLineWidth, this.collapse);
    }

    private void drawHandle(Canvas canvas, float f, float f2) {
        float lineWidth = getLineWidth();
        float width = AndroidUtilities.lerp(this.handleWidth, lineWidth, this.collapse);
        float height = AndroidUtilities.lerp(AndroidUtilities.dpf2(this.handleHeightDp), lineWidth, this.collapse);
        this.fillPaint.setColor(this.waveColor);
        float halfWidth = width / 2.0f;
        float halfHeight = height / 2.0f;
        this.rect.set(f - halfWidth, f2 - halfHeight, f + halfWidth, f2 + halfHeight);
        canvas.drawRoundRect(this.rect, halfWidth, halfWidth, this.fillPaint);
    }

    private void drawBubble(Canvas canvas, float f, float f2) {
        Delegate delegate;
        CharSequence timeLabel;
        if (this.bubble <= 0.01f || (delegate = this.delegate) == null || (timeLabel = delegate.getTimeLabel(f2)) == null) {
            return;
        }
        String string = timeLabel.toString();
        float fMeasureText = this.bubbleTextPaint.measureText(string) + AndroidUtilities.dp(20.0f);
        float fDpf2 = AndroidUtilities.dpf2(26.0f);
        float f3 = fMeasureText / 2.0f;
        float fMax = Math.max(-AndroidUtilities.dp(4.0f), Math.min((getWidth() - fMeasureText) + AndroidUtilities.dp(4.0f), f - f3));
        float f4 = fDpf2 / 2.0f;
        float centerY = ((getCenterY() - AndroidUtilities.dpf2(30.0f)) - f4) + AndroidUtilities.dp(2.0f);
        float fClamp01 = (Utilities.clamp01(this.bubble) * 0.4f) + 0.6f;
        canvas.save();
        float f5 = f3 + fMax;
        float f6 = fDpf2 + centerY;
        canvas.scale(fClamp01, fClamp01, f5, f6);
        this.fillPaint.setColor(this.bubbleColor);
        Paint paint = this.fillPaint;
        paint.setAlpha((int) (paint.getAlpha() * Utilities.clamp01(this.bubble)));
        this.rect.set(fMax, centerY, fMeasureText + fMax, f6);
        canvas.drawRoundRect(this.rect, f4, f4, this.fillPaint);
        this.bubbleTextPaint.setColor(this.bubbleTextColor);
        this.bubbleTextPaint.setAlpha((int) (Utilities.clamp01(this.bubble) * 255.0f));
        Paint.FontMetrics fontMetrics = this.bubbleTextPaint.getFontMetrics();
        canvas.drawText(string, f5, (centerY + f4) - ((fontMetrics.ascent + fontMetrics.descent) / 2.0f), this.bubbleTextPaint);
        canvas.restore();
    }

    private void drawLoading(Canvas canvas, float f, float f2, float f3) {
        this.linePaint.setStrokeWidth(AndroidUtilities.dpf2(this.lineWidthDp));
        this.linePaint.setColor(this.trackColor);
        canvas.drawLine(f, f3, f2, f3, this.linePaint);
        if (this.loadingProgress > 0.0f) {
            this.linePaint.setColor(this.waveColor);
            canvas.drawLine(f, f3, Math.max(f + 0.5f, AndroidUtilities.lerp(f, f2, this.loadingProgress)), f3, this.linePaint);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo accessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(accessibilityNodeInfo);
        accessibilityNodeInfo.setClassName(SeekBar.class.getName());
        accessibilityNodeInfo.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(2, 0.0f, 100.0f, getProgress() * 100.0f));
        Delegate delegate = this.delegate;
        CharSequence accessibilityValue = delegate != null ? delegate.getAccessibilityValue() : null;
        if (accessibilityValue != null) {
            accessibilityNodeInfo.setText(accessibilityValue);
        }
        if (!isEnabled() || this.loading) {
            return;
        }
        accessibilityNodeInfo.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        accessibilityNodeInfo.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int i, Bundle bundle) {
        if (super.performAccessibilityAction(i, bundle)) {
            return true;
        }
        if (this.loading || this.delegate == null) {
            return false;
        }
        if (i != 4096 && i != 8192) {
            return false;
        }
        float fClamp01 = Utilities.clamp01(this.progress + (i == 4096 ? 0.05f : -0.05f));
        this.progress = fClamp01;
        this.delegate.onSeek(fClamp01);
        invalidate();
        sendAccessibilityEvent(4);
        return true;
    }
}
