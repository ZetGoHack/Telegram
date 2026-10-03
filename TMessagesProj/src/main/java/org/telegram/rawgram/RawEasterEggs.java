package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.FireworksOverlay;

import java.util.ArrayList;

/**
 * Small celebrations for rare moments, reusable by any easter egg:
 * {@link #celebrate(View...)} = confetti over the targets' window + a light double haptic + {@link #goldGlow(View)}
 * on each target; {@link #goldGlow(View)} = animated gold border with a soft glow and sparkles drifting outward for
 * ~2.6 s, then a calmer endless shimmer while the view is shown. With rawGram motion off / power saver on only the static outline stays.
 */
public final class RawEasterEggs {

    public static final int GOLD_LIGHT = 0xFFFFF3C4;
    public static final int GOLD = 0xFFFFC83D;
    public static final int GOLD_DEEP = 0xFFE09B00;

    private RawEasterEggs() {
    }

    /** Confetti + haptic + gold glow on every target; starts once the first target is attached to a window. */
    public static void celebrate(View... targets) {
        if (targets == null || targets.length == 0 || targets[0] == null) {
            return;
        }
        for (View target : targets) {
            goldGlow(target);
        }
        whenAttached(targets[0], () -> {
            View anchor = targets[0];
            try {
                anchor.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                anchor.postDelayed(() -> anchor.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING), 110);
            } catch (Exception ignore) {
            }
            if (RawMotion.active()) {
                confetti(anchor);
            }
        });
    }

    /** A one-shot confetti burst over the window that holds {@code anchor} (works inside dialogs too). */
    public static void confetti(View anchor) {
        if (anchor == null) {
            return;
        }
        View root = anchor.getRootView();
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        try {
            FireworksOverlay overlay = new FireworksOverlay(anchor.getContext()) {
                @Override
                protected void onStop() {
                    AndroidUtilities.runOnUIThread(() -> AndroidUtilities.removeFromParent(this));
                }
            };
            overlay.setClickable(false);
            group.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.start();
            // safety net: never leave the overlay behind
            AndroidUtilities.runOnUIThread(() -> AndroidUtilities.removeFromParent(overlay), 8000);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Animated gold border + glow + sparkles on {@code target} (drawn in its overlay), settling into a faint outline. */
    public static void goldGlow(View target) {
        if (target == null) {
            return;
        }
        GoldGlowDrawable drawable = new GoldGlowDrawable(RawMotion.active());
        target.getOverlay().add(drawable);
        drawable.setBounds(0, 0, target.getWidth(), target.getHeight());
        target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> drawable.setBounds(0, 0, r - l, b - t));
        whenAttached(target, drawable::start);
    }

    private static void whenAttached(View view, Runnable action) {
        if (view.isAttachedToWindow()) {
            view.post(action);
            return;
        }
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                v.removeOnAttachStateChangeListener(this);
                v.post(action);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
            }
        });
    }

    /** Gold border with a shimmer running around it, a soft glow and rising sparkles; static outline when settled. */
    private static final class GoldGlowDrawable extends Drawable {

        private static final long DURATION = 2600, SETTLE = 600;
        private static final float STATIC_ALPHA = 0.45f;
        private static final float LOOP_INTENSITY = 0.55f;

        private final boolean animated;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sparkle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF rect = new RectF();
        private final Path star = new Path();
        private final ArrayList<Spark> sparks = new ArrayList<>();
        private LinearGradient shader;
        private int shaderWidth;
        private long startTime;
        private long lastFrame;
        private float spawnCarry;

        private static final class Spark {
            float x, y, vx, vy, life, age, size;
        }

        GoldGlowDrawable(boolean animated) {
            this.animated = animated;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            glow.setStyle(Paint.Style.STROKE);
            glow.setColor(GOLD);
            sparkle.setStyle(Paint.Style.FILL);
        }

        void start() {
            startTime = SystemClock.elapsedRealtime();
            lastFrame = startTime;
            invalidateSelf();
        }

        private void ensureShader(int width) {
            if (shader != null && shaderWidth == width) {
                return;
            }
            shaderWidth = width;
            shader = new LinearGradient(0, 0, Math.max(1, width), 0,
                    new int[]{GOLD_DEEP, GOLD, GOLD_LIGHT, GOLD, GOLD_DEEP},
                    new float[]{0f, 0.35f, 0.5f, 0.65f, 1f}, Shader.TileMode.MIRROR);
            stroke.setShader(shader);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int w = getBounds().width(), h = getBounds().height();
            if (w <= 0 || h <= 0 || animated && startTime == 0) {
                return; // animated: nothing until start(), so the outline doesn't blink before fading in
            }
            ensureShader(w);
            float inset = dp(3);
            rect.set(getBounds().left + inset, getBounds().top + inset, getBounds().right - inset, getBounds().bottom - inset);
            float radius = dp(10);

            long now = SystemClock.elapsedRealtime();
            long elapsed = startTime == 0 ? 0 : now - startTime;
            // the glow keeps looping while the view is shown: full burst first, then a calmer endless shimmer
            boolean running = animated && startTime != 0;

            float intensity;
            if (!animated || startTime == 0) {
                intensity = 0f;
            } else if (elapsed < DURATION) {
                intensity = 1f;
            } else {
                float settle = Utilities.clamp((elapsed - DURATION) / (float) SETTLE, 1f, 0f);
                intensity = 1f - settle * (1f - LOOP_INTENSITY);
            }
            float fadeIn = animated && startTime != 0 ? Utilities.clamp(elapsed / 300f, 1f, 0f) : 1f;

            // soft glow: a few wide, faint strokes (BlurMaskFilter isn't hardware accelerated everywhere)
            if (intensity > 0) {
                float pulse = 0.75f + 0.25f * (float) Math.sin(elapsed / 260.0);
                for (int i = 3; i >= 1; i--) {
                    glow.setStrokeWidth(dp(1.5f) + dp(1.6f) * i);
                    glow.setAlpha((int) (fadeIn * intensity * pulse * (70 - i * 18)));
                    canvas.drawRoundRect(rect, radius, radius, glow);
                }
            }

            // shimmering border, then the faint static outline
            matrix.setTranslate(running ? (elapsed / 1600f) * w * 2f : 0, 0);
            shader.setLocalMatrix(matrix);
            float alpha = STATIC_ALPHA + (1f - STATIC_ALPHA) * intensity;
            stroke.setAlpha((int) (255 * alpha * fadeIn));
            canvas.drawRoundRect(rect, radius, radius, stroke);

            if (running) {
                updateSparks(now, intensity);
            } else {
                sparks.clear();
            }
            for (int i = 0; i < sparks.size(); i++) {
                Spark s = sparks.get(i);
                float t = s.age / s.life;
                float a = t < 0.2f ? t / 0.2f : 1f - (t - 0.2f) / 0.8f;
                sparkle.setColor(i % 3 == 0 ? GOLD_LIGHT : GOLD);
                sparkle.setAlpha((int) (230 * a));
                drawStar(canvas, s.x, s.y, s.size * (0.6f + 0.4f * a));
            }

            if (running || !sparks.isEmpty()) {
                invalidateSelf();
            }
        }

        private void updateSparks(long now, float intensity) {
            float dt = Math.min(32, now - lastFrame) / 1000f;
            lastFrame = now;
            for (int i = sparks.size() - 1; i >= 0; i--) {
                Spark s = sparks.get(i);
                s.age += dt;
                if (s.age >= s.life) {
                    sparks.remove(i);
                    continue;
                }
                s.x += s.vx * dt;
                s.y += s.vy * dt;
                s.vy -= dp(10) * dt; // drift upward, gently accelerating
            }
            spawnCarry += dt * 16 * intensity; // ~16 sparks a second at full intensity
            while (spawnCarry >= 1f && sparks.size() < 24) {
                spawnCarry -= 1f;
                sparks.add(spawn());
            }
        }

        private Spark spawn() {
            Spark s = new Spark();
            // a random point on the border, moving away from the center
            float perimeter = 2 * (rect.width() + rect.height());
            float p = Utilities.random.nextFloat() * perimeter;
            if (p < rect.width()) {
                s.x = rect.left + p;
                s.y = rect.top;
            } else if (p < rect.width() + rect.height()) {
                s.x = rect.right;
                s.y = rect.top + (p - rect.width());
            } else if (p < 2 * rect.width() + rect.height()) {
                s.x = rect.right - (p - rect.width() - rect.height());
                s.y = rect.bottom;
            } else {
                s.x = rect.left;
                s.y = rect.bottom - (p - 2 * rect.width() - rect.height());
            }
            float dx = s.x - rect.centerX(), dy = s.y - rect.centerY();
            float len = Math.max(1f, (float) Math.hypot(dx, dy));
            float speed = dp(8) + Utilities.random.nextFloat() * dp(14);
            s.vx = dx / len * speed;
            s.vy = dy / len * speed - dp(6);
            s.life = 0.7f + Utilities.random.nextFloat() * 0.7f;
            s.size = dp(1.4f) + Utilities.random.nextFloat() * dp(1.6f);
            return s;
        }

        /** A four-point sparkle. */
        private void drawStar(Canvas canvas, float cx, float cy, float r) {
            float k = r * 0.32f;
            star.rewind();
            star.moveTo(cx, cy - r * 1.6f);
            star.quadTo(cx + k, cy - k, cx + r * 1.6f, cy);
            star.quadTo(cx + k, cy + k, cx, cy + r * 1.6f);
            star.quadTo(cx - k, cy + k, cx - r * 1.6f, cy);
            star.quadTo(cx - k, cy - k, cx, cy - r * 1.6f);
            star.close();
            canvas.drawPath(star, sparkle);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
