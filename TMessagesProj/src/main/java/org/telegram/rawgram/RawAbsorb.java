package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * "Soaks" a panel into a round button, the way the profile avatar merges into the camera cutout.
 * The panel (drawn live) contracts toward the button while its contents fade into a solid blob; then the
 * button hands its disc over to the overlay, and on Android 12+ the blob and the disc are blurred and
 * alpha-thresholded together, so the border between them dissolves and the disc swallows the rest.
 */
@SuppressLint("ViewConstructor")
public class RawAbsorb extends FrameLayout {

    private static final CubicBezierInterpolator SHRINK = new CubicBezierInterpolator(0.5, 0, 0.2, 1);
    private static final long DURATION = 560;

    private final View source, target;
    private final float sourceX, sourceY;
    private final RectF from;
    private final float targetX, targetY, targetR;
    private final RectF current = new RectF();
    private final int[] loc = new int[2];
    private final float gooAlpha;
    private float progress, discR, handover;

    private final GooLayer goo;
    private final ContentLayer content;
    private final IconLayer icon;

    private RawAbsorb(Context context, View source, float sourceX, float sourceY, RectF from, View target,
                      float targetX, float targetY, float targetR, int color, int iconRes, int iconColor) {
        super(context);
        this.source = source;
        this.target = target;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.from = from;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetR = targetR;
        this.gooAlpha = Math.max(0.85f, Color.alpha(color) / 255f);
        goo = new GooLayer(context, color);
        content = new ContentLayer(context);
        icon = new IconLayer(context, iconRes, iconColor);
        addView(goo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        setClickable(true);
        update(0);
    }

    /**
     * @param root    the view that hosts the overlay (the source and the target must be inside it)
     * @param source  the panel view; it is hidden (alpha 0) for the animation and drawn by the overlay instead
     * @param panel   the visible panel rect in the source's coordinates
     * @param target  the button to soak into; its disc is centered horizontally, 28dp above its bottom
     */
    public static void start(ViewGroup root, View source, RectF panel, View target, int color, int iconRes, int iconColor,
                             Runnable onAbsorbed, Runnable onEnd) {
        // everything is kept in window coordinates; the layers translate by their own window position
        int[] srcLoc = new int[2], tgtLoc = new int[2];
        source.getLocationInWindow(srcLoc);
        target.getLocationInWindow(tgtLoc);
        float sx = srcLoc[0], sy = srcLoc[1];
        RectF from = new RectF(panel);
        from.offset(sx, sy);
        float tx = tgtLoc[0] + target.getWidth() / 2f;
        float ty = tgtLoc[1] + target.getHeight() - dp(28);

        RawAbsorb overlay = new RawAbsorb(root.getContext(), source, sx, sy, from, target, tx, ty, dp(22), color, iconRes, iconColor);
        int index = root.indexOfChild(source);
        root.addView(overlay, index < 0 ? -1 : index + 1, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        source.setAlpha(0f);

        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(DURATION);
        animator.setInterpolator(new LinearInterpolator());
        final boolean[] absorbed = {false};
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            overlay.update(t);
            // the panel is visually gone inside the button well before the disc settles
            if (!absorbed[0] && t >= 0.72f) {
                absorbed[0] = true;
                if (onAbsorbed != null) {
                    onAbsorbed.run();
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!absorbed[0] && onAbsorbed != null) {
                    onAbsorbed.run();
                }
                target.setAlpha(1f);
                target.setScaleX(1f);
                target.setScaleY(1f);
                root.removeView(overlay);
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        animator.start();
    }

    private static float part(float t, float start, float end) {
        return Math.max(0, Math.min(1, (t - start) / (end - start)));
    }

    private void update(float t) {
        progress = t;
        // the edges start one after another, so the panel flows into the button instead of scaling
        float endR = targetR * 0.45f;
        float left = lerp(from.left, targetX - endR, SHRINK.getInterpolation(part(t, 0f, 0.8f)));
        float top = lerp(from.top, targetY - endR, SHRINK.getInterpolation(part(t, 0.04f, 0.86f)));
        float right = lerp(from.right, targetX + endR, SHRINK.getInterpolation(part(t, 0.1f, 0.92f)));
        float bottom = lerp(from.bottom, targetY + endR, SHRINK.getInterpolation(part(t, 0.1f, 0.94f)));
        current.set(left, top, right, bottom);

        // the disc swells once while it swallows the rest; the real button follows the same swell,
        // so handing the disc back to it is seamless (one bump, no blink)
        discR = targetR * (1f + 0.14f * (float) Math.sin(Math.PI * part(t, 0.4f, 1f)));
        float swell = discR / targetR;
        target.setScaleX(swell);
        target.setScaleY(swell);
        // the button gives its disc to the overlay while the panel is still around it, and takes it back
        // (fully opaque first) before the blob under it fades away
        float giveAway = part(t, 0.22f, 0.38f);
        float takeBack = part(t, 0.74f, 0.86f);
        handover = giveAway * (1f - takeBack);
        target.setAlpha(1f - handover);

        goo.setAlpha(gooAlpha * Math.max(part(t, 0.04f, 0.34f), giveAway) * (1f - part(t, 0.86f, 0.96f)));
        goo.invalidate();
        content.invalidate();
        icon.invalidate();
    }

    private float cornerRadius() {
        float min = Math.min(current.width(), current.height());
        return Math.min(Math.max(dp(22), min * 0.35f), min / 2f);
    }

    private class GooLayer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GooLayer(Context context, int color) {
            super(context);
            paint.setColor(color | 0xFF000000);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                float blur = dp(11);
                ColorMatrixColorFilter threshold = new ColorMatrixColorFilter(new float[]{
                        1, 0, 0, 0, 0,
                        0, 1, 0, 0, 0,
                        0, 0, 1, 0, 0,
                        0, 0, 0, 51, 51 * -125
                });
                setRenderEffect(RenderEffect.createChainEffect(
                        RenderEffect.createColorFilterEffect(threshold),
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL)));
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationInWindow(loc);
            canvas.save();
            canvas.translate(-loc[0], -loc[1]);
            float r = cornerRadius();
            canvas.drawRoundRect(current, r, r, paint);
            canvas.drawCircle(targetX, targetY, discR, paint);
            canvas.restore();
        }
    }

    private class ContentLayer extends View {
        private final Path clip = new Path();

        ContentLayer(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float alpha = 1f - part(progress, 0.06f, 0.46f);
            if (alpha <= 0) {
                return;
            }
            getLocationInWindow(loc);
            canvas.save();
            canvas.translate(-loc[0], -loc[1]);
            float r = cornerRadius();
            clip.rewind();
            clip.addRoundRect(current, r, r, Path.Direction.CW);
            // the live panel shrinks with the shape (by its area), anchored at the shape's center
            float s = (float) Math.sqrt(current.width() * current.height() / (from.width() * from.height()));
            canvas.saveLayerAlpha(current, (int) (255 * alpha));
            canvas.clipPath(clip);
            canvas.translate(current.centerX(), current.centerY());
            canvas.scale(s, s);
            canvas.translate(-from.centerX() + sourceX, -from.centerY() + sourceY);
            source.draw(canvas);
            canvas.restore();
            canvas.restore();
        }
    }

    /** The button's icon, kept on screen while the button itself is handed over to the blob. */
    private class IconLayer extends View {
        private final Drawable drawable;

        IconLayer(Context context, int iconRes, int iconColor) {
            super(context);
            Drawable d = iconRes != 0 ? ContextCompat.getDrawable(context, iconRes) : null;
            if (d != null) {
                d = d.mutate();
                d.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
            }
            drawable = d;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawable == null || handover <= 0) {
                return;
            }
            getLocationInWindow(loc);
            canvas.save();
            canvas.translate(-loc[0], -loc[1]);
            float s = discR / targetR;
            canvas.scale(s, s, targetX, targetY);
            int w = drawable.getIntrinsicWidth(), h = drawable.getIntrinsicHeight();
            int cx = (int) targetX, cy = (int) targetY + dp(1);
            drawable.setBounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            drawable.setAlpha((int) (255 * handover));
            drawable.draw(canvas);
            canvas.restore();
        }
    }
}
