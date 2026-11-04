package org.telegram.rawgram.badges;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.text.TextPaint;

import org.telegram.messenger.AndroidUtilities;

/**
 * The badge number engraved on the back of the 3D supporter arrow: an alpha mask drawn once on a CPU canvas and
 * sampled by {@code fragment_extera_arrow.glsl} as a height map. Lives on the GL thread of the arrow's
 * {@link org.telegram.ui.Components.Premium.GLIcon.Icon3D}.
 * <p>
 * Ported from exteraGram's {@code SupporterArrowEngraving} (exteraGram, GPLv3).
 */
public final class RawArrowEngraving {

    /** Mask size, in the arrow model's texture space. */
    private static final int MASK_SIZE = 1024;
    private static final float TEXT_SIZE = 59.392f;
    private static final float LETTER_SPACING = 0.12f;
    private static final float BLUR_RADIUS = 1.2288f;
    /** Where the number sits on the model's UV map, and its tilt along the arrow's shaft. */
    private static final float CENTER_X = 509.5424f;
    private static final float CENTER_Y = 288.0512f;
    private static final float ROTATION = 19.49f;
    /** Texture unit used for the mask (0..2 are taken by Icon3D). */
    private static final int TEXTURE_UNIT = 3;

    private int enabledHandle;
    private int samplerHandle;
    private int texture;
    private boolean ready;
    private volatile Bitmap pending;

    /** The engraving mask for {@code text} (call off the UI thread: a 1024² bitmap). */
    public static Bitmap createMask(String text) {
        Bitmap bitmap = Bitmap.createBitmap(MASK_SIZE, MASK_SIZE, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bitmap);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(AndroidUtilities.bold());
        paint.setTextSize(TEXT_SIZE);
        paint.setLetterSpacing(LETTER_SPACING);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setMaskFilter(new BlurMaskFilter(BLUR_RADIUS, BlurMaskFilter.Blur.NORMAL));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.rotate(ROTATION, CENTER_X, CENTER_Y);
        canvas.drawText(text, CENTER_X, CENTER_Y - (metrics.ascent + metrics.descent) / 2f, paint);
        return bitmap;
    }

    public void init(int program) {
        enabledHandle = GLES20.glGetUniformLocation(program, "engraving");
        samplerHandle = GLES20.glGetUniformLocation(program, "u_Engraving");
    }

    /** Queues a mask; it is uploaded on the next {@link #apply()} (GL thread). */
    public void set(Bitmap mask) {
        pending = mask;
    }

    public void apply() {
        Bitmap bitmap = pending;
        if (bitmap != null) {
            pending = null;
            if (texture == 0) {
                int[] textures = new int[1];
                GLES20.glGenTextures(1, textures, 0);
                texture = textures[0];
            }
            int[] active = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, active, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + TEXTURE_UNIT);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            GLES20.glUniform1i(samplerHandle, TEXTURE_UNIT);
            GLES20.glActiveTexture(active[0]);
            ready = true;
        }
        GLES20.glUniform1f(enabledHandle, ready ? 1f : 0f);
    }

    public void destroy() {
        if (texture != 0) {
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            texture = 0;
        }
        ready = false;
    }
}
