package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;

/**
 * "Classic chat look": full-width solid action bar, attached pinned bar and a full-width
 * input panel instead of the floating glass pills. Read once per ChatActivity.createView,
 * so toggling it applies to chats opened afterwards.
 */
public class RawClassicUi {

    private static final String KEY_ENABLED = "enabled";
    private static Boolean enabled;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("rawgram_classic", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        if (enabled == null) {
            try {
                enabled = prefs().getBoolean(KEY_ENABLED, false);
            } catch (Throwable t) {
                return false;
            }
        }
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        prefs().edit().putBoolean(KEY_ENABLED, value).apply();
    }

    public static boolean isLight(int color) {
        return ColorUtils.calculateLuminance(color | 0xff000000) > 0.7f;
    }

    private static int color(int key, Theme.ResourcesProvider resourcesProvider) {
        return Theme.getColor(key, resourcesProvider);
    }

    /* Action bar */

    /** Replaces ActionBar.setupGlass for the chat header: solid background, classic layout. */
    public static void setupChatActionBar(ActionBar actionBar, Theme.ResourcesProvider resourcesProvider, boolean report) {
        actionBar.setBackgroundColor(color(report ? Theme.key_actionBarActionModeDefault : Theme.key_actionBarDefault, resourcesProvider));
        actionBar.setClipChildren(false);
    }

    private static Drawable headerShadow;

    /** Header shadow under the action bar, or under the attached pinned/top panel when it is shown. */
    public static void drawHeaderShadow(Canvas canvas, View parent, ActionBar actionBar, ChatActivityTopPanelLayout topPanel) {
        if (actionBar == null || actionBar.getVisibility() != View.VISIBLE || actionBar.getAlpha() <= 0) {
            return;
        }
        if (headerShadow == null) {
            headerShadow = ContextCompat.getDrawable(parent.getContext(), R.drawable.header_shadow);
            if (headerShadow == null) {
                return;
            }
            headerShadow = headerShadow.mutate();
        }
        float y = actionBar.getY() + actionBar.getMeasuredHeight();
        if (topPanel != null && topPanel.getVisibility() == View.VISIBLE) {
            final float panelBottom = topPanel.getY() + topPanel.getAnimatedHeightWithPadding();
            if (panelBottom > y) {
                y += (panelBottom - y) * topPanel.getAlpha();
            }
        }
        final int top = (int) y;
        headerShadow.setAlpha((int) (255 * actionBar.getAlpha()));
        headerShadow.setBounds(0, top, parent.getMeasuredWidth(), top + headerShadow.getIntrinsicHeight());
        headerShadow.draw(canvas);
    }

    /* Pinned / top panel */

    private static final Paint topPanelPaint = new Paint();

    public static void drawTopPanelBackground(Canvas canvas, View panel, float bgHeight, float visibility, Theme.ResourcesProvider resourcesProvider) {
        if (visibility <= 0 || bgHeight <= 0) {
            return;
        }
        topPanelPaint.setColor(color(Theme.key_chat_topPanelBackground, resourcesProvider));
        topPanelPaint.setAlpha((int) (topPanelPaint.getAlpha() * Math.min(1f, visibility)));
        canvas.drawRect(panel.getPaddingLeft(), 0, panel.getMeasuredWidth() - panel.getPaddingRight(),
            panel.getPaddingTop() + panel.getPaddingBottom() + bgHeight, topPanelPaint);
    }

    /* Input panel */

    /** Per-container state for the classic full-width input panel. */
    public static class InputPanel {
        public static final int EXTRA_TOP = 4;

        private final Theme.ResourcesProvider resourcesProvider;
        private final Paint paint = new Paint();
        private float panelTop = Float.MAX_VALUE;

        public InputPanel(Theme.ResourcesProvider resourcesProvider) {
            this.resourcesProvider = resourcesProvider;
        }

        /**
         * @param bubbleTop    top of the (invisible) input island
         * @param bubbleHeight island height, nothing is drawn when it is empty
         * @param keyboardTop  top of the in-app keyboard area, or -1 when it is not shown
         * @param alpha        island alpha (0 while the selection actions replace the input)
         */
        public void draw(Canvas canvas, View container, float bubbleTop, float bubbleHeight, float keyboardTop, int alpha, boolean drawInput) {
            final int w = container.getMeasuredWidth();
            final int h = container.getMeasuredHeight();
            panelTop = Float.MAX_VALUE;
            if (drawInput && alpha > 0 && bubbleHeight > 0) {
                final float top = bubbleTop - dp(EXTRA_TOP);
                panelTop = top;
                final float a = alpha / 255f;
                final Drawable shadow = Theme.chat_composeShadowDrawable;
                if (shadow != null) {
                    final int sh = shadow.getIntrinsicHeight();
                    shadow.setAlpha(alpha);
                    shadow.setBounds(0, (int) top - sh, w, (int) top);
                    shadow.draw(canvas);
                }
                paint.setColor(color(Theme.key_chat_messagePanelBackground, resourcesProvider) | 0xff000000);
                paint.setAlpha((int) (255 * a));
                canvas.drawRect(0, top, w, h, paint);
            }
            if (keyboardTop >= 0 && keyboardTop < h) {
                paint.setColor(color(Theme.key_chat_emojiPanelBackground, resourcesProvider) | 0xff000000);
                canvas.drawRect(0, keyboardTop, w, h, paint);
            }
        }

        public boolean contains(float y) {
            return y >= panelTop;
        }
    }

    /** Glass icon color of the input panel mapped to the classic one. */
    public static int mapInputIconKey(int key) {
        return key == Theme.key_glass_defaultIcon ? Theme.key_chat_messagePanelIcons : key;
    }
}
