package org.telegram.rawgram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Top of the settings hub, as in exteraGram: the app's launcher icon in the launcher's icon shape, the app name
 * and a line under it. Sits on the screen background, outside the section cards.
 * Ported from exteraGram's HeaderSettingsCell (exteraSquad, GPL).
 */
public class RawSettingsHeaderCell extends LinearLayout {

    private static final int ICON_DP = 72;
    private static final int ICON_CORNER_DP = 18;

    private final TextView titleView;
    private final TextView subtitleView;

    public RawSettingsHeaderCell(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        // keep the list's section cards from wrapping this view
        setTag(RecyclerListView.TAG_NOT_SECTION);

        addView(new IconView(context, loadLauncherIcon(context)),
                LayoutHelper.createLinear(ICON_DP, ICON_DP, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 28, 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setGravity(Gravity.CENTER);
        addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 50, 16, 50, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitleView.setTypeface(AndroidUtilities.bold());
        subtitleView.setLineSpacing(dp(2), 1f);
        subtitleView.setGravity(Gravity.CENTER);
        addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 60, 2, 60, 28));

        updateColors();
    }

    public void setTexts(CharSequence title, CharSequence subtitle) {
        titleView.setText(title);
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.length() == 0 ? GONE : VISIBLE);
    }

    public void updateColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
    }

    /** The icon of the launcher entry that is enabled now (rawGram can switch between several). */
    private static Drawable loadLauncherIcon(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(context.getPackageName());
            if (launch != null && launch.getComponent() != null) {
                return pm.getActivityIcon(launch.getComponent());
            }
            return pm.getApplicationIcon(context.getPackageName());
        } catch (Exception e) {
            FileLog.e(e);
        }
        return ContextCompat.getDrawable(context, R.mipmap.ic_launcher);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    /** Draws the launcher icon's layers clipped to the launcher's icon shape. */
    private static class IconView extends View {
        private final Drawable icon;
        private Path shape;

        IconView(Context context, Drawable icon) {
            super(context);
            this.icon = icon == null ? null : icon.mutate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            shape = null;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (icon == null) {
                return;
            }
            if (shape == null) {
                shape = RawIconShape.get(ICON_DP, ICON_DP, ICON_CORNER_DP);
            }
            int size = getWidth();
            canvas.save();
            canvas.clipPath(shape);
            if (Build.VERSION.SDK_INT >= 26 && icon instanceof AdaptiveIconDrawable) {
                // adaptive layers are 108dp with the visible 72dp in the middle: a quarter of the size sticks out on each side
                AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
                int extra = size / 4;
                Drawable background = adaptive.getBackground();
                Drawable foreground = adaptive.getForeground();
                if (background != null) {
                    background.setBounds(-extra, -extra, size + extra, size + extra);
                    background.draw(canvas);
                }
                if (foreground != null) {
                    foreground.setBounds(-extra, -extra, size + extra, size + extra);
                    foreground.draw(canvas);
                }
            } else {
                icon.setBounds(0, 0, size, getHeight());
                icon.draw(canvas);
            }
            canvas.restore();
        }
    }
}
