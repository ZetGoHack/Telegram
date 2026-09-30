package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

/**
 * The floating buttons rawGram draws above the inline results: "put aside" (parks the results in the
 * side tray button) and the reroll status bubble. Drawn and touched through MentionsContainerView.
 */
public class RawMentionsButtons {

    public interface Host {
        boolean isReversed();

        /** The visible panel's top (or bottom, when reversed) edge, in the view's coordinates. */
        float panelTop();

        float panelBottom();

        boolean isBotContext();

        /** Inline results are on screen (the "put aside" button makes sense). */
        boolean hasResults();

        int getThemedColor(int key);
    }

    private final View view;
    private final Host host;

    // reroll bubble; the last controller is kept while the bubble fades out
    private RawRerollController reroll, lastReroll;
    private RawRerollBubble bubble;
    private Runnable bubbleClick;
    private boolean bubbleTouch;
    private final AnimatedFloat bubbleAppear;

    // "put aside" button
    private Runnable onHide;
    private final RectF hideBounds = new RectF();
    private boolean hideTouch;
    private final AnimatedFloat hideAppear;
    private Paint hideBg, hideIcon;

    // both buttons leave to the right while the results are parked
    private long slideStart;

    public RawMentionsButtons(View view, Host host) {
        this.view = view;
        this.host = host;
        bubbleAppear = new AnimatedFloat(view, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        hideAppear = new AnimatedFloat(view, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
    }

    public void setBubble(RawRerollController controller, Runnable onClick) {
        reroll = controller;
        bubbleClick = onClick;
        if (controller != null) {
            lastReroll = controller;
            if (bubble == null) {
                bubble = new RawRerollBubble();
            }
        }
        view.invalidate();
    }

    public void setOnHide(Runnable onHide) {
        this.onHide = onHide;
        view.invalidate();
    }

    public void slideOut() {
        slideStart = SystemClock.elapsedRealtime();
        view.invalidate();
    }

    /** The results are shown again: the buttons come back in place. */
    public void reset() {
        slideStart = 0;
    }

    public void draw(Canvas canvas) {
        float slide = 0;
        if (slideStart != 0) {
            float t = Math.min(1f, (SystemClock.elapsedRealtime() - slideStart) / 300f);
            slide = CubicBezierInterpolator.EASE_IN.getInterpolation(t) * dp(150);
            if (t < 1f) {
                view.invalidate();
            }
        }
        canvas.save();
        canvas.translate(slide, 0);
        drawHide(canvas);
        drawBubble(canvas);
        canvas.restore();
    }

    private float buttonsCy(float size) {
        if (host.isReversed()) {
            return Math.min(view.getMeasuredHeight() - size / 2 - dp(4), host.panelBottom() + dp(10) + size / 2);
        }
        return Math.max(size / 2 + dp(4), host.panelTop() - dp(10) - size / 2);
    }

    private void drawHide(Canvas canvas) {
        boolean visible = onHide != null && host.hasResults();
        float appear = hideAppear.set(visible ? 1f : 0f);
        if (appear <= 0.01f) {
            hideBounds.setEmpty();
            return;
        }
        if (hideBg == null) {
            hideBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            hideBg.setShadowLayer(dp(3), 0, dp(1), 0x33000000);
            hideIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
            hideIcon.setStyle(Paint.Style.STROKE);
            hideIcon.setStrokeCap(Paint.Cap.ROUND);
            hideIcon.setStrokeJoin(Paint.Join.ROUND);
            hideIcon.setStrokeWidth(dp(2.2f));
        }
        float size = dp(38);
        float bubbleSize = dp(RawRerollBubble.SIZE_DP);
        float right = view.getMeasuredWidth() - dp(14);
        if (reroll != null) {
            right -= bubbleSize + dp(10);
        }
        float cx = right - size / 2;
        float cy = buttonsCy(bubbleSize);
        hideBounds.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        float s = (0.4f + 0.6f * appear) * (hideTouch ? 0.92f : 1f);
        canvas.save();
        canvas.scale(s, s, cx, cy);
        hideBg.setColor(host.getThemedColor(Theme.key_chat_messagePanelBackground));
        hideBg.setAlpha((int) (255 * appear));
        canvas.drawCircle(cx, cy, size / 2, hideBg);
        hideIcon.setColor(host.getThemedColor(Theme.key_featuredStickers_addButton));
        hideIcon.setAlpha((int) (255 * appear));
        // a tray with an arrow into it: "put these results aside"
        float w = dp(8);
        canvas.drawLine(cx - w, cy + dp(2), cx - w, cy + dp(7), hideIcon);
        canvas.drawLine(cx - w, cy + dp(7), cx + w, cy + dp(7), hideIcon);
        canvas.drawLine(cx + w, cy + dp(7), cx + w, cy + dp(2), hideIcon);
        canvas.drawLine(cx, cy - dp(8), cx, cy + dp(2), hideIcon);
        canvas.drawLine(cx - dp(4), cy - dp(2), cx, cy + dp(2), hideIcon);
        canvas.drawLine(cx + dp(4), cy - dp(2), cx, cy + dp(2), hideIcon);
        canvas.restore();
    }

    private void drawBubble(Canvas canvas) {
        float appear = bubbleAppear.set(reroll != null && host.isBotContext() ? 1f : 0f);
        if (bubble == null || lastReroll == null || appear <= 0.01f) {
            if (appear <= 0.01f && reroll == null) {
                lastReroll = null;
            }
            return;
        }
        float size = dp(RawRerollBubble.SIZE_DP);
        float cx = view.getMeasuredWidth() - dp(14) - size / 2;
        float cy = buttonsCy(size);
        canvas.save();
        canvas.scale(0.4f + 0.6f * appear, 0.4f + 0.6f * appear, cx, cy);
        canvas.saveLayerAlpha(cx - size, cy - size, cx + size, cy + size, (int) (255 * appear), Canvas.ALL_SAVE_FLAG);
        bubble.draw(canvas, view, cx, cy, lastReroll,
                host.getThemedColor(Theme.key_featuredStickers_addButton),
                host.getThemedColor(Theme.key_chat_messagePanelBackground),
                host.getThemedColor(Theme.key_featuredStickers_addButton));
        canvas.restore();
        canvas.restore();
    }

    /** Returns true if the touch belongs to one of the buttons. */
    public boolean onTouch(MotionEvent ev) {
        if (onHide != null && !hideBounds.isEmpty()) {
            boolean inside = hideBounds.contains(ev.getX(), ev.getY());
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && inside) {
                hideTouch = true;
                view.invalidate();
                return true;
            }
            if (hideTouch) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    hideTouch = false;
                    view.invalidate();
                    if (action == MotionEvent.ACTION_UP && inside) {
                        onHide.run();
                    }
                }
                return true;
            }
        }
        if (reroll != null && bubble != null && host.isBotContext()) {
            boolean inside = bubble.bounds.contains(ev.getX(), ev.getY());
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && inside) {
                bubbleTouch = true;
                bubble.setPressed(true);
                view.invalidate();
                return true;
            }
            if (bubbleTouch) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    bubbleTouch = false;
                    bubble.setPressed(false);
                    view.invalidate();
                    if (action == MotionEvent.ACTION_UP && inside && bubbleClick != null) {
                        bubbleClick.run();
                    }
                }
                return true;
            }
        }
        return false;
    }
}
