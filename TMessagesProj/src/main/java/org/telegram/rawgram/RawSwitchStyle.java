package org.telegram.rawgram;

/*
 * "Modern" and Material 3 switch styles.
 * Ported from Nagram (org.telegram.ui.Components.Switch#drawCustomSwitch, SwitchStyle option), GPLv3:
 * Copyright (C) Nagram contributors. Monet colour harmonisation and the theme-change reveal overlay
 * are left out: while a reveal animation runs, the stock switch is drawn.
 */

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Switch;

public class RawSwitchStyle {

    private static final RectF rectF = new RectF();
    private static Paint fillPaint;
    private static Paint borderPaint;
    private static Paint crossPaint;
    private static Drawable checkDrawable;
    private static int checkColor = Integer.MIN_VALUE;

    public static boolean enabled() {
        return RawUiConfig.switchStyle() != RawUiConfig.SWITCH_DEFAULT;
    }

    /** Draws the switch in the selected custom style; false = draw the stock switch. */
    public static boolean draw(Switch view, Canvas canvas, float progress,
                               int trackColorKey, int trackCheckedColorKey, int thumbColorKey, int thumbCheckedColorKey,
                               Drawable iconDrawable, float iconVisibility, int drawIconType, Drawable rippleDrawable,
                               Theme.ResourcesProvider resourcesProvider) {
        int style = RawUiConfig.switchStyle();
        if (style == RawUiConfig.SWITCH_DEFAULT) {
            return false;
        }
        if (fillPaint == null) {
            fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeCap(Paint.Cap.ROUND);
            crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            crossPaint.setStyle(Paint.Style.STROKE);
            crossPaint.setStrokeCap(Paint.Cap.ROUND);
        }
        borderPaint.setStrokeWidth(AndroidUtilities.dp(1));

        final int width = AndroidUtilities.dp(36);
        final int x = (view.getMeasuredWidth() - width) / 2;
        final float y = (view.getMeasuredHeight() - AndroidUtilities.dpf2(14)) / 2;
        final int tx = x + AndroidUtilities.dp(7) + (int) (AndroidUtilities.dp(18) * progress);
        final int thumbTx = Utilities.clamp(tx, x + width + AndroidUtilities.dp(2), x + AndroidUtilities.dp(10));
        final int ty = view.getMeasuredHeight() / 2;

        final boolean isMd3 = style == RawUiConfig.SWITCH_MD3;
        final boolean isModern = style == RawUiConfig.SWITCH_MODERN;
        final boolean hasVisibleIcon = iconDrawable != null && iconVisibility > 0;
        final boolean isMd3PermissionStyle = isMd3 && trackColorKey == Theme.key_fill_RedNormal && (drawIconType == 1 || hasVisibleIcon);
        final boolean isModernPermissionStyle = isModern && trackColorKey == Theme.key_fill_RedNormal && drawIconType == 1;
        final boolean drawModernOffIcon = isModern && (hasVisibleIcon || isModernPermissionStyle);

        final int trackColor = view.rawProcessColor(Theme.getColor(trackColorKey, resourcesProvider));
        final int checkedColor = view.rawProcessColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));
        int md3OffTrackFill = 0;
        if (isMd3) {
            md3OffTrackFill = view.rawProcessColor(Theme.blendOver(
                    Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                    Theme.multAlpha(Theme.getColor(trackColorKey, resourcesProvider), Theme.isCurrentThemeDay() ? 0.2f : 0.1f)));
        }

        // track
        int fillOff = isMd3 ? (isMd3PermissionStyle ? trackColor : md3OffTrackFill) : Color.TRANSPARENT;
        fillPaint.setColor(lerpColor(fillOff, checkedColor, progress));
        rectF.set(x, y - AndroidUtilities.dpf2(3), x + width, y + AndroidUtilities.dpf2(17));
        canvas.drawRoundRect(rectF, AndroidUtilities.dpf2(15), AndroidUtilities.dpf2(15), fillPaint);
        borderPaint.setColor(lerpColor(trackColor, checkedColor, progress));
        canvas.drawRoundRect(rectF, AndroidUtilities.dpf2(15), AndroidUtilities.dpf2(15), borderPaint);

        if (iconDrawable != null && !isMd3 && !drawModernOffIcon) {
            iconDrawable.setColorFilter(new PorterDuffColorFilter(progress > 0.5f ? checkedColor : trackColor, PorterDuff.Mode.MULTIPLY));
        }

        if (rippleDrawable != null) {
            rippleDrawable.setBounds(thumbTx - AndroidUtilities.dp(18), ty - AndroidUtilities.dp(18), thumbTx + AndroidUtilities.dp(18), ty + AndroidUtilities.dp(18));
            rippleDrawable.draw(canvas);
        }

        // thumb
        int thumbOffKey = isMd3 && isMd3PermissionStyle ? thumbCheckedColorKey : trackColorKey;
        int thumbOff = Theme.getColor(thumbOffKey, resourcesProvider);
        int thumbOn = view.rawProcessColor(Theme.getColor(thumbCheckedColorKey, resourcesProvider));
        fillPaint.setColor(lerpColor(thumbOff, thumbOn, progress));
        float radius = AndroidUtilities.dp(isMd3 ? 8 : drawModernOffIcon ? 7 + progress : 6 + 2 * progress);
        canvas.drawCircle(thumbTx, ty, radius, fillPaint);

        if (isMd3) {
            int iconColor = isMd3PermissionStyle ? trackColor : md3OffTrackFill;
            if (hasVisibleIcon) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
                drawIcon(canvas, iconDrawable, thumbTx, ty, 0.8f, iconVisibility, (int) (255 * (1f - progress)));
            } else {
                drawCross(canvas, thumbTx, ty, iconColor, 1f - progress, 3);
            }
            Drawable check = checkDrawable();
            if (check != null) {
                int color = Theme.getColor(trackCheckedColorKey, resourcesProvider);
                if (checkColor != color) {
                    check.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                    checkColor = color;
                }
                int w = check.getIntrinsicWidth() / 2;
                int h = check.getIntrinsicHeight() / 2;
                check.setBounds(thumbTx - w / 2, ty - h / 2, thumbTx + w / 2, ty + h / 2);
                check.setAlpha((int) (255 * progress));
                check.draw(canvas);
            }
        } else if (drawModernOffIcon) {
            int iconColor = Theme.getColor(thumbColorKey, resourcesProvider);
            if (hasVisibleIcon) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
                drawIcon(canvas, iconDrawable, thumbTx, ty, 0.7f, iconVisibility, (int) (255 * (1f - progress)));
            } else {
                drawCross(canvas, thumbTx, ty, iconColor, 1f - progress, 2.5f);
            }
        }
        return true;
    }

    private static Drawable checkDrawable() {
        if (checkDrawable == null) {
            Drawable d = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.floating_check);
            if (d != null) {
                checkDrawable = d.mutate();
            }
        }
        return checkDrawable;
    }

    private static void drawIcon(Canvas canvas, Drawable drawable, int cx, int cy, float scale, float visibility, int alpha) {
        boolean needScale = visibility < 1;
        if (needScale) {
            canvas.save();
            canvas.scale(visibility, visibility, cx, cy);
        }
        int w = (int) (drawable.getIntrinsicWidth() * scale);
        int h = (int) (drawable.getIntrinsicHeight() * scale);
        drawable.setBounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        drawable.setAlpha(alpha);
        drawable.draw(canvas);
        drawable.setAlpha(255);
        if (needScale) {
            canvas.restore();
        }
    }

    private static void drawCross(Canvas canvas, int cx, int cy, int color, float alpha, float sizeDp) {
        crossPaint.setColor(color);
        crossPaint.setAlpha((int) (255 * alpha * Color.alpha(color) / 255f));
        crossPaint.setStrokeWidth(AndroidUtilities.dpf2(1.5f));
        int s = AndroidUtilities.dp(sizeDp);
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, crossPaint);
        canvas.drawLine(cx + s, cy - s, cx - s, cy + s, crossPaint);
    }

    private static int lerpColor(int color1, int color2, float progress) {
        int red = (int) (Color.red(color1) + (Color.red(color2) - Color.red(color1)) * progress);
        int green = (int) (Color.green(color1) + (Color.green(color2) - Color.green(color1)) * progress);
        int blue = (int) (Color.blue(color1) + (Color.blue(color2) - Color.blue(color1)) * progress);
        int alpha = (int) (Color.alpha(color1) + (Color.alpha(color2) - Color.alpha(color1)) * progress);
        return ((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
    }
}
