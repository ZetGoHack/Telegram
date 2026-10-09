package org.telegram.rawgram.drawer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;

/**
 * The accent unread counter at the end of an account row; shown only with more than one account and when the
 * account's notification settings show the badge number. Ported from exteraGram's drawer (exteraSquad, GPL).
 */
final class RawDrawerAccountUnreadBadge {

    private final RectF rect = new RectF();
    private int account = -1;
    private boolean visible;
    private String text;
    private int textWidth;
    private int badgeWidth;

    void bind(int account, SimpleTextView nameView) {
        this.account = account;
        update(nameView);
    }

    void update(SimpleTextView nameView) {
        visible = false;
        text = null;
        textWidth = 0;
        badgeWidth = 0;
        if (account < 0 || UserConfig.getActivatedAccountsCount() <= 1 || !NotificationsController.getInstance(account).showBadgeNumber) {
            nameView.setRightPadding(0);
            return;
        }
        int count = MessagesStorage.getInstance(account).getMainUnreadCount();
        if (count <= 0) {
            nameView.setRightPadding(0);
            return;
        }
        visible = true;
        text = Integer.toString(count);
        textWidth = (int) Math.ceil(Theme.dialogs_countTextPaint.measureText(text));
        badgeWidth = Math.max(dp(10), textWidth) + dp(14);
        nameView.setRightPadding(badgeWidth + dp(12));
    }

    void draw(View view, Canvas canvas) {
        if (!visible) {
            return;
        }
        float height = dp(23);
        float top = (view.getMeasuredHeight() - height) / 2f;
        float left = view.getMeasuredWidth() - dp(12.5f) - badgeWidth;
        rect.set(left, top, left + badgeWidth, top + height);
        canvas.drawRoundRect(rect, dp(11.5f), dp(11.5f), Theme.dialogs_countPaint);
        float baseline = rect.centerY() - (Theme.dialogs_countTextPaint.descent() + Theme.dialogs_countTextPaint.ascent()) / 2f;
        canvas.drawText(text, rect.left + (rect.width() - textWidth) / 2f, baseline, Theme.dialogs_countTextPaint);
    }
}
