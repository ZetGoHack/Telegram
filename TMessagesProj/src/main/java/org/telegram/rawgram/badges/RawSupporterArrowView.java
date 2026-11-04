package org.telegram.rawgram.badges;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.GLIcon.GLIconRenderer;
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.StarParticlesView;

/**
 * Header of the supporter sheets: the rotating 3D exteraGram arrow (accent-tinted, with the badge number engraved on
 * it) over star particles, or the holder's custom badge emoji in its place.
 * <p>
 * Ported from exteraGram's {@code SupporterArrowView} (exteraGram, GPLv3).
 */
public class RawSupporterArrowView extends FrameLayout {

    private static final int HEIGHT_DP = 190;
    private static final int ICON_SIZE_DP = 160;
    private static final int BADGE_SIZE_DP = 130;
    private static final int PARTICLES_LOW = 25;
    private static final int PARTICLES_AVERAGE = 50;
    private static final int PARTICLES_HIGH = 90;
    private static final int BACKGROUND_BITMAP_SIZE = 50;

    public final GLIconTextureView iconView;
    public final int color1;
    public final int color2;
    private final Theme.ResourcesProvider resourcesProvider;
    private BadgeIconView badgeView;
    private boolean iconHidden;

    public RawSupporterArrowView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setClipChildren(false);

        int performance = SharedConfig.getDevicePerformanceClass();
        int particles = performance == SharedConfig.PERFORMANCE_CLASS_AVERAGE ? PARTICLES_AVERAGE
                : performance == SharedConfig.PERFORMANCE_CLASS_HIGH ? PARTICLES_HIGH : PARTICLES_LOW;
        StarParticlesView particlesView = new StarParticlesView(context, particles) {
            @Override
            protected void configure() {
                super.configure();
                drawable.useGradient = false;
                drawable.useBlur = false;
                drawable.forceMaxAlpha = true;
                drawable.checkBounds = true;
                drawable.colorKey = Theme.key_featuredStickers_addButton;
                drawable.resourcesProvider = resourcesProvider;
                drawable.init();
            }
        };
        addView(particlesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, HEIGHT_DP, Gravity.CENTER));

        iconView = new GLIconTextureView(context, GLIconRenderer.DIALOG_STYLE, Icon3D.TYPE_EXTERA_ARROW) {
            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                setPaused(false);
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                setPaused(true);
            }
        };
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        Bitmap background = Bitmap.createBitmap(BACKGROUND_BITMAP_SIZE, BACKGROUND_BITMAP_SIZE, Bitmap.Config.ARGB_8888);
        new Canvas(background).drawColor(ColorUtils.blendARGB(accent, Theme.getColor(Theme.key_dialogBackground, resourcesProvider), 0.5f));
        iconView.setBackgroundBitmap(background);
        iconView.mRenderer.resourcesProvider = resourcesProvider;
        color1 = shiftColor(accent, -10f, 0.75f, 1.15f);
        color2 = shiftColor(accent, 18f, 0.6f, 1.1f);
        iconView.mRenderer.setColors(color1, color2);
        iconView.setStarParticlesView(particlesView);
        addView(iconView, LayoutHelper.createFrame(ICON_SIZE_DP, ICON_SIZE_DP, Gravity.CENTER));
    }

    /** Shows a custom badge emoji instead of the arrow. */
    public void setBadge(int account, long documentId) {
        if (badgeView == null) {
            removeView(iconView);
            badgeView = new BadgeIconView(getContext());
            addView(badgeView, LayoutHelper.createFrame(BADGE_SIZE_DP, BADGE_SIZE_DP, Gravity.CENTER));
        }
        badgeView.set(account, documentId, Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
    }

    /** Engraves {@code text} (the formatted badge number) on the arrow. */
    public void setNumber(String text) {
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap mask = RawArrowEngraving.createMask(text);
            AndroidUtilities.runOnUIThread(() -> iconView.mRenderer.setEngraving(mask));
        });
    }

    public View getIconView() {
        return badgeView != null ? badgeView : iconView;
    }

    public boolean hasBadge() {
        return badgeView != null;
    }

    public void setIconHidden(boolean hidden) {
        if (iconHidden != hidden) {
            iconHidden = hidden;
            invalidate();
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child == getIconView() && iconHidden) {
            return true;
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    private static int shiftColor(int color, float hueShift, float saturation, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + hueShift + 360f) % 360f;
        hsv[1] = Math.min(1f, hsv[1] * saturation);
        hsv[2] = Math.min(1f, hsv[2] * value);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    /** A custom badge emoji, tinted with the accent when it is a colourable emoji. */
    public static class BadgeIconView extends View {
        private AnimatedEmojiDrawable drawable;
        private PorterDuffColorFilter colorFilter;

        public BadgeIconView(Context context) {
            super(context);
        }

        public void set(int account, long documentId, int color) {
            if (drawable != null && isAttachedToWindow()) {
                drawable.removeView(this);
            }
            drawable = AnimatedEmojiDrawable.make(account, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_LARGE_140, documentId);
            colorFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            if (isAttachedToWindow()) {
                drawable.addView(this);
            }
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (drawable != null) {
                drawable.addView(this);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (drawable != null) {
                drawable.removeView(this);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawable == null) {
                return;
            }
            drawable.setBounds(0, 0, getWidth(), getHeight());
            boolean tint = drawable.canOverrideColor();
            if (tint) {
                drawable.setColorFilter(colorFilter);
            }
            drawable.draw(canvas);
            if (tint) {
                drawable.setColorFilter(null);
            }
        }
    }
}
