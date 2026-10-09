package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/**
 * «Счётчик на кнопке «Назад»» (NekoX's unreadBadgeOnBackButton): the number of unread chats of the main list as a small
 * badge over the chat's back arrow, drawn in the back button's overlay.
 */
public final class RawBackBadge implements NotificationCenter.NotificationCenterDelegate {

    private final BaseFragment fragment;
    private final int account;
    private BadgeDrawable badge;
    private View attachedTo;
    private boolean observing;

    public RawBackBadge(BaseFragment fragment, int account) {
        this.fragment = fragment;
        this.account = account;
    }

    public void onResume() {
        if (!RawChatUiConfig.backBadge.get()) {
            detach();
            return;
        }
        if (!observing) {
            observing = true;
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogsUnreadCounterChanged);
        }
        update();
    }

    public void onDestroy() {
        if (observing) {
            observing = false;
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.dialogsUnreadCounterChanged);
        }
        detach();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsUnreadCounterChanged) {
            update();
        }
    }

    private void update() {
        ActionBar actionBar = fragment.getActionBar();
        View back = actionBar != null ? actionBar.getBackButton() : null;
        if (back == null) {
            return;
        }
        if (attachedTo != back) {
            detach();
            badge = new BadgeDrawable();
            back.getOverlay().add(badge);
            attachedTo = back;
        }
        badge.setBounds(0, 0, back.getWidth(), back.getHeight());
        badge.setCount(MessagesStorage.getInstance(account).getMainUnreadCount());
        if (back.getWidth() == 0) {
            back.post(() -> {
                if (badge != null && attachedTo != null) {
                    badge.setBounds(0, 0, attachedTo.getWidth(), attachedTo.getHeight());
                }
            });
        }
    }

    private void detach() {
        if (attachedTo != null && badge != null) {
            attachedTo.getOverlay().remove(badge);
        }
        attachedTo = null;
        badge = null;
    }

    private static final class BadgeDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String text;

        BadgeDrawable() {
            textPaint.setTextSize(dp(11.5f));
            textPaint.setTypeface(AndroidUtilities.bold());
        }

        void setCount(int count) {
            String t = count <= 0 ? null : count > 99 ? "99+" : Integer.toString(count);
            if (t == null ? text != null : !t.equals(text)) {
                text = t;
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (text == null || getBounds().width() == 0) {
                return;
            }
            paint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
            textPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));
            float textWidth = textPaint.measureText(text);
            float h = dp(18);
            float w = Math.max(h, textWidth + dp(10));
            float right = getBounds().right - dp(2);
            float top = getBounds().top + dp(4);
            rect.set(right - w, top, right, top + h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(text, rect.centerX() - textWidth / 2f, rect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
