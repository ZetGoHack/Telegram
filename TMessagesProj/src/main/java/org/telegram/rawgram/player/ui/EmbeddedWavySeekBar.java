package org.telegram.rawgram.player.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.rawgram.player.RawPlayerConfig;

/**
 * A {@link WavySeekBar} drawn by hand inside another view: the chat audio bubble ({@code SeekBar}) and the photo viewer's
 * video bar ({@code VideoPlayerSeekBar}). It is never attached; visibility and redraws go through the host view.
 * Used only with the new player style.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later): SeekBar.setWavy / VideoPlayerSeekBar.drawWavy.
 */
public class EmbeddedWavySeekBar extends WavySeekBar {

    /** Draws closer together than this keep the jump animation (the progress changes on its own while playing). */
    private static final long CONTINUOUS_DRAW_MS = 100;

    private final View host;
    private final boolean hiddenWhenTransparent;
    private long lastDrawTime;

    private EmbeddedWavySeekBar(View host, boolean hiddenWhenTransparent) {
        super(host.getContext());
        this.host = host;
        this.hiddenWhenTransparent = hiddenWhenTransparent;
    }

    /** The chat bubble's bar (music and voice messages), or null when the new player style is off. */
    public static EmbeddedWavySeekBar createForMessage(View host) {
        if (!RawPlayerConfig.getNewPlayerStyle()) {
            return null;
        }
        EmbeddedWavySeekBar bar = new EmbeddedWavySeekBar(host, false);
        bar.setWaveStyle(2.5f, 2.0f, 20.0f, 12.0f, 3.0f, 14.0f);
        return bar;
    }

    /** The photo viewer's video bar, or null when the new player style is off. */
    public static EmbeddedWavySeekBar createForVideo(View host) {
        return RawPlayerConfig.getNewPlayerStyle() ? new EmbeddedWavySeekBar(host, true) : null;
    }

    // The host can be null only while the View constructor runs (before the field is set).

    @Override
    public boolean isShown() {
        if (host == null) {
            return super.isShown();
        }
        return host.isShown() && (!hiddenWhenTransparent || host.getAlpha() > 0.0f);
    }

    @Override
    public boolean isAttachedToWindow() {
        return host != null ? host.isAttachedToWindow() : super.isAttachedToWindow();
    }

    @Override
    public void invalidate() {
        if (host != null) {
            host.invalidate();
        }
    }

    @Override
    public void postInvalidateOnAnimation() {
        if (host != null) {
            host.postInvalidateOnAnimation();
        }
    }

    /** {@code SeekBar.draw}: the bar spans the track between the thumb centres. */
    public void drawInSeekBar(Canvas canvas, int width, int height, int thumbWidth, int thumbX, boolean pressed, float bufferedProgress, int progressColor, int trackColor, int bufferColor) {
        layout(0, 0, width - thumbWidth + AndroidUtilities.dp(4), height / 2 + AndroidUtilities.dp(16));
        setColors(progressColor, trackColor, bufferColor, 0, 0);
        setBufferedProgress(bufferedProgress);
        long now = SystemClock.uptimeMillis();
        int range = width - thumbWidth;
        setProgress(range > 0 ? thumbX / (float) range : 0.0f, !pressed && now - lastDrawTime < CONTINUOUS_DRAW_MS);
        lastDrawTime = now;
        float dx = thumbWidth / 2f - AndroidUtilities.dp(2);
        canvas.translate(dx, 0);
        draw(canvas);
        canvas.translate(-dx, 0);
    }

    /**
     * {@code VideoPlayerSeekBar.draw}: the wave, collapsing into the thin mini line ({@code transition} 1) when the
     * controls hide, with its outline.
     */
    public void drawInVideoSeekBar(Canvas canvas, float progress, boolean pressed, float transition, int width, int height, int thumbWidth,
                                   int lineHeight, int smallLineHeight, int hostWidth, int horizontalPadding, int progressColor, int smallLineColor,
                                   int trackColor, int bufferColor, float bufferedProgress, RectF rect, Paint strokePaint) {
        float inset = AndroidUtilities.dpf2(2);
        float lineWidth = AndroidUtilities.lerp(lineHeight, smallLineHeight, transition);
        float visible = 1f - transition;
        float insetVisible = inset * visible;
        float halfLine = lineWidth / 2f;
        float start = AndroidUtilities.lerp(thumbWidth / 2f, 0, transition) - insetVisible + halfLine;
        float end = AndroidUtilities.lerp(width - thumbWidth / 2f, hostWidth - horizontalPadding * 2f, transition) + insetVisible - halfLine;
        float centerY = AndroidUtilities.lerp(height / 2f, height - AndroidUtilities.dp(3) - smallLineHeight / 2f, transition);
        int halfHeight = height / 2;
        float dx = horizontalPadding + start - inset;
        float dy = centerY - halfHeight;
        float length = end - start;
        layout(0, 0, Math.round(inset * 2 + length), halfHeight + AndroidUtilities.dp(16));
        setColors(ColorUtils.blendARGB(progressColor, smallLineColor, transition),
                ColorUtils.setAlphaComponent(trackColor, (int) (Color.alpha(trackColor) * visible)),
                ColorUtils.setAlphaComponent(bufferColor, (int) (Color.alpha(bufferColor) * visible)), 0, 0);
        setCollapse(transition, smallLineHeight);
        setBufferedProgress(bufferedProgress);
        setProgress(progress, !pressed);
        canvas.translate(dx, dy);
        draw(canvas);
        canvas.translate(-dx, -dy);
        if (transition > 0) {
            float right = length * getProgress() + start + halfLine;
            rect.set(horizontalPadding + start - halfLine, centerY - halfLine, horizontalPadding + right, centerY + halfLine);
            if (rect.width() > lineWidth) {
                strokePaint.setAlpha((int) (transition * 255 * 0.2f));
                canvas.drawRoundRect(rect, halfLine, halfLine, strokePaint);
            }
        }
    }
}
